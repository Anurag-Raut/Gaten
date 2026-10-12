/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Where the optional manga-ocr model lives, and how it gets there.
 *
 * The model is ~117 MB, so it is never shipped in a release APK. There are three ways onto a device,
 * and all of them end in `filesDir/manga-ocr/`, which is the one thing the OCR path asks about
 * ([isInstalled]):
 *
 *  - **Download** — the user picks a [MangaOcrCatalog] entry and taps Download on the settings
 *    screen ([MangaOcrDownloader] does the fetching and the installing).
 *  - **Import** — the user picks the three files from device storage ([importFrom]), for a phone
 *    that has no network or a model fetched elsewhere.
 *  - **A bundled debug build** — `./gradlew :app:assembleDebug -PbundleMangaOcr=true` puts them in
 *    the APK's assets, and the first second-pass lookup copies them out ([installBundledIfPresent]).
 *
 * Files are named by role, not by the name they were fetched under: a picked file is matched on its
 * name prefix ([classifyName]), so `encoder_model_int8.onnx` lands as `encoder.onnx`. Every write
 * goes through a temp file and a rename ([moveAtomically]), so a half-copied model is never visible
 * to [isInstalled] — and a failed download leaves whatever was installed before untouched.
 *
 * Which catalog entry is installed is recorded in a marker file ([installedCatalogId]); it is what
 * the settings screen names the installed model by.
 */
object MangaOcrModel {
    private const val TAG = "GatenOcr"

    /** Directory inside `filesDir` holding the three files. */
    private const val DIR = "manga-ocr"

    /** Asset directory a `-PbundleMangaOcr=true` debug build fills in. */
    private const val ASSET_DIR = "manga-ocr"

    /** File naming the [MangaOcrCatalog] entry the installed model came from, when it is known. */
    private const val MARKER = "catalog.txt"

    /** The three files the engine needs, as they are stored (and as they are bundled). */
    enum class Slot(val fileName: String) {
        ENCODER("encoder.onnx"),
        DECODER("decoder.onnx"),
        VOCAB("vocab.txt")
    }

    /**
     * Whether the assets have already been looked for in this process. A release APK has none, and
     * the second pass runs on every lookup, so the lookup is done once and remembered.
     */
    @Volatile
    private var bundledChecked = false

    /** `filesDir/manga-ocr`, where the engine reads the model from. */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** The file [slot] is stored as. */
    fun file(context: Context, slot: Slot): File = File(dir(context), slot.fileName)

    /** True when all three files are present and non-empty, i.e. when the second pass can run. */
    fun isInstalled(context: Context): Boolean = Slot.entries.all { file(context, it).isNotEmpty() }

    /** True when a file exists and holds anything: a truncated download is not a model. */
    private fun File.isNotEmpty(): Boolean = isFile && length() > 0

    /** Total size of the installed files in bytes, 0 when nothing is installed. */
    fun installedSizeBytes(context: Context): Long =
        Slot.entries.sumOf { file(context, it).length() }

    /**
     * The id of the [MangaOcrCatalog] entry the installed model came from, or null when that is not
     * known — a model imported from files that match no entry. The settings screen shows it as the
     * name of what is installed.
     */
    fun installedCatalogId(context: Context): String? = readMarker(dir(context))

    /**
     * Copies the model out of the app's own assets into `filesDir/manga-ocr/` when a debug build
     * bundled it and the directory is not already complete. Returns true when the model is installed
     * afterwards. A no-op (and one asset listing per process) in every release build. Never throws.
     */
    suspend fun installBundledIfPresent(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (bundledChecked) return@withContext isInstalled(context)
        bundledChecked = true
        try {
            if (isInstalled(context)) return@withContext true
            val assets = context.assets.list(ASSET_DIR)?.toSet().orEmpty()
            if (!Slot.entries.all { it.fileName in assets }) return@withContext false

            Log.d(TAG, "Installing the bundled manga-ocr model from assets")
            val target = dir(context).apply { mkdirs() }
            for (slot in Slot.entries) {
                context.assets.open("$ASSET_DIR/${slot.fileName}").use { input ->
                    copyInto(input, File(target, slot.fileName))
                }
            }
            recordInstalled(context)
            isInstalled(context)
        } catch (e: Exception) {
            Log.w(TAG, "Bundled manga-ocr model could not be installed", e)
            false
        }
    }

