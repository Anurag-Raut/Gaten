/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure part of [RecognitionPreference]: how a persisted value becomes a mode and how a mode
 * becomes the optional PP engine. Android storage is not touched.
 */
class RecognitionPreferenceTest {

    @Test
    fun persistedIdsRoundTrip() {
        for (mode in RecognitionMode.entries) {
            assertEquals(mode, RecognitionMode.fromId(mode.id))
        }
    }

    @Test
    fun missingLegacyAndUnknownValuesUseTheBundledDefault() {
        for (value in listOf(null, "", "ml_kit", "PP-OCRv6", "manga_ocr_enabled", "MEDIUM")) {
            assertEquals(RecognitionMode.MANGA_OCR, RecognitionMode.fromId(value))
        }
    }

    @Test
    fun theBundledDefaultHasNoPpEngine() {
        assertNull(RecognitionMode.MANGA_OCR.ppEngine())
    }

    @Test
    fun theManualModesMapToTheirPpEngine() {
        assertEquals(PpOcrModel.Engine.MEDIUM, RecognitionMode.PP_MEDIUM.ppEngine())
        assertEquals(PpOcrModel.Engine.MANGA, RecognitionMode.PP_MANGA.ppEngine())
    }

    /**
     * The bridge addresses engines by these ids, so the mode vocabulary and the PP catalog's ids
     * must stay identical; a rename on either side would silently break a download or status row.
     */
    @Test
    fun theEngineIdsAreTheBridgeVocabulary() {
        assertEquals(listOf("manga_ocr", "medium", "manga"), RecognitionMode.entries.map { it.id })
        assertEquals(RecognitionMode.PP_MEDIUM.id, PpOcrModel.Engine.MEDIUM.id)
        assertEquals(RecognitionMode.PP_MANGA.id, PpOcrModel.Engine.MANGA.id)
    }
}
