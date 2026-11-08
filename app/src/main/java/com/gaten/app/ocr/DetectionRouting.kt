/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/** Who found the lines of a screenshot. */
internal enum class DetectionSource { ML_KIT, PP_DETECTOR }

/**
 * Which detector finds a screenshot's lines. Pure, so the rule is unit-tested without a device.
 *
 * ML Kit is the default and the fallback. The PP-OCRv6 detector is used only in
 * [RecognitionMode.PP_MEDIUM], when both it and the recogniser are on disk (its boxes carry no text,
 * so a detector without a recogniser reads nothing), and only if it produced something usable.
 */
internal object DetectionRouting {
    /** True when the PP detector should be run at all; [RecognitionMode.ML_KIT] never runs it. */
    fun shouldTryDetector(
        mode: RecognitionMode,
        detectorInstalled: Boolean,
        recognizerInstalled: Boolean
    ): Boolean = mode == RecognitionMode.PP_MEDIUM && detectorInstalled && recognizerInstalled

    /**
     * The source whose lines are used, given what the PP path produced: [ppLineCount] is null when it
     * was not tried or failed, and the number of boxes otherwise. Zero lines is an empty
     * result and falls back to ML Kit like every other failure.
     */
    fun choose(mode: RecognitionMode, ppLineCount: Int?): DetectionSource =
        if (mode == RecognitionMode.PP_MEDIUM && ppLineCount != null && ppLineCount > 0) {
            DetectionSource.PP_DETECTOR
        } else {
            DetectionSource.ML_KIT
        }
}
