/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ocr

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
 * Which engine runs is the user's choice ([OcrPreferences]): ML Kit's bundled Japanese recognizer
 * by default, the bundled Tesseract engine ([TesseractEngine], `jpn+eng`) when Tesseract is
 * selected. Under ML Kit the Tesseract engine is still the safety net for the cases it handles
 * better than the model — an outright failure, or a result with no usable lines.
 */
object JapaneseOcr {
    private const val TAG = "YomiLensOcr"

    /** Language pack for the Tesseract engine: Japanese with Latin support. */
    private const val TESSERACT_LANG = "jpn+eng"

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
     * Tesseract reports no layout, so its result comes back as a single line covering the whole
     * bitmap. Never throws; returns an empty list when nothing was recognised.
     */
    suspend fun recognizeLines(context: Context, bitmap: Bitmap): List<OcrLine> {
        if (OcrPreferences.engine(context) == OcrEngine.TESSERACT) {
            return runTesseract(context, bitmap)
        }

        val primary = try {
            runMlKit(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit Japanese OCR failed; falling back to Tesseract", e)
            emptyList()
        }
        if (primary.isNotEmpty()) return primary

        Log.w(TAG, "ML Kit returned no lines; falling back to Tesseract ($TESSERACT_LANG)")
        return runTesseract(context, bitmap)
    }

    /** The Tesseract engine's output as a single full-bitmap line; empty on failure or blank text. */
    private suspend fun runTesseract(context: Context, bitmap: Bitmap): List<OcrLine> = try {
        val text = TesseractEngine.extractRegionText(context, bitmap, TESSERACT_LANG).trim()
        if (text.isEmpty()) {
            emptyList()
        } else {
            listOf(OcrLine(text, Box(0, 0, bitmap.width, bitmap.height)))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Tesseract OCR failed", e)
        emptyList()
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
