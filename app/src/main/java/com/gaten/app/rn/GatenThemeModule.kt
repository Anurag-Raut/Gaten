/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.LifecycleState
import com.facebook.react.uimanager.UIManagerHelper
import com.gaten.app.ui.theme.AccentColor
import com.gaten.app.ui.theme.DarkError
import com.gaten.app.ui.theme.DarkErrorContainer
import com.gaten.app.ui.theme.DarkOnErrorContainer
import com.gaten.app.ui.theme.LightError
import com.gaten.app.ui.theme.LightErrorContainer
import com.gaten.app.ui.theme.LightOnErrorContainer
import com.gaten.app.ui.theme.ThemeMode
import com.gaten.app.ui.theme.ThemePreferences
import com.gaten.app.ui.theme.accentRoles
import com.gaten.app.ui.theme.gatenNeutrals

/**
 * The app's theme choices and their resolved colours, for the RN screens.
 *
 * The choices live where they always have — [ThemePreferences]'s `gaten_settings` — so the
 * accessibility service's overlay and the RN screens read the same two values. The colours are
 * resolved here, on the Kotlin side, from [accentRoles] and [gatenNeutrals]: the same accent and
 * neutral functions [com.gaten.app.ui.theme.Theme] builds its Material scheme from, so JS does
 * no colour maths and renders exactly the tones Compose rendered. The role-to-key mapping mirrors
 * `gatenColorScheme`'s (a private function of that file): secondary and tertiary use accent tones,
 * the secondary action container is neutral, the finer container steps land on the ladder's own
 * tones, and inverse roles come from the *other* mode's ladder.
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

    /** The choices as stored: `{ mode, accent }`, the names JS sends back. */
    @ReactMethod
    fun getTheme(promise: Promise) {
        promise.resolve(prefsToJsMap())
    }

    /** Captures the old theme on the UI thread; a cosmetic failure never prevents a theme change. */
    @ReactMethod
    fun prepareThemeTransition(viewTag: Int, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            if (reactApplicationContext.lifecycleState != LifecycleState.RESUMED) {
                promise.resolve(false)
                return@runOnUiThread
            }
            try {
                val view = UIManagerHelper.getUIManagerForReactTag(reactApplicationContext, viewTag)
                    ?.resolveView(viewTag) as? GatenThemeTransitionView
                if (view == null) {
                    promise.resolve(false)
                } else {
                    view.prepareTransition(reactApplicationContext.currentActivity?.window) {
                        promise.resolve(it)
                    }
                }
            } catch (error: Exception) {
                Log.w(NAME, "Could not capture the theme transition", error)
                promise.resolve(false)
            } catch (error: OutOfMemoryError) {
                Log.w(NAME, "Not enough memory for the theme transition", error)
                promise.resolve(false)
            }
        }
    }

    /** Stores the mode (`System`, `Light` or `Dark`) and announces it. */
    @ReactMethod
    fun setThemeMode(mode: String, promise: Promise) {
        val value = ThemeMode.entries.firstOrNull { it.name == mode }
        if (value == null) {
            promise.reject(CODE_UNKNOWN, "Unknown theme mode '$mode'")
            return
        }
        try {
            ThemePreferences.setThemeMode(reactApplicationContext, value)
        } catch (error: Exception) {
            promise.reject(CODE_SAVE, "Could not save the theme mode.", error)
            return
        }
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
        try {
            ThemePreferences.setAccent(reactApplicationContext, value)
        } catch (error: Exception) {
            promise.reject(CODE_SAVE, "Could not save the accent.", error)
            return
        }
        promise.resolve(prefsToJsMap())
        GatenBridgeEmitter.emitThemeChanged()
    }

    /**
     * The resolved colours for [modeName] and [accentName] — each an omitted or null value falling
     * back to the stored choice, so a screen that changed one of the two can ask for the scheme it
     * is about to show.
     *
     * `System` is resolved against the device's own dark setting at the moment of the call, the
     * same resolution [ThemeMode.isDark] gives every consumer of the choice. Every colour answers
     * as `#RRGGBB`, the keys named after the Material roles the Compose scheme fills.
     */
    @ReactMethod
    fun colors(modeName: String?, accentName: String?, promise: Promise) {
        val context = reactApplicationContext
        val mode = modeName?.let { name ->
            ThemeMode.entries.firstOrNull { it.name == name }
                ?: return rejectUnknown(promise, "theme mode", name)
        } ?: ThemePreferences.themeMode(context)
        val accent = accentName?.let { name ->
            AccentColor.entries.firstOrNull { it.name == name }
                ?: return rejectUnknown(promise, "accent", name)
        } ?: ThemePreferences.accent(context)

        val systemDark = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        promise.resolve(colorMap(accent, mode.isDark(systemDark)))
    }

    /**
     * Clears the gesture area so the app's own content shows through it, and sets the icon contrast
     * to [dark]. The bar itself stays transparent on purpose: an opaque navigation bar is painted
     * over the window's bottom strip, which is exactly where the floating bottom bar's all-round
     * shadow falls, so colouring it would cut that shadow off. React Native's safe-area layout
     * remains responsible for insets.
     */
    @ReactMethod
    fun setNavigationBarAppearance(dark: Boolean, promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.resolve(null)
            return
        }

        activity.runOnUiThread {
            try {
                val window = activity.window
                window.navigationBarColor = Color.TRANSPARENT
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
        putString("accent", ThemePreferences.accent(context).name)
    }

    /**
     * The scheme's roles as a flat map of hex strings, [dark] deciding the mode.
     *
     * The tones come from [accentRoles] and [gatenNeutrals] and the errors from the constants the
     * Compose scheme reads; the mapping below mirrors [com.gaten.app.ui.theme.Theme]: accent tones
     * carry secondary and tertiary, the secondary action container is neutral, the ladder's two
     * steps stand in for finer container steps, and inverse roles come from the other mode's ladder.
     */
    private fun colorMap(accent: AccentColor, dark: Boolean): WritableMap {
        val tone = accentRoles(accent, dark)
        val neutral = gatenNeutrals(dark)
        val inverseNeutral = gatenNeutrals(!dark)
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
            put("secondaryContainer", neutral.surfaceVariant)
            put("onSecondaryContainer", neutral.onSurface)
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
        private const val CODE_SAVE = "E_THEME_SAVE"
        private const val CODE_SYSTEM_BAR = "E_THEME_SYSTEM_BAR"
    }
}
