/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The geometry of the meiki text detector, and the rule that folds its boxes into ML Kit's.
 *
 * meiki (`meiki.text.detect.v0`, [MeikiDetector]) is a second opinion on *where* the text lines are:
 * it was trained on the hand-lettered and outlined lettering ML Kit's line detector misses entirely.
 * It is not a reader, and it is not trusted as one — this file's only job is to say which of its
 * boxes are lines ML Kit did not already find, so the caller can recognise those too without
 * recognising anything twice.
 *
 * Everything here is pure Kotlin on the plain [Box] rectangle, so the overlap arithmetic — the part
 * most likely to be subtly wrong — is unit-tested on the JVM.
 */
object DetectionMerge {

    /** Input side the detector takes. */
    const val MODEL_SIZE = 640

    /** Boxes from the model below this confidence are dropped. */
    const val SCORE_THRESHOLD = 0.4f

    /** Fraction of a tile's side two adjacent tiles share, so a line on a seam is not cut in half. */
    const val TILE_OVERLAP = 0.2f

    /** Same line seen in two tiles: IoU above this means the two boxes are one detection. */
    const val CROSS_TILE_IOU = 0.5f

    /** Merge: a meiki box overlapping an ML Kit box this much is the same line, not a new one. */
    const val MERGE_IOU = 0.3f

    /** Merge: this much of one box inside the other is the same line even when IoU is small. */
    const val MERGE_CONTAINMENT = 0.6f

