/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.dict.DictEntry
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

    /** A repaired, inflected tap reuses hits and misses while the next tap starts a fresh cache. */
    @Test
    fun tapMemoizesRepeatedRepairAndInflectionQueriesForOneOperation() {
        val screen = screenOf(screenLine("コーヒ一を注文します", top = 100))
        val dictionary = dictionaryOf("コーヒー", "注文", "する")
        val calls = mutableMapOf<String, Int>()
        val lookup: (String) -> List<DictEntry> = { term ->
            calls[term] = (calls[term] ?: 0) + 1
            dictionary(term)
        }

        val first = lookupFromTap(screen, x = 60, y = 130, tolerancePx = 0, lookup = lookup)

        assertNotNull(first)
        assertEquals("コーヒーを注文します", first!!.result.ocrText)
        // A tap answers with scan()'s complex-first ranking: 注文 (kanji) heads the line.
        assertEquals(listOf("注文", "コーヒー", "します"), first.result.words.map { it.surface })
        assertEquals(listOf("注文", "コーヒー", "する"), first.result.words.map { it.baseForm })
        assertEquals(listOf(5, 0, 7), first.result.words.map { it.start })
        assertEquals(listOf(7, 4, 10), first.result.words.map { it.end })
        assertTrue(first.result.words.all { it.context == "コーヒーを注文します" })
        assertEquals(screen.lines.single().bounds, first.selectionBox)
        assertTrue(calls.keys.any { dictionary(it).isEmpty() })
        // Both successful and failed terms are delegated at most once during repair + tap scans.
        assertTrue(calls.values.all { it == 1 })

        val firstCalls = calls.toMap()
        val second = lookupFromTap(screen, x = 60, y = 130, tolerancePx = 0, lookup = lookup)

        assertEquals(first, second)
        assertEquals(firstCalls.keys, calls.keys)
        assertTrue(firstCalls.keys.all { calls[it] == 2 })
    }

    @Test
    fun aTapAfterTheTextWasReplacedSelectsTheWholeBox() {
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
        assertEquals(listOf("猫", "カード"), tap!!.result.words.map { it.surface })
        assertEquals(line.bounds, tap.selectionBox)
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
    fun tappingTheSecondCharacterSelectsTheWholeBox() {
        val screen = screenOf(screenLine("コンピュータを使います", top = 100))
        val lookup = dictionaryOf("コンピュータ", "使う")

        // ン is the second character: its box spans x 40..80.
        val tap = lookupFromTap(screen, x = 60, y = 130, tolerancePx = 0, lookup = lookup)

        assertNotNull(tap)
        // A tap ranks complex-first, so the kanji-bearing 使います heads the line.
        val word = tap!!.result.words.first()
        assertEquals("使います", word.surface)
        assertEquals(7, word.start)
        assertEquals(11, word.end)
        // The whole line comes back, ranked complex-first rather than in text order.
        assertEquals(listOf("使います", "コンピュータ"), tap.result.words.map { it.surface })
        assertEquals(screen.lines.single().bounds, tap.selectionBox)
        assertEquals("コンピュータを使います", tap.result.ocrText)
    }

    @Test
    fun aTapOnALoneKanaParticleIncludesWordsBeforeAndAfterIt() {
        val screen = screenOf(screenLine("猫を食べる", top = 100))
        val lookup = dictionaryOf("猫", "食べる")

        // を is the second character: its box spans x 40..80.
        val tap = lookupFromTap(screen, x = 60, y = 130, tolerancePx = 0, lookup = lookup)

        assertNotNull(tap)
        assertEquals(listOf("猫", "食べる"), tap!!.result.words.map { it.surface })
        assertEquals(screen.lines.single().bounds, tap.selectionBox)
    }

    @Test
    fun aTapBeyondToleranceHitsNothing() {
        val screen = screenOf(screenLine("猫を食べる", top = 100))

        assertNull(lookupFromTap(screen, x = 800, y = 1000, tolerancePx = 24, lookup = dictionaryOf("猫")))
    }

    @Test
    fun aTapOnATategakiLineWorks() {
        val screen = screenOf(screenLine("猫が水を飲む", top = 100, vertical = true))
        val lookup = dictionaryOf("猫", "水", "飲む")

        // The character boxes stack downward: 水 is the third character, y 220..280.
        val tap = lookupFromTap(screen, x = 20, y = 250, tolerancePx = 0, lookup = lookup)

        assertNotNull(tap)
        assertEquals(listOf("猫", "水", "飲む"), tap!!.result.words.map { it.surface })
        assertEquals(screen.lines.single().bounds, tap.selectionBox)
    }
}
