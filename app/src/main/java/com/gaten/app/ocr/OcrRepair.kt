/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.WordScanner

/**
 * Repairs the character confusions OCR engines make on Japanese text, before the text reaches the
 * dictionary.
 *
 * Both engines the app ships split thin CJK strokes and read katakana as the kanji it was drawn
 * from: コーヒー comes back as コーヒ一, カード as カ一ド, ロケット as 口ケット, and 猫 as 描 on a
 * phone screen (docs/jp-prototype/REPORT.md). The repairs run in two passes:
 *
 * 1. **Script context**, no dictionary needed. A lookalike of the prolonged sound mark (一 － ｰ ―
 *    —) that follows katakana becomes ー, and a katakana-shaped kanji (力 口 工 夕 二 八 卜) that
 *    sits in a katakana run of at least three characters becomes that katakana. Both need the
 *    neighbours to agree, which is what saves the 一 of 一人 and the 口 of 入口: with kanji or
 *    hiragana around them they are real characters, not broken ー and ロ.
 * 2. **Dictionary checked**. What is left is substituted only where the dictionary then covers
 *    strictly more of the surrounding window, so the pass cannot invent text: a reading the
 *    dictionary does not know is rejected, and so is one that buys nothing — 週末 and 週未 are both
 *    real words, and a coin toss between them is worse than leaving the line as recognised.
 *
 * Every replacement is exactly one character for one character, so `result.length == text.length`
 * always holds and the per-character boxes the OCR engine reported stay aligned with the text.
 * Pure: no Android dependency, the dictionary arrives as a lambda. Never throws.
 */
object OcrRepair {

    /**
     * Returns [text] with confusable characters repaired. Every replacement is exactly 1 char -> 1
     * char, so `result.length == text.length` always holds and per-character boxes stay aligned.
     * Never throws.
     */
    fun repair(text: String, lookup: (String) -> List<DictEntry>): String {
        val repaired = scriptRepair(text)
        // Nothing in the table is in the line, so no position can be repaired by the dictionary.
        if (!containsConfusable(repaired)) return repaired

        val budget = LookupBudget(lookup, MAX_LOOKUPS)
        val covered = coveredPositions(repaired, budget)
        var result: StringBuilder? = null
        for (position in candidatePositions(repaired, covered)) {
            val replacement = dictionaryReplacement(repaired, covered, position, budget) ?: continue
            val builder = result ?: StringBuilder(repaired)
            builder.setCharAt(position, replacement)
            result = builder
        }
        return result?.toString() ?: repaired
    }

    /** True when [text] holds a character the table knows a lookalike of. */
    private fun containsConfusable(text: String): Boolean = text.any { it in CONFUSABLES }

    /**
     * The dictionary-free pass, exposed for callers that must produce text without a dictionary
     * handle — the sentence a gesture selected ([com.gaten.app.ocr.selectionText]). Every position
     * is judged on the characters of [text] alone, so a repair never confirms the next one and the
     * result does not depend on the order of the repairs. Returns [text] itself when nothing
     * changed. 1 char -> 1 char, so offsets into the result stay valid.
     */
    internal fun scriptRepair(text: String): String {
        var repaired: StringBuilder? = null
        for (position in text.indices) {
            val replacement = scriptReplacement(text, position) ?: continue
            val builder = repaired ?: StringBuilder(text)
            builder.setCharAt(position, replacement)
            repaired = builder
        }
        return repaired?.toString() ?: text
    }

    /** The script-context repair of the character at [position], or null when its neighbours do not call for one. */
    private fun scriptReplacement(text: String, position: Int): Char? {
        val char = text[position]
        if (char in PROLONGED_LOOKALIKES) return prolongedMarkReplacement(text, position)
        val katakana = KATAKANA_LOOKALIKES[char]
        if (katakana != null) return katakanaRunReplacement(text, position, katakana)
        for ((katakanaKana, hiraganaKana) in KANA_SCRIPT_PAIRS) {
            if (char == katakanaKana || char == hiraganaKana) {
                return kanaScriptReplacement(text, position, katakanaKana, hiraganaKana)
            }
        }
        return null
    }

