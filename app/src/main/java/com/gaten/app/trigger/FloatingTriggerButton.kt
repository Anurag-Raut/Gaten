/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import com.gaten.app.R
import com.gaten.app.ui.theme.ThemePreferences
import com.gaten.app.ui.theme.accentRoles
import kotlin.math.hypot

private const val TAG = "Gaten"

/** Bubble diameter; the icon sits centred inside with equal padding. */
private const val BUBBLE_DP = 48
private const val ICON_DP = 24

/** Gap kept between the bubble and the screen's right edge on first show. */
private const val EDGE_MARGIN_DP = 8

/** Distance the finger must travel before a press becomes a drag instead of a tap. */
private const val TOUCH_SLOP_DP = 8f

/** Where the bubble starts vertically: a little above the middle, clear of both screen edges. */
private const val DEFAULT_HEIGHT_FRACTION = 0.45f

/** ~90% opacity, so the bubble reads as a float without hiding the text underneath it. */
private const val BUBBLE_ALPHA = 0xE6

/** Longest [FloatingTriggerButton.hideThen] waits for the cleared bubble to reach the screen. */
private const val GONE_TIMEOUT_MS = 150L

/**
 * The floating lookup trigger: a small bubble in its own accessibility-overlay window.
 *
 * The window is sized to the bubble alone ([WindowManager.LayoutParams.WRAP_CONTENT]) and is
 * `FLAG_NOT_TOUCH_MODAL`, so a touch anywhere else falls through to the app behind instead of
 * being swallowed by an invisible full-screen window. The bubble is a plain [ImageView] because
 * this window is added from the accessibility service — there is no composition to host Compose.
 *
 * The window does not fit the system bars ([WindowManager.LayoutParams.setFitInsetsTypes] cleared,
 * `FLAG_LAYOUT_IN_SCREEN` below API 30), so its `x`/`y` are display coordinates: the position asked
 * for, the one persisted, the one a drag moves on from and the one the window reports all mean the
 * same place on screen. Left at the default, such a window is laid out inside the inset-adjusted
 * parent frame, whose origin sits under the status bar — the bubble then draws, is stored and is
 * reported one status-bar height away from the display position it was given, which is what a tap
 * aimed with those numbers misses. Every position this class takes in — restored, dragged,
 * re-clamped — is clamped into the display area its own context reports, so the bubble can never be
 * stored outside the area it can be touched in.
 *
 * [show]/[hide] are idempotent and hop to the main thread themselves, since adding or removing a
 * window anywhere else throws.
 */
