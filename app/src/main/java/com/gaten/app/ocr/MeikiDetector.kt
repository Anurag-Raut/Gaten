/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.roundToInt

/**
 * The optional line detector: meiki (`meiki.text.detect.v0`, a D-FINE detector fine-tuned on Japanese
 * manga and games), run on ONNX Runtime.
 *
 * It answers the one question the finder path asks — *where are the lines?* — for the lettering ML
 * Kit's own line detector never sees: hand-drawn manga balloons, outlined game UI text. It reads
 * nothing; its boxes are handed to ML Kit / manga-ocr as extra regions ([DetectionMerge] folds them
 * into ML Kit's without duplicating a line).
 *
 * The model (41 MB) ships inside the APK as the asset `meiki/detect.onnx`, fetched at build time by
 * the `fetchMeikiModel` Gradle task, and the session is built once from it and reused for the life
 * of the process. Every call takes [lock], so two lookups cannot drive the session at once.
 *
 * A screenshot is taller or wider than the model's square input, so it is cut into overlapping
 * square tiles ([DetectionMerge.planTiles]) and each tile is letterboxed to 640x640 and run on its
 * own; a line on a seam is seen whole in one tile. Boxes come back in the 640 px padded space and are
 * mapped through the inverse letterbox and the tile origin into bitmap pixels, then deduplicated by
 * IoU. Colors are BGR — what the reference scripts feed the model (OpenCV reads BGR and never
 * converts) — which on Android means swapping the bitmap's R and B.
 *
 * Every failure path returns an empty list: the model is optional, and a missing asset or a failed
 * inference must leave the lookup exactly as it was.
 */
object MeikiDetector {
    private const val TAG = "Meiki"

    /** Asset the model is packaged as, from `app/models/meiki/detect.onnx`. */
    private const val ASSET = "meiki/detect.onnx"

    /** Graph names, read off the exported model. */
    private const val IMAGES_INPUT = "images"
    private const val SIZES_INPUT = "orig_target_sizes"
    private const val BOXES_OUTPUT = "boxes"
    private const val SCORES_OUTPUT = "scores"

    /** Input side the detector takes; the second input is the same value for x and y. */
    private const val SIZE = DetectionMerge.MODEL_SIZE

    private val lock = Any()

    /** The built session, or null before the first successful call. Guarded by [lock]. */
    private var session: OrtSession? = null

    /** Set when the asset could not be loaded, so a missing model is not looked for every call. */
    private var unavailable = false

