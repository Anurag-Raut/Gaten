/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * YomiLens: Japanese screen lookup for immersion learners.
 *
 * Freehand circle / drag-select a region -> on-device Japanese OCR (ML Kit, Tesseract fallback)
 * -> offline JMdict lookup with deinflection -> result panel with real dictionary entries.
 */

package com.akslabs.circletosearch.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionSnapTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        OcrLine(text, Box(left, top, right, bottom))

    @Test
    fun aSelectionTouchingPartOfALineStillReturnsThatWholeLine() {
        // 猫が水を飲む spans x 100..700, but the user's loop stops at 猫が水を飲 (x 100..520).
        val gesture = Box(100, 200, 520, 260)
        val lines = listOf(line("猫が水を飲む", 100, 200, 700, 260))

        val chosen = SelectionSnap.selectLines(lines, gesture)

        assertEquals(1, chosen.size)
        assertEquals("猫が水を飲む", chosen.single().text)
    }

    @Test
    fun aSelectionOverlappingTwoLinesReturnsBoth() {
        val gesture = Box(0, 0, 400, 300)
        val lines = listOf(
            line("一行目", 0, 10, 300, 80),
            line("二行目", 0, 200, 300, 270),
            line("三行目", 0, 500, 300, 570)
        )

        assertEquals(listOf("一行目", "二行目"), SelectionSnap.selectLines(lines, gesture).map { it.text })
    }

    @Test
    fun aGestureBetweenLinesFallsBackToTheNearestLine() {
        val gesture = Box(0, 90, 300, 150) // in the gutter between the two lines
        val lines = listOf(
            line("上の行", 0, 10, 300, 80),
            line("下の行", 0, 200, 300, 270)
        )

        val chosen = SelectionSnap.selectLines(lines, gesture)

        assertEquals(listOf("上の行"), chosen.map { it.text })
    }

    @Test
    fun noLinesMeansNoSelection() {
        assertTrue(SelectionSnap.selectLines(emptyList(), Box(0, 0, 10, 10)).isEmpty())
    }

    @Test
    fun unionBoundsCoversEveryLine() {
        val lines = listOf(
            line("一行目", 10, 100, 200, 150),
            line("二行目", 30, 200, 260, 260)
        )

        assertEquals(Box(10, 100, 260, 260), SelectionSnap.unionBounds(lines))
    }

    @Test
    fun searchRegionClampsToTheBitmap() {
        val selection = Box(5, 5, 100, 60)

        val region = SelectionSnap.searchRegion(selection, 50, 40, bitmapWidth = 1080, bitmapHeight = 2400)

        assertEquals(0, region.left)
        assertEquals(0, region.top)
        assertEquals(150, region.right)
        assertEquals(100, region.bottom)
    }

    @Test
    fun marginsScaleWithTheSelectionButStayBounded() {
        val tiny = Box(500, 500, 510, 510)
        val wide = Box(0, 500, 1080, 512)

        // Never smaller than the floor, never wider than a third of the screen.
        assertTrue(SelectionSnap.horizontalMargin(tiny, 1080) >= 16)
        assertEquals(360, SelectionSnap.horizontalMargin(wide, 1080))

        // Vertical margin stays well inside one line pitch so the next line is not dragged in.
        assertEquals(16, SelectionSnap.verticalMargin(tiny))
        assertTrue(SelectionSnap.verticalMargin(Box(0, 0, 100, 400)) <= 96)
    }

    @Test
    fun translateMovesLineBoundsIntoBitmapCoordinates() {
        val regionLocal = listOf(line("猫が水を飲む", 0, 0, 600, 60))

        val onScreen = SelectionSnap.translate(regionLocal, dx = 100, dy = 200)

        assertEquals(Box(100, 200, 700, 260), onScreen.single().bounds)
        assertEquals("猫が水を飲む", onScreen.single().text)
    }

    @Test
    fun padAndClampKeepsTheBoxInsideTheBitmap() {
        val box = Box(2, 3, 1078, 2398)

        assertEquals(Box(0, 0, 1080, 2400), SelectionSnap.padAndClamp(box, 6, 6, 1080, 2400))
    }

    @Test
    fun boxesThatOnlyTouchEdgesDoNotOverlap() {
        assertFalse(Box(0, 0, 100, 100).overlaps(Box(100, 0, 200, 100)))
        assertTrue(Box(0, 0, 101, 100).overlaps(Box(100, 0, 200, 100)))
    }
}
