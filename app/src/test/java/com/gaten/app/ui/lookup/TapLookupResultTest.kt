/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.dict.dictionaryOf
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.ScreenLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapLookupResultTest {
    @Test
    fun localCorrectionKeepsTheOtherWordsAndWholeSentence() {
        val line = screenLine("猫と呪分と犬", top = 100)
        val result = tapLookupResult(
            line, phrase(line, 2, 4), "注文", 100, 130, dictionaryOf("猫", "注文", "犬")
        )

        assertEquals("猫と注文と犬", result.ocrText)
        assertEquals(listOf("注文", "猫", "犬"), result.words.map { it.surface })
        assertTrue(result.words.all {
            it.context == result.ocrText && it.context.substring(it.start, it.end) == it.surface
        })
    }

    @Test
    fun insertedCharactersDoNotMoveTheWordsOutsideThePhrase() {
        val line = screenLine("猫とカドと犬", top = 100)
        val result = tapLookupResult(
            line, phrase(line, 2, 4), "カード", 220, 130, dictionaryOf("猫", "カード", "犬")
        )

        assertEquals("猫とカードと犬", result.ocrText)
        assertEquals("犬", result.words.first().surface)
        assertEquals(6, result.words.first().start)
        assertEquals(setOf("猫", "カード", "犬"), result.words.map { it.surface }.toSet())
        assertTrue(result.words.all { it.context.substring(it.start, it.end) == it.surface })
    }

    @Test
    fun deletedCharactersKeepTheSuffixAtItsOriginalScreenPosition() {
        val line = screenLine("猫とカーーードと犬", top = 100)
        val result = tapLookupResult(
            line, phrase(line, 2, 7), "カード", 340, 130, dictionaryOf("猫", "カード", "犬")
        )

        assertEquals("猫とカードと犬", result.ocrText)
        assertEquals("犬", result.words.first().surface)
        assertEquals(6, result.words.first().start)
        assertTrue(result.words.all { it.context.substring(it.start, it.end) == it.surface })
    }

    @Test
    fun deduplicationKeepsTheRepeatedOccurrenceNearestTheTap() {
        val line = screenLine("猫と犬と猫", top = 100)
        val result = tapLookupResult(line, null, null, 180, 130, dictionaryOf("猫", "犬"))

        assertEquals(listOf("猫", "犬"), result.words.map { it.surface })
        assertEquals(4, result.words.first().start)
    }

    @Test
    fun aWordNearTheEndOfALongLineSurvivesTheDisplayLimit() {
        val terms = (0 until 30).map { ('一'.code + it).toChar().toString() }
        val text = terms.joinToString("を")
        val line = screenLine(text, top = 100, charWidth = 10, charHeight = 20)
        val result = tapLookupResult(
            line, null, null, text.length * 10 - 5, 110, dictionaryOf(*terms.toTypedArray())
        )

        assertEquals(24, result.words.size)
        assertEquals(terms.last(), result.words.first().surface)
        assertEquals(text.length - 1, result.words.first().start)
    }

    @Test
    fun theCorrectedRegionWinsAnEqualDistanceTie() {
        val line = screenLine("猫犬", top = 100)
        val result = tapLookupResult(
            line, phrase(line, 1, 2), "犬", 40, 130, dictionaryOf("猫", "犬")
        )

        assertEquals(listOf("犬", "猫"), result.words.map { it.surface })
    }

    @Test
    fun unavailableOrRejectedOcrDoesNotDeleteTheIndexedPhrase() {
        val line = screenLine("猫と犬", top = 100)
        val lookup = dictionaryOf("猫", "犬")
        val expected = tapLookupResult(line, null, null, 20, 130, lookup)

        for (reading in listOf(null, "", "   ", "[UNK]", "□", "letters")) {
            assertEquals(expected, tapLookupResult(line, phrase(line, 0, 1), reading, 20, 130, lookup))
        }
    }

    @Test
    fun anInvalidSpanFallsBackWithoutReplacingTheLine() {
        val line = screenLine("猫と犬", top = 100)
        val result = tapLookupResult(
            line, TapPhrase(line.bounds, -1, 10), "別の文字", 20, 130, dictionaryOf("猫", "犬")
        )

        assertEquals(line.text, result.ocrText)
        assertEquals(listOf("猫", "犬"), result.words.map { it.surface })
    }

    private fun phrase(line: ScreenLine, from: Int, to: Int): TapPhrase {
        val boxes = line.charBoxes.subList(from, to)
        return TapPhrase(
            Box(boxes.minOf { it.left }, boxes.minOf { it.top },
                boxes.maxOf { it.right }, boxes.maxOf { it.bottom }),
            from, to
        )
    }
}
