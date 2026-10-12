/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.anki

import com.yomilens.app.dict.DictEntry
import com.yomilens.app.dict.ScannedWord
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiCardTest {

    @Test
    fun kanjiWordTakesTheFirstEntrysDisplayFormAndReading() {
        val card = AnkiCard.from(
            word(listOf(entry(primary = "猫", kana = listOf("ねこ", "ネコ"), glosses = listOf("cat", "feline")))),
            sentence = ""
        )

        assertEquals("猫", card.expression)
        assertEquals("ねこ", card.reading)
        assertEquals("cat; feline", card.meaning)
    }

    @Test
    fun kanaOnlyWordUsesItsOwnFormAsTheExpression() {
        val card = AnkiCard.from(
            word(entries = listOf(entry(primary = "ねこ", kana = listOf("ねこ"))), surface = "ねこ"),
            sentence = ""
        )

        assertEquals("ねこ", card.expression)
        assertEquals("ねこ", card.reading)
    }

    @Test
    fun onlyTheFirstFiveGlossesAreKept() {
        val glosses = (1..8).map { "gloss$it" }

        val card = AnkiCard.from(word(listOf(entry(glosses = glosses))), sentence = "")

        assertEquals("gloss1; gloss2; gloss3; gloss4; gloss5", card.meaning)
    }

    @Test
    fun htmlMetacharactersAreEscaped() {
        val card = AnkiCard.from(
            word(listOf(entry(primary = "a<b", kana = listOf("a&b"), glosses = listOf("<&>")))),
            sentence = ""
        )

        assertEquals("a&lt;b", card.expression)
        assertEquals("a&amp;b", card.reading)
        assertEquals("&lt;&amp;&gt;", card.meaning)
    }

    @Test
    fun sentenceBoldsTheSurfaceItContains() {
        val card = AnkiCard.from(word(surface = "猫"), sentence = "猫が水を飲む")

        assertEquals("<b>猫</b>が水を飲む", card.sentence)
    }

    @Test
    fun sentenceBoldsOnlyTheFirstOccurrence() {
        val card = AnkiCard.from(word(surface = "猫"), sentence = "猫と猫")

        assertEquals("<b>猫</b>と猫", card.sentence)
    }

    @Test
    fun sentenceWithoutTheSurfaceIsKeptAsIs() {
        val card = AnkiCard.from(word(surface = "猫"), sentence = "犬が走る")

        assertEquals("犬が走る", card.sentence)
    }

    @Test
    fun sentenceIsEscapedAroundTheBoldWord() {
        val card = AnkiCard.from(word(surface = "猫"), sentence = "a<b>猫</b>c")

        assertEquals("a&lt;b&gt;<b>猫</b>&lt;/b&gt;c", card.sentence)
    }

    @Test
    fun missingSentenceStaysEmpty() {
        val card = AnkiCard.from(word(surface = "猫"), sentence = "")

        assertEquals("", card.sentence)
    }

    @Test
    fun wordWithoutEntriesFallsBackToItsBaseForm() {
        val card = AnkiCard.from(
            word(surface = "飲ん", baseForm = "飲む", entries = emptyList()),
            sentence = "水を飲んだ"
        )

        assertEquals("飲む", card.expression)
        assertEquals("", card.reading)
        assertEquals("", card.meaning)
        assertEquals("水を<b>飲ん</b>だ", card.sentence)
    }

    private fun word(
        entries: List<DictEntry> = listOf(entry()),
        surface: String = "猫",
        baseForm: String = surface
    ) = ScannedWord(
        surface = surface,
        start = 0,
        end = surface.length,
        baseForm = baseForm,
        entries = entries
    )

    private fun entry(
        primary: String = "猫",
        kana: List<String> = listOf("ねこ"),
        glosses: List<String> = listOf("cat")
    ) = DictEntry(
        id = 1,
        primary = primary,
        kanji = listOf(primary),
        kana = kana,
        glosses = glosses,
        pos = listOf("n"),
        common = true
    )
}
