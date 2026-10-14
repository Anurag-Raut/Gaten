/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.os.Build
import android.view.RoundedCorner
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
        val radius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                ?.radius?.toFloat() ?: 0f
        } else {
            0f
        }
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
        val corner = radius.coerceAtMost(min(right - left, bottom - top) / 2f)
        rightHalf.halfRing(fromLeft = false, l = left, t = top, r = right, b = bottom, radius = corner)
        leftHalf.halfRing(fromLeft = true, l = left, t = top, r = right, b = bottom, radius = corner)

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
private fun Path.halfRing(fromLeft: Boolean, l: Float, t: Float, r: Float, b: Float, radius: Float) {
    reset()
    moveTo((l + r) / 2f, b)
    if (radius <= 0f) {
        lineTo(if (fromLeft) l else r, b)
        lineTo(if (fromLeft) l else r, t)
    } else if (fromLeft) {
        lineTo(l + radius, b)
        arcTo(Rect(l, b - 2 * radius, l + 2 * radius, b), 90f, 90f, false)
        lineTo(l, t + radius)
        arcTo(Rect(l, t, l + 2 * radius, t + 2 * radius), 180f, 90f, false)
    } else {
        lineTo(r - radius, b)
        arcTo(Rect(r - 2 * radius, b - 2 * radius, r, b), 90f, -90f, false)
        lineTo(r, t + radius)
        arcTo(Rect(r - 2 * radius, t, r, t + 2 * radius), 0f, -90f, false)
    }
    lineTo((l + r) / 2f, t)
}
