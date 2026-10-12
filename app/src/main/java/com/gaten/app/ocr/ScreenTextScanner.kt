/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.CancellationException

/**
 * One OCR pass over the whole frozen screenshot, indexing every line it can address.
 *
 * The lookup path recognises a small region around the gesture — fast, but it leaves the screen
 * unreadable until the reader has already drawn something. This pass runs once instead and keeps
 * every line, with a box per character, so a tap or a lasso can be answered from the index
 * ([ScreenHits]) with no further recognition.
 *
 * ML Kit only, deliberately: it is the detector, and it reports the layout the index is made of.
 *
 * Every line ML Kit read is kept, whatever script it is in — a line it read as Latin can still be
 * stylized Japanese, which is what manga-ocr is for — and each one's box is trimmed onto its ink
 * with [InkProjection] so a tap lands on the glyphs, not on ML Kit's padding.
 *
 * This is the detector, not the reader: what it returns is ML Kit's reading of each line, and it is
 * what the overlay goes ready with. [ScreenLineReader] then replaces the text of the lines manga-ocr
 * reads, leaving these boxes — which every gesture is hit-tested against — where they are, and drops
 * the lines whose final text turns out to have no Japanese in it.
 */
object ScreenTextScanner {
    private const val TAG = "GatenOcr"

