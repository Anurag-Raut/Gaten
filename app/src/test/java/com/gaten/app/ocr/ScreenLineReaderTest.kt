/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ScreenLineReader] with a fake recogniser: the cache, the fallbacks, the timeout and the prefetch
 * order are all exercised without a model, which is what the injected `readLine` is for.
 */
class ScreenLineReaderTest {

    /** The lines the fake recogniser was asked for, in order, and the log the reader wrote. */
    private val asked = Collections.synchronizedList(ArrayList<Int>())
    private val log = Collections.synchronizedList(ArrayList<String>())

    private fun reader(
        screen: ScreenText,
        timeoutMillis: Long = 5_000,
        readLine: suspend (ScreenLine) -> String?
    ) = ScreenLineReader(screen, readLine, timeoutMillis, onLog = { log.add(it) })

    /** A one-line-per-entry screen, boxes 40 x 60 px per character, like the scanner's. */
    private fun screenOf(vararg texts: String): ScreenText = ScreenText(
        texts.mapIndexed { index, text ->
            val bounds = Box(0, index * 100, 40 * maxOf(text.length, 1), index * 100 + 60)
            ScreenLine(text, bounds, vertical = false, charBoxes = interpolateCharBoxes(text, bounds, false))
        },
        width = 1080,
        height = 2400
    )

    /** Answers every line with [text], remembering which line was asked for. */
    private fun readerAnswering(screen: ScreenText, text: String, timeoutMillis: Long = 5_000) =
        reader(screen, timeoutMillis) { line ->
            asked.add(screen.lines.indexOfFirst { it === line })
            text
        }

    @Test
    fun readsALineAndReplacesItsTextInTheIndex() {
        val screen = screenOf("描が水を飲む")
        val reader = readerAnswering(screen, "猫が水を飲む")

        val text = runBlocking { reader.read(0) }

        assertEquals("猫が水を飲む", text)
        assertEquals("猫が水を飲む", reader.text.value.lines[0].text)
    }

    @Test
    fun theReplacedLineKeepsTheInvariantThatBoxesMatchTheText() {
        val screen = screenOf("描が水")
        val reader = readerAnswering(screen, "猫が水を飲む")

        runBlocking { reader.read(0) }

        val line = reader.text.value.lines[0]
        assertEquals("猫が水を飲む", line.text)
        assertEquals(line.text.length, line.charBoxes.size)
        // The boxes still tile the line the detector reported.
        assertEquals(screen.lines[0].bounds, Box(
            line.charBoxes.first().left,
            line.charBoxes.first().top,
            line.charBoxes.last().right,
            line.charBoxes.last().bottom
        ))
    }

    @Test
    fun keepsMlKitsTextWhenTheModelReadsNothing() {
        val screen = screenOf("描が水を飲む")
        val reader = reader(screen) { null }

        val text = runBlocking { reader.read(0) }

        assertEquals("描が水を飲む", text)
        assertEquals("描が水を飲む", reader.text.value.lines[0].text)
    }

    @Test
    fun keepsMlKitsTextWhenTheReadingIsRefused() {
        val screen = screenOf("描")
        // Seventeen characters for a one character line: past what that box could hold.
        val runaway = readerAnswering(screen, "猫".repeat(17))

        val text = runBlocking { runaway.read(0) }

        assertEquals("描", text)
        assertEquals("描", runaway.text.value.lines[0].text)
        assertTrue(log.any { it.contains("[rejected]") })
    }

    @Test
    fun aNonJapaneseLineIsStillOfferedToTheModel() {
        // ML Kit reading a stylized line as Latin is exactly the case manga-ocr is here for.
        val screen = screenOf("Hello world")

        val text = runBlocking { readerAnswering(screen, "猫が水を飲む").read(0) }

        assertEquals("猫が水を飲む", text)
        assertEquals(listOf(0), asked)
    }

    @Test
    fun aLineWhoseFinalTextHasNoJapaneseIsDropped() {
        val screen = screenOf("Hello world")
        val reader = readerAnswering(screen, "Hello there")

        runBlocking { reader.read(0) }

        val line = reader.text.value.lines[0]
        assertEquals("", line.text)
        assertTrue(line.charBoxes.isEmpty())
        // The line keeps its place: every index a gesture hands back addresses this list.
        assertEquals(screen.lines[0].bounds, line.bounds)
        assertTrue(log.any { it.contains("dropped line") })
    }

    @Test
    fun mlKitsOwnNonJapaneseTextIsDroppedWhenNoModelReadsTheLine() {
        val screen = screenOf("11:21")
        val reader = reader(screen) { null }

        runBlocking { reader.read(0) }

        assertEquals("", reader.text.value.lines[0].text)
        assertTrue(log.any { it.contains("dropped line") })
    }

