/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.gaten.app.R

/**
 * Asks the user for the screen-capture consent and hands the answer to [GatenCaptureService].
 *
 * The platform only hands a projection token back to an activity, so this is that activity and
 * nothing else: it is transparent, excluded from recents, draws no UI, and finishes the moment the
 * system dialog is answered. It exists for a few hundred milliseconds, which is what keeps the app
 * behind it — a video player — where it is.
 *
 * Started through this activity rather than from a service because the consent has to be requested
 * from an activity on every Android version, and because a Quick Settings tile has no other way to
 * close its own shade.
 */
class CaptureConsentActivity : ComponentActivity() {

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                GatenCaptureService.start(this, result.resultCode, data)
            } else {
                Log.d(TAG, "Screen capture consent was declined")
                Toast.makeText(this, R.string.toast_capture_declined, Toast.LENGTH_LONG).show()
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Once per launch, not once per instance: a recreation (a rotation while the system dialog
        // is up) must not stack a second consent dialog on top of the first.
        if (savedInstanceState != null) return
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        consent.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The whole display, never one app: a lookup happens wherever the reader is.
                projectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                projectionManager.createScreenCaptureIntent()
            }
        )
    }

    companion object {
        private const val TAG = "Gaten"

        /** The intent a launcher, the home screen or a tile uses to ask for the capture consent. */
        fun intent(context: Context): Intent =
            Intent(context, CaptureConsentActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
    }
}