    /**
     * Recognises the whole [bitmap] and returns its index, lines in the order ML Kit reported them.
     *
     * Returns null — i.e. [ScanState.Unavailable] for the caller — when recognition fails, or when
     * ML Kit read no line at all. On failure the region path still works, so nothing is logged as a
     * crash. Never throws.
     */
    suspend fun scan(bitmap: Bitmap): ScreenText? {
        val startedAt = SystemClock.elapsedRealtime()
        val lines = try {
            JapaneseOcr.mlKitResult(bitmap).textBlocks
                .flatMap { it.lines }
                .mapNotNull { toScreenLine(bitmap, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Full-screen OCR failed", e)
            return null
        }
        if (lines.isEmpty()) return null

        Log.d(TAG, "Indexed ${lines.size} lines in ${SystemClock.elapsedRealtime() - startedAt} ms")
        return ScreenText(lines, bitmap.width, bitmap.height)
    }

    /** Margin, in px, the line is cropped with before the ink profile is read. */
    private const val TIGHTEN_MARGIN = 2

    /** A crop of [bitmap] to profile: where it starts and how big it is. */
    private class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * One ML Kit line as a [ScreenLine], or null when the line is blank or boxless.
     *
     * A line whose text has no Japanese in it is kept: manga-ocr may still read Japanese out of the
     * glyphs ML Kit saw as Latin, and [ScreenLineReader] drops the line if it does not.
     *
     * The model's box is loose, so the line is cropped (a small margin) and run through
     * [InkProjection]: the ink trims the box, and for a full-width line the character cells are cut
     * from the tightened span instead of an even split of the loose box. A tightening that comes
     * back under half the line's length is a threshold failure, not text, so ML Kit's own box is
     * kept.
     */
    private fun toScreenLine(bitmap: Bitmap, line: Text.Line): ScreenLine? {
        val text = line.text.trim()
        if (text.isEmpty()) return null
        val rect = line.boundingBox ?: return null
        val mlKit = Box(rect.left, rect.top, rect.right, rect.bottom)
        val vertical = mlKit.height > mlKit.width * 1.5 && text.length > 1

        val crop = cropFor(bitmap, mlKit)
            ?: return ScreenLine(text, mlKit, vertical, fallbackCharBoxes(line, text, mlKit, vertical))

        val gray = luminance(bitmap, crop)
        val span = InkProjection.tighten(gray, crop.width, crop.height, vertical)
        val originalMain = if (vertical) mlKit.height else mlKit.width
        if (span == null || (span.mainEnd - span.mainStart) * 2 < originalMain) {
            return ScreenLine(text, mlKit, vertical, fallbackCharBoxes(line, text, mlKit, vertical))
        }

        val bounds = tightenedBounds(span, crop.left, crop.top, mlKit, vertical)
        if (bounds.width <= 0 || bounds.height <= 0) {
            return ScreenLine(text, mlKit, vertical, fallbackCharBoxes(line, text, mlKit, vertical))
        }
        val boxes = if (isFullWidthText(text)) {
            gridCharBoxes(gray, crop, span, text, vertical, bounds)
        } else {
            fallbackCharBoxes(line, text, bounds, vertical)
        }
        return ScreenLine(text, bounds, vertical, boxes)
    }

    /** The line's [bounds] grown by [TIGHTEN_MARGIN] px and clamped to [bitmap]'s edges, or null. */
    private fun cropFor(bitmap: Bitmap, bounds: Box): Crop? {
        val left = (bounds.left - TIGHTEN_MARGIN).coerceAtLeast(0)
        val top = (bounds.top - TIGHTEN_MARGIN).coerceAtLeast(0)
        val right = (bounds.right + TIGHTEN_MARGIN).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom + TIGHTEN_MARGIN).coerceAtMost(bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return Crop(left, top, width, height)
    }

    /** The crop as Rec. 601 luma (`0..255`): one [Bitmap.getPixels] for the whole line. */
    private fun luminance(bitmap: Bitmap, crop: Crop): IntArray {
        val pixels = IntArray(crop.width * crop.height)
        bitmap.getPixels(pixels, 0, crop.width, crop.left, crop.top, crop.width, crop.height)
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
        }
        return gray
    }

    /** [span] in bitmap coordinates, clamped so it can never reach past ML Kit's own [mlKit] box. */
    private fun tightenedBounds(
        span: InkProjection.Tightened,
        cropLeft: Int,
        cropTop: Int,
        mlKit: Box,
        vertical: Boolean
    ): Box {
        val left = (cropLeft + if (vertical) span.crossStart else span.mainStart).coerceIn(mlKit.left, mlKit.right)
        val right = (cropLeft + if (vertical) span.crossEnd else span.mainEnd).coerceIn(mlKit.left, mlKit.right)
        val top = (cropTop + if (vertical) span.mainStart else span.crossStart).coerceIn(mlKit.top, mlKit.bottom)
        val bottom = (cropTop + if (vertical) span.mainEnd else span.crossEnd).coerceIn(mlKit.top, mlKit.bottom)
        return Box(left, top, right, bottom)
    }

    /**
     * [text]'s per-character boxes on the tightened [bounds], one cell per character of the
     * full-width grid. The cells come from [InkProjection.charCells] over the crop's ink; when the
     * span cannot be cut (too few pixels per character) the even split of [bounds] is used, so
     * `charBoxes.size == text.length` always holds.
     */
    private fun gridCharBoxes(
        gray: IntArray,
        crop: Crop,
        span: InkProjection.Tightened,
        text: String,
        vertical: Boolean,
        bounds: Box
    ): List<Box> {
        val cells = InkProjection.charCells(gray, crop.width, crop.height, vertical, span, text.length)
            ?: return interpolateCharBoxes(text, bounds, vertical)
        val crossLow: Int
        val crossHigh: Int
        val mainOffset: Int
        if (vertical) {
            crossLow = (crop.left + span.crossStart).coerceIn(bounds.left, bounds.right)
            crossHigh = (crop.left + span.crossEnd).coerceIn(bounds.left, bounds.right)
            mainOffset = crop.top
        } else {
            crossLow = (crop.top + span.crossStart).coerceIn(bounds.top, bounds.bottom)
            crossHigh = (crop.top + span.crossEnd).coerceIn(bounds.top, bounds.bottom)
            mainOffset = crop.left
        }
        val mainLow = if (vertical) bounds.top else bounds.left
        val mainHigh = if (vertical) bounds.bottom else bounds.right
        return cells.map { cell ->
            val start = (mainOffset + cell.first).coerceIn(mainLow, mainHigh)
            val end = (mainOffset + cell.last + 1).coerceIn(mainLow, mainHigh)
            if (vertical) Box(crossLow, start, crossHigh, end) else Box(start, crossLow, end, crossHigh)
        }
    }

    /**
     * The per-character boxes of a line the ink pass could not improve: the model's own symbol boxes
     * when they line up with the trimmed text, [interpolateCharBoxes] otherwise.
     *
     * Symbol boxes are used for lines that are not uniformly full-width, whenever the model exposes
     * them for every character of the line, one box each. A line whose leading whitespace the model
     * reported differently never lines up, so the interpolation is a normal outcome, not an error
     * path.
     */
    private fun fallbackCharBoxes(line: Text.Line, text: String, bounds: Box, vertical: Boolean): List<Box> {
        // Full-width text sits on an even grid, and the model's own symbol boxes drift off it (on
        // a measured line they ran half a character past the glyphs by the end), so an even split
        // of the line box is the more accurate of the two. Symbol boxes are kept for mixed lines,
        // where Latin and digits make the widths uneven.
        if (isFullWidthText(text)) return interpolateCharBoxes(text, bounds, vertical)

        val symbols = line.elements.flatMap { it.symbols }
        val rects = symbols.mapNotNull { it.boundingBox }
        if (rects.size == text.length && symbols.joinToString("") { it.text } == text) {
            return rects.map { Box(it.left, it.top, it.right, it.bottom) }
        }
        return interpolateCharBoxes(text, bounds, vertical)
    }
}
