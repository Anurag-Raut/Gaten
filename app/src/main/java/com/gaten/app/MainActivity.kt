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
import androidx.compose.ui.Modifier
import com.gaten.app.ui.home.HomeScreen
import com.gaten.app.ui.theme.GatenTheme

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
            GatenTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
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
