/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.dictEntry
import com.gaten.app.dict.dictionaryOf
import com.gaten.app.ocr.OcrLinePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [mergeLookup], the one seam of the circle path that is pure: what the crop reading and the screen
 * index become together.
 *
 * That the crop is read at all is structural — [runLookup] cuts it and calls manga-ocr before it
 * looks at the index, with no branch to test — so it is not covered here; the priority of the two
 * answers, the acceptance of a reading, the deduplication and the fallback are.
 */
class LookupPipelineTest {

    private fun word(surface: String, context: String, start: Int = 0) = ScannedWord(
        surface = surface,
        start = start,
        end = start + surface.length,
        baseForm = surface,
        entries = listOf(dictEntry(surface)),
        context = context
    )

    @Test
    fun theCropReadingIsTheTextWhateverTheIndexReadInstead() {
        val index = LookupResult(ocrText = "違う行", words = emptyList())

        val result = mergeLookup("猫が水を飲む", index, dictionaryOf("猫", "水", "飲む"))

        assertEquals("猫が水を飲む", result.ocrText)
    }

    @Test
    fun theCropWordsAddressTheCropTextAndCarryItAsContext() {
        val result = mergeLookup("猫が水を飲む", null, dictionaryOf("猫", "水", "飲む"))

        assertEquals(listOf("猫", "水", "飲む"), result.words.map { it.surface })
        val water = result.words.first { it.surface == "水" }
        assertEquals(2, water.start)
        assertEquals(3, water.end)
        assertEquals("水", result.ocrText.substring(water.start, water.end))
        assertTrue(result.words.all { it.context == result.ocrText })
    }

    @Test
    fun theIndexWordsSupplementTheCropAndAreDeduplicatedBySurface() {
        val index = LookupResult(
            ocrText = "猫と犬が走る",
            words = listOf(word("猫", "猫と犬が走る"), word("犬", "猫と犬が走る", start = 2))
        )

        val result = mergeLookup("猫が水を飲む", index, dictionaryOf("猫", "水", "飲む", "犬"))

        // The crop's words come first and the index's own follow; 猫 is the crop's already and is
        // not listed twice.
        assertEquals(listOf("猫", "水", "飲む", "犬"), result.words.map { it.surface })
        // The supplemented word keeps the line and the offsets it was read with.
        val supplemented = result.words.last()
        assertEquals(2, supplemented.start)
        assertEquals(3, supplemented.end)
        assertEquals("猫と犬が走る", supplemented.context)
        assertEquals(supplemented.surface, supplemented.context.substring(supplemented.start, supplemented.end))
    }

    @Test
    fun aMissingCropReadingLeavesTheIndexAnswer() {
        // No model installed, or a read that failed: the reading is null.
        val index = LookupResult(ocrText = "水を飲", words = listOf(word("水", "猫が水を飲む", start = 2)))

        val result = mergeLookup(null, index, dictionaryOf("水"))

        assertEquals("水を飲", result.ocrText)
        assertEquals(listOf("水"), result.words.map { it.surface })
    }

    @Test
    fun aLatinCropReadingIsNotTheText() {
        val index = LookupResult(ocrText = "水を飲", words = emptyList())

        val result = mergeLookup("Hello world", index, dictionaryOf("水"))

        assertEquals("水を飲", result.ocrText)
    }

    @Test
    fun aPlaceholderCropReadingIsNotTheText() {
        val index = LookupResult(ocrText = "水を飲", words = emptyList())

        assertEquals("水を飲", mergeLookup("", index, dictionaryOf("水")).ocrText)
        assertEquals("水を飲", mergeLookup("   ", index, dictionaryOf("水")).ocrText)
        assertEquals("水を飲", mergeLookup("□□□", index, dictionaryOf("水")).ocrText)
    }

    @Test
    fun aLongCropReadingIsAccepted() {
        // 18 characters: OcrLinePolicy's length heuristic would refuse a reading that long against
        // an empty ML Kit line, and the whole selection is one line, so its length says nothing.
        val reading = "猫が水を飲む猫が水を飲む猫が水を飲む"
        assertFalse(OcrLinePolicy.acceptsMangaText("", reading))

        val result = mergeLookup(reading, null, dictionaryOf("猫", "水", "飲む"))

        assertEquals(reading, result.ocrText)
        assertTrue(result.words.isNotEmpty())
    }

    @Test
    fun neitherACropReadingNorAnIndexLeavesTheNoTextAnswer() {
        val result = mergeLookup(null, null, dictionaryOf("猫"))

        assertEquals("", result.ocrText)
        assertTrue(result.words.isEmpty())
    }

    @Test
    fun theMergedWordsAreCappedTogether() {
        val index = LookupResult(ocrText = "犬", words = listOf(word("犬", "犬")))

        val result = mergeLookup("猫が水を飲む", index, dictionaryOf("猫", "水", "飲む", "犬"), maxWords = 2)

        assertEquals(listOf("猫", "水"), result.words.map { it.surface })
    }
}
