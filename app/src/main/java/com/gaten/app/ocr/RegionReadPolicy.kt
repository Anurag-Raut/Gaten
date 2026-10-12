/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * What a circled region reads as.
 *
 * ML Kit is the detector: its pass over the region around the gesture finds the lines and their
 * boxes, and when it found Japanese in them those are the region's lines — manga-ocr reads them
 * ([JapaneseOcr.readLines]). A stylized line is exactly what ML Kit misreads, though, so a pass that
 * found no Japanese at all (or nothing) is not the end of it: the selection box the user drew goes
 * to manga-ocr itself ([JapaneseOcr.readLineWithMangaOcr]) and its reading is the region's one line,
 * with the selection box as its bounds.
 *
 * Only when that reading is missing — no model installed, or a failure — or unreadable is ML Kit's
 * own output kept, which may be nothing: the existing "no text" answer.
 *
 * Pure: no Android, so the order of the fallbacks is unit-tested directly.
 */
object RegionReadPolicy {

    /** True when ML Kit's region pass found a line with Japanese in it to read with manga-ocr. */
    fun hasJapaneseLines(lines: List<OcrLine>): Boolean =
        lines.any { JapaneseText.isJapaneseLine(it.text) }

    /**
     * The lines a region reads as when ML Kit found no Japanese in it: the selection box as one
     * line, read by manga-ocr, or ML Kit's own [mlKitLines] when that reading is missing or
     * unreadable.
     */
    fun fallbackLines(
        mlKitLines: List<OcrLine>,
        selection: Box,
        mangaReading: String?
    ): List<OcrLine> {
        val reading = mangaReading?.trim().orEmpty()
        return if (OcrLinePolicy.isUnreadable(reading)) {
            mlKitLines
        } else {
            listOf(OcrLine(reading, selection))
        }
    }
}
