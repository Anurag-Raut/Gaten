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
 * External entry point so a key remapper can start a lookup: the [ACTION_TRIGGER_LOOKUP] broadcast
 * asks the running accessibility service to screenshot the screen; a no-op when it is not connected.
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
