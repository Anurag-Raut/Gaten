/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.support

import android.content.Context

/**
 * The support popup's own state, in the app's settings file so a dismissal survives process death:
 * how many times the app has been opened, whether the reader has silenced the popup for good, and —
 * when they only postponed it — the earliest moment it may be offered again.
 */
object SupportPreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_OPEN_COUNT = "support_open_count"
    private const val KEY_NEVER = "support_prompt_never"
    private const val KEY_NEXT_AT = "support_prompt_next_at"

    /** How long "Not now" holds the popup back. */
    private const val POSTPONE_MS = 7L * 24 * 60 * 60 * 1000

    fun openCount(context: Context): Int = prefs(context).getInt(KEY_OPEN_COUNT, 0)

    /** Counts one app open and returns the running total, so the caller can gate on it. */
    fun recordAppOpen(context: Context): Int {
        val next = openCount(context) + 1
        prefs(context).edit().putInt(KEY_OPEN_COUNT, next).apply()
        return next
    }

    /** True once the reader chose "Don't show again". */
    fun promptNever(context: Context): Boolean = prefs(context).getBoolean(KEY_NEVER, false)

    /** The earliest moment the popup may be offered again; 0 until "Not now" is chosen. */
    fun promptNextEligibleAt(context: Context): Long = prefs(context).getLong(KEY_NEXT_AT, 0L)

    /**
     * Records a dismissal. [permanent] is "Don't show again" — the one thing that silences the popup
     * for good; otherwise "Not now" holds it back for [POSTPONE_MS] from now.
     */
    fun dismissPrompt(context: Context, permanent: Boolean) {
        val editor = prefs(context).edit()
        if (permanent) {
            editor.putBoolean(KEY_NEVER, true)
        } else {
            editor.putLong(KEY_NEXT_AT, System.currentTimeMillis() + POSTPONE_MS)
        }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
