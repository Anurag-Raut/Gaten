/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** The ring's stroke width, and how long its gradient takes to drift one way while work runs. */
private val OutlineStroke = 3.dp
private const val TRAVEL_MS = 2400

/**
 * The thin gradient ring around the whole overlay, over everything else in it.
 *
 * The stroke is inset by half its own width so it stays inside the screen, and follows the panel's
 * rounded corners where the window reports one (API 31+; square before that). Its gradient runs
 * primary -> tertiary -> primary along the diagonal and drifts along that diagonal while [busy], so
 * a lookup or the screen scan is visible at the very edge of the display; it holds still when idle.
 * Nothing here takes touches — it is drawing only.
 */
@Composable
fun EdgeOutline(busy: Boolean, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val primary = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    val tertiary = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f)
    val sweep = rememberInfiniteTransition(label = "edge outline").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(TRAVEL_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "edge outline drift"
    )

    Canvas(modifier = modifier) {
        // Read in the draw scope: the drift then only redraws the ring, it never recomposes it.
        val shift = if (busy) sweep.value else 0.5f
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
        val stroke = OutlineStroke.toPx()
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(primary, tertiary, primary),
                start = Offset(start, start),
                end = Offset(start + span, start + span)
            ),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = stroke)
        )
    }
}
