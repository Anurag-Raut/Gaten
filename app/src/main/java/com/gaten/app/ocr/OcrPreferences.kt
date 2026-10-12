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
    private const val KEY_MANGA_OCR = "manga_ocr_enabled"

    fun engine(context: Context): OcrEngine {
        val stored = prefs(context).getString(KEY_OCR_ENGINE, null)
        return OcrEngine.entries.firstOrNull { it.name == stored } ?: OcrEngine.MLKIT
    }

    fun setEngine(context: Context, engine: OcrEngine) {
        prefs(context).edit().putString(KEY_OCR_ENGINE, engine.name).apply()
    }

    /**
     * Whether the optional manga-ocr model may re-read the lines ML Kit read badly. On by default:
     * the model does nothing at all until it is installed, and once it is, the reader asked for it.
     */
    fun mangaOcrEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MANGA_OCR, true)

    fun setMangaOcrEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MANGA_OCR, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
