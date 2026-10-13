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
import com.gaten.app.ocr.Box
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * [mergeLookup], the one seam of the circle path that is pure: what the crop reading and the screen
 * index become together.
 *
 * That the crop is read at all is structural — [runLookup] cuts it and calls manga-ocr before it
 * looks at the index, with no branch to test — so it is not covered here; the priority of the two
 * answers, the acceptance of a reading, the deduplication and the fallback are.
 */
class LookupPipelineTest {

    @Test
    fun exactCropCacheReusesSuccessfulReadingAndRetriesNull() = runBlocking {
        val cache = ExactCropOcrCache(maxResults = 2)
        val box = Box(1, 2, 20, 30)
        val calls = AtomicInteger()

        assertEquals("猫", cache.read(box) { calls.incrementAndGet(); "猫" })
        assertEquals("猫", cache.read(box) { calls.incrementAndGet(); "別" })
        assertEquals(1, calls.get())

        val nullBox = Box(2, 3, 20, 30)
        assertEquals(null, cache.read(nullBox) { calls.incrementAndGet(); null })
        assertEquals("犬", cache.read(nullBox) { calls.incrementAndGet(); "犬" })
        assertEquals(3, calls.get())
    }

    @Test
    fun exactCropCacheInvalidatesWhenModelRevisionChanges() = runBlocking {
        val cache = ExactCropOcrCache(modelRevision = "one")
        val box = Box(1, 2, 20, 30)
        val calls = AtomicInteger()

        cache.read(box) { calls.incrementAndGet(); "猫" }
        cache.setModelRevision("two")
        assertEquals("犬", cache.read(box) { calls.incrementAndGet(); "犬" })
        assertEquals(2, calls.get())
    }

    @Test
    fun rejectedPrimaryKeepsSupplementText() {
        val supplement = LookupResult(ocrText = "画面の文字", words = emptyList())

        assertEquals("画面の文字", mergePrimary(LookupResult("", emptyList()), supplement).ocrText)
    }

    @Test
    fun identicalCropsJoinOneInFlightRead() = runBlocking {
        val cache = ExactCropOcrCache()
        val box = Box(1, 2, 20, 30)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = launch {
            assertEquals("猫", cache.read(box) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                "猫"
            })
        }
        withTimeout(1_000) { started.await() }
        val second = launch {
            assertEquals("猫", cache.read(box) { calls.incrementAndGet(); "犬" })
        }
        yield()
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, calls.get())
    }

    @Test
    fun cancelledOwnerIsRemovedSoTheCropCanRetry() = runBlocking {
        val cache = ExactCropOcrCache()
        val box = Box(1, 2, 20, 30)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch {
            cache.read(box) {
                started.complete(Unit)
                release.await()
                "never"
            }
        }
        withTimeout(1_000) { started.await() }
        owner.cancelAndJoin()
        assertEquals("再試行", cache.read(box) { "再試行" })
    }

    @Test
    fun failedOwnerIsRemovedSoTheCropCanRetry() = runBlocking {
        val cache = ExactCropOcrCache()
        val box = Box(1, 2, 20, 30)
        try {
            cache.read(box) { error("temporary") }
        } catch (_: IllegalStateException) {
            // Expected: failures are never cached.
        }
        assertEquals("再試行", cache.read(box) { "再試行" })
    }

    @Test
    fun cacheKeepsOnlyTheConfiguredNumberOfResults() = runBlocking {
        val cache = ExactCropOcrCache(maxResults = 1)
        val first = Box(1, 2, 20, 30)
        val second = Box(2, 3, 20, 30)
        val calls = AtomicInteger()

        cache.read(first) { calls.incrementAndGet(); "一" }
        cache.read(second) { calls.incrementAndGet(); "二" }
        cache.read(first) { calls.incrementAndGet(); "一" }

        assertEquals(3, calls.get())
    }

    private fun word(surface: String, context: String, start: Int = 0) = ScannedWord(
        surface = surface,
        start = start,
        end = start + surface.length,
        baseForm = surface,
        entries = listOf(dictEntry(surface)),
        context = context
    )

    @Test
    fun progressiveMergeKeepsTheCompleteCropAndSupplementResult() {
        val lookup = dictionaryOf("猫", "水", "飲む", "犬")
        val crop = "猫が水を飲む"
        val supplement = LookupResult("猫と犬", listOf(word("猫", "猫と犬"), word("犬", "猫と犬", 2)))

        assertEquals(
            mergeLookup(crop, supplement, lookup),
            mergePrimary(mergeLookup(crop, null, lookup), supplement)
        )
    }

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
