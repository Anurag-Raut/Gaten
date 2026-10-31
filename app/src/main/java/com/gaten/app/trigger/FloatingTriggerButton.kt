/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.toArgb
import com.gaten.app.R
import com.gaten.app.ui.Feedback
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.theme.AccentRoles
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.NeutralRoles
import com.gaten.app.ui.theme.ThemePreferences
import com.gaten.app.ui.theme.accentRoles
import com.gaten.app.ui.theme.gatenNeutrals
import kotlin.math.ceil
import kotlin.math.hypot

private const val TAG = "Gaten"

/** Bubble diameter; the icon sits centred inside with equal padding. */
private const val BUBBLE_DP = 48
private const val ICON_DP = 24

/** How much the bubble grows under a tap; it comes straight back down and never changes colour. */
private const val BUBBLE_PRESS_SCALE = 1.15f

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
 * The gap kept between the enlarged drop target and the bottom of the usable display, on top of the
 * system-bar inset the usable bounds already exclude. The target grows into this gap, so the finger
 * never has to reach into the navigation bar or the system's own bottom gesture.
 */
private const val DISMISS_BOTTOM_MARGIN_DP = 32

/** Extra room around the drop target, so a bubble dragged near it still counts as a drop. */
private const val DISMISS_SLOP_DP = 20

/**
 * How near the remove target a dragged bubble starts being drawn toward it, in dp. The pull is
 * strongest at the target's centre and gone at this radius, so a finger coming near the target
 * carries the bubble onto it and a release there is a drop.
 */
private const val DISMISS_ATTRACT_DP = 96f

/** How much the drop target grows while a dragged bubble is over it; it never changes colour. */
private const val DISMISS_HOT_SCALE = 1.15f

/** Longest [FloatingTriggerButton.hideThen] waits for the cleared control to reach the screen. */
private const val GONE_TIMEOUT_MS = 150L

