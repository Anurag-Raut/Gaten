/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * Integer rectangle in bitmap pixels.
 *
 * Deliberately not `android.graphics.Rect`: the geometry the hit tests and the circle's crop are
 * made of is the part most likely to be subtly wrong, and this way it stays unit-testable on the
 * JVM.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centreX: Int get() = (left + right) / 2
    val centreY: Int get() = (top + bottom) / 2

    /** True when the two boxes share at least one pixel row and column. */
    fun overlaps(other: Box): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}
