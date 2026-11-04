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
 * install reads with nothing to download. [MANGA_OCR], [PP_MEDIUM] and [PP_MANGA] are optional:
 * each is an explicit download in Settings that works offline afterwards, so a mode can be selected
 * before its model is installed, and a selected engine that is missing, fails, or returns nothing
 * usable falls back to ML Kit at read time. [PP_MEDIUM] is the recommended download — it suits
 * screen text and novels; [PP_MANGA] suits manga, stylized lettering and video thumbnails. There is
 * no automatic image classifier: the mode is the reader's manual choice.
 */
enum class RecognitionMode(
    val id: String,
    /** Bundled with the app: nothing to download, and always installed. */
    val bundled: Boolean = false,
    /** Shown with a "Recommended" marker in the settings screen. */
    val recommended: Boolean = false
) {
    ML_KIT("ml_kit", bundled = true),
    MANGA_OCR("manga_ocr"),
    PP_MEDIUM("medium", recommended = true),
    PP_MANGA("manga");

    /** The PP-OCR engine this mode reads with, or null for the bundled ML Kit and for Manga OCR. */
    fun ppEngine(): PpOcrModel.Engine? = when (this) {
        ML_KIT, MANGA_OCR -> null
        PP_MEDIUM -> PpOcrModel.Engine.MEDIUM
        PP_MANGA -> PpOcrModel.Engine.MANGA
    }

    companion object {
        /** The default mode: the bundled ML Kit recognizer, ready on a fresh install. */
        val DEFAULT = ML_KIT

        /**
         * The persisted value as a mode; a missing, unknown, or retired value (including the old
         * ML Kit-era keys) means the default. The migration is deliberate: a reader who never chose
         * an engine had no stored value and moves to ML Kit, the new default, while an explicit
         * `manga_ocr` / `medium` / `manga` choice is kept so no one's selection changes under them.
         */
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
