/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.ui.theme.AccentColor
import com.gaten.app.ui.theme.DarkError
import com.gaten.app.ui.theme.DarkErrorContainer
import com.gaten.app.ui.theme.DarkOnErrorContainer
import com.gaten.app.ui.theme.LightError
import com.gaten.app.ui.theme.LightErrorContainer
import com.gaten.app.ui.theme.LightOnErrorContainer
import com.gaten.app.ui.theme.Palette
import com.gaten.app.ui.theme.ThemeMode
import com.gaten.app.ui.theme.ThemePreferences
import com.gaten.app.ui.theme.accentRoles
import com.gaten.app.ui.theme.paletteNeutrals

/**
 * The app's theme choices and their resolved colours, for the RN screens.
 *
 * The choices live where they always have — [ThemePreferences]'s `gaten_settings` — so the
 * accessibility service's overlay and the RN screens read the same three values. The colours are
 * resolved here, on the Kotlin side, from [accentRoles] and [paletteNeutrals]: the same palette
 * and accent functions [com.gaten.app.ui.theme.Theme] builds its Material scheme from, so JS does
 * no colour maths and renders exactly the tones Compose rendered. The role-to-key mapping mirrors
 * `gatenColorScheme`'s (a private function of that file): secondary and its container are the
 * accent's tones, the finer container steps land on the ladder's own tones, and the inverse roles
 * come from the *other* mode's ladder — the only part of the scheme that is not already a public
 * function.
 *
 * Every setter announces the change through [GatenBridgeEmitter], so the RN screens re-read
 * without being told.
 */
class GatenThemeModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    /** The three choices as stored: `{ mode, palette, accent }`, the names JS sends back. */
    @ReactMethod
    fun getTheme(promise: Promise) {
        promise.resolve(prefsToJsMap())
    }

    /** Stores the mode (`System`, `Light` or `Dark`) and announces it. */
    @ReactMethod
    fun setThemeMode(mode: String, promise: Promise) {
        val value = ThemeMode.entries.firstOrNull { it.name == mode }
        if (value == null) {
            promise.reject(CODE_UNKNOWN, "Unknown theme mode '$mode'")
            return
        }
        ThemePreferences.setThemeMode(reactApplicationContext, value)
        promise.resolve(prefsToJsMap())
        GatenBridgeEmitter.emitThemeChanged()
    }

    /** Stores the palette (`Gaten`, `Paper`, … — [Palette]'s names) and announces it. */
    @ReactMethod
    fun setPalette(palette: String, promise: Promise) {
        val value = Palette.entries.firstOrNull { it.name == palette }
        if (value == null) {
            promise.reject(CODE_UNKNOWN, "Unknown palette '$palette'")
            return
        }
        ThemePreferences.setPalette(reactApplicationContext, value)
        promise.resolve(prefsToJsMap())
        GatenBridgeEmitter.emitThemeChanged()
    }

    /** Stores the accent ([AccentColor]'s names) and announces it. */
    @ReactMethod
    fun setAccent(accent: String, promise: Promise) {
        val value = AccentColor.entries.firstOrNull { it.name == accent }
        if (value == null) {
            promise.reject(CODE_UNKNOWN, "Unknown accent '$accent'")
            return
        }
        ThemePreferences.setAccent(reactApplicationContext, value)
        promise.resolve(prefsToJsMap())
        GatenBridgeEmitter.emitThemeChanged()
    }

    /**
     * The resolved colours for [modeName], [paletteName] and [accentName] — each an omitted or null
     * value falling back to the stored choice, so a screen that changed one of the three can ask
     * for the scheme it is about to show.
     *
     * `System` is resolved against the device's own dark setting at the moment of the call, the
     * same resolution [ThemeMode.isDark] gives every consumer of the choice. Every colour answers
     * as `#RRGGBB`, the keys named after the Material roles the Compose scheme fills.
     */
    @ReactMethod
    fun colors(modeName: String?, paletteName: String?, accentName: String?, promise: Promise) {
        val context = reactApplicationContext
        val mode = modeName?.let { name ->
            ThemeMode.entries.firstOrNull { it.name == name }
                ?: return rejectUnknown(promise, "theme mode", name)
        } ?: ThemePreferences.themeMode(context)
        val palette = paletteName?.let { name ->
            Palette.entries.firstOrNull { it.name == name }
                ?: return rejectUnknown(promise, "palette", name)
        } ?: ThemePreferences.palette(context)
        val accent = accentName?.let { name ->
            AccentColor.entries.firstOrNull { it.name == name }
                ?: return rejectUnknown(promise, "accent", name)
        } ?: ThemePreferences.accent(context)

        val systemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        promise.resolve(colorMap(palette, accent, mode.isDark(systemDark)))
    }

    /**
     * Applies the RN shell's background and icon contrast to the Android gesture area. This changes
     * only window bar appearance; React Native's safe-area layout remains responsible for insets.
     */
    @ReactMethod
    fun setNavigationBarAppearance(background: String, dark: Boolean, promise: Promise) {
        if (!background.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
            promise.reject(CODE_INVALID_BAR_COLOR, "Expected an opaque #RRGGBB navigation bar color.")
            return
        }
        val backgroundColor = Color.parseColor(background)
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.resolve(null)
            return
        }

        activity.runOnUiThread {
            try {
                val window = activity.window
                window.navigationBarColor = backgroundColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, window.decorView)
                    .isAppearanceLightNavigationBars = !dark
                promise.resolve(null)
            } catch (error: Exception) {
                promise.reject(CODE_SYSTEM_BAR, "Could not apply the navigation bar appearance.", error)
            }
        }
    }

    private fun prefsToJsMap(): WritableMap = Arguments.createMap().apply {
        val context = reactApplicationContext
        putString("mode", ThemePreferences.themeMode(context).name)
        putString("palette", ThemePreferences.palette(context).name)
        putString("accent", ThemePreferences.accent(context).name)
    }

    /**
     * The scheme's roles as a flat map of hex strings, [dark] deciding the mode.
     *
     * The tones come from [accentRoles] and [paletteNeutrals] and the errors from the constants the
     * Compose scheme reads; the mapping below is [com.gaten.app.ui.theme.Theme]'s own: the accent's
     * tones carry secondary and tertiary under the names the scheme aliases them to, the ladder's
     * two steps stand in for the finer container steps, and the inverse roles come from the other
     * mode's ladder.
     */
    private fun colorMap(palette: Palette, accent: AccentColor, dark: Boolean): WritableMap {
        val tone = accentRoles(accent, dark)
        val neutral = paletteNeutrals(palette, dark)
        val inverseNeutral = paletteNeutrals(palette, !dark)
        val inverseTone = accentRoles(accent, !dark)

        return Arguments.createMap().apply {
            put("background", neutral.background)
            put("onBackground", neutral.onSurface)
            put("surface", neutral.surface)
            put("onSurface", neutral.onSurface)
            put("surfaceVariant", neutral.surfaceVariant)
            put("onSurfaceVariant", neutral.onSurfaceVariant)
            put("surfaceContainer", neutral.surfaceContainer)
            put("surfaceContainerHigh", neutral.surfaceContainerHigh)
            put("surfaceContainerHighest", neutral.surfaceContainerHighest)
            put("surfaceContainerLow", neutral.surfaceContainer)
            put("surfaceContainerLowest", neutral.surface)
            put("surfaceTint", tone.primary)
            put("outline", neutral.outline)
            put("outlineVariant", neutral.outline)
            put("primary", tone.primary)
            put("onPrimary", tone.onPrimary)
            put("primaryContainer", tone.primaryContainer)
            put("onPrimaryContainer", tone.onPrimaryContainer)
            put("secondary", tone.primary)
            put("onSecondary", tone.onPrimary)
            put("secondaryContainer", tone.primaryContainer)
            put("onSecondaryContainer", tone.onPrimaryContainer)
            put("tertiary", tone.tertiary)
            put("tertiaryContainer", tone.primaryContainer)
            put("onTertiaryContainer", tone.onPrimaryContainer)
            put("inverseSurface", inverseNeutral.surface)
            put("inverseOnSurface", inverseNeutral.onSurface)
            put("inversePrimary", inverseTone.primary)
            put("error", if (dark) DarkError else LightError)
            put("errorContainer", if (dark) DarkErrorContainer else LightErrorContainer)
            put("onErrorContainer", if (dark) DarkOnErrorContainer else LightOnErrorContainer)
        }
    }

    /** Puts [color] under [key] as `#RRGGBB`; every tone in the scheme is opaque. */
    private fun WritableMap.put(key: String, color: androidx.compose.ui.graphics.Color) {
        putString(key, "#%06X".format(color.toArgb() and 0xFFFFFF))
    }

    private fun rejectUnknown(promise: Promise, what: String, name: String) {
        promise.reject(CODE_UNKNOWN, "Unknown $what '$name'")
    }

    companion object {
        const val NAME = "GatenTheme"

        private const val CODE_UNKNOWN = "E_THEME_UNKNOWN"
        private const val CODE_INVALID_BAR_COLOR = "E_THEME_INVALID_BAR_COLOR"
        private const val CODE_SYSTEM_BAR = "E_THEME_SYSTEM_BAR"
    }
}
