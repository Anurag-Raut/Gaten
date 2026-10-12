/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.gaten.app.data.BitmapRepository
import com.gaten.app.media.MediaPauser
import com.gaten.app.trigger.FloatingTriggerButton
import com.gaten.app.trigger.FrameStability
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.overlay.OverlayWindowController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** SystemUI owns the notification shade; its window is what the trigger pipeline waits out. */
private const val SYSTEMUI_PACKAGE = "com.android.systemui"

/** How long [GatenAccessibilityService] waits for the shade to collapse before capturing anyway. */
private const val ShadeWaitMs = 1200L

/** How often the shade wait re-reads the window list. */
private const val ShadePollMs = 50L

/** Used when the window list is unavailable: a fixed delay long enough for a normal shade collapse. */
private const val ShadeFallbackMs = 400L

/**
 * How long to wait after a pause before the screenshot. A player's control bar animates in when
 * playback stops and pushes the subtitles up, so a capture taken at the instant of the pause reads
 * a frame the player is still moving.
 */
private const val ControlsSettleMs = 250L

/**
 * How long to wait between the frame-stability probes that follow the settle floor. Android
 * throttles `AccessibilityService.takeScreenshot` to about one call per 333 ms and answers a
 * quicker retry with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`, so the gap is a little over that:
 * short enough to keep the probe count low, long enough that a probe is not throttled away.
 */
private const val FrameProbeIntervalMs = 350L

/**
 * The probe loop's share of the 1000 ms capture budget: [ControlsSettleMs] of it is already spent
 * on the settle floor by the time the first probe runs, so the loop gets `1000 - ControlsSettleMs`,
 * i.e. 750 ms. The floor and this budget together are the whole cap, measured from the pause.
 */
private const val FrameStabilityBudgetMs = 1000L - ControlsSettleMs

/** Width the probe screenshots are downsampled to before being compared. */
private const val FrameProbeWidthPx = 96

/**
 * Gaten' only use of the accessibility framework: screenshots the screen when a lookup is
 * triggered, stores the bitmap in [BitmapRepository] and draws the lookup overlay in a window of
 * its own — [OverlayWindowController], not an activity, so the app behind keeps playing.
 */
class GatenAccessibilityService : AccessibilityService() {

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayWindowController
    private lateinit var mediaPauser: MediaPauser
    private var floatingButton: FloatingTriggerButton? = null
    /** True once the system has connected the service; only then may a window be added. */
    private var serviceConnected = false
    /** True from the moment a trigger is accepted until its capture resolves; gates new triggers. */
    @Volatile
    private var captureInFlight = false

    override fun onCreate() {
        super.onCreate()
        mediaPauser = MediaPauser(this)
        overlay = OverlayWindowController(this, mediaPauser) { syncFloatingButton() }
        // No window may be added here: an AccessibilityService has no valid window token until the
        // system connects it, so the floating button is shown from onServiceConnected instead.
        instance = this
    }

    /**
     * The first point at which this service may add a window: its window token only exists once the
     * system has connected it. Showing the button from onCreate threw BadTokenException and killed
     * the process before the service could ever bind, so this call must stay here.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        syncFloatingButton()
    }

    /** A service that loses its binding must leave no window behind. */
    override fun onUnbind(intent: Intent?): Boolean {
        // Unbound means no window token again, so nothing may be shown until reconnection.
        serviceConnected = false
        floatingButton?.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // The window belongs to this service: without this it would stay on screen over everything
        // after the service goes away. hide() re-shows the button; take it down again after.
        overlay.hide()
        floatingButton?.hide()
        instance = null
    }

    /**
     * Shows the floating trigger when the user has it enabled and it is not already up, hides it
     * otherwise. A no-op when the preference is unchanged and the button already matches it.
     */
    fun syncFloatingButton() {
        // Before onServiceConnected there is no window token: adding the button now would throw
        // BadTokenException, so the attempt is deferred to that callback.
        if (!serviceConnected) {
            Log.d(TAG, "Service not connected yet - not showing the floating button")
            return
        }
        if (TriggerPreferences.floatingButtonEnabled(this)) {
            if (floatingButton?.isShowing == true) return
            val button = floatingButton
                ?: FloatingTriggerButton(this) { triggerCapture() }.also { floatingButton = it }
            try {
                button.show()
            } catch (e: Exception) {
                // Adding a window can fail (no valid token yet); a button that cannot be shown is
                // no reason to take the whole service - and every trigger with it - down.
                Log.w(TAG, "Could not show the floating button", e)
            }
        } else {
            floatingButton?.hide()
        }
    }

