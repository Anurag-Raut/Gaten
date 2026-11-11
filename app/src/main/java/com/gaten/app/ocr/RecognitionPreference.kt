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
 * [ML_KIT] is the default and always available: its Japanese recognizer is bundled, so a fresh
 * install reads with nothing to download. [PP_MEDIUM] is the optional engine — an explicit install
 * in Settings that works offline afterwards (Google Play's fast-follow asset pack on a Play install,
 * the in-app downloader otherwise) — so the mode can be selected before its model is installed, and
 * a selected engine that is missing, fails, or returns nothing usable falls back to ML Kit at read
 * time. It is the recommended engine: it suits ordinary screen and novel text. There is no automatic
 * image classifier: the mode is the reader's manual choice.
 */
enum class RecognitionMode(
    val id: String,
    /** Bundled with the app: nothing to download, and always installed. */
    val bundled: Boolean = false,
    /** Shown with a "Recommended" marker in the settings screen. */
    val recommended: Boolean = false
) {
    ML_KIT("ml_kit", bundled = true),
    PP_MEDIUM("medium", recommended = true);

    /** The PP-OCR engine this mode reads with, or null for the bundled ML Kit. */
    fun ppEngine(): PpOcrModel.Engine? = when (this) {
        ML_KIT -> null
        PP_MEDIUM -> PpOcrModel.Engine.MEDIUM
    }

    companion object {
        /** The default mode: the bundled ML Kit recognizer, ready on a fresh install. */
        val DEFAULT = ML_KIT

        /**
         * The persisted value as a mode. Only the two live ids are known; anything else is migrated
         * rather than dropped, so a selection never silently changes engine without an install:
         * the retired Manga OCR ids (`manga_ocr`, `manga`) select the recommended [PP_MEDIUM], while
         * a missing or unknown value (including the old ML Kit-era keys) falls back to the bundled
         * default.
         */
        fun fromId(id: String?): RecognitionMode {
            RecognitionMode.entries.firstOrNull { it.id == id }?.let { return it }
            return when (id) {
                "manga_ocr", "manga" -> PP_MEDIUM
                else -> DEFAULT
            }
        }
    }
}

/**
 * Stores the selected recognition mode.
 *
 * The store is the prefs file the old ML Kit toggle used, so an upgrade does not leave a second
 * store behind, and the stored mode is the only switch: recognition is always required.
 */
object RecognitionPreference {
    private const val PREFS = "recognition"
    private const val KEY_MODE = "recognition_mode"

    fun mode(context: Context): RecognitionMode =
        RecognitionMode.fromId(prefs(context).getString(KEY_MODE, null))

    fun setMode(context: Context, mode: RecognitionMode) {
        prefs(context).edit().putString(KEY_MODE, mode.id).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
