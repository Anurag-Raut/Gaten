/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPreprocessTest {

    @Test
    fun theBoxIsGrownByTenPercentOfItsHeight() {
        // 40 px tall, so 4 px of padding on every side.
        val crop = OcrPreprocess.cropFor(Box(100, 100, 300, 140), 1000, 1000)

        assertEquals(96, crop.left)
        assertEquals(96, crop.top)
        assertEquals(208, crop.width)
        assertEquals(48, crop.height)
    }

    @Test
    fun aShortLineIsUpscaledToTheMinimumHeight() {
        // 12 px tall: x4, which lands exactly on the 48 px minimum.
        val crop = OcrPreprocess.cropFor(Box(10, 10, 60, 20), 1000, 1000)

        assertEquals(52, crop.width)
        assertEquals(12, crop.height)
        assertEquals(4f, crop.scale, 1e-4f)
        assertEquals(208, crop.scaledWidth)
        assertEquals(48, crop.scaledHeight)
    }

    @Test
    fun paddingIsClampedToTheBitmap() {
        // The box touches the top left corner, so there is nothing to pad into.
        val crop = OcrPreprocess.cropFor(Box(0, 0, 50, 20), 1000, 1000)

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(52, crop.width)
        assertEquals(22, crop.height)
        assertEquals(48, crop.scaledHeight)
    }

    @Test
    fun theLongestSideIsCapped() {
        // 2000 px wide at x1 would be 2000 px of input; the cap scales it down to 1024 instead.
        val crop = OcrPreprocess.cropFor(Box(0, 0, 2000, 100), 2000, 1000)

        assertEquals(2000, crop.width)
        assertEquals(1024, crop.scaledWidth)
        assertEquals(56, crop.scaledHeight)
    }

    @Test
    fun anEmptyBoxStillYieldsACrop() {
        // A degenerate box (a zero-width selection, say) must not produce a zero-sized bitmap.
        val crop = OcrPreprocess.cropFor(Box(5, 5, 5, 5), 1000, 1000)

        assertEquals(2, crop.width)
        assertEquals(2, crop.height)
        assertEquals(48, crop.scaledWidth)
        assertEquals(48, crop.scaledHeight)
    }

    @Test
    fun aBoxOutsideTheBitmapStillYieldsACrop() {
        val crop = OcrPreprocess.cropFor(Box(5000, 5000, 5100, 5100), 100, 100)

        assertTrue(crop.width >= 1)
        assertTrue(crop.height >= 1)
        assertEquals(48, crop.scaledWidth)
        assertEquals(48, crop.scaledHeight)
    }
}