    /**
     * The ordered trigger pipeline: wait out the notification shade, pause media, let the player's
     * controls settle, then capture once the frame itself stops changing. Capturing after the
     * pause is what keeps every recognised box on the frame the user actually saw.
     */
    private fun startOrderedCapture() {
        Log.d(TAG, "Starting the ordered capture pipeline")
        waitForShadeGone {
            mediaPauser.pause {
                // The 1000 ms cap runs from the pause, so the clock starts here, on the far side of
                // MediaPauser, and the settle floor is charged against it from the same instant.
                val playbackStoppedAt = SystemClock.uptimeMillis()
                mainHandler.postDelayed({
                    executor.execute {
                        probeFrameOrCapture(playbackStoppedAt, null, 0)
                    }
                }, ControlsSettleMs)
            }
        }
    }

    /** The instant the pipeline must have captured by: the whole 1000 ms cap after the pause. */
    private fun captureDeadline(playbackStoppedAt: Long) =
        playbackStoppedAt + ControlsSettleMs + FrameStabilityBudgetMs

    /**
     * One step of the probe loop: screenshots the screen and compares the shot with [reference], the
     * frame taken before it (null for the first probe, which runs as soon as the settle floor is
     * over). Two shots that differ by less than [FrameStability.DEFAULT_THRESHOLD] mean the player
     * has stopped re-laying its controls, so that frame itself becomes the capture; otherwise the loop
     * goes round again while [captureDeadline] allows. A player like YouTube animates its captions
     * in after the pause, and a capture taken mid-animation would bake boxes onto a frame the user
     * never saw.
     *
     * Runs on [executor] — the thread `takeScreenshot` answers on — so consecutive probes chain
     * through their callbacks and the wait between them is a plain [Thread.sleep], exactly like in
     * [MediaPauser]. Probes never touch `captureInFlight` or [BitmapRepository]: only the real
     * capture does. When no further probe fits before the deadline, the latest probe frame is the capture
     * ("display no matter what") rather than making the user wait for a frame that never settles.
     * It is the probe's own full-size screenshot, never a second one: `takeScreenshot` answers a
     * call within ~333 ms of the previous one with an error, which would lose the capture.
     */
    private fun probeFrameOrCapture(playbackStoppedAt: Long, reference: Bitmap?, probes: Int) {
        // `takeScreenshot` only exists from API 30. Older devices get the plain old capture (which
        // clears `captureInFlight` itself) rather than a NoSuchMethodError that would leave every
        // later trigger blocked on a capture that never resolved.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reference?.recycle()
            performCapture()
            return
        }

        val deadline = captureDeadline(playbackStoppedAt)
        if (!FrameStability.shouldProbeAgain(SystemClock.uptimeMillis(), deadline)) {
            reference?.recycle()
            Log.w(
                TAG,
                "Frame still changing after ${SystemClock.uptimeMillis() - playbackStoppedAt}ms" +
                    " (probes: $probes) - capturing anyway"
            )
            performCapture()
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val probe = toProbeBitmap(screenshot)
                    if (probe == null) {
                        // Unreadable for comparing, but still the freshest frame there is.
                        reference?.recycle()
                        Log.w(TAG, "Frame probe unreadable - capturing it anyway")
                        deliverScreenshot(screenshot)
                        return
                    }

                    val total = probes + 1
                    val diff = reference?.let { meanAbsoluteDifference(it, probe) }
                    reference?.recycle()
                    if (diff != null && FrameStability.isStable(diff)) {
                        probe.recycle()
                        Log.d(
                            TAG,
                            "Frame stable after ${SystemClock.uptimeMillis() - playbackStoppedAt}ms" +
                                " (probes: $total)"
                        )
                        deliverScreenshot(screenshot)
                        return
                    }

                    // Still moving (or the first probe, with nothing to compare against). Another
                    // probe is only worth taking if it can run a full gap from now and still land
                    // before the deadline; otherwise this frame is the capture, which also keeps the
                    // real capture clear of the screenshot throttle.
                    val now = SystemClock.uptimeMillis()
                    val nextProbeAt = now + FrameProbeIntervalMs
                    if (!FrameStability.shouldProbeAgain(nextProbeAt, captureDeadline(playbackStoppedAt))) {
                        probe.recycle()
                        Log.w(
                            TAG,
                            "Frame still changing after ${now - playbackStoppedAt}ms" +
                                " (probes: $total) - capturing anyway"
                        )
                        deliverScreenshot(screenshot)
                        return
                    }

                    screenshot.hardwareBuffer.close()
                    probeAfterGap(playbackStoppedAt, probe, total)
                }

