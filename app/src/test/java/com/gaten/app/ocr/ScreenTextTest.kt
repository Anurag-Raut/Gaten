/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {

    @Test
    fun aHorizontalLineIsCutIntoEvenSlices() {
        val boxes = interpolateCharBoxes("日本語", Box(0, 10, 90, 70), vertical = false)

        assertEquals(listOf(Box(0, 10, 30, 70), Box(30, 10, 60, 70), Box(60, 10, 90, 70)), boxes)
    }

    @Test
    fun aVerticalLineIsCutDownItsHeight() {
        val bounds = Box(100, 0, 160, 100)

        // Every slice stays as wide as the line; only the height is divided.
        assertEquals(
            listOf(Box(100, 0, 160, 50), Box(100, 50, 160, 100)),
            interpolateCharBoxes("あい", bounds, vertical = true)
        )
    }

    @Test
    fun everyCharacterGetsABoxThatTilesTheLine() {
        // 90 px over 7 characters is not a whole number of pixels per character.
        val bounds = Box(7, 20, 97, 80)
        val text = "にほんごのぶん"

        val boxes = interpolateCharBoxes(text, bounds, vertical = false)

        assertEquals(text.length, boxes.size)
        assertEquals(bounds.left, boxes.first().left)
        assertEquals(bounds.right, boxes.last().right)
        boxes.zipWithNext { left, right -> assertEquals(left.right, right.left) }
        boxes.forEach { box ->
            assertEquals(bounds.top, box.top)
            assertEquals(bounds.bottom, box.bottom)
            assertTrue(box.width > 0)
        }
    }

    @Test
    fun blankTextHasNoBoxes() {
        assertTrue(interpolateCharBoxes("", Box(0, 0, 100, 50), vertical = false).isEmpty())
        assertTrue(interpolateCharBoxes("", Box(0, 0, 100, 50), vertical = true).isEmpty())
    }

    @Test
    fun japaneseTextIsFullWidth() {
        assertTrue(isFullWidthText("日本語の勉強は楽しいです"))
        assertTrue(isFullWidthText("コンピュータを使っています"))
        assertTrue(isFullWidthText("「猫が水を飲む。」"))
        assertTrue(isFullWidthText("ＡＢＣ１２３"))
    }

    @Test
    fun latinDigitsAndSpacesAreNotFullWidth() {
        assertFalse(isFullWidthText("11:21"))
        assertFalse(isFullWidthText("猫 cat"))
        assertFalse(isFullWidthText("日本語123"))
        assertFalse(isFullWidthText(""))
    }

    @Test
    fun replacingALinesTextKeepsTheBoxesOfTheCharactersItShares() {
        val bounds = Box(0, 10, 120, 70)
        val detectorBoxes = interpolateCharBoxes("描が水", bounds, vertical = false)
        val line = ScreenLine("描が水", bounds, vertical = false, charBoxes = detectorBoxes)

        // A second reader gives back a longer string: the boxes still have to address it.
        val replaced = line.withText("猫が水を飲む")

        assertEquals("猫が水を飲む", replaced.text)
        assertEquals(replaced.text.length, replaced.charBoxes.size)
        assertEquals("the line itself does not move", bounds, replaced.bounds)
        // 描 read as 猫 is a different character on the same glyph, so it keeps its box, and so does
        // が. 水 has no room after it for the three new characters, so they share its span with it.
        assertEquals(detectorBoxes[0], replaced.charBoxes[0])
        assertEquals(detectorBoxes[1], replaced.charBoxes[1])
        assertEquals(detectorBoxes[2].left, replaced.charBoxes[2].left)
        // The characters ML Kit never saw sit at the end of the line, inside it.
        assertEquals(bounds.right, replaced.charBoxes.last().right)
        replaced.charBoxes.forEach { assertTrue(it.left >= bounds.left && it.right <= bounds.right) }
    }

    @Test
    fun aTategakiLineIsRebuiltDownItsHeight() {
        val bounds = Box(100, 0, 160, 120)
        val detectorBoxes = interpolateCharBoxes("描が", bounds, vertical = true)
        val line = ScreenLine("描が", bounds, vertical = true, charBoxes = detectorBoxes)

        val replaced = line.withText("猫が水を飲む")

        assertEquals(replaced.text.length, replaced.charBoxes.size)
        // 猫 keeps its own box; が shares its span with the new characters that stack after it, up to
        // the line's own edge.
        assertEquals(detectorBoxes[0], replaced.charBoxes[0])
        assertEquals(detectorBoxes[1].top, replaced.charBoxes[1].top)
        assertEquals(bounds.left, replaced.charBoxes.first().left)
        assertEquals(bounds.right, replaced.charBoxes.last().right)
        assertEquals(bounds.bottom, replaced.charBoxes.last().bottom)
        replaced.charBoxes.forEach { assertTrue(it.top >= bounds.top && it.bottom <= bounds.bottom) }
    }

    @Test
    fun replacingOneLineLeavesTheOthersAlone() {
        val first = ScreenLine("猫", Box(0, 0, 40, 60), false, interpolateCharBoxes("猫", Box(0, 0, 40, 60), false))
        val second = ScreenLine("描が水", Box(0, 100, 120, 160), false, interpolateCharBoxes("描が水", Box(0, 100, 120, 160), false))
        val screen = ScreenText(listOf(first, second), width = 1080, height = 2400)

        val updated = screen.withLineText(1, "猫が水を飲む")

        assertEquals("猫が水を飲む", updated.lines[1].text)
        assertEquals(first, updated.lines[0])
        assertEquals(screen.width, updated.width)
        assertEquals(screen.height, updated.height)
    }

    @Test
    fun replacingALineThatIsNotThereChangesNothing() {
        val screen = ScreenText(listOf(screenLine()), width = 1080, height = 2400)

        assertEquals(screen, screen.withLineText(9, "猫"))
    }

    private fun screenLine() = ScreenLine("猫", Box(0, 0, 40, 60), false, interpolateCharBoxes("猫", Box(0, 0, 40, 60), false))
}
