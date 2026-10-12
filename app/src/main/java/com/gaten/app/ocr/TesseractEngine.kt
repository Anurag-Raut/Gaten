/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Bundled Tesseract engine for a single cropped region: the fallback behind [JapaneseOcr], and the
 * engine that runs when the user selects Tesseract. Copies `assets/tessdata/{eng,jpn}.traineddata`
 * into `filesDir` on first use, then OCRs the crop in one PSM_SINGLE_BLOCK pass.
 */
object TesseractEngine {
    private const val TAG = "TesseractEngine"

    /** Default OCR language. "jpn+eng" so Japanese text is recognized while Latin still works. */
    const val DEFAULT_LANG = "jpn+eng"

    /** Tesseract models bundled in assets/tessdata that are copied to filesDir on first run. */
    private val BUNDLED_MODELS = listOf("eng", "jpn")

    /** Copies the bundled models to `filesDir/tessdata` once and returns that data path for [TessBaseAPI.init]. */
    fun prepareTessData(context: Context): String {
        val filesDir = context.filesDir.absolutePath
        val tessDir = File(filesDir, "tessdata")
        if (!tessDir.exists()) {
            tessDir.mkdirs()
        }

        BUNDLED_MODELS.forEach { model ->
            val dest = File(tessDir, "$model.traineddata")
            if (!dest.exists()) {
                Log.d(TAG, "Copying $model.traineddata from assets...")
                try {
                    context.assets.open("tessdata/$model.traineddata").use { input ->
                        FileOutputStream(dest).use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy $model.traineddata: ${e.message}")
                }
            }
        }
        return filesDir
    }

    /**
     * Single-pass OCR of one cropped region, with an explicit page segmentation mode.
     *
     * Subtitles and other white-on-dark text defeat Tesseract's default PSM_AUTO, which returns
     * garbage for a tight crop (measured: PSM_AUTO -> "ta 7k A Bad" vs PSM_SINGLE_BLOCK ->
     * "狂が水を飲む" on the same bitmap). Hence the PSM_SINGLE_BLOCK default here.
     */
    suspend fun extractRegionText(
        context: Context,
        bitmap: Bitmap,
        lang: String = DEFAULT_LANG,
        psm: Int = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
    ): String = withContext(Dispatchers.Default) {
        val dataPath = prepareTessData(context)
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataPath, lang)) return@withContext ""
            tess.setPageSegMode(psm)
            tess.setImage(bitmap)
            tess.getUTF8Text()?.trim().orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Region OCR failed: ${e.message}")
            ""
        } finally {
            tess.recycle()
        }
    }
}
