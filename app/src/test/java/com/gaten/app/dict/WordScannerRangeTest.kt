/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WordScanner.scanLine], a lasso against the fake dictionary. */
class WordScannerRangeTest {

    @Test
    fun aWordStraddlingTheRangeEdgeIsKeptWhole() {
        val text = "猫が水を飲む"
        val lookup = dictionaryOf("猫", "水", "飲む")

        // The range covers む alone; 飲む spans 4..6 and comes back in one piece.
        val words = WordScanner.scanLine(text, rangeStart = 5, rangeEndExclusive = 6, lookup = lookup)

        assertEquals(listOf("飲む"), words.map { it.surface })
        assertEquals(4, words[0].start)
        assertEquals(6, words[0].end)
        assertEquals("飲む", text.substring(words[0].start, words[0].end))
    }

    @Test
    fun wordsOutsideTheRangeAreDropped() {
        val text = "猫が水を飲む"
        val lookup = dictionaryOf("猫", "水", "飲む")

        assertEquals(
            listOf("水"),
            WordScanner.scanLine(text, rangeStart = 2, rangeEndExclusive = 4, lookup = lookup).map { it.surface }
        )
        // を alone holds no word at all, and neither neighbour reaches into it.
        assertTrue(WordScanner.scanLine(text, rangeStart = 1, rangeEndExclusive = 2, lookup = lookup).isEmpty())
    }

    @Test
    fun aDeinflectedWordAtTheRangeEdgeIsKept() {
        val text = "日本語を勉強します"
        val lookup = dictionaryOf("日本語", "勉強", "する")

        // The range holds only す; reading します needs the whole line as context.
        val words = WordScanner.scanLine(text, rangeStart = 8, rangeEndExclusive = 9, lookup = lookup)

        assertEquals(listOf("します"), words.map { it.surface })
        assertEquals("する", words[0].baseForm)
        assertEquals(6, words[0].start)
        assertEquals(9, words[0].end)
    }

    @Test
    fun aRangeOverTheWholeLineIsTheWholeScan() {
        val text = "コンピュータを使います"
        val lookup = dictionaryOf("コンピュータ", "使う", "する")

        val line = WordScanner.scanLine(text, rangeStart = 0, rangeEndExclusive = text.length, lookup = lookup)

        // scan()'s ranking is the one a lasso over the whole line gets back.
        assertEquals(listOf("使います", "コンピュータ"), line.map { it.surface })
        assertEquals(WordScanner.scan(text, lookup).map { it.surface }, line.map { it.surface })
    }
}
