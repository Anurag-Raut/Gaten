/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.data

import android.graphics.Bitmap

/** Holds the single screenshot the accessibility service just captured, for the overlay to read. */
object BitmapRepository {
    private var screenshot: Bitmap? = null

    fun setScreenshot(bitmap: Bitmap?) {
        screenshot = bitmap
    }

    fun getScreenshot(): Bitmap? {
        return screenshot
    }

    fun clear() {
        screenshot = null
    }
}
