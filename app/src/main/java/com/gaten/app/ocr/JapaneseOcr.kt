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
 *
 * Under ML Kit, when the optional manga-ocr model is installed and enabled, ML Kit stays the
 * detector and manga-ocr reads the lines it found ([readLineWithMangaOcr], and [ScreenLineReader]
 * for the overlay's index). The Tesseract path is untouched by that.
 */
object JapaneseOcr {
    private const val TAG = "GatenOcr"

    /** Language pack for the Tesseract engine: Japanese with Latin support. */
    private const val TESSERACT_LANG = "jpn+eng"

    /** Lines this region path reads with manga-ocr. Each is an encoder pass plus a decode. */
    private const val MAX_READ_LINES = 8

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
     * ML Kit detects the lines and reads them; when the optional manga-ocr model is installed and
     * enabled, every line worth reading is read again by it ([readLines]) — this is the fallback
     * path, so it takes the whole crop's lines in one go rather than answering a gesture.
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
        if (primary.isNotEmpty()) return readLines(context, bitmap, primary)

        Log.w(TAG, "ML Kit returned no lines; falling back to Tesseract ($TESSERACT_LANG)")
        return runTesseract(context, bitmap)
    }

    /**
     * [lines] with the text manga-ocr read for the lines worth reading, in reading order and at
     * most [MAX_READ_LINES] of them. A line the model fails on, or whose reading is refused by
     * [OcrLinePolicy], keeps ML Kit's text; with no model installed nothing is read at all.
     */
    private suspend fun readLines(
        context: Context,
        bitmap: Bitmap,
        lines: List<OcrLine>
    ): List<OcrLine> {
        var read = 0
        return lines.map { line ->
            if (read >= MAX_READ_LINES || !OcrLinePolicy.readWithMangaOcr(line.text)) return@map line
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
     * Reads one line's box with manga-ocr: the crop [OcrPreprocess] builds, through
     * [MangaOcrEngine]. Null when the model is not installed, the reader turned it off, or the read
     * failed — the caller keeps ML Kit's text, which is the point of the fallback.
     *
     * The overlay's index reads its lines through this too ([ScreenLineReader]), so the index and
     * this path crop and read a line exactly the same way.
     */
    suspend fun readLineWithMangaOcr(context: Context, bitmap: Bitmap, bounds: Box): String? {
        if (!OcrPreferences.mangaOcrEnabled(context)) return null
        MangaOcrModel.installBundledIfPresent(context)
        if (!MangaOcrModel.isInstalled(context)) return null
        val crop = OcrPreprocess.cropForModel(bitmap, bounds) ?: return null
        return MangaOcrEngine.recognize(context, crop)
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

    /**
     * The raw ML Kit result for [bitmap], shared with callers that need a pass of their own
     * ([ScreenTextScanner]) so the client and the [Task] bridge stay in one place.
     */
    internal suspend fun mlKitResult(bitmap: Bitmap): Text =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()

    private suspend fun runMlKit(bitmap: Bitmap): List<OcrLine> {
        val result: Text = mlKitResult(bitmap)
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