    /**
     * The text-line boxes meiki finds in [bitmap], in bitmap pixels, deduplicated across tiles.
     * Empty when the model is not packaged, cannot be loaded, or inference fails; never throws.
     */
    fun detect(context: Context, bitmap: Bitmap): List<Box> {
        val started = SystemClock.elapsedRealtime()
        val model = session(context) ?: return emptyList()
        return try {
            val scored = ArrayList<DetectionMerge.ScoredBox>()
            for (tile in DetectionMerge.planTiles(bitmap.width, bitmap.height)) {
                if (tile.width <= 0 || tile.height <= 0) continue
                scored.addAll(detectTile(model, bitmap, tile))
            }
            val boxes = DetectionMerge.nms(scored, DetectionMerge.CROSS_TILE_IOU).map { it.box }
            Log.d(TAG, "meiki: ${boxes.size} boxes, ${SystemClock.elapsedRealtime() - started} ms")
            boxes
        } catch (e: Exception) {
            Log.w(TAG, "meiki inference failed", e)
            emptyList()
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "meiki ran out of memory", e)
            emptyList()
        }
    }

    /** The session built from the packaged asset, or null when it is not there or cannot be opened. */
    private fun session(context: Context): OrtSession? {
        synchronized(lock) {
            session?.let { return it }
            if (unavailable) return null
            return try {
                val bytes = context.assets.open(ASSET).use { it.readBytes() }
                val built = OrtEnvironment.getEnvironment().createSession(bytes)
                session = built
                Log.d(TAG, "meiki: loaded $ASSET (${bytes.size / 1024 / 1024} MB)")
                built
            } catch (e: Exception) {
                Log.w(TAG, "meiki: cannot load $ASSET", e)
                unavailable = true
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "meiki: out of memory loading $ASSET", e)
                unavailable = true
                null
            }
        }
    }

    /** One tile through the model, its confident boxes mapped back into bitmap coordinates. */
    private fun detectTile(
        model: OrtSession,
        bitmap: Bitmap,
        tile: DetectionMerge.Tile
    ): List<DetectionMerge.ScoredBox> {
        val letterbox = DetectionMerge.letterbox(tile.width, tile.height, SIZE)
        val input = modelInput(bitmap, tile, letterbox)
        val environment = OrtEnvironment.getEnvironment()

        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())
        ).use { images ->
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(longArrayOf(SIZE.toLong(), SIZE.toLong())),
                longArrayOf(1, 2)
            ).use { sizes ->
                model.run(mapOf(IMAGES_INPUT to images, SIZES_INPUT to sizes)).use { outputs ->
                    val boxes = outputs.get(BOXES_OUTPUT).orElse(null) as? OnnxTensor ?: return emptyList()
                    val scores = outputs.get(SCORES_OUTPUT).orElse(null) as? OnnxTensor ?: return emptyList()
                    return readDetections(boxes, scores, letterbox, tile, bitmap)
                }
            }
        }
    }

    /**
     * The confident boxes in the model's outputs, mapped from the 640 px padded space into bitmap
     * pixels (inverse letterbox, then the tile origin) and clamped to the bitmap.
     */
    private fun readDetections(
        boxes: OnnxTensor,
        scores: OnnxTensor,
        letterbox: DetectionMerge.Letterbox,
        tile: DetectionMerge.Tile,
        bitmap: Bitmap
    ): List<DetectionMerge.ScoredBox> {
        val boxBuffer = boxes.floatBuffer
        val scoreBuffer = scores.floatBuffer
        val count = scoreBuffer.capacity()
        val result = ArrayList<DetectionMerge.ScoredBox>()
        for (index in 0 until count) {
            val score = scoreBuffer.get(index)
            if (score <= DetectionMerge.SCORE_THRESHOLD) continue
            val base = index * 4
            val model = Box(
                boxBuffer.get(base).roundToInt(),
                boxBuffer.get(base + 1).roundToInt(),
                boxBuffer.get(base + 2).roundToInt(),
                boxBuffer.get(base + 3).roundToInt()
            )
            val mapped = DetectionMerge.tileToBitmap(DetectionMerge.modelToBitmap(model, letterbox), tile)
            val clamped = clamp(mapped, bitmap.width, bitmap.height) ?: continue
            result.add(DetectionMerge.ScoredBox(clamped, score))
        }
        return result
    }

    /** [box] intersected with the bitmap, or null when nothing is left of it. */
    private fun clamp(box: Box, width: Int, height: Int): Box? {
        val left = box.left.coerceIn(0, width)
        val top = box.top.coerceIn(0, height)
        val right = box.right.coerceIn(0, width)
        val bottom = box.bottom.coerceIn(0, height)
        if (right <= left || bottom <= top) return null
        return Box(left, top, right, bottom)
    }

    /**
     * [tile] of [bitmap] as the model's `images` input: 1x3x640x640 float, CHW, BGR, pixel/255, the
     * tile scaled to fit the square and centred on black padding.
     *
     * The tile is scaled with the platform's bilinear filter, matching the reference scripts' OpenCV
     * `INTER_LINEAR`; the model is only reliable at the scale it was trained on, hence the tiling.
     */
    private fun modelInput(
        bitmap: Bitmap,
        tile: DetectionMerge.Tile,
        letterbox: DetectionMerge.Letterbox
    ): FloatArray {
        val scaledWidth = (tile.width * letterbox.ratio).toInt().coerceAtLeast(1)
        val scaledHeight = (tile.height * letterbox.ratio).toInt().coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width, tile.height)
        val scaled = Bitmap.createScaledBitmap(cropped, scaledWidth, scaledHeight, true)
        val pixels = IntArray(scaledWidth * scaledHeight)
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

        val plane = SIZE * SIZE
        val input = FloatArray(plane * 3)
        for (y in 0 until scaledHeight) {
            for (x in 0 until scaledWidth) {
                val pixel = pixels[y * scaledWidth + x]
                val target = (y + letterbox.padH) * SIZE + (x + letterbox.padW)
                // BGR: blue in channel 0, then green, then red (see the class note).
                input[target] = (pixel and 0xFF) / 255f
                input[plane + target] = (pixel shr 8 and 0xFF) / 255f
                input[2 * plane + target] = (pixel shr 16 and 0xFF) / 255f
            }
        }
        if (scaled !== cropped) scaled.recycle()
        cropped.recycle()
        return input
    }
}