/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Transparent activity launched from the Quick Settings tile: starting an activity from a
 * TileService collapses the shade, and the trigger pipeline waits for that collapse itself before
 * it captures, so this hands off immediately and gets out of the way.
 */
class TileTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable window animations: the activity is invisible, so any transition would be a flash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        Log.d(TAG, "TileTriggerActivity started")

        // The pipeline waits out the shade; a fixed postDelayed here only added latency.
        GatenAccessibilityService.triggerCapture()
        finish()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "Gaten"
    }
}
