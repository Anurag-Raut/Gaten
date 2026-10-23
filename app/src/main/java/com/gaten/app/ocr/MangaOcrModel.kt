/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Lazily extracts the bundled manga-ocr weights into private app storage. */
object MangaOcrModel {
    private const val TAG = "GatenOcr"
    private const val DIR = "manga-ocr"
    private const val ASSET_DIR = "manga-ocr"
    private val installLock = Any()

    enum class Slot(val fileName: String) {
        ENCODER("encoder.onnx"),
        DECODER("decoder.onnx"),
        VOCAB("vocab.txt")
    }

    fun dir(context: Context): File = File(context.filesDir, DIR)

    fun file(context: Context, slot: Slot): File = File(dir(context), slot.fileName)

    fun isInstalled(context: Context): Boolean = Slot.entries.all { file(context, it).isNotEmpty() }

    /** Extracts once on first use; incomplete copies are retried on the next lookup. */
    suspend fun installBundledIfPresent(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(context)) return@withContext true
        synchronized(installLock) {
            if (isInstalled(context)) return@synchronized true
            try {
                val assets = context.assets.list(ASSET_DIR)?.toSet().orEmpty()
                if (!Slot.entries.all { it.fileName in assets }) return@synchronized false

                val target = dir(context).apply { mkdirs() }
                for (slot in Slot.entries) {
                    val destination = File(target, slot.fileName)
                    if (destination.isNotEmpty()) continue
                    val temporary = File(target, "${slot.fileName}.part")
                    context.assets.open("$ASSET_DIR/${slot.fileName}").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(destination)) {
                        destination.delete()
                        check(temporary.renameTo(destination)) { "Could not install ${slot.fileName}" }
                    }
                }
                isInstalled(context)
            } catch (e: Exception) {
                Log.w(TAG, "Bundled manga-ocr model could not be installed", e)
                false
            }
        }
    }

    private fun File.isNotEmpty(): Boolean = isFile && length() > 0
}
