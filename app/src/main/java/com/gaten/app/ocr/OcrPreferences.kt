/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context

/** OCR engine a lookup runs on. */
enum class OcrEngine { MLKIT, TESSERACT }

/**
 * The user's OCR engine choice, stored in the app's single settings file.
 *
 * The enum name is what gets persisted, so an unknown or missing value falls back to
 * [OcrEngine.MLKIT] — which is also the default before any settings UI has been opened.
 */
object OcrPreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_OCR_ENGINE = "ocr_engine"

    fun engine(context: Context): OcrEngine {
        val stored = prefs(context).getString(KEY_OCR_ENGINE, null)
        return OcrEngine.entries.firstOrNull { it.name == stored } ?: OcrEngine.MLKIT
    }

    fun setEngine(context: Context, engine: OcrEngine) {
        prefs(context).edit().putString(KEY_OCR_ENGINE, engine.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
