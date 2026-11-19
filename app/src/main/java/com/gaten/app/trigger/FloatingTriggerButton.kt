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
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.graphics.toArgb
import com.gaten.app.R
import com.gaten.app.ui.Feedback
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.theme.DarkError
import com.gaten.app.ui.theme.LightError
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.NeutralRoles
import com.gaten.app.ui.theme.ThemePreferences
import com.gaten.app.ui.theme.gatenNeutrals
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "Gaten"

/** Bubble diameter; the mark sits centred inside on its own ink box. */
private const val BUBBLE_DP = 48

/**
 * The Gaten mark's ink box inside the disk, in dp: wider than tall, because its two dakuten sit to
 * the right of the letter. The disk's padding centres this box on the 48 dp circle, not the disk's
 * canvas.
 */
private const val ICON_WIDTH_DP = 27
private const val ICON_HEIGHT_DP = 24

/** The disk's fill in every theme: white, the flat ground of the app's own Light icon. */
private const val DISC_FILL = -0x1

/**
 * The keyline drawn inside the disk's edge: black at 14%. A white disk on a white page would
 * otherwise have no edge at all, and on a dark page it is the disk itself that carries the contrast.
 */
private const val DISC_KEYLINE = 0x24000000


/**
 * Transparent room around the disk inside its window, on every side: what the drop shadow and the
 * entrance's overshoot draw into. Only the disk is ever docked against the screen edge; this ring
 * may hang off it.
 */
private const val BUBBLE_PAD_DP = 14

/** The disk's resting elevation, and how much higher a drag lifts it. */
private const val BUBBLE_ELEVATION_DP = 6f
private const val BUBBLE_LIFT_DP = 6f

/** How far the disk sinks under a finger before the press becomes a tap or a drag. */
private const val BUBBLE_PRESS_SCALE = 0.9f

/** The disk's opacity once it has been left alone for [IDLE_DELAY_MS]: present, not in the way. */
private const val BUBBLE_IDLE_ALPHA = 0.55f
private const val IDLE_DELAY_MS = 3000L

/** Gap kept between the disk and the screen edge it docks against. */
private const val EDGE_MARGIN_DP = 6

/** Distance the finger must travel before a press becomes a drag instead of a tap. */
private const val TOUCH_SLOP_DP = 8f

/** Where the bubble starts vertically: a little above the middle, clear of both screen edges. */
private const val DEFAULT_HEIGHT_FRACTION = 0.45f

/** The remove target's disk, its glyph, and how much it grows while the bubble is locked on it. */
private const val DISMISS_DP = 56
private const val DISMISS_ICON_DP = 24
private const val DISMISS_HOT_SCALE = 1.3f
private const val DISMISS_ELEVATION_DP = 6f

/** Gap between the remove target and the bottom of the usable display (above the system bars). */
private const val DISMISS_BOTTOM_MARGIN_DP = 48

/** How far below its resting place the target starts when a drag brings it up. */
private const val DISMISS_TRAVEL_DP = 72

/** How far above the target the bottom scrim reaches, fading from clear to [SCRIM_ALPHA]. */
private const val SCRIM_RISE_DP = 220
private const val SCRIM_ALPHA = 0x73

/**
 * The magnet: a dragged bubble whose centre comes within this radius of the target's centre is
 * pulled onto it and held there, and is let go only once the finger is [MAGNET_RELEASE_FACTOR]
 * times as far, so a finger hovering at the edge cannot make it flicker in and out.
 */
private const val MAGNET_DP = 88f
private const val MAGNET_RELEASE_FACTOR = 1.25f

/** A release this fast across the screen picks its edge from the throw, not from where it was. */
private const val FLING_MIN_DP_PER_S = 500f

/** How far ahead a throw is projected, in seconds of its release velocity. */
private const val FLING_PROJECTION_S = 0.12f

/** A dock shorter than this lands without a tick: there was no flight to land from. */
private const val LAND_HAPTIC_MIN_DP = 24f

/** Springs: the dock's glide, the snap onto the target, and the pull back to the finger. */
private const val DOCK_STIFFNESS = 420f
private const val DOCK_DAMPING_X = 0.78f
private const val DOCK_DAMPING_Y = 0.95f
private const val SNAP_STIFFNESS = 700f
private const val SNAP_DAMPING = 0.62f
private const val UNSNAP_STIFFNESS = 1400f
private const val UNSNAP_DAMPING = 0.9f

/** Longest [FloatingTriggerButton.hideThen] waits for the cleared control to reach the screen. */
private const val GONE_TIMEOUT_MS = 150L

