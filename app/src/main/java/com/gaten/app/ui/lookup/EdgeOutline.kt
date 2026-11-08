/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.res.Resources
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** The ring's stroke width, and how long its gradient takes to drift one way while work runs. */
private val OutlineStroke = 3.dp
private const val TRAVEL_MS = 2400

/** How long the ring takes to draw itself in from the bottom midpoint on open. */
private const val REVEAL_MS = 900

/**
 * How long it takes to leave again once the result sheet is up: the same animation backwards, but
 * quicker and accelerating away, where the reveal was settling into place. The retract only has to
 * clear the way for a sheet that is already sliding in.
 */
private const val RETRACT_MS = 420

/**
 * The thin gradient ring around the whole overlay, over everything else in it.
 *
 * On open the ring draws itself in: two strokes leave the screen's bottom midpoint together, run
 * left and right along the bottom edge, up the sides and meet at the top midpoint. The stroke is
 * inset by half its own width so it stays inside the screen, and follows the panel's rounded
 * corners where the window reports one (API 31+; square before that). Once drawn, its gradient
 * runs primary -> tertiary -> primary along the diagonal and drifts along that diagonal while
 * [busy], so a lookup or the screen scan is visible at the very edge of the display; it holds
 * still when idle. Nothing here takes touches — it is drawing only.
 *
 * While [shown] is false the ring retracts: the reveal played backwards, so the two strokes leave
 * the top midpoint last and meet again at the bottom midpoint. That is what happens when the result
 * sheet comes up — the ring is the overlay's own frame, and a frame is not something the sheet
 * should have to sit inside or cover. It draws itself back in on "Select again", the same way it
 * arrived.
 */
@Composable
fun EdgeOutline(busy: Boolean, shown: Boolean, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val frameworkRadii = remember { frameworkCornerRadii() }
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val tertiary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        reveal.animateTo(
            targetValue = if (shown) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (shown) REVEAL_MS else RETRACT_MS,
                easing = if (shown) FastOutSlowInEasing else FastOutLinearInEasing
            )
        )
    }
    // Only while it is up: a retracted ring is not drawn, so there is nothing for the drift to show.
    val sweep = if (busy && shown) {
        rememberInfiniteTransition(label = "edge outline").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(TRAVEL_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "edge outline drift"
        )
    } else {
        null
    }
    // Rebuilt in place each frame rather than reallocated: the draw scope runs per frame.
    val rightHalf = remember { Path() }
    val leftHalf = remember { Path() }
    val rightSegment = remember { Path() }
    val leftSegment = remember { Path() }
    val rightMeasure = remember { PathMeasure() }
    val leftMeasure = remember { PathMeasure() }

    Canvas(modifier = modifier) {
        // Read in the draw scope: the reveal and drift then only redraw the ring, never recompose it.
        val progress = reveal.value
        if (progress <= 0f) return@Canvas
        val shift = sweep?.value ?: 0.5f
        val radii = deviceCornerRadii(view, frameworkRadii)
        // The gradient is as long as the diagonal and slid by up to half of it, which keeps the
        // whole ring inside it: the drift moves the tertiary band along rather than off the screen.
        val span = size.width + size.height
        val start = -span * shift * 0.5f
        val brush = Brush.linearGradient(
            colors = listOf(primary, tertiary, primary),
            start = Offset(start, start),
            end = Offset(start + span, start + span)
        )
        val stroke = OutlineStroke.toPx()
        val inset = stroke / 2f
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        // The ring's centreline sits half a stroke inside the screen edge, so each arc is that much
        // tighter than the display's own corner to stay concentric with it, and each corner keeps
        // its own radius: most panels round the top and the bottom differently.
        val limit = min(right - left, bottom - top) / 2f
        fun corner(index: Int) = (radii[index] - inset).coerceIn(0f, limit)
        rightHalf.halfRing(false, left, top, right, bottom, bottomRadius = corner(BOTTOM_RIGHT), topRadius = corner(TOP_RIGHT))
        leftHalf.halfRing(true, left, top, right, bottom, bottomRadius = corner(BOTTOM_LEFT), topRadius = corner(TOP_LEFT))

        // Each half grows from the bottom midpoint; the same fraction of both, so the two tips
        // stay level and meet at the top midpoint exactly when the reveal completes.
        rightMeasure.setPath(rightHalf, forceClosed = false)
        leftMeasure.setPath(leftHalf, forceClosed = false)
        // getSegment appends the extracted contour to the destination, so each destination is
        // cleared first — otherwise every drawn frame adds another half-ring to the path.
        rightSegment.reset()
        leftSegment.reset()
        if (rightMeasure.getSegment(0f, rightMeasure.length * progress, rightSegment, true)) {
            drawPath(rightSegment, brush = brush, style = Stroke(width = stroke))
        }
        if (leftMeasure.getSegment(0f, leftMeasure.length * progress, leftSegment, true)) {
            drawPath(leftSegment, brush = brush, style = Stroke(width = stroke))
        }
    }
}

