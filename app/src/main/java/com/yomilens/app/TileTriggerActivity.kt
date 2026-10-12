/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Transparent activity launched from the Quick Settings tile: starting an activity from a
 * TileService collapses the shade, and once it is gone this activity triggers the lookup capture.
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

        // Wait 500ms for the quick settings panel to collapse cleanly across all Android OEM skins (OneUI, MIUI/HyperOS, ColorOS)
        Handler(Looper.getMainLooper()).postDelayed({
            YomiLensAccessibilityService.triggerCapture()
            finish()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }, 500L)
    }

    companion object {
        private const val TAG = "YomiLens"
    }
}
