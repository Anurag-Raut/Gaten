/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gaten.app.ui.home.GatenApp
import com.gaten.app.ui.theme.GatenTheme
import com.gaten.app.ui.theme.ThemePreferences

/**
 * The app's only activity: it owns the theme the whole app is drawn in and hands the rest to
 * [GatenApp].
 *
 * The only thing it tells the service is when the user is done with the app: a task that is really
 * going away (not a rotation) takes any lookup and pending capture down with it, which is also what
 * covers the app being taken off recents when the platform does not deliver `onTaskRemoved` to a
 * service it itself binds. Later triggers are unaffected — the service stays connected.
 */
class MainActivity : ComponentActivity() {

    /**
     * The settings page this launch asked for, from [EXTRA_SETTINGS_PAGE], until a screen has taken
     * it. Held here rather than inside the composition because it can arrive after the app is up: an
     * outside launch carries `FLAG_ACTIVITY_NEW_TASK`, which delivers the intent to this running
     * instance instead of creating another one.
     */
    private var settingsPageRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A rotation replays onCreate with the same intent, and whatever it asked for has already
        // been dealt with once: only a fresh start may act on it.
        if (savedInstanceState == null) {
            settingsPageRequest = intent.getStringExtra(EXTRA_SETTINGS_PAGE)
        }
        addOnNewIntentListener { newIntent ->
            settingsPageRequest = newIntent.getStringExtra(EXTRA_SETTINGS_PAGE)
        }

        setContent {
            // Hoisted here, not read inside GatenApp: the theme is applied above it, so the choices
            // have to come from outside the content they change.
            var themeMode by remember { mutableStateOf(ThemePreferences.themeMode(this)) }
            var palette by remember { mutableStateOf(ThemePreferences.palette(this)) }
            var accent by remember { mutableStateOf(ThemePreferences.accent(this)) }
            GatenTheme(themeMode, palette, accent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GatenApp(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            ThemePreferences.setThemeMode(this, mode)
                        },
                        palette = palette,
                        onPaletteChange = { picked ->
                            palette = picked
                            ThemePreferences.setPalette(this, picked)
                        },
                        accent = accent,
                        onAccentChange = { picked ->
                            accent = picked
                            ThemePreferences.setAccent(this, picked)
                        },
                        settingsPageRequest = settingsPageRequest,
                        onSettingsPageRequestHandled = { settingsPageRequest = null }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // Only when the app is really finished: a configuration change destroys this activity too,
        // and a lookup must survive that.
        if (isFinishing && !isChangingConfigurations) {
            GatenAccessibilityService.dismissForAppExit()
        }
        super.onDestroy()
    }

    companion object {
        /**
         * The extra an outside launcher sends to open one settings page: the settings route key, so
         * "wanikani", "anki" or "lookup". Anything else opens the Settings root.
         */
        const val EXTRA_SETTINGS_PAGE = "settings_page"
    }
}
