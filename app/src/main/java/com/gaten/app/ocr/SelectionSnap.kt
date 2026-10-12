/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * Integer rectangle in bitmap pixels.
 *
 * Deliberately not `android.graphics.Rect`: the snapping geometry below is the part most likely to
 * be subtly wrong, and this way it stays unit-testable on the JVM.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centreX: Int get() = (left + right) / 2
    val centreY: Int get() = (top + bottom) / 2

    /** True when the two boxes share at least one pixel row and column. */
    fun overlaps(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Turns a hand-drawn selection into "the lines the user clearly meant".
 *
 * A lasso never frames text exactly. The tight bounding box of the trail drops whatever the loop
 * clipped, so 猫が水を飲む circled a character short came back as 猫が水を飲 and the verb vanished
 * from the panel entirely. Rather than trusting the box, the overlay recognises a generous region
 * around it, keeps the per-line bounds the OCR engine reports, and snaps to the lines the gesture
 * actually touched — so a near miss still yields the whole line the user was pointing at.
 *
 * Pure geometry; the decisions are unit-tested.
 */
object SelectionSnap {

    /** Left/right margin to recognise around a selection, in px. */
    fun horizontalMargin(selection: Box, bitmapWidth: Int): Int =
        (selection.width * HORIZONTAL_MARGIN_RATIO).toInt()
            .coerceIn(MIN_MARGIN_PX, (bitmapWidth / 3).coerceAtLeast(MIN_MARGIN_PX))

    /**
     * Top/bottom margin to recognise around a selection, in px. Kept well below one line pitch so
     * the neighbouring line stays out of the recognised region.
     */
    fun verticalMargin(selection: Box): Int =
        (selection.height * VERTICAL_MARGIN_RATIO).toInt().coerceIn(MIN_MARGIN_PX, MAX_VERTICAL_MARGIN_PX)

    /** [selection] grown by the given margins and clamped to the bitmap. */
    fun searchRegion(
        selection: Box,
        marginX: Int,
        marginY: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): Box = Box(
        (selection.left - marginX).coerceAtLeast(0),
        (selection.top - marginY).coerceAtLeast(0),
        (selection.right + marginX).coerceAtMost(bitmapWidth),
        (selection.bottom + marginY).coerceAtMost(bitmapHeight)
    )

    /** Moves line bounds from region-local coordinates into bitmap coordinates. */
    fun translate(lines: List<OcrLine>, dx: Int, dy: Int): List<OcrLine> = lines.map { line ->
        val b = line.bounds
        OcrLine(line.text, Box(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy))
    }

    /**
     * The lines a selection points at: every line it overlaps, and nothing else.
     *
     * There is no nearest-line fallback on purpose: this is the circle path, where the answer must
     * be only what was circled. When nothing overlaps — nothing recognised in the region, or a
     * gesture between lines — the result is empty, and the caller answers with the box the user
     * drew ([RegionReadPolicy.fallbackLines]) instead of text from somewhere else on the screen.
     */
    fun selectLines(lines: List<OcrLine>, selection: Box): List<OcrLine> =
        lines.filter { it.bounds.overlaps(selection) }

    private const val HORIZONTAL_MARGIN_RATIO = 0.5f
    private const val VERTICAL_MARGIN_RATIO = 0.35f
    private const val MIN_MARGIN_PX = 16
    private const val MAX_VERTICAL_MARGIN_PX = 96
}
