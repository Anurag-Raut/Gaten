/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gaten.app.GatenAccessibilityService
import com.gaten.app.media.MediaPauser
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.ScreenTextScanner
import com.gaten.app.ui.lookup.LookupOverlay
import com.gaten.app.ui.theme.GatenTheme
import com.gaten.app.ui.theme.ThemePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

private const val TAG = "Gaten"

/** How long the frozen frame is on screen before the media behind it is paused. */
private const val PauseAfterFreezeMs = 100L

/** Longest the overlay waits for its frozen first frame to reach the display. */
private const val FreezeTimeoutMs = 250L

/** How long a lost window focus has to stay away before the overlay treats it as the user leaving. */
private const val FocusBounceMs = 200L

/**
 * The lookup overlay, drawn in a window owned by the accessibility service.
 *
 * An activity used to do this, and an activity takes the foreground: the app the user was looking
 * at stopped, and Android answered a video player that stops while playing with picture-in-picture.
 * A [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window never touches the activity
 * stack, so the app behind stays foreground and keeps playing fullscreen.
 *
 * The window holds a [ComposeView] and is not an activity: [OverlayHost] supplies the owners
 * the composition reads from the view tree, and [show]/[hide] drive that host's lifecycle. Every
 * [show] builds a fresh window from the capture it is handed — that, not a composition key, is what
 * resets the overlay to selection mode on a re-trigger.
 *
 * Everything here belongs on the main thread: it adds and removes views.
 */
