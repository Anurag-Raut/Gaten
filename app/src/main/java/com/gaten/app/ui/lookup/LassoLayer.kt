/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.graphics.Bitmap
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
 * The lasso trail is two solid passes over one smoothed path: an `onPrimary` keyline under a
 * `primary` core. The pair is the theme's own contrast couple — a deep accent keyed with white in
 * light mode, a pale one keyed with #111111 in dark — so neither pass has to sample the page under
 * it, and neither is translucent: the widths alone carry the hierarchy, whatever distance the
 * finger travelled.
 */
private val TrailOuterWidth = 5.dp
private val TrailInnerWidth = 3.dp

/** The selection edge: the same pair around the box, lighter once the answer is up. */
private val EdgeOuterWidth = 4.dp
private val EdgeInnerWidth = 2.dp
private val EdgeResultOuterWidth = 3.dp
private val EdgeResultInnerWidth = 1.dp

/** How far the clear region's corners are rounded out of the dim, in dp. */
private val SelectionCorner = 4.dp

/**
 * The dim over everything outside the selection. The veil is the one colour in the overlay that is
 * not a theme role: it dims somebody else's pixels, so it is plain black in both themes — no tint,
 * no accent wash, and nothing taken from the screenshot it covers.
 */
private const val ScrimAlpha = 0.40f

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
 * at the selection, so the clear region the reader chose is the frozen capture itself, unaltered —
 * then the selection's own edge and the live lasso trail only while the user is drawing. Drag and
 * tap gestures are active only while [interactive]; the result sheet sits on top and handles its own
 * touches afterwards.
 *
 * The edge changes weight once a result is up — [EdgeResultOuterWidth] and [EdgeResultInnerWidth] in
 * place of the drawing pair — so the region the answer came from reads as a marker instead of as the
 * subject, by weight rather than by fading over pixels the app cannot predict. The trail disappears
 * on finger lift.
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
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val currentStart by rememberUpdatedState(onLassoStart)
    val currentUpdate by rememberUpdatedState(onLassoUpdate)
    val currentEnd by rememberUpdatedState(onLassoEnd)
    val currentCancel by rememberUpdatedState(onLassoCancel)
    val currentTap by rememberUpdatedState(onTap)
    val liveBox = remember(points) { boundingBoxOf(points) }
    val box = frozenBox ?: liveBox

    // Measured once: the scrim's outer rectangle is built in composition from the canvas size,
    // never inside the draw scope.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

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

    val trailOuterStroke = roundStroke(TrailOuterWidth)
    val trailInnerStroke = roundStroke(TrailInnerWidth)
    // One weight while the reader draws — and while the crop they committed is being read — and the
    // lighter pair once there is an answer to look at.
    val edgeOuterStroke = roundStroke(if (showingResult) EdgeResultOuterWidth else EdgeOuterWidth)
    val edgeInnerStroke = roundStroke(if (showingResult) EdgeResultInnerWidth else EdgeInnerWidth)

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
                    // A tap looks up a word or offers a retry hint; a drag is never a tap.
                    detectTapGestures { currentTap(it) }
                }
            }
    ) {
        // The live stroke: the keyline first, the accent core over it, both fully opaque.
        fun DrawScope.drawTrail(path: Path) {
            drawPath(path, onPrimary, style = trailOuterStroke)
            drawPath(path, primary, style = trailInnerStroke)
        }

        val scrim = Color.Black.copy(alpha = ScrimAlpha)
        if (box == null) {
            drawRect(scrim)
        } else {
            // One even-odd fill: the whole canvas less the rounded hole at the selection, so the
            // frozen pixels the reader chose stay exactly as captured instead of sitting under a
            // second, softer surface of the app's own.
            drawPath(scrimPath, scrim)
            val corner = CornerRadius(cornerPx.coerceAtMost(minOf(box.width, box.height) / 2f))
            // The selection's own edge, the same two passes as the trail.
            drawRoundRect(
                color = onPrimary,
                topLeft = box.topLeft,
                size = box.size,
                cornerRadius = corner,
                style = edgeOuterStroke
            )
            drawRoundRect(
                color = primary,
                topLeft = box.topLeft,
                size = box.size,
                cornerRadius = corner,
                style = edgeInnerStroke
            )
        }
        // The user's own stroke while drawing. A single sample — the first event of a drag — is a
        // dot, so the gesture is answered by the first touch rather than by the second.
        if (interactive && points.size == 1) {
            val center = points[0]
            drawCircle(onPrimary, trailOuterStroke.width / 2f, center)
            drawCircle(primary, trailInnerStroke.width / 2f, center)
        } else if (interactive && points.size > 1) {
            drawTrail(liveTrail)
        }
    }
}
