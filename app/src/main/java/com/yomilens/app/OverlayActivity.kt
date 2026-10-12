/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yomilens.app.data.BitmapRepository
import com.yomilens.app.ui.lookup.LookupOverlay
import com.yomilens.app.ui.theme.YomiLensTheme

/**
 * Transparent overlay drawn over the frozen screen capture.
 *
 * YomiLens has exactly one overlay workflow — lasso a region, OCR it, look the words up in the
 * offline dictionary — so this activity is deliberately nothing more than that: the captured
 * bitmap plus [LookupOverlay].
 *
 * The activity is declared `singleInstance`, so a second capture arrives through [onNewIntent]
 * rather than a fresh `onCreate`. [Capture.id] is bumped for every new bitmap and used as the
 * composition key, which resets the overlay to selection mode; without it a re-trigger would
 * recompose over the previous, already-dismissed panel.
 */
class OverlayActivity : ComponentActivity() {

    /** One frozen capture; [id] makes every re-trigger a distinct composition. */
    private data class Capture(val bitmap: Bitmap, val id: Int)

    private val capture = mutableStateOf<Capture?>(null)
    private var nextCaptureId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setBackgroundDrawable(ColorDrawable(0))
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.d(TAG, "OverlayActivity onCreate")
        showing = this

        // The overlay must receive touches in the area it covers while staying transparent.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        if (!loadScreenshot()) return

        setContent {
            YomiLensTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    tonalElevation = 0.dp
                ) {
                    val current = capture.value
                    if (current != null) {
                        key(current.id) {
                            LookupOverlay(
                                screenshot = current.bitmap,
                                onDismiss = {
                                    BitmapRepository.clear()
                                    finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "OverlayActivity onNewIntent - loading new capture")
        setIntent(intent)

        // Drop the previous capture first so the old panel can never flash for the new one.
        capture.value = null
        loadScreenshot()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (showing === this) showing = null
        if (isFinishing) BitmapRepository.clear()
    }

    /** Publishes the bitmap the accessibility service stored; false when there is nothing to show. */
    private fun loadScreenshot(): Boolean {
        val bitmap = BitmapRepository.getScreenshot()
        if (bitmap == null) {
            Log.e(TAG, "No bitmap in Repository - closing overlay")
            finish()
            return false
        }
        Log.d(TAG, "Bitmap loaded from Repository. Size: ${bitmap.width}x${bitmap.height}")
        capture.value = Capture(bitmap, ++nextCaptureId)
        return true
    }

    companion object {
        private const val TAG = "YomiLens"

        /** The panel currently on screen, if any: set in [onCreate], cleared in [onDestroy]. */
        @Volatile
        private var showing: OverlayActivity? = null

        /** Closes the panel when it is already up; true when there was one to close. */
        fun closeIfShowing(): Boolean {
            val activity = showing ?: return false
            activity.runOnUiThread { activity.finish() }
            return true
        }
    }
}
