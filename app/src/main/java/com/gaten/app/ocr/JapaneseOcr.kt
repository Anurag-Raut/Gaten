/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognised text line; [bounds] are relative to the bitmap that was recognised. */
data class OcrLine(val text: String, val bounds: Box)

/**
 * On-device Japanese OCR for a single cropped box.
 *
 * ML Kit finds lines and their boxes. Manga OCR recognizes selected text; lookup failures
 * are reported without substituting ML Kit text. Every path that reads a box — the
 * overlay's index line by line, and a circle's whole selection — reads it through
 * [readLineWithMangaOcr], so they crop and read it exactly the same way.
 */
object JapaneseOcr {
    private const val TAG = "GatenOcr"

    /** The ML Kit client, kept across calls and dropped by [release] when the models go idle. */
    private val mlKit = MlKitClient()

    /**
     * The raw ML Kit result for [bitmap], shared with callers that need a pass of their own
     * ([ScreenTextScanner]) so the client and the [Task] bridge stay in one place. The use is
     * counted for as long as the pass runs — the [Task] completing ends it, not the coroutine
     * returning, since cancelling the coroutine does not stop the native pass.
     */
    internal suspend fun mlKitResult(bitmap: Bitmap): Text {
        OcrModels.begin()
        val task = mlKit.run(onComplete = OcrModels::end) { recognizer ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        }
        return task.await()
    }

    /**
     * The lines ML Kit reads in [bitmap], in the order it reported them. Never throws: a failed pass
     * is an empty list, which the region path answers by reading the selection box itself.
     */
    suspend fun detectLines(bitmap: Bitmap): List<OcrLine> = try {
        val result = mlKitResult(bitmap)
        val lines = ArrayList<OcrLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val box = line.boundingBox ?: continue
                lines.add(OcrLine(text, Box(box.left, box.top, box.right, box.bottom)))
            }
        }
        lines
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "ML Kit Japanese OCR failed", e)
        emptyList()
    }

    /**
     * Reads one box with manga-ocr: the crop [OcrPreprocess] builds, through [MangaOcrEngine]. Null
     * when the model is not installed or the read failed; the lookup caller reports a retry error.
     *
     * A circle passes the exact crop it cut out of the capture as both [bitmap] and [bounds], so
     * [OcrPreprocess]'s padding is clamped to that crop's edge and the read cannot reach the lines
     * beside the selection.
     */
    suspend fun readLineWithMangaOcr(
        context: Context,
        bitmap: Bitmap,
        bounds: Box,
        priority: MangaOcrEngine.Priority = MangaOcrEngine.Priority.FOREGROUND
    ): String? {
        MangaOcrModel.installBundledIfPresent(context)
        if (!MangaOcrModel.isInstalled(context)) return null
        val crop = OcrPreprocess.cropForModel(bitmap, bounds) ?: return null
        try {
            return MangaOcrEngine.recognize(context, crop, priority)
        } finally {
            // Recognition's synchronous native calls have finished before it returns or cancels.
            if (crop !== bitmap) crop.recycle()
        }
    }

    /**
     * Drops the ML Kit client so the system can take its memory back; the next pass builds a new
     * one. A no-op while a pass is in flight, since closing the client under it would fail that
     * call. True when there was a client to drop.
     */
    fun release(): Boolean = mlKit.release()

    /** Bridges an ML Kit [Task] into coroutines without kotlinx-coroutines-play-services. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}

/**
 * The ML Kit text client and the passes pinning it.
 *
 * `process()` is asynchronous: it returns a [Task] that finishes on ML Kit's own thread, so a
 * coroutine cancelled while awaiting it does not stop the native pass. The client therefore stays
 * pinned from before `process()` until the [Task] has actually completed, in any of its outcomes —
 * a coroutine returning or being cancelled is not the end of a use. [release], asked to drop the
 * client for memory, skips while any pass is pinned rather than close it under one; the next call
 * after a release builds a fresh client.
 */
internal class MlKitClient(
    private val build: () -> TextRecognizer = {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
) {
    /** Runs a completion listener on the thread that finishes the task, so no Looper is needed. */
    private companion object {
        val DIRECT = Executor { it.run() }
    }

    private val lock = Any()

    /** The client, or null once [release] has dropped it. Guarded by [lock]. */
    private var client: TextRecognizer? = null

    /** Passes not yet completed; [release] skips while this is non-zero. Guarded by [lock]. */
    private var users = 0

    /**
     * Builds or reuses the client, runs [block] against it and returns its task. [onComplete] runs,
     * and the client unpins, exactly once: when the task completes (success, failure or cancel), or
     * right away if [block] throws before a task exists or the client cannot be built. The count
     * goes up only after the client is built, so a build that throws leaves nothing pinned.
     */
    fun <T> run(onComplete: () -> Unit = {}, block: (TextRecognizer) -> Task<T>): Task<T> {
        val recognizer = try {
            synchronized(lock) {
                val current = client ?: build().also { client = it }
                users++
                current
            }
        } catch (e: Throwable) {
            // No client was built, so nothing is pinned — but the caller's use still has to close.
            onComplete()
            throw e
        }
        val task = try {
            block(recognizer)
        } catch (e: Throwable) {
            // `process()` threw before a task existed: nothing will complete, so unpin here.
            finish(onComplete)
            throw e
        }
        task.addOnCompleteListener(DIRECT) { finish(onComplete) }
        return task
    }

    private fun finish(onComplete: () -> Unit) {
        synchronized(lock) { if (users > 0) users-- }
        onComplete()
    }

    /**
     * Drops the client so the system can take its memory back; the next [run] builds a new one. A
     * no-op while a pass is pinned, since closing the client under it would fail that call — the
     * idle timer or the next memory-pressure callback retries. True when there was a client to drop.
     */
    fun release(): Boolean {
        val dropped = synchronized(lock) {
            if (users > 0) return false
            val current = client
            client = null
            current
        }
        dropped?.close()
        return dropped != null
    }
}
