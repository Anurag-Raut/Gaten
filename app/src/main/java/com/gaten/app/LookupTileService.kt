/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast
import com.gaten.app.capture.CaptureConsentActivity
import com.gaten.app.capture.GatenCaptureService
import com.gaten.app.trigger.TileCaptureActivity
import com.gaten.app.trigger.TriggerPreferences

/**
 * Quick Settings tile: with lookup mode on it starts a lookup, and with lookup mode off it asks for
 * the screen-capture consent that starts it. Its state mirrors whether the projection behind the
 * overlay is alive.
 *
 * Both paths go through an activity, because a tile cannot close its own shade on its own: the
 * system collapses the shade for whatever activity the tile starts, and the activity does nothing
 * else — it never draws, and it finishes at once.
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
        Log.d(TAG, "Tile clicked. Lookup mode: ${GatenCaptureService.isLookupModeActive()}")

        val intent = if (GatenCaptureService.isLookupModeActive()) {
            TileCaptureActivity.intent(this)
        } else if (!Settings.canDrawOverlays(this)) {
            // Lookup mode needs this before anything can be drawn over the app being read, and a
            // tile has no other way to say so: the Settings screen that grants it is what the tile
            // opens, and the shade still collapses on the way.
            Toast.makeText(this, R.string.toast_overlay_needed, Toast.LENGTH_LONG).show()
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            // There is no projection to take a frame from, and asking for one is the only way back
            // to a working tile: the consent dialog is the point of the tap.
            CaptureConsentActivity.intent(this)
        }
        collapseAndStart(intent)
    }

    override fun onStartListening() {
        super.onStartListening()
        // Also covers tiles added before this version started tracking their presence.
        TriggerPreferences.setTileAdded(this, true)
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (GatenCaptureService.isLookupModeActive()) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }

    /** Starts the trigger activity and lets the system take the shade down on the way. */
    private fun collapseAndStart(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val TAG = "Gaten"
    }
}
