/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun theDefaultIsTheBundledMlKit() {
        assertEquals(RecognitionMode.ML_KIT, RecognitionMode.DEFAULT)
        assertTrue(RecognitionMode.DEFAULT.bundled)
    }

    @Test
    fun missingAndUnknownValuesUseTheBundledDefault() {
        for (value in listOf(null, "", "PP-OCRv6", "manga_ocr_enabled", "MEDIUM")) {
            assertEquals(RecognitionMode.ML_KIT, RecognitionMode.fromId(value))
        }
    }

    /**
     * The withdrawn Manga OCR ids migrate to the recommended downloadable engine instead of falling
     * to the bundled default, so a reader who had chosen one keeps a downloadable engine after the
     * upgrade rather than silently moving to ML Kit.
     */
    @Test
    fun theRetiredMangaIdsMigrateToTheRecommendedEngine() {
        assertEquals(RecognitionMode.PP_MEDIUM, RecognitionMode.fromId("manga_ocr"))
        assertEquals(RecognitionMode.PP_MEDIUM, RecognitionMode.fromId("manga"))
    }

    /** The old ML Kit-era value is the bundled mode now, not a retired alias for another engine. */
    @Test
    fun theRetiredMlKitValueMapsToTheBundledMode() {
        assertEquals(RecognitionMode.ML_KIT, RecognitionMode.fromId("ml_kit"))
    }

    @Test
    fun onlyPpMediumIsRecommended() {
        assertEquals(
            listOf(RecognitionMode.PP_MEDIUM),
            RecognitionMode.entries.filter { it.recommended }
        )
    }

    @Test
    fun theBundledModeHasNoPpEngine() {
        assertNull(RecognitionMode.ML_KIT.ppEngine())
    }

    @Test
    fun theOptionalModeMapsToItsPpEngine() {
        assertEquals(PpOcrModel.Engine.MEDIUM, RecognitionMode.PP_MEDIUM.ppEngine())
    }

    /**
     * The bridge addresses engines by these ids, so the mode vocabulary and the PP catalog's ids
     * must stay identical; a rename on either side would silently break a download or status row.
     */
    @Test
    fun theEngineIdsAreTheBridgeVocabulary() {
        assertEquals(listOf("ml_kit", "medium"), RecognitionMode.entries.map { it.id })
        assertEquals(RecognitionMode.PP_MEDIUM.id, PpOcrModel.Engine.MEDIUM.id)
    }
}
