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
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.gaten.app.data.BitmapRepository
import com.gaten.app.media.MediaPauser
import com.gaten.app.trigger.FloatingTriggerButton
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.overlay.OverlayWindowController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * How long a trigger from the Quick Settings tile waits for the shade to collapse before capturing.
 * The service does not ask for the window list (flagRetrieveInteractiveWindows), so it cannot watch
 * the shade go and waits a fixed time long enough for a normal collapse instead.
 */
private const val ShadeCollapseMs = 400L

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
     * The ordered trigger pipeline: screenshot the screen, and only then pause the media. The
     * screenshot is the freeze: the overlay shows it on top of the app, so whatever the player does
     * once it pauses - raising its controls, moving its subtitles - happens underneath and is never
     * seen. Nothing on screen needs to settle first, so the capture runs at once unless something of
     * our own is still in the way: the collapsing shade after [fromShade], or the floating button.
     */
    private fun startOrderedCapture(fromShade: Boolean) {
        Log.d(TAG, "Starting the ordered capture pipeline (fromShade=$fromShade)")
        val capture = { performCapture { mediaPauser.pause() } }
        val button = floatingButton
        when {
            // The shade wait outlasts the button's removal, so a plain hide is enough there.
            fromShade -> {
                button?.hide()
                mainHandler.postDelayed(capture, ShadeCollapseMs)
            }
            // The capture is what the user sees frozen, so the button must be off screen first.
            button != null -> button.hideThen(capture)
            else -> capture()
        }
    }

    /**
     * Freezes the current screen and opens the lookup overlay. [onCaptured] runs once the screenshot
     * is stored - the point from which the screen may safely change underneath the overlay. A
     * capture that fails brings the floating button back, since no overlay will.
     *
     * `takeScreenshot` respects content protection, so surfaces marked FLAG_SECURE (DRM video)
     * come back blank; Gaten never tries to bypass that.
     */
    private fun performCapture(onCaptured: () -> Unit = {}) {
        Log.d(TAG, "performCapture called")

        // Clear the repository at the source so a stale capture can never flash.
        BitmapRepository.clear()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            captureInFlight = false
            syncFloatingButton()
            return
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (deliverScreenshot(screenshot)) {
                        onCaptured()
                    } else {
                        mainHandler.post { syncFloatingButton() }
                    }
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    mainHandler.post { syncFloatingButton() }
                }
            }
        )
    }

    /**
     * Stores [screenshot] in [BitmapRepository] and puts the overlay on screen, releasing the
     * screenshot's hardware buffer. Every capture path ends here, so `captureInFlight` is cleared
     * here too: the screenshot has resolved and a fresh trigger may start a new pipeline. True when
     * the capture was stored and the overlay is on its way.
     */
    private fun deliverScreenshot(screenshot: ScreenshotResult): Boolean {
        captureInFlight = false
        // Never let a stale capture flash if this one cannot be stored.
        BitmapRepository.clear()
        try {
            val hardwareBuffer = screenshot.hardwareBuffer
            val colorSpace = screenshot.colorSpace

            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
            if (bitmap == null) {
                hardwareBuffer.close()
                return false
            }

            // Copy to a software bitmap, then release the hardware buffer.
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            hardwareBuffer.close()
            if (copy == null) return false

            BitmapRepository.setScreenshot(copy)
            // takeScreenshot answers on the executor; the overlay is a window and belongs to the
            // main thread.
            Handler(Looper.getMainLooper()).post { showOverlay() }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare the captured screenshot", e)
            return false
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
         * ignored. The accepted trigger runs the ordered pipeline on the main thread. [fromShade]
         * is for the Quick Settings tile, whose shade is still collapsing when this is called.
         */
        fun triggerCapture(fromShade: Boolean = false) {
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
                service.startOrderedCapture(fromShade)
            }
        }
    }
}
