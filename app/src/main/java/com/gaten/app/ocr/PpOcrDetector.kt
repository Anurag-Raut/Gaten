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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv6 text detection, on the optional `det.onnx` [PpOcrModel] carries.
 *
 * It finds the text lines of a whole screenshot as axis-aligned [Box]es, with no text attached: the
 * caller reads each box with [PpOcrEngine]. The graph is PaddleOCR's DB detector: input `x`
 * `[1,3,H,W]` float32 with dynamic `H` and `W` (both multiples of 32), output a probability map
 * `[1,1,H,W]` float32 in `[0,1]`. [DbPostProcess] turns the map into boxes.
 *
 * The session is rebuilt when the file behind it changes, and [OcrModels] releases it once idle,
 * following [PpOcrEngine]: the use counts against the idle clock, background work yields to the
 * foreground, and every call takes [mutex].
 */
object PpOcrDetector {
    private const val TAG = "GatenOcr"

    /** The input name read off the exported graph; the first input is used if a re-export renames it. */
    private const val INPUT_NAME = "x"

    private val mutex = Mutex()

    private var loaded: Loaded? = null

    private class Loaded(
        val key: String,
        val environment: OrtEnvironment,
        val session: OrtSession,
        val inputName: String,
        val outputName: String
    )

    /**
     * The text boxes of [bitmap], in the bitmap's own pixels and in reading order, or null when the
     * detector is not installed, cannot be loaded, or inference fails. An empty list is a successful
     * pass that found no text. Never throws except to propagate cancellation; safe to call from
     * several coroutines, serialised on [mutex].
     */
    suspend fun detect(
        context: Context,
        bitmap: Bitmap,
        priority: OcrJobPriority = OcrJobPriority.FOREGROUND
    ): List<Box>? = withContext(Dispatchers.Default) {
        OcrModels.begin()
        if (priority == OcrJobPriority.FOREGROUND) OcrPriority.beginForeground()
        try {
            while (true) {
                if (priority == OcrJobPriority.BACKGROUND) OcrPriority.awaitBackgroundTurn()
                mutex.lock()
                if (priority == OcrJobPriority.BACKGROUND && OcrPriority.foregroundPending()) {
                    mutex.unlock()
                    continue
                }
                break
            }
            try {
                infer(context, bitmap)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pp-ocr detection failed", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "pp-ocr detection ran out of memory", e)
                null
            } finally {
                mutex.unlock()
            }
        } finally {
            if (priority == OcrJobPriority.FOREGROUND) OcrPriority.endForeground()
            OcrModels.end()
        }
    }

