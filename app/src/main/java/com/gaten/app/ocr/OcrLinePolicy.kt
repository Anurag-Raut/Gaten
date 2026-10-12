/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * The rules for reading a line with manga-ocr: which lines are worth reading, and when its reading
 * may replace what ML Kit found.
 *
 * ML Kit stays the detector — it finds the lines and their boxes — and manga-ocr reads them, because
 * it is the one that can handle stylized lettering. It is Japanese-trained and its post-processing
 * widens ASCII to full width, so a line of English or digits is left exactly as ML Kit read it:
 * there is nothing to gain and the text would come back wrong.
 *
 * Replacing is guarded on shape alone, never on the dictionary. A stylized line is precisely the
 * case where the dictionary has nothing to say, and judging the reading by coverage threw away the
 * text this engine exists to read. What is left are the three ways a decoder returns noise instead
 * of text: it said nothing, it said only a placeholder, or it ran on far past what the line's box
 * could hold.
 *
 * Pure: no Android, so every rule is unit-tested directly.
 */
object OcrLinePolicy {

    /** A line this short is read whatever script it is in: ML Kit had too little to go on. */
    private const val SHORT_LINE_CHARS = 2

    /** How much longer than ML Kit's reading a manga-ocr reading may be before it is noise. */
    private const val LENGTH_FACTOR = 3
    private const val LENGTH_SLACK = 4

    /** What a model writes for a glyph it could not read. A line of nothing else is not a reading. */
    private val PLACEHOLDERS = setOf("[UNK]", "[PAD]", "[CLS]", "[SEP]", "[MASK]", "□")

    /**
     * True when [text] should be read by manga-ocr: it holds at least one Japanese character, or it
     * is too short for ML Kit to have had anything to work with.
     */
    fun readWithMangaOcr(text: String): Boolean =
        text.any { isJapanese(it) } || text.length <= SHORT_LINE_CHARS

    /**
     * True when manga-ocr's reading of a line may replace ML Kit's [mlKitText]: it said something,
     * it is not just a placeholder, and it is not longer than the line's box could plausibly hold.
     */
    fun acceptsMangaText(mlKitText: String, mangaText: String): Boolean {
        if (isUnreadable(mangaText)) return false
        return mangaText.length <= mlKitText.length * LENGTH_FACTOR + LENGTH_SLACK
    }

    /** Blank text, or nothing but a placeholder: the model found nothing it could read. */
    fun isUnreadable(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed in PLACEHOLDERS) return true
        return trimmed.all { it == '□' }
    }

    /**
     * Kana, kanji, CJK punctuation and the full-width forms: the scripts manga-ocr is trained on.
     * The ideographic space (U+3000) is not one of them — a line of Latin text with a wide space in
     * it is still Latin.
     */
    private fun isJapanese(char: Char): Boolean {
        val code = char.code
        return code in 0x3001..0x30FF || // CJK punctuation, hiragana, katakana
            code in 0x3400..0x4DBF ||    // CJK unified ideographs extension A
            code in 0x4E00..0x9FFF ||    // CJK unified ideographs
            code in 0xF900..0xFAFF ||    // CJK compatibility ideographs
            code in 0xFF00..0xFFEF       // half- and full-width forms
    }
}
