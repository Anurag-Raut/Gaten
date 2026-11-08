/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The line box to feed a recogniser, cut out of the bitmap that holds it.
 *
 * A detector's line box is tight around the ink — a stroke touching the edge is otherwise cut off
 * when the crop is taken — so the box is grown by ~10% of its height and clamped into the bitmap
 * before it is used. The recogniser does its own resize, so this is geometry only: the padded,
 * clamped rectangle, never a bitmap. Pure Kotlin so the padding and clamping are unit-tested on the
 * JVM.
 */
object LineCrop {

    /** Padding around the line box, as a fraction of its height. */
    private const val PAD_FRACTION = 0.1f

    /**
     * The padded, clamped crop of [box] within a [bitmapWidth] x [bitmapHeight] bitmap. Never
     * returns an empty rectangle, whatever the box says.
     */
    fun cropFor(box: Box, bitmapWidth: Int, bitmapHeight: Int): Box {
        val pad = max(1, (box.height * PAD_FRACTION).roundToInt())
        val left = (box.left - pad).coerceIn(0, bitmapWidth - 1)
        val top = (box.top - pad).coerceIn(0, bitmapHeight - 1)
        val right = (box.right + pad).coerceIn(left + 1, bitmapWidth)
        val bottom = (box.bottom + pad).coerceIn(top + 1, bitmapHeight)
        return Box(left, top, right, bottom)
    }
}
