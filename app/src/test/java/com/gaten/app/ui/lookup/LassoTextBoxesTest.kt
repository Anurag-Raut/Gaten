/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.ui.geometry.Offset
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.ScreenLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [polygonContains] (the even-odd point test) and [lassoTextBoxes] (the text a loop selected). */
class LassoTextBoxesTest {

    @Test
    fun aPointInsideASquareIsContainedAndOneOutsideIsNot() {
        val square = square(0f, 0f, 10f, 10f)

        assertTrue(polygonContains(square, 5f, 5f))
        assertFalse(polygonContains(square, 15f, 5f))
    }

    @Test
    fun aPolygonOfFewerThanThreePointsContainsNothing() {
        assertFalse(polygonContains(emptyList(), 0f, 0f))
        assertFalse(polygonContains(listOf(Offset(0f, 0f), Offset(10f, 10f)), 1f, 1f))
    }

    @Test
    fun aPointInTheNotchOfAConcavePolygonIsOutside() {
        // An L: a horizontal arm right along the top, a vertical arm down the left. The square
        // below and right of the elbow is the notch, and it is outside the loop.
        val l = listOf(
            Offset(0f, 0f),
            Offset(10f, 0f),
            Offset(10f, 4f),
            Offset(4f, 4f),
            Offset(4f, 10f),
            Offset(0f, 10f)
        )

        assertTrue(polygonContains(l, 2f, 2f)) // the elbow, inside the L
        assertTrue(polygonContains(l, 7f, 2f)) // the horizontal arm
        assertFalse(polygonContains(l, 7f, 7f)) // the notch, outside the L
    }

    @Test
    fun aLoopAroundTheMiddleCharactersReturnsOneBoxSpanningExactlyThem() {
        // Each character is 20 px wide and its centre sits at x = 10, 30, 50, 70, so the loop
        // covers the two middle characters and neither of the outer ones.
        val line = screenLine("abcd", top = 0, charWidth = 20, charHeight = 20)

        val boxes = lassoTextBoxes(listOf(line), square(20f, 0f, 60f, 20f))

        assertEquals(listOf(Box(20, 0, 60, 20)), boxes)
    }

    @Test
    fun aLineWithNoCharacterCentreInsideReturnsNothing() {
        val line = screenLine("abcd", top = 0, charWidth = 20, charHeight = 20)

        assertTrue(lassoTextBoxes(listOf(line), square(100f, 100f, 200f, 200f)).isEmpty())
    }

    @Test
    fun aLineWhoseCharBoxesDoNotMatchItsTextIsSkipped() {
        // Three characters, two boxes: the one-per-character invariant is broken, so nothing on
        // the line can be addressed and it contributes no box.
        val mismatched = ScreenLine(
            text = "abc",
            bounds = Box(0, 0, 60, 20),
            vertical = false,
            charBoxes = listOf(Box(0, 0, 20, 20), Box(20, 0, 40, 20))
        )
        val valid = screenLine("ab", top = 0, charWidth = 20, charHeight = 20)

        val boxes = lassoTextBoxes(listOf(mismatched, valid), square(0f, 0f, 40f, 20f))

        assertEquals(listOf(Box(0, 0, 40, 20)), boxes)
    }

    @Test
    fun whitespaceInsideTheLoopDoesNotWidenTheBox() {
        // "abc d": a, b, c and the space all fall inside the loop, but only a, b and c count, so
        // the box stops at c's right edge (60) instead of stretching over the space to 80.
        val line = screenLine("abc d", top = 0, charWidth = 20, charHeight = 20)

        val boxes = lassoTextBoxes(listOf(line), square(0f, 0f, 80f, 20f))

        assertEquals(listOf(Box(0, 0, 60, 20)), boxes)
    }

    @Test
    fun twoEnclosedLinesReturnTheirBoxesInOrder() {
        val first = screenLine("ab", top = 0, charWidth = 20, charHeight = 20)
        val second = screenLine("cd", top = 40, charWidth = 20, charHeight = 20)

        val boxes = lassoTextBoxes(listOf(first, second), square(0f, -10f, 40f, 60f))

        assertEquals(listOf(Box(0, 0, 40, 20), Box(0, 40, 40, 60)), boxes)
    }

    /** A rectangle as a clockwise loop, for the point-in-polygon tests. */
    private fun square(left: Float, top: Float, right: Float, bottom: Float): List<Offset> =
        listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(right, bottom),
            Offset(left, bottom)
        )
}
