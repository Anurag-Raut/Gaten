/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StyleRes
import com.gaten.app.R

/**
 * Applies the active accent and mode's Android theme overlay (`colorAccent`,
 * `colorControlActivated` and `android:textColorHighlight`) to a [Context]'s theme.
 *
 * React Native's `cursorColor` and `selectionHandleColor` props tint `EditText` drawables with a
 * `BlendModeColorFilter`, which works on stock Android but is bypassed by OEM text editors
 * (including ColorOS/OxygenOS) that tint cursors and selection handles directly from
 * `?attr/colorControlActivated` / `?attr/colorAccent` in the view's theme. Keeping the Activity
 * and overlay themes in sync with [ThemePreferences] ensures cursors, handles and highlights wear
 * the reader's accent everywhere without restarting the activity.
 */
object AccentThemeOverlay {

    @StyleRes
    fun overlayRes(accent: AccentColor, dark: Boolean): Int = when (accent) {
        AccentColor.Indigo ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Indigo_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Indigo_Light
        AccentColor.Blue ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Blue_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Blue_Light
        AccentColor.Violet ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Violet_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Violet_Light
        AccentColor.Teal ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Teal_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Teal_Light
        AccentColor.Green ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Green_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Green_Light
        AccentColor.Rose ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Rose_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Rose_Light
        AccentColor.Black ->
            if (dark) R.style.ThemeOverlay_Gaten_Accent_Black_Dark
            else R.style.ThemeOverlay_Gaten_Accent_Black_Light
    }

    /** Applies the stored accent and mode overlay to [context]'s theme in place. */
    fun apply(context: Context, configuration: Configuration = context.resources.configuration) {
        val systemDark = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = ThemePreferences.themeMode(context).isDark(systemDark)
        val accent = ThemePreferences.accent(context)
        context.theme.applyStyle(overlayRes(accent, dark), /* force = */ true)
    }
}
