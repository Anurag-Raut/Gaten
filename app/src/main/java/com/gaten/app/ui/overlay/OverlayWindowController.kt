/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
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
import com.gaten.app.data.BitmapRepository
import com.gaten.app.media.MediaPauser
import com.gaten.app.ui.lookup.LookupOverlay
import com.gaten.app.ui.theme.GatenTheme

private const val TAG = "Gaten"

/** How long the frozen frame is on screen before the media behind it is paused. */
private const val PauseAfterFreezeMs = 100L

/** Longest the overlay waits for its frozen first frame to reach the display. */
private const val FreezeTimeoutMs = 250L

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
    /** Runs on the main thread once the overlay is down and media resumed; the service re-shows its button. */
    private val onHidden: () -> Unit
) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The overlay on screen, if any. */
    private var showing: Showing? = null

    /**
     * Puts [bitmap]'s capture on screen, replacing any overlay already up, and pauses the media
     * behind it once the frozen frame is really showing.
     *
     * The window goes up holding nothing but the capture, so its first frame - the freeze - is as
     * cheap as a frame can be; the lookup UI is composed into it only after that frame is on screen.
     * The pause waits [PauseAfterFreezeMs] past the same point: a player raises its controls the
     * moment it pauses, and paused any earlier they flash up before the freeze covers them.
     */
    fun show(bitmap: Bitmap) {
        hide()

        val host = OverlayHost()
        val root = FrameLayout(context)
        root.setViewTreeLifecycleOwner(host)
        root.setViewTreeViewModelStoreOwner(host)
        root.setViewTreeSavedStateRegistryOwner(host)
        root.setViewTreeOnBackPressedDispatcherOwner(host)
        // The capture itself, unscaled from the window's origin, is the freeze: the app keeps
        // running underneath and none of it shows through. Drawn 1:1 (an identity matrix), it lines
        // up with the recognised boxes, which are in the same screenshot pixels.
        root.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.MATRIX
                setImageBitmap(bitmap)
            },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )

        // The composition reads the owners on attach, so the lifecycle has to be CREATED before the
        // window is added and RESUMED once it is up, exactly as an activity would be.
        host.onCreate()
        windowManager.addView(root, windowParams())
        host.onResume()
        val current = Showing(root, host)
        showing = current
        Log.d(TAG, "Overlay window added for a ${bitmap.width}x${bitmap.height} capture")

        var frozen = false
        val onFrozen = Runnable {
            if (frozen || showing !== current) return@Runnable
            frozen = true
            Log.d(TAG, "Frozen frame on screen")
            // Scheduled first: composing the lookup UI below blocks this thread for a while.
            mediaPauser.pause(PauseAfterFreezeMs)
            root.addView(lookupView(bitmap), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        // Committed, then one vsync more: the frame is on the display. The timeout covers a frame
        // that is never drawn (screen off) so the lookup still opens and the media still pauses.
        root.viewTreeObserver.registerFrameCommitCallback {
            mainHandler.post { Choreographer.getInstance().postFrameCallback { onFrozen.run() } }
        }
        mainHandler.postDelayed(onFrozen, FreezeTimeoutMs)
    }

    /** The lookup UI, transparent apart from what it draws over the capture beneath it. */
    private fun lookupView(bitmap: Bitmap) = ComposeView(context).apply {
        setContent {
            GatenTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    LookupOverlay(screenshot = bitmap, onDismiss = ::hide)
                }
            }
        }
    }

    /** Takes the overlay down when it is up; true when there was one to take down. */
    fun hideIfShowing(): Boolean {
        if (showing == null) return false
        hide()
        return true
    }

    /** Takes the overlay down and drops the capture it was drawn from. */
    fun hide() {
        val current = showing
        // Resume only when an overlay is really coming down; show() starts by calling hide(). One
        // closed before its delayed pause fired resumes nothing, and resume() cancels that pause.
        if (current != null) mediaPauser.resume()
        showing = null
        if (current == null) return
        windowManager.removeViewImmediate(current.view)
        current.host.onDestroy()
        BitmapRepository.clear()
        Log.d(TAG, "Overlay window removed")
        // The window is gone and media is playing again: whoever hid it can come back now.
        onHidden()
    }

    /**
     * Full-screen and transparent, so the overlay sits exactly where the capture was taken: the
     * lasso and the recognised boxes are in screenshot pixels, and those are only the same as view
     * pixels when the window covers the whole display from its origin. By default the system keeps
     * a window out of the cutout band while the status bar is hidden - over fullscreen video - and
     * shrinks it for a visible navigation bar, so both are switched off.
     */
    private fun windowParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) fitInsetsTypes = 0
    }

    /** The overlay on screen: the view to detach and the host its lifecycle runs on. */
    private class Showing(val view: View, val host: OverlayHost)
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
