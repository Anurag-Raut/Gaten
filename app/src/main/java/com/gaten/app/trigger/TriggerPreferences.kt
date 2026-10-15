/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import android.content.Context

/**
 * Floating trigger settings, position and Quick Settings tile presence, stored in the app's
 * settings file so they survive process death.
 */
object TriggerPreferences {
    private const val PREFS_NAME = "gaten_settings"
    private const val KEY_FLOATING_BUTTON = "floating_button"
    private const val KEY_FLOATING_BUTTON_X = "floating_button_x"
    private const val KEY_FLOATING_BUTTON_Y = "floating_button_y"
    const val KEY_TILE_ADDED = "tile_added"

    /** Sentinel for "never dragged"; a real window coordinate is never this. */
    private const val NO_POSITION = Int.MIN_VALUE

    /** Broadcast sent after the home-screen switch flips, so the service shows or hides the bubble. */
    const val ACTION_FLOATING_BUTTON_CHANGED = "com.gaten.app.action.FLOATING_BUTTON_CHANGED"

    fun floatingButtonEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLOATING_BUTTON, false)

    fun setFloatingButtonEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING_BUTTON, enabled).apply()
    }

    fun tileAdded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TILE_ADDED, false)

    fun setTileAdded(context: Context, added: Boolean) {
        prefs(context).edit().putBoolean(KEY_TILE_ADDED, added).apply()
    }

    /** The bubble's last dragged top-left in window pixels, or null when it was never moved. */
    fun floatingButtonPosition(context: Context): Pair<Int, Int>? {
        val stored = prefs(context)
        val x = stored.getInt(KEY_FLOATING_BUTTON_X, NO_POSITION)
        val y = stored.getInt(KEY_FLOATING_BUTTON_Y, NO_POSITION)
        if (x == NO_POSITION || y == NO_POSITION) return null
        return x to y
    }

    fun setFloatingButtonPosition(context: Context, x: Int, y: Int) {
        prefs(context).edit()
            .putInt(KEY_FLOATING_BUTTON_X, x)
            .putInt(KEY_FLOATING_BUTTON_Y, y)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