    /**
     * Copies the user-picked [uris] into `filesDir/manga-ocr/`, recognising each by its file name.
     * Files that match none of the three roles are ignored, and an already-installed file is only
     * replaced when a new one for its role was picked — so importing the one file that is missing
     * repairs an incomplete model. Returns true when all three files are in place afterwards. Never
     * throws.
     */
    suspend fun importFrom(context: Context, uris: List<Uri>): Boolean = withContext(Dispatchers.IO) {
        try {
            val target = dir(context).apply { mkdirs() }
            for (uri in uris) {
                val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
                val slot = classifyName(name)
                if (slot == null) {
                    Log.w(TAG, "Ignoring $name: not an encoder, decoder or vocab file")
                    continue
                }
                val stream = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot open $uri", e)
                    null
                } ?: continue
                stream.use { copyInto(it, File(target, slot.fileName)) }
                Log.d(TAG, "Imported ${slot.fileName} from $name")
            }

            val installed = isInstalled(context)
            if (!installed) {
                Log.w(TAG, "Imported files, but the model is still incomplete")
            } else {
                recordInstalled(context)
            }
            installed
        } catch (e: Exception) {
            Log.w(TAG, "manga-ocr import failed", e)
            false
        }
    }

    /** Deletes the installed model. The engine notices on its next call and drops its sessions. */
    fun remove(context: Context) {
        val dir = dir(context)
        if (dir.exists() && !dir.deleteRecursively()) {
            Log.w(TAG, "Could not delete ${dir.path}")
        }
    }

    /**
     * Which of the three roles a file name stands for, or null when it is none of them. The name is
     * matched on its base name only, so a picked file may live anywhere; the downloaded names
     * (`encoder_model_int8.onnx`, `decoder_model_int8.onnx`, `vocab.txt`) and the stored names
     * (`encoder.onnx`, ...) both classify. Pure.
     */
    fun classifyName(name: String): Slot? {
        val base = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        return Slot.entries.firstOrNull { base.startsWith(it.fileName.substringBefore('.')) }
    }

    /** Writes [input] to [target] through a temp file, so [target] is never half-written. */
    private fun copyInto(input: InputStream, target: File) {
        val temp = File(target.parentFile, "${target.name}.part")
        temp.outputStream().use { output -> input.copyTo(output) }
        moveAtomically(temp, target)
    }

    /**
     * Renames [source] onto [target], replacing it. Both are inside the model directory, so this is
     * one filesystem rename: a reader sees the old file or the new one, never a half-written one.
     * This is the only way a model file is put in place — the import and the download both use it.
     */
    internal fun moveAtomically(source: File, target: File) {
        if (!source.renameTo(target)) {
            // A filesystem that refuses to replace: fall back to delete+rename.
            target.delete()
            if (!source.renameTo(target)) error("cannot move ${source.path} to ${target.path}")
        }
    }

    /**
     * Records [id] as the installed model, or forgets it when [id] is null. Best effort: a marker
     * that cannot be written costs a name in the settings screen, not the model itself.
     */
    internal fun writeMarker(dir: File, id: String?) {
        val marker = File(dir, MARKER)
        try {
            if (id == null) {
                marker.delete()
            } else {
                marker.writeText("$id\n")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the installed model's catalog id", e)
        }
    }

    /** The catalog id recorded in [dir], or null when there is none. */
    internal fun readMarker(dir: File): String? = try {
        File(dir, MARKER).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the installed model's catalog id", e)
        null
    }

    /** Names the installed model after the catalog entry whose file sizes it matches, if any. */
    private fun recordInstalled(context: Context) {
        val sizes = Slot.entries.associateWith { file(context, it).length() }
        writeMarker(dir(context), MangaOcrCatalog.identify(sizes)?.id)
    }

    /** The name the document provider shows for [uri], or null when it does not answer. */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the display name of $uri", e)
        null
    }
}
