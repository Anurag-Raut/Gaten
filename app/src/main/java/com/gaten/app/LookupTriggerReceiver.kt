/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gaten.app.trigger.TriggerPreferences

/**
 * External entry point so a key remapper can start a lookup: the [ACTION_TRIGGER_LOOKUP] broadcast
 * asks the running accessibility service to screenshot the screen; a no-op when it is not connected.
 *
 * It also relays [TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED], which the home screen sends
 * after toggling the floating trigger, to [GatenAccessibilityService.syncFloatingButton].
 */
class LookupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER_LOOKUP -> {
                if (GatenAccessibilityService.instance == null) {
                    Log.w(TAG, "Lookup requested but the accessibility service is not connected")
                    return
                }
                GatenAccessibilityService.triggerCapture()
            }
            TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED ->
                GatenAccessibilityService.instance?.syncFloatingButton()
        }
    }

    companion object {
        private const val TAG = "Gaten"
        const val ACTION_TRIGGER_LOOKUP = "com.gaten.app.action.TRIGGER_LOOKUP"
    }
}
