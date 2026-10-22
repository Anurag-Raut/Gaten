/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import android.content.Context

/** Which light or dark mode the app draws with. [System] follows the device until the user picks otherwise. */
enum class ThemeMode {
    System,
    Light,
    Dark;

    /**
     * This choice resolved against the device's own dark setting, which is all [System] means.
     * Every consumer of the choice — the Compose theme, the floating bubble's window (which has no
     * composition and so reads the configuration itself) — resolves it through here, so they can
     * never disagree.
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        System -> systemDark
        Light -> false
        Dark -> true
    }
}

/**
 * The app's theme choices — dark or light and the accent — stored in the app's single settings file
 * shared with [com.gaten.app.trigger.TriggerPreferences].
 *
 * They are read by whoever composes a theme — the activity and the capture service's overlay —
 * rather than pushed to them, so a change reaches the next overlay without the service being told.
 */
object ThemePreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_ACCENT = "accent"

    fun themeMode(context: Context): ThemeMode =
        when (prefs(context).getString(KEY_THEME_MODE, null)) {
            ThemeMode.Light.name -> ThemeMode.Light
            ThemeMode.Dark.name -> ThemeMode.Dark
            // Anything else, including a value written by a future version, is the default.
            else -> ThemeMode.System
        }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /** The accent, defaulting to the app's own indigo for an unset or unrecognised value. */
    fun accent(context: Context): AccentColor {
        val stored = prefs(context).getString(KEY_ACCENT, null) ?: return AccentColor.Indigo
        return AccentColor.entries.firstOrNull { it.name == stored } ?: AccentColor.Indigo
    }

    fun setAccent(context: Context, accent: AccentColor) {
        prefs(context).edit().putString(KEY_ACCENT, accent.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