    @Test
    fun fallsBackToMlKitsTextWhenTheReadTimesOut() {
        val screen = screenOf("描が水を飲む")
        val reader = reader(screen, timeoutMillis = 60) { delay(400); "猫が水を飲む" }

        val text = runBlocking { reader.read(0) }

        assertEquals("描が水を飲む", text)
        assertTrue(log.any { it.contains("timed out") })
        reader.close()
    }

    @Test
    fun doesNotReadTheSameLineTwice() {
        val screen = screenOf("描が水を飲む")
        val reader = readerAnswering(screen, "猫が水を飲む")

        runBlocking {
            reader.read(0)
            reader.read(0)
        }

        assertEquals(1, asked.size)
    }

    @Test
    fun aLineAlreadyReadIsReportedAsACacheHit() {
        val screen = screenOf("描が水を飲む")
        val reader = readerAnswering(screen, "猫が水を飲む")

        runBlocking {
            // The prefetch warms the line; the read that follows joins it rather than reading again.
            reader.prefetch(listOf(0))
            assertEquals("猫が水を飲む", reader.read(0))
            assertEquals("猫が水を飲む", reader.read(0))
        }

        assertEquals(1, asked.size)
        assertTrue(log.any { it.contains("(cache hit)") })
    }

    @Test
    fun prefetchReadsTheLinesInTheOrderItWasGiven() {
        val screen = screenOf("猫が水を飲む", "描", "犬が走る")
        val prefetchFinished = CountDownLatch(1)
        val reader = ScreenLineReader(
            screen,
            { line -> asked.add(screen.lines.indexOfFirst { it === line }); "猫" },
            timeoutMillis = 5_000,
            onLog = { message ->
                log.add(message)
                if (message.contains("prefetch finished")) prefetchFinished.countDown()
            }
        )

        reader.prefetch(listOf(2, 0, 1))

        assertTrue("the prefetch did not finish: asked=$asked", prefetchFinished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(2, 0, 1), asked)
    }

    @Test
    fun prefetchReadsEveryLine() {
        val screen = screenOf("Hello world", "描が水", "11:21")
        val finished = CountDownLatch(1)
        val reader = ScreenLineReader(
            screen,
            { line -> asked.add(screen.lines.indexOfFirst { it === line }); "猫" },
            timeoutMillis = 5_000,
            onLog = { message ->
                log.add(message)
                if (message.contains("prefetch finished")) finished.countDown()
            }
        )

        reader.prefetch(listOf(0, 1, 2))

        assertTrue("the prefetch did not finish: asked=$asked", finished.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0, 1, 2), asked)
    }

    @Test
    fun closingStopsThePrefetch() {
        val screen = screenOf("描が水", "猫が水", "犬が走る", "鳥が飛ぶ", "魚が泳ぐ")
        val first = CountDownLatch(1)
        val reader = reader(screen) { line ->
            asked.add(screen.lines.indexOfFirst { it === line })
            first.countDown()
            delay(100)
            "猫"
        }

        reader.prefetch(screen.lines.indices.toList())
        assertTrue("the prefetch never started", first.await(5, TimeUnit.SECONDS))
        reader.close()
        Thread.sleep(300)

        // 300 ms is three reads' worth of delay: without the cancel the whole screen would be read.
        assertTrue("kept reading after close: $asked", asked.size <= 2)
    }

    @Test
    fun theBiggestBoxesAreReadFirstWhenNothingHasBeenTouched() {
        val screen = screenOf("Hello world", "描", "猫が水を飲む")

        // Every line is read, so the widest box goes first and the smallest last.
        assertEquals(listOf(0, 2, 1), prefetchOrder(screen))
    }

    @Test
    fun theLinesNearestTheTouchedOneAreReadFirst() {
        val screen = screenOf("猫が水を飲む", "描", "犬が走る")

        assertEquals(listOf(0, 1, 2), prefetchOrder(screen, fromLine = 0))
        assertEquals(listOf(2, 1, 0), prefetchOrder(screen, fromLine = 2))
    }

    @Test
    fun everyLineIsInTheOrder() {
        // Equal-size boxes, so the earlier line goes first.
        assertEquals(listOf(0, 1), prefetchOrder(screenOf("Hello", "11:21")))
    }

    @Test
    fun anUnknownLineIsNotReadAndYieldsNothing() {
        val screen = screenOf("猫が水を飲む")

        val text = runBlocking { readerAnswering(screen, "猫").read(9) }

        assertEquals("", text)
        assertFalse(asked.contains(9))
    }
}
