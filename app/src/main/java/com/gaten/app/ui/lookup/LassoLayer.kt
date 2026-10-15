/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** Degenerate gestures (a tap or a sliver) are ignored below this side length, in view px. */
internal const val MIN_SELECTION_PX = 24f

/*
 * The lasso trail is three passes over one smoothed path, the way a route is drawn on a map: a
 * wide translucent bloom for the glow, a dark casing that carries the contrast, then the gradient
 * core inside it. The core is the theme's indigo - primary drifting to tertiary and back, the same
 * two tones the edge outline uses - and the casing under it is deliberately NOT a theme role: the
 * theme's dark-mode roles flip polarity, so a role-derived casing would vanish on one kind of page
 * or the other. A fixed dark casing keeps the stroke readable on a white manga page and on a
 * dark game UI alike.
 */
private val TrailBloomWidth = 14.dp
private val TrailCasingWidth = 5.5.dp
private val TrailCoreWidth = 3.dp
private const val TrailBloomAlpha = 0.14f
private const val TrailCasingAlpha = 0.75f

/** The selection edge: the same bloom-over-core language, drawn around the box instead. */
private val EdgeBloomWidth = 10.dp
private val EdgeCoreWidth = 2.dp
private const val EdgeBloomAlpha = 0.18f

/** How far the clear region's corners are rounded out of the dim, in dp. */
private val SelectionCorner = 12.dp

/** The dim over everything outside the selection. */
private const val ScrimAlpha = 0.35f

/**
 * What the selection edge is cut back to once the result sheet is up: still there,
 * marking the region the answer came from, but no longer the subject of the screen.
 */
private const val ResultEmphasis = 0.4f

