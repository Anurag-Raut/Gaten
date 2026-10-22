/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.ScannedWord
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiCardTest {

    @Test
    fun kanjiWordTakesTheFirstEntrysDisplayFormAndReading() {
        val card = AnkiCard.from(
            word(listOf(entry(primary = "猫", kana = listOf("ねこ", "ネコ"), glosses = listOf("cat", "feline"))))
        )

        assertEquals("猫", card.expression)
        assertEquals("ねこ", card.reading)
        assertEquals("cat; feline<br><small>n</small>", card.meaning)
    }

    @Test
    fun kanaOnlyWordUsesItsOwnFormAsTheExpression() {
        val card = AnkiCard.from(
            word(entries = listOf(entry(primary = "ねこ", kana = listOf("ねこ"))), surface = "ねこ")
        )

        assertEquals("ねこ", card.expression)
        assertEquals("ねこ", card.reading)
    }

    @Test
    fun meaningKeepsTheFirstFiveGlossesAndThePartOfSpeech() {
        val glosses = (1..8).map { "gloss$it" }

        val card = AnkiCard.from(word(listOf(entry(glosses = glosses, pos = listOf("noun", "verb")))))

        assertEquals("gloss1; gloss2; gloss3; gloss4; gloss5<br><small>noun, verb</small>", card.meaning)
    }

    @Test
    fun meaningWithoutAPartOfSpeechIsJustTheGlosses() {
        val card = AnkiCard.from(word(listOf(entry(pos = emptyList()))))

        assertEquals("cat", card.meaning)
    }

    @Test
    fun htmlMetacharactersAreEscaped() {
        val card = AnkiCard.from(
            word(
                listOf(
                    entry(
                        primary = "a<b",
                        kana = listOf("a&b"),
                        glosses = listOf("<&>"),
                        pos = listOf("a>")
                    )
                )
            )
        )

        assertEquals("a&lt;b", card.expression)
        assertEquals("a&amp;b", card.reading)
        assertEquals("&lt;&amp;&gt;<br><small>a&gt;</small>", card.meaning)
    }

    @Test
    fun wordWithoutEntriesFallsBackToItsBaseForm() {
        val card = AnkiCard.from(word(surface = "飲ん", baseForm = "飲む", entries = emptyList()))

        assertEquals("飲む", card.expression)
        assertEquals("", card.reading)
        assertEquals("", card.meaning)
    }

    /**
     * A screen with a chosen entry exports that entry: こう reaches 甲 and 乙, and the note has to
     * carry the readings and meanings of the one the reader opened, not of the first row.
     */
    @Test
    fun theSelectedEntryIsWhatTheCardCarries() {
        val selected = entry(primary = "乙", kana = listOf("おつ"), glosses = listOf("second"))

        val card = AnkiCard.from(selected)

        assertEquals("乙", card.expression)
        assertEquals("おつ", card.reading)
        assertEquals("second<br><small>n</small>", card.meaning)
    }

    @Test
    fun anEntryWithoutAKanaFormHasNoReading() {
        val card = AnkiCard.from(entry(primary = "𠮷", kana = emptyList()))

        assertEquals("𠮷", card.expression)
        assertEquals("", card.reading)
        assertEquals("cat<br><small>n</small>", card.meaning)
    }

    /** The word path and the entry path build the same note for the same entry. */
    @Test
    fun theEntryPathMatchesTheWordPathForTheSameEntry() {
        val entry = entry(primary = "猫", kana = listOf("ねこ"), glosses = listOf("cat", "feline"))

        assertEquals(AnkiCard.from(entry), AnkiCard.from(word(listOf(entry))))
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
        glosses: List<String> = listOf("cat"),
        pos: List<String> = listOf("n")
    ) = DictEntry(
        id = 1,
        primary = primary,
        kanji = listOf(primary),
        kana = kana,
        glosses = glosses,
        pos = pos,
        common = true
    )
}
