/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
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
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.toArgb
import com.gaten.app.R
import com.gaten.app.ui.theme.AccentRoles
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.NeutralRoles
import com.gaten.app.ui.theme.ThemePreferences
import com.gaten.app.ui.theme.accentRoles
import com.gaten.app.ui.theme.gatenNeutrals
import kotlin.math.hypot

private const val TAG = "Gaten"

/** Bubble diameter; the icon sits centred inside with equal padding. */
private const val BUBBLE_DP = 48
private const val ICON_DP = 24

/** The drop target's diameter and glyph. */
private const val DISMISS_DP = 48
private const val DISMISS_ICON_DP = 24

/** The hard keyline inside the circle's edge, drawn in the glyph's own colour. */
private const val PERIMETER_DP = 2

/** Gap kept between the bubble and the screen's right edge on first show. */
private const val EDGE_MARGIN_DP = 8

/** Distance the finger must travel before a press becomes a drag instead of a tap. */
private const val TOUCH_SLOP_DP = 8f

/** Where the bubble starts vertically: a little above the middle, clear of both screen edges. */
private const val DEFAULT_HEIGHT_FRACTION = 0.45f

/**
 * Where the drop target sits vertically, as a fraction of the usable display: in the lower half but
 * clear of the very bottom, so the navigation bar and the system's own bottom gesture stay out of
 * the way.
 */
private const val DISMISS_BOTTOM_FRACTION = 0.75f

/** Extra room around the drop target, so a bubble dragged near it still counts as a drop. */
private const val DISMISS_SLOP_DP = 20

/** How far the drop target grows while a dragged bubble is over it. */
private const val DISMISS_HOT_SCALE = 1.15f

/** Longest [FloatingTriggerButton.hideThen] waits for the cleared control to reach the screen. */
private const val GONE_TIMEOUT_MS = 150L

/**
 * The floating lookup trigger: a small bubble in its own accessibility-overlay window, and — while
 * the reader is dragging it — a remove target in a second window, centred horizontally in the lower
 * part of the usable display.
 *
 * The bubble is the only thing that is always on screen. A press that becomes a drag brings the
 * target up, and a release or cancellation takes it down again; a release over the target writes the
 * floating-button preference off and removes the bubble, while a release anywhere else only keeps
 * the bubble's new position. The target is still a control with its own description, so an
 * accessibility service can activate the one action it names.
 *
 * Both windows are `WRAP_CONTENT` and `FLAG_NOT_TOUCH_MODAL`, so a touch anywhere else falls
 * through to the app behind instead of being swallowed by an invisible full-screen window. Both are
 * plain Android views because these windows are added from the accessibility service — there is no
 * composition to host Compose.
 *
 * Neither window fits the system bars ([WindowManager.LayoutParams.setFitInsetsTypes] cleared,
 * `FLAG_LAYOUT_IN_SCREEN` below API 30), so their `x`/`y` are display coordinates: the position
 * asked for, the one persisted, the one a drag moves on from and the one the window reports all mean
 * the same place on screen. Left at the default, such a window is laid out inside the inset-adjusted
 * parent frame, whose origin sits under the status bar — the bubble then draws, is stored and is
 * reported one status-bar height away from the display position it was given, which is what a tap
 * aimed with those numbers misses. Every bubble position this class takes in — restored, dragged,
 * re-clamped — is clamped into the display area its own context reports, so the bubble can never be
 * stored outside the area it can be touched in. The target is placed by the display's own centre and
 * the same box, so a rotation re-centres it too.
 *
 * [show]/[hide] are idempotent and hop to the main thread themselves, since adding or removing a
 * window anywhere else throws.
 *
 * The bubble's motion is its opacity: it fades in where it was left, and fades out when the reader
 * switches it off or a drag drops it on the target. The target has its own short fade of its own.
 * The bubble also changes colour under a finger, and the target changes colour while the dragged
 * finger is over it; neither is a geometry animation — the drag and the clamp answer a finger, and
 * the service takes the windows down outright before a capture.
 */
