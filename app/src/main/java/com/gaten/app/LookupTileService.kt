/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import com.gaten.app.trigger.TriggerPreferences

/**
 * Quick Settings tile: asks the connected accessibility service to dismiss the shade and start a
 * lookup while lookup mode is on, and opens Gaten's own Screen lookup page while it is off so the
 * consent disclosure comes before Android's accessibility list. The tile can also be switched off
 * in Gaten's settings; while off it is unavailable and ignores taps.
 */
class LookupTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        TriggerPreferences.setTileAdded(this, true)
        // A tile the reader added by hand is one they want working.
        TriggerPreferences.setTileEnabled(this, true)
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        TriggerPreferences.setTileAdded(this, false)
    }

    override fun onClick() {
        super.onClick()

        // Switched off: the tile is drawn unavailable and does nothing; Android's editor removes it.
        if (!TriggerPreferences.tileEnabled(this)) {
            return
        }

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
        val serviceEnabled = isAccessibilityServiceEnabled()

        tile.state = when {
            !TriggerPreferences.tileEnabled(this) -> Tile.STATE_UNAVAILABLE
            serviceEnabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(
            when {
                !TriggerPreferences.tileEnabled(this) -> R.string.tile_subtitle_disabled
                serviceEnabled -> R.string.tile_subtitle_ready
                else -> R.string.tile_subtitle_setup_needed
            }
        )
        tile.updateTile()
    }

    /**
     * True when [GatenAccessibilityService] is one of the platform's enabled accessibility
     * services, read from the platform's own list of enabled components.
     *
     * [android.view.accessibility.AccessibilityManager.getEnabledAccessibilityServiceList] is
     * deliberately not used: on Android 14 it can return an empty list for a service that is
     * enabled and bound (observed on an API 34 emulator), which left the tile saying "Setup needed"
     * and ignoring taps. The enabled-component setting is the same answer the app's own lookup-mode
     * check reads, so the tile and the Screen lookup page cannot disagree.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, GatenAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val services = TextUtils.SimpleStringSplitter(':')
        services.setString(enabled)
        while (services.hasNext()) {
            if (ComponentName.unflattenFromString(services.next()) == expected) return true
        }
        return false
    }

    companion object {
        private const val TAG = "Gaten"

        /** The settings route key the shell reads to open its Screen lookup page. */
        private const val SETTINGS_PAGE_LOOKUP = "lookup"
    }
}
