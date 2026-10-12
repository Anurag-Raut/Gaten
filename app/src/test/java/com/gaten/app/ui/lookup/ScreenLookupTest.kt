/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.dict.dictionaryOf
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.ScreenHits
import com.gaten.app.ocr.withText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [lookupFromSelection] (a lasso) and [lookupFromTap] (a tap), answered from the screen index. */
class ScreenLookupTest {

    @Test
    fun aTapAfterTheTextWasReplacedAddressesTheNewText() {
        // ML Kit read three characters of this line; manga-ocr read five, so the boxes were rebuilt.
        val line = screenLine("猫カド", top = 100).withText("猫がカード")
        val screen = screenOf(line)
        val lookup = dictionaryOf("猫", "カード")

        // ML Kit's boxes touch, so が and ー, which it never saw, share the spans of their neighbours;
        // x = 110 addresses ド at index 4 in the new text and at index 2 in ML Kit's, so the offset
        // moved with the text rather than being carried over.
        assertEquals(2, ScreenHits.hitTest(screenOf(screenLine("猫カド", top = 100)), 110, 130, 8)?.charIndex)
        assertEquals(4, ScreenHits.hitTest(screen, 110, 130, 8)?.charIndex)

        val tap = lookupFromTap(screen, 110, 130, 8, lookup)

        assertNotNull(tap)
        assertEquals("カード", tap!!.result.words.first().surface)
        // The word's box is the union of the new character boxes it spans: カ, squeezed by が, at
        // 53..80, through ド at 97..120.
        assertEquals(Box(53, 100, 120, 160), tap.wordBox)
        assertEquals("猫がカード", tap.result.ocrText)
    }

    @Test
    fun aWordStraddlingTheSelectionEdgeIsKeptWholeAndWordsOutsideAreDropped() {
        val screen = screenOf(screenLine("猫が水を飲む", top = 100))
        val lookup = dictionaryOf("猫", "水", "飲む")

        // 40 px characters: 水 80..120, を 120..160, 飲 160..200, む 200..240. The selection covers
        // 水, を and 飲 and stops before む, and there is no slack: a character the loop did not
        // reach is not selected.
        val result = lookupFromSelection(screen, Box(100, 110, 170, 140), lookup)

        assertEquals(listOf("水", "飲む"), result.words.map { it.surface })
        // 猫 lies outside the selection and is gone; 飲む is not clipped to 飲.
        assertTrue(result.words.none { it.surface == "猫" })
        // Every word travels the line it was read from.
        assertEquals(listOf("猫が水を飲む", "猫が水を飲む"), result.words.map { it.context })
        // Only the selected characters feed the sheet's copy button.
        assertEquals("水を飲", result.ocrText)
    }

    @Test
    fun aRepairedLookalikeReachesTheDictionaryAndEveryWordCarriesTheRepairedLine() {
        val screen = screenOf(screenLine("コーヒ一を注文する", top = 100))
        val lookup = dictionaryOf("コーヒー", "注文")

        val result = lookupFromSelection(screen, Box(0, 110, 360, 140), lookup)

        // The misread 一 (the prolonged sound mark after ヒ) is repaired, so コーヒー matches.
        assertEquals(2, result.words.size)
        assertTrue(result.words.any { it.surface == "コーヒー" })
        assertTrue(result.words.any { it.surface == "注文" })
        // The context is the repaired line text, on every returned word.
        assertEquals(
            listOf("コーヒーを注文する", "コーヒーを注文する"),
            result.words.map { it.context }
        )
        assertEquals("コーヒーを注文する", result.ocrText)
    }

    @Test
    fun anEmptySelectionFarFromEveryLineYieldsAnEmptyResult() {
        val screen = screenOf(screenLine("猫が水を飲む", top = 100))

        val result = lookupFromSelection(screen, Box(500, 2000, 600, 2100), dictionaryOf("猫"))

        assertEquals("", result.ocrText)
        assertTrue(result.words.isEmpty())
    }

    @Test
    fun aMultiLineSelectionConcatenatesTheLinesInOrder() {
        val screen = screenOf(
            screenLine("今日は", top = 100),
            screenLine("晴れです", top = 300)
        )
        val lookup = dictionaryOf("今日", "晴れ")

        val result = lookupFromSelection(screen, Box(0, 110, 480, 400), lookup)

        assertEquals("今日は\n晴れです", result.ocrText)
        assertEquals(listOf("今日", "晴れ"), result.words.map { it.surface })
    }

    @Test
    fun tappingTheSecondCharacterOfAWordReturnsThatWordFirst() {
        val screen = screenOf(screenLine("コンピュータを使います", top = 100))
        val lookup = dictionaryOf("コンピュータ", "使う")

        // ン is the second character: its box spans x 40..80.
        val tap = lookupFromTap(screen, x = 60, y = 130, tolerancePx = 0, lookup = lookup)

        assertNotNull(tap)
        val word = tap!!.result.words.first()
        assertEquals("コンピュータ", word.surface)
        assertEquals(0, word.start)
        assertEquals(6, word.end)
        // The highlight is the union of the word's character boxes, not one character's.
        assertEquals(Box(0, 100, 240, 160), tap.wordBox)
        assertEquals("コンピュータを使います", tap.result.ocrText)
    }

    @Test
    fun aTapOnALoneKanaParticleStepsToTheNextWord() {
        val screen = screenOf(screenLine("猫を食べる", top = 100))
        val lookup = dictionaryOf("猫", "食べる")

        // を is the second character: its box spans x 40..80.
        val tap = lookupFromTap(screen, x = 60, y = 130, tolerancePx = 0, lookup = lookup)

        assertNotNull(tap)
        val word = tap!!.result.words.first()
        assertEquals("食べる", word.surface)
        assertEquals(2, word.start)
    }

    @Test
    fun aTapBeyondToleranceHitsNothing() {
        val screen = screenOf(screenLine("猫を食べる", top = 100))

        assertNull(lookupFromTap(screen, x = 800, y = 1000, tolerancePx = 24, lookup = dictionaryOf("猫")))
    }

    @Test
    fun theLongestCoverWinsAndTiesGoToTheEarliestStart() {
        // 語 tapped at index 2: 本語です (1..5) covers more of the tap than 語 (2..3) does.
        assertEquals(1, resolveTapStart("日本語です", 2, dictionaryOf("本語です", "語")))
        // With only 本語 and 語 known, both covers end at 3 — the earlier start wins.
        assertEquals(1, resolveTapStart("日本語です", 2, dictionaryOf("本語", "語")))
    }

    @Test
    fun aTapOnATategakiLineWorks() {
        val screen = screenOf(screenLine("猫が水を飲む", top = 100, vertical = true))
        val lookup = dictionaryOf("猫", "水", "飲む")

        // The character boxes stack downward: 水 is the third character, y 220..280.
        val tap = lookupFromTap(screen, x = 20, y = 250, tolerancePx = 0, lookup = lookup)

        assertNotNull(tap)
        assertEquals("水", tap!!.result.words.first().surface)
        assertEquals(Box(0, 220, 40, 280), tap.wordBox)
    }
}
