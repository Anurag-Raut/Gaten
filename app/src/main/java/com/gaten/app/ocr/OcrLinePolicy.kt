/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * The rule for keeping what manga-ocr read for a line.
 *
 * ML Kit stays the detector — it finds the lines and their boxes — and manga-ocr reads every one of
 * them, because a stylized line is precisely the case ML Kit misreads and there is no way to tell
 * from its reading whether that happened. Replacing is therefore guarded on shape alone, never on
 * the dictionary: a stylized line is exactly the case where the dictionary has nothing to say, and
 * judging the reading by coverage threw away the text this engine exists to read. What is left are
 * the three ways a decoder returns noise instead of text: it said nothing, it said only a
 * placeholder, or it ran on far past what the line's box could hold.
 *
 * Pure: no Android, so every rule is unit-tested directly.
 */
object OcrLinePolicy {

    /** How much longer than ML Kit's reading a manga-ocr reading may be before it is noise. */
    private const val LENGTH_FACTOR = 3
    private const val LENGTH_SLACK = 4

    /**
     * Least number of characters a line's box is assumed to hold when ML Kit's reading is shorter
     * than this. A line ML Kit read as one or two Latin characters is a line it misread — the
     * stylized lettering manga-ocr is here for — so its length says nothing about how much text the
     * box holds, and using it as the estimate would refuse the very readings this engine exists to
     * produce.
     */
    private const val MIN_ESTIMATED_CHARS = 4

    /** What a model writes for a glyph it could not read. A line of nothing else is not a reading. */
    private val PLACEHOLDERS = setOf("[UNK]", "[PAD]", "[CLS]", "[SEP]", "[MASK]", "□")

    /**
     * True when manga-ocr's reading of a line may replace ML Kit's [mlKitText]: it said something,
     * it is not just a placeholder, and it is not longer than the line's box could plausibly hold.
     */
    fun acceptsMangaText(mlKitText: String, mangaText: String): Boolean {
        if (isUnreadable(mangaText)) return false
        val estimated = maxOf(mlKitText.length, MIN_ESTIMATED_CHARS)
        return mangaText.length <= estimated * LENGTH_FACTOR + LENGTH_SLACK
    }

    /** Blank text, or nothing but a placeholder: the model found nothing it could read. */
    fun isUnreadable(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed in PLACEHOLDERS) return true
        return trimmed.all { it == '□' }
    }
}
