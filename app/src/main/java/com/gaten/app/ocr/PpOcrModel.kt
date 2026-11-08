/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.app.Activity
import android.content.Context
import java.io.File

/**
 * The optional PP-OCRv6 Medium recognition model, delivered by Google Play.
 *
 * A line recogniser only: the boxes come from the detector that is already there (ML Kit /
 * [ScreenLineReader]), so nothing here detects or captures. The weights ship in neither the APK nor
 * the base module; on a Play install they arrive through the `ppocr` fast-follow asset pack
 * ([PpOcrPack]) — Play fetches it after install, without the reader opening Settings, and verifies
 * the transfer itself — and [PpOcrEngine] then reads the files straight out of the pack's directory.
 *
 * That is the only delivery path. Gaten never downloads the model itself: an install Google Play did
 * not make (a sideloaded APK, F-Droid, an image without Play) has no pack to read and cannot install
 * the engine, so ML Kit stays its recognizer. Nothing is copied out of the pack and nothing about it
 * is written to app storage, so there is no second copy to fall back to or to reconcile.
 *
 * The graph was read off the ONNX file itself: input `x` float32 `[N,3,48,W]` (W dynamic), output
 * `fetch_name_0` float32 `[N,T,18710]` with `T` about `W/8`.
 */
object PpOcrModel {
    /**
     * The upstream recognition model this app ships.
     *
     * [revision] is the catalogue's own identity for the engine: it is part of the crop-cache key, so
     * re-pinning the files to another upstream revision cannot serve a reading cached from the old
     * model. [approximateDownloadBytes] is the sum of the pinned file sizes — what Play reports as
     * the pack's download.
     */
    enum class Engine(
        val id: String,
        val displayName: String,
        val revision: String,
        val approximateDownloadBytes: Long
    ) {
        MEDIUM("medium", "Enhanced", "ppocrv6-medium-onnx-50c7eaca", 76_629_926L)
    }

    /** One file the pack carries: its name, and the exact size a complete pack holds. */
    internal data class PackFile(val name: String, val size: Long)

    internal const val MODEL_FILE = "rec.onnx"
    internal const val DICT_FILE = "dict.txt"

    // PaddlePaddle/PP-OCRv6_medium_rec_onnx @ 50c7eacafc52fa7bcf4194e8cd08e46f8558504b
    // (ONNX IR 6, opset 11; dict: PaddlePaddle/PaddleOCR @ b03f46425e8ff4442b268ce449e3eef758146cd4).
    /**
     * The files [PpOcrEngine] reads, and the sizes a complete pack must hold.
     *
     * The size is the check that matters here: Play can report a pack as extracted before every file
     * is on disk, so "the pack has a location" is never "the files are there". The upstream sources
     * and their SHA-256s are pinned in `ppocr/build.gradle.kts`, which verifies them when the pack is
     * staged at bundle-build time, and recorded in docs/ocr-engines.md; the app has no build step of
     * its own to lean on and does not re-verify them.
     */
    internal val files = listOf(
        PackFile(MODEL_FILE, 76_554_979L),
        PackFile(DICT_FILE, 74_947L)
    )

    /** The pack's directory when it holds a complete copy, else null — there is no other copy. */
    internal fun dir(context: Context): File? = PpOcrPack.completeDir(context, files)

    /** The ONNX recogniser, or null when the pack is not installed. */
    internal fun modelFile(context: Context): File? = dir(context)?.let { File(it, MODEL_FILE) }

    /** The character dictionary, or null when the pack is not installed. */
    internal fun dictFile(context: Context): File? = dir(context)?.let { File(it, DICT_FILE) }

    /** True when the pack's complete copy is on disk, which is the only way this engine exists. */
    fun isInstalled(context: Context): Boolean = dir(context) != null

    /**
     * Installs the engine, explicitly requested by the user, by driving Play's own asset-pack fetch.
     *
     * A Play install that cannot serve the pack — Play Core unavailable on the device, or the app was
     * not installed by Google Play — throws [DownloadException]. There is no in-app downloader behind
     * this, so a request that cannot be met says so instead of quietly doing nothing.
     *
     * [activity] is only needed when Play asks the user to confirm a download over mobile data; a
     * null activity leaves that prompt to Play's own notification.
     */
    suspend fun download(context: Context, engine: Engine, activity: Activity? = null) {
        if (PpOcrPack.ensureInstalled(context, files, activity)) return
        throw DownloadException(
            "Gaten was not installed by Google Play, so ${engine.displayName} cannot be downloaded here."
        )
    }

    /**
     * The installed identity, or an empty string when the engine is not installed: the pack's name,
     * which Play versions with the app bundle, plus this catalogue's revision, so a repin of the
     * files still changes the answer a caller caching against it holds.
     */
    fun revision(context: Context): String =
        if (isInstalled(context)) {
            "${Engine.MEDIUM.id}@${PpOcrPack.PACK_NAME}:${Engine.MEDIUM.revision}"
        } else {
            ""
        }

    /** A pack that cannot be delivered: Play refused, failed, or this install has no pack to fetch. */
    class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