class FloatingTriggerButton(
    private val context: Context,
    private val onTrigger: () -> Unit,
    private val onDismissed: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val bubblePx = (BUBBLE_DP * density).toInt()
    private val dismissPx = (DISMISS_DP * density).toInt()
    private val dismissSlopPx = (DISMISS_SLOP_DP * density).toInt()
    private val touchSlopPx = TOUCH_SLOP_DP * density
    private val edgeMarginPx = (EDGE_MARGIN_DP * density).toInt()
    private val strokePx = (PERIMETER_DP * density).toInt()

    /** The bubble on screen, if any; also the source of truth behind [isShowing]. */
    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    /** The remove target, up only for the length of a drag. */
    private var dismissView: ImageView? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    /**
     * The bubble itself, kept separately because the press feedback repaints this one view, while
     * the window operations always address [view].
     */
    private var bubbleView: ImageView? = null

    /** The bubble's fill and stroke in both states, so a press only repaints the drawable. */
    private var bubbleBackground: GradientDrawable? = null
    private var bubbleFillNormal = 0
    private var bubbleFillPressed = 0
    private var bubbleInkNormal = 0
    private var bubbleInkPressed = 0

    /** The target's own two states, so a drag over it can say so without rebuilding the view. */
    private var dismissBackground: GradientDrawable? = null
    private var dismissFillNormal = 0
    private var dismissInkNormal = 0
    private var dismissStrokeNormal = 0
    private var dismissFillHot = 0
    private var dismissInkHot = 0

    /**
     * Whatever is writing the bubble's opacity: an entrance, or the fade out of a bubble the reader
     * switched off. One at a time, so two of them can never fight over it.
     */
    private var alphaAnimator: ValueAnimator? = null

    /**
     * Whatever is writing the target's opacity: the fade in of a drag's target, or its fade out on
     * release or cancellation. Separate from the bubble's, which never runs at the same time as
     * these in a way that matters.
     */
    private var dismissAlphaAnimator: ValueAnimator? = null

    /** Whatever is writing the target's over-it highlight, and how hot it currently is (0..1). */
    private var dismissHotAnimator: ValueAnimator? = null
    private var dismissHot = 0f

    /** True from the start of a bubble fade out until the control is gone or asked for again. */
    private var fadingOut = false

    // Press bookkeeping for the drag/tap split, reset on every DOWN.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false
    private var overDismiss = false

    /**
     * True while the bubble is up and staying up. A bubble fading out is on its way down and says so,
     * which is what has the service ask for it again if the reader switches it back on inside that
     * fade.
     */
    val isShowing: Boolean
        get() = view != null && !fadingOut

    /** Puts the bubble up at its restored (or default) position; a no-op when already up. */
    fun show() = onMain {
        val existing = view
        if (existing != null) {
            // A bubble still fading out — switched back on inside that fade — is this bubble coming
            // back, not a second one: take the fade over and raise the opacity where it was left
            // standing.
            if (fadingOut) {
                fadingOut = false
                pressColor(false)
                animateBubbleAlpha(1f, Motion.emphasized)
            }
            return@onMain
        }
        // The build/position work sits inside the guard too: reading the persisted position and
        // measuring the views touch the same resources addView does, and any of it failing must
        // leave the bubble down rather than propagate. A service has no valid overlay window token
        // until it is connected to the system, so addView can throw (BadTokenException being the
        // common case) if this runs too early, or if the display is being torn down. Swallowing
        // that keeps the whole service process alive.
        try {
            val tone = accentRoles(ThemePreferences.accent(context), isDark())
            val bubble = buildBubble(tone)
            val layoutParams = overlayParams(restoredOrDefaultPosition())
            // Invisible until the entrance's first frame lands, so a bubble returning from a
            // capture cannot flash back at full strength.
            bubble.alpha = 0f
            windowManager.addView(bubble, layoutParams)
            // Only now is the window really on screen; isShowing derives from these.
            view = bubble
            params = layoutParams
            animateBubbleAlpha(1f, Motion.emphasized)
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

    /** Takes the bubble, and any target still up, down; a no-op when already down. */
    fun hide() = onMain {
        val current = view ?: return@onMain
        if (fadesOut(current)) {
            fadingOut = true
            stopDismissAlpha()
            dismissView?.let { animateDismissAlpha(it, 0f, Motion.emphasized) }
            animateBubbleAlpha(0f, Motion.emphasized) {
                fadingOut = false
                if (view === current) removeNow()
            }
        } else {
            removeNow()
        }
    }

    /**
     * Whether this hide may be seen going. Exactly one hide may: the reader switched the control
     * off — with the drop target or in settings, both of which leave the preference false while the
     * bubble is still on screen and opaque. Every other hide — a lock, a teardown, the overlay
     * coming up — is about not being on screen a frame longer than needed, and one of them
     * ([hideThen]) is waited on frame by frame before a screenshot.
     */
    private fun fadesOut(current: View): Boolean =
        current.alpha > 0f && !TriggerPreferences.floatingButtonEnabled(context)

    /**
     * Drops both windows. The state goes first: whatever removeViewImmediate does, no view is
     * retained and isShowing ends false, so a failed removal cannot strand a half-gone control.
     */
    private fun removeNow() {
        stopAlpha()
        stopDismissAlpha()
        stopDismissHot()
        fadingOut = false
        val bubble = view
        val target = dismissView
        view = null
        params = null
        dismissView = null
        dismissParams = null
        bubbleView = null
        bubbleBackground = null
        dismissBackground = null
        bubble?.let { removeWindow(it) }
        target?.let { removeWindow(it) }
    }

    /** One window's own removal, so a failure on one never blocks the other. */
    private fun removeWindow(target: View) {
        try {
            windowManager.removeViewImmediate(target)
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
     * Takes the control down and runs [onGone] on the main thread once the display no longer shows
     * it, so a screenshot taken then cannot contain either window; at once when it is already down.
     *
     * A removed window leaves the screen whenever the window manager gets to it, which can take
     * several frames. Clearing the control to transparent instead goes out as an ordinary frame:
     * once that frame is committed to the compositor and one more vsync has passed, it is on screen.
     * Both windows are cleared in the same main-thread pass, so one frame commit covers them both;
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
        // The opacity is written here and now, so nothing else may be writing it: an entrance or a
        // fade still running would put the control back on screen inside this wait. Any fade is over
        // with it — from here these windows only have one way left to go.
        stopAlpha()
        stopDismissAlpha()
        fadingOut = false
        current.alpha = 0f
        dismissView?.alpha = 0f
        mainHandler.postDelayed(finish, GONE_TIMEOUT_MS)
    }

    /** One line per failed window op; [operation] is `add` or `remove`. */
    private fun logWindowNotAdded(operation: String, cause: Exception) {
        Log.w(TAG, "Floating trigger: could not $operation the overlay window (not shown)", cause)
    }

    /** The app's own light/dark choice resolved against the system setting, as the theme does. */
    private fun isDark(): Boolean = ThemePreferences.themeMode(context).isDark(
        (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    )

    /**
     * Builds the remove target and fades it in. Called once a press has become a drag; a no-op when
     * the target is already up, which is what a re-entry inside its own fade out looks like.
     */
    private fun showDismiss() {
        if (view == null) return
        if (dismissView != null) {
            // A target still fading out from the last release is this drag's target coming back:
            // take the fade over and raise it where it was left standing.
            stopDismissAlpha()
            resetDismissHighlight()
            dismissView?.let { animateDismissAlpha(it, 1f, Motion.emphasized) }
            return
        }
        try {
            val tone = accentRoles(ThemePreferences.accent(context), isDark())
            val neutral = gatenNeutrals(isDark())
            val target = buildDismiss(neutral, tone)
            val layoutParams = overlayParams(dismissPosition())
            target.alpha = 0f
            windowManager.addView(target, layoutParams)
            dismissView = target
            dismissParams = layoutParams
            resetDismissHighlight()
            animateDismissAlpha(target, 1f, Motion.emphasized)
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

    /** Fades the target out and drops its window; a no-op when it is already down. */
    private fun hideDismiss() {
        val target = dismissView ?: return
        stopDismissHot()
        animateDismissAlpha(target, 0f, Motion.emphasized) {
            if (dismissView === target) {
                dismissView = null
                dismissParams = null
                dismissBackground = null
                removeWindow(target)
            }
        }
    }

    /**
     * A round [ImageView]: an opaque accent disk, a hard keyline inside its edge, and the tiled
     * lookup glyph tinted `onPrimary` so it reads on that fill in both light and dark modes. The
     * theme is the app's own choice ([ThemePreferences]), not the device's: this window has no
     * composition to read it from, so the preferences are read here and resolved against the system
     * setting the same way.
     *
     * The fill is opaque and the keyline is the paired ink rather than a paler accent. This disk
     * floats over whatever the other app happens to be drawing, so it has to name its action by
     * itself on a dark page and on a bright one — a translucent disk, or one held together by a
     * hairline alone, would not.
     */
    private fun buildBubble(tone: AccentRoles): ImageView {
        val primary = tone.primary.toArgb()
        val onPrimary = tone.onPrimary.toArgb()

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // One solid colour and one stroke, never a gradient. A drawable's stroke is inset
            // inside its bounds, which is where this keyline belongs: inside the 48 dp circle.
            setColor(primary)
            setStroke(strokePx, onPrimary)
        }
        val inset = ((BUBBLE_DP - ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_hint)

        // The press state repaints this drawable and tint; the entrance/fade animator writes the
        // window's alpha, never the background's, so the two never touch the same property.
        bubbleBackground = circle
        bubbleFillNormal = primary
        bubbleFillPressed = tone.primaryContainer.toArgb()
        bubbleInkNormal = onPrimary
        bubbleInkPressed = tone.onPrimaryContainer.toArgb()

        return ImageView(context).apply {
            setImageResource(R.drawable.ic_qs_lookup)
            imageTintList = ColorStateList.valueOf(onPrimary)
            scaleType = ImageView.ScaleType.CENTER
            setBackground(circle)
            setPadding(inset, inset, inset, inset)
            minimumWidth = bubblePx
            minimumHeight = bubblePx
            contentDescription = context.getString(R.string.bubble_lookup_label)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    // The description names the action; the hint is what a finger on it does,
                    // which an icon on its own cannot say.
                    info.hintText = hint
                }
            }
            setOnTouchListener { touched, event -> handleTouch(touched, event) }
            // A finger is handled above — drag and tap both — so a real touch never reaches this.
            // It is what an accessibility service activates, though, and without it the label and
            // hint above would be describing an action nobody can perform.
            setOnClickListener { onTrigger() }
        }.also { bubbleView = it }
    }

    /**
     * The remove target: the neutral surface disk with a hairline and the paired ink, so it reads on
     * any page it floats over, and the app's close glyph. It carries its own description and hint so
     * an accessibility service can perform the one action it names.
     *
     * While a dragged bubble is over it the disk switches to the accent with the paired ink and grows
     * a little, so the drop the release is about to perform is visible before the finger lifts.
     */
    private fun buildDismiss(neutral: NeutralRoles, tone: AccentRoles): ImageView {
        val fillNormal = neutral.surface.toArgb()
        val inkNormal = neutral.onSurface.toArgb()
        val strokeNormal = neutral.outline.toArgb()
        val disk = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillNormal)
            setStroke(strokePx, strokeNormal)
        }
        dismissBackground = disk
        dismissFillNormal = fillNormal
        dismissInkNormal = inkNormal
        dismissStrokeNormal = strokeNormal
        dismissFillHot = tone.primary.toArgb()
        dismissInkHot = tone.onPrimary.toArgb()

        val inset = ((DISMISS_DP - DISMISS_ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_close_hint)
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_close)
            imageTintList = ColorStateList.valueOf(inkNormal)
            scaleType = ImageView.ScaleType.CENTER
            setBackground(disk)
            setPadding(inset, inset, inset, inset)
            minimumWidth = dismissPx
            minimumHeight = dismissPx
            contentDescription = context.getString(R.string.bubble_lookup_close_label)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = hint
                }
            }
            setOnClickListener { dismissByReader() }
        }
    }

    /** The shared params for one overlay window, positioned in display coordinates. */
    private fun overlayParams(position: Pair<Int, Int>) = WindowManager.LayoutParams(
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
     * The target's own position: horizontally centred, and [DISMISS_BOTTOM_FRACTION] down the usable
     * display, clamped so a very short display still keeps it whole and above the very bottom.
     */
    private fun dismissPosition(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val x = ((metrics.widthPixels - dismissPx) / 2).coerceAtLeast(0)
        val y = (metrics.heightPixels * DISMISS_BOTTOM_FRACTION).toInt()
            .coerceIn(0, (metrics.heightPixels - dismissPx).coerceAtLeast(0))
        return x to y
    }

    /**
     * Moves the showing bubble back inside the display, for a rotation or a resize that left its
     * position outside the new one, and re-centres a target that is up on the new display. The
     * window manager clips the frame it draws, but the position kept here is what the next drag
     * moves on from: off-display, that drag only resumes once the finger has travelled back past the
     * clipped distance.
     */
    fun reclampToDisplay() = onMain {
        val layoutParams = params
        val root = view
        if (layoutParams != null && root != null) {
            val (x, y) = clampToDisplay(layoutParams.x to layoutParams.y)
            if (x != layoutParams.x || y != layoutParams.y) {
                layoutParams.x = x
                layoutParams.y = y
                windowManager.updateViewLayout(root, layoutParams)
                TriggerPreferences.setFloatingButtonPosition(context, x, y)
            }
        }
        val target = dismissView
        val targetParams = dismissParams
        if (target != null && targetParams != null) {
            val (targetX, targetY) = dismissPosition()
            if (targetX != targetParams.x || targetY != targetParams.y) {
                targetParams.x = targetX
                targetParams.y = targetY
                windowManager.updateViewLayout(target, targetParams)
            }
        }
    }

    /**
     * A position with the whole bubble inside the display. The box is this context's own display
     * metrics, which stop at the status and navigation bars rather than at the screen edges, so a
     * clamped bubble is never left under a system bar where the system's own gesture would take the
     * touch. The bubble may exceed a tiny display; the box then collapses to its origin.
     */
    private fun clampToDisplay(position: Pair<Int, Int>): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - bubblePx).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - bubblePx).coerceAtLeast(0)
        return position.first.coerceIn(0, maxX) to position.second.coerceIn(0, maxY)
    }

    /**
     * Press handling, kept on the bubble's own bounds: past the touch slop the press becomes a drag,
     * the remove target comes up, and the bubble's window follows the finger. Movement is the whole
     * test — a timer on top of it only adds a second way for a press on this bubble to do nothing at
     * all, and nothing here is bound to a long press to deserve one. A drag is clamped into the
     * display, so a released position is always one the bubble can be seen and touched at.
     *
     * A press repaints the bubble in the pressed colour and the drag gives the normal colour back:
     * once the finger is moving the window, the position is the feedback and the colour has nothing
     * left to say. While the finger is over the target, the target says so; a release there removes
     * the bubble, and a release anywhere else only stores its new position. A cancellation never
     * removes it.
     */
    private fun handleTouch(touched: View, event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        val root = view ?: return false
        if (fadingOut) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                dragging = false
                overDismiss = false
                pressColor(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlopPx) {
                    dragging = true
                    pressColor(false)
                    showDismiss()
                }
                if (dragging) {
                    val (x, y) = clampToDisplay(downX + dx.toInt() to downY + dy.toInt())
                    layoutParams.x = x
                    layoutParams.y = y
                    windowManager.updateViewLayout(root, layoutParams)
                    val over = isOverDismiss(event.rawX, event.rawY)
                    if (over != overDismiss) {
                        overDismiss = over
                        animateDismissHighlight(over)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                pressColor(false)
                val dropped = dragging && overDismiss
                overDismiss = false
                if (dragging) {
                    if (dropped) {
                        // The reader dropped the bubble on the remove target: the same dismissal the
                        // target's own activation performs, so the preference, the fade and the
                        // settings-page notification all take the one path.
                        dismissByReader()
                    } else {
                        animateDismissHighlight(false)
                        TriggerPreferences.setFloatingButtonPosition(
                            context, layoutParams.x, layoutParams.y
                        )
                        hideDismiss()
                    }
                } else {
                    onTrigger()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pressColor(false)
                overDismiss = false
                if (dragging) {
                    animateDismissHighlight(false)
                    TriggerPreferences.setFloatingButtonPosition(
                        context, layoutParams.x, layoutParams.y
                    )
                    hideDismiss()
                }
            }
        }
        return true
    }

    /** Whether the finger that is dragging the bubble is over the remove target. */
    private fun isOverDismiss(rawX: Float, rawY: Float): Boolean {
        val target = dismissParams ?: return false
        return rawX >= target.x - dismissSlopPx &&
            rawX <= target.x + dismissPx + dismissSlopPx &&
            rawY >= target.y - dismissSlopPx &&
            rawY <= target.y + dismissPx + dismissSlopPx
    }

    /**
     * The reader removed the floating control: write the preference off and let the ordinary hide
     * fade it away. Only the floating-button preference moves — lookup mode and the Quick Settings
     * tile are separate switches and stay as they were. The Screen lookup settings page reads this
     * preference, so switching Floating button back on shows the bubble again.
     */
    private fun dismissByReader() {
        TriggerPreferences.setFloatingButtonEnabled(context, false)
        // The Screen lookup page, if it is open, re-reads the switch from this notification; the
        // service owns the bridge, so the button only reports the dismissal.
        onDismissed()
        hide()
    }

    /**
     * Repaints the bubble in the pressed or normal theme tones: the accent's container and its
     * paired ink while a finger is down, the accent's primary and `onPrimary` otherwise. Both pairs
     * are the app's own, so the glyph stays readable on the disk in light and dark mode.
     */
    private fun pressColor(pressed: Boolean) {
        val background = bubbleBackground ?: return
        val bubble = bubbleView ?: return
        val ink = if (pressed) bubbleInkPressed else bubbleInkNormal
        background.setColor(if (pressed) bubbleFillPressed else bubbleFillNormal)
        background.setStroke(strokePx, ink)
        bubble.imageTintList = ColorStateList.valueOf(ink)
    }

    /**
     * Eases the remove target between its resting and over-it states: the accent disk with the
     * paired ink, grown a little. The fraction is animated over [Motion.SHORT] with the theme's press
     * easing, so the highlight arrives with the finger instead of snapping; at a zero animator
     * duration scale it lands on its end value at once.
     */
    private fun animateDismissHighlight(highlighted: Boolean) {
        val background = dismissBackground ?: return
        val target = dismissView ?: return
        val to = if (highlighted) 1f else 0f
        stopDismissHot()
        val animator = ValueAnimator.ofFloat(dismissHot, to).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { Motion.pressEasing.transform(it) })
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                dismissHot = fraction
                applyDismissHot(background, target, fraction)
            }
        }
        dismissHotAnimator = animator
        animator.start()
    }

    /** Writes the over-it fraction onto the target's disk, glyph and scale in one pass. */
    private fun applyDismissHot(background: GradientDrawable, target: ImageView, fraction: Float) {
        val evaluator = ArgbEvaluator()
        val ink = evaluator.evaluate(fraction, dismissInkNormal, dismissInkHot) as Int
        background.setColor(evaluator.evaluate(fraction, dismissFillNormal, dismissFillHot) as Int)
        background.setStroke(
            strokePx,
            evaluator.evaluate(fraction, dismissStrokeNormal, dismissInkHot) as Int
        )
        target.imageTintList = ColorStateList.valueOf(ink)
        val scale = 1f + (DISMISS_HOT_SCALE - 1f) * fraction
        target.scaleX = scale
        target.scaleY = scale
    }

    /** Writes the resting state directly, for a target that has just been built. */
    private fun resetDismissHighlight() {
        val background = dismissBackground ?: return
        val target = dismissView ?: return
        stopDismissHot()
        dismissHot = 0f
        applyDismissHot(background, target, 0f)
    }

    /**
     * Moves the bubble's opacity over [Motion.SHORT] with [easing]. One animator at a time: whatever
     * was running is stopped first, and cleared before the stop, so its own end callback sees itself
     * superseded — that is what keeps an entrance and a fade out from writing the same property
     * together. [onEnd] runs only for the animation that really finished.
     */
    private fun animateBubbleAlpha(to: Float, easing: Easing, onEnd: (() -> Unit)? = null) {
        val bubble = view ?: return
        stopAlpha()
        val animator = ValueAnimator.ofFloat(bubble.alpha, to).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { easing.transform(it) })
            addUpdateListener { animation -> bubble.alpha = animation.animatedValue as Float }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (alphaAnimator !== animation) return
                alphaAnimator = null
                onEnd?.invoke()
            }
        })
        alphaAnimator = animator
        animator.start()
    }

    /**
     * The target's own fade over the same short duration with [easing]. Same one-animator rule as the
     * bubble's, so a drag that starts again inside a release's fade out takes it over instead of
     * removing the target underneath the new drag.
     */
    private fun animateDismissAlpha(
        target: View,
        to: Float,
        easing: Easing,
        onEnd: (() -> Unit)? = null
    ) {
        stopDismissAlpha()
        val animator = ValueAnimator.ofFloat(target.alpha, to).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { easing.transform(it) })
            addUpdateListener { animation -> target.alpha = animation.animatedValue as Float }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (dismissAlphaAnimator !== animation) return
                dismissAlphaAnimator = null
                onEnd?.invoke()
            }
        })
        dismissAlphaAnimator = animator
        animator.start()
    }

    /** Stops whatever is writing the bubble's opacity, so a direct write is the last word on it. */
    private fun stopAlpha() {
        val running = alphaAnimator ?: return
        alphaAnimator = null
        running.cancel()
    }

    /** Stops whatever is writing the target's opacity. */
    private fun stopDismissAlpha() {
        val running = dismissAlphaAnimator ?: return
        dismissAlphaAnimator = null
        running.cancel()
    }

    /** Stops whatever is writing the target's over-it highlight. */
    private fun stopDismissHot() {
        val running = dismissHotAnimator ?: return
        dismissHotAnimator = null
        running.cancel()
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
