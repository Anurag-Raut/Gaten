/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.trigger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.gaten.app.capture.GatenCaptureService

/**
 * What the Quick Settings tile starts: nothing but a lookup, and a window the system can collapse
 * the shade for.
 *
 * A tile cannot close its own shade without starting an activity — the accessibility service used
 * to do it with a global action, and that is exactly what lookup mode no longer has — so the tile
 * starts this instead, the shade collapses on the way, and the activity is gone before the frozen
 * frame is taken. It draws nothing and finishes in [onCreate], which is what keeps the app behind
 * it where it is.
 */
class TileCaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Tile capture requested")
        GatenCaptureService.triggerCapture(fromShade = true)
        finish()
    }

    companion object {
        private const val TAG = "Gaten"

        /** The intent for [android.service.quicksettings.TileService.startActivityAndCollapse]. */
        fun intent(context: Context): Intent =
            Intent(context, TileCaptureActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
    }
}
