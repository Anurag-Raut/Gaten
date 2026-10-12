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

/**
 * The circle fallback order, without Android: ML Kit's lines when it found Japanese in them, the
 * selection box read by manga-ocr when it did not, and ML Kit's own output when that read produced
 * nothing.
 */
class RegionReadPolicyTest {

    private val selection = Box(100, 200, 400, 260)

    private fun mlKitLine(text: String) = OcrLine(text, Box(0, 0, 300, 60))

    @Test
    fun mlKitLinesWithJapaneseAreTheRegionsLines() {
        assertTrue(RegionReadPolicy.hasJapaneseLines(listOf(mlKitLine("Hello"), mlKitLine("猫が水を飲む"))))
        assertTrue(RegionReadPolicy.hasJapaneseLines(listOf(mlKitLine("Lv.5 勇者"))))
    }

    @Test
    fun aRegionWithoutJapaneseIsReadFromTheSelectionBox() {
        assertFalse(RegionReadPolicy.hasJapaneseLines(emptyList()))
        assertFalse(RegionReadPolicy.hasJapaneseLines(listOf(mlKitLine("Hello world"))))
        assertFalse(RegionReadPolicy.hasJapaneseLines(listOf(mlKitLine("11:21"), mlKitLine("OK"))))
    }

    @Test
    fun theSelectionBoxReadingIsTheRegionsOneLine() {
        val read = RegionReadPolicy.fallbackLines(emptyList(), selection, "猫が水を飲む")

        assertEquals(listOf(OcrLine("猫が水を飲む", selection)), read)
    }

    @Test
    fun theSelectionBoxReadingReplacesWhatMlKitReadWithoutJapanese() {
        val mlKit = listOf(mlKitLine("Hello world"))

        val read = RegionReadPolicy.fallbackLines(mlKit, selection, "猫が水を飲む")

        assertEquals(listOf(OcrLine("猫が水を飲む", selection)), read)
    }

    @Test
    fun mlKitsOwnLinesAreKeptWhenTheModelReadNothing() {
        val mlKit = listOf(mlKitLine("Hello world"), mlKitLine("11:21"))

        assertEquals(mlKit, RegionReadPolicy.fallbackLines(mlKit, selection, null))
        assertEquals(mlKit, RegionReadPolicy.fallbackLines(mlKit, selection, "  "))
        assertEquals(mlKit, RegionReadPolicy.fallbackLines(mlKit, selection, "□"))
        assertEquals(emptyList<OcrLine>(), RegionReadPolicy.fallbackLines(emptyList(), selection, null))
    }
}