    private fun infer(context: Context, bitmap: Bitmap): List<Box>? {
        val started = SystemClock.elapsedRealtime()
        val model = load(context) ?: return null
        val plan = PpOcrDetGeometry.plan(bitmap.width, bitmap.height)
        val input = PpOcrDetPreprocess.toModelInput(bitmap, plan)
        val preprocessedAt = SystemClock.elapsedRealtime()

        OnnxTensor.createTensor(
            model.environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, plan.height.toLong(), plan.width.toLong())
        ).use { tensor ->
            model.session.run(mapOf(model.inputName to tensor)).use { result ->
                val output = result.get(model.outputName).orElse(null) as? OnnxTensor ?: return null
                val shape = output.info.shape
                if (shape.size < 2) return null
                val mapHeight = shape[shape.size - 2].toInt()
                val mapWidth = shape[shape.size - 1].toInt()
                if (mapWidth <= 0 || mapHeight <= 0) return null
                val probabilities = FloatArray(mapWidth * mapHeight)
                val buffer = output.floatBuffer
                if (buffer.remaining() < probabilities.size) return null
                buffer.get(probabilities)
                val boxes = DbPostProcess.boxes(
                    probabilities, mapWidth, mapHeight, bitmap.width, bitmap.height
                )
                Log.d(
                    TAG,
                    "pp-ocr detect: prep=${preprocessedAt - started}ms " +
                        "infer=${SystemClock.elapsedRealtime() - preprocessedAt}ms " +
                        "input=${plan.width}x${plan.height} boxes=${boxes.size}"
                )
                return boxes
            }
        }
    }

    /** The loaded session, built from `det.onnx` when it is not resident or the file changed. */
    private fun load(context: Context): Loaded? {
        val file = PpOcrModel.detFile(context)
        if (file == null || !file.isFile) {
            close()
            return null
        }
        val key = "${file.path}:${file.length()}:${file.lastModified()}"
        loaded?.let { if (it.key == key) return it }
        close()

        return try {
            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                // Same trade as the recogniser: no allocation caches kept between lookups, and no
                // memory pattern, which a dynamic input size would only invalidate.
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                var session: OrtSession? = null
                try {
                    val created = environment.createSession(file.path, options)
                    session = created
                    val inputName = if (INPUT_NAME in created.inputNames) {
                        INPUT_NAME
                    } else {
                        created.inputNames.firstOrNull()
                    }
                    val outputName = created.outputNames.firstOrNull()
                    if (inputName == null || outputName == null) {
                        Log.w(TAG, "pp-ocr detector graph has no input or output")
                        created.close()
                        return null
                    }
                    Loaded(key, environment, created, inputName, outputName).also { loaded = it }
                } catch (e: Throwable) {
                    try {
                        session?.close()
                    } catch (closeError: Exception) {
                        e.addSuppressed(closeError)
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load the pp-ocr detector", e)
            null
        }
    }

    private fun close() {
        val dropped = loaded ?: return
        loaded = null
        dropped.session.close()
    }

    /**
     * Drops the session so its memory goes back to the system; the next [detect] builds it again.
     * Waits on [mutex], so a call already inside [detect] keeps its session until it is done. True
     * when a session was dropped.
     */
    suspend fun release(): Boolean = mutex.withLock {
        val had = loaded != null
        close()
        had
    }
}

/** The detector's input size: both sides multiples of 32, as the network's strides require. */
internal data class PpOcrDetPlan(val width: Int, val height: Int)

internal object PpOcrDetGeometry {
    /** Both sides must be a multiple of this (the DB network downsamples by up to 32). */
    internal const val STRIDE = 32

    /**
     * The longest side the detector is run at. PaddleOCR's `limit_type=max` default is 960: bigger
     * screenshots are shrunk to it, smaller ones are never enlarged (beyond the rounding to
     * [STRIDE]).
     */
    internal const val MAX_SIDE = 960

    internal fun plan(sourceWidth: Int, sourceHeight: Int): PpOcrDetPlan {
        val longest = max(sourceWidth, sourceHeight).coerceAtLeast(1)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toDouble() / longest else 1.0
        return PpOcrDetPlan(
            width = roundToStride(sourceWidth * scale),
            height = roundToStride(sourceHeight * scale)
        )
    }

    /** Nearest multiple of [STRIDE], at least one stride, and never past [MAX_SIDE]'s own multiple. */
    private fun roundToStride(value: Double): Int {
        val rounded = (value / STRIDE).roundToInt() * STRIDE
        return rounded.coerceIn(STRIDE, MAX_SIDE)
    }
}

/**
 * Turns the screenshot into the `[1,3,H,W]` float tensor the detector takes. PaddleOCR decodes with
 * OpenCV, so the channels are **BGR**: plane 0 is blue and is normalised with [MEAN]`[0]` and
 * [STD]`[0]` — `(pixel/255 - mean) / std`. The order lives in [CHANNEL_SHIFTS] alone, so flipping it
 * is one line.
 */
internal object PpOcrDetPreprocess {
    /** Normalisation per plane, in plane order (B, G, R). */
    internal val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    internal val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** The bit shift of each plane's channel inside an `ARGB_8888` pixel: B, G, R. */
    internal val CHANNEL_SHIFTS = intArrayOf(0, 8, 16)

    internal fun toModelInput(bitmap: Bitmap, plan: PpOcrDetPlan): FloatArray {
        val scaled = if (bitmap.width != plan.width || bitmap.height != plan.height) {
            Bitmap.createScaledBitmap(bitmap, plan.width, plan.height, true)
        } else {
            bitmap
        }
        try {
            val pixels = IntArray(plan.width * plan.height)
            scaled.getPixels(pixels, 0, plan.width, 0, 0, plan.width, plan.height)
            return normalise(pixels, plan.width, plan.height)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Planar float tensor data for ARGB [pixels]; pure, so the channel order is unit-tested. */
    internal fun normalise(pixels: IntArray, width: Int, height: Int): FloatArray {
        val plane = width * height
        val input = FloatArray(plane * 3)
        for (channel in 0 until 3) {
            val shift = CHANNEL_SHIFTS[channel]
            val scale = 1f / (255f * STD[channel])
            val offset = MEAN[channel] / STD[channel]
            val base = channel * plane
            for (i in 0 until plane) {
                input[base + i] = ((pixels[i] shr shift) and 0xFF) * scale - offset
            }
        }
        return input
    }
}

/**
 * DB post-processing in pure Kotlin (no OpenCV on the device): the probability map the detector
 * returns becomes axis-aligned line boxes in the screenshot's own pixels. It follows PaddleOCR's
 * `DBPostProcess` with the PP-OCRv6 medium detector's parameters, with the rotated-rectangle steps
 * replaced by their axis-aligned equivalents:
 *
 *  1. binarise the map at [THRESH];
 *  2. label 8-connected components (at most [MAX_CANDIDATES]) — the stand-in for `findContours`;
 *  3. drop a component whose short side is under [MIN_SIZE] map pixels;
 *  4. score it as the mean probability over its bounding box (`box_score_fast`), dropping it under
 *     [BOX_THRESH];
 *  5. unclip: grow every side by `area * ratio / perimeter`, PaddleOCR's offset;
 *  6. drop a box whose short side is then under [MIN_SIZE] + 2;
 *  7. scale from map to screenshot pixels and clamp;
 *  8. order for reading ([readingOrder]).
 *
 * Takes plain arrays, so it is unit-tested without ONNX.
 */
internal object DbPostProcess {
    /** Probability above which a map pixel is text. */
    internal const val THRESH = 0.2f

    /** Least mean probability inside a component's box for it to be kept. */
    internal const val BOX_THRESH = 0.45f

    /** Expansion ratio of the unclip step. */
    internal const val UNCLIP_RATIO = 1.4f

    /** At most this many components are examined. */
    internal const val MAX_CANDIDATES = 3000

    /** Smallest short side, in map pixels. */
    internal const val MIN_SIZE = 3

    /**
     * The boxes of [probabilities], a row-major `[mapHeight, mapWidth]` map, scaled to a
     * [sourceWidth] x [sourceHeight] screenshot. Empty when the map holds no text.
     */
    internal fun boxes(
        probabilities: FloatArray,
        mapWidth: Int,
        mapHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<Box> {
        if (mapWidth <= 0 || mapHeight <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return emptyList()
        if (probabilities.size < mapWidth * mapHeight) return emptyList()
        val scaleX = sourceWidth.toDouble() / mapWidth
        val scaleY = sourceHeight.toDouble() / mapHeight

        val out = ArrayList<Box>()
        for (component in components(probabilities, mapWidth, mapHeight)) {
            val width = component.maxX - component.minX + 1
            val height = component.maxY - component.minY + 1
            if (min(width, height) < MIN_SIZE) continue
            if (score(probabilities, mapWidth, component) < BOX_THRESH) continue

            val distance = width.toDouble() * height * UNCLIP_RATIO / (2.0 * (width + height))
            val left = component.minX - distance
            val top = component.minY - distance
            val right = component.maxX + 1 + distance
            val bottom = component.maxY + 1 + distance
            if (min(right - left, bottom - top) < MIN_SIZE + 2) continue

            val box = Box(
                floor((left * scaleX)).toInt().coerceIn(0, sourceWidth),
                floor((top * scaleY)).toInt().coerceIn(0, sourceHeight),
                ceil((right * scaleX)).toInt().coerceIn(0, sourceWidth),
                ceil((bottom * scaleY)).toInt().coerceIn(0, sourceHeight)
            )
            if (box.width > 0 && box.height > 0) out.add(box)
        }
        return readingOrder(out)
    }

    /** One connected component of the binarised map: its extent in map pixels. */
    internal class Component(var minX: Int, var minY: Int, var maxX: Int, var maxY: Int)

    /**
     * The 8-connected components of `probabilities > THRESH`, in raster order of their first pixel,
     * capped at [MAX_CANDIDATES]. An explicit stack, so a screen-sized blob cannot overflow the call
     * stack.
     */
    internal fun components(probabilities: FloatArray, width: Int, height: Int): List<Component> {
        val visited = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val found = ArrayList<Component>()
        for (start in 0 until width * height) {
            if (visited[start] || probabilities[start] <= THRESH) continue
            if (found.size >= MAX_CANDIDATES) break
            val component = Component(start % width, start / width, start % width, start / width)
            var top = 0
            stack[top++] = start
            visited[start] = true
            while (top > 0) {
                val index = stack[--top]
                val x = index % width
                val y = index / width
                if (x < component.minX) component.minX = x
                if (x > component.maxX) component.maxX = x
                if (y < component.minY) component.minY = y
                if (y > component.maxY) component.maxY = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (!visited[next] && probabilities[next] > THRESH) {
                            visited[next] = true
                            stack[top++] = next
                        }
                    }
                }
            }
            found.add(component)
        }
        return found
    }

    /** Mean probability over [component]'s bounding box, the axis-aligned `box_score_fast`. */
    private fun score(probabilities: FloatArray, width: Int, component: Component): Float {
        var sum = 0.0
        for (y in component.minY..component.maxY) {
            val row = y * width
            for (x in component.minX..component.maxX) sum += probabilities[row + x]
        }
        val area = (component.maxX - component.minX + 1).toLong() * (component.maxY - component.minY + 1)
        return (sum / area).toFloat()
    }

    /**
     * [boxes] in the order a reader meets them, which is also the order ML Kit reports lines in.
     * Mostly-vertical text (tategaki, manga) reads in columns from the right edge to the left,
     * each column top to bottom; anything else reads in rows from the top, each row left to right.
     * Boxes whose centres fall within half the shorter box of one another across the reading
     * direction share a row (column).
     */
    internal fun readingOrder(boxes: List<Box>): List<Box> {
        if (boxes.size < 2) return boxes
        val vertical = boxes.count { it.height > it.width * 1.5 } * 2 > boxes.size
        val sorted = if (vertical) {
            boxes.sortedWith(compareByDescending<Box> { it.centreX }.thenBy { it.top })
        } else {
            boxes.sortedWith(compareBy<Box> { it.centreY }.thenBy { it.left })
        }
        // Same-row boxes differ a little in height, so a pure centre sort would interleave them.
        // Walk the sorted list and let a box that is level with the one before it move ahead of it
        // when it sits earlier in the reading direction inside the row.
        val result = sorted.toMutableList()
        for (i in 1 until result.size) {
            var j = i
            while (j > 0 && sameLine(result[j - 1], result[j], vertical) &&
                earlierInLine(result[j], result[j - 1], vertical)
            ) {
                val swap = result[j]
                result[j] = result[j - 1]
                result[j - 1] = swap
                j--
            }
        }
        return result
    }

    private fun sameLine(a: Box, b: Box, vertical: Boolean): Boolean =
        if (vertical) {
            kotlin.math.abs(a.centreX - b.centreX) < min(a.width, b.width) / 2
        } else {
            kotlin.math.abs(a.centreY - b.centreY) < min(a.height, b.height) / 2
        }

    private fun earlierInLine(a: Box, b: Box, vertical: Boolean): Boolean =
        if (vertical) a.top < b.top else a.left < b.left
}