class FloatingTriggerButton(
    private val context: Context,
    private val onTrigger: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val bubblePx = (BUBBLE_DP * density).toInt()
    private val touchSlopPx = TOUCH_SLOP_DP * density
    private val edgeMarginPx = (EDGE_MARGIN_DP * density).toInt()

    /** The bubble on screen, if any; also the source of truth behind [isShowing]. */
    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    // Press bookkeeping for the drag/tap split, reset on every DOWN.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    val isShowing: Boolean
        get() = view != null

    /** Puts the bubble up at its restored (or default) position; a no-op when already up. */
    fun show() = onMain {
        if (view != null) return@onMain
        // The build/position work sits inside the guard too: reading the persisted position and
        // measuring the bubble touch the same resources addView does, and any of it failing must
        // leave the button down rather than propagate. A service has no valid overlay window token
        // until it is connected to the system, so addView can throw (BadTokenException being the
        // common case) if this runs too early, or if the display is being torn down. Swallowing
        // that keeps the whole service process alive.
        try {
            val bubble = buildBubble()
            val layoutParams = buildParams(restoredOrDefaultPosition())
            windowManager.addView(bubble, layoutParams)
            // Only now is the window really on screen; isShowing derives from these.
            view = bubble
            params = layoutParams
        } catch (e: WindowManager.BadTokenException) {
            logWindowNotAdded("add", e)
        } catch (e: WindowManager.InvalidDisplayException) {
            logWindowNotAdded("add", e)
        } catch (e: IllegalStateException) {
            logWindowNotAdded("add", e)
        } catch (e: SecurityException) {
            logWindowNotAdded("add", e)
        } catch (e: RuntimeException) {
            logWindowNotAdded("add", e)
        }
    }

    /** Takes the bubble down; a no-op when it is already down. Drops the window references. */
    fun hide() = onMain {
        val current = view ?: return@onMain
        // Clear the state first: whatever removeViewImmediate does, no view is retained and
        // isShowing ends false, so a failed removal cannot strand a half-gone bubble.
        view = null
        params = null
        try {
            windowManager.removeViewImmediate(current)
        } catch (e: WindowManager.BadTokenException) {
            logWindowNotAdded("remove", e)
        } catch (e: WindowManager.InvalidDisplayException) {
            logWindowNotAdded("remove", e)
        } catch (e: IllegalStateException) {
            logWindowNotAdded("remove", e)
        } catch (e: SecurityException) {
            logWindowNotAdded("remove", e)
        } catch (e: RuntimeException) {
            logWindowNotAdded("remove", e)
        }
    }

    /**
     * Takes the bubble down and runs [onGone] on the main thread once the display no longer shows
     * it, so a screenshot taken then cannot contain it; at once when the bubble is already down.
     *
     * A removed window leaves the screen whenever the window manager gets to it, which can take
     * several frames. Clearing the bubble to transparent instead goes out as an ordinary frame: once
     * that frame is committed to the compositor and one more vsync has passed, it is on screen.
     * [GONE_TIMEOUT_MS] caps the wait in case no frame is ever drawn (screen off).
     */
    fun hideThen(onGone: () -> Unit) = onMain {
        val current = view
        if (current == null) {
            onGone()
            return@onMain
        }
        var finished = false
        val finish = object : Runnable {
            override fun run() {
                if (finished) return
                finished = true
                mainHandler.removeCallbacks(this)
                // Only this bubble: a newer one shown meanwhile is not ours to take down.
                if (view === current) hide()
                onGone()
            }
        }
        current.viewTreeObserver.registerFrameCommitCallback {
            mainHandler.post { Choreographer.getInstance().postFrameCallback { finish.run() } }
        }
        current.alpha = 0f
        mainHandler.postDelayed(finish, GONE_TIMEOUT_MS)
    }

    /** One line per failed window op; [operation] is `add` or `remove`. */
    private fun logWindowNotAdded(operation: String, cause: Exception) {
        Log.w(TAG, "Floating trigger: could not $operation the overlay window (not shown)", cause)
    }

    /**
     * A round [ImageView]: accent-coloured oval behind the tiled lookup glyph, tinted `onPrimary`
     * so it reads on that fill in both light and dark modes. The theme is the app's own choice
     * ([ThemePreferences]), not the device's: this window has no composition to read it from, so
     * the preferences are read here and resolved against the system setting the same way.
     */
    private fun buildBubble(): ImageView {
        val dark = ThemePreferences.themeMode(context).isDark(
            (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
        val tone = accentRoles(ThemePreferences.accent(context), dark)
        val primary = tone.primary.toArgb()
        val onPrimary = tone.onPrimary.toArgb()

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(primary)
            alpha = BUBBLE_ALPHA
        }
        val inset = ((BUBBLE_DP - ICON_DP) / 2f * density).toInt()

        return ImageView(context).apply {
            setImageResource(R.drawable.ic_qs_lookup)
            imageTintList = ColorStateList.valueOf(onPrimary)
            scaleType = ImageView.ScaleType.CENTER
            setBackground(circle)
            setPadding(inset, inset, inset, inset)
            minimumWidth = bubblePx
            minimumHeight = bubblePx
            setOnTouchListener { touched, event -> handleTouch(touched, event) }
        }
    }

    private fun buildParams(position: Pair<Int, Int>) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            // Below API 30 this is what keeps the window out of the inset-fitted parent frame;
            // from 30 on, fitInsetsTypes = 0 below is the supported spelling of the same thing and
            // this flag is ignored.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Do not fit the system bars: x/y are then display coordinates, the same space the stored
        // position and the default below are computed in. See the class comment.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
        x = position.first
        y = position.second
    }

    /**
     * The last dragged position, or a sensible first one: near the right edge at ~45% height. A
     * stored position is clamped too, so one written at another orientation (or by an older build
     * that did not clamp) cannot put the bubble where it can be neither seen nor touched.
     */
    private fun restoredOrDefaultPosition(): Pair<Int, Int> {
        TriggerPreferences.floatingButtonPosition(context)?.let { return clampToDisplay(it) }
        val metrics = context.resources.displayMetrics
        val x = (metrics.widthPixels - bubblePx - edgeMarginPx).coerceAtLeast(0)
        val y = (metrics.heightPixels * DEFAULT_HEIGHT_FRACTION).toInt().coerceAtLeast(0)
        return x to y
    }

    /**
     * Moves the showing bubble back inside the display, for a rotation or a resize that left its
     * position outside the new one. The window manager clips the frame it draws, but the position
     * kept here is what the next drag moves on from: off-display, that drag only resumes once the
     * finger has travelled back past the clipped distance.
     */
    fun reclampToDisplay() = onMain {
        val layoutParams = params ?: return@onMain
        val (x, y) = clampToDisplay(layoutParams.x to layoutParams.y)
        if (x == layoutParams.x && y == layoutParams.y) return@onMain
        layoutParams.x = x
        layoutParams.y = y
        view?.let { windowManager.updateViewLayout(it, layoutParams) }
        TriggerPreferences.setFloatingButtonPosition(context, x, y)
    }

    /**
     * A position with the whole bubble inside the display. The box is this context's own display
     * metrics, which stop at the status and navigation bars rather than at the screen edges, so a
     * clamped bubble is never left under a system bar where the system's own gesture would take the
     * touch. [bubblePx] may exceed a tiny display; the box then collapses to its origin.
     */
    private fun clampToDisplay(position: Pair<Int, Int>): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - bubblePx).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - bubblePx).coerceAtLeast(0)
        return position.first.coerceIn(0, maxX) to position.second.coerceIn(0, maxY)
    }

    /**
     * Press handling, kept on this view's own bounds by the wrap-content window: past the touch
     * slop the press becomes a drag that moves the window, and a release that never got there is a
     * tap. Movement is the whole test — a timer on top of it only adds a second way for a press on
     * this bubble to do nothing at all, and nothing here is bound to a long press to deserve one. A
     * drag is clamped into the display, so a released position is always one the bubble can be seen
     * and touched at.
     */
    private fun handleTouch(touched: View, event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlopPx) dragging = true
                if (dragging) {
                    val (x, y) = clampToDisplay(downX + dx.toInt() to downY + dy.toInt())
                    layoutParams.x = x
                    layoutParams.y = y
                    windowManager.updateViewLayout(touched, layoutParams)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    TriggerPreferences.setFloatingButtonPosition(
                        context, layoutParams.x, layoutParams.y
                    )
                } else {
                    onTrigger()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    TriggerPreferences.setFloatingButtonPosition(
                        context, layoutParams.x, layoutParams.y
                    )
                }
            }
        }
        return true
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