/** A rounded [Stroke] of [width] dp, resolved once per density rather than per drawn frame. */
@Composable
private fun roundStroke(width: Dp): Stroke {
    val density = LocalDensity.current
    return remember(density) {
        Stroke(with(density) { width.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
}

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

/**
 * [points] as a smoothed path, written into [path]: one quadratic per sampled point, through the
 * midpoints of the segments either side of it, so the coarse one-point-per-drag-event polyline
 * reads as a curve. A quadratic never leaves the convex hull of its control points, so the trail
 * cannot overshoot the sampled bounds the selection is cut from. In place, so the draw scope can
 * keep reusing one path instead of allocating a new one every frame.
 */
internal fun trailInto(path: Path, points: List<Offset>) {
    path.reset()
    if (points.isEmpty()) return
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.lastIndex) {
        val mid = Offset((points[i].x + points[i + 1].x) / 2f, (points[i].y + points[i + 1].y) / 2f)
        path.quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
    }
    if (points.size > 1) path.lineTo(points.last().x, points.last().y)
}

/**
 * The full-screen selection canvas over the frozen screenshot.
 *
 * Draws the dim-over-everything scrim as one even-odd fill — the whole canvas less a rounded hole
 * at the selection, so the clear region the reader chose has soft corners — then the selection's
 * own edge and the live lasso trail only while the user is drawing. Drag and tap gestures are active
 * only while [interactive]; the result sheet sits on top and handles its own touches afterwards.
 *
 * The selection edge is cut back once the result is up ([ResultEmphasis]): the reader can still
 * see which region was read while the sheet explains it. The trail disappears on finger lift.
 *
 * The points themselves are hoisted into [LookupOverlay] so the lasso can survive recomposition
 * and be reset on "select again"; [onLassoEnd] reads that state at call time.
 */
@Composable
fun LassoLayer(
    screenshot: Bitmap?,
    points: List<Offset>,
    frozenBox: Rect?,
    interactive: Boolean,
    showingResult: Boolean,
    onLassoStart: (Offset) -> Unit,
    onLassoUpdate: (Offset) -> Unit,
    onLassoEnd: () -> Unit,
    onLassoCancel: () -> Unit,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val currentStart by rememberUpdatedState(onLassoStart)
    val currentUpdate by rememberUpdatedState(onLassoUpdate)
    val currentEnd by rememberUpdatedState(onLassoEnd)
    val currentCancel by rememberUpdatedState(onLassoCancel)
    val currentTap by rememberUpdatedState(onTap)
    val liveBox = remember(points) { boundingBoxOf(points) }
    val box = frozenBox ?: liveBox

    // The selection keeps a quieter edge under the sheet, so the region that was read stays marked.
    val edgeEmphasis by animateFloatAsState(
        targetValue = if (showingResult) ResultEmphasis else 1f,
        animationSpec = tween(220),
        label = "edgeEmphasis"
    )

    // Measured once. The two things that are laid out from the canvas — the trail's sheen and the
    // scrim's outer rectangle — are then built in composition rather than per drawn frame.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // The sheen: the theme's two indigo tones along the viewport diagonal, so one stroke changes
    // tone as it crosses the display. Fixed in screen space, rebuilt only when the theme or the
    // size changes, and null until the canvas is measured — a zero-length gradient is no brush.
    val tertiary = MaterialTheme.colorScheme.tertiary
    val trailBrush = remember(viewSize, primary, tertiary) {
        if (viewSize.width <= 0 || viewSize.height <= 0) {
            null
        } else {
            Brush.linearGradient(
                colors = listOf(primary, tertiary, primary),
                start = Offset.Zero,
                end = Offset(viewSize.width.toFloat(), viewSize.height.toFloat())
            )
        }
    }

    // Rebuilt as the finger moves, never inside the draw scope: smoothing is O(points) work.
    val liveTrail = remember { Path() }
    remember(points) { trailInto(liveTrail, points) }

    // The clear region's rounded hole: the whole canvas less the box, as one even-odd path. Rebuilt
    // only when the selection or the canvas changes; empty while there is no selection to cut.
    val cornerPx = with(LocalDensity.current) { SelectionCorner.toPx() }
    val scrimPath = remember { Path().apply { fillType = PathFillType.EvenOdd } }
    remember(viewSize, box, cornerPx) {
        scrimPath.reset()
        val hole = box
        if (hole != null && viewSize.width > 0 && viewSize.height > 0) {
            scrimPath.addRect(
                Rect(Offset.Zero, Size(viewSize.width.toFloat(), viewSize.height.toFloat()))
            )
            scrimPath.addRoundRect(
                RoundRect(hole, CornerRadius(cornerPx.coerceAtMost(minOf(hole.width, hole.height) / 2f)))
            )
        }
    }

    val trailBloomStroke = roundStroke(TrailBloomWidth)
    val trailCasingStroke = roundStroke(TrailCasingWidth)
    val trailCoreStroke = roundStroke(TrailCoreWidth)
    val edgeBloomStroke = roundStroke(EdgeBloomWidth)
    val edgeCoreStroke = roundStroke(EdgeCoreWidth)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { if (viewSize != it) viewSize = it }
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
                    // A tap without dragging is a word lookup once the index is ready, a
                    // dismissal otherwise; a drag is never a tap.
                    detectTapGestures { currentTap(it) }
                }
            }
    ) {
        // Bloom, dark casing and themed core for the live stroke.
        fun DrawScope.drawTrail(path: Path) {
            drawPath(path, primary.copy(alpha = TrailBloomAlpha), style = trailBloomStroke)
            drawPath(
                path,
                Color.Black.copy(alpha = TrailCasingAlpha),
                style = trailCasingStroke
            )
            val brush = trailBrush
            if (brush != null) {
                drawPath(path, brush, style = trailCoreStroke)
            } else {
                drawPath(path, primary, style = trailCoreStroke)
            }
        }

        val scrim = Color.Black.copy(alpha = ScrimAlpha)
        if (box == null) {
            drawRect(scrim)
        } else {
            // One even-odd fill: the whole canvas less the rounded hole at the selection, so the
            // dim frames a clear region with soft corners instead of four square ones.
            drawPath(scrimPath, scrim)
            val corner = CornerRadius(cornerPx.coerceAtMost(minOf(box.width, box.height) / 2f))
            // The selection's own edge: bloom under core, the same language as the trail.
            drawRoundRect(
                color = primary.copy(alpha = EdgeBloomAlpha * edgeEmphasis),
                topLeft = box.topLeft,
                size = box.size,
                cornerRadius = corner,
                style = edgeBloomStroke
            )
            drawRoundRect(
                color = primary.copy(alpha = edgeEmphasis),
                topLeft = box.topLeft,
                size = box.size,
                cornerRadius = corner,
                style = edgeCoreStroke
            )
        }
        // The user's own stroke while drawing. A single sample — the first event of a drag — is a
        // dot, so the gesture is answered by the first move rather than by the second.
        if (interactive && points.size == 1) {
            val center = points[0]
            drawCircle(
                Color.Black.copy(alpha = TrailCasingAlpha),
                trailCasingStroke.width / 2f,
                center
            )
            drawCircle(primary, trailCoreStroke.width / 2f, center)
        } else if (interactive && points.size > 1) {
            drawTrail(liveTrail)
        }
    }
}