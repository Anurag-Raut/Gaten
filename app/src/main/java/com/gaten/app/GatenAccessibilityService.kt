/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.gaten.app.data.BitmapRepository
import com.gaten.app.lifecycle.CaptureToken
import com.gaten.app.lifecycle.DisplayGeometry
import com.gaten.app.lifecycle.DisplayOrientation
import com.gaten.app.lifecycle.LookupDismissal
import com.gaten.app.lifecycle.NotificationEvent
import com.gaten.app.lifecycle.WindowEvent
import com.gaten.app.lifecycle.classifyNotificationEvent
import com.gaten.app.lifecycle.classifyWindowEvent
import com.gaten.app.lifecycle.displayChanged
import com.gaten.app.lifecycle.lookupAdmitted
import com.gaten.app.media.MediaPauser
import com.gaten.app.trigger.FloatingTriggerButton
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.overlay.OverlayWindowController
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
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
 *
 * A lookup is only ever useful on the screen the user is looking at, so the service watches for the
 * ways that stops being true: the screen going off or the keyguard locking, a call, a firing alarm
 * or the power menu arriving, the display rotating under a frozen frame, and the service itself
 * being unbound or destroyed. Those dismissals end the lookup without playing the media back,
 * because the media was paused for a lookup that is over; the user's own dismissals — back, the
 * scrim, the trigger toggle — resume it as before.
 *
 * Every capture runs under a [CaptureToken], which is also the single-flight gate: the screenshot
 * answers on an executor while the trigger, the window and the calls that end it all live on the
 * main thread, so a step that resolves after the lookup was dismissed (or replaced) is dropped and
 * cannot clear a newer trigger's state.
 */
