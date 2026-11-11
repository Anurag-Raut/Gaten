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

class DetectionRoutingTest {
    @Test
    fun `Standard never runs or selects the detector`() {
        assertFalse(DetectionRouting.shouldTryDetector(RecognitionMode.ML_KIT, true, true))
        assertEquals(DetectionSource.ML_KIT, DetectionRouting.choose(RecognitionMode.ML_KIT, 5))
    }

    @Test
    fun `Enhanced without the detector falls back to ML Kit`() {
        assertFalse(DetectionRouting.shouldTryDetector(RecognitionMode.PP_MEDIUM, false, true))
        assertEquals(DetectionSource.ML_KIT, DetectionRouting.choose(RecognitionMode.PP_MEDIUM, null))
    }

    @Test
    fun `Enhanced without the recogniser does not run the detector`() {
        assertFalse(DetectionRouting.shouldTryDetector(RecognitionMode.PP_MEDIUM, true, false))
    }

    @Test
    fun `Enhanced with both installed runs the detector`() {
        assertTrue(DetectionRouting.shouldTryDetector(RecognitionMode.PP_MEDIUM, true, true))
    }

    @Test
    fun `Enhanced uses the detector result when it has lines`() {
        assertEquals(DetectionSource.PP_DETECTOR, DetectionRouting.choose(RecognitionMode.PP_MEDIUM, 3))
    }

    @Test
    fun `Enhanced falls back when the detector failed or found nothing`() {
        assertEquals(DetectionSource.ML_KIT, DetectionRouting.choose(RecognitionMode.PP_MEDIUM, null))
        assertEquals(DetectionSource.ML_KIT, DetectionRouting.choose(RecognitionMode.PP_MEDIUM, 0))
    }
}
