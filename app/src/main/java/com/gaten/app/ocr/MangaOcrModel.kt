/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.util.Log
import java.io.File

/**
 * The manga-ocr weights, downloaded on demand like the PP-OCRv6 engines rather than bundled.
 *
 * Three files make up the pair [MangaOcrEngine] runs: the ViT encoder and the BERT decoder from
 * `onnx-community/manga-ocr-base-ONNX` and the character vocabulary from `kha-white/manga-ocr-base`.
 * Each URL is pinned to an immutable upstream revision and verified by exact size and SHA-256 before
 * it is installed (through [ModelInstall], shared with [PpOcrModel]), manually only — never
 * automatically — and downloads use whatever connection the device has, with the settings screen
 * warning about mobile data first. Nothing here touches the network otherwise, and recognition stays
 * offline.
 *
 * Files keep their historical location (`filesDir/manga-ocr`, names `encoder.onnx` / `decoder.onnx` /
 * `vocab.txt`), so an install that predates this downloader — extracted from the old bundled asset or
 * imported by hand — keeps working with no re-download: a complete set is trusted by its pinned sizes.
 * The `revision` marker is written only by [download] and is *not* required for a legacy install; if a
 * marker is present it must match this catalogue. Both backup formats exclude the directory (the
 * weights are large and re-downloadable).
 */
object MangaOcrModel {
    private const val TAG = "GatenOcr"
    private const val DIR = "manga-ocr"
    private const val REVISION_FILE = "revision"

    /** This catalogue's identity for the installed set; [download] writes it beside the files. */
    private const val REVISION = "manga-ocr-base-int8-f9023406"

    /** What the three files weigh together, for the settings screen's download prompt. */
    const val approximateDownloadBytes = 116_619_775L

    /** Used only for the install log; the settings screen has its own label for the model. */
    private const val DISPLAY_NAME = "manga-ocr"

    /** The three files, in the names [MangaOcrEngine] reads them by. */
    enum class Slot(val fileName: String) {
        ENCODER("encoder.onnx"),
        DECODER("decoder.onnx"),
        VOCAB("vocab.txt")
    }

    // onnx-community/manga-ocr-base-ONNX @ f9023406bb2f6b17df67bc4a327c56ecd20611f0 (int8 export);
    // vocab from kha-white/manga-ocr-base @ aa6573bd10b0d446cbf622e29c3e084914df9741.
    internal val files = listOf(
        PpOcrModel.ModelFile(
            Slot.ENCODER.fileName,
            "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/" +
                "f9023406bb2f6b17df67bc4a327c56ecd20611f0/onnx/encoder_model_int8.onnx",
            "ddd1af56963093795705fa38da6ce7e6567d1658e7c7359db7e13fcd37dbf279",
            86_967_767L
        ),
        PpOcrModel.ModelFile(
            Slot.DECODER.fileName,
            "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/" +
                "f9023406bb2f6b17df67bc4a327c56ecd20611f0/onnx/decoder_model_int8.onnx",
            "2e7177d2b0a59f1c612b694ed70c13971bee765cc2b2bc7bc9376e4753652f27",
            29_627_936L
        ),
        PpOcrModel.ModelFile(
            Slot.VOCAB.fileName,
            "https://huggingface.co/kha-white/manga-ocr-base/resolve/" +
                "aa6573bd10b0d446cbf622e29c3e084914df9741/vocab.txt",
            "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d",
            24_072L
        )
    )

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(context: Context, slot: Slot): File = File(dir(context), slot.fileName)

    /**
     * True when all three files are present at their pinned sizes and, if a revision marker exists, it
     * matches this catalogue. A legacy install has no marker and is trusted by size alone; a
     * mismatched marker means a different catalogue wrote the files.
     */
    fun isInstalled(context: Context): Boolean {
        val directory = dir(context)
        if (files.any { val file = File(directory, it.name); !file.isFile || file.length() != it.size }) {
            return false
        }
        val marker = File(directory, REVISION_FILE)
        if (!marker.isFile) return true
        return try {
            marker.readText().trim() == REVISION
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the manga-ocr revision marker", e)
            false
        }
    }

    /**
     * Downloads and installs the three files, explicitly requested by the user. The transfer uses the
     * device's current connection (the settings screen warns about mobile data before starting one);
     * each file is pinned by HTTPS revision, size and SHA-256, installed atomically, with partial
     * downloads cleaned up. Throws [PpOcrModel.DownloadException] when it cannot complete.
     */
    suspend fun download(
        context: Context,
        onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null
    ) {
        ModelInstall.install(
            displayName = DISPLAY_NAME,
            directory = dir(context),
            markerName = REVISION_FILE,
            revision = REVISION,
            files = files,
            installed = { isInstalled(context) },
            onProgress = onProgress
        )
    }
}