class GatenAccessibilityService : AccessibilityService() {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    // The platform can deliver a result after service teardown. Main-thread delivery still drains
    // and closes that result after the image-copy executor has been shut down.
    private val screenshotCallbacks = Executor { callback -> mainHandler.post(callback) }
    private lateinit var overlay: OverlayWindowController
    private lateinit var mediaPauser: MediaPauser
    private var floatingButton: FloatingTriggerButton? = null
    /** True once the system has connected the service; only then may a window be added. */
    private var serviceConnected = false
    /** True while the screen receiver is registered, so it is unregistered exactly once. */
    private var receiverRegistered = false
    private var displayListenerRegistered = false
    /** The capture in flight and its single-flight flag, moved on by a dismissal or a new trigger. */
    private val captureToken = CaptureToken()
    /** The display the capture was accepted on, to notice a rotation while it is still pending. */
    private var pendingGeometry: DisplayGeometry? = null
    /** The display the overlay was shown on, to notice a rotation under the frozen frame. */
    private var overlayGeometry: DisplayGeometry? = null
    /** The foreground app before a capture; SystemUI and our overlay do not replace it. */
    private var foregroundPackage: String? = null
    private var capturePackage: String? = null

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                cancelLookup()
                syncFloatingButton()
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) checkDisplayGeometry(resources.configuration)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off - ending any lookup")
                    // The token is aborted and the overlay closed before anything else, and nothing
                    // new may start while the screen is off: the button goes down with it.
                    cancelLookup()
                    floatingButton?.hide()
                }

                // The screen coming back is not necessarily an unlock, so this is only a chance to
                // put the button back where the state allows it; USER_PRESENT is the reliable one.
                Intent.ACTION_SCREEN_ON -> syncFloatingButton()

                // User present means the keyguard was dismissed: that is an unlock, never a lock, so
                // it only restores the button — never the lookup that a lock took down.
                Intent.ACTION_USER_PRESENT -> syncFloatingButton()
            }
        }
    }

    /** Creates the window and media owners; windows remain unavailable until the service connects. */
    override fun onCreate() {
        super.onCreate()
        mediaPauser = MediaPauser(this)
        overlay = OverlayWindowController(
            this,
            mediaPauser,
            // onHidden: the capture is over and the overlay is down; the in-flight token ends here,
            // so the next trigger is accepted, and the button comes back where the state allows.
            onHidden = { token ->
                captureToken.complete(token)
                BitmapRepository.clear()
                overlayGeometry = null
                syncFloatingButton()
            },
            // onAutoDismiss: the overlay closed itself, so the capture behind it must go stale even
            // if its screenshot is still on its way.
            onAutoDismiss = { captureToken.abort() }
        )
        // No window may be added here: an AccessibilityService has no valid window token until the
        // system connects it, so the floating button is shown from onServiceConnected instead.
        instance = this
    }

    /**
     * The first point at which this service may add a window: its window token only exists once the
     * system has connected it. Showing the button from onCreate threw BadTokenException and killed
     * the process before the service could ever bind, so this call must stay here.
     *
     * The screen broadcasts are registered here rather than in the manifest: they are sent to
     * registered receivers, and the service is already running whenever a lookup is.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        registerScreenReceiver()
        if (!displayListenerRegistered) {
            displayManager.registerDisplayListener(displayListener, mainHandler)
            displayListenerRegistered = true
        }
        syncFloatingButton()
    }

    /**
     * A service that loses its binding must leave no window behind, and must never resume the media
     * for a lookup the user is no longer in: the dismissal is automatic, so the playback stays for
     * whatever took the screen.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        // Down before anything is hidden: the overlay's own onHidden is then the teardown path, and
        // a service that is on its way out can never re-show the button.
        serviceConnected = false
        unregisterScreenReceiver()
        unregisterDisplayListener()
        cancelLookup()
        floatingButton?.hide()
        return super.onUnbind(intent)
    }

    /** Invalidates callbacks, removes windows and releases observers and worker threads. */
    override fun onDestroy() {
        super.onDestroy()
        // No window may be added after this point, so the flag goes first.
        serviceConnected = false
        unregisterScreenReceiver()
        unregisterDisplayListener()
        cancelLookup()
        // The screenshot executor is stopped before the overlay goes: a queued or late callback must
        // not be able to reach the repository or the window of a service that is gone.
        executor.shutdown()
        floatingButton?.hide()
        mediaPauser.release()
        instance = null
    }

    /**
     * The user took the app off recents. The service is bound by the system, so this callback only
     * arrives for reasons the platform decides and is not a guarantee — what is certain is that a
     * lookup must not stay frozen over the launcher afterwards, and that a pending capture must not
     * reopen it. Registering a foreground service or polling tasks to make this reliable is not
     * something Gaten will do.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "App task removed - ending any lookup")
        dismissForAppExit()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * The user left the app for good (its task was finished and it is not just rotating): the same
     * dismissal as [onTaskRemoved], from the one lifecycle callback that does arrive. Later triggers
     * are unaffected — the service stays connected and the button comes back with it.
     */
    fun dismissForAppExit() {
        cancelLookup()
        syncFloatingButton()
    }

    /**
     * A frozen capture only matches the screen it was taken from. A rotation, a fold or a real
     * display swap while a capture is pending or on screen means the frame no longer matches, so the
     * capture is dropped (or the overlay closed) instead of the window rescaling it. Ordinary layout
     * changes — the keyboard, a split screen, a font change — do not change any of the compared
     * values and are ignored.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkDisplayGeometry(newConfig)
    }

    /** Drops both pending and displayed captures when screen geometry or rotation changes. */
    private fun checkDisplayGeometry(newConfig: Configuration) {
        val next = geometryOf(newConfig)
        val pending = pendingGeometry
        val shown = overlayGeometry
        if ((pending != null && displayChanged(pending, next)) ||
            (shown != null && displayChanged(shown, next))
        ) {
            Log.d(TAG, "Display changed - ending the lookup")
            cancelLookup()
            syncFloatingButton()
        }
    }

    /**
     * The accessibility events that can take the screen: a call, a firing alarm or the power menu
     * per [classifyWindowEvent], and an incoming-call or firing-alarm notification per
     * [classifyNotificationEvent]. A notification arrives as an ordinary accessibility event, so no
     * notification-listener access is needed for this; the window-state event is the backstop for
     * the call and alarm windows themselves. Both are dispatched immediately — not only while an
     * overlay is up — so a capture that is still pending is cancelled too.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        if (!serviceConnected) return
        if (!admissionAllowed()) {
            cancelLookup()
            floatingButton?.hide()
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val interruption = classifyWindowEvent(
                    WindowEvent(
                        packageName = event.packageName?.toString(),
                        ownPackage = packageName,
                        windowClass = event.className?.toString()
                    )
                )
                dismissForInterruption(interruption, event.packageName)
                val source = event.packageName?.toString()
                // Full-screen app changes can precede the screenshot. Ignore our overlay's events
                // and the shade collapsing after a tile trigger, while tracking the app beneath it.
                if (source != null && event.isFullScreen &&
                    source != "com.android.systemui" && source != "com.oplus.systemui" &&
                    source != "com.oneplus.systemui" &&
                    (source != packageName || event.className?.toString() == MainActivity::class.java.name)
                ) {
                    if (captureToken.busy && capturePackage != null && capturePackage != source) {
                        Log.d(TAG, "Foreground app changed while capturing - ending the lookup")
                        cancelLookup()
                        syncFloatingButton()
                    }
                    foregroundPackage = source
                }
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                // The event carries the posted Notification itself; only its metadata is ever read.
                val notification = event.parcelableData as? Notification ?: return
                val interruption = classifyNotificationEvent(
                    NotificationEvent(
                        packageName = event.packageName?.toString(),
                        ownPackage = packageName,
                        category = notification.category,
                        styleTemplate = notification.extras?.getString(Notification.EXTRA_TEMPLATE),
                        callType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            notification.extras?.getInt(
                                Notification.EXTRA_CALL_TYPE,
                                Notification.CallStyle.CALL_TYPE_UNKNOWN
                            )
                        } else {
                            null
                        },
                        hasFullScreenIntent = notification.fullScreenIntent != null
                    )
                )
                dismissForInterruption(interruption, event.packageName)
            }
        }
    }

    /** Ends a lookup for an interruption that is underway; a no-op for [LookupDismissal.None]. */
    private fun dismissForInterruption(interruption: LookupDismissal, source: CharSequence?) {
        if (interruption == LookupDismissal.None) return
        Log.d(TAG, "$interruption in the foreground - ending the lookup ($source)")
        // The token is aborted first: a capture that is still pending is cancelled even though no
        // overlay is up to close.
        cancelLookup()
        syncFloatingButton()
    }

    /** Invalidates capture callbacks and closes without resuming playback or retaining the frame. */
    private fun cancelLookup() {
        captureToken.abort()
        pendingGeometry = null
        overlayGeometry = null
        capturePackage = null
        if (overlay.isShowing) {
            overlay.hideWithoutResume()
        } else {
            // A manual close may already have queued a resume. An interruption cancels that too,
            // even when its window has just disappeared.
            mediaPauser.discard()
        }
        BitmapRepository.clear()
    }

    override fun onInterrupt() {}

    /**
     * Shows the floating trigger when the user has it enabled and it is not already up, hides it
     * otherwise. A no-op when the preference is unchanged and the button already matches it.
     *
     * Nothing is shown while the service is not connected or the screen is off or locked, and the
     * hide goes the other way: those states are exactly when the button must not sit on screen.
     */
    fun syncFloatingButton() {
        // Before onServiceConnected there is no window token: adding the button now would throw
        // BadTokenException, so the attempt is deferred to that callback.
        if (!serviceConnected || !admissionAllowed() || overlay.isShowing || captureToken.busy) {
            floatingButton?.hide()
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
     * The ordered trigger pipeline: screenshot the screen, show it, and only then pause the media -
     * the overlay does that once the frozen frame is on screen, so whatever the player does when it
     * pauses - raising its controls, moving its subtitles - happens underneath and is never seen.
     * Nothing on screen needs to settle first, so the capture runs at once unless something of our
     * own is still in the way: the collapsing shade after [fromShade], or the floating button.
     */
    private fun startOrderedCapture(fromShade: Boolean, token: Long) {
        Log.d(TAG, "Starting the ordered capture pipeline (fromShade=$fromShade," +
            " +${elapsedSinceTrigger()} ms since the trigger)")
        val capture = { performCapture(token) }
        val button = floatingButton
        when {
            // The shade wait outlasts the button's removal, so a plain hide is enough there.
            fromShade -> {
                button?.hide()
                // Wrapped so a dismissal that landed during the wait ends the accepted capture
                // instead of stranding it: the token has moved on by then.
                mainHandler.postDelayed({ if (captureToken.isCurrent(token)) capture() }, ShadeCollapseMs)
            }
            // The capture is what the user sees frozen, so the button must be off screen first.
            button != null -> button.hideThen {
                if (captureToken.isCurrent(token)) capture() else finishCapture(token)
            }
            else -> capture()
        }
    }

    /**
     * Freezes the current screen and opens the lookup overlay. A capture that fails brings the
     * floating button back, since no overlay will.
     *
     * `takeScreenshot` respects content protection, so surfaces marked FLAG_SECURE (DRM video)
     * come back blank; Gaten never tries to bypass that.
     */
    private fun performCapture(token: Long) {
        Log.d(TAG, "performCapture called (+${elapsedSinceTrigger()} ms since the trigger)")

        // The token is checked before anything is cleared: a capture that was already dismissed must
        // not touch the repository a newer capture may be using.
        if (!captureToken.isCurrent(token)) {
            Log.d(TAG, "Capture is stale - not starting it")
            return
        }
        // Clear the repository at the source so a stale capture can never flash.
        BitmapRepository.clear()
        if (!admissionAllowed()) {
            finishCapture(token)
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishCapture(token)
            return
        }

        Log.d(TAG, "takeScreenshot requested (+${elapsedSinceTrigger()} ms since the trigger)")
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotCallbacks,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        if (!captureToken.isCurrent(token) || !admissionAllowed()) {
                            screenshot.hardwareBuffer.close()
                            finishCapture(token)
                            return
                        }
                        executor.execute { deliverScreenshot(screenshot, token) }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        finishCapture(token)
                    }
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not request the screenshot", e)
            finishCapture(token)
        }
    }

    /**
     * Prepares the screenshot's software copy on the executor and hands it to the main thread,
     * releasing the hardware buffer in every path. The copy is private until the main thread accepts
     * it: a stale capture releases its own copy and leaves the repository, the overlay and the
     * in-flight state of a newer capture untouched.
     */
    private fun deliverScreenshot(screenshot: ScreenshotResult, token: Long) {
        Log.d(TAG, "Screenshot delivered (+${elapsedSinceTrigger()} ms since the trigger)")
        val hardwareBuffer = screenshot.hardwareBuffer
        val copy = try {
            val hardware = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
            if (hardware == null) {
                null
            } else {
                val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                software?.setHasAlpha(false)
                software
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare the captured screenshot", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory for the captured screenshot", e)
            null
        } finally {
            // Released whether the copy worked or not.
            hardwareBuffer.close()
        }

        if (copy == null) {
            mainHandler.post { finishCapture(token) }
            return
        }
        mainHandler.post { acceptScreenshot(copy, token) }
    }

    /**
     * The main thread decides what happens to a prepared capture: shown when it is still the current
     * one and the screen allows it, otherwise its private copy is dropped. Nothing shared — the
     * repository, the window, the in-flight state — is touched by a stale capture.
     */
    private fun acceptScreenshot(bitmap: Bitmap, token: Long) {
        if (!captureToken.isCurrent(token) || !admissionAllowed()) {
            Log.d(TAG, "Capture is stale or the screen is gone - dropping it")
            bitmap.recycle()
            finishCapture(token)
            return
        }
        if (!overlay.show(bitmap, token) { captureToken.isCurrent(token) && admissionAllowed() }) {
            // The window could not be added; the capture is over and the button comes back.
            BitmapRepository.clear()
            finishCapture(token)
            return
        }
        // The overlay is on screen: it keeps the capture until it is closed, which completes the
        // token (see the controller's onHidden). The repository holds it for the lookup UI to read.
        BitmapRepository.setScreenshot(bitmap)
        overlayGeometry = geometryOf(resources.configuration)
        pendingGeometry = null
        floatingButton?.hide()
    }

    /** Screen dimensions and actual rotation, including half-turns without a configuration change. */
    private fun geometryOf(configuration: Configuration) = DisplayGeometry(
        orientation = when (configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT -> DisplayOrientation.PORTRAIT
            Configuration.ORIENTATION_LANDSCAPE -> DisplayOrientation.LANDSCAPE
            Configuration.ORIENTATION_SQUARE -> DisplayOrientation.SQUARE
            else -> DisplayOrientation.UNDEFINED
        },
        screenWidthDp = configuration.screenWidthDp,
        screenHeightDp = configuration.screenHeightDp,
        smallestScreenWidthDp = configuration.smallestScreenWidthDp,
        densityDpi = configuration.densityDpi,
        rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
    )

    /**
     * The screen has to be on and unlocked for a lookup to start, show or come back. Read straight
     * from the platform on the main thread, which is the only thread that decides these.
     */
    private fun admissionAllowed(): Boolean = serviceConnected &&
        displayManager.getDisplay(Display.DEFAULT_DISPLAY) != null && lookupAdmitted(
        isInteractive = powerManager.isInteractive,
        isKeyguardLocked = keyguardManager.isKeyguardLocked
    )

    /**
     * Ends the capture [token] belongs to when it is still the current one, so a failure or a
     * cancelled wait does not clear a newer capture's request.
     */
    private fun finishCapture(token: Long) {
        if (!captureToken.complete(token)) return
        pendingGeometry = null
        syncFloatingButton()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Screen receiver was not registered", e)
        }
    }

    private fun unregisterDisplayListener() {
        if (!displayListenerRegistered) return
        displayManager.unregisterDisplayListener(displayListener)
        displayListenerRegistered = false
    }

    companion object {
        private const val TAG = "Gaten"

        var instance: GatenAccessibilityService? = null
            private set

        /** The app is really going away; a lookup and any pending capture go with it. */
        fun dismissForAppExit() {
            instance?.mainHandler?.post { instance?.dismissForAppExit() }
        }

        /** When the running capture pipeline was triggered, in elapsedRealtime ms; 0 between triggers. */
        @Volatile
        private var triggerAtMs = 0L

        /**
         * Milliseconds since the trigger the overlay on screen came from, or 0 when no capture is in
         * flight. One clock for the whole chain — accepted, screenshot requested, screenshot
         * delivered, frozen frame committed — so a capture that lands late can be placed in it.
         */
        fun elapsedSinceTrigger(): Long =
            if (triggerAtMs == 0L) 0L else SystemClock.elapsedRealtime() - triggerAtMs

        /**
         * Entry point for the Quick Settings tile, the trigger broadcast and the floating button.
         *
         * Acts as a toggle and is single-flight: a trigger while the panel is up closes it instead
         * of starting another lookup, and a trigger while a capture is already in flight is
         * ignored. The accepted trigger runs the ordered pipeline on the main thread. [fromShade]
         * is for the Quick Settings tile, whose shade is still collapsing when this is called.
         *
         * A trigger that cannot start a lookup — the service is not connected, the screen is off, or
         * the keyguard is locked — is refused: the capture would only be frozen for a screen the
         * user is not there to see.
         */
        fun triggerCapture(fromShade: Boolean = false) {
            val service = instance
            if (service == null) {
                Log.d(TAG, "triggerCapture called but the accessibility service is not connected")
                return
            }
            // The toggle touches views and reads the screen state; the whole decision belongs on the
            // main thread, including the service-connected gate: the queued lambda must not run
            // after an unbind or a destroy left no window token behind.
            service.mainHandler.post {
                if (!service.serviceConnected) {
                    Log.d(TAG, "Service is not connected - ignoring the trigger")
                    return@post
                }
                if (service.overlay.hideIfShowing()) {
                    Log.d(TAG, "Lookup panel was open - closing it")
                    return@post
                }
                if (service.captureToken.busy) {
                    Log.d(TAG, "Capture already in flight - ignoring the new trigger")
                    return@post
                }
                if (!service.admissionAllowed()) {
                    Log.d(TAG, "Screen off or locked - ignoring the trigger")
                    service.syncFloatingButton()
                    return@post
                }
                // The token this capture runs under: it is what every callback and every delayed
                // step is checked against, so a trigger that is overtaken is dropped, not shown.
                val token = service.captureToken.start()
                service.capturePackage = service.foregroundPackage
                // A capture is only valid for the display it was accepted on: a rotation before the
                // screenshot lands drops it rather than freezing the wrong screen.
                service.pendingGeometry = service.geometryOf(service.resources.configuration)
                // Everything from here is timed against this instant, so logcat shows where a
                // capture spent its time when the frozen frame lands late.
                triggerAtMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "Trigger accepted (+${elapsedSinceTrigger()} ms since the trigger)")
                service.startOrderedCapture(fromShade, token)
            }
        }
    }
}
