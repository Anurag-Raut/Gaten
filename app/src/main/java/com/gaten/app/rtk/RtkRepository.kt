/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rtk

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.gaten.app.data.BundledDatabase

/**
 * Read-only access to the Remembering the Kanji reference bundled with the app.
 *
 * The database ships uncompressed as `assets/db/rtk.sqlite` and is copied once to
 * `filesDir/rtk.sqlite` on the first [open] by [BundledDatabase]. Unlike WaniKani there is no
 * token, no network and no cache to fall back on: every answer is one indexed row on this device,
 * available immediately after installation.
 */
class RtkRepository private constructor(private val db: SQLiteDatabase) {

    companion object {
        private const val ASSET_PATH = "db/rtk.sqlite"
        private const val DB_FILE_NAME = "rtk.sqlite"

        /** The primary key is the character: one indexed row, no scan, no story text in JS. */
        private const val LOOKUP_SQL =
            "SELECT keyword, story, primitive, mnemonics FROM rtk WHERE character = ?"

        /**
         * Copies `assets/db/rtk.sqlite` to `context.filesDir` if needed (idempotent, safe under
         * concurrent calls) and opens the result read-only.
         *
         * @throws java.io.IOException when the asset is missing or the extracted file cannot be installed.
         */
        suspend fun open(context: Context): RtkRepository = RtkRepository(
            BundledDatabase.openReadOnly(context, ASSET_PATH, DB_FILE_NAME)
        )
    }

    /** The entry for [character], or null when the dataset does not carry that kanji. */
    fun lookup(character: String): RtkEntry? {
        if (character.isEmpty()) return null
        db.rawQuery(LOOKUP_SQL, arrayOf(character)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return rtkEntry(
                character = character,
                keyword = cursor.getString(0),
                story = cursor.getString(1),
                primitive = cursor.getString(2),
                mnemonicsJson = cursor.getString(3)
            )
        }
    }

    /** Releases the underlying read-only [SQLiteDatabase]. */
    fun close() {
        db.close()
    }
}
