/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context

/**
 * The recognizer every lookup reads with, chosen by the reader.
 *
 * [MANGA_OCR] is the default, [PP_MEDIUM] and [PP_MANGA] the PP-OCRv6 engines: [PP_MEDIUM] suits
 * screen text and novels, [PP_MANGA] suits manga, stylized lettering and video thumbnails. No engine
 * ships in the app — each is an explicit download in Settings that works offline afterwards,
 * and so a mode can be selected before its model is installed. There is no automatic image
 * classifier: the mode is the reader's manual choice, and an unreadable selected engine falls back
 * to Manga OCR at read time when Manga OCR is installed.
 */
enum class RecognitionMode(val id: String) {
    MANGA_OCR("manga_ocr"),
    PP_MEDIUM("medium"),
    PP_MANGA("manga");

    /** The PP-OCR engine this mode reads with, or null for Manga OCR. */
    fun ppEngine(): PpOcrModel.Engine? = when (this) {
        MANGA_OCR -> null
        PP_MEDIUM -> PpOcrModel.Engine.MEDIUM
        PP_MANGA -> PpOcrModel.Engine.MANGA
    }

    companion object {
        /** The default mode; its model is downloaded on demand like the others. */
        val DEFAULT = MANGA_OCR

        /** Unknown or legacy values, including a missing one, mean the default mode. */
        fun fromId(id: String?): RecognitionMode =
            RecognitionMode.entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Stores the selected recognition mode.
 *
 * The legacy enabled flag is ignored: recognition is always required, and the stored mode is the
 * only switch. The prefs file is the one the old ML Kit toggle used, so upgrading does not leave a
 * second store behind.
 */
object RecognitionPreference {
    private const val PREFS = "recognition"
    private const val KEY_MODE = "recognition_mode"

    fun mode(context: Context): RecognitionMode =
        RecognitionMode.fromId(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: RecognitionMode) {
        prefs(context).edit().putString(KEY_MODE, mode.id).apply()
    }

    /** Legacy bridge query: recognition is required and always enabled. */
    fun isMangaOcrEnabled(context: Context): Boolean = true

    /** Legacy bridge setter: refusing the old "off" keeps text recognition available. */
    fun setMangaOcrEnabled(context: Context, enabled: Boolean) {
        require(enabled) { "Text recognition is required for lookups." }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
