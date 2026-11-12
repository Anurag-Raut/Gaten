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
 * time. It is the recommended engine: it suits ordinary screen and novel text. The mode is the
 * reader's manual choice, with one exception: a pack that finishes installing selects Enhanced once
 * for a reader who did not choose a mode while that download was running ([RecognitionAutoSelect]).
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

    /** True between the start of an Enhanced transfer and the completion it is waiting for. */
    private const val KEY_DOWNLOAD_WINDOW = "download_auto_select_window"

    /** The reader picked a mode while [KEY_DOWNLOAD_WINDOW] was open: do not override it. */
    private const val KEY_MANUAL_IN_WINDOW = "manual_mode_in_download_window"

    /** Serialises the window's read-modify-write pairs; the bridge writes them from IO coroutines. */
    private val lock = Any()

    fun mode(context: Context): RecognitionMode =
        RecognitionMode.fromId(prefs(context).getString(KEY_MODE, null))

    /**
     * Stores the reader's manual choice. While a download window is open this is also the veto that
     * stops [RecognitionAutoSelect] from selecting Enhanced when that download lands.
     */
    fun setMode(context: Context, mode: RecognitionMode) = synchronized(lock) {
        val prefs = prefs(context)
        val editor = prefs.edit().putString(KEY_MODE, mode.id)
        if (prefs.getBoolean(KEY_DOWNLOAD_WINDOW, false)) {
            editor.putBoolean(KEY_MANUAL_IN_WINDOW, true)
        }
        editor.apply()
    }

    /**
     * Opens the auto-select window for a download that has begun (or was already running when this
     * process started watching). Idempotent: a transfer Play reports many times, and a process that
     * restarts mid-transfer, must not erase a manual choice already made in this window.
     */
    fun beginDownloadWindow(context: Context) = synchronized(lock) {
        val prefs = prefs(context)
        if (prefs.getBoolean(KEY_DOWNLOAD_WINDOW, false)) return
        prefs.edit()
            .putBoolean(KEY_DOWNLOAD_WINDOW, true)
            .putBoolean(KEY_MANUAL_IN_WINDOW, false)
            .apply()
    }

    /** True while a download's completion may still select Enhanced. */
    fun isDownloadWindowOpen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DOWNLOAD_WINDOW, false)

    /** True when the reader chose a mode after [beginDownloadWindow] opened the current window. */
    fun manualChoiceSinceDownloadBegan(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MANUAL_IN_WINDOW, false)

    /**
     * Ends the window after its completion has been handled, so a later completion report cannot
     * select Enhanced a second time and the next download starts with a fresh veto.
     */
    fun closeDownloadWindow(context: Context) = synchronized(lock) {
        prefs(context).edit()
            .putBoolean(KEY_DOWNLOAD_WINDOW, false)
            .putBoolean(KEY_MANUAL_IN_WINDOW, false)
            .apply()
    }

    /**
     * Writes the automatic selection. Deliberately not [setMode]: this is not a manual choice, so it
     * must not count as the reader's veto if the same window is ever evaluated again.
     */
    fun selectEnhancedAutomatically(context: Context) = synchronized(lock) {
        prefs(context).edit().putString(KEY_MODE, RecognitionMode.PP_MEDIUM.id).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
