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
import com.gaten.app.ui.home.HomeScreen
import com.gaten.app.ui.theme.GatenTheme
import com.gaten.app.ui.theme.ThemePreferences

/**
 * The setup screen.
 *
 * The only thing it tells the service is when the user is done with the app: a task that is really
 * going away (not a rotation) takes any lookup and pending capture down with it, which is also what
 * covers the app being taken off recents when the platform does not deliver `onTaskRemoved` to a
 * service it itself binds. Later triggers are unaffected — the service stays connected.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // Hoisted here, not read inside HomeScreen: the theme is applied above it, so the
            // choices have to come from outside the content they change.
            var themeMode by remember { mutableStateOf(ThemePreferences.themeMode(this)) }
            var palette by remember { mutableStateOf(ThemePreferences.palette(this)) }
            var accent by remember { mutableStateOf(ThemePreferences.accent(this)) }
            GatenTheme(themeMode, palette, accent) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeMode = mode
                            ThemePreferences.setThemeMode(this, mode)
                        },
                        palette = palette,
                        onPaletteChange = { picked ->
                            palette = picked
                            ThemePreferences.setPalette(this, picked)
                            // A palette brings its own accent; the row below can still change it.
                            accent = picked.defaultAccent
                            ThemePreferences.setAccent(this, picked.defaultAccent)
                        },
                        accent = accent,
                        onAccentChange = { picked ->
                            accent = picked
                            ThemePreferences.setAccent(this, picked)
                        }
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
}
