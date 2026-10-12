/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context

/**
 * The OCR settings, stored in the app's single settings file.
 *
 * There is no engine to choose: ML Kit finds the lines on screen, and manga-ocr reads them whenever
 * its model is installed. What is left here is the overlay's debug aid.
 */
object OcrPreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_SHOW_CHAR_BOXES = "show_char_boxes"

    /**
     * Whether the overlay draws the recognised boxes themselves - every line's bounds and every
     * character box - over the frozen frame. Off by default: it is a debugging aid for the mapping
     * between the capture's pixels and the window's, not something a reader wants in the way.
     */
    fun showCharBoxes(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOW_CHAR_BOXES, false)

    fun setShowCharBoxes(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_CHAR_BOXES, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
