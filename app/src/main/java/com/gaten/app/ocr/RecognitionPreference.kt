/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context

/** Manga OCR is required; persisted legacy ML Kit selections no longer disable it. */
object RecognitionPreference {
    fun isMangaOcrEnabled(context: Context): Boolean = true

    fun setMangaOcrEnabled(context: Context, enabled: Boolean) {
        require(enabled) { "Manga OCR is required for text recognition." }
    }
}
