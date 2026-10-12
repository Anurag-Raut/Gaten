/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.gaten.app.data.BitmapRepository
import com.gaten.app.media.MediaPauser
import com.gaten.app.ui.overlay.OverlayWindowController
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Gaten' only use of the accessibility framework: screenshots the screen when a lookup is
 * triggered, stores the bitmap in [BitmapRepository] and draws the lookup overlay in a window of
 * its own — [OverlayWindowController], not an activity, so the app behind keeps playing.
 */
class GatenAccessibilityService : AccessibilityService() {

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private lateinit var overlay: OverlayWindowController

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayWindowController(this, MediaPauser(this))
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        // The window belongs to this service: without this it would stay on screen over everything
        // after the service goes away.
        overlay.hide()
        instance = null
    }

    /**
     * Freezes the current screen and opens the lookup overlay.
     *
     * `takeScreenshot` respects content protection, so surfaces marked FLAG_SECURE (DRM video)
     * come back blank; Gaten never tries to bypass that.
     */
    private fun performCapture() {
        Log.d(TAG, "performCapture called")

        // Clear the repository at the source so a stale capture can never flash.
        BitmapRepository.clear()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshot.hardwareBuffer
                        val colorSpace = screenshot.colorSpace

                        val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (bitmap == null) {
                            hardwareBuffer.close()
                            return
                        }

                        // Copy to a software bitmap, then release the hardware buffer.
                        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hardwareBuffer.close()
                        if (copy == null) return

                        BitmapRepository.setScreenshot(copy)
                        // takeScreenshot answers here, on the executor; the overlay is a window
                        // and belongs to the main thread.
                        Handler(Looper.getMainLooper()).post { showOverlay() }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare the captured screenshot", e)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                }
            }
        )
    }

    /**
     * Puts the capture the service just stored on screen.
     *
     * An activity used to do this and took the foreground, which stopped whatever was behind it;
     * an accessibility overlay window leaves the app behind foreground instead.
     */
    private fun showOverlay() {
        val bitmap = BitmapRepository.getScreenshot()
        if (bitmap == null) {
            Log.e(TAG, "No bitmap in Repository - not showing the overlay")
            return
        }
        Log.d(TAG, "Bitmap loaded from Repository. Size: ${bitmap.width}x${bitmap.height}")
        overlay.show(bitmap)
    }

    /** Nothing to observe: a lookup is always started explicitly from outside this service. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "Gaten"

        var instance: GatenAccessibilityService? = null
            private set

        /**
         * Entry point for the Quick Settings tile and the trigger broadcast. Acts as a toggle: a
         * trigger while the panel is up closes it instead of starting another lookup.
         */
        fun triggerCapture() {
            if (instance?.overlay?.hideIfShowing() == true) {
                Log.d(TAG, "Lookup panel was open - closing it")
                return
            }
            Log.d(TAG, "triggerCapture static called. instance=${instance != null}")
            instance?.performCapture()
        }
    }
}
