/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * [CharBoxAlign]: a re-read line keeps the detector's boxes for the characters it shares, and the
 * ones it does not share are cut from the gaps between them. Geometry only, so it is all reasoned
 * about in pixels.
 */
class CharBoxAlignTest {

    private val bounds = Box(0, 0, 100, 40)

    /** Four uneven per-character boxes across [bounds], the way detector symbol boxes come back. */
    private val uneven = listOf(
        Box(0, 0, 10, 40),
        Box(10, 0, 25, 40),
        Box(25, 0, 45, 40),
        Box(45, 0, 100, 40)
    )

    @Test
    fun identicalTextKeepsEveryBox() {
        assertEquals(uneven, CharBoxAlign.align("ABC1", uneven, "ABC1", bounds, vertical = false))
    }

    @Test
    fun theSameLengthKeepsTheBoxesOneToOne() {
        // Different characters, but the glyphs did not move: the boxes stay where they were.
        assertEquals(uneven, CharBoxAlign.align("ABC1", uneven, "XYZ9", bounds, vertical = false))
    }

    @Test
    fun fullWidthAsciiKeepsTheBoxesItsHalfWidthCharactersHad() {
        assertEquals(uneven, CharBoxAlign.align("ABC1", uneven, "ＡＢＣ１", bounds, vertical = false))
    }

    @Test
    fun fullWidthAsciiIsMatchedThroughTheAlignment() {
        // The lengths differ, so this goes through the alignment: the widened characters still pair
        // with the boxes their half-width originals had, and only 。 is new.
        val boxes = listOf(Box(0, 0, 10, 40), Box(10, 0, 25, 40))

        val aligned = CharBoxAlign.align("A1", boxes, "Ａ１。", bounds, vertical = false)

        assertEquals(boxes[0], aligned[0])
        assertEquals(boxes[1], aligned[1])
        assertEquals("。 goes after １, up to the line's edge", Box(25, 0, 100, 40), aligned[2])
    }

    @Test
    fun anEllipsisSpelledAsDotsSplitsItsOwnBox() {
        val boxes = listOf(
            Box(0, 0, 20, 40),
            Box(20, 0, 46, 40),
            Box(46, 0, 60, 40),
            Box(60, 0, 100, 40)
        )

        val aligned = CharBoxAlign.align("待って…", boxes, "待って...", bounds, vertical = false)

        assertEquals(6, aligned.size)
        // 待, っ and て were read the same both times and did not move.
        assertEquals(boxes.subList(0, 3), aligned.subList(0, 3))
        // The three dots cut the … box into equal thirds.
        assertEquals(
            listOf(Box(60, 0, 73, 40), Box(73, 0, 86, 40), Box(86, 0, 100, 40)),
            aligned.subList(3, 6)
        )
    }

    @Test
    fun aTwoDotEllipsisIsSpelledAsTwo() {
        val boxes = listOf(Box(0, 0, 20, 40), Box(20, 0, 80, 40))

        val aligned = CharBoxAlign.align("猫‥", boxes, "猫..", Box(0, 0, 80, 40), vertical = false)

        assertEquals(listOf(boxes[0], Box(20, 0, 50, 40), Box(50, 0, 80, 40)), aligned)
    }

    @Test
    fun anInsertedCharacterSitsInTheGapItsNeighboursLeft() {
        val boxes = listOf(Box(0, 0, 20, 40), Box(40, 0, 60, 40), Box(60, 0, 100, 40))

        val aligned = CharBoxAlign.align("あいう", boxes, "あXいう", bounds, vertical = false)

        // あ is 0..20, the newcomer takes the whole 20..40 gap, い and う keep their own boxes.
        assertEquals(
            listOf(Box(0, 0, 20, 40), Box(20, 0, 40, 40), Box(40, 0, 60, 40), Box(60, 0, 100, 40)),
            aligned
        )
    }

    @Test
    fun anInsertedCharacterBetweenTouchingBoxesSharesItsNeighboursSpan() {
        // A full-width line's boxes touch, so there is no gap to cut: が takes a third of the span
        // 猫 and カ held together, and ド, which is not next to the insertion, keeps its own box.
        val line = Box(0, 0, 120, 40)
        val boxes = listOf(Box(0, 0, 40, 40), Box(40, 0, 80, 40), Box(80, 0, 120, 40))

        val aligned = CharBoxAlign.align("猫カド", boxes, "猫がカド", line, vertical = false)

        assertEquals(listOf(Box(0, 0, 26, 40), Box(26, 0, 53, 40), Box(53, 0, 80, 40), boxes[2]), aligned)
    }

