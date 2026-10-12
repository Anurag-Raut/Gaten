/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

/**
 * A dictionary word found inside a scanned text.
 *
 * [surface] is the exact substring of the input, [baseForm] the dictionary form that matched (the
 * surface itself when it matched directly, a deinflected or kana-folded form otherwise). [context]
 * is the line the word was read from — the sentence a flashcard export wants to show next to the
 * word — and is empty until whoever knows the line fills it in.
 */
data class ScannedWord(
    val surface: String,
    val start: Int,
    val end: Int,
    val baseForm: String,
    val entries: List<DictEntry>,
    val context: String = ""
)

/**
 * Longest-match word identification over OCR output.
 *
 * OCR gives back a string, not words, so every character range is probed against the dictionary:
 * at each position the longest span that the dictionary knows wins, unmatched characters are
 * skipped one by one, and matched spans are never revisited. Only real dictionary entries are ever
 * emitted, which is what filters OCR garbage out. Pure: the dictionary is injected as a lambda.
 */
object WordScanner {
    /** Longest surface probed at one position. Covers compounds such as 国立大学法人 without scanning the whole line. */
    private const val MAX_MATCH_LENGTH = 12

    /**
     * Extra candidates [scanLine] asks for, so the caller's cap falls on the words inside the range
     * rather than on the whole line.
     */
    private const val RANGE_CANDIDATE_FACTOR = 4

    private const val KATAKANA_START = 0x30A1
    private const val KATAKANA_END = 0x30F6
    private const val HIRAGANA_START = 0x3041
    private const val HIRAGANA_END = 0x3096
    private const val KATAKANA_TO_HIRAGANA_OFFSET = 0x60

    /** CJK unified ideographs: the "complex" characters that should head the result list. */
    private const val KANJI_START = 0x4E00
    private const val KANJI_END = 0x9FFF

    /**
     * Scans [text] for dictionary words, most useful first.
     *
     * Results are ranked by complexity — kanji-bearing words first, then text order within the same
     * complexity — because a reader circling a line already knows the kana and wants the kanji
     * words. Surfaces that are a single kana character (が, を, ん) are dropped: they are particles
     * and auxiliaries that carry no lookup value. At most [maxWords] are returned.
     *
     * [lookup] is the dictionary accessor (usually `JmdictRepository::lookup`).
     */
    fun scan(text: String, lookup: (String) -> List<DictEntry>, maxWords: Int = 24): List<ScannedWord> {
        if (text.isEmpty() || maxWords <= 0) return emptyList()

        // Bounded candidate scan: ranking happens afterwards, so collect a few extra matches.
        val candidateLimit = maxWords * 4
        val words = ArrayList<ScannedWord>(minOf(maxWords, 8))
        var index = 0
        while (index < text.length && words.size < candidateLimit) {
            val word = matchAt(text, index, lookup)
            if (word == null || isLoneKana(word.surface)) {
                index++
            } else {
                words.add(word)
                index = word.end
            }
        }

        return words
            .sortedWith(
                compareByDescending<ScannedWord> { kanjiCount(it.surface) }
                    .thenBy { it.start }
            )
            .take(maxWords)
    }

    /**
     * Scans [text] from a character the reader actually pointed at, the way a tap is answered.
     *
     * The first word must start exactly at [startIndex] — that is the word under the finger. When
     * nothing in the dictionary starts there the scan steps forward to the first position that does,
     * so tapping a particle (を, が) still reaches the word behind it instead of returning nothing.
     * That first word is kept come what may, even a lone kana (the あ of あの): unlike in [scan],
     * where single kana are noise, here the reader chose it. Everything after it follows [scan]'s
     * rules — longest match, deinflection, single-kana surfaces dropped — and the list stays in text
     * order rather than being re-ranked kanji-first, because the tapped word is the answer and the
     * rest of the line is its context. At most [maxWords] are returned.
     */
    fun scanFrom(
        text: String,
        startIndex: Int,
        lookup: (String) -> List<DictEntry>,
        maxWords: Int = 24
    ): List<ScannedWord> {
        if (text.isEmpty() || maxWords <= 0 || startIndex >= text.length) return emptyList()

        var first: ScannedWord? = null
        var index = startIndex.coerceAtLeast(0)
        while (index < text.length) {
            val word = matchAt(text, index, lookup)
            if (word != null) {
                first = word
                break
            }
            index++
        }
        val tapped = first ?: return emptyList()

        val words = ArrayList<ScannedWord>(minOf(maxWords, 8))
        words.add(tapped)
        index = tapped.end
        while (index < text.length && words.size < maxWords) {
            val word = matchAt(text, index, lookup)
            if (word == null || isLoneKana(word.surface)) {
                index++
            } else {
                words.add(word)
                index = word.end
            }
        }
        return words
    }

