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
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.gaten.app.trigger.TriggerPreferences

/**
 * Quick Settings tile: asks the connected accessibility service to dismiss the shade and start a
 * lookup, or opens accessibility settings when the service is not enabled. Its state mirrors
 * whether the service is enabled.
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
            // Guide user to enable accessibility
            Toast.makeText(this, getString(R.string.toast_enable_accessibility_tile), Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
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
    }
}
