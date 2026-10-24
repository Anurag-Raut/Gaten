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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
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

/** The remove button beside the bubble: its own touch target, the same height as the bubble. */
private const val CLOSE_DP = 48
private const val CLOSE_ICON_DP = 24

/** Gap between the bubble and the remove button. */
private const val GROUP_GAP_DP = 8

/** The hard keyline inside the circle's edge, drawn in the glyph's own colour. */
private const val PERIMETER_DP = 2

/** Gap kept between the control group and the screen's right edge on first show. */
private const val EDGE_MARGIN_DP = 8

/** Distance the finger must travel before a press becomes a drag instead of a tap. */
private const val TOUCH_SLOP_DP = 8f

/** Where the bubble starts vertically: a little above the middle, clear of both screen edges. */
private const val DEFAULT_HEIGHT_FRACTION = 0.45f

/** Longest [FloatingTriggerButton.hideThen] waits for the cleared bubble to reach the screen. */
private const val GONE_TIMEOUT_MS = 150L

/**
 * The floating lookup trigger: a small bubble in its own accessibility-overlay window, with a
 * remove button beside it.
 *
 * The window is sized to the whole control group ([WindowManager.LayoutParams.WRAP_CONTENT]) and is
 * `FLAG_NOT_TOUCH_MODAL`, so a touch anywhere else falls through to the app behind instead of being
 * swallowed by an invisible full-screen window. Both controls are plain Android views because this
 * window is added from the accessibility service — there is no composition to host Compose.
 *
 * The window does not fit the system bars ([WindowManager.LayoutParams.setFitInsetsTypes] cleared,
 * `FLAG_LAYOUT_IN_SCREEN` below API 30), so its `x`/`y` are display coordinates: the position asked
 * for, the one persisted, the one a drag moves on from and the one the window reports all mean the
 * same place on screen. Left at the default, such a window is laid out inside the inset-adjusted
 * parent frame, whose origin sits under the status bar — the group then draws, is stored and is
 * reported one status-bar height away from the display position it was given, which is what a tap
 * aimed with those numbers misses. Every position this class takes in — restored, dragged,
 * re-clamped — is clamped against the whole group, so it can never be stored outside the area it can
 * be touched in.
 *
 * [show]/[hide] are idempotent and hop to the main thread themselves, since adding or removing a
 * window anywhere else throws.
 *
 * The group's motion is its opacity: it fades in where it was left, and fades out when the reader
 * switches it off with the remove button or in settings. The bubble itself also changes colour under
 * a finger, which is the press feedback; its geometry is never animated — the drag and the clamp
 * answer a finger, and the service takes the window down outright before a capture.
 *
 * The remove button carries its own accessible touch target. It is a sibling of the bubble, never a
 * child of it, so a press on either one is unambiguous: the bubble's touch listener only ever sees
 * presses on the bubble, and the button's click listener only ever sees presses on the button. The
 * button writes the floating-button preference off and lets the ordinary fade take the group down;
 * the Screen lookup settings switch it back on, which shows the group again. Lookup mode and the
 * Quick Settings tile are untouched by it.
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
    private val closePx = (CLOSE_DP * density).toInt()
    private val groupGapPx = (GROUP_GAP_DP * density).toInt()
    private val groupWidthPx = bubblePx + groupGapPx + closePx
    private val groupHeightPx = maxOf(bubblePx, closePx)
    private val touchSlopPx = TOUCH_SLOP_DP * density
    private val edgeMarginPx = (EDGE_MARGIN_DP * density).toInt()

    /** The group on screen, if any; also the source of truth behind [isShowing]. */
    private var view: ViewGroup? = null
    private var params: WindowManager.LayoutParams? = null

    /**
     * The bubble inside [view]. Kept separately because the press feedback repaints this one view,
     * while the window operations — move, remove, fade — always address the group.
     */
    private var bubbleView: ImageView? = null

    /** The bubble's fill and stroke in both states, so a press only repaints the drawable. */
    private var bubbleBackground: GradientDrawable? = null
    private var bubbleFillNormal = 0
    private var bubbleFillPressed = 0
    private var bubbleInkNormal = 0
    private var bubbleInkPressed = 0

    /**
     * Whatever is writing the group's opacity: an entrance, or the fade out of a group the reader
     * switched off. One at a time, so two of them can never fight over it.
     */
    private var alphaAnimator: ValueAnimator? = null

    /** True from the start of a fade out until the group is gone or is asked for again. */
    private var fadingOut = false

    // Press bookkeeping for the drag/tap split, reset on every DOWN.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    /**
     * True while the group is up and staying up. A group fading out is on its way down and says so,
     * which is what has the service ask for it again if the reader switches it back on inside that
     * fade.
     */
    val isShowing: Boolean
        get() = view != null && !fadingOut

    /** Puts the group up at its restored (or default) position; a no-op when already up. */
    fun show() = onMain {
        val existing = view
        if (existing != null) {
            // A group still fading out — switched back on inside that fade — is this group coming
            // back, not a second one: take the fade over and raise the opacity where it was left
            // standing.
            if (fadingOut) {
                fadingOut = false
                pressColor(false)
                animateAlpha(existing, 1f, Motion.emphasized)
            }
            return@onMain
        }
        // The build/position work sits inside the guard too: reading the persisted position and
        // measuring the views touch the same resources addView does, and any of it failing must
        // leave the control down rather than propagate. A service has no valid overlay window token
        // until it is connected to the system, so addView can throw (BadTokenException being the
        // common case) if this runs too early, or if the display is being torn down. Swallowing
        // that keeps the whole service process alive.
        try {
            val group = buildGroup()
            val layoutParams = buildParams(restoredOrDefaultPosition())
            // Invisible until the entrance's first frame lands, so a group returning from a
            // capture cannot flash back at full strength.
            group.alpha = 0f
            windowManager.addView(group, layoutParams)
            // Only now is the window really on screen; isShowing derives from these.
            view = group
            params = layoutParams
            animateAlpha(group, 1f, Motion.emphasized)
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

    /** Takes the group down; a no-op when it is already down. Drops the window references. */
    fun hide() = onMain {
        val current = view ?: return@onMain
        if (fadesOut(current)) {
            fadingOut = true
            animateAlpha(current, 0f, Motion.emphasized) {
                fadingOut = false
                if (view === current) removeNow(current)
            }
        } else {
            removeNow(current)
        }
    }

    /**
     * Whether this hide may be seen going. Exactly one hide may: the reader switched the control
     * off — with the remove button or in settings, both of which leave the preference false while
     * the group is still on screen and opaque. Every other hide — a lock, a teardown, the overlay
     * coming up — is about not being on screen a frame longer than needed, and one of them
     * ([hideThen]) is waited on frame by frame before a screenshot.
     */
    private fun fadesOut(current: View): Boolean =
        current.alpha > 0f && !TriggerPreferences.floatingButtonEnabled(context)

    /**
     * Drops the window. The state goes first: whatever removeViewImmediate does, no view is
     * retained and isShowing ends false, so a failed removal cannot strand a half-gone group.
     */
    private fun removeNow(current: View) {
        stopAlpha()
        fadingOut = false
        view = null
        params = null
        bubbleView = null
        bubbleBackground = null
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
     * Takes the group down and runs [onGone] on the main thread once the display no longer shows
     * it, so a screenshot taken then cannot contain either control; at once when it is already
     * down.
     *
     * A removed window leaves the screen whenever the window manager gets to it, which can take
     * several frames. Clearing the group to transparent instead goes out as an ordinary frame:
     * once that frame is committed to the compositor and one more vsync has passed, it is on
     * screen. [GONE_TIMEOUT_MS] caps the wait in case no frame is ever drawn (screen off).
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
                // Only this group: a newer one shown meanwhile is not ours to take down.
                if (view === current) hide()
                onGone()
            }
        }
        current.viewTreeObserver.registerFrameCommitCallback {
            mainHandler.post { Choreographer.getInstance().postFrameCallback { finish.run() } }
        }
        // The opacity is written here and now, so nothing else may be writing it: an entrance or a
        // fade out still running would put the group back on screen inside this wait. Any fade is
        // over with it — from here this group only has one way left to go.
        stopAlpha()
        fadingOut = false
        current.alpha = 0f
        mainHandler.postDelayed(finish, GONE_TIMEOUT_MS)
    }

    /** One line per failed window op; [operation] is `add` or `remove`. */
    private fun logWindowNotAdded(operation: String, cause: Exception) {
        Log.w(TAG, "Floating trigger: could not $operation the overlay window (not shown)", cause)
    }

    /**
     * The horizontal control group: the lookup bubble, a gap, and the remove button. A [LinearLayout]
     * because the window has no composition, and because each control must keep its own touch
     * bounds — the group only positions them.
     */
    private fun buildGroup(): ViewGroup {
        val dark = ThemePreferences.themeMode(context).isDark(
            (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
        val tone = accentRoles(ThemePreferences.accent(context), dark)
        val neutral = gatenNeutrals(dark)
        val bubble = buildBubble(tone)
        val close = buildCloseButton(neutral)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                bubble,
                LinearLayout.LayoutParams(bubblePx, bubblePx).apply { rightMargin = groupGapPx }
            )
            addView(close, LinearLayout.LayoutParams(closePx, closePx))
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
            setStroke((PERIMETER_DP * density).toInt(), onPrimary)
        }
        val inset = ((BUBBLE_DP - ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_hint)

        // The press state repaints this drawable and tint; the entrance/fade animator writes the
        // group's alpha, never the bubble's, so the two never touch the same property.
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
     * The remove button: the neutral surface disk with a hairline and the paired ink, so it reads
     * on any page the group floats over, and the app's close glyph. It is a control of its own, not
     * a decoration on the bubble, so it has its own description, hint and touch target.
     *
     * A press only writes the floating-button preference off; [hide] then fades the whole group
     * away through the ordinary path, and the Screen lookup settings switch shows the group again.
     */
    private fun buildCloseButton(neutral: NeutralRoles): ImageView {
        val disk = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(neutral.surface.toArgb())
            setStroke((PERIMETER_DP * density).toInt(), neutral.outline.toArgb())
        }
        val inset = ((CLOSE_DP - CLOSE_ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_close_hint)
        return ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_close)
            imageTintList = ColorStateList.valueOf(neutral.onSurface.toArgb())
            scaleType = ImageView.ScaleType.CENTER
            setBackground(disk)
            setPadding(inset, inset, inset, inset)
            minimumWidth = closePx
            minimumHeight = closePx
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
     * that did not clamp) cannot put the group where it can be neither seen nor touched. The
     * position is the group's top-left, so the whole group — bubble and remove button — is what
     * fits.
     */
    private fun restoredOrDefaultPosition(): Pair<Int, Int> {
        TriggerPreferences.floatingButtonPosition(context)?.let { return clampToDisplay(it) }
        val metrics = context.resources.displayMetrics
        val x = (metrics.widthPixels - groupWidthPx - edgeMarginPx).coerceAtLeast(0)
        val y = (metrics.heightPixels * DEFAULT_HEIGHT_FRACTION).toInt().coerceAtLeast(0)
        return x to y
    }

    /**
     * Moves the showing group back inside the display, for a rotation or a resize that left its
     * position outside the new one. The window manager clips the frame it draws, but the position
     * kept here is what the next drag moves on from: off-display, that drag only resumes once the
     * finger has travelled back past the clipped distance.
     */
    fun reclampToDisplay() = onMain {
        val layoutParams = params ?: return@onMain
        val root = view ?: return@onMain
        val (x, y) = clampToDisplay(layoutParams.x to layoutParams.y)
        if (x == layoutParams.x && y == layoutParams.y) return@onMain
        layoutParams.x = x
        layoutParams.y = y
        windowManager.updateViewLayout(root, layoutParams)
        TriggerPreferences.setFloatingButtonPosition(context, x, y)
    }

    /**
     * A position with the whole control group inside the display. The box is this context's own
     * display metrics, which stop at the status and navigation bars rather than at the screen
     * edges, so a clamped group is never left under a system bar where the system's own gesture
     * would take the touch. The group may exceed a tiny display; the box then collapses to its
     * origin.
     */
    private fun clampToDisplay(position: Pair<Int, Int>): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val maxX = (metrics.widthPixels - groupWidthPx).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - groupHeightPx).coerceAtLeast(0)
        return position.first.coerceIn(0, maxX) to position.second.coerceIn(0, maxY)
    }

    /**
     * Press handling, kept on the bubble's own bounds: past the touch slop the press becomes a drag
     * that moves the whole window, and a release that never got there is a tap. Movement is the
     * whole test — a timer on top of it only adds a second way for a press on this bubble to do
     * nothing at all, and nothing here is bound to a long press to deserve one. A drag is clamped
     * into the display, so a released position is always one the group can be seen and touched at.
     *
     * A press repaints the bubble in the pressed colour and the drag gives the normal colour back:
     * once the finger is moving the window, the position is the feedback and the colour has nothing
     * left to say. A group on its way out has no action to offer and takes the touch, so nothing
     * underneath receives a press the reader aimed at the bubble. Only the bubble drags; the remove
     * button is a sibling and handles its own presses.
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
                pressColor(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlopPx) {
                    dragging = true
                    pressColor(false)
                }
                if (dragging) {
                    val (x, y) = clampToDisplay(downX + dx.toInt() to downY + dy.toInt())
                    layoutParams.x = x
                    layoutParams.y = y
                    windowManager.updateViewLayout(root, layoutParams)
                }
            }

            MotionEvent.ACTION_UP -> {
                pressColor(false)
                if (dragging) {
                    TriggerPreferences.setFloatingButtonPosition(
                        context, layoutParams.x, layoutParams.y
                    )
                } else {
                    onTrigger()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                pressColor(false)
                if (dragging) {
                    TriggerPreferences.setFloatingButtonPosition(
                        context, layoutParams.x, layoutParams.y
                    )
                }
            }
        }
        return true
    }

    /**
     * The reader removed the floating control: write the preference off and let the ordinary hide
     * fade it away. Only the floating-button preference moves — lookup mode and the Quick Settings
     * tile are separate switches and stay as they were. The Screen lookup settings page reads this
     * preference, so switching Floating button back on shows the group again.
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
        background.setStroke((PERIMETER_DP * density).toInt(), ink)
        bubble.imageTintList = ColorStateList.valueOf(ink)
    }

    /**
     * Moves the group's opacity over [Motion.SHORT] with [easing]. One animator at a time: whatever
     * was running is stopped first, and cleared before the stop, so its own end callback sees itself
     * superseded — that is what keeps an entrance and a fade out from writing the same property
     * together. [onEnd] runs only for the animation that really finished.
     *
     * A [ValueAnimator] rather than a view property animator because these fades have to obey the
     * system's animator duration scale — what the reduced-motion setting drives — and this is the
     * animator the framework hands that scale to. At zero the fades land on their end value at
     * once, which is exactly what an entrance and a dismissal should do there.
     */
    private fun animateAlpha(
        target: View,
        to: Float,
        easing: Easing,
        onEnd: (() -> Unit)? = null
    ) {
        stopAlpha()
        val animator = ValueAnimator.ofFloat(target.alpha, to).apply {
            duration = Motion.SHORT.toLong()
            setInterpolator(TimeInterpolator { easing.transform(it) })
            addUpdateListener { target.alpha = it.animatedValue as Float }
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

    /** Stops whatever is writing the opacity, so a direct write is the last word on it. */
    private fun stopAlpha() {
        val running = alphaAnimator ?: return
        alphaAnimator = null
        running.cancel()
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }
}
