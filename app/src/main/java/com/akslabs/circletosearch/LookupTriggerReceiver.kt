package com.akslabs.circletosearch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * External entry point so a key remapper can start a lookup.
 *
 * Send a broadcast to `com.akslabs.circletosearch` with action [ACTION_TRIGGER_LOOKUP]; the
 * running accessibility service takes the screenshot and opens the overlay.
 *
 * Key Mapper 4.x (its "Intent" action) needs: target *Broadcast receiver*,
 * Action = `com.akslabs.circletosearch.action.TRIGGER_LOOKUP`,
 * Package name = `com.akslabs.circletosearch`.
 *
 * A no-op when the accessibility service is not connected, because only that service can grab the
 * screen.
 */
class LookupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER_LOOKUP) return

        if (CircleToSearchAccessibilityService.instance == null) {
            Log.w(TAG, "Lookup requested but the accessibility service is not connected")
            return
        }
        CircleToSearchAccessibilityService.triggerCapture()
    }

    companion object {
        private const val TAG = "CircleToSearch"
        const val ACTION_TRIGGER_LOOKUP = "com.akslabs.circletosearch.action.TRIGGER_LOOKUP"
    }
}
