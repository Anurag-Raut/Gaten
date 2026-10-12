/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns one recognised line's box into the grayscale square the manga-ocr encoder takes.
 *
 * The encoder was trained on tight crops of a single line, so the box ML Kit reported is grown by
 * ~10% of its height (a stroke touching the box edge is otherwise cut off) and then scaled up when
 * the line is small: its 16 px patches need the strokes to survive, and a 20 px line on a phone
 * screen is normal. Grayscale, not binarized — the model was trained on the antialiased gray the
 * screenshot already carries, and thresholding outlined or anti-aliased text destroys it.
 *
 * The geometry ([cropFor]) is pure Kotlin so the padding and scaling can be unit tested; only
 * [cropForModel] and [toModelInput] touch a [Bitmap].
 */
object OcrPreprocess {
    private const val TAG = "GatenOcr"

    /** Input side the ViT encoder takes (preprocessor_config.json: 224x224). */
    private const val MODEL_SIZE = 224

    /** Line height the crop is upscaled to before the encoder's own resize. */
    private const val MIN_CROP_HEIGHT = 48

    /** Longest side the crop is allowed to reach, so a huge box cannot blow up the resize. */
    private const val MAX_CROP_SIDE = 1024

    /** Padding around the line box, as a fraction of its height. */
    private const val PAD_FRACTION = 0.1f

    /**
     * Where to cut a line out of its bitmap and by how much to scale it: [left]/[top]/[width]/
     * [height] index the source bitmap, [scale] applies to the crop.
     */
    data class Crop(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val scale: Float
    ) {
        val scaledWidth: Int get() = max(1, (width * scale).roundToInt())
        val scaledHeight: Int get() = max(1, (height * scale).roundToInt())
    }

    /**
     * The padded, clamped crop of [box] within a [bitmapWidth] x [bitmapHeight] bitmap, and the
     * scale that brings its height to at least [MIN_CROP_HEIGHT] without letting the longest side
     * pass [MAX_CROP_SIDE]. Never returns an empty rectangle, whatever the box says.
     */
    fun cropFor(box: Box, bitmapWidth: Int, bitmapHeight: Int): Crop {
        val pad = max(1, (box.height * PAD_FRACTION).roundToInt())
        val left = (box.left - pad).coerceIn(0, bitmapWidth - 1)
        val top = (box.top - pad).coerceIn(0, bitmapHeight - 1)
        val right = (box.right + pad).coerceIn(left + 1, bitmapWidth)
        val bottom = (box.bottom + pad).coerceIn(top + 1, bitmapHeight)
        val width = right - left
        val height = bottom - top
        return Crop(left, top, width, height, scaleFor(width, height))
    }

    /**
     * The upscale that lifts a [height]-tall crop to [MIN_CROP_HEIGHT], capped so neither side of
     * the result passes [MAX_CROP_SIDE]. The cap can scale a wide crop down, which is the point:
     * a 4000 px line is more than the encoder can use.
     */
    private fun scaleFor(width: Int, height: Int): Float =
        min(max(1f, MIN_CROP_HEIGHT.toFloat() / height), MAX_CROP_SIDE.toFloat() / max(width, height))

    /**
     * The line [box] as the encoder's input bitmap: cropped, upscaled, grayscale. Null when the
     * crop cannot be produced (the box lies outside the bitmap, or the device is out of memory —
     * the upscale allocates a bitmap of its own, so a loaded device can refuse it).
     * Releases intermediate bitmaps; the caller owns the returned grayscale bitmap.
     */
    fun cropForModel(bitmap: Bitmap, box: Box): Bitmap? = try {
        val crop = cropFor(box, bitmap.width, bitmap.height)
        val cut = Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        try {
            val scaled = if (crop.scale == 1f) {
                cut
            } else {
                Bitmap.createScaledBitmap(cut, crop.scaledWidth, crop.scaledHeight, true)
            }
            try {
                grayscale(scaled)
            } finally {
                if (scaled !== cut && scaled !== bitmap) scaled.recycle()
            }
        } finally {
            // A full-image crop can be the shared source itself.
            if (cut !== bitmap) cut.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "manga-ocr crop failed for $box", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "manga-ocr crop ran out of memory for $box", e)
        null
    }

    /**
     * [bitmap] as the encoder's `pixel_values`: 1x3x[MODEL_SIZE]x[MODEL_SIZE] float, CHW, rescaled
     * by 1/255 and normalized by the model's mean and standard deviation (both 0.5).
     *
     * The crop is grayscale, so the single channel is replicated across RGB — which is what
     * manga-ocr itself feeds the encoder (`Image.convert("L").convert("RGB")`).
     */
    fun toModelInput(bitmap: Bitmap): FloatArray {
        val scaled = if (bitmap.width == MODEL_SIZE && bitmap.height == MODEL_SIZE) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, MODEL_SIZE, MODEL_SIZE, true)
        }
        try {
            val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
            scaled.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

            val plane = MODEL_SIZE * MODEL_SIZE
            val input = FloatArray(plane * 3)
            for (index in pixels.indices) {
                val gray = ((pixels[index] and 0xFF) / 255f - 0.5f) / 0.5f
                input[index] = gray
                input[plane + index] = gray
                input[2 * plane + index] = gray
            }
            return input
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /**
     * [bitmap] in grayscale, weighted the way PIL's `convert("L")` weighs it (Rec. 601 luma), which
     * is the conversion the model was trained on. `ColorMatrix.setSaturation(0f)` would weigh the
     * channels differently (Rec. 709) and is not the same picture.
     */
    private fun grayscale(bitmap: Bitmap): Bitmap {
        val luma = floatArrayOf(
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0.299f, 0.587f, 0.114f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(luma)) }
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }
}