    /**
     * Scans the whole of [text] and keeps the words a lasso's `[rangeStart, rangeEndExclusive)`
     * touches, one line's worth at a time.
     *
     * The line is scanned entire even though only part of it was circled, because that is what makes
     * the partial range readable: ます on its own is nothing, but seen behind 飲み it deinflects to
     * 飲む. Words whose span misses the range are then dropped, while a word straddling either edge
     * survives whole — half a word is of no use to a reader. [ScannedWord.start] and
     * [ScannedWord.end] keep indexing [text], and the ranking is [scan]'s: kanji-bearing words
     * first, text order inside each rank. At most [maxWords] are returned.
     */
    fun scanLine(
        text: String,
        rangeStart: Int,
        rangeEndExclusive: Int,
        lookup: (String) -> List<DictEntry>,
        maxWords: Int = 24
    ): List<ScannedWord> {
        if (text.isEmpty() || maxWords <= 0) return emptyList()

        return scan(text, lookup, maxWords * RANGE_CANDIDATE_FACTOR)
            .filter { it.start < rangeEndExclusive && rangeStart < it.end }
            .take(maxWords)
    }

    /** True for a single kana character: a particle or auxiliary, never a useful lookup. */
    private fun isLoneKana(surface: String): Boolean {
        if (surface.length != 1) return false
        val code = surface[0].code
        return code in HIRAGANA_START..HIRAGANA_END || code in KATAKANA_START..KATAKANA_END
    }

    /** Kanji count of [surface]; the primary ranking key. */
    private fun kanjiCount(surface: String): Int = surface.count { it.code in KANJI_START..KANJI_END }

    /**
     * The longest dictionary word starting at [start], or null when nothing at this position is in
     * the dictionary.
     *
     * Direct hits are preferred over deinflected ones and longer spans over shorter ones, so the
     * search runs two passes over every span length: first dictionary forms (including the
     * katakana-folded reading), then inflected readings.
     */
    private fun matchAt(text: String, start: Int, lookup: (String) -> List<DictEntry>): ScannedWord? {
        val maxLength = minOf(MAX_MATCH_LENGTH, text.length - start)

        for (length in maxLength downTo 1) {
            val surface = text.substring(start, start + length)
            val direct = lookup(surface)
            if (direct.isNotEmpty()) {
                return ScannedWord(surface, start, start + length, surface, direct)
            }
            val folded = foldKatakanaToHiragana(surface)
            if (folded != surface) {
                val foldedEntries = lookup(folded)
                if (foldedEntries.isNotEmpty()) {
                    return ScannedWord(surface, start, start + length, folded, foldedEntries)
                }
            }
        }

        for (length in maxLength downTo 1) {
            val surface = text.substring(start, start + length)
            val hit = lookupDeinflected(surface, lookup)
                ?: foldKatakanaToHiragana(surface)
                    .takeIf { it != surface }
                    ?.let { lookupDeinflected(it, lookup) }
            if (hit != null) {
                return ScannedWord(surface, start, start + length, hit.first, hit.second)
            }
        }

        return null
    }

    /**
     * The first deinflection candidate of [variant] the dictionary knows, together with its
     * entries, or null when no reading of [variant] is a dictionary word.
     */
    private fun lookupDeinflected(
        variant: String,
        lookup: (String) -> List<DictEntry>
    ): Pair<String, List<DictEntry>>? {
        for (candidate in Deinflector.candidates(variant)) {
            val entries = lookup(candidate)
            if (entries.isNotEmpty()) return candidate to entries
            val folded = foldKatakanaToHiragana(candidate)
            if (folded != candidate) {
                val foldedEntries = lookup(folded)
                if (foldedEntries.isNotEmpty()) return folded to foldedEntries
            }
        }
        return null
    }

    /**
     * Katakana folded to hiragana (コーヒー -> こーひー) so a katakana surface can still reach a
     * hiragana-only dictionary form. Returns [text] itself when it holds no katakana.
     */
    private fun foldKatakanaToHiragana(text: String): String {
        var hasKatakana = false
        for (char in text) {
            val code = char.code
            if (code in KATAKANA_START..KATAKANA_END) {
                hasKatakana = true
                break
            }
        }
        if (!hasKatakana) return text

        val folded = StringBuilder(text.length)
        for (char in text) {
            val code = char.code
            folded.append(
                if (code in KATAKANA_START..KATAKANA_END) {
                    (code - KATAKANA_TO_HIRAGANA_OFFSET).toChar()
                } else {
                    char
                }
            )
        }
        return folded.toString()
    }
}
