/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
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
 * ML Kit only, deliberately: Tesseract reports no layout, so a screen index built from it would be
 * one line covering the whole bitmap — worth less than the region path the caller already has.
 */
object ScreenTextScanner {
    private const val TAG = "GatenOcr"

    /**
     * Recognises the whole [bitmap] and returns its index, lines in the order ML Kit reported them.
     *
     * Returns null — i.e. [ScanState.Unavailable] for the caller — when the engine preference is
     * [OcrEngine.TESSERACT], when recognition fails, or when the screen held no text at all. On
     * failure the region path still works, so nothing is logged as a crash. Never throws.
     */
    suspend fun scan(context: Context, bitmap: Bitmap): ScreenText? {
        if (OcrPreferences.engine(context) == OcrEngine.TESSERACT) return null

        val startedAt = SystemClock.elapsedRealtime()
        val lines = try {
            JapaneseOcr.mlKitResult(bitmap).textBlocks
                .flatMap { it.lines }
                .mapNotNull(::toScreenLine)
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

    /** One ML Kit line as a [ScreenLine], or null for a blank line or one the model gave no box for. */
    private fun toScreenLine(line: Text.Line): ScreenLine? {
        val text = line.text.trim()
        if (text.isEmpty()) return null
        val rect = line.boundingBox ?: return null
        val bounds = Box(rect.left, rect.top, rect.right, rect.bottom)
        val vertical = bounds.height > bounds.width * 1.5 && text.length > 1
        return ScreenLine(text, bounds, vertical, charBoxes(line, text, bounds, vertical))
    }

    /**
     * The per-character boxes of a line: the model's own symbol boxes when they line up with the
     * trimmed text, [interpolateCharBoxes] otherwise.
     *
     * Symbol boxes are used for lines that are not uniformly full-width, whenever the model exposes
     * them for every character of the line, one box each. A line whose leading whitespace the model
     * reported differently never lines up, so the interpolation is a normal outcome, not an error
     * path.
     */
    private fun charBoxes(line: Text.Line, text: String, bounds: Box, vertical: Boolean): List<Box> {
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
