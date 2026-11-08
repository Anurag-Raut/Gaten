/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil

/**
 * PP-OCRv6 line recognition, on the optional model [PpOcrModel] installs.
 *
 * This is recognition only. The line box is decided by the caller — the same boxes ML Kit /
 * [ScreenLineReader] already produce — and this turns the crop into text. The graph is the
 * recogniser from the upstream PP-OCRv6 medium pair (ordinary screen and novel text): input `x`
 * `[N,3,48,W]` float32, output `fetch_name_0` `[N,T,18710]` float32 with `T` about `W/8`. Inputs are
 * BGR, scaled to `[0,1]` and normalised with mean and std of 0.5, exactly as PaddleOCR's
 * `RecResizeImg` does; this is deliberately not a grayscale square.
 *
 * The session is rebuilt when the files behind it change, and [OcrModels] releases it once idle.
 * Every call takes [mutex], so two lookups cannot drive the session at once.
 */
object PpOcrEngine {
    private const val TAG = "GatenOcr"

    /** Graph names, read off both exported models. */
    private const val INPUT_NAME = "x"
    private const val OUTPUT_NAME = "fetch_name_0"

    private val mutex = Mutex()

    /** The loaded session, together with the engine and files it was built from. */
    private var loaded: Loaded? = null

    private class Loaded(
        val engine: PpOcrModel.Engine,
        val key: String,
        val environment: OrtEnvironment,
        val session: OrtSession,
        val vocab: List<String>
    )

