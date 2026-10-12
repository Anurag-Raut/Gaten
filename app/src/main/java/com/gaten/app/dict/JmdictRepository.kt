/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One dictionary entry, as stored by the compressed JMdict database bundled in `assets/db`.
 *
 * [primary] is the display form: the first kanji form when the entry has one, otherwise the first
 * kana form. [kanji]/[kana] are the individual forms, [glosses] the first senses' english
 * glosses, [pos] the human-readable parts of speech.
 */
data class DictEntry(
    val id: Int,
    val primary: String,
    val kanji: List<String>,
    val kana: List<String>,
    val glosses: List<String>,
    val pos: List<String>,
    val common: Boolean
)

/**
 * Read-only access to the JMdict database bundled with the app.
 *
 * The database ships uncompressed as `assets/db/jmdict.sqlite` (AGP expands `.gz` assets during
 * asset merge, so the packaged path is always the plain name) and is copied once to
 * `filesDir/jmdict.sqlite` on the first [open]. The copy goes through a temporary file and a
 * rename so a killed process can never leave a half-written database behind, and it is guarded by
 * a [Mutex] so concurrent callers extract exactly once.
 */
class JmdictRepository private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val ASSET_PATH = "db/jmdict.sqlite"
        private const val DB_FILE_NAME = "jmdict.sqlite"
        private const val TEMP_FILE_NAME = "jmdict.sqlite.tmp"

        /** SQLite file header: 16-byte magic, page size at 16, database page count at 28. */
        private const val SQLITE_HEADER_SIZE = 100
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        /**
         * Ranks matches for one surface form. `common` first, then kana-only headwords, then the
         * JMdict id.
         *
         * The middle key matters for kana surfaces: `が` matches both the particle が (a kana-only
         * headword) and the reading of 絵/画, and both are marked common. Without it the raw id
         * order wins and a screen lookup of a particle shows "picture, drawing" — measured on the
         * emulator. Kanji queries are unaffected, since all their matches have a kanji form.
         */
        private const val LOOKUP_SQL =
            "SELECT e.* FROM terms t JOIN entries e ON e.id = t.entry_id " +
                "WHERE t.term = ? ORDER BY t.common DESC, (e.kanji = '') DESC, e.id ASC LIMIT ?"

        /**
         * Proper nouns: JMdict carries almost none of them (池袋, 新宿, 秋葉原 are all absent), so
         * JMnedict name rows live in their own compact table. `term` is its primary key, which is
         * why a name match needs no dedupe pass.
         */
        private const val NAME_LOOKUP_SQL = "SELECT term, gloss, kind FROM names WHERE term = ?"

        /** Serialises extraction so two concurrent [open] calls never write the same file twice. */
        private val extractionMutex = Mutex()

        /**
         * Copies `assets/db/jmdict.sqlite` to `context.filesDir` if needed (idempotent, safe under
         * concurrent calls) and opens the result read-only.
         *
         * @throws IOException when the asset is missing or the extracted file cannot be installed.
         */
        suspend fun open(context: Context): JmdictRepository = withContext(Dispatchers.IO) {
            val target = File(context.filesDir, DB_FILE_NAME)
            extractionMutex.withLock { ensureExtracted(context, target) }
            JmdictRepository(
                SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
            )
        }

        /**
         * True when the extracted copy is byte-identical to the packaged asset, judged from the
         * 100-byte SQLite header both share (page size, page count, change counter and
         * `user_version`).
         *
         * Without this the copy in `filesDir` would be trusted forever: an app update shipping a
         * newer JMdict asset kept the previous database, and a query against a table the new code
         * expects (`names`) threw `no such table`. The asset is deflated inside the APK, so this
         * compares headers rather than `openFd().length`, and it stays a 100-byte read either way.
         */
        private fun matchesAsset(context: Context, file: File): Boolean {
            val copied = readHeader(file) ?: return false
            val packaged = try {
                context.assets.open(ASSET_PATH).use { readHeader(it) }
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

        /**
         * True when [file] holds a complete SQLite database: correct magic, and a length equal to
         * the size declared by its own header (page size x page count). A truncated or interrupted
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

    /**
     * Exact-term lookup over the `terms` index, ordered common-first then by entry id, followed by
     * any JMnedict proper noun with that exact form.
     *
     * [term] must be a dictionary form as stored in the database — inflected surfaces go through
     * [Deinflector] first (see [WordScanner]). Entries reachable from several matching rows are
     * returned once. Name entries come last on purpose: they exist to make a circled place or
     * person name resolvable, not to outrank the word the user probably wants.
     */
    fun lookup(term: String, limit: Int = 8): List<DictEntry> {
        if (term.isEmpty() || limit <= 0) return emptyList()

        val entries = ArrayList<DictEntry>(minOf(limit, 8))
        val seenIds = HashSet<Int>()
        db.rawQuery(LOOKUP_SQL, arrayOf(term, limit.toString())).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val kanjiIndex = cursor.getColumnIndexOrThrow("kanji")
            val kanaIndex = cursor.getColumnIndexOrThrow("kana")
            val glossIndex = cursor.getColumnIndexOrThrow("gloss")
            val posIndex = cursor.getColumnIndexOrThrow("pos")
            val commonIndex = cursor.getColumnIndexOrThrow("common")

            while (cursor.moveToNext()) {
                val id = cursor.getInt(idIndex)
                if (!seenIds.add(id)) continue
                val kanji = cursor.splitJoined(kanjiIndex, " ")
                val kana = cursor.splitJoined(kanaIndex, " ")
                entries.add(
                    DictEntry(
                        id = id,
                        primary = kanji.firstOrNull() ?: kana.firstOrNull() ?: "",
                        kanji = kanji,
                        kana = kana,
                        glosses = cursor.splitJoined(glossIndex, " | "),
                        pos = cursor.splitJoined(posIndex, " | "),
                        common = cursor.getInt(commonIndex) != 0
                    )
                )
            }
        }

        if (entries.size < limit) {
            db.rawQuery(NAME_LOOKUP_SQL, arrayOf(term)).use { cursor ->
                while (cursor.moveToNext() && entries.size < limit) {
                    val kind = cursor.getString(0)
                    val gloss = cursor.getString(1)
                    entries.add(
                        DictEntry(
                            // Names have no JMdict id; a negative hash of term+kind keeps it
                            // distinct from every word id (those start at 1000000) and stable.
                            id = -(term + kind).hashCode(),
                            primary = term,
                            kanji = listOf(term),
                            kana = emptyList(),
                            glosses = listOf(gloss),
                            pos = listOf(kind),
                            common = false
                        )
                    )
                }
            }
        }
        return entries
    }

    /** Releases the underlying read-only [SQLiteDatabase]. */
    fun close() {
        db.close()
    }
}

/** Splits a `' '` or `' | '` joined database column back into its individual values. */
private fun Cursor.splitJoined(index: Int, separator: String): List<String> {
    val value = getString(index)
    return if (value.isEmpty()) emptyList() else value.split(separator).filter { it.isNotEmpty() }
}