    /**
     * The prolonged sound mark a lookalike stands for, or null when it is not standing in for one:
     * the 一 of コーヒ一 follows katakana, the 一 of 一人 starts the line.
     */
    private fun prolongedMarkReplacement(text: String, position: Int): Char? {
        if (position == 0 || !isKatakana(text[position - 1])) return null
        val next = position + 1
        return if (next == text.length || isKatakana(text[next])) PROLONGED_SOUND_MARK else null
    }

    /**
     * The katakana a kanji lookalike stands for, or null when the character it sits in is not a
     * katakana word: the 口 of 口ケット is a broken ロ, the 口 of 入口 is a real kanji.
     */
    private fun katakanaRunReplacement(text: String, position: Int, katakana: Char): Char? {
        val left = position - 1
        val right = position + 1
        if (left >= 0 && !isKatakanaRun(text[left])) return null
        if (right < text.length && !isKatakanaRun(text[right])) return null

        var run = 1
        var index = left
        while (index >= 0 && isKatakanaRun(text[index])) {
            run++
            index--
        }
        index = right
        while (index < text.length && isKatakanaRun(text[index])) {
            run++
            index++
        }
        return if (run >= KATAKANA_RUN_MIN) katakana else null
    }

    /**
     * The same kana in the script of its surroundings: the べ of テーべル belongs to ベ, the ベ of
     * たベる to べ. Null when the two neighbours disagree or one of them is not kana at all, and when
     * there is no neighbour to follow — ー counts for neither script, since it carries none of its
     * own.
     */
    private fun kanaScriptReplacement(
        text: String,
        position: Int,
        katakana: Char,
        hiragana: Char
    ): Char? {
        val left = scriptOf(text, position - 1)
        val right = scriptOf(text, position + 1)
        if (left == SCRIPT_OTHER || right == SCRIPT_OTHER) return null
        val katakanaNeighbour = left == SCRIPT_KATAKANA || right == SCRIPT_KATAKANA
        val hiraganaNeighbour = left == SCRIPT_HIRAGANA || right == SCRIPT_HIRAGANA
        if (katakanaNeighbour == hiraganaNeighbour) return null
        return if (katakanaNeighbour) katakana else hiragana
    }

    /**
     * The script of the character at [position]: [SCRIPT_NONE] outside the text and for ー, which
     * belongs to both scripts, [SCRIPT_OTHER] for anything that is not kana (kanji, latin, digits).
     */
    private fun scriptOf(text: String, position: Int): Int {
        if (position < 0 || position >= text.length) return SCRIPT_NONE
        val char = text[position]
        return when {
            char == PROLONGED_SOUND_MARK -> SCRIPT_NONE
            char.code in HIRAGANA_START..HIRAGANA_END -> SCRIPT_HIRAGANA
            char.code in KATAKANA_START..KATAKANA_END -> SCRIPT_KATAKANA
            else -> SCRIPT_OTHER
        }
    }

    /**
     * The character ranges [WordScanner] matched, as a per-character flag. This is where the
     * candidate search starts: a character the dictionary has no word for is where a misread hides.
     */
    private fun coveredPositions(text: String, lookup: (String) -> List<DictEntry>): BooleanArray {
        val covered = BooleanArray(text.length)
        for (word in WordScanner.scan(text, lookup, MAX_WORDS)) {
            for (position in word.start until word.end) covered[position] = true
        }
        return covered
    }

    /**
     * The positions the dictionary pass may touch: confusable characters inside a run of text the
     * dictionary does not know. At most [MAX_CANDIDATES] of them, in text order, so one line costs
     * a bounded amount of work however mangled it is.
     */
    private fun candidatePositions(text: String, covered: BooleanArray): List<Int> {
        val candidates = ArrayList<Int>(MAX_CANDIDATES)
        var position = 0
        while (position < text.length && candidates.size < MAX_CANDIDATES) {
            if (covered[position]) {
                position++
                continue
            }
            while (position < text.length && !covered[position]) {
                if (candidates.size < MAX_CANDIDATES && text[position] in CONFUSABLES) {
                    candidates.add(position)
                }
                position++
            }
        }
        return candidates
    }