    /**
     * Reads the line in [crop] with [engine] and returns its text, or null when that model is not
     * installed, cannot be loaded, or inference fails. Never throws, and safe to call from several
     * coroutines: the calls are serialised on [mutex].
     */
    suspend fun recognize(
        context: Context,
        crop: Bitmap,
        engine: PpOcrModel.Engine,
        priority: OcrJobPriority = OcrJobPriority.FOREGROUND
    ): String? = withContext(Dispatchers.Default) {
        // The session is in use from here to the end of the call, so [OcrModels]' idle clock must
        // not run it out mid-inference: the use counts in on the way in and out on the way out.
        OcrModels.begin()
        if (priority == OcrJobPriority.FOREGROUND) OcrPriority.beginForeground()
        try {
            val queueStarted = SystemClock.elapsedRealtime()
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
                val queueWait = SystemClock.elapsedRealtime() - queueStarted
                if (queueWait > 0) Log.d(TAG, "pp-ocr queue wait=${queueWait}ms engine=${engine.id} priority=$priority")
                infer(context, crop, engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "pp-ocr inference failed (${engine.id})", e)
                null
            } catch (e: OutOfMemoryError) {
                Log.w(TAG, "pp-ocr ran out of memory", e)
                null
            } finally {
                mutex.unlock()
            }
        } finally {
            if (priority == OcrJobPriority.FOREGROUND) OcrPriority.endForeground()
            OcrModels.end()
        }
    }

    /**
     * One recogniser pass plus CTC decoding. Returns null when the model is not installed. A single
     * native call, run on the caller's dispatcher ([Dispatchers.Default]).
     */
    private fun infer(context: Context, crop: Bitmap, engine: PpOcrModel.Engine): String? {
        val started = SystemClock.elapsedRealtime()
        val model = load(context, engine) ?: return null
        val plan = PpOcrGeometry.plan(crop.width, crop.height)
        val input = PpOcrPreprocess.toModelInput(crop, plan)
        val preprocessedAt = SystemClock.elapsedRealtime()

        OnnxTensor.createTensor(
            model.environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, PpOcrGeometry.MODEL_HEIGHT.toLong(), plan.width.toLong())
        ).use { tensor ->
            model.session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                val output = result.get(OUTPUT_NAME).orElse(null) as? OnnxTensor ?: return null
                val shape = output.info.shape
                if (shape.size < 3) return null
                val steps = shape[shape.size - 2].toInt()
                val classes = shape[shape.size - 1].toInt()
                val text = PpOcrCtc.decode(output.floatBuffer, steps, classes, model.vocab)
                Log.d(
                    TAG,
                    "pp-ocr timing: load=${preprocessedAt - started}ms infer=${SystemClock.elapsedRealtime() - preprocessedAt}ms " +
                        "engine=${engine.id} width=${plan.width} steps=$steps"
                )
                return text
            }
        }
    }

    /**
     * The loaded session, built from the installed files when they are not already resident, or when
     * the files behind it changed (a re-install or a remove). Null when the engine is not installed
     * (per [PpOcrModel.isInstalled], not a bare file test), or when it cannot be loaded at all.
     */
    private fun load(context: Context, engine: PpOcrModel.Engine): Loaded? {
        if (!PpOcrModel.isInstalled(context, engine)) {
            close()
            return null
        }
        val modelFile = PpOcrModel.modelFile(context, engine)
        val dictFile = PpOcrModel.dictFile(context, engine)
        if (!modelFile.isFile || !dictFile.isFile) {
            close()
            return null
        }

        val key = "${engine.id}:${stamp(modelFile)}:${stamp(dictFile)}"
        loaded?.let { if (it.key == key) return it }
        close()

        return try {
            val environment = OrtEnvironment.getEnvironment()
            // Avoid retaining inference allocation caches between lookups. This can also make warm
            // inference slower; session reuse still avoids rebuilding the model each time.
            OrtSession.SessionOptions().use { sessionOptions ->
                sessionOptions.setCPUArenaAllocator(false)
                sessionOptions.setMemoryPatternOptimization(false)
                val vocab = PpOcrVocabulary.load(dictFile)
                var session: OrtSession? = null
                try {
                    val created = environment.createSession(modelFile.path, sessionOptions)
                    session = created
                    if (rejectMismatchedGraph(created, vocab)) {
                        created.close()
                        return null
                    }
                    Loaded(engine, key, environment, created, vocab).also { loaded = it }
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
            Log.w(TAG, "Cannot load the pp-ocr model for ${engine.id}", e)
            null
        }
    }

    /**
     * True when [session] does not look like the recogniser this code drives: it must take
     * [INPUT_NAME], produce [OUTPUT_NAME], and have as many output classes as the dictionary has
     * symbols, so a mismatched model/dictionary pair is refused rather than decoded into garbage.
     */
    private fun rejectMismatchedGraph(session: OrtSession, vocab: List<String>): Boolean {
        if (INPUT_NAME !in session.inputNames) {
            Log.w(TAG, "pp-ocr graph has no '$INPUT_NAME' input")
            return true
        }
        if (OUTPUT_NAME !in session.outputNames) {
            Log.w(TAG, "pp-ocr graph has no '$OUTPUT_NAME' output")
            return true
        }
        val shape = (session.outputInfo[OUTPUT_NAME]?.info as? TensorInfo)?.shape ?: return false
        val classes = shape.lastOrNull()?.toInt() ?: return false
        if (classes > 0 && classes != vocab.size) {
            Log.w(TAG, "pp-ocr output has $classes classes but the dictionary has ${vocab.size}")
            return true
        }
        return false
    }

    /** Path, size and modification time: enough to tell one install of a file from the next. */
    private fun stamp(file: File): String = "${file.path}:${file.length()}:${file.lastModified()}"

    /** Drops the session. The environment is the process-wide singleton and stays open. */
    private fun close() {
        val dropped = loaded ?: return
        loaded = null
        dropped.session.close()
    }

    /**
     * Drops the session so its memory (tens of MB for medium) goes back to the system; the next
     * [recognize] builds it again. Call it from [OcrModels] once the models have been idle, and when
     * the system is short on memory. It waits on [mutex], so a call already inside [recognize] keeps
     * a session that is still open until it is done. True when a session was dropped.
     */
    suspend fun release(): Boolean = mutex.withLock {
        val had = loaded != null
        close()
        had
    }
}

/**
 * The shape of one recogniser input: how the crop is turned, the tensor width, and how much of that
 * width the resized crop actually fills.
 *
 * Vertical text — a manga balloon column, a tategaki line — is read after a quarter turn, matching the
 * upstream reference pipeline (`cv2.ROTATE_90_COUNTERCLOCKWISE` when the crop is taller than wide).
 * The input follows PaddleOCR's `RecResizeImg`: at height 48 the crop keeps its aspect ratio, its
 * width is `ceil(48 * w / h)`, and the tensor is `width` columns wide with the resized crop placed
 * from the left (`resizedWidth` columns) and zeros — normalised mid-grey, not black — filling the
 * rest. The width is the crop's own ratio clamped into `[MIN_WIDTH, MAX_WIDTH]`: short lines are
 * padded up to the official 320, wider ones grow to keep their text at its true aspect, and past
 * [PpOcrGeometry.MAX_WIDTH] they are squeezed as `RecResizeImg` does past its input width. That one
 * bound keeps the timestep count about `width/8` (160 at the 1280 maximum) — and so the `[T,18710]`
 * output — bounded. Pure Kotlin so it can be unit tested.
 */
internal data class PpOcrPlan(val width: Int, val resizedWidth: Int, val rotated: Boolean)

internal object PpOcrGeometry {
    /** Both recognisers were exported at height 48. */
    internal const val MODEL_HEIGHT = 48

    /**
     * The input width floor. 320 is PaddleOCR's `RecResizeImg` default (`image_shape: [3,48,320]`),
     * so a line narrower than it is padded to 320 in the normalised-grey the padding uses.
     */
    internal const val MIN_WIDTH = 320

    /**
     * The single upper bound on the input width. A 48-pixel line of CJK glyphs is about square, so
     * 1280 covers roughly 26 characters before the crop is squeezed; the timestep count stays at most
     * 160 and the `[T,18710]` logits under ~12 MB. Lines wider than this keep their whole text but are
     * squeezed, the same as `RecResizeImg` does past its input width.
     */
    internal const val MAX_WIDTH = 1280

    internal fun plan(sourceWidth: Int, sourceHeight: Int): PpOcrPlan {
        val rotated = sourceHeight > sourceWidth
        val cropWidth = if (rotated) sourceHeight else sourceWidth
        val cropHeight = if (rotated) sourceWidth else sourceHeight
        val natural = if (cropHeight <= 0) {
            1
        } else {
            ceil(MODEL_HEIGHT.toDouble() * cropWidth / cropHeight).toInt().coerceAtLeast(1)
        }
        val width = natural.coerceIn(MIN_WIDTH, MAX_WIDTH)
        return PpOcrPlan(width, natural.coerceAtMost(MAX_WIDTH), rotated)
    }
}

/**
 * Turns one crop into the `[1,3,48,W]` float tensor both recognisers take: a quarter turn first when
 * [PpOcrPlan.rotated], resized to the plan's width at height 48, left-aligned on a zero canvas, then
 * planar BGR scaled to `[0,1]` and normalised by mean 0.5 / std 0.5 — PaddleOCR's
 * `DecodeImage(img_mode=BGR)` + `RecResizeImg`, which both models were trained and exported under.
 * Zero on the canvas is normalised mid-grey (the value a 127.5 pixel maps to), which is what
 * `RecResizeImg` pads with, not black.
 *
 * Bitmaps it creates are recycled in a `finally`; the caller's [crop] is never recycled, and an
 * allocation failure in between still frees what was created already.
 */
internal object PpOcrPreprocess {
    internal fun toModelInput(crop: Bitmap, plan: PpOcrPlan): FloatArray {
        val turned = if (plan.rotated) quarterTurnCounterClockwise(crop) else crop
        try {
            val width = plan.resizedWidth.coerceAtLeast(1)
            val height = PpOcrGeometry.MODEL_HEIGHT
            val scaled = if (turned.width != width || turned.height != height) {
                Bitmap.createScaledBitmap(turned, width, height, true)
            } else {
                turned
            }
            try {
                val plane = plan.width * height
                val input = FloatArray(plane * 3)
                val pixels = IntArray(width * height)
                scaled.getPixels(pixels, 0, width, 0, 0, width, height)
                for (row in 0 until height) {
                    val sourceRow = row * width
                    val targetRow = row * plan.width
                    for (column in 0 until width) {
                        val pixel = pixels[sourceRow + column]
                        val index = targetRow + column
                        input[index] = ((pixel and 0xFF) * 2f / 255f) - 1f
                        input[plane + index] = (((pixel shr 8) and 0xFF) * 2f / 255f) - 1f
                        input[plane * 2 + index] = (((pixel shr 16) and 0xFF) * 2f / 255f) - 1f
                    }
                }
                return input
            } finally {
                if (scaled !== crop && scaled !== turned) scaled.recycle()
            }
        } finally {
            if (turned !== crop) turned.recycle()
        }
    }

    private fun quarterTurnCounterClockwise(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

/**
 * CTC greedy decoding for the recognisers' `[T,18710]` logits: per timestep take the most likely
 * class, drop blanks (class 0) and collapse a class repeated across consecutive timesteps (the
 * duplicate collapse of PaddleOCR's `CTCLabelDecode`, which keeps a class that recurs after a blank).
 * A space is class `dict.size + 1` and is kept, as the upstream decoder keeps it. Pure, so it can be
 * unit tested without a session.
 */
internal object PpOcrCtc {
    internal fun decode(logits: FloatBuffer, steps: Int, classes: Int, vocab: List<String>): String {
        val text = StringBuilder()
        var previous = -1
        for (step in 0 until steps) {
            val base = step.toLong() * classes
            if (base + classes > logits.capacity()) break
            var best = 0
            var bestScore = logits.get(base.toInt())
            for (candidate in 1 until classes) {
                val score = logits.get((base + candidate).toInt())
                if (score > bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
            if (best != 0 && best != previous && best < vocab.size) text.append(vocab[best])
            previous = best
        }
        return text.toString()
    }
}

/**
 * The recognisers' shared dictionary: one symbol per line, indexed from 1 with the CTC blank at 0 and
 * a space appended last, so a decoded class maps straight onto a string.
 */
internal object PpOcrVocabulary {
    internal fun load(file: File): List<String> = listOf("blank") + file.readLines() + listOf(" ")
}
