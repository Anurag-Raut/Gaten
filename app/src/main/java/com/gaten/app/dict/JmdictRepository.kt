/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.gaten.app.data.BundledDatabase

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
 * The database ships uncompressed as `assets/db/jmdict.sqlite` and is copied once to
 * `filesDir/jmdict.sqlite` on the first [open] by [BundledDatabase], which also keeps the copied
 * file in step with the packaged asset when an update ships a new one.
 */
class JmdictRepository private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val ASSET_PATH = "db/jmdict.sqlite"
        private const val DB_FILE_NAME = "jmdict.sqlite"

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
         * The terms that start with a query, for the search screen's suggestions.
         *
         * The predicate is a range on the term, not `LIKE 'prefix%'`: `idx_terms_term` is a BINARY
         * index and SQLite's `LIKE` is not, so `LIKE` could not use it and would scan the whole
         * table, while `term >= ? AND term < ?` is that index's own range scan — the planner reads
         * the range in term order and the LIMIT stops it early, with no sort. The bound comes from
         * [DictionarySearch.prefixUpperBound]. Wildcards are therefore literal, which is also what a
         * reader typing `%` or `_` means.
         */
        private const val PREFIX_SQL =
            "SELECT e.* FROM terms t JOIN entries e ON e.id = t.entry_id " +
                "WHERE t.term >= ? AND t.term < ? ORDER BY t.term ASC LIMIT ?"

        /** Rows read per entry wanted: one entry can hold several terms inside the range. */
        private const val PREFIX_OVER_READ = 2

        /**
         * The entries one collapsed reading reaches — the romaji fallback behind the exact matches.
         *
         * `loose` holds one row per reading, already folded by [DictionarySearch.looseForm] at build
         * time, so this is an equality on that column and uses `idx_loose_reading`'s B-tree. Ranking
         * mirrors [LOOKUP_SQL] over the reading's own `common` flag: the same entry offered by the
         * exact pass and this one comes back in the same place. The table is one row per reading, so
         * one entry can appear several times (ラーメン, らーめん and らあめん are all らめん); the
         * caller's dedupe keeps the first.
         */
        private const val LOOSE_SQL =
            "SELECT e.* FROM loose l JOIN entries e ON e.id = l.entry_id " +
                "WHERE l.reading = ? ORDER BY l.common DESC, (e.kanji = '') DESC, e.id ASC LIMIT ?"

        /**
         * Proper nouns: JMdict carries almost none of them (池袋, 新宿, 秋葉原 are all absent), so
         * JMnedict name rows live in their own compact table. `term` is its primary key, which is
         * why a name match needs no dedupe pass.
         */
        // Column order matters: [lookup] reads column 0 as the kind and column 1 as the gloss, and
        // the table is `names(term, rank, kind, gloss)`.
        private const val NAME_LOOKUP_SQL = "SELECT kind, gloss FROM names WHERE term = ?"

        /**
         * Copies `assets/db/jmdict.sqlite` to `context.filesDir` if needed (idempotent, safe under
         * concurrent calls) and opens the result read-only. The extraction and the version check
         * live in [BundledDatabase], shared with the other bundled databases.
         *
         * @throws java.io.IOException when the asset is missing or the extracted file cannot be installed.
         */
        suspend fun open(context: Context): JmdictRepository = JmdictRepository(
            BundledDatabase.openReadOnly(context, ASSET_PATH, DB_FILE_NAME)
        )
    }

    /**
     * Exact-term lookup over the `terms` index, ordered common-first then by entry id, followed by
     * any JMnedict proper noun with that exact form.
     *
     * [term] must be a dictionary form as stored in the database — inflected surfaces go through
     * [Deinflector] first (see [WordScanner]). Entries reachable from several matching rows are
     * returned once. Name entries come last on purpose: they exist to make a circled place or person
     * name resolvable, not to outrank the word the user probably wants.
     */
    fun lookup(term: String, limit: Int = 8): List<DictEntry> {
        if (term.isEmpty() || limit <= 0) return emptyList()

        val entries = ArrayList<DictEntry>(minOf(limit, 8))
        val seenIds = HashSet<Int>()
        db.rawQuery(LOOKUP_SQL, arrayOf(term, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = cursor.readEntry()
                if (seenIds.add(entry.id)) entries.add(entry)
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

    /**
     * The entries whose term starts with [prefix], in term order — the search screen's suggestions
     * behind the entries that matched the query exactly.
     *
     * A term is returned once even when several of an entry's own terms fall in the range
     * (コンピューター and コンピュータ share the entry), which is why the query over-reads: an entry
     * costs one row per matching term. At most [limit] entries come back.
     */
    fun lookupPrefix(prefix: String, limit: Int = 8): List<DictEntry> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()
        val upper = DictionarySearch.prefixUpperBound(prefix)
        // The bound closes on the prefix itself for the one code point that has no successor; the
        // range would then be empty, and the query is not worth running.
        if (upper <= prefix) return emptyList()

        val entries = ArrayList<DictEntry>(minOf(limit, 8))
        val seenIds = HashSet<Int>()
        val rows = limit * PREFIX_OVER_READ
        db.rawQuery(PREFIX_SQL, arrayOf(prefix, upper, rows.toString())).use { cursor ->
            while (cursor.moveToNext() && entries.size < limit) {
                val entry = cursor.readEntry()
                if (seenIds.add(entry.id)) entries.add(entry)
            }
        }
        return entries
    }

    /**
     * The entries whose reading collapses to [reading] — the romaji reader's fallback, asked with
     * [DictionarySearch.looseForm] of the query's kana.
     *
     * "tokyo" converts to ときょ, which no dictionary reading spells, while 東京's reading とうきょう
     * collapses to exactly that. Only a romaji query is looked up this way; a query typed in kana is
     * answered by [lookup] alone.
     */
    fun lookupLoose(reading: String, limit: Int = 8): List<DictEntry> {
        if (reading.isEmpty() || limit <= 0) return emptyList()

        val entries = ArrayList<DictEntry>(minOf(limit, 8))
        val seenIds = HashSet<Int>()
        db.rawQuery(LOOSE_SQL, arrayOf(reading, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = cursor.readEntry()
                if (seenIds.add(entry.id)) entries.add(entry)
            }
        }
        return entries
    }

    /** Releases the underlying read-only [SQLiteDatabase]. */
    fun close() {
        db.close()
    }
}

/**
 * One `entries` row as a [DictEntry]. Both entry queries select `e.*`, so the columns are read by
 * name and the joins above can change shape without this breaking.
 */
private fun Cursor.readEntry(): DictEntry {
    val kanji = splitJoined(getColumnIndexOrThrow("kanji"), " ")
    val kana = splitJoined(getColumnIndexOrThrow("kana"), " ")
    return DictEntry(
        id = getInt(getColumnIndexOrThrow("id")),
        primary = kanji.firstOrNull() ?: kana.firstOrNull() ?: "",
        kanji = kanji,
        kana = kana,
        glosses = splitJoined(getColumnIndexOrThrow("gloss"), " | "),
        pos = splitJoined(getColumnIndexOrThrow("pos"), " | "),
        common = getInt(getColumnIndexOrThrow("common")) != 0
    )
}

/** Splits a `' '` or `' | '` joined database column back into its individual values. */
private fun Cursor.splitJoined(index: Int, separator: String): List<String> {
    val value = getString(index)
    return if (value.isEmpty()) emptyList() else value.split(separator).filter { it.isNotEmpty() }
}