                override fun onFailure(errorCode: Int) {
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        // Android throttled this probe away rather than failing it, so the same step
                        // is retried after the interval: the reference frame is kept, no probe is
                        // counted twice and no bitmap is orphaned. The retry runs out of budget like
                        // any other step, so a persistent throttle still ends in a capture.
                        probeAfterGap(playbackStoppedAt, reference, probes)
                        return
                    }
                    reference?.recycle()
                    Log.w(TAG, "Frame probe failed with error code: $errorCode - capturing anyway")
                    performCapture()
                }
            }
        )
    }

    /**
     * Waits out the [FrameProbeIntervalMs] gap since the probe that just finished, then takes the
     * next step of the loop.
     *
     * The wait is the shorter of the gap and what the budget has left, never a one-gap overshoot:
     * it ends at [captureDeadline] instead of crossing it, and [probeFrameOrCapture] then finds the
     * budget spent and captures. The gap is what keeps `takeScreenshot` from being throttled, and it
     * is measured here — from the completion of the previous probe — not from the trigger.
     */
    private fun probeAfterGap(playbackStoppedAt: Long, reference: Bitmap?, probes: Int) {
        val remainingMs = captureDeadline(playbackStoppedAt) - SystemClock.uptimeMillis()
        val waitMs = minOf(FrameProbeIntervalMs, remainingMs)
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        probeFrameOrCapture(playbackStoppedAt, reference, probes)
    }

    /**
     * Downsamples a screenshot to a [FrameProbeWidthPx]-wide software bitmap for comparison. The
     * screenshot's hardware buffer stays open: the caller either captures the screenshot or closes it.
     */
    private fun toProbeBitmap(screenshot: ScreenshotResult): Bitmap? {
        val hardware = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
            ?: return null
        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        if (software == null) return null
        val height = ((software.height.toLong() * FrameProbeWidthPx / software.width))
            .coerceAtLeast(1L).toInt()
        val probe = Bitmap.createScaledBitmap(software, FrameProbeWidthPx, height, true)
        if (probe !== software) software.recycle()
        return probe
    }

    /**
     * Reads two bitmaps into ARGB pixel arrays and hands them to [FrameStability], which owns the
     * arithmetic (and is unit tested). Frames of different sizes come back as [Float.MAX_VALUE],
     * read as "the frame changed".
     */
    private fun meanAbsoluteDifference(a: Bitmap, b: Bitmap): Float {
        val aPixels = IntArray(a.width * a.height)
        val bPixels = IntArray(b.width * b.height)
        a.getPixels(aPixels, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(bPixels, 0, b.width, 0, 0, b.width, b.height)
        return FrameStability.meanAbsoluteDifference(aPixels, bPixels)
    }

    /**
     * Runs [onGone] once no window belongs to SystemUI, i.e. the shade has finished collapsing.
     *
     * Polls `windows` (the accessibility window list) every [ShadePollMs] for up to [ShadeWaitMs].
     * A list that is empty or throws names no shade window to wait out, so it falls back to the
     * fixed [ShadeFallbackMs] delay instead.
     */
    private fun waitForShadeGone(onGone: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + ShadeWaitMs
        val poll = object : Runnable {
            override fun run() {
                val openWindows = try {
                    windows
                } catch (e: Exception) {
                    Log.w(TAG, "Window list unavailable - fixed ${ShadeFallbackMs}ms shade wait", e)
                    mainHandler.postDelayed({ onGone() }, ShadeFallbackMs)
                    return
                }
                if (openWindows.isNullOrEmpty()) {
                    Log.d(TAG, "Window list empty - fixed ${ShadeFallbackMs}ms shade wait")
                    mainHandler.postDelayed({ onGone() }, ShadeFallbackMs)
                    return
                }
                // Only a SystemUI window means the shade is (still) up; the status bar it also owns
                // is not in this list once the shade has collapsed on the devices this targets.
                if (openWindows.none { it.root?.packageName == SYSTEMUI_PACKAGE }) {
                    Log.d(TAG, "Notification shade is gone - capturing")
                    onGone()
                    return
                }
                if (SystemClock.uptimeMillis() >= deadline) {
                    Log.d(TAG, "Shade still up after ${ShadeWaitMs}ms - capturing anyway")
                    onGone()
                    return
                }
                mainHandler.postDelayed(this, ShadePollMs)
            }
        }
        mainHandler.post(poll)
    }

    /**
     * Freezes the current screen and opens the lookup overlay.
     *
     * `takeScreenshot` respects content protection, so surfaces marked FLAG_SECURE (DRM video)
     * come back blank; Gaten never tries to bypass that.
     */
    private fun performCapture() {
        Log.d(TAG, "performCapture called")

        // Clear the repository at the source so a stale capture can never flash.
        BitmapRepository.clear()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            captureInFlight = false
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    deliverScreenshot(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                }
            }
        )
    }

    /**
     * Stores [screenshot] in [BitmapRepository] and puts the overlay on screen, releasing the
     * screenshot's hardware buffer. Every capture path ends here, so `captureInFlight` is cleared
     * here too: the screenshot has resolved and a fresh trigger may start a new pipeline.
     */
    private fun deliverScreenshot(screenshot: ScreenshotResult) {
        captureInFlight = false
        // Never let a stale capture flash if this one cannot be stored.
        BitmapRepository.clear()
        try {
            val hardwareBuffer = screenshot.hardwareBuffer
            val colorSpace = screenshot.colorSpace

            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (bitmap == null) {
                hardwareBuffer.close()
                return
            }

            // Copy to a software bitmap, then release the hardware buffer.
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBuffer.close()
            if (copy == null) return

            BitmapRepository.setScreenshot(copy)
            // takeScreenshot answers on the executor; the overlay is a window and belongs to the
            // main thread.
            Handler(Looper.getMainLooper()).post { showOverlay() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare the captured screenshot", e)
        }
    }

    /**
     * Puts the capture the service just stored on screen.
     *
     * An activity used to do this and took the foreground, which stopped whatever was behind it;
     * an accessibility overlay window leaves the app behind foreground instead.
     */
    private fun showOverlay() {
        val bitmap = BitmapRepository.getScreenshot()
        if (bitmap == null) {
            Log.e(TAG, "No bitmap in Repository - not showing the overlay")
            return
        }
        Log.d(TAG, "Bitmap loaded from Repository. Size: ${bitmap.width}x${bitmap.height}")
        // The button must never sit on top of the frozen capture; the overlay's onHidden re-shows it.
        floatingButton?.hide()
        overlay.show(bitmap)
    }

    /** Nothing to observe: a lookup is always started explicitly from outside this service. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "Gaten"

        var instance: GatenAccessibilityService? = null
            private set

        /**
         * Entry point for the Quick Settings tile, the trigger broadcast and the floating button.
         *
         * Acts as a toggle and is single-flight: a trigger while the panel is up closes it instead
         * of starting another lookup, and a trigger while a capture is already in flight is
         * ignored. The accepted trigger runs the ordered pipeline on the main thread.
         */
        fun triggerCapture() {
            val service = instance
            if (service == null) {
                Log.d(TAG, "triggerCapture called but the accessibility service is not connected")
                return
            }
            // The toggle touches views; the whole decision belongs on the main thread.
            service.mainHandler.post {
                if (service.overlay.hideIfShowing()) {
                    Log.d(TAG, "Lookup panel was open - closing it")
                    return@post
                }
                if (service.captureInFlight) {
                    Log.d(TAG, "Capture already in flight - ignoring the new trigger")
                    return@post
                }
                service.captureInFlight = true
                service.startOrderedCapture()
            }
        }
    }
}