    /**
     * The alternative for the character at [position] that makes the dictionary cover more of the
     * window around it, or null when none does. The window is ±[WINDOW] characters and the
     * comparison is against the covering [coveredPositions] already measured, so a substitution that
     * does not add a single dictionary character is a guess — and guesses are never taken. Of the
     * alternatives that do add something the one with the longest covered match wins; two that tie
     * leave the original character alone.
     */
    private fun dictionaryReplacement(
        text: String,
        covered: BooleanArray,
        position: Int,
        lookup: (String) -> List<DictEntry>
    ): Char? {
        val start = maxOf(0, position - WINDOW)
        val end = minOf(text.length, position + WINDOW + 1)
        var before = 0
        for (index in start until end) {
            if (covered[index]) before++
        }

        var best: Char? = null
        var bestCoverage = 0
        var bestLongest = 0
        var ambiguous = false
        for (alternative in CONFUSABLES.getValue(text[position])) {
            val after = coverage(windowWith(text, start, end, position, alternative), lookup)
            if (after.characters <= before) continue
            when {
                best == null || after.longest > bestLongest -> {
                    best = alternative
                    bestCoverage = after.characters
                    bestLongest = after.longest
                    ambiguous = false
                }

                after.longest == bestLongest && after.characters > bestCoverage -> {
                    best = alternative
                    bestCoverage = after.characters
                    ambiguous = false
                }

                after.longest == bestLongest -> ambiguous = true
            }
        }
        return if (ambiguous) null else best
    }

    /**
     * What the dictionary knows about [text]: how many of its characters sit inside a matched word,
     * and the length of the longest such word. Read off [WordScanner.scan], the same longest-match
     * scan the lookup panel uses, so the two agree on what a word is.
     */
    private fun coverage(text: String, lookup: (String) -> List<DictEntry>): Coverage {
        var characters = 0
        var longest = 0
        for (word in WordScanner.scan(text, lookup, MAX_WORDS)) {
            val length = word.end - word.start
            characters += length
            if (length > longest) longest = length
        }
        return Coverage(characters, longest)
    }

    /** [text] between [start] and [end] with [position] replaced by [alternative]. */
    private fun windowWith(
        text: String,
        start: Int,
        end: Int,
        position: Int,
        alternative: Char
    ): String = StringBuilder(end - start)
        .append(text, start, position)
        .append(alternative)
        .append(text, position + 1, end)
        .toString()

    /** How much of a window the dictionary knows: characters covered, and the longest single word. */
    private data class Coverage(val characters: Int, val longest: Int)

    /**
     * [lookup] with a hard call limit. Once [MAX_LOOKUPS] lookups are spent the dictionary answers
     * "nothing", which makes every later comparison see no coverage and therefore reject the
     * substitution: a line the pass runs out of budget on comes back repaired less, never invented.
     */
    private class LookupBudget(
        private val lookup: (String) -> List<DictEntry>,
        private val limit: Int
    ) : (String) -> List<DictEntry> {
        private var spent = 0

        override fun invoke(term: String): List<DictEntry> {
            if (spent >= limit) return emptyList()
            spent++
            return lookup(term)
        }
    }

    /** Katakana, or the prolonged sound mark, which is the script a mangled katakana word is written in. */
    private fun isKatakana(char: Char): Boolean =
        char == PROLONGED_SOUND_MARK || char.code in KATAKANA_START..KATAKANA_END

    /**
     * Katakana, the prolonged sound mark, or a lookalike of it. A 一 in an otherwise katakana run is
     * a broken ー rather than the kanji for "one", so it keeps the run going for its neighbours.
     */
    private fun isKatakanaRun(char: Char): Boolean = isKatakana(char) || char in PROLONGED_LOOKALIKES

    /** Lookalikes of ー: the kanji for "one", a fullwidth and a halfwidth hyphen, and the two dashes. */
    private val PROLONGED_LOOKALIKES = setOf('一', '－', 'ｰ', '―', '—')

    /** Kanji a katakana font is read as, mapped to the katakana they stand for. */
    private val KATAKANA_LOOKALIKES = mapOf(
        '力' to 'カ',
        '口' to 'ロ',
        '工' to 'エ',
        '夕' to 'タ',
        '二' to 'ニ',
        '八' to 'ハ',
        '卜' to 'ト'
    )

    /** Kana pairs that differ only by their script: katakana form to hiragana form. */
    private val KANA_SCRIPT_PAIRS = listOf('ヘ' to 'へ', 'ベ' to 'べ', 'ペ' to 'ぺ')

    /**
     * The confusable pairs, each used in both directions. The first ten come from the shapes
     * themselves: ー/一 (identical in most fonts), the katakana a kanji is drawn from (カ/力, ロ/口,
     * エ/工, タ/夕, ニ/二, ハ/八, ト/卜), and the kana pairs that share one stroke (ヘ/へ, ベ/べ,
     * ペ/ぺ). The kanji pairs below them are a single stroke apart and are told apart by the
     * dictionary instead of by their shape: 末/未, 土/士, 千/干 (stroke length and slant), 己/已/巳
     * (which strokes are joined), and 猫/描, the 犭/扌 misread measured on a real phone
     * (docs/jp-prototype/REPORT.md).
     *
     * A pair only ever fires on a character the dictionary has no word for: 描 is not in JMdict, so
     * that misread is repaired, while 未, 土 and 千 are words on their own and are left alone. The
     * unit tests' smaller dictionaries are what exercise the other kanji pairs.
     */
    private val CONFUSABLE_PAIRS: List<Pair<Char, Char>> = listOf(
        'ー' to '一',
        'カ' to '力',
        'ロ' to '口',
        'エ' to '工',
        'タ' to '夕',
        'ニ' to '二',
        'ハ' to '八',
        'ト' to '卜',
        'ヘ' to 'へ',
        'ベ' to 'べ',
        'ペ' to 'ぺ',
        '末' to '未',
        '土' to '士',
        '千' to '干',
        '己' to '已',
        '己' to '巳',
        '已' to '巳',
        '猫' to '描'
    )

    /** Every character the pairs mention, with the alternatives the dictionary pass tries for it. */
    private val CONFUSABLES: Map<Char, List<Char>> = CONFUSABLE_PAIRS
        .flatMap { (first, second) -> listOf(first to second, second to first) }
        .groupBy({ it.first }, { it.second })

    /** A katakana word is at least this long: 口 in カ口 is a broken ロ, in 入口 it is a kanji. */
    private const val KATAKANA_RUN_MIN = 3

    /** Characters either side of a candidate the dictionary comparison looks at. */
    private const val WINDOW = 6

    /** Confusable positions tried per line. Each costs dictionary work, so a mangled line stays bounded. */
    private const val MAX_CANDIDATES = 8

    /** Dictionary lookups a single [repair] may spend, [WordScanner.scan] included. */
    private const val MAX_LOOKUPS = 400

    /** Words taken from one scan. Above the panel's 24, so the covering of a long line is never truncated. */
    private const val MAX_WORDS = 64

    private const val PROLONGED_SOUND_MARK = 'ー'
    private const val HIRAGANA_START = 0x3041
    private const val HIRAGANA_END = 0x3096
    private const val KATAKANA_START = 0x30A1
    private const val KATAKANA_END = 0x30F6
    private const val SCRIPT_OTHER = -1
    private const val SCRIPT_NONE = 0
    private const val SCRIPT_KATAKANA = 1
    private const val SCRIPT_HIRAGANA = 2
}
