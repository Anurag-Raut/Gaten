/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * YomiLens: Japanese screen lookup for immersion learners.
 *
 * Freehand circle / drag-select a region -> on-device Japanese OCR (ML Kit, Tesseract fallback)
 * -> offline JMdict lookup with deinflection -> result panel with real dictionary entries.
 */

package com.akslabs.circletosearch.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One recognised text line; [bounds] are relative to the bitmap that was recognised. */
data class OcrLine(val text: String, val bounds: Box)

/**
 * On-device Japanese OCR for a single cropped region.
 *
 * Primary engine is ML Kit's bundled Japanese text recognizer (offline, no API key). When it fails
 * or returns nothing usable, the bundled Tesseract engine ([TesseractEngine], `jpn+eng`) is used as
 * a fallback.
 */
object JapaneseOcr {
    private const val TAG = "YomiLensOcr"

    /** Language pack for the Tesseract fallback: Japanese with Latin support. */
    private const val FALLBACK_LANG = "jpn+eng"

    /** ML Kit clients are expensive to build and safe to reuse; the model is app-lifetime. */
    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    /**
     * OCR the bitmap. Returns recognized text, "" when nothing found. Never throws.
     */
    suspend fun recognize(context: Context, bitmap: Bitmap): String =
        recognizeLines(context, bitmap).joinToString("\n") { it.text }

    /**
     * OCR the bitmap keeping each line's bounds, so a caller can tell which lines a hand-drawn
     * selection actually pointed at (see [SelectionSnap]).
     *
     * Tesseract, the fallback engine, reports no layout, so its result comes back as a single line
     * covering the whole bitmap. Never throws; returns an empty list when nothing was recognised.
     */
    suspend fun recognizeLines(context: Context, bitmap: Bitmap): List<OcrLine> {
        val primary = try {
            runMlKit(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit Japanese OCR failed; falling back to Tesseract", e)
            emptyList()
        }
        if (primary.isNotEmpty()) return primary

        Log.w(TAG, "ML Kit returned no lines; falling back to Tesseract ($FALLBACK_LANG)")
        return try {
            val text = TesseractEngine.extractRegionText(context, bitmap, FALLBACK_LANG).trim()
            if (text.isEmpty()) {
                emptyList()
            } else {
                listOf(OcrLine(text, Box(0, 0, bitmap.width, bitmap.height)))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract fallback failed", e)
            emptyList()
        }
    }

    private suspend fun runMlKit(bitmap: Bitmap): List<OcrLine> {
        val result: Text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = ArrayList<OcrLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                if (text.isEmpty()) continue
                val box = line.boundingBox ?: continue
                lines.add(OcrLine(text, Box(box.left, box.top, box.right, box.bottom)))
            }
        }
        return lines
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
