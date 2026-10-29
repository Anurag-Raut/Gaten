/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Opens a read-only SQLite database bundled in `assets/`.
 *
 * Bundled databases ship uncompressed (AGP expands `.gz` assets during asset merge, so the packaged
 * path is always the plain name) and are copied once to `filesDir` on the first open. The copy goes
 * through a temporary file and a rename so a killed process can never leave a half-written database
 * behind, and it is guarded by a [Mutex] so concurrent callers extract exactly once.
 *
 * The extracted copy is reused only while its 100-byte SQLite header matches the packaged asset's —
 * page size, page count, change counter and `user_version`. Without that check an app update
 * shipping a newer asset would keep the previous database, and a query against a table the new code
 * expects would throw `no such table`. The asset is deflated inside the APK, so comparing headers
 * rather than `openFd().length` is the portable way, and it stays a 100-byte read either way.
 */
object BundledDatabase {

    /** SQLite file header: 16-byte magic, page size at 16, database page count at 28. */
    private const val SQLITE_HEADER_SIZE = 100

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** Serialises extraction so two concurrent [openReadOnly] calls never write the same file twice. */
    private val extractionMutex = Mutex()

    /**
     * Copies `assets/[assetPath]` to `filesDir/[fileName]` when needed (idempotent, safe under
     * concurrent calls) and opens the result read-only. [assetPath] is the path inside `assets/`.
     *
     * @throws IOException when the asset is missing or the extracted file cannot be installed.
     */
    suspend fun openReadOnly(
        context: Context,
        assetPath: String,
        fileName: String
    ): SQLiteDatabase = withContext(Dispatchers.IO) {
        val target = File(context.filesDir, fileName)
        extractionMutex.withLock { ensureExtracted(context, assetPath, target) }
        SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun matchesAsset(context: Context, assetPath: String, file: File): Boolean {
        val copied = readHeader(file) ?: return false
        val packaged = try {
            context.assets.open(assetPath).use { readHeader(it) }
        } catch (e: IOException) {
            null
        } ?: return false
        return copied.contentEquals(packaged)
    }

    /** The first [SQLITE_HEADER_SIZE] bytes of [file], or null when it is shorter than that. */
    private fun readHeader(file: File): ByteArray? = try {
        file.inputStream().use { readHeader(it) }
    } catch (e: IOException) {
        null
    }

    private fun readHeader(input: InputStream): ByteArray? {
        val header = ByteArray(SQLITE_HEADER_SIZE)
        var read = 0
        while (read < header.size) {
            val n = input.read(header, read, header.size - read)
            if (n <= 0) return null
            read += n
        }
        return header
    }

    private fun ensureExtracted(context: Context, assetPath: String, target: File) {
        if (isUsableDatabase(target) && matchesAsset(context, assetPath, target)) return

        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(temp).use { out -> input.copyTo(out) }
            }
            if (!temp.renameTo(target) && !(target.delete() && temp.renameTo(target))) {
                throw IOException("Could not move ${temp.path} to ${target.path}")
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * True when [file] holds a complete SQLite database: correct magic, and a length equal to the
     * size declared by its own header (page size x page count). A truncated or interrupted
     * extraction fails this check and is simply re-extracted.
     */
    private fun isUsableDatabase(file: File): Boolean {
        if (!file.isFile || file.length() < SQLITE_HEADER_SIZE) return false
        val header = ByteArray(SQLITE_HEADER_SIZE)
        try {
            file.inputStream().use { input ->
                if (input.read(header) != SQLITE_HEADER_SIZE) return false
            }
        } catch (e: IOException) {
            return false
        }
        if (!header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return false

        val rawPageSize = ((header[16].toInt() and 0xFF) shl 8) or (header[17].toInt() and 0xFF)
        // A page size of 1 encodes 65536, which does not fit in the 16-bit field.
        val pageSize = if (rawPageSize == 1) 65536L else rawPageSize.toLong()
        val pageCount = ((header[28].toLong() and 0xFF) shl 24) or
            ((header[29].toLong() and 0xFF) shl 16) or
            ((header[30].toLong() and 0xFF) shl 8) or
            (header[31].toLong() and 0xFF)
        return pageSize * pageCount == file.length()
    }
}