    @Test
    fun anInsertedCharacterAtTheStartRunsToTheLinesEdge() {
        val boxes = listOf(Box(40, 0, 100, 40))

        val aligned = CharBoxAlign.align("う", boxes, "Xう", bounds, vertical = false)

        assertEquals(listOf(Box(0, 0, 40, 40), Box(40, 0, 100, 40)), aligned)
    }

    @Test
    fun aDeletedCharacterDropsItsBoxAndLeavesTheRestAlone() {
        val boxes = listOf(Box(0, 0, 20, 40), Box(40, 0, 60, 40), Box(60, 0, 100, 40))

        val aligned = CharBoxAlign.align("あいう", boxes, "あう", bounds, vertical = false)

        // い is gone; あ and う stay where they were read, う does not shift left.
        assertEquals(listOf(boxes[0], boxes[2]), aligned)
    }

    @Test
    fun anInsertedCharacterOnATategakiLineSitsBetweenItsNeighbours() {
        val boxes = listOf(Box(0, 0, 40, 30), Box(0, 70, 40, 100))

        val aligned = CharBoxAlign.align("あい", boxes, "あXい", Box(0, 0, 40, 100), vertical = true)

        assertEquals(listOf(Box(0, 0, 40, 30), Box(0, 30, 40, 70), Box(0, 70, 40, 100)), aligned)
    }

    @Test
    fun emptyNewTextHasNoBoxes() {
        assertTrue(CharBoxAlign.align("あい", uneven.subList(0, 2), "", bounds, vertical = false).isEmpty())
    }

    @Test
    fun anEmptyDetectorReadingTilesTheLineEvenly() {
        // With no original text or boxes, each new character takes an even slice along the axis.
        val horizontal = CharBoxAlign.align("", emptyList(), "猫が水を飲む", bounds, vertical = false)
        assertEquals(
            interpolateCharBoxes("猫が水を飲む", bounds, vertical = false),
            horizontal
        )

        val verticalBoxes = CharBoxAlign.align("", emptyList(), "猫が水を飲む", Box(100, 0, 160, 120), vertical = true)
        assertEquals(
            interpolateCharBoxes("猫が水を飲む", Box(100, 0, 160, 120), vertical = true),
            verticalBoxes
        )
    }

    @Test
    fun boxesThatDoNotDescribeTheOldTextFallBackToTheEvenSplit() {
        val mismatched = listOf(Box(0, 0, 10, 40))

        assertEquals(
            interpolateCharBoxes("あいう", bounds, vertical = false),
            CharBoxAlign.align("あい", mismatched, "あいう", bounds, vertical = false)
        )
    }

    @Test
    fun anyPairOfReadingsLeavesOneBoxPerCharacterInsideTheLine() {
        val alphabet = "あいうえおカキク…‥ABC1.・\u3000 ！"
        val random = Random(7)

        // Fixed seed, many pairs: whatever a second reader returns, the result addresses it exactly.
        repeat(300) {
            val old = (0..random.nextInt(8)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val new = (0..random.nextInt(8)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            assertTilesTheLine(old, new, Box(7, 20, 97, 80), vertical = false)
            assertTilesTheLine(old, new, Box(10, 10, 60, 120), vertical = true)
        }
    }

    /** The invariant, for one pair of readings: one box per new character, all inside [line]. */
    private fun assertTilesTheLine(old: String, new: String, line: Box, vertical: Boolean) {
        val aligned = CharBoxAlign.align(old, interpolateCharBoxes(old, line, vertical), new, line, vertical)

        assertEquals("\"$old\" -> \"$new\"", new.length, aligned.size)
        aligned.forEach { box ->
            assertTrue("\"$old\" -> \"$new\": $box", box.left >= line.left && box.right <= line.right)
            assertTrue("\"$old\" -> \"$new\": $box", box.top >= line.top && box.bottom <= line.bottom)
            assertTrue("\"$old\" -> \"$new\": $box", box.width >= 1 && box.height >= 1)
        }
    }
}
