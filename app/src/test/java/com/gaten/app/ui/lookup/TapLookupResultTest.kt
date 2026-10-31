/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.dict.dictionaryOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapLookupResultTest {
    @Test
    fun onlyMangaReadingSuppliesWordsAndContext() {
        val result = tapLookupResult(screenLine("猫と呪分と犬", top = 100),
            "注文", 100, 130, dictionaryOf("猫", "注文", "犬"))
        assertEquals("注文", result.ocrText)
        assertEquals(listOf("注文"), result.words.map { it.surface })
        assertTrue(result.words.all { it.context == "注文" })
    }

    @Test
    fun failedRecognitionNeverReturnsIndexedWordsForEitherGesture() {
        val line = screenLine("猫と犬", top = 100)
        val lookup = dictionaryOf("猫", "犬")
        for (reading in listOf(null, "", "   ", "[UNK]", "□", "letters")) {
            for (result in listOf(tapLookupResult(line, reading, 20, 130, lookup),
                mangaLookupResult(reading, lookup))) {
                assertEquals("", result.ocrText)
                assertTrue(result.words.isEmpty())
                assertTrue(result.error != null)
            }
        }
    }

    @Test
    fun deduplicationKeepsTheRepeatedOccurrenceNearestTheTap() {
        val line = screenLine("猫と犬と猫", top = 100)
        val result = tapLookupResult(line, line.text, 180, 130, dictionaryOf("猫", "犬"))
        assertEquals(listOf("猫", "犬"), result.words.map { it.surface })
        assertEquals(4, result.words.first().start)
    }

    @Test
    fun aWordNearTheEndOfALongLineSurvivesTheDisplayLimit() {
        val terms = (0 until 30).map { ('一'.code + it).toChar().toString() }
        val text = terms.joinToString("を")
        val line = screenLine(text, top = 100, charWidth = 10, charHeight = 20)
        val result = tapLookupResult(line, text, text.length * 10 - 5, 110,
            dictionaryOf(*terms.toTypedArray()))
        assertEquals(24, result.words.size)
        assertEquals(terms.last(), result.words.first().surface)
    }
}
