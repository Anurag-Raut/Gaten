/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One example sentence: the Japanese line and its English translation, both from the corpus. */
data class SentenceExample(
    val japanese: String,
    val english: String
)

/**
 * Read-only access to the Tanaka Corpus bundled with the app as `assets/db/examples.sqlite`.
 *
 * The asset is copied once to `filesDir/examples.sqlite` on first [open], through a temporary file
 * and a rename, guarded by a [Mutex] so concurrent callers extract exactly once. The copy is
 * replaced when the packaged asset changes, judged from the shared 100-byte SQLite header (page
 * size, page count and `user_version`) — the same convention [JmdictRepository] uses, so an app
 * update that ships a newer corpus is read instead of the previous copy.
 */
class ExampleRepository private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val ASSET_PATH = "db/examples.sqlite"
        private const val DB_FILE_NAME = "examples.sqlite"
        private const val TEMP_FILE_NAME = "examples.sqlite.tmp"

        /** SQLite file header: 16-byte magic, page size at 16, database page count at 28. */
        private const val SQLITE_HEADER_SIZE = 100
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * Sentences carrying an exact term, shortest first: a short line reads as an example, a
         * long one as a paragraph. `length()` counts characters, so a 12-character sentence beats a
         * 40-character one regardless of the translation's length.
         */
        private const val LOOKUP_SQL =
            "SELECT s.japanese, s.english FROM sentence_terms t JOIN sentences s " +
                "ON s.id = t.sentence_id WHERE t.term = ? " +
                "ORDER BY length(s.japanese) ASC, s.id ASC LIMIT ?"

        private val extractionMutex = Mutex()

        /** Copies the bundled corpus out if needed and opens it read-only. */
        suspend fun open(context: Context): ExampleRepository = withContext(Dispatchers.IO) {
            val target = File(context.filesDir, DB_FILE_NAME)
            extractionMutex.withLock { ensureExtracted(context, target) }
            ExampleRepository(
                SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
            )
        }

        private fun matchesAsset(context: Context, file: File): Boolean {
            val copied = readHeader(file) ?: return false
            val packaged = try {
                context.assets.open(ASSET_PATH).use { readHeader(it) }
            } catch (e: IOException) {
                null
            } ?: return false
            return copied.contentEquals(packaged)
        }

        private fun readHeader(file: File): ByteArray? = try {
            file.inputStream().use { readHeader(it) }
        } catch (e: IOException) {
            null
        }

        private fun readHeader(input: java.io.InputStream): ByteArray? {
            val header = ByteArray(SQLITE_HEADER_SIZE)
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n <= 0) return null
                read += n
            }
            return header
        }

        private fun ensureExtracted(context: Context, target: File) {
            if (isUsableDatabase(target) && matchesAsset(context, target)) return

            val temp = File(target.parentFile, TEMP_FILE_NAME)
            try {
                context.assets.open(ASSET_PATH).use { input ->
                    FileOutputStream(temp).use { out -> input.copyTo(out) }
                }
                if (!temp.renameTo(target) && !(target.delete() && temp.renameTo(target))) {
                    throw IOException("Could not move ${temp.path} to ${target.path}")
                }
            } finally {
                if (temp.exists()) temp.delete()
            }
        }

        /** True when [file] is a complete SQLite database: correct magic and a matching length. */
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
            val pageSize = if (rawPageSize == 1) 65536L else rawPageSize.toLong()
            val pageCount = ((header[28].toLong() and 0xFF) shl 24) or
                ((header[29].toLong() and 0xFF) shl 16) or
                ((header[30].toLong() and 0xFF) shl 8) or
                (header[31].toLong() and 0xFF)
            return pageSize * pageCount == file.length()
        }
    }

    /**
     * Up to [limit] sentences containing [term], shortest first. [term] is the card's dictionary
     * form (its expression, or its reading when the expression has no example); the corpus indexes
     * both, so either answers. An empty result is not an error — a rare word may simply have no
     * sentence in the corpus.
     */
    fun lookup(term: String, limit: Int = 3): List<SentenceExample> {
        if (term.isEmpty() || limit <= 0) return emptyList()
        val examples = ArrayList<SentenceExample>(limit)
        db.rawQuery(LOOKUP_SQL, arrayOf(term, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                examples.add(SentenceExample(cursor.getString(0), cursor.getString(1)))
            }
        }
        return examples
    }

    /** Releases the underlying read-only [SQLiteDatabase]. */
    fun close() {
        db.close()
    }
}