    /** A square region of the source bitmap the detector is run on. */
    data class Tile(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** How the source bitmap was fitted into the model's square input. */
    data class Letterbox(val size: Int, val ratio: Float, val padW: Int, val padH: Int)

    /** A detector box with the confidence it came with. */
    data class ScoredBox(val box: Box, val score: Float)

    /** The boxes [merge] added beyond ML Kit's, and ML Kit's own boxes followed by them. */
    data class MergeResult(val added: List<Box>, val all: List<Box>)

    /**
     * The square tiles [MeikiDetector] runs, sized to the shorter side of a [width] x [height]
     * bitmap and stepping along the longer one with [TILE_OVERLAP] shared between neighbours.
     *
     * A screen taller than it is wide — or wider than it is tall — would otherwise be squeezed into
     * the model's 640 px input until a text line is a few pixels high. Tiling keeps every run at the
     * model's native scale. A bitmap already no longer than its shorter side is one tile.
     */
    fun planTiles(width: Int, height: Int): List<Tile> {
        val side = min(width, height).coerceAtLeast(1)
        val step = max(1, (side * (1f - TILE_OVERLAP)).toInt())
        val xOffsets = if (width >= height) offsets(width, side, step) else listOf(0)
        val yOffsets = if (width >= height) listOf(0) else offsets(height, side, step)
        return yOffsets.flatMap { y -> xOffsets.map { x -> Tile(x, y, x + side, y + side) } }
    }

    /** Tile origins from 0 to `extent - side`, one per [step] and the last flush with the far edge. */
    private fun offsets(extent: Int, side: Int, step: Int): List<Int> {
        if (extent <= side) return listOf(0)
        val last = extent - side
        val result = mutableListOf(0)
        var next = step
        while (result.last() < last) {
            result.add(min(next, last))
            next += step
        }
        return result
    }

    /** Moves a box from tile-local coordinates into bitmap coordinates. */
    fun tileToBitmap(box: Box, tile: Tile): Box = Box(
        box.left + tile.left,
        box.top + tile.top,
        box.right + tile.left,
        box.bottom + tile.top
    )

    /**
     * The letterbox the model's input is built with: [width] x [height] scaled by the ratio that
     * fits it inside [size], centred on a black square. Matches the reference script's
     * `resize_and_pad` (integer division for the padding, `int()` truncation for the resize).
     */
    fun letterbox(width: Int, height: Int, size: Int = MODEL_SIZE): Letterbox {
        val ratio = min(size.toFloat() / width, size.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Letterbox(size, ratio, (size - newWidth) / 2, (size - newHeight) / 2)
    }

    /** A bitmap x inside the letterboxed input. */
    fun toModelX(letterbox: Letterbox, x: Int): Float = x * letterbox.ratio + letterbox.padW

    /** A bitmap y inside the letterboxed input. */
    fun toModelY(letterbox: Letterbox, y: Int): Float = y * letterbox.ratio + letterbox.padH

    /** The model-space box of a tile-local box, still in the 640 px padded coordinate space. */
    fun bitmapToModel(box: Box, letterbox: Letterbox): Box = Box(
        toModelX(letterbox, box.left).roundToInt(),
        toModelY(letterbox, box.top).roundToInt(),
        toModelX(letterbox, box.right).roundToInt(),
        toModelY(letterbox, box.bottom).roundToInt()
    )

    /** A model-space box back in tile-local bitmap coordinates. The inverse of [bitmapToModel]. */
    fun modelToBitmap(box: Box, letterbox: Letterbox): Box = Box(
        inverse(box.left, letterbox.padW, letterbox.ratio),
        inverse(box.top, letterbox.padH, letterbox.ratio),
        inverse(box.right, letterbox.padW, letterbox.ratio),
        inverse(box.bottom, letterbox.padH, letterbox.ratio)
    )

    private fun inverse(coord: Int, pad: Int, ratio: Float): Int = ((coord - pad) / ratio).roundToInt()

    /** Area of the intersection of [a] and [b], 0 when they do not overlap. */
    fun intersectionArea(a: Box, b: Box): Long {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return 0L
        return w.toLong() * h.toLong()
    }

    /** Intersection over union of [a] and [b], 0 when either is empty or they do not touch. */
    fun iou(a: Box, b: Box): Float {
        val inter = intersectionArea(a, b)
        if (inter == 0L) return 0f
        val union = a.area() + b.area() - inter
        if (union <= 0L) return 0f
        return inter.toFloat() / union.toFloat()
    }

    /** Fraction of [a]'s area that lies inside [b], 0 when [a] is empty. */
    fun fractionInside(a: Box, b: Box): Float {
        val area = a.area()
        if (area <= 0L) return 0f
        return intersectionArea(a, b).toFloat() / area.toFloat()
    }

    /**
     * True when [meiki] and [mlkit] are the same line: their boxes overlap enough by IoU, or one is
     * mostly inside the other even though a size difference keeps IoU low.
     */
    fun sameLine(meiki: Box, mlkit: Box): Boolean =
        iou(meiki, mlkit) > MERGE_IOU ||
            fractionInside(meiki, mlkit) > MERGE_CONTAINMENT ||
            fractionInside(mlkit, meiki) > MERGE_CONTAINMENT

    /**
     * Greedy non-maximum suppression over [boxes] by [iouThreshold], keeping the highest-scoring box
     * of each overlapping cluster. Used to fold the detections the tiles share back into one box per
     * line.
     */
    fun nms(boxes: List<ScoredBox>, iouThreshold: Float = CROSS_TILE_IOU): List<ScoredBox> {
        val kept = mutableListOf<ScoredBox>()
        for (candidate in boxes.sortedByDescending { it.score }) {
            if (kept.none { iou(it.box, candidate.box) > iouThreshold }) kept.add(candidate)
        }
        return kept
    }

    /**
     * Every ML Kit box is kept; a meiki box is added only when it names a line ML Kit did not find.
     * The order of the result is ML Kit's boxes first, then the added ones.
     */
    fun merge(mlKit: List<Box>, meiki: List<Box>): MergeResult {
        val added = meiki.filter { candidate -> mlKit.none { sameLine(candidate, it) } }
        return MergeResult(added, mlKit + added)
    }

    private fun Box.area(): Long =
        if (right > left && bottom > top) (right - left).toLong() * (bottom - top).toLong() else 0L
}