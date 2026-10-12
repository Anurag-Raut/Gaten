/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ocr

import com.yomilens.app.dict.DictEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRepairTest {

    /** [input] repaired to [expected], with the one-character-out-for-one-character-in invariant. */
    private fun assertRepair(
        expected: String,
        input: String,
        lookup: (String) -> List<DictEntry> = noDictionary
    ) {
        val repaired = OcrRepair.repair(input, lookup)

        assertEquals(expected, repaired)
        assertEquals(input.length, repaired.length)
    }

    @Test
    fun prolongedSoundMarkLookalikesAreRepairedAfterKatakana() {
        assertRepair("コーヒー", "コーヒ一")
        assertRepair("スーパー", "ス一パー")
        assertRepair("コーヒー", "コーヒ－")
        assertRepair("コーヒー", "コーヒｰ")
        assertRepair("コーヒー", "コーヒ―")
        assertRepair("コーヒー", "コーヒ—")
    }

    @Test
    fun theKanjiForOneOutsideAKatakanaRunIsRealText() {
        assertRepair("一人", "一人")
        assertRepair("一番", "一番")
        assertRepair("一日", "一日")
        assertRepair("一", "一")
    }

    @Test
    fun katakanaLookalikeKanjiInsideAKatakanaRunAreRepaired() {
        assertRepair("カード", "カ一ド")
        // 口 is the shape ロ was drawn from: ロケット (rocket) recognised as 口ケット.
        assertRepair("ロケット", "口ケット")
        assertRepair("エラー", "工ラー")
        assertRepair("タクシー", "夕クシー")
        assertRepair("ニュース", "二ュース")
        assertRepair("ハンドル", "八ンドル")
        assertRepair("トラブル", "卜ラブル")
        assertRepair("カフェ", "力フェ")
    }

    @Test
    fun aKatakanaRunShorterThanThreeCharactersIsNotAWord() {
        // カ口 is two characters long, so the 口 stays a kanji.
        assertRepair("カ口", "カ口")
        assertRepair("口ー", "口ー")
    }

    @Test
    fun realKanjiIsNeverRewritten() {
        listOf("体力", "入口", "出口", "二つ", "八百屋", "夕方", "工事", "六月").forEach {
            assertRepair(it, it)
        }
    }

    @Test
    fun kanaFollowsTheScriptOfItsSurroundings() {
        assertRepair("たべる", "たベる")
        // テーブル (table) recognised with a hiragana べ: ー carries no script of its own, so the
        // ル next to it is what decides.
        assertRepair("テーベル", "テーべル")
        assertRepair("スペイン", "スぺイン")
    }

    @Test
    fun kanaScriptIsNotGuessedWithoutAgreeingNeighbours() {
        assertRepair("カへる", "カへる")
        assertRepair("犬ヘル", "犬ヘル")
        assertRepair("へ", "へ")
    }

    @Test
    fun substitutionThatMakesTheDictionaryCoverMoreIsAccepted() {
        // 週末 ("weekend") read as 週未: 末 and 未 differ only in the length of the top horizontal
        // stroke, which is exactly the stroke a thin CJK font loses.
        assertRepair("週末", "週未", dictionaryOf("週末"))
        // 干渉 ("interference") read as 千渉: 千 and 干 differ by the slant of the top stroke.
        assertRepair("干渉", "千渉", dictionaryOf("干渉"))
        // 博士 ("doctorate") read as 博土: 士 and 土 differ by the length of the bottom stroke.
        assertRepair("博士", "博土", dictionaryOf("博士"))
    }

    @Test
    fun substitutionIsRejectedWithoutDictionarySupport() {
        assertRepair("週未", "週未", dictionaryOf())
        assertRepair("週未", "週未", dictionaryOf("猫"))
        // Both readings are real words, so the substitution buys nothing.
        assertRepair("週未", "週未", dictionaryOf("週末", "週未"))
        assertRepair("千渉", "千渉", dictionaryOf("千", "干"))
    }

    @Test
    fun theMisreadMeasuredOnARealPhoneIsRepaired() {
        // docs/jp-prototype/REPORT.md, known limitation 1: 猫が水を飲む recognised as 描が水を飲む
        // (犭 read as 扌 on thin strokes). 描 is not a word here; 猫 is.
        assertRepair("猫が水を飲む", "描が水を飲む", dictionaryOf("猫", "水", "飲む"))
    }

    @Test
    fun correctTextComesBackUnchanged() {
        val text = "日本語の勉強は楽しいです"

        assertRepair(text, text, dictionaryOf("日本語", "勉強", "楽しい"))
        assertRepair("猫が水を飲む", "猫が水を飲む", dictionaryOf("猫", "水", "飲む"))
    }

    @Test
    fun equallyGoodAlternativesKeepTheOriginalCharacter() {
        // 已 and 巳 both cover the same single character, so there is nothing to choose between them.
        assertRepair("己", "己", dictionaryOf("已", "巳"))
    }

    @Test
    fun theAlternativeWithTheLongestCoveredMatchWins() {
        // 已猫 is one dictionary word and 巳 + 猫 are two; both cover the window, so the longer
        // single match decides which reading is taken.
        assertRepair("已猫", "己猫", dictionaryOf("已猫", "巳", "猫"))
    }

    @Test
    fun emptyAndBlankTextComesBackUnchanged() {
        assertRepair("", "")
        assertRepair(" ", " ")
        assertRepair("   ", "   ")
        assertRepair("\n\t ", "\n\t ")
    }

    @Test
    fun atMostEightPositionsAreRepairedPerLine() {
        // Nine misread characters, eight of which the dictionary can vouch for: the ninth stays.
        assertRepair("末末末末末末末末未", "未未未未未未未未未", dictionaryOf("末"))
    }

    @Test
    fun adversarialTextCannotOutspendTheLookupCap() {
        // 260 confusable characters with a dictionary that knows none of them: every position is
        // unmatched, so without the cap the scan alone would probe thousands of times.
        val text = "力口工夕二八卜一へベペ".repeat(20)
        var lookups = 0
        val repaired = OcrRepair.repair(text) {
            lookups++
            emptyList()
        }

        assertEquals(text.length, repaired.length)
        assertEquals(400, lookups)
    }

    @Test
    fun textWithoutConfusablesNeverAsksTheDictionary() {
        var lookups = 0
        val repaired = OcrRepair.repair("こんにちは、世界") {
            lookups++
            emptyList()
        }

        assertEquals("こんにちは、世界", repaired)
        assertEquals(0, lookups)
    }

    @Test
    fun onlyTheCharactersTheDictionaryCanVouchForAreRepaired() {
        // 描 becomes 猫, because 猫 is a word where the rest of the line still matches; the 未 does
        // not move, because nothing in this dictionary covers more of the line once it does.
        assertRepair("猫が水を飲む未", "描が水を飲む未", dictionaryOf("猫", "水", "飲む"))
    }
}

/** A lookup lambda that only knows [terms]; every other term has no entries. */
private fun dictionaryOf(vararg terms: String): (String) -> List<DictEntry> {
    val byTerm = terms.associateWith { term ->
        listOf(
            DictEntry(
                id = 1,
                primary = term,
                kanji = listOf(term),
                kana = listOf(term),
                glosses = listOf("gloss of $term"),
                pos = listOf("n"),
                common = false
            )
        )
    }
    return { term -> byTerm[term] ?: emptyList() }
}

/** A dictionary that knows nothing: the script-context pass needs no lookups at all. */
private val noDictionary: (String) -> List<DictEntry> = { emptyList() }