class OverlayWindowController(
    private val context: Context,
    private val mediaPauser: MediaPauser,
    /** The overlay is down and the capture behind it is over. */
    private val onHidden: (Long) -> Unit,
    /**
     * Runs on the main thread when the overlay took itself down and the media must stay paused. The
     * service drops the capture it was drawn from here, so a dismissal that raced the screenshot's
     * delivery can never put that lookup back up. Never runs for a dismissal the user asked for.
     */
    private val onAutoDismiss: () -> Unit
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The overlay on screen, if any. */
    private var showing: Showing? = null

    /** The accepted capture's detector pass starts before the frozen-frame/UI transition. */
    private var pendingScan: Deferred<ScreenText?>? = null

    /**
     * Puts [bitmap]'s capture on screen, replacing any overlay already up, and pauses the media
     * behind it once the frozen frame is really showing. False when nothing could be shown, so the
     * caller can release the capture it prepared.
     *
     * The window goes up holding nothing but the capture, so its first frame - the freeze - is as
     * cheap as a frame can be; the lookup UI is composed into it only after that frame is on screen.
     * The pause waits [PauseAfterFreezeMs] past the same point: a player raises its controls the
     * moment it pauses, and paused any earlier they flash up before the freeze covers them.
     *
     * [token] is the capture this window belongs to; it travels with the overlay so its teardown can
     * end exactly that capture (see [onHidden]). [admission] is re-checked on the main thread here
     * and again before the media is paused: the capture may have been taken while the screen was
     * still on and resolved only after the screen went off or the phone locked.
     */
    fun show(bitmap: Bitmap, token: Long, admission: () -> Boolean): Boolean {
        hide()
        if (!admission()) {
            Log.d(TAG, "Screen off or locked - not showing the captured overlay")
            return false
        }

        // Submit immediately after acceptance. The first frozen frame and Compose setup can now
        // overlap ML Kit instead of making the detector wait behind them.
        val scan = scanScope.async {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            Log.d(TAG, "Early screen scan submitted" +
                " (+${GatenAccessibilityService.elapsedSinceTrigger()} ms since the trigger)")
            val result = ScreenTextScanner.scan(bitmap)
            Log.d(TAG, "Early screen scan ${if (result == null) "unavailable" else "ready"}" +
                " in ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
            result
        }
        pendingScan = scan

        val host = OverlayHost()
        val root = OverlayRoot(
            context,
            onBack = {
                // The lookup UI closes itself with a fade; before it is composed there is none.
                if (host.onBackPressedDispatcher.hasEnabledCallbacks()) {
                    host.onBackPressedDispatcher.onBackPressed()
                } else {
                    hide()
                }
            },
            // Posted: the window is not taken down from inside its own focus dispatch. A window
            // that lost focus to something else - home, recents, another app, the shade, the power
            // menu - is not a dismissal the user made, so the media stays paused for whatever took
            // the screen. A window that has just been added can lose focus for a beat without the
            // user having gone anywhere (the system settling as the window comes up, the player
            // raising its controls as the pause lands), so the dismissal waits for the focus to stay
            // away: one that is handed back is not a dismissal at all.
            onFocusLost = { view ->
                mainHandler.post {
                    if (showing?.view === view) {
                        mainHandler.postDelayed({ closeIfFocusStayedAway(view) }, FocusBounceMs)
                    }
                }
            }
        ).apply {
            // The freeze has to hold whatever is moving behind it. The window itself is opaque
            // (see windowParams), so the root's black is what fills every pixel the capture does
            // not cover: nothing of the live app behind can reach the display through the overlay.
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        root.setViewTreeLifecycleOwner(host)
        root.setViewTreeViewModelStoreOwner(host)
        root.setViewTreeSavedStateRegistryOwner(host)
        root.setViewTreeOnBackPressedDispatcherOwner(host)
        // A capture can carry alpha below 255 - a video surface can come back as a transparent-hole
        // region - and the ImageView would then draw the app behind it through the frozen frame.
        // The frame is the whole display, so it is opaque by definition. Safe to mutate: the
        // repository holds the ARGB_8888 software copy the service made (`deliverScreenshot`).
        bitmap.setHasAlpha(false)
        // The capture itself is the freeze: the app keeps running underneath and none of it shows
        // through. Drawn through the mapping below, it lines up with the recognised boxes, which
        // are in the same screenshot pixels.
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setImageBitmap(bitmap)
        }
        root.addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // The composition reads the owners on attach, so the lifecycle has to be CREATED before the
        // window is added and RESUMED once it is up, exactly as an activity would be.
        host.onCreate()
        try {
            windowManager.addView(root, windowParams())
        } catch (e: Exception) {
            // Adding a window can fail (no valid token, the display being torn down). Nothing was
            // shown, so the caller is told and the host is discarded.
            Log.w(TAG, "Could not add the overlay window", e)
            host.onDestroy()
            scan.cancel()
            pendingScan = null
            return false
        }
        host.onResume()
        val current = Showing(root, host, token, scan)
        showing = current
        Log.d(TAG, "Overlay window added for a ${bitmap.width}x${bitmap.height} capture" +
            " (+${GatenAccessibilityService.elapsedSinceTrigger()} ms since the trigger)")

        // Where the capture is drawn: the window's size and its origin on the display are what the
        // frozen frame and every box have to be placed through. Derived in the layout pass - before
        // the first draw, so the frame is never shown at the wrong scale - and re-derived on every
        // further layout, since a display change under a live overlay moves and resizes the window.
        // Identity until then, which is also what a window of the capture's own size gets.
        var mapping = ViewMapping.IDENTITY
        var logged = false
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            val fixed = ViewMapping.between(
                bitmapWidth = bitmap.width,
                bitmapHeight = bitmap.height,
                viewWidth = root.width,
                viewHeight = root.height,
                originX = origin[0],
                originY = origin[1]
            )
            image.imageMatrix = Matrix().apply {
                setScale(fixed.scaleX, fixed.scaleY)
                // postTranslate works in the destination space, so the offset is applied after the
                // scale, exactly as ViewMapping does it.
                postTranslate(-fixed.offsetX, -fixed.offsetY)
            }
            // Once per show, and again only when the window really moved under the capture.
            if (!logged || fixed != mapping) {
                logged = true
                Log.d(TAG, "Mapping: bitmap ${bitmap.width}x${bitmap.height}," +
                    " view ${root.width}x${root.height}," +
                    " origin (${origin[0]}, ${origin[1]})," +
                    " scale ${fixed.scaleX}x${fixed.scaleY}")
            }
            mapping = fixed
        }

        var frozen = false
        val onFrozen = Runnable {
            // A dismissal or a newer capture beat the frame here: nothing may be composed or paused.
            if (frozen || showing !== current) return@Runnable
            if (!admission()) {
                Log.d(TAG, "Screen off or locked before the frozen frame - closing without resuming")
                dismissAutomatically()
                return@Runnable
            }
            frozen = true
            Log.d(TAG, "Frozen frame on screen" +
                " (+${GatenAccessibilityService.elapsedSinceTrigger()} ms since the trigger)")
            // Scheduled first: composing the lookup UI below blocks this thread for a while.
            mediaPauser.pause(PauseAfterFreezeMs)
            root.addView(
                lookupView(bitmap, mapping, current.scan),
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
        }
        // Committed, then one vsync more: the frame is on the display. The timeout covers a frame
        // that is never drawn (screen off) so the lookup still opens and the media still pauses.
        root.viewTreeObserver.registerFrameCommitCallback {
            mainHandler.post { Choreographer.getInstance().postFrameCallback { onFrozen.run() } }
        }
        mainHandler.postDelayed(onFrozen, FreezeTimeoutMs)
        return true
    }

    /** The lookup UI, transparent apart from what it draws over the capture beneath it. */
    private fun lookupView(
        bitmap: Bitmap,
        mapping: ViewMapping,
        scan: Deferred<ScreenText?>
    ) = ComposeView(context).apply {
        setContent {
            GatenTheme(
                ThemePreferences.themeMode(context),
                ThemePreferences.accent(context)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    LookupOverlay(
                        screenshot = bitmap,
                        mapping = mapping,
                        earlyScan = scan,
                        onDismiss = ::hide
                    )
                }
            }
        }
    }

    /**
     * Closes the overlay for a focus loss that was not handed back within [FocusBounceMs]. Checked
     * again here rather than acted on at once, so the transient focus changes that a window sees
     * while it is coming up cannot dismiss a lookup the reader just opened.
     */
    private fun closeIfFocusStayedAway(view: View) {
        if (showing?.view !== view || view.hasWindowFocus()) return
        Log.d(TAG, "Overlay lost window focus - the user went elsewhere, closing it")
        dismissAutomatically()
    }

    /** Takes the overlay down when it is up; true when there was one to take down. */
    fun hideIfShowing(): Boolean {
        if (showing == null) return false
        hide()
        return true
    }

    /** True while an overlay is on screen; the service uses it to drop work a dismissal made stale. */
    val isShowing: Boolean
        get() = showing != null

    /**
     * Takes the overlay down and drops the capture it was drawn from, playing back what the overlay
     * paused. The path for every dismissal the user made: back, the scrim, the trigger toggle.
     */
    fun hide() = close(resume = true, notifyService = false)

    /**
     * Takes the overlay down without playing anything back. The path for a dismissal nobody asked
     * for - the screen going off, a call, an alarm, the power menu, the app going away - so the
     * media it paused is left for whatever interrupted to deal with.
     */
    fun hideWithoutResume() = close(resume = false, notifyService = true)

    /** The overlay closing itself because the screen or a window outside it changed. */
    private fun dismissAutomatically() = close(resume = false, notifyService = true)

    /**
     * Takes the overlay down and drops the capture it was drawn from. [resume] plays back what the
     * overlay paused; [notifyService] tells the service the dismissal was not the user's, so the
     * capture token moves on and a late screenshot cannot reopen the lookup.
     *
     * show() starts by calling this with no overlay up, so every branch is a no-op then: nothing is
     * resumed, no callback fires and the capture the new show is about to use is never touched.
     */
    private fun close(resume: Boolean, notifyService: Boolean) {
        val current = showing
        // Only when an overlay is really coming down: show() starts by calling hide(), and one
        // closed before its delayed pause fired has nothing to resume or discard.
        if (current != null) {
            if (resume) mediaPauser.resume() else mediaPauser.discard()
        }
        showing = null
        pendingScan?.cancel()
        pendingScan = null
        if (current != null) {
            try {
                // The lifecycle goes even if the window refuses to leave, the way the floating
                // button clears its state before it tries to remove.
                windowManager.removeViewImmediate(current.view)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the overlay window", e)
            }
            current.host.onDestroy()
        }
        if (current == null) return
        Log.d(TAG, "Overlay window removed (resumed=$resume)")
        // A dismissal the user did not make: the service drops the capture this was drawn from, so
        // the screenshot that may still be on its way can never bring the lookup back.
        if (notifyService) onAutoDismiss()
        // The overlay - and with it this capture - is over. The token ends only if it is still the
        // current one, so a capture that replaced this overlay keeps its in-flight state.
        onHidden(current.token)
    }

    /**
     * Full-screen and opaque, so the overlay sits exactly where the capture was taken: the
     * lasso and the recognised boxes are in screenshot pixels, and those are only the same as view
     * pixels when the window covers the whole display from its origin. By default the system keeps
     * a window out of the cutout band while the status bar is hidden - over fullscreen video - and
     * shrinks it for a visible navigation bar, so both are switched off.
     *
     * OPAQUE rather than TRANSLUCENT, and the frozen frame is the whole display: the lookup UI is
     * composed into this same window, over the capture, so there is nothing left to see through it —
     * while a translucent window would let the app behind show wherever the capture is not opaque.
     * Keeps the screen bright while the lookup window is visible; removing it restores normal
     * screen timeout behavior.
     */
    private fun windowParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // Windows added via addView do not inherit the manifest's hardware acceleration
            // (activities get it through PhoneWindow); without this flag the whole overlay
            // renders on the software renderer.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }

    /** The overlay on screen: the view to detach, the host its lifecycle runs on, its capture token. */
    private class Showing(
        val view: View,
        val host: OverlayHost,
        val token: Long,
        val scan: Deferred<ScreenText?>
    )
}

