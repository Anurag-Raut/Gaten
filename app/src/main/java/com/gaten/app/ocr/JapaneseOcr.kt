/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognised text line; [bounds] are relative to the bitmap that was recognised. */
data class OcrLine(val text: String, val bounds: Box)

/**
 * On-device Japanese OCR for a single cropped region.
 *
 * ML Kit is the detector and the fallback reader: it finds the lines and their boxes, and its
 * reading is what the reader shows when nothing better is available. When the optional manga-ocr
 * model is installed it is the reader instead — [readLines] re-reads the lines ML Kit found, and
 * [readLineWithMangaOcr] reads the box the user circled when ML Kit found no Japanese in the region
 * (see [RegionReadPolicy]).
 *
 * Both the region path and the overlay's index read a line through [readLineWithMangaOcr], so they
 * crop and read it exactly the same way.
 */
object JapaneseOcr {
    private const val TAG = "GatenOcr"

    /** Lines one region pass reads with manga-ocr. Each is an encoder pass plus a decode. */
    private const val MAX_READ_LINES = 8

    /** ML Kit clients are expensive to build and safe to reuse; the model is app-lifetime. */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /**
     * The raw ML Kit result for [bitmap], shared with callers that need a pass of their own
     * ([ScreenTextScanner]) so the client and the [Task] bridge stay in one place.
     */
    internal suspend fun mlKitResult(bitmap: Bitmap): Text =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

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
     * [lines] with the text manga-ocr read for them, in reading order and at most [MAX_READ_LINES]
     * of them. Every line is offered to the model; a line it fails on, or whose reading is refused
     * by [OcrLinePolicy], keeps ML Kit's text. With no model installed nothing is read at all.
     */
    suspend fun readLines(context: Context, bitmap: Bitmap, lines: List<OcrLine>): List<OcrLine> {
        var read = 0
        return lines.map { line ->
            if (read >= MAX_READ_LINES) return@map line
            read++
            val startedAt = SystemClock.elapsedRealtime()
            val text = readLineWithMangaOcr(context, bitmap, line.bounds) ?: return@map line
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val accepted = OcrLinePolicy.acceptsMangaText(line.text, text)
            Log.d(
                TAG,
                "manga-ocr read \"${line.text}\" -> \"$text\" in $elapsed ms (region)" +
                    if (accepted) "" else " [rejected]"
            )
            if (accepted) line.copy(text = text) else line
        }
    }

    /**
     * Reads one box with manga-ocr: the crop [OcrPreprocess] builds, through [MangaOcrEngine]. Null
     * when the model is not installed or the read failed — the caller keeps ML Kit's text, which is
     * the point of the fallback.
     *
     * The overlay's index reads its lines through this too ([ScreenLineReader]), so the index and
     * this path crop and read a line exactly the same way.
     */
    suspend fun readLineWithMangaOcr(context: Context, bitmap: Bitmap, bounds: Box): String? {
        MangaOcrModel.installBundledIfPresent(context)
        if (!MangaOcrModel.isInstalled(context)) return null
        val crop = OcrPreprocess.cropForModel(bitmap, bounds) ?: return null
        return MangaOcrEngine.recognize(context, crop)
    }

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
