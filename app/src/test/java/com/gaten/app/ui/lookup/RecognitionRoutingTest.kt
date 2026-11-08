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
        val bundled = recognitionRevision(RecognitionMode.ML_KIT, "pp")
        assertNotEquals(bundled, recognitionRevision(RecognitionMode.PP_MEDIUM, "pp"))
    }

    @Test
    fun theCacheRevisionChangesWithTheInstalledPpRevision() {
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "medium@a"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "medium@b")
        )
    }

    @Test
    fun theSameInputsGiveTheSameRevision() {
        assertEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "medium@x"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "medium@x")
        )
    }
}