/**
 * The overlay's root view, which also hears what the window is told.
 *
 * The overlay is a focusable window, so it holds window focus until the user goes somewhere else:
 * home, recents, another app, the notification shade or a share sheet all take it. [onFocusLost]
 * reports that - and only after the window has had focus, so a window that never got it (the
 * system was busy with something else as it came up) is never closed for it. It needs no
 * accessibility event, which the service is not set up to receive.
 *
 * [onBack] gets the back gesture. The manifest opts in to predictive back, which routes the gesture
 * past the key event to a registered callback, so both are listened for.
 */
private class OverlayRoot(
    context: Context,
    private val onBack: () -> Unit,
    private val onFocusLost: (View) -> Unit
) : FrameLayout(context) {

    private var hadFocus = false

    private val backCallback = OnBackInvokedCallback { onBack() }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) hadFocus = true else if (hadFocus) onFocusLost(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
            )
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(backCallback)
        }
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}

/**
 * The owners a [ComposeView] reads from the view tree when no activity hosts it.
 *
 * `ComposeView` throws on attach without a lifecycle, a saved-state owner and a view-model owner,
 * and `BackHandler` throws without a back dispatcher; a service has none of the four, so this
 * stands in for the activity. [onCreate] restores the (always empty) saved state before the view is
 * attached — the registry refuses a restore once the owner is started — [onResume] starts the
 * composition's recomposer, and [onDestroy] destroys the lifecycle, which cancels it.
 */
private class OverlayHost :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    fun onCreate() {
        savedState.performRestore(null)
    }

    fun onResume() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