/**
 * One half of the ring's contour, traced from the bottom midpoint toward the top midpoint: along
 * the bottom edge, around the near bottom corner, up the side, around the near top corner and in
 * to the top midpoint. [fromLeft] picks the left (counter-clockwise) or right (clockwise) half.
 */
private fun Path.halfRing(
    fromLeft: Boolean,
    l: Float,
    t: Float,
    r: Float,
    b: Float,
    bottomRadius: Float,
    topRadius: Float
) {
    reset()
    moveTo((l + r) / 2f, b)
    if (fromLeft) {
        lineTo(l + bottomRadius, b)
        if (bottomRadius > 0f) arcTo(Rect(l, b - 2 * bottomRadius, l + 2 * bottomRadius, b), 90f, 90f, false)
        lineTo(l, t + topRadius)
        if (topRadius > 0f) arcTo(Rect(l, t, l + 2 * topRadius, t + 2 * topRadius), 180f, 90f, false)
        lineTo(l + topRadius, t)
    } else {
        lineTo(r - bottomRadius, b)
        if (bottomRadius > 0f) arcTo(Rect(r - 2 * bottomRadius, b - 2 * bottomRadius, r, b), 90f, -90f, false)
        lineTo(r, t + topRadius)
        if (topRadius > 0f) arcTo(Rect(r - 2 * topRadius, t, r, t + 2 * topRadius), 0f, -90f, false)
        lineTo(r - topRadius, t)
    }
    lineTo((l + r) / 2f, t)
}

private const val TOP_LEFT = 0
private const val TOP_RIGHT = 1
private const val BOTTOM_RIGHT = 2
private const val BOTTOM_LEFT = 3

/**
 * The panel's four corner radii in px, in [TOP_LEFT], [TOP_RIGHT], [BOTTOM_RIGHT], [BOTTOM_LEFT]
 * order. Each corner is read from the window's own insets first (API 31+), then from the display
 * the window is on, and only then from the radius the system ships for that edge
 * ([frameworkRadii]) — which is the only source below API 31 and on panels whose window reports
 * no corner of its own. A corner nothing reports stays square.
 */
private fun deviceCornerRadii(view: View, frameworkRadii: FloatArray): FloatArray {
    val radii = frameworkRadii.copyOf()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val positions = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT
        )
        val insets = view.rootWindowInsets
        val display = view.display
        for (i in positions.indices) {
            val corner = insets?.getRoundedCorner(positions[i]) ?: display?.getRoundedCorner(positions[i])
            if (corner != null && corner.radius > 0) radii[i] = corner.radius.toFloat()
        }
    }
    return radii
}

/** The system's configured top and bottom corner radii (`rounded_corner_radius_*`), in px; 0 when absent. */
private fun frameworkCornerRadii(): FloatArray {
    fun dimen(name: String): Float {
        val res = Resources.getSystem()
        val id = res.getIdentifier(name, "dimen", "android")
        return if (id == 0) 0f else res.getDimension(id)
    }
    val top = dimen("rounded_corner_radius_top")
    val bottom = dimen("rounded_corner_radius_bottom")
    val fallback = dimen("rounded_corner_radius")
    val t = if (top > 0f) top else fallback
    val b = if (bottom > 0f) bottom else fallback
    return floatArrayOf(t, t, b, b)
}