/**
 * The floating lookup trigger: a small bubble in its own accessibility-overlay window, and a remove
 * target in a second window along the bottom of the display. The target is drawn only while the
 * reader is dragging; its window is built and added with the bubble's and hidden between drags.
 *
 * The bubble behaves the way readers know floating heads to behave:
 *
 * - **Pressed**, the disk sinks a little; **picked up** (past the touch slop) it springs back to size
 *   and lifts — a deeper shadow — with a drag-start tick, and the remove target rises from the bottom
 *   over a soft dark scrim.
 * - **Thrown**, it keeps the throw: the release velocity picks the edge and carries the height, and
 *   a spring docks it there with a small settle and a light landing tick.
 * - **Near the target** it is pulled onto it — a magnet with a spring and a lock-on tick — while the
 *   target grows to take it; pulling away lets go with a lighter tick and the bubble springs back
 *   under the finger. A release while locked on (or a throw straight at it) removes the control:
 *   both disks shrink away together, with a confirming tick.
 * - **Left alone** for a few seconds, the disk fades to half strength so it stays out of the way;
 *   any touch brings it back at once.
 *
 * Every haptic goes through the app's shared [Feedback], which respects the phone's own haptic
 * setting. The lookup is fired on the tap itself, never after an animation: an accepted capture
 * takes the bubble off screen in the same pass.
 *
 * The target's window is added before the bubble's and is never removed and re-added: two
 * accessibility-overlay windows of the same type stack in the order they were added, so the bubble
 * stays in front of the target, and a bubble re-added mid-drag would cancel the very gesture moving
 * it. Between drags the target's view is `GONE` and its window is `FLAG_NOT_TOUCHABLE`, so it
 * measures to nothing and can never take a touch meant for the app behind.
 *
 * Neither window fits the system bars ([WindowManager.LayoutParams.setFitInsetsTypes] cleared,
 * `FLAG_LAYOUT_IN_SCREEN` below API 30), so their `x`/`y` are display coordinates: the position
 * asked for, the one persisted, the one a drag moves on from and the one the window reports all mean
 * the same place on screen. `FLAG_LAYOUT_NO_LIMITS` lets the bubble's transparent shadow ring hang
 * past the screen edge so the disk itself can sit close to it. Every position is clamped so the disk
 * stays inside the display, and docked positions stay inside the usable area (clear of the bars).
 *
 * [show]/[hide] are idempotent and hop to the main thread themselves, since adding or removing a
 * window anywhere else throws. Both are plain Android views: these windows are added from the
 * accessibility service, where there is no composition to host Compose.
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
    private val bubblePadPx = (BUBBLE_PAD_DP * density).toInt()

    /** The bubble's window: the disk plus its transparent shadow ring on every side. */
    private val bubbleWindowPx = bubblePx + 2 * bubblePadPx
    private val dismissPx = (DISMISS_DP * density).toInt()

    /** The target's own frame: the grown disk plus room for its shadow. */
    private val dismissFramePx = ceil(dismissPx * DISMISS_HOT_SCALE).toInt() + 2 * bubblePadPx
    private val dismissBottomMarginPx = (DISMISS_BOTTOM_MARGIN_DP * density).toInt()
    private val dismissTravelPx = DISMISS_TRAVEL_DP * density
    private val scrimRisePx = (SCRIM_RISE_DP * density).toInt()
    private val magnetPx = MAGNET_DP * density
    private val touchSlopPx = TOUCH_SLOP_DP * density
    private val edgeMarginPx = (EDGE_MARGIN_DP * density).toInt()
    private val strokePx = (2 * density).toInt()
    private val flingMinPx = FLING_MIN_DP_PER_S * density
    private val landMinPx = LAND_HAPTIC_MIN_DP * density

    /** The bubble's window on screen, if any; also the source of truth behind [isShowing]. */
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    /** The disk inside the bubble's window: what scales, lifts and dims. */
    private var bubbleView: ImageView? = null

    /** The remove target's window (the scrim), the frame that slides, and the disk that grows. */
    private var dismissView: FrameLayout? = null
    private var dismissFrame: FrameLayout? = null
    private var dismissCircle: ImageView? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    /** Whatever is writing the bubble window's opacity: an entrance or a fade out. One at a time. */
    private var alphaAnimator: ValueAnimator? = null

    /** The spring moving the bubble's window: a dock, a snap onto the target or back to the finger. */
    private val positionSpring = WindowSpring { x, y -> moveBubbleTo(x, y) }

    private var feedback: Feedback? = null
    private var velocityTracker: VelocityTracker? = null

    /** True from the start of a bubble fade out until the control is gone or asked for again. */
    private var fadingOut = false

    /**
     * True while the bubble is the close control of a lookup it started: the disk shows a close
     * glyph in red ink instead of the mark, a tap closes the lookup (the service's trigger toggles), it never
     * dims, and a drag only moves it — there is no remove target, so the reader cannot switch the
     * button off and strand the lookup without its close.
     */
    private var closeMode = false

    // Press bookkeeping for the drag/tap split, reset on every DOWN.
    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false
    private var magnetized = false

    private val dimIdle = Runnable {
        bubbleView?.animate()
            ?.alpha(BUBBLE_IDLE_ALPHA)
            ?.setDuration(Motion.MEDIUM.toLong())
            ?.setInterpolator { Motion.emphasized.transform(it) }
            ?.start()
    }

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
            // back, not a second one: take the fade over and raise it where it was left standing.
            if (fadingOut) {
                fadingOut = false
                bubbleView?.let { disk ->
                    disk.animate().cancel()
                    disk.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(Motion.SHORT.toLong()).start()
                }
                animateBubbleAlpha(1f, Motion.SHORT)
                scheduleIdle()
            }
            return@onMain
        }
        // A service has no valid overlay window token until it is connected to the system, so
        // addView can throw (BadTokenException being the common case) if this runs too early, or if
        // the display is being torn down. Swallowing that keeps the whole service process alive.
        try {
            val bubble = buildBubble()
            val layoutParams = overlayParams(restoredOrDefaultPosition())
            // Invisible until the entrance's first frame lands, so a bubble returning from a
            // capture cannot flash back at full strength.
            bubble.alpha = 0f
            addDismissWindow()
            try {
                windowManager.addView(bubble, layoutParams)
            } catch (e: RuntimeException) {
                removeNow()
                throw e
            }
            view = bubble
            params = layoutParams
            // The entrance: the disk pops up from a little smaller with a short overshoot while the
            // window fades in, so the bubble arrives rather than blinks on.
            bubbleView?.let { disk ->
                disk.scaleX = 0.6f
                disk.scaleY = 0.6f
                disk.animate().scaleX(1f).scaleY(1f)
                    .setDuration(Motion.MEDIUM.toLong())
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()
            }
            animateBubbleAlpha(1f, Motion.SHORT)
            scheduleIdle()
        } catch (e: RuntimeException) {
            logWindowNotAdded("add", e)
        }
    }

    /** Takes the bubble, and any target still up, down; a no-op when already down. */
    fun hide() = onMain {
        val current = view ?: return@onMain
        cancelIdle()
        if (fadesOut(current)) {
            fadingOut = true
            // The position spring keeps running: a drop's last glide onto the target is part of
            // this exit. removeNow stops it with everything else.
            hideDismiss()
            bubbleView?.animate()?.scaleX(0.6f)?.scaleY(0.6f)
                ?.setDuration(Motion.MEDIUM.toLong())
                ?.setInterpolator { Motion.emphasizedAccelerate.transform(it) }
                ?.start()
            animateBubbleAlpha(0f, Motion.MEDIUM) {
                fadingOut = false
                if (view === current) removeNow()
            }
        } else {
            removeNow()
        }
    }

    /**
     * Whether this hide may be seen going. Exactly one may: the reader switched the control off —
     * with the drop target or in settings, both of which leave the preference false while the bubble
     * is still on screen. Every other hide — a lock, a teardown, the overlay coming up — is about not
     * being on screen a frame longer than needed.
     */
    private fun fadesOut(current: View): Boolean =
        current.alpha > 0f && !TriggerPreferences.floatingButtonEnabled(context)

    /**
     * Drops both windows. The state goes first: whatever removeViewImmediate does, no view is
     * retained and isShowing ends false, so a failed removal cannot strand a half-gone control.
     */
    private fun removeNow() {
        stopAlpha()
        cancelIdle()
        positionSpring.cancel()
        bubbleView?.animate()?.cancel()
        dismissView?.animate()?.cancel()
        dismissFrame?.animate()?.cancel()
        dismissCircle?.animate()?.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        fadingOut = false
        dragging = false
        magnetized = false
        val bubble = view
        val target = dismissView
        view = null
        params = null
        dismissView = null
        dismissFrame = null
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
     * once that frame is committed and one more vsync has passed, it is on screen. Both windows are
     * cleared in the same main-thread pass; [GONE_TIMEOUT_MS] caps the wait in case no frame is ever
     * drawn (screen off).
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
        // The opacity is written here and now, so nothing else may be writing it.
        stopAlpha()
        cancelIdle()
        fadingOut = false
        current.alpha = 0f
        dismissView?.let {
            it.animate().cancel()
            it.alpha = 0f
        }
        mainHandler.postDelayed(finish, GONE_TIMEOUT_MS)
    }

    private fun logWindowNotAdded(operation: String, cause: Exception) {
        Log.w(TAG, "Floating trigger: could not $operation the overlay window (not shown)", cause)
    }

    /** The app's own light/dark choice resolved against the system setting, as the theme does. */
    private fun isDark(): Boolean = ThemePreferences.themeMode(context).isDark(
        (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    )

    // ---------------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------------

    /**
     * The bubble: a round [ImageView] — a flat white disk carrying the app's own mark, the black
     * Gaten G, untinted, with a hairline keyline inside its edge and a soft drop shadow underneath,
     * so it reads as floating over whatever the other app draws. The disk is the same white in every
     * theme: the G with its two dakuten is Gaten's Light icon.
     */
    private fun buildBubble(): FrameLayout {
        val disk = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(DISC_FILL)
            setStroke(strokePx, DISC_KEYLINE)
        }
        val hint = context.getString(R.string.bubble_lookup_hint)

        val circle = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = disk
            // The oval background is the outline, so the shadow is round.
            elevation = BUBBLE_ELEVATION_DP * density
            layoutParams = FrameLayout.LayoutParams(bubblePx, bubblePx, Gravity.CENTER)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.hintText = if (closeMode) null else hint
                }
            }
            setOnTouchListener { _, event -> handleTouch(event) }
            // A finger is handled above, so a real touch never reaches this; it is what an
            // accessibility service activates.
            setOnClickListener { onTrigger() }
        }
        applyFace(circle)
        bubbleView = circle
        feedback = Feedback(circle, context)
        return FrameLayout(context).apply {
            clipChildren = false
            minimumWidth = bubbleWindowPx
            minimumHeight = bubbleWindowPx
            addView(circle)
        }
    }

    /**
     * Switches the bubble between the lookup trigger and a lookup's close control. On screen, the
     * glyph turns out and the other turns in — a quarter turn each way — so the reader sees the very
     * button they tapped become the way back. Off screen it only records the mode for the next show.
     */
    fun setCloseMode(close: Boolean) = onMain {
        if (closeMode == close) return@onMain
        closeMode = close
        val disk = bubbleView ?: return@onMain
        if (close) {
            wake()
            hideDismiss()
        } else {
            scheduleIdle()
        }
        if (!ValueAnimator.areAnimatorsEnabled() || view?.alpha == 0f) {
            applyFace(disk)
            return@onMain
        }
        disk.animate().rotation(90f)
            .setDuration(Motion.SHORT.toLong() / 2)
            .setInterpolator { Motion.emphasizedAccelerate.transform(it) }
            .withEndAction {
                if (bubbleView !== disk) return@withEndAction
                applyFace(disk)
                disk.rotation = -90f
                disk.animate().rotation(0f)
                    .setDuration(Motion.MEDIUM.toLong())
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }

    /**
     * Paints the disk's face for the current mode: the black Gaten mark, untinted, on its own ink
     * box — or the close glyph in the same black, centred square.
     */
    private fun applyFace(disk: ImageView) {
        if (closeMode) {
            val inset = ((BUBBLE_DP - DISMISS_ICON_DP) / 2f * density).toInt()
            disk.setImageResource(R.drawable.ic_bubble_close)
            // The theme's own red ink, as it is written on white paper: the disk is white in every
            // theme, so the light-paper ink is the one that reads on it.
            disk.imageTintList = ColorStateList.valueOf(LightError.toArgb())
            disk.setPadding(inset, inset, inset, inset)
            disk.contentDescription = context.getString(R.string.lookup_close)
        } else {
            // Asymmetric by design: the mark is wider than it is tall.
            val insetX = ((BUBBLE_DP - ICON_WIDTH_DP) / 2f * density).toInt()
            val insetY = ((BUBBLE_DP - ICON_HEIGHT_DP) / 2f * density).toInt()
            disk.setImageResource(R.drawable.ic_trigger_g)
            disk.imageTintList = null
            disk.setPadding(insetX, insetY, insetX, insetY)
            disk.contentDescription = context.getString(R.string.bubble_lookup_label)
        }
    }

    /**
     * The remove target: a full-width window along the bottom of the display holding a soft dark
     * scrim, and in it the round neutral disk with the app's close glyph. The disk carries its own
     * description and hint so an accessibility service can perform the one action it names.
     */
    private fun buildDismiss(neutral: NeutralRoles): FrameLayout {
        val disk = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(neutral.surface.toArgb())
            setStroke(strokePx, neutral.outline.toArgb())
        }
        val inset = ((DISMISS_DP - DISMISS_ICON_DP) / 2f * density).toInt()
        val hint = context.getString(R.string.bubble_lookup_close_hint)
        val circle = ImageView(context).apply {
            setImageResource(R.drawable.ic_bubble_close)
            // Red ink on the neutral paper: the disk stays the theme's own paper, and only the glyph
            // says "remove", in the ink the theme writes errors and destructive actions with.
            imageTintList = ColorStateList.valueOf(
                (if (isDark()) DarkError else LightError).toArgb()
            )
            scaleType = ImageView.ScaleType.CENTER
            background = disk
            setPadding(inset, inset, inset, inset)
            elevation = DISMISS_ELEVATION_DP * density
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
        val frame = FrameLayout(context).apply {
            clipChildren = false
            layoutParams = FrameLayout.LayoutParams(dismissFramePx, dismissFramePx)
            addView(circle)
        }
        dismissFrame = frame
        return FrameLayout(context).apply {
            clipChildren = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, SCRIM_ALPHA shl 24)
            )
            addView(frame)
        }
    }

    /**
     * Builds the remove target and adds its window, hidden and untouchable. Added with the bubble's,
     * so the bubble — added after — stays in front of it. A failure is logged and swallowed: a
     * bubble without a target is still a working bubble.
     */
    private fun addDismissWindow() {
        if (dismissView != null) return
        try {
            val target = buildDismiss(gatenNeutrals(isDark()))
            val layoutParams = overlayParams(0 to 0).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            target.alpha = 0f
            target.visibility = View.GONE
            windowManager.addView(target, layoutParams)
            dismissView = target
            dismissParams = layoutParams
        } catch (e: RuntimeException) {
            dismissView = null
            dismissFrame = null
            dismissCircle = null
            logWindowNotAdded("add", e)
        }
    }

    /** The shared params for one overlay window, positioned in display coordinates. */
    private fun overlayParams(position: Pair<Int, Int>) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            // The bubble's shadow ring may hang past the edge so the disk can sit close to it.
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            // Shadows and the springs' per-frame moves need the GPU path.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitInsetsTypes = 0
        }
        x = position.first
        y = position.second
    }

    // ---------------------------------------------------------------------------------------------
    // Geometry
    // ---------------------------------------------------------------------------------------------

    /** The whole display, in display coordinates (the system bars included). */
    private fun fullDisplayBounds(): Rect {
        val metrics = context.resources.displayMetrics
        val fallback = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        return try {
            Rect(windowManager.currentWindowMetrics.bounds)
        } catch (e: RuntimeException) {
            fallback
        }
    }

    /**
     * The display area the app can actually use, in display coordinates: the display bounds less the
     * system-bar and cutout insets. Below API 30 the metrics' own usable size is used.
     */
    private fun usableDisplayBounds(): Rect {
        val metrics = context.resources.displayMetrics
        val fallback = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fallback
        return try {
            val current = windowManager.currentWindowMetrics
            val insets = current.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val bounds = current.bounds
            Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom
            )
        } catch (e: RuntimeException) {
            fallback
        }
    }

    /** The remove target's centre, in display coordinates. */
    private fun dismissCentre(): Pair<Float, Float> {
        val usable = usableDisplayBounds()
        val y = usable.bottom - dismissBottomMarginPx - dismissPx / 2f
        return usable.exactCenterX() to y.coerceAtLeast(usable.top + dismissPx / 2f)
    }

    /** The bubble window position that puts the disk's centre on the target's centre. */
    private fun bubbleOnDismiss(): Pair<Int, Int> {
        val (cx, cy) = dismissCentre()
        return (cx - bubbleWindowPx / 2f).toInt() to (cy - bubbleWindowPx / 2f).toInt()
    }

    /**
     * Sizes and places the target's scrim window for the display as it is now, and the target's
     * frame inside it: a rotation or resize lays it out again on the next drag.
     */
    private fun layoutDismiss() {
        val target = dismissView ?: return
        val frame = dismissFrame ?: return
        val targetParams = dismissParams ?: return
        val full = fullDisplayBounds()
        val (cx, cy) = dismissCentre()
        val top = (cy - dismissFramePx / 2f - scrimRisePx).toInt().coerceAtLeast(full.top)
        targetParams.x = full.left
        targetParams.y = top
        target.minimumWidth = full.width()
        target.minimumHeight = full.bottom - top
        (frame.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = (cx - dismissFramePx / 2f).toInt() - full.left
            topMargin = (cy - dismissFramePx / 2f).toInt() - top
        }
        frame.requestLayout()
    }

    /**
     * The last dragged position, or a sensible first one: docked right at ~45% height. A stored
     * position is clamped and re-docked too, so one written at another orientation (or by an older
     * build) cannot put the bubble where it can be neither seen nor touched.
     */
    private fun restoredOrDefaultPosition(): Pair<Int, Int> {
        TriggerPreferences.floatingButtonPosition(context)?.let {
            return snappedToEdge(clampToDisplay(it))
        }
        val metrics = context.resources.displayMetrics
        val y = (metrics.heightPixels * DEFAULT_HEIGHT_FRACTION).toInt()
        return snappedToEdge(metrics.widthPixels to y)
    }

    /** A window position with the whole disk inside the display; its shadow ring may hang off. */
    private fun clampToDisplay(position: Pair<Int, Int>): Pair<Int, Int> {
        val full = fullDisplayBounds()
        val minX = full.left - bubblePadPx
        val minY = full.top - bubblePadPx
        val maxX = (full.right - bubbleWindowPx + bubblePadPx).coerceAtLeast(minX)
        val maxY = (full.bottom - bubbleWindowPx + bubblePadPx).coerceAtLeast(minY)
        return position.first.coerceIn(minX, maxX) to position.second.coerceIn(minY, maxY)
    }

    /** The y range a docked disk may rest in: inside the usable area, [EDGE_MARGIN_DP] clear. */
    private fun dockYRange(usable: Rect): IntRange {
        val minY = usable.top + edgeMarginPx - bubblePadPx
        val maxY = (usable.bottom - edgeMarginPx - bubblePx - bubblePadPx).coerceAtLeast(minY)
        return minY..maxY
    }

    /** The two x positions a docked window may rest at: its disk against the left or right edge. */
    private fun dockXs(usable: Rect): Pair<Int, Int> {
        val left = usable.left + edgeMarginPx - bubblePadPx
        val right = (usable.right - edgeMarginPx - bubblePx - bubblePadPx).coerceAtLeast(left)
        return left to right
    }

    /** A position docked to the nearer vertical edge at the height it was let go at. */
    private fun snappedToEdge(position: Pair<Int, Int>): Pair<Int, Int> {
        val usable = usableDisplayBounds()
        val (left, right) = dockXs(usable)
        val centre = position.first + bubbleWindowPx / 2
        val x = if (centre < usable.centerX()) left else right
        return x to position.second.coerceIn(dockYRange(usable))
    }

    /**
     * Moves the showing bubble back inside the display after a rotation or a resize and re-docks it
     * to the nearer edge; a target that is up is laid out again for the new display.
     */
    fun reclampToDisplay() = onMain {
        positionSpring.cancel()
        val layoutParams = params
        val root = view
        if (layoutParams != null && root != null) {
            val (x, y) = snappedToEdge(clampToDisplay(layoutParams.x to layoutParams.y))
            if (x != layoutParams.x || y != layoutParams.y) {
                moveBubbleTo(x.toFloat(), y.toFloat())
                TriggerPreferences.setFloatingButtonPosition(context, x, y)
            }
        }
        val target = dismissView
        val targetParams = dismissParams
        if (target != null && targetParams != null && target.visibility == View.VISIBLE) {
            layoutDismiss()
            updateLayout(target, targetParams)
        }
    }

    private fun moveBubbleTo(x: Float, y: Float) {
        val layoutParams = params ?: return
        val root = view ?: return
        val nx = x.toInt()
        val ny = y.toInt()
        if (nx == layoutParams.x && ny == layoutParams.y) return
        layoutParams.x = nx
        layoutParams.y = ny
        updateLayout(root, layoutParams)
    }

    private fun updateLayout(target: View, layoutParams: WindowManager.LayoutParams) {
        try {
            windowManager.updateViewLayout(target, layoutParams)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Floating trigger: could not move the overlay window", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Remove target
    // ---------------------------------------------------------------------------------------------

    /**
     * Brings the remove target up for a drag: the scrim fades in and the disk rises into place from
     * below. Its window was added with the bubble's and is only shown here, never added, so it stays
     * under the bubble.
     */
    private fun showDismiss() {
        if (view == null) return
        if (dismissView == null) addDismissWindow()
        val target = dismissView ?: return
        val frame = dismissFrame ?: return
        val circle = dismissCircle ?: return
        val targetParams = dismissParams ?: return
        layoutDismiss()
        targetParams.flags = targetParams.flags and
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (target.visibility != View.VISIBLE) {
            target.alpha = 0f
            frame.translationY = dismissTravelPx
            frame.alpha = 0f
            circle.scaleX = 1f
            circle.scaleY = 1f
        }
        target.visibility = View.VISIBLE
        updateLayout(target, targetParams)
        target.animate().cancel()
        target.animate().alpha(1f)
            .setDuration(Motion.SHORT.toLong())
            .setInterpolator { Motion.emphasized.transform(it) }
            .start()
        frame.animate().cancel()
        frame.animate().translationY(0f).alpha(1f)
            .setDuration(Motion.MEDIUM.toLong())
            .setInterpolator { Motion.emphasizedDecelerate.transform(it) }
            .start()
    }

    /**
     * Takes the target down — scrim out, disk sinking away — and leaves its window in place,
     * `GONE` and untouchable; a no-op when already down.
     */
    private fun hideDismiss() {
        val target = dismissView ?: return
        val frame = dismissFrame ?: return
        if (target.visibility != View.VISIBLE) return
        frame.animate().cancel()
        frame.animate().translationY(dismissTravelPx).alpha(0f)
            .setDuration(Motion.SHORT.toLong())
            .setInterpolator { Motion.emphasizedAccelerate.transform(it) }
            .start()
        target.animate().cancel()
        target.animate().alpha(0f)
            .setDuration(Motion.SHORT.toLong())
            .setInterpolator { Motion.emphasized.transform(it) }
            .withEndAction {
                if (dismissView !== target) return@withEndAction
                target.visibility = View.GONE
                dismissParams?.let { p ->
                    p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    updateLayout(target, p)
                }
                dismissCircle?.let {
                    it.animate().cancel()
                    it.scaleX = 1f
                    it.scaleY = 1f
                }
            }
            .start()
    }

    /** Grows the target to take a bubble locked onto it, or lets it back down. */
    private fun animateDismissScale(enlarged: Boolean) {
        val circle = dismissCircle ?: return
        val to = if (enlarged) DISMISS_HOT_SCALE else 1f
        circle.animate().cancel()
        circle.animate().scaleX(to).scaleY(to)
            .setDuration(Motion.MEDIUM.toLong())
            .setInterpolator(if (enlarged) OvershootInterpolator(2f) else TimeInterpolator {
                Motion.pressEasing.transform(it)
            })
            .start()
    }

    // ---------------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------------

    /**
     * Press handling: past the touch slop the press becomes a drag; otherwise a release is a tap
     * that fires the lookup at once. Movement is the whole test — nothing here is bound to a long
     * press. A cancellation never removes the bubble and never turns the preference off.
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        val layoutParams = params ?: return false
        if (fadingOut) return true
        trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A finger on a bubble still gliding takes it over from where it stands.
                positionSpring.cancel()
                wake()
                downRawX = event.rawX
                downRawY = event.rawY
                downX = layoutParams.x
                downY = layoutParams.y
                dragging = false
                magnetized = false
                bubbleView?.animate()?.scaleX(BUBBLE_PRESS_SCALE)?.scaleY(BUBBLE_PRESS_SCALE)
                    ?.setDuration(Motion.SHORT.toLong())
                    ?.setInterpolator { Motion.pressEasing.transform(it) }
                    ?.start()
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && hypot(dx, dy) > touchSlopPx) {
                    dragging = true
                    feedback?.haptic(FeedbackHaptic.Grab)
                    // Picked up: back to full size, lifted higher off the page.
                    bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)
                        ?.translationZ(BUBBLE_LIFT_DP * density)
                        ?.setDuration(Motion.SHORT.toLong())
                        ?.setInterpolator(OvershootInterpolator(2f))
                        ?.start()
                    // A lookup's close control only moves: no remove target while it is one.
                    if (!closeMode) showDismiss()
                }
                if (dragging) followFinger(downX + dx.toInt(), downY + dy.toInt())
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    val (vx, vy) = releaseVelocity()
                    endDrag()
                    if (magnetized || thrownAtDismiss(vx, vy)) {
                        magnetized = false
                        dropOnDismiss()
                    } else {
                        hideDismiss()
                        dockToEdge(vx, vy)
                    }
                } else {
                    // The lookup fires on the tap itself; the disk springs back up meanwhile, and
                    // an accepted capture takes the bubble off screen in the same pass.
                    bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)
                        ?.setDuration(Motion.MEDIUM.toLong())
                        ?.setInterpolator(OvershootInterpolator(3f))
                        ?.start()
                    feedback?.haptic(FeedbackHaptic.Tick)
                    scheduleIdle()
                    onTrigger()
                }
                recycleVelocity()
            }

            MotionEvent.ACTION_CANCEL -> {
                bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(Motion.SHORT.toLong())
                    ?.start()
                if (dragging) {
                    endDrag()
                    magnetized = false
                    animateDismissScale(false)
                    hideDismiss()
                    dockToEdge(0f, 0f)
                } else {
                    scheduleIdle()
                }
                recycleVelocity()
            }
        }
        return true
    }

    /**
     * Moves a dragged bubble for the finger now at window position ([x], [y]). Near the target, the
     * magnet takes it: a spring carries the bubble onto the target and holds it there; pulled far
     * enough away it lets go and springs back under the finger, after which it tracks 1:1 again.
     */
    private fun followFinger(x: Int, y: Int) {
        val finger = clampToDisplay(x to y)
        val (cx, cy) = dismissCentre()
        val distance = hypot(
            finger.first + bubbleWindowPx / 2f - cx,
            finger.second + bubbleWindowPx / 2f - cy
        )
        val shouldLock = if (magnetized) {
            distance < magnetPx * MAGNET_RELEASE_FACTOR
        } else {
            distance < magnetPx
        }
        if (shouldLock != magnetized && dismissView?.visibility == View.VISIBLE) {
            magnetized = shouldLock
            val from = params ?: return
            if (shouldLock) {
                feedback?.haptic(FeedbackHaptic.Snap)
                animateDismissScale(true)
                val (tx, ty) = bubbleOnDismiss()
                positionSpring.start(
                    from.x.toFloat(), from.y.toFloat(), 0f, 0f,
                    tx.toFloat(), ty.toFloat(),
                    SNAP_STIFFNESS, SNAP_DAMPING, SNAP_DAMPING
                )
            } else {
                feedback?.haptic(FeedbackHaptic.Unsnap)
                animateDismissScale(false)
                positionSpring.start(
                    from.x.toFloat(), from.y.toFloat(), 0f, 0f,
                    finger.first.toFloat(), finger.second.toFloat(),
                    UNSNAP_STIFFNESS, UNSNAP_DAMPING, UNSNAP_DAMPING
                )
            }
            return
        }
        if (magnetized) return
        // Still catching up with the finger after letting go of the target: chase it, then hand
        // back to direct tracking once the spring has caught it.
        if (positionSpring.running) {
            positionSpring.retarget(finger.first.toFloat(), finger.second.toFloat())
        } else {
            moveBubbleTo(finger.first.toFloat(), finger.second.toFloat())
        }
    }

    /** The end of a drag, either way: the disk settles back onto the page. */
    private fun endDrag() {
        dragging = false
        bubbleView?.animate()?.scaleX(1f)?.scaleY(1f)?.translationZ(0f)
            ?.setDuration(Motion.SHORT.toLong())
            ?.setInterpolator { Motion.pressEasing.transform(it) }
            ?.start()
    }

    /** Whether a release was a throw straight at the target: fast, downward, and landing near it. */
    private fun thrownAtDismiss(vx: Float, vy: Float): Boolean {
        val from = params ?: return false
        if (dismissView?.visibility != View.VISIBLE) return false
        if (vy < flingMinPx * 2 || hypot(vx, vy) < flingMinPx * 2) return false
        val (cx, cy) = dismissCentre()
        val centreX = from.x + bubbleWindowPx / 2f
        val centreY = from.y + bubbleWindowPx / 2f
        if (cy <= centreY) return false
        // Where the throw crosses the target's height, if it keeps its direction.
        val t = (cy - centreY) / vy
        val crossX = centreX + vx * t
        return abs(crossX - cx) < magnetPx && t < 0.6f
    }

    /**
     * Docks a released bubble to an edge with a spring that keeps the release's momentum: a fast
     * sideways throw picks the edge it was thrown toward, and the vertical speed carries the height a
     * little further. The docked position is stored at once, so a process death mid-glide restores
     * the dock. A light tick lands with the disk when it really flew there.
     */
    private fun dockToEdge(vx: Float, vy: Float) {
        val layoutParams = params ?: return
        val usable = usableDisplayBounds()
        val (left, right) = dockXs(usable)
        val projectedCentre = layoutParams.x + bubbleWindowPx / 2f + vx * FLING_PROJECTION_S
        val x = when {
            abs(vx) > flingMinPx -> if (vx < 0) left else right
            projectedCentre < usable.exactCenterX() -> left
            else -> right
        }
        val y = (layoutParams.y + vy * FLING_PROJECTION_S).toInt().coerceIn(dockYRange(usable))
        TriggerPreferences.setFloatingButtonPosition(context, x, y)
        val travel = hypot((x - layoutParams.x).toFloat(), (y - layoutParams.y).toFloat())
        var landed = travel < landMinPx
        val startX = layoutParams.x.toFloat()
        positionSpring.onFrameExtra = { px, _ ->
            // The tick lands on the first touch of the edge, not at the end of the settle.
            if (!landed && (px - x) * (startX - x) <= 0f) {
                landed = true
                feedback?.haptic(FeedbackHaptic.Land)
            }
        }
        positionSpring.start(
            layoutParams.x.toFloat(), layoutParams.y.toFloat(), vx, vy,
            x.toFloat(), y.toFloat(),
            DOCK_STIFFNESS, DOCK_DAMPING_X, DOCK_DAMPING_Y,
            clampY = dockYRange(usable).let { (it.first - bubblePadPx)..(it.last + bubblePadPx) }
        ) { scheduleIdle() }
    }

    /**
     * The release over the target: the bubble finishes its glide onto the target, both disks shrink
     * away together and the scrim fades, then the control is switched off.
     */
    private fun dropOnDismiss() {
        val from = params
        if (from != null) {
            val (tx, ty) = bubbleOnDismiss()
            positionSpring.start(
                from.x.toFloat(), from.y.toFloat(), 0f, 0f, tx.toFloat(), ty.toFloat(),
                UNSNAP_STIFFNESS, UNSNAP_DAMPING, UNSNAP_DAMPING
            )
        }
        dismissCircle?.animate()?.scaleX(0.6f)?.scaleY(0.6f)
            ?.setDuration(Motion.MEDIUM.toLong())
            ?.setInterpolator { Motion.emphasizedAccelerate.transform(it) }
            ?.start()
        dismissByReader()
    }

    /**
     * The reader removed the floating control: one confirming tick, the preference written off, and
     * the ordinary hide fades it away. Only the floating-button preference moves — lookup mode and
     * the Quick Settings tile are separate switches. A drop on the target and the target's own
     * accessibility activation both arrive here, so the tick fires exactly once for either.
     */
    private fun dismissByReader() {
        feedback?.haptic(FeedbackHaptic.Remove)
        TriggerPreferences.setFloatingButtonEnabled(context, false)
        onDismissed()
        hide()
    }

    private fun trackVelocity(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            velocityTracker?.recycle()
            velocityTracker = VelocityTracker.obtain()
        }
        val tracker = velocityTracker ?: return
        // The view moves under the finger, so its own coordinates are useless for speed; the raw
        // display coordinates are what the finger actually did.
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker.addMovement(copy)
        copy.recycle()
    }

    private fun releaseVelocity(): Pair<Float, Float> {
        val tracker = velocityTracker ?: return 0f to 0f
        tracker.computeCurrentVelocity(1000, 8000f * density)
        return tracker.xVelocity to tracker.yVelocity
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    // ---------------------------------------------------------------------------------------------
    // Idle dim
    // ---------------------------------------------------------------------------------------------

    private fun scheduleIdle() {
        mainHandler.removeCallbacks(dimIdle)
        // The close control never dims: it is what the reader is looking for to get out.
        if (view != null && !closeMode) mainHandler.postDelayed(dimIdle, IDLE_DELAY_MS)
    }

    private fun cancelIdle() {
        mainHandler.removeCallbacks(dimIdle)
    }

    /** Brings a dimmed disk back to full strength at once, for a finger that has just found it. */
    private fun wake() {
        cancelIdle()
        val disk = bubbleView ?: return
        if (disk.alpha < 1f) {
            disk.animate().alpha(1f).setDuration(Motion.SHORT.toLong() / 2).start()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Window opacity
    // ---------------------------------------------------------------------------------------------

    /**
     * Moves the bubble window's opacity over [durationMs]. One animator at a time: whatever was
     * running is stopped first, and cleared before the stop, so its own end callback sees itself
     * superseded. [onEnd] runs only for the animation that really finished.
     */
    private fun animateBubbleAlpha(to: Float, durationMs: Int, onEnd: (() -> Unit)? = null) {
        val bubble = view ?: return
        stopAlpha()
        val animator = ValueAnimator.ofFloat(bubble.alpha, to).apply {
            duration = durationMs.toLong()
            setInterpolator(TimeInterpolator { Motion.emphasized.transform(it) })
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

/**
 * A two-axis damped spring for a window's position, stepped on the display's own frames. Unlike a
 * tween it starts from a velocity — a throw keeps its momentum — and its target can move while it
 * runs, which is what lets a bubble chase a finger. With animations off in the system settings it
 * lands on its target at once.
 */
private class WindowSpring(private val onFrame: (Float, Float) -> Unit) : Choreographer.FrameCallback {
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var tx = 0f
    private var ty = 0f
    private var stiffness = 0f
    private var dampingX = 1f
    private var dampingY = 1f
    private var clampY: IntRange? = null
    private var lastFrameNanos = 0L
    private var onEnd: (() -> Unit)? = null

    /** Called with every frame's position after [onFrame]; cleared when a new run starts. */
    var onFrameExtra: ((Float, Float) -> Unit)? = null

    var running = false
        private set

    fun start(
        fromX: Float,
        fromY: Float,
        velocityX: Float,
        velocityY: Float,
        toX: Float,
        toY: Float,
        stiffness: Float,
        dampingX: Float,
        dampingY: Float,
        clampY: IntRange? = null,
        onEnd: (() -> Unit)? = null
    ) {
        val extra = onFrameExtra
        cancel()
        onFrameExtra = extra
        x = fromX
        y = fromY
        vx = velocityX
        vy = velocityY
        tx = toX
        ty = toY
        this.stiffness = stiffness
        this.dampingX = dampingX
        this.dampingY = dampingY
        this.clampY = clampY
        this.onEnd = onEnd
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finish()
            return
        }
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun retarget(toX: Float, toY: Float) {
        tx = toX
        ty = toY
    }

    /** Stops where it stands, without its end callback. */
    fun cancel() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        onEnd = null
        onFrameExtra = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
        }
        lastFrameNanos = frameTimeNanos
        // Small fixed sub-steps keep the stiff snap springs stable on a slow frame.
        var remaining = dt
        while (remaining > 0f) {
            val h = min(remaining, 0.004f)
            vx += (-stiffness * (x - tx) - 2f * dampingX * sqrt(stiffness) * vx) * h
            vy += (-stiffness * (y - ty) - 2f * dampingY * sqrt(stiffness) * vy) * h
            x += vx * h
            y += vy * h
            remaining -= h
        }
        clampY?.let { range ->
            if (y < range.first) { y = range.first.toFloat(); vy = 0f }
            if (y > range.last) { y = range.last.toFloat(); vy = 0f }
        }
        val settled = abs(x - tx) < 0.5f && abs(y - ty) < 0.5f && abs(vx) < 30f && abs(vy) < 30f
        if (settled) {
            finish()
            return
        }
        onFrame(x, y)
        onFrameExtra?.invoke(x, y)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun finish() {
        running = false
        x = tx
        y = ty
        onFrame(x, y)
        onFrameExtra?.invoke(x, y)
        val end = onEnd
        onEnd = null
        onFrameExtra = null
        end?.invoke()
    }
}
