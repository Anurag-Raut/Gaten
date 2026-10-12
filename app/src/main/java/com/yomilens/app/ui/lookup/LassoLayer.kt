/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ui.lookup

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput

/** Degenerate gestures (a tap or a sliver) are ignored below this side length, in view px. */
internal const val MIN_SELECTION_PX = 24f

/** Bounding box of a freehand stroke, in view pixels; null when there is nothing drawn. */
internal fun boundingBoxOf(points: List<Offset>): Rect? {
    if (points.isEmpty()) return null
    return Rect(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y }
    )
}

/** Polyline through the sampled drag points. */
internal fun buildTrailPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        lineTo(points[i].x, points[i].y)
    }
}

/**
 * The full-screen selection canvas over the frozen screenshot.
 *
 * Draws the dim-over-everything scrim (the selection box stays clear with a 2px outline), the
 * live lasso trail while the user is drawing, and the frozen trail fading out once the result
 * has arrived. Drag and tap gestures are active only while [interactive]; the result sheet sits
 * on top and handles its own touches afterwards.
 *
 * The points themselves are hoisted into [LookupOverlay] so the lasso can survive recomposition
 * and be reset on "select again"; [onLassoEnd] reads that state at call time.
 */
@Composable
fun LassoLayer(
    screenshot: Bitmap?,
    points: List<Offset>,
    frozenTrail: Path?,
    frozenBox: Rect?,
    interactive: Boolean,
    trailFading: Boolean,
    onLassoStart: (Offset) -> Unit,
    onLassoUpdate: (Offset) -> Unit,
    onLassoEnd: () -> Unit,
    onLassoCancel: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val currentStart by rememberUpdatedState(onLassoStart)
    val currentUpdate by rememberUpdatedState(onLassoUpdate)
    val currentEnd by rememberUpdatedState(onLassoEnd)
    val currentCancel by rememberUpdatedState(onLassoCancel)
    val currentTap by rememberUpdatedState(onTap)
    val liveBox = remember(points) { boundingBoxOf(points) }

    // The frozen lasso stays put while the scan runs and fades out once the result shows.
    val trailAlpha by animateFloatAsState(
        targetValue = if (trailFading) 0f else 1f,
        animationSpec = tween(420),
        label = "trailAlpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(interactive, screenshot) {
                if (interactive && screenshot != null) {
                    detectDragGestures(
                        onDragStart = { offset -> currentStart(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            currentUpdate(change.position)
                        },
                        onDragEnd = { currentEnd() },
                        onDragCancel = { currentCancel() }
                    )
                }
            }
            .pointerInput(interactive) {
                if (interactive) {
                    // A tap without dragging closes the overlay; a drag is never a tap.
                    detectTapGestures { currentTap() }
                }
            }
    ) {
        val dim = Color.Black.copy(alpha = 0.35f)
        val box = frozenBox ?: liveBox
        if (box == null) {
            drawRect(dim)
        } else {
            // Dim everything outside the selection.
            drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, box.top.coerceAtLeast(0f)))
            drawRect(
                dim,
                topLeft = Offset(0f, box.bottom),
                size = Size(size.width, (size.height - box.bottom).coerceAtLeast(0f))
            )
            drawRect(
                dim,
                topLeft = Offset(0f, box.top),
                size = Size(box.left.coerceAtLeast(0f), box.height)
            )
            drawRect(
                dim,
                topLeft = Offset(box.right, box.top),
                size = Size((size.width - box.right).coerceAtLeast(0f), box.height)
            )
            drawRect(primary, topLeft = box.topLeft, size = box.size, style = Stroke(width = 2f))
        }
        // The frozen lasso trail, fading out on completion.
        frozenTrail?.let { trail ->
            drawPath(trail, primary.copy(alpha = trailAlpha * 0.12f))
            drawPath(
                path = trail,
                color = primary.copy(alpha = trailAlpha),
                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        // The user's own stroke while drawing.
        if (points.size > 1) {
            val live = buildTrailPath(points)
            drawPath(live, primary.copy(alpha = 0.12f))
            drawPath(
                path = live,
                color = primary,
                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}