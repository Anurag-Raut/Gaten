/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.gaten.app.ocr.Box

/**
 * The transform between the capture's pixels and the overlay window's pixels.
 *
 * The recognised boxes are in screenshot pixels and everything they are drawn and hit-tested
 * against is in view pixels, which are the same only while the window covers the whole display
 * from its origin — the case this maps to identity. A window the platform shrank for an inset, or
 * a capture at a different resolution than the window, is where they part, and then the frozen
 * frame is drawn *and* every box is placed through the same mapping: what the overlay highlights
 * always sits on the pixels it names, whatever the window turned out to be.
 *
 * Bitmap (0,0) is taken to be the display's origin, and [offsetX]/[offsetY] are the window's own
 * origin on the display, so a pixel captured at the display origin lands at the window's origin
 * minus that offset. The scale is the window's size over the capture's, which is exact in the
 * cases that matter and is what keeps the two ends of the mapping each other's inverse.
 *
 * Pure arithmetic, no Android: the unit tests drive it directly.
 */
data class ViewMapping(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float
) {

    /** Bitmap pixel -> view pixel. */
    fun toView(x: Float, y: Float): Offset =
        Offset(x * scaleX - offsetX, y * scaleY - offsetY)

    /** Bitmap box -> the view rectangle it occupies. */
    fun toView(box: Box): Rect = Rect(
        left = box.left * scaleX - offsetX,
        top = box.top * scaleY - offsetY,
        right = box.right * scaleX - offsetX,
        bottom = box.bottom * scaleY - offsetY
    )

    /** View pixel -> bitmap pixel, the way back for a gesture's coordinates. */
    fun toBitmap(x: Float, y: Float): Offset =
        Offset((x + offsetX) / scaleX, (y + offsetY) / scaleY)

    /** View rectangle -> the capture rectangle it covers, for the region crop and the hit tests. */
    fun toBitmap(rect: Rect): Rect = Rect(
        left = (rect.left + offsetX) / scaleX,
        top = (rect.top + offsetY) / scaleY,
        right = (rect.right + offsetX) / scaleX,
        bottom = (rect.bottom + offsetY) / scaleY
    )

    /** How many view pixels the smaller axis gives one bitmap pixel: the worst case for a distance. */
    val minScale: Float get() = minOf(scaleX, scaleY)

    companion object {
        /** The mapping when the window and the capture are the same pixels: every lookup's default. */
        val IDENTITY = ViewMapping(1f, 1f, 0f, 0f)

        /**
         * The mapping for a [bitmapWidth]x[bitmapHeight] capture shown in a [viewWidth]x
         * [viewHeight] window whose origin is ([originX], [originY]) on the display.
         *
         * Identity when the sizes match and the origin is the display's. Also identity - the only
         * safe answer - when a size is not known yet, which is the window before its first layout.
         */
        fun between(
            bitmapWidth: Int,
            bitmapHeight: Int,
            viewWidth: Int,
            viewHeight: Int,
            originX: Int = 0,
            originY: Int = 0
        ): ViewMapping {
            if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
                return IDENTITY
            }
            return ViewMapping(
                scaleX = viewWidth.toFloat() / bitmapWidth,
                scaleY = viewHeight.toFloat() / bitmapHeight,
                offsetX = originX.toFloat(),
                offsetY = originY.toFloat()
            )
        }
    }
}
