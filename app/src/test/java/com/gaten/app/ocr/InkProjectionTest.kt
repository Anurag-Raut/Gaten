/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkProjectionTest {

    @Test
    fun tightenedSpanEqualsTheInkExtent() {
        val gray = canvas(60, 20, background = 255)
        block(gray, 60, 10, 20, 4, 16, value = 0)
        block(gray, 60, 25, 35, 4, 16, value = 0)
        block(gray, 60, 45, 55, 4, 16, value = 0)

        val span = InkProjection.tighten(gray, 60, 20, vertical = false)

        assertEquals(InkProjection.Tightened(10, 55, 4, 16), span)
    }

    @Test
    fun invertedPolarityIsTightenedTheSameWay() {
        // White text on a dark background: the polarity must be read from the crop, not assumed.
        val gray = canvas(60, 20, background = 20)
        block(gray, 60, 10, 20, 4, 16, value = 235)
        block(gray, 60, 25, 35, 4, 16, value = 235)
        block(gray, 60, 45, 55, 4, 16, value = 235)

        assertEquals(InkProjection.Tightened(10, 55, 4, 16), InkProjection.tighten(gray, 60, 20, false))
    }

    @Test
    fun lightTextOnAMidGreyBackgroundIsInk() {
        // The Otsu split sits between the two levels, well above the 128-unit contrast, so treating
        // it as a contrast threshold would find no ink at all here.
        val gray = canvas(60, 20, background = 128)
        block(gray, 60, 10, 20, 4, 16, value = 255)
        block(gray, 60, 25, 35, 4, 16, value = 255)
        block(gray, 60, 45, 55, 4, 16, value = 255)

        assertEquals(InkProjection.Tightened(10, 55, 4, 16), InkProjection.tighten(gray, 60, 20, false))
    }

    @Test
    fun aBlankCropHasNoInk() {
        assertNull(InkProjection.tighten(canvas(40, 12, background = 200), 40, 12, vertical = false))
    }

    @Test
    fun aVerticalLineIsProfiledDownItsHeight() {
        val gray = canvas(20, 60, background = 255)
        block(gray, 20, 4, 16, 10, 20, value = 0)
        block(gray, 20, 4, 16, 25, 35, value = 0)
        block(gray, 20, 4, 16, 45, 55, value = 0)

        assertEquals(InkProjection.Tightened(10, 55, 4, 16), InkProjection.tighten(gray, 20, 60, vertical = true))
    }

    @Test
    fun boundariesSnapIntoTheGapsBetweenUnevenGlyphs() {
        // Three blocks of 26/20/38 px, gaps at 30..35 and 56..61. The even boundaries land at 36
        // and 68, inside glyphs, so the snap has to move into the gaps to 35 and 61. The glyph rows
        // stay off the crop's top and bottom border, which is what the background median reads.
        val gray = canvas(110, 20, background = 255)
        block(gray, 110, 4, 30, 4, 16, value = 0)
        block(gray, 110, 36, 56, 4, 16, value = 0)
        block(gray, 110, 62, 100, 4, 16, value = 0)

        val span = InkProjection.tighten(gray, 110, 20, vertical = false)!!
        assertEquals(InkProjection.Tightened(4, 100, 4, 16), span)

        val cells = InkProjection.charCells(gray, 110, 20, vertical = false, span = span, count = 3)

        assertEquals(listOf(4 until 35, 35 until 61, 61 until 100), cells)
    }

    @Test
    fun evenlyInkedCellsKeepTheirEvenBoundaries() {
        val gray = canvas(16, 8, background = 255)
        block(gray, 16, 0, 16, 2, 6, value = 0)

        val span = InkProjection.Tightened(0, 16, 2, 6)
        val cells = InkProjection.charCells(gray, 16, 8, vertical = false, span = span, count = 2)

        assertEquals(listOf(0 until 8, 8 until 16), cells)
    }

    @Test
    fun aSpanTooShortForItsCellsIsRefused() {
        val gray = canvas(8, 4, background = 255)
        block(gray, 8, 0, 8, 0, 4, value = 0)

        assertNull(
            InkProjection.charCells(gray, 8, 4, vertical = false, span = InkProjection.Tightened(0, 3, 0, 4), count = 5)
        )
    }

    private fun canvas(w: Int, h: Int, background: Int) = IntArray(w * h) { background }

    private fun block(gray: IntArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int, value: Int) {
        for (y in y0 until y1) {
            for (x in x0 until x1) gray[y * w + x] = value
        }
    }
}
