/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadwordTest {

    private fun word(surface: String, baseForm: String, vararg entries: DictEntry) =
        ScannedWord(surface, 0, surface.length, baseForm, entries.toList())

    private fun entry(
        primary: String,
        kanji: List<String> = emptyList(),
        kana: List<String> = emptyList()
    ) = DictEntry(1, primary, kanji, kana, listOf("gloss"), listOf("n"), common = false)

    private fun headword(word: ScannedWord, entry: DictEntry) = headwordFor(word, entry)

    @Test
    fun directKanjiKeepsTheSelectedForm() {
        val entry = entry("事", kanji = listOf("事"), kana = listOf("こと"))
        assertEquals("事", headword(word("事", "事", entry), entry))
    }

    @Test
    fun kanaOnlyEntryKeepsTheSelectedForm() {
        val entry = entry("こと", kana = listOf("こと"))
        assertEquals("こと", headword(word("こと", "こと", entry), entry))
    }

    @Test
    fun katakanaSurfaceOverAHiraganaOnlyEntry() {
        val entry = entry("こーひー", kana = listOf("こーひー"))
        assertEquals("コーヒー", headword(word("コーヒー", "こーひー", entry), entry))
    }

    @Test
    fun katakanaSurfaceKeptWhenTheEntryAlsoHasIt() {
        val entry = entry("コーヒー", kana = listOf("コーヒー", "コーヒ"))
        assertEquals("コーヒー", headword(word("コーヒー", "コーヒー", entry), entry))
    }

    @Test
    fun deinflectedVerbUsesTheEntrysDictionaryForm() {
        val entry = entry("食べる", kanji = listOf("食べる"), kana = listOf("たべる"))
        assertEquals("食べる", headword(word("食べた", "食べる", entry), entry))
    }

    @Test
    fun deinflectedVerbOnAKanaOnlyEntry() {
        val entry = entry("たべる", kana = listOf("たべる"))
        assertEquals("たべる", headword(word("たべた", "たべる", entry), entry))
    }

    @Test
    fun selectedSpellingNotFirstAmongKanjiForms() {
        val entry = entry("奇麗", kanji = listOf("奇麗", "綺麗"), kana = listOf("きれい"))
        assertEquals("綺麗", headword(word("綺麗", "綺麗", entry), entry))
    }

    @Test
    fun selectedSpellingFirstAmongKanjiForms() {
        val entry = entry("綺麗", kanji = listOf("綺麗", "奇麗"), kana = listOf("きれい"))
        assertEquals("綺麗", headword(word("綺麗", "綺麗", entry), entry))
    }

    @Test
    fun emptyWordFallsBackToThePrimaryForm() {
        val entry = entry("食べる", kanji = listOf("食べる"), kana = listOf("たべる"))
        assertEquals("食べる", headword(word("", "", entry), entry))
    }

    @Test
    fun noNoteWhenTheHeadwordIsThePrimaryForm() {
        val entry = entry("事", kanji = listOf("事"), kana = listOf("こと"))
        assertNull(notedForm(word("事", "事", entry), entry))
    }

    @Test
    fun noteNamesThePrimaryFormWhenTheHeadwordDiffers() {
        val alternate = entry("奇麗", kanji = listOf("奇麗", "綺麗"), kana = listOf("きれい"))
        assertEquals("奇麗", notedForm(word("綺麗", "綺麗", alternate), alternate))
    }

    @Test
    fun noteAppearsWhenAKanaSurfaceHeadsAKanjiEntry() {
        val entry = entry("綺麗", kanji = listOf("綺麗"), kana = listOf("きれい"))
        assertEquals("綺麗", notedForm(word("きれい", "きれい", entry), entry))
    }

    @Test
    fun otherMatchKeepsTheSelectedFormWhenItHasIt() {
        val word = word("事", "事")
        val entry = entry("事", kanji = listOf("事"), kana = listOf("こと"))
        assertEquals("事", alternativeHeadwordFor(word, entry))
    }

    @Test
    fun otherMatchFallsBackToItsOwnPrimaryForm() {
        val word = word("事", "事")
        val entry = entry("こと", kana = listOf("こと"))
        assertEquals("こと", alternativeHeadwordFor(word, entry))
    }
}
