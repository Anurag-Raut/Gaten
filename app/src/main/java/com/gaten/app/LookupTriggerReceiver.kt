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
 * External entry point so a key remapper can start a lookup: a trigger broadcast asks the running
 * accessibility service to screenshot the screen; a no-op when it is not connected.
 *
 * [ACTION_TRIGGER_LOOKUP] is the canonical action and [ACTION_TRIGGER_LOOKUP_LEGACY] the earlier
 * `com.gaten.app.action.TRIGGER_LOOKUP` name, kept so a Key Mapper map made before the app's package
 * changed keeps working. Both enter the same capture route, once per broadcast.
 *
 * The trigger actions are answered by the service's own context-registered receiver
 * ([GatenAccessibilityService] registers one): a custom, action-only broadcast is not delivered to
 * a manifest receiver on apps targeting Android 8.0+, and that is the form a key remapper may
 * send. This manifest receiver stays exported for a sender that names this component explicitly,
 * and it relays [TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED], which the home screen sends
 * after toggling the floating trigger, to [GatenAccessibilityService.syncFloatingButton].
 */
class LookupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TRIGGER_LOOKUP, ACTION_TRIGGER_LOOKUP_LEGACY -> {
                Log.d(TAG, "Trigger broadcast received: ${intent.action}")
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

        /** Canonical external trigger action, matching the installed `com.gaten` package. */
        const val ACTION_TRIGGER_LOOKUP = "com.gaten.action.TRIGGER_LOOKUP"

        /** The earlier action name, still accepted so an existing Key Mapper map keeps working. */
        const val ACTION_TRIGGER_LOOKUP_LEGACY = "com.gaten.app.action.TRIGGER_LOOKUP"
    }
}
