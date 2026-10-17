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
import com.gaten.app.capture.GatenCaptureService
import com.gaten.app.trigger.TriggerPreferences

/**
 * External entry point so a key remapper can start a lookup: the [ACTION_TRIGGER_LOOKUP] broadcast
 * asks the capture service for a frame; a no-op when lookup mode is off, because nothing can be
 * captured without the projection the user consented to.
 *
 * It also relays [TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED], which the home screen sends
 * after toggling the floating trigger, to [GatenCaptureService.syncFloatingButton].
 */
class LookupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER_LOOKUP -> {
                if (!GatenCaptureService.isLookupModeActive()) {
                    Log.w(TAG, "Lookup requested but lookup mode is off")
                    return
                }
                GatenCaptureService.triggerCapture()
            }
            TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED ->
                GatenCaptureService.syncFloatingButton()
        }
    }

    companion object {
        private const val TAG = "Gaten"
        const val ACTION_TRIGGER_LOOKUP = "com.gaten.app.action.TRIGGER_LOOKUP"
    }
}
