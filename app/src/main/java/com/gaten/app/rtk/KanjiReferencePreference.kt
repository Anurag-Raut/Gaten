/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rtk

import android.content.Context
import android.content.SharedPreferences

/**
 * Which reference a kanji page shows: WaniKani's online subjects, or the offline Remembering the
 * Kanji dataset. It changes only what the reference pages display — the WaniKani connection, its
 * cache, reviews, lessons and analytics are untouched either way.
 *
 * [wire] is what crosses the React Native bridge; [name] is what is stored on disk.
 */
enum class KanjiReferenceSource(val wire: String) {
    WANIKANI("wanikani"),
    RTK("rtk");

    companion object {
        /** The default for a device that never chose — every existing user had WaniKani. */
        val DEFAULT: KanjiReferenceSource = WANIKANI

        /** The stored value, with anything unrecognised (or absent) reading as [DEFAULT]. */
        fun fromStored(value: String?): KanjiReferenceSource =
            entries.firstOrNull { it.name == value } ?: DEFAULT

        /** The bridge's spelling, or null when it names no source. */
        fun fromWire(value: String?): KanjiReferenceSource? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * The store the choice lives in, as an interface so the default and the round trip can be exercised
 * on a plain JVM, without a `SharedPreferences` and without a device.
 */
internal interface KanjiReferenceStorage {
    /** The stored enum name, or null when nothing was ever stored. */
    fun source(): String?

    /** Stores [value]; false when it did not take. */
    fun saveSource(value: String): Boolean
}

/** Reads and writes the selected reference source. */
internal class KanjiReferencePreference(private val storage: KanjiReferenceStorage) {

    fun source(): KanjiReferenceSource = KanjiReferenceSource.fromStored(storage.source())

    /**
     * Stores [source] and answers what is on disk afterwards — never the request — so a write that
     * did not take reads back as the source that is actually in force.
     */
    fun setSource(source: KanjiReferenceSource): KanjiReferenceSource {
        storage.saveSource(source.name)
        return this.source()
    }
}

/** The app's own preference file behind the choice; theme and Anki preferences live here too. */
private class SharedPreferencesStorage(
    private val preferences: SharedPreferences
) : KanjiReferenceStorage {

    override fun source(): String? = preferences.getString(KEY_SOURCE, null)

    override fun saveSource(value: String): Boolean =
        preferences.edit().putString(KEY_SOURCE, value).commit()
}

/** The preference file the choice lives in. */
internal fun kanjiReferencePreference(context: Context): KanjiReferencePreference =
    KanjiReferencePreference(
        SharedPreferencesStorage(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        )
    )

private const val PREFERENCES = "gaten_settings"
private const val KEY_SOURCE = "kanji_reference_source"
