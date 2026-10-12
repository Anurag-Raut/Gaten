/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * External entry point so a key remapper can start a lookup.
 *
 * Send a broadcast to `com.yomilens.app` with action [ACTION_TRIGGER_LOOKUP]; the
 * running accessibility service takes the screenshot and opens the overlay.
 *
 * Key Mapper 4.x (its "Intent" action) needs: target *Broadcast receiver*,
 * Action = `com.yomilens.app.action.TRIGGER_LOOKUP`,
 * Package name = `com.yomilens.app`.
 *
 * A no-op when the accessibility service is not connected, because only that service can grab the
 * screen.
 */
class LookupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_LOOKUP) return

        if (YomiLensAccessibilityService.instance == null) {
            Log.w(TAG, "Lookup requested but the accessibility service is not connected")
            return
        }
        YomiLensAccessibilityService.triggerCapture()
    }

    companion object {
        private const val TAG = "YomiLens"
        const val ACTION_TRIGGER_LOOKUP = "com.yomilens.app.action.TRIGGER_LOOKUP"
    }
}
