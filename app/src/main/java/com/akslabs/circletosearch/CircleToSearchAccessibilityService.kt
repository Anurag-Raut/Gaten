/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.akslabs.circletosearch

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.akslabs.circletosearch.data.BitmapRepository
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * YomiLens' only use of the accessibility framework: one screenshot per lookup.
 *
 * A lookup is started from outside — by the OnePlus Plus Key (Key Mapper) broadcast, by the Quick
 * Settings tile, or by anything else sending the trigger action — and this service answers by
 * capturing the screen, handing the bitmap to [BitmapRepository] and opening [OverlayActivity].
 *
 * There is no floating trigger any more, so the service can start activities from the background
 * without an overlay window of its own; and because it never inspects window content, the
 * accessibility declaration asks for screenshots only (`canTakeScreenshot`).
 */
class CircleToSearchAccessibilityService : AccessibilityService() {

    private val executor: Executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Freezes the current screen and opens the lookup overlay.
     *
     * `takeScreenshot` respects content protection, so surfaces marked FLAG_SECURE (DRM video)
     * come back blank; YomiLens never tries to bypass that.
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
                        launchOverlay()
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

    fun launchOverlay() {
        Log.d(TAG, "AccessibilityService launching OverlayActivity")
        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    /** Nothing to observe: a lookup is always started explicitly from outside this service. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        private const val TAG = "CircleToSearch"

        var instance: CircleToSearchAccessibilityService? = null
            private set

        /**
         * Entry point for the Quick Settings tile and the trigger broadcast. Acts as a toggle: a
         * trigger while the panel is up closes it instead of starting another lookup.
         */
        fun triggerCapture() {
            if (OverlayActivity.closeIfShowing()) {
                Log.d(TAG, "Lookup panel was open - closing it")
                return
            }
            Log.d(TAG, "triggerCapture static called. instance=${instance != null}")
            instance?.performCapture()
        }
    }
}
