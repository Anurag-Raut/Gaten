/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ocr

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
}
