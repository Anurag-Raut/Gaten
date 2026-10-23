/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.gaten.app.trigger.TriggerPreferences

/**
 * Quick Settings tile: asks the connected accessibility service to dismiss the shade and start a
 * lookup while lookup mode is on, and opens Gaten's own Screen lookup page while it is off. The
 * page raises the consent disclosure that has to come before Android's accessibility list, so the
 * tile never jumps past it. Its state mirrors whether the service is enabled.
 */
class LookupTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        TriggerPreferences.setTileAdded(this, true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        TriggerPreferences.setTileAdded(this, false)
    }

    override fun onClick() {
        super.onClick()

        Log.d(TAG, "Tile clicked. Service enabled: ${isAccessibilityServiceEnabled()}")

        if (isAccessibilityServiceEnabled()) {
            if (GatenAccessibilityService.instance == null) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_tile_service_not_ready),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            // The service dismisses Quick Settings and owns the capture pipeline, avoiding a
            // temporary activity that can expose the launcher while the shade is collapsing.
            GatenAccessibilityService.triggerCaptureFromTile()
        } else {
            // Lookup mode is off, so this opens Gaten's own page for it rather than Android's
            // accessibility list: that page is where lookup mode is turned on, and the consent
            // disclosure before the system list is raised from there. Nothing on this route
            // captures a screen, and coming back from it only re-reads whether the service is on.
            Toast.makeText(
                this,
                getString(R.string.toast_turn_on_lookup_mode),
                Toast.LENGTH_LONG
            ).show()
            openLookupSettings()
        }
    }

    /**
     * Gaten's Screen lookup page, brought up over a collapsing shade. From Android 14 that call
     * only takes a [PendingIntent]; before it, the plain intent is the spelling it still accepts.
     */
    private fun openLookupSettings() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_SETTINGS_PAGE, SETTINGS_PAGE_LOOKUP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        // Also covers tiles added before this version started tracking their presence.
        TriggerPreferences.setTileAdded(this, true)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = isAccessibilityServiceEnabled()

        // The state says whether a lookup can start, not that a capture is latched on: active
        // exactly while the service is enabled.
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        // Subtitles exist from API 29, which is this app's floor, so the words can always match
        // the state; whether there is room to show them stays the system's call.
        tile.subtitle = getString(
            if (isEnabled) R.string.tile_subtitle_ready else R.string.tile_subtitle_setup_needed
        )
        tile.updateTile()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name == GatenAccessibilityService::class.java.name) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "Gaten"

        /** The settings route key the shell reads to open its Screen lookup page. */
        private const val SETTINGS_PAGE_LOOKUP = "lookup"
    }
}
