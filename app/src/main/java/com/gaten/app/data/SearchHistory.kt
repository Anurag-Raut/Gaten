/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.data

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The words the reader has searched with, kept on the device.
 *
 * This is the one place the history is read and written: the RN screens reach it through
 * `GatenDictModule` and the lookup cover reads it directly, so a word searched in Home is offered by
 * the cover and the other way round — one file, one key, one set of rules, whoever asks.
 *
 * The rules are the module's own, unchanged: the newest word goes to the front, a copy of it further
 * down the list goes, the list is capped at [LIMIT], and a write that does not commit is an error
 * rather than a silent loss. Only a query the reader acted on belongs here — the keyboard's search
 * action, a chip on the empty prompt, or a result they opened. Typing does not: those fragments are
 * text they are still writing.
 *
 * One lock covers both directions, so two writers in this process cannot lose each other's update.
 */
object SearchHistory {
    /** The words kept, newest first. The empty prompt offers exactly these. */
    const val LIMIT = 6

    /** A bound on what may be written, well past `DictionarySearch.QUERY_LIMIT`. */
    private const val MAX_QUERY_LENGTH = 64

    /** The file and the key the RN module has always used, now named here and nowhere else. */
    private const val PREFERENCES = "gaten_dictionary_history"
    private const val KEY = "recent_searches"

    private val mutex = Mutex()

    /**
     * The one history file, shared with the recent lookups the module keeps in the same place. The
     * search history is the only part this object reads and writes.
     */
    internal fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** The queries the reader has run, most recent first. Throws when the stored value is unusable. */
    suspend fun recents(context: Context): List<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val stored = preferences(context).getString(KEY, "[]")?.let(::JSONArray) ?: JSONArray()
            List(stored.length()) { index -> stored.getString(index) }
        }
    }

    /**
     * Saves [query] as a word the reader searched with: trimmed, at the front of the list, without a
     * copy of itself after it, and never past [LIMIT].
     *
     * @throws IllegalArgumentException when the query is empty or longer than the history is allowed
     *   to hold — the caller's own input error, not a failure of the store.
     * @throws IOException when the write did not commit.
     */
    suspend fun remember(context: Context, query: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "The search to remember is empty" }
            require(trimmed.length <= MAX_QUERY_LENGTH) { "The search to remember is too long" }

            val preferences = preferences(context)
            val old = preferences.getString(KEY, "[]")?.let(::JSONArray) ?: JSONArray()
            val updated = JSONArray().put(trimmed)
            for (index in 0 until old.length()) {
                val prior = old.getString(index)
                if (prior != trimmed && updated.length() < LIMIT) {
                    updated.put(prior)
                }
            }
            if (!preferences.edit().putString(KEY, updated.toString()).commit()) {
                throw IOException("Recent search history was not saved")
            }
        }
    }
}
