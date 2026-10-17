/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.capture

import android.app.Activity
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import com.gaten.app.MainActivity
import com.gaten.app.R
import com.gaten.app.data.BitmapRepository
import com.gaten.app.lifecycle.CaptureToken
import com.gaten.app.lifecycle.DisplayGeometry
import com.gaten.app.lifecycle.DisplayOrientation
import com.gaten.app.lifecycle.LookupDismissal
import com.gaten.app.lifecycle.displayChanged
import com.gaten.app.lifecycle.lookupAdmitted
import com.gaten.app.media.MediaPauser
import com.gaten.app.ocr.OcrModels
import com.gaten.app.trigger.FloatingTriggerButton
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.overlay.OverlayWindowController

/**
 * How long a trigger from the Quick Settings tile waits for the shade to collapse before capturing.
 * The tile collapses the shade by starting [TileCaptureActivity], and the frozen frame is taken
 * from the mirrored display, so the wait has to outlast the collapse animation on the way out — the
 * activity itself is already gone by then.
 */
private const val ShadeCollapseMs = 400L

private const val ForegroundId = 1
private const val ChannelId = "lookup-mode"

/**
 * The app while lookup mode is on: it holds the media projection session, screenshots the screen
 * when a lookup is triggered, stores the frame in [BitmapRepository] and draws the lookup overlay in
 * windows of its own — [OverlayWindowController], not an activity, so the app behind keeps playing.
 *
 * It is a plain foreground service rather than an accessibility service: capture comes from
 * [ScreenCaptureSession], whose projection the user consents to once per session, and the windows
 * are `TYPE_APPLICATION_OVERLAY` windows behind the "display over other apps" permission. Nothing
 * here asks for accessibility, so no banking app has to see an enabled accessibility service.
 *
 * A lookup is only ever useful on the screen the user is looking at, so the service watches for the
 * ways that stops being true: the screen going off or the keyguard locking, a projection the user or
 * the system stops, a call or a firing alarm, the display rotating under a frozen frame, and lookup
 * mode being switched off. Those dismissals end the lookup without playing the media back, because
 * the media was paused for a lookup that is over; the user's own dismissals — back, the scrim, the
 * trigger toggle — resume it as before.
 *
 * Every capture runs under a [CaptureToken], which is also the single-flight gate: the frame is
 * copied on the capture session's own thread while the trigger, the window and the calls that end it
 * all live on the main thread, so a step that resolves after the lookup was dismissed (or replaced)
 * is dropped and cannot clear a newer trigger's state.
 */
class GatenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var overlay: OverlayWindowController
    private lateinit var mediaPauser: MediaPauser
    private var floatingButton: FloatingTriggerButton? = null
    /** The projection session and everything lookup mode is while it is on, null while it is off. */
    private var session: ScreenCaptureSession? = null
    /** True while the screen receiver is registered, so it is unregistered exactly once. */
    private var receiverRegistered = false
    private var displayListenerRegistered = false
    /** The capture in flight and its single-flight flag, moved on by a dismissal or a new trigger. */
    private val captureToken = CaptureToken()
    /** The display the capture was accepted on, to notice a rotation while it is still pending. */
    private var pendingGeometry: DisplayGeometry? = null
    /** The display the overlay was shown on, to notice a rotation under the frozen frame. */
    private var overlayGeometry: DisplayGeometry? = null

    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val projectionManager by lazy { getSystemService(MediaProjectionManager::class.java) }

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
                    // new may start while the screen is off: the button goes down with it. The
                    // projection goes too: Android stops it when the screen locks.
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

    /** Creates the window and media owners. */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            // if its frame is still on its way.
            onAutoDismiss = { captureToken.abort() }
        )
        registerScreenReceiver()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        displayListenerRegistered = true
        instance = this
        syncFloatingButton()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginLookupMode(intent)
            ACTION_STOP -> stopLookupMode()
            else -> Log.w(TAG, "Ignoring an unknown start action: ${intent?.action}")
        }
        // Nothing to restore: lookup mode begins with a consent the user gives in an activity, and a
        // session that outlived the projection it was consented for would be a lie.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The user took the app off recents. Lookup mode is not the app's task and stays on — the
     * overlay is what the reader is using — so this only takes down a lookup that would otherwise
     * stay frozen over the launcher.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "App task removed - ending any lookup")
        dismissForAppExit()
        super.onTaskRemoved(rootIntent)
    }

    /** The app is going away for good; a lookup and any pending capture go with it. */
    fun dismissForAppExit() {
        cancelLookup()
        syncFloatingButton()
    }

    /** Lookup mode ends here: the projection, the windows and the notification all go together. */
    override fun onDestroy() {
        super.onDestroy()
        unregisterScreenReceiver()
        unregisterDisplayListener()
        cancelLookup()
        val ending = session
        session = null
        ending?.release()
        floatingButton?.hide()
        mediaPauser.release()
        OcrModels.releaseNow()
        instance = null
    }

    /**
     * Requests model cleanup on memory pressure; active recognition finishes before cleanup.
     * UI_HIDDEN alone is ignored because a service overlay may still be in use. Trim callbacks
     * vary by Android version, so the independent idle timer remains necessary.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val low = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (!low) return
        Log.d(TAG, "Trim memory level $level: releasing the OCR models")
        OcrModels.releaseNow()
    }

    /**
     * A frozen capture only matches the screen it was taken from. A rotation, a fold or a real
     * display swap while a capture is pending or on screen means the frame no longer matches, so the
     * capture is dropped (or the overlay closed) instead of the window rescaling it — and the mirror
     * is reshaped so the next lookup is taken at the display's new size. Ordinary layout changes —
     * the keyboard, a split screen, a font change — do not change any of the compared values and are
     * ignored.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkDisplayGeometry(newConfig)
    }

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
        val size = displaySize()
        session?.resize(size.widthPixels, size.heightPixels, size.densityDpi)
    }

    /**
     * Starts lookup mode for the projection the user just consented to: the foreground service that
     * the platform requires before the token is used, then the token, then the mirror.
     */
    private fun beginLookupMode(intent: Intent) {
        // Declared first, so a start that fails on the way still has a foreground service to fall
        // back on rather than tripping the "did not call startForeground" timeout.
        if (!goForeground()) {
            stopSelf()
            return
        }
        @Suppress("DEPRECATION")
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        if (data == null || resultCode != Activity.RESULT_OK) {
            Log.w(TAG, "Lookup mode was asked for without a capture consent")
            stopLookupMode()
            return
        }
        val projection = try {
            projectionManager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "Could not take the media projection", e)
            null
        }
        if (projection == null) {
            Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_LONG).show()
            stopLookupMode()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            // Nothing can be drawn over the app being read without it, so a session here would only
            // leave a projection running for a lookup that can never appear.
            Toast.makeText(this, R.string.toast_overlay_needed, Toast.LENGTH_LONG).show()
            stopLookupMode()
            return
        }
        val size = displaySize()
        session?.release()
        session = ScreenCaptureSession(
            projection,
            size.widthPixels,
            size.heightPixels,
            size.densityDpi
        ) { stopped -> onSessionStopped(stopped) }
        Log.d(TAG, "Lookup mode on for a ${size.widthPixels}x${size.heightPixels} display")
        syncFloatingButton()
    }

    /**
     * The projection is gone — the user tapped the status-bar chip, the screen locked, another app
     * started capturing, or the process was restarted. Lookup mode cannot continue without it, so it
     * ends, and the reader is told why the next trigger will not work.
     */
    private fun onSessionStopped(stopped: ScreenCaptureSession) {
        // Only the session that is still the current one: a projection that ends after a newer
        // session has replaced it must not switch that newer one off.
        if (session !== stopped) {
            Log.d(TAG, "An earlier capture session ended - ignoring it")
            return
        }
        Log.d(TAG, "The capture session ended - switching lookup mode off")
        cancelLookup()
        session = null
        floatingButton?.hide()
        Toast.makeText(this, R.string.toast_capture_stopped, Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Ends lookup mode on the user's instruction. */
    private fun stopLookupMode() {
        Log.d(TAG, "Lookup mode off")
        cancelLookup()
        // Forgotten before it is released: this is the user's own end, and the teardown it triggers
        // must not come back as the system stopping the projection.
        val ending = session
        session = null
        ending?.release()
        floatingButton?.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Shows the floating trigger when the user has it enabled and it is not already up, hides it
     * otherwise. A no-op when the preference is unchanged and the button already matches it.
     *
     * Nothing is shown while lookup mode is off, the overlay permission is missing, the screen is
     * off or locked, or a capture is in flight: those are exactly when the button must not sit on
     * screen.
     */
    fun syncFloatingButton() {
        if (!admissionAllowed() || overlay.isShowing || captureToken.busy) {
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
                // Adding a window can fail (the permission was revoked, the display is going down);
                // a button that cannot be shown is no reason to take every trigger with it.
                Log.w(TAG, "Could not show the floating button", e)
            }
        } else {
            floatingButton?.hide()
        }
    }

    /**
     * The ordered trigger pipeline: capture the screen, show it, and only then pause the media - the
     * overlay does that once the frozen frame is on screen, so whatever the player does when it
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
     * Freezes the current screen and opens the lookup overlay. A capture that fails, or that is
     * asked for while lookup mode is off, brings the floating button back, since no overlay will.
     *
     * The frame comes from the projection's mirror, which respects content protection, so surfaces
     * marked FLAG_SECURE (DRM video) come back blank; Gaten never tries to bypass that.
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
        val session = session
        if (session == null || !admissionAllowed()) {
            finishCapture(token)
            return
        }

        Log.d(TAG, "Frame requested (+${elapsedSinceTrigger()} ms since the trigger)")
        session.grab { bitmap ->
            if (bitmap == null) {
                Log.w(TAG, "The capture session produced no frame")
                finishCapture(token)
                return@grab
            }
            if (!captureToken.isCurrent(token) || !admissionAllowed()) {
                Log.d(TAG, "Capture is stale or the screen is gone - dropping it")
                bitmap.recycle()
                finishCapture(token)
                return@grab
            }
            acceptScreenshot(bitmap, token)
        }
    }

    /**
     * The main thread decides what happens to a captured frame: shown when it is still the current
     * one and the screen allows it, otherwise its private copy is dropped.
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

    /**
     * Ends a lookup for an interruption that is underway; a no-op for [LookupDismissal.None]. Fed
     * from the notification listener, which is the only component that sees an incoming call or a
     * firing alarm without an accessibility service.
     */
    fun dismissForInterruption(interruption: LookupDismissal, source: CharSequence?) {
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
        // A request the mirror is still rendering — for a lookup that is already over — is dropped
        // here, so its reader and capture target go with it instead of waiting for the frame.
        session?.cancel()
        if (overlay.isShowing) {
            overlay.hideWithoutResume()
        } else {
            // A manual close may already have queued a resume. An interruption cancels that too,
            // even when its window has just disappeared.
            mediaPauser.discard()
        }
        BitmapRepository.clear()
    }

    /**
     * Ends the capture [token] belongs to when it is still the current one, so a failure or a
     * cancelled wait does not clear a newer capture's request.
     */
    private fun finishCapture(token: Long) {
        if (!captureToken.complete(token)) return
        pendingGeometry = null
        syncFloatingButton()
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
     * The pixels the mirror is sized to. A projection hands over the display it was consented for, so
     * the real metrics of the default display are what the virtual display has to match to avoid
     * being letterboxed inside an older size.
     */
    private fun displaySize(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.setTo(resources.displayMetrics)
        }
        return metrics
    }

    /**
     * The screen has to be on and unlocked, lookup mode has to be on, and the overlay permission has
     * to still be granted, for a lookup to start, show or come back. Read straight from the platform
     * on the main thread, which is the only thread that decides these.
     */
    private fun admissionAllowed(): Boolean = session != null &&
        Settings.canDrawOverlays(this) &&
        displayManager.getDisplay(Display.DEFAULT_DISPLAY) != null &&
        lookupAdmitted(
            isInteractive = powerManager.isInteractive,
            isKeyguardLocked = keyguardManager.isKeyguardLocked
        )

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

    /**
     * Puts the capture up as a foreground service. The platform requires the service to be running
     * with the mediaProjection type before the projection token is used, and a foreground service is
     * the only thing that keeps the overlay and its projection alive while the reader is in another
     * app — so this is also why lookup mode is a switch the user can see, not a hidden background
     * service.
     */
    private fun goForeground(): Boolean = try {
        startForeground(
            ForegroundId,
            foregroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        true
    } catch (e: Exception) {
        Log.e(TAG, "Could not start the capture foreground service", e)
        false
    }

    /** What is running, why the privacy chip is up, and the one tap that turns it off. */
    private fun foregroundNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, GatenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_qs_lookup)
            .setContentTitle(getString(R.string.notification_lookup_mode_title))
            .setContentText(getString(R.string.notification_lookup_mode_text))
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_lookup_mode_stop),
                    stop
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            ChannelId,
            getString(R.string.notification_channel_lookup_mode),
            // No sound, no badge, and no heads-up: this notification reports a state the user
            // switched on themselves, and the projection's own status-bar chip is the visible one.
            NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "Gaten"

        /** The projection result the consent activity forwards to [start]. */
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val ACTION_START = "com.gaten.app.action.START_LOOKUP_MODE"
        private const val ACTION_STOP = "com.gaten.app.action.STOP_LOOKUP_MODE"

        var instance: GatenCaptureService? = null
            private set

        /** True while the projection behind the lookup overlay is alive. */
        fun isLookupModeActive(): Boolean = instance?.session != null

        /** Asks the user for the capture consent, which is what starts lookup mode. */
        fun requestLookupMode(context: Context) {
            try {
                context.startActivity(CaptureConsentActivity.intent(context))
            } catch (e: Exception) {
                Log.w(TAG, "Could not ask for the capture consent", e)
                Toast.makeText(context, R.string.toast_capture_settings_error, Toast.LENGTH_LONG)
                    .show()
            }
        }

        /** Starts lookup mode for a consent the user just gave, from [CaptureConsentActivity]. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, GatenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        /** Ends lookup mode: the projection, the overlay and the notification all go. */
        fun stopLookupMode() {
            instance?.postToService { stopLookupMode() }
        }

        /** The app is really going away; a lookup and any pending capture go with it. */
        fun dismissForAppExit() {
            instance?.postToService { dismissForAppExit() }
        }

        /** Rebinds the floating trigger to the preference the home screen just changed. */
        fun syncFloatingButton() {
            instance?.postToService { syncFloatingButton() }
        }

        /** When the running capture pipeline was triggered, in elapsedRealtime ms; 0 between triggers. */
        @Volatile
        private var triggerAtMs = 0L

        /**
         * Milliseconds since the trigger the overlay on screen came from, or 0 when no capture is
         * in flight. One clock for the whole chain — accepted, frame requested, frame delivered,
         * frozen frame committed — so a capture that lands late can be placed in it.
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
         * A trigger that cannot start a lookup — lookup mode is off, the overlay permission is gone,
         * the screen is off, or the keyguard is locked — is refused: the capture would only be
         * frozen for a screen the user is not there to see.
         */
        fun triggerCapture(fromShade: Boolean = false) {
            val service = instance
            if (service == null) {
                Log.d(TAG, "triggerCapture called but lookup mode is off")
                return
            }
            service.postToService {
                if (service.session == null) {
                    Log.d(TAG, "Lookup mode is off - ignoring the trigger")
                    return@postToService
                }
                if (service.overlay.hideIfShowing()) {
                    Log.d(TAG, "Lookup panel was open - closing it")
                    return@postToService
                }
                if (service.captureToken.busy) {
                    Log.d(TAG, "Capture already in flight - ignoring the new trigger")
                    return@postToService
                }
                if (!service.admissionAllowed()) {
                    Log.d(TAG, "Screen off or locked - ignoring the trigger")
                    service.syncFloatingButton()
                    return@postToService
                }
                // The token this capture runs under: it is what every callback and every delayed
                // step is checked against, so a trigger that is overtaken is dropped, not shown.
                val token = service.captureToken.start()
                // A capture is only valid for the display it was accepted on: a rotation before the
                // frame lands drops it rather than freezing the wrong screen.
                service.pendingGeometry = service.geometryOf(service.resources.configuration)
                // Everything from here is timed against this instant, so logcat shows where a
                // capture spent its time when the frozen frame lands late.
                triggerAtMs = SystemClock.elapsedRealtime()
                Log.d(TAG, "Trigger accepted (+${elapsedSinceTrigger()} ms since the trigger)")
                service.startOrderedCapture(fromShade, token)
            }
        }

        private fun GatenCaptureService.postToService(block: GatenCaptureService.() -> Unit) {
            mainHandler.post { if (instance === this) block() }
        }
    }
}
