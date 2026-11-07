/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import android.content.Context

/**
 * Lookup mode's own record of the reader's history with it, in the app's settings file so it
 * survives process death: whether the accessibility grant has ever been on, and whether Home's
 * reminder for it has been dismissed.
 *
 * [everEnabled] is monotonic — [recordEverEnabled] writes true and nothing ever writes it back —
 * so a reader who turns the grant on and later off is never reminded as if they had never used it.
 * It is written only where the grant is really on: the accessibility service connecting, or a read
 * that finds it enabled (see `GatenAppInfoModule.getLookupStatus`). Opening Android's settings or
 * accepting the disclosure writes nothing, so neither can pass for a grant that was given.
 */
object LookupModePreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_EVER_ENABLED = "lookup_ever_enabled"
    private const val KEY_REMINDER_DISMISSED = "lookup_reminder_dismissed"

    /** Whether the accessibility grant has ever been on for this install. */
    fun everEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EVER_ENABLED, false)

    /**
     * Records that the grant was on. A second call — and one made after the grant was turned off —
     * leaves it true, which is what keeps the reminder from coming back to a reader who has used
     * lookup mode and switched it off.
     */
    fun recordEverEnabled(context: Context) {
        if (everEnabled(context)) return
        prefs(context).edit().putBoolean(KEY_EVER_ENABLED, true).apply()
    }

    /** Whether the reader dismissed Home's lookup-mode reminder. */
    fun reminderDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMINDER_DISMISSED, false)

    /** Dismissing is permanent: nothing clears it, so the reminder stays away across restarts. */
    fun setReminderDismissed(context: Context) {
        prefs(context).edit().putBoolean(KEY_REMINDER_DISMISSED, true).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
