/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context

/** Stores the selected on-device text reader. Manga OCR is the default. */
object RecognitionPreference {
    private const val PREFS = "recognition"
    private const val MANGA_OCR = "manga_ocr_enabled"

    fun isMangaOcrEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(MANGA_OCR, true)

    fun setMangaOcrEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MANGA_OCR, enabled)
            .apply()
    }
}
