/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpOcrDetectorTest {
    private fun map(width: Int, height: Int) = FloatArray(width * height)

    private fun FloatArray.fill(width: Int, x0: Int, y0: Int, x1: Int, y1: Int, value: Float) {
        for (y in y0..y1) for (x in x0..x1) this[y * width + x] = value
    }

    @Test
    fun `a rectangle blob becomes its unclipped box in source pixels`() {
        val m = map(100, 50)
        m.fill(100, 20, 10, 59, 19, 0.9f) // 40 x 10 map pixels
        // offset = 40*10*1.4 / (2*50) = 5.6 -> map [14.4, 4.4, 65.6, 25.6]; x2 to a 200x100 source.
        val boxes = DbPostProcess.boxes(m, 100, 50, 200, 100)
        assertEquals(listOf(Box(28, 8, 132, 52)), boxes)
    }

    @Test
    fun `unclip grows the box beyond the raw blob`() {
        val m = map(100, 50)
        m.fill(100, 20, 10, 59, 19, 0.9f)
        val box = DbPostProcess.boxes(m, 100, 50, 100, 50).single()
        assertTrue(box.left < 20 && box.top < 10 && box.right > 60 && box.bottom > 20)
    }

    @Test
    fun `two separate blobs give two boxes`() {
        val m = map(100, 60)
        m.fill(100, 10, 5, 80, 14, 0.9f)
        m.fill(100, 10, 35, 60, 44, 0.9f)
        val boxes = DbPostProcess.boxes(m, 100, 60, 100, 60)
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].top < boxes[1].top)
    }

    @Test
    fun `a blob scoring under the box threshold is dropped and one above is kept`() {
        val low = map(100, 50).also { it.fill(100, 20, 10, 59, 19, 0.4f) }
        val high = map(100, 50).also { it.fill(100, 20, 10, 59, 19, 0.5f) }
        assertTrue(DbPostProcess.boxes(low, 100, 50, 100, 50).isEmpty())
        assertEquals(1, DbPostProcess.boxes(high, 100, 50, 100, 50).size)
    }

    @Test
    fun `values at or under the binarisation threshold are not text`() {
        val m = map(100, 50).also { it.fill(100, 20, 10, 59, 19, DbPostProcess.THRESH) }
        assertTrue(DbPostProcess.boxes(m, 100, 50, 100, 50).isEmpty())
    }

    @Test
    fun `a blob thinner than the minimum size is dropped`() {
        val m = map(100, 50).also { it.fill(100, 10, 10, 60, 11, 0.9f) } // 2 px tall
        assertTrue(DbPostProcess.boxes(m, 100, 50, 100, 50).isEmpty())
    }

    @Test
    fun `diagonal pixels join one component`() {
        val m = map(100, 50)
        for (i in 0 until 6) m.fill(100, 10 + i * 5, 10 + i, 14 + i * 5, 12 + i, 0.9f)
        assertEquals(1, DbPostProcess.components(m, 100, 50).size)
    }

    @Test
    fun `boxes are mapped to the source size and clamped to it`() {
        val m = map(64, 32)
        m.fill(64, 0, 0, 63, 9, 0.95f) // full-width band at the top edge
        // Source is 1080 x 540: x by 16.875, y by 16.875.
        val box = DbPostProcess.boxes(m, 64, 32, 1080, 540).single()
        assertEquals(0, box.left)
        assertEquals(0, box.top)
        assertEquals(1080, box.right)
        assertTrue(box.bottom in 170..540)
    }

    @Test
    fun `a tall vertical blob yields a tall box without failing`() {
        val m = map(60, 100)
        m.fill(60, 20, 10, 27, 89, 0.9f) // 8 x 80
        val box = DbPostProcess.boxes(m, 60, 100, 60, 100).single()
        assertTrue(box.height > box.width * 3)
    }

    @Test
    fun `an empty map or bad sizes give no boxes`() {
        assertTrue(DbPostProcess.boxes(map(10, 10), 10, 10, 100, 100).isEmpty())
        assertTrue(DbPostProcess.boxes(FloatArray(4), 10, 10, 100, 100).isEmpty())
        assertTrue(DbPostProcess.boxes(map(10, 10), 0, 10, 100, 100).isEmpty())
    }

    @Test
    fun `horizontal text reads top to bottom and left to right within a row`() {
        val right = Box(200, 12, 300, 40)
        val left = Box(10, 10, 100, 40)
        val below = Box(10, 100, 120, 130)
        assertEquals(listOf(left, right, below), DbPostProcess.readingOrder(listOf(below, right, left)))
    }

    @Test
    fun `vertical text reads columns from the right edge to the left`() {
        val rightTop = Box(300, 10, 340, 200)
        val rightLower = Box(302, 240, 338, 400)
        val leftColumn = Box(100, 10, 140, 300)
        assertEquals(
            listOf(rightTop, rightLower, leftColumn),
            DbPostProcess.readingOrder(listOf(leftColumn, rightLower, rightTop))
        )
    }

    @Test
    fun `the input is a multiple of 32 on both sides and bounded without upscaling`() {
        val tall = PpOcrDetGeometry.plan(1080, 2400)
        assertTrue(tall.width % 32 == 0 && tall.height % 32 == 0)
        assertTrue(tall.height <= PpOcrDetGeometry.MAX_SIDE && tall.width <= PpOcrDetGeometry.MAX_SIDE)

        val small = PpOcrDetGeometry.plan(300, 200)
        assertEquals(PpOcrDetPlan(288, 192), small)

        val tiny = PpOcrDetGeometry.plan(5, 5)
        assertEquals(PpOcrDetPlan(32, 32), tiny)
    }

    @Test
    fun `planes are blue green red, each normalised with its own mean and std`() {
        // ARGB: R=0x10, G=0x20, B=0x30
        val input = PpOcrDetPreprocess.normalise(intArrayOf(0xFF102030.toInt()), 1, 1)
        fun expected(value: Int, plane: Int) =
            (value / 255f - PpOcrDetPreprocess.MEAN[plane]) / PpOcrDetPreprocess.STD[plane]
        assertEquals(expected(0x30, 0), input[0], 1e-5f)
        assertEquals(expected(0x20, 1), input[1], 1e-5f)
        assertEquals(expected(0x10, 2), input[2], 1e-5f)
        assertEquals((0x30 / 255f - 0.485f) / 0.229f, input[0], 1e-5f)
    }
}
