/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.ocr.RecognitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The pure seam of engine routing: the exact-crop cache key must change when the selected mode or an
 * installed model revision does, so a reading is never served after a switch or a re-download.
 */
class RecognitionRoutingTest {

    @Test
    fun theCacheRevisionChangesWithTheMode() {
        val bundled = recognitionRevision(RecognitionMode.ML_KIT, "manga", "pp")
        assertNotEquals(bundled, recognitionRevision(RecognitionMode.MANGA_OCR, "manga", "pp"))
        assertNotEquals(bundled, recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "pp"))
        assertNotEquals(bundled, recognitionRevision(RecognitionMode.PP_MANGA, "manga", "pp"))
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "pp"),
            recognitionRevision(RecognitionMode.PP_MANGA, "manga", "pp")
        )
    }

    @Test
    fun theCacheRevisionChangesWithTheInstalledPpRevision() {
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@a"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@b")
        )
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@a"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@a;manga@a")
        )
    }

    @Test
    fun theCacheRevisionChangesWithTheInstalledMangaFiles() {
        assertNotEquals(
            recognitionRevision(RecognitionMode.MANGA_OCR, "old", ""),
            recognitionRevision(RecognitionMode.MANGA_OCR, "new", "")
        )
    }

    @Test
    fun theSameInputsGiveTheSameRevision() {
        assertEquals(
            recognitionRevision(RecognitionMode.PP_MANGA, "manga", "manga@x"),
            recognitionRevision(RecognitionMode.PP_MANGA, "manga", "manga@x")
        )
    }
}