/**
 * The floating lookup trigger: a small bubble in its own accessibility-overlay window, and a remove
 * target in a second window, centred horizontally near the bottom of the usable display, above the
 * system bars. The target is drawn only while the reader is dragging; its window is built and added
 * with the bubble's and hidden between drags.
 *
 * The bubble is the only thing always drawn. A press that becomes a drag brings the target up, and a
 * release or cancellation takes it down again; a release over the target writes the floating-button
 * preference off and removes the bubble, while a release anywhere else only keeps the bubble's new
 * position. The target is still a control with its own description, so an accessibility service can
 * activate the one action it names.
 *
 * The target's window is added before the bubble's, and is never removed and re-added: two
 * accessibility-overlay windows of the same type stack in the order they were added, the last one
 * added drawing on top. The bubble must stay in front of the target when a drag brings the two
 * together, and a bubble removed and added back mid-drag would cancel the very gesture that is
 * moving it, so the target is added first and stays under the bubble, hidden by its own view
 * between drags rather than by taking its window down.
 *
 * Both windows are `WRAP_CONTENT` and `FLAG_NOT_TOUCH_MODAL`, so a touch anywhere else falls
 * through to the app behind instead of being swallowed by an invisible full-screen window. The
 * target's view is `GONE` between drags, so its window measures to nothing and cannot take a touch
 * there either. Both are plain Android views because these windows are added from the accessibility
 * service — there is no composition to host Compose.
 *
 * Neither window fits the system bars ([WindowManager.LayoutParams.setFitInsetsTypes] cleared,
 * `FLAG_LAYOUT_IN_SCREEN` below API 30), so their `x`/`y` are display coordinates: the position
 * asked for, the one persisted, the one a drag moves on from and the one the window reports all mean
 * the same place on screen. Left at the default, such a window is laid out inside the inset-adjusted
 * parent frame, whose origin sits under the status bar — the bubble then draws, is stored and is
 * reported one status-bar height away from the display position it was given, which is what a tap
 * aimed with those numbers misses. Every bubble position this class takes in — restored, dragged,
 * re-clamped — is clamped into the display area its own context reports, so the bubble can never be
 * stored outside the area it can be touched in. The target is placed from the same usable bounds the
 * system reports (the display less the system-bar insets), so it keeps its distance from the bottom
 * in both orientations and moves with a rotation.
 *
 * [show]/[hide] are idempotent and hop to the main thread themselves, since adding or removing a
 * window anywhere else throws.
 *
 * The bubble's motion is its opacity: it fades in where it was left, and fades out when the reader
 * switches it off or a drag drops it on the target — only ever alpha, never a scale. The target has a
 * short fade of its own, and grows while the dragged finger is over it and shrinks back on the way out;
 * its colours, keyline and round silhouette never change, so a finger on it says only "bigger", never
 * "different". The bubble's own disk answers a tap the same way: it grows and comes straight back down,
 * with its colours untouched. The lookup is fired on the tap itself, not at the end of that shrink:
 * an accepted capture takes the bubble off screen in the same pass, so the shrink is never drawn for
 * it, and waiting the animation out only added its duration to every lookup. The drag, the clamp and
 * the pull toward the target are not animations — they answer a finger — and the service takes the
 * windows down outright before a capture.
 *
 * A release docks the bubble to the nearer vertical edge, at the height it was let go at: the dock is
 * the one motion on the drag path, a short glide on the shared easing, and the docked position is
 * what is stored, so the bubble always rests where it can be seen and touched. A finger near the
 * remove target draws the bubble toward it while still down — the pull recomputed from the finger on
 * every move, never accumulated — so a release there is a drop and moving away lets the bubble back.
 * Each touch gives one tick through the app's shared haptics, which respect the phone's own haptic
 * setting: a tap at the press, and a removal on the single dismissal path, whether the reader dropped
 * the bubble on the target or activated the target itself.
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

    /**
     * The bubble's window is larger than the disk it draws, by [BUBBLE_PRESS_SCALE]. The transparent
     * ring left over is what the disk grows into on a tap: a scaled view is clipped to its window,
     * so a window the size of the disk would shear the grown disk into a rounded square.
     */
    private val bubbleWindowPx = ceil(bubblePx * BUBBLE_PRESS_SCALE).toInt()
    private val dismissPx = (DISMISS_DP * density).toInt()

    /**
     * The drop target's window is larger than the circle it draws, by [DISMISS_HOT_SCALE]. The
     * transparent ring left over is what the circle grows into: a scaled view is clipped to its
     * window, so a window the size of the circle would shear the enlarged circle into a rounded
     * square. The window — and so the forgiving hit area — is this wide; the circle inside stays
     * [dismissPx] at rest.
     */
    private val dismissWindowPx = ceil(dismissPx * DISMISS_HOT_SCALE).toInt()
    private val dismissSlopPx = (DISMISS_SLOP_DP * density).toInt()
    private val dismissBottomMarginPx = (DISMISS_BOTTOM_MARGIN_DP * density).toInt()
    private val touchSlopPx = TOUCH_SLOP_DP * density
    private val edgeMarginPx = (EDGE_MARGIN_DP * density).toInt()
    private val strokePx = (PERIMETER_DP * density).toInt()

    /** The bubble on screen, if any; also the source of truth behind [isShowing]. */
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** The remove target's window, up only for the length of a drag, and the circle inside it. */
    private var dismissView: FrameLayout? = null
    private var dismissCircle: ImageView? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    /**
     * The bubble's disk, kept separately because the tap feedback scales this one view, while the
     * window operations always address [view]. The disk is the child of that window; the window is
     * the transparent box the disk grows into.
     */
    private var bubbleView: ImageView? = null

    /** Whatever is writing the bubble disk's tap scale, and the scale it is at now. */
    private var bubbleScaleAnimator: ValueAnimator? = null
    private var bubbleScale = 1f

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

    /** Whatever is writing the target circle's over-it scale, and the scale it is at now. */
    private var dismissScaleAnimator: ValueAnimator? = null
    private var dismissScale = 1f

    /** Whatever is writing a release's dock to the nearer edge. */
    private var snapAnimator: ValueAnimator? = null

    /**
     * What gives a touch its tick: the bubble disk on screen, wrapped in the app's shared haptics so
     * a tap, a drop on the remove target and the target's own activation all respect the phone's own
     * haptic setting. Rebuilt with each bubble, since haptics come from the view on screen.
     */
    private var feedback: Feedback? = null

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
                resetBubbleScale()
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
            // The target's window goes up first and stays under the bubble: same-type overlay
            // windows stack in the order they are added, and the bubble must stay in front of the
            // target when a drag brings the two together. Its own failure is contained — the bubble
            // still shows, and showDismiss falls back to adding a target of its own.
            addDismissWindow()
            try {
                windowManager.addView(bubble, layoutParams)
            } catch (e: RuntimeException) {
                // The bubble never came up; take the target's already-added window back down rather
                // than leave a window the reader never asked for.
                removeNow()
                throw e
            }
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
        stopDismissScale()
        stopBubbleScale()
        stopSnap()
        fadingOut = false
        val bubble = view
        val target = dismissView
        view = null
        params = null
        dismissView = null
        dismissParams = null
        bubbleView = null
        dismissCircle = null
        feedback?.release()
        feedback = null
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
     * Builds the remove target and adds its window, hidden. Added with the bubble's, so the bubble —
     * added after — stays in front of it, never the other way round. A failure is logged and
     * swallowed: a bubble without a target is still a working bubble.
     */
    private fun addDismissWindow() {
        if (dismissView != null) return
        try {
            val neutral = gatenNeutrals(isDark())
            val target = buildDismiss(neutral)
            val layoutParams = overlayParams(dismissPosition())
            target.alpha = 0f
            // Gone between drags: a nothing-sized window cannot take a touch, and the target is
            // not in the tree for a reader either until the drag that shows it.
            target.visibility = View.GONE
            windowManager.addView(target, layoutParams)
            dismissView = target
            dismissParams = layoutParams
            resetDismissScale()
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

    /**
     * Brings the remove target up for a drag. Called once a press has become a drag; its window was
     * added with the bubble's and is only shown here, never added, so it stays under the bubble — see
     * the class comment. A no-op when the target is already up, which is what a re-entry inside its
     * own fade out looks like.
     */
    private fun showDismiss() {
        if (view == null) return
        if (dismissView == null) addDismissWindow()
        val target = dismissView ?: return
        val targetParams = dismissParams ?: return
        // Reposition and re-measure: the display may have turned since the target was built, and the
        // view is going from GONE back to VISIBLE.
        val (x, y) = dismissPosition()
        targetParams.x = x
        targetParams.y = y
        if (target.visibility != View.VISIBLE) target.alpha = 0f
        target.visibility = View.VISIBLE
        windowManager.updateViewLayout(target, targetParams)
        stopDismissAlpha()
        resetDismissScale()
        animateDismissAlpha(target, 1f, Motion.emphasized)
    }

    /** Fades the target out and leaves its window in place, hidden; a no-op when already down. */
    private fun hideDismiss() {
        val target = dismissView ?: return
        animateDismissAlpha(target, 0f, Motion.emphasized) {
            if (dismissView === target) {
                // Hidden, not removed: taking the window down would leave the next drag to add it
                // back above the bubble, which is the stacking this class works to avoid.
                target.visibility = View.GONE
                stopDismissScale()
                resetDismissScale()
            }
        }
    }

    /**
     * The bubble: a round [ImageView] — an opaque accent disk, a hard keyline inside its edge, and
     * the tiled lookup glyph tinted `onPrimary` so it reads on that fill in both light and dark
     * modes. The theme is the app's own choice ([ThemePreferences]), not the device's: this window
     * has no composition to read it from, so the preferences are read here and resolved against the
     * system setting the same way.
     *
     * The fill is opaque and the keyline is the paired ink rather than a paler accent. This disk
     * floats over whatever the other app happens to be drawing, so it has to name its action by
     * itself on a dark page and on a bright one — a translucent disk, or one held together by a
     * hairline alone, would not.
     *
     * The disk sits in a window one [BUBBLE_PRESS_SCALE] larger than itself, so a tap's growth has
     * somewhere to go; only the disk is scaled, and its colours, keyline, glyph and round silhouette
     * are the same before, during and after.
     */
    private fun buildBubble(tone: AccentRoles): FrameLayout {
        val primary = tone.primary.toArgb()
        val onPrimary = tone.onPrimary.toArgb()

        val disk = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // One solid colour and one stroke, never a gradient. A drawable's stroke is inset
            // inside its bounds, which is where this keyline belongs: inside the 48 dp circle.
            setColor(primary)
            setStroke(strokePx, onPrimary)
        }
        val inset = ((BUBBLE_DP - ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_hint)

        bubbleScale = 1f
        val circle = ImageView(context).apply {
            setImageResource(R.drawable.ic_qs_lookup)
            imageTintList = ColorStateList.valueOf(onPrimary)
            scaleType = ImageView.ScaleType.CENTER
            setBackground(disk)
            setPadding(inset, inset, inset, inset)
            // The disk is the thing that grows; it is centred in the window and only ever scaled.
            layoutParams = FrameLayout.LayoutParams(bubblePx, bubblePx, Gravity.CENTER)
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
        }
        bubbleView = circle
        // The tick's source: this disk is the view on screen for every touch the reader makes here.
        // Any previous instance went with the previous bubble in removeNow.
        feedback = Feedback(circle, context)
        return FrameLayout(context).apply {
            // Transparent, and exactly the grown disk's size: the ring the tap's scale grows into.
            minimumWidth = bubbleWindowPx
            minimumHeight = bubbleWindowPx
            addView(circle)
        }
    }

    /**
     * The remove target: a round, neutral disk with a hairline and the paired ink, so it reads on
     * any page it floats over, holding the app's close glyph. It carries its own description and hint
     * so an accessibility service can perform the one action it names.
     *
     * The disk lives in a window one [DISMISS_HOT_SCALE] larger than itself. A dragged bubble over it
     * grows the disk to fill that window, so the drop the release is about to perform is visible
     * before the finger lifts. Only the scale moves: the fill, keyline, glyph and round silhouette are
     * the same before, during and after. An accent of its own would read as a change of state and,
     * because the bubble is the same accent, the two overlapping disks would merge into one blob.
     */
    private fun buildDismiss(neutral: NeutralRoles): FrameLayout {
        val fill = neutral.surface.toArgb()
        val ink = neutral.onSurface.toArgb()
        val stroke = neutral.outline.toArgb()
        val disk = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(strokePx, stroke)
        }
        val inset = ((DISMISS_DP - DISMISS_ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_close_hint)
        val circle = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_close)
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER
            setBackground(disk)
            setPadding(inset, inset, inset, inset)
            // The circle is the disk; it is centred in the window and only ever scaled.
            layoutParams = FrameLayout.LayoutParams(dismissPx, dismissPx, Gravity.CENTER)
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
        dismissCircle = circle
        return FrameLayout(context).apply {
            // Transparent, and exactly the grown circle's size: the ring the disk grows into.
            minimumWidth = dismissWindowPx
            minimumHeight = dismissWindowPx
            addView(circle)
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
        val x = (metrics.widthPixels - bubbleWindowPx - edgeMarginPx).coerceAtLeast(0)
        val y = (metrics.heightPixels * DEFAULT_HEIGHT_FRACTION).toInt().coerceAtLeast(0)
        return x to y
    }

    /**
     * The target's own position: horizontally centred in the usable display, its whole grown circle
     * one [DISMISS_BOTTOM_MARGIN_DP] above the bottom of that display and so clear of the system
     * bars. The usable bounds are the display less the system-bar insets, read fresh each time, so a
     * rotation or a resize places it correctly in the new orientation; a very short display collapses
     * the box rather than pushing the target off it.
     */
    private fun dismissPosition(): Pair<Int, Int> {
        val bounds = usableDisplayBounds()
        val maxX = (bounds.right - dismissWindowPx).coerceAtLeast(bounds.left)
        val minY = bounds.top
        val maxY = (bounds.bottom - dismissWindowPx).coerceAtLeast(minY)
        val x = (bounds.left + (bounds.width() - dismissWindowPx) / 2).coerceIn(bounds.left, maxX)
        val y = (bounds.bottom - dismissBottomMarginPx - dismissWindowPx).coerceIn(minY, maxY)
        return x to y
    }

    /**
     * The display area the app can actually use, in display coordinates: the display bounds less the
     * system-bar insets, so the bottom edge sits above the navigation bar or gesture inset. Read from
     * the window manager's current metrics on API 30+, the same display origin the overlay windows
     * are positioned in. Below 30 the metrics' own usable size is used; its origin is 0 rather than
     * inset-shifted, which only ever leaves extra margin at the bottom.
     */
    private fun usableDisplayBounds(): Rect {
        val metrics = context.resources.displayMetrics
        val fallback = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        return try {
            val current = windowManager.currentWindowMetrics
            val insets = current.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars()
            )
            val bounds = current.bounds
            Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom
            )
        } catch (e: RuntimeException) {
            // No display attached, or a window manager that will not answer: the metrics' own
            // usable size is still a valid, if slightly more conservative, box.
            fallback
        }
    }

    /**
     * Moves the showing bubble back inside the display, for a rotation or a resize that left its
     * position outside the new one, re-docks it to the nearer edge, and re-centres a target that is
     * up on the new display. The window manager clips the frame it draws, but the position kept here
     * is what the next drag moves on from: off-display, that drag only resumes once the finger has
     * travelled back past the clipped distance. A docked bubble keeps its edge across the change, and
     * a position stored mid-screen by an older build comes back to one this class can keep.
     */
    fun reclampToDisplay() = onMain {
        stopSnap()
        val layoutParams = params
        val root = view
        if (layoutParams != null && root != null) {
            val (x, y) = snappedToEdge(clampToDisplay(layoutParams.x to layoutParams.y))
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
        val maxX = (metrics.widthPixels - bubbleWindowPx).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - bubbleWindowPx).coerceAtLeast(0)
        return position.first.coerceIn(0, maxX) to position.second.coerceIn(0, maxY)
    }

    /**
     * A position with the bubble docked to the nearer vertical edge — [EDGE_MARGIN_DP] inside it, on
     * whichever side of the display's middle it was released — at the height it was let go at. The
     * height is clamped like a drag's, so a docked bubble is always one the reader can see and touch.
     */
    private fun snappedToEdge(position: Pair<Int, Int>): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val maxY = (metrics.heightPixels - bubbleWindowPx).coerceAtLeast(0)
        val right = (metrics.widthPixels - bubbleWindowPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
        val centre = position.first + bubbleWindowPx / 2
        val x = if (centre < metrics.widthPixels / 2) edgeMarginPx.coerceAtMost(right) else right
        return x to position.second.coerceIn(0, maxY)
    }

    /**
     * Docks a released bubble to the nearer edge, gliding it there over [Motion.SHORT] on the shared
     * easing, and stores the docked position at once — the resting place is the dock whether or not
     * the glide finishes, so a process death mid-glide restores the dock rather than the drop. A new
     * touch stops the glide and takes the bubble from where it stands.
     */
    private fun dockToEdge() {
        val layoutParams = params ?: return
        val root = view ?: return
        val (x, y) = snappedToEdge(layoutParams.x to layoutParams.y)
        TriggerPreferences.setFloatingButtonPosition(context, x, y)
        stopSnap()
        if (layoutParams.x == x && layoutParams.y == y) return
        layoutParams.y = y
        if (layoutParams.x == x) {
            windowManager.updateViewLayout(root, layoutParams)
            return
        }
        val animator = ValueAnimator.ofInt(layoutParams.x, x).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { Motion.emphasized.transform(it) })
            addUpdateListener { animation ->
                layoutParams.x = animation.animatedValue as Int
                windowManager.updateViewLayout(root, layoutParams)
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (snapAnimator !== animation) return
                snapAnimator = null
            }
        })
        snapAnimator = animator
        animator.start()
    }

    /** Stops whatever is writing a release's dock, so the next touch moves on from where it stands. */
    private fun stopSnap() {
        val running = snapAnimator ?: return
        snapAnimator = null
        running.cancel()
    }

    /**
     * Press handling, kept on the bubble's own bounds: past the touch slop the press becomes a drag,
     * the remove target comes up, and the bubble's window follows the finger. Movement is the whole
     * test — a timer on top of it only adds a second way for a press on this bubble to do nothing at
     * all, and nothing here is bound to a long press to deserve one. A drag is clamped into the
     * display, so a released position is always one the bubble can be seen and touched at.
     *
     * A press grows the bubble and the drag lets it back down: once the finger is moving the window,
     * the position is the feedback and the size has nothing left to say. A tap's feedback is that
     * same grow-and-shrink, plus one tick; the lookup fires on the release itself, not at the end of
     * that shrink, so an accepted capture is never held back by it. Coming near the target draws the
     * bubble toward it and grows the target, so a release there is a drop; a release anywhere else
     * docks the bubble to the nearer edge. A cancellation never removes the bubble and never turns
     * the preference off.
     */
    private fun handleTouch(touched: View, event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        val root = view ?: return false
        if (fadingOut) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A finger on a bubble that is still gliding to its dock takes it over: the drag
                // starts from where it stands, not from where the glide was headed.
                stopSnap()
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                dragging = false
                overDismiss = false
                animateBubbleScale(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlopPx) {
                    dragging = true
                    animateBubbleScale(false)
                    showDismiss()
                }
                if (dragging) {
                    val (x, y) = attractedToDismiss(
                        clampToDisplay(downX + dx.toInt() to downY + dy.toInt())
                    )
                    layoutParams.x = x
                    layoutParams.y = y
                    windowManager.updateViewLayout(root, layoutParams)
                    val over = isOverDismiss()
                    if (over != overDismiss) {
                        overDismiss = over
                        animateDismissScale(over)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val dropped = dragging && overDismiss
                overDismiss = false
                if (dragging) {
                    animateBubbleScale(false)
                    if (dropped) {
                        // The reader dropped the bubble on the remove target: the same dismissal the
                        // target's own activation performs, so the preference, the fade, the tick
                        // and the settings-page notification all take the one path.
                        dismissByReader()
                    } else {
                        animateDismissScale(false)
                        hideDismiss()
                        dockToEdge()
                    }
                } else {
                    // The lookup fires on the tap itself. An accepted capture hides the bubble in the
                    // same pass (the service's startOrderedCapture → hideThen), so the release shrink
                    // is never drawn for it: waiting the animation out first only added its duration
                    // to every lookup. The shrink still plays when the trigger is refused — the
                    // screen is off or locked, or the tap just closes an open panel — which is the
                    // case it exists for; the press grow while the finger is held is unchanged.
                    animateBubbleScale(false)
                    // One tick at the press. The overlay's own tick comes later, when a result is
                    // published, so the two are separate moments rather than one doubled cue.
                    feedback?.haptic(FeedbackHaptic.Tick)
                    onTrigger()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                animateBubbleScale(false)
                overDismiss = false
                if (dragging) {
                    // A gesture the system took away still ends with the bubble resting: it docks
                    // like a release, but never removes the control or moves the preference.
                    animateDismissScale(false)
                    hideDismiss()
                    dockToEdge()
                }
            }
        }
        return true
    }

    /**
     * The position a dragged bubble is drawn at: its own, or — within [DISMISS_ATTRACT_DP] of the
     * remove target — eased toward it, so a finger coming near the target carries the bubble with it
     * and a release there is a drop. The pull is recomputed from the finger on every move, never
     * accumulated, so moving away lets the bubble back to the finger. A convex blend of two points
     * already inside the display stays inside it.
     */
    private fun attractedToDismiss(position: Pair<Int, Int>): Pair<Int, Int> {
        val target = dismissParams ?: return position
        val radius = DISMISS_ATTRACT_DP * density
        val centreX = position.first + bubbleWindowPx / 2f
        val centreY = position.second + bubbleWindowPx / 2f
        val targetX = target.x + dismissWindowPx / 2f
        val targetY = target.y + dismissWindowPx / 2f
        val distance = hypot(targetX - centreX, targetY - centreY)
        if (distance >= radius) return position
        val pull = 1f - distance / radius
        return (centreX + (targetX - centreX) * pull - bubbleWindowPx / 2f).toInt() to
            (centreY + (targetY - centreY) * pull - bubbleWindowPx / 2f).toInt()
    }

    /**
     * Whether the bubble the finger is dragging now overlaps the remove target, with
     * [DISMISS_SLOP_DP] of slack. Read from the bubble's own drawn rectangle, not the finger's: the
     * drag's pull can put the bubble on the target with the finger a little outside it, and a drop
     * has to follow what the reader can see.
     */
    private fun isOverDismiss(): Boolean {
        val target = dismissParams ?: return false
        val layoutParams = params ?: return false
        return layoutParams.x <= target.x + dismissWindowPx + dismissSlopPx &&
            layoutParams.x + bubbleWindowPx >= target.x - dismissSlopPx &&
            layoutParams.y <= target.y + dismissWindowPx + dismissSlopPx &&
            layoutParams.y + bubbleWindowPx >= target.y - dismissSlopPx
    }

    /**
     * The reader removed the floating control: give one confirming tick, write the preference off
     * and let the ordinary hide fade it away. Only the floating-button preference moves — lookup
     * mode and the Quick Settings tile are separate switches and stay as they were. The Screen
     * lookup settings page reads this preference, so switching Floating button back on shows the
     * bubble again. This is the one dismissal path — a drop on the target and the target's own
     * activation both arrive here — so the tick fires exactly once for either.
     */
    private fun dismissByReader() {
        feedback?.haptic(FeedbackHaptic.Confirm)
        TriggerPreferences.setFloatingButtonEnabled(context, false)
        // The Screen lookup page, if it is open, re-reads the switch from this notification; the
        // service owns the bridge, so the button only reports the dismissal.
        onDismissed()
        hide()
    }

    /**
     * Eases the bubble's disk between its resting size and its pressed size. Only the scale moves:
     * the fill, keyline, glyph and round silhouette are untouched, so a tap reads the same control,
     * larger. A drag taking the press over, or another tap, supersedes a running animation; nothing
     * is notified on its end, the lookup no longer waits for the shrink.
     */
    private fun animateBubbleScale(pressed: Boolean) {
        val circle = bubbleView ?: return
        val to = if (pressed) BUBBLE_PRESS_SCALE else 1f
        stopBubbleScale()
        val animator = ValueAnimator.ofFloat(bubbleScale, to).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { Motion.pressEasing.transform(it) })
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                bubbleScale = scale
                circle.scaleX = scale
                circle.scaleY = scale
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (bubbleScaleAnimator !== animation) return
                bubbleScaleAnimator = null
            }
        })
        bubbleScaleAnimator = animator
        animator.start()
    }

    /** Writes the resting scale directly, for a disk that has just been built. */
    private fun resetBubbleScale() {
        val circle = bubbleView ?: return
        stopBubbleScale()
        bubbleScale = 1f
        circle.scaleX = 1f
        circle.scaleY = 1f
    }

    /** Stops whatever is writing the bubble disk's tap scale. */
    private fun stopBubbleScale() {
        val running = bubbleScaleAnimator ?: return
        bubbleScaleAnimator = null
        running.cancel()
    }

    /**
     * Eases the remove target's circle between its resting size and its grown size. Only the scale
     * moves: the fill, keyline, glyph and round silhouette are untouched, so a finger over it reads
     * the same control, larger. The scale is animated over [Motion.SHORT] with the theme's press
     * easing, so it arrives with the finger instead of snapping and returns the same way on the way
     * out; at a zero animator duration scale it lands on its end value at once.
     */
    private fun animateDismissScale(enlarged: Boolean) {
        val circle = dismissCircle ?: return
        val to = if (enlarged) DISMISS_HOT_SCALE else 1f
        stopDismissScale()
        val animator = ValueAnimator.ofFloat(dismissScale, to).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { Motion.pressEasing.transform(it) })
            addUpdateListener { animation ->
                val scale = animation.animatedValue as Float
                dismissScale = scale
                circle.scaleX = scale
                circle.scaleY = scale
            }
        }
        dismissScaleAnimator = animator
        animator.start()
    }

    /** Writes the resting scale directly, for a circle that has just been built. */
    private fun resetDismissScale() {
        val circle = dismissCircle ?: return
        stopDismissScale()
        dismissScale = 1f
        circle.scaleX = 1f
        circle.scaleY = 1f
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

    /** Stops whatever is writing the target circle's over-it scale. */
    private fun stopDismissScale() {
        val running = dismissScaleAnimator ?: return
        dismissScaleAnimator = null
        running.cancel()
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
