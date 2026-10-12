/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * Which of ML Kit's lines are worth indexing on the screen overlay.
 *
 * The full-screen pass runs on the whole screenshot, so every status-bar clock, icon label, Latin
 * button, arrow glyph and stray mark the model hallucinated comes back as a "line" too. A box per
 * character is only useful where there is Japanese to address, so everything else is dropped before
 * it ever reaches the tap/lasso index.
 *
 * A line is kept when it holds at least one kana or CJK ideograph — a mixed line like
 * `Lv.5 勇者` still has a character worth reading, and only wholly non-Japanese lines (Latin,
 * digits, symbols, emoji) are thrown away.
 *
 * Pure, so the rule is unit-tested directly.
 */
object JapaneseText {

    /**
     * Stroke-like characters that are also single "lines" the UI draws: a long vowel bar, an
     * arrow-ish mark, a thick pipe. A one-character line made of one of these is almost always the
     * model reading a divider or an icon, not text — 一, ー and ノ alone are not worth a box.
     */
    private val LONE_STROKE_CHARS = setOf('ー', '一', '丨', 'ノ', '・')

    /** True when [text] holds Japanese worth indexing as a line and is not a lone UI stroke. */
    fun isJapaneseLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.length == 1 && trimmed[0] in LONE_STROKE_CHARS) return false
        return trimmed.any { isJapanese(it) }
    }

    /**
     * Hiragana, katakana (full- and half-width, plus the phonetic extensions) and CJK ideographs,
     * with the two iteration marks the ranges miss (々 U+3005, 〆 U+3006). CJK punctuation and the
     * full-width Latin block are deliberately left out: on their own they mark a UI line, not text.
     */
    private fun isJapanese(char: Char): Boolean {
        val code = char.code
        return code in 0x3040..0x309F || // hiragana
            code in 0x30A0..0x30FF ||     // katakana (incl. ー, ・, ノ)
            code in 0x31F0..0x31FF ||     // katakana phonetic extensions
            code in 0xFF66..0xFF9D ||     // half-width katakana
            code in 0x4E00..0x9FFF ||     // CJK unified ideographs
            code in 0x3400..0x4DBF ||     // CJK unified ideographs extension A
            code == 0x3005 ||             // 々 iteration mark
            code == 0x3006                // 〆
    }
}
