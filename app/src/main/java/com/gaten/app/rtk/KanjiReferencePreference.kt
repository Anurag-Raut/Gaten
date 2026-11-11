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
        /**
         * What a device that never chose shows. WaniKani's pages need an account, so a device
         * without one gets Remembering the Kanji, which is bundled and works offline; a connected
         * account keeps the WaniKani pages every device had before the choice existed.
         */
        fun defaultFor(wanikaniConfigured: Boolean): KanjiReferenceSource =
            if (wanikaniConfigured) WANIKANI else RTK

        /**
         * The stored value, with anything unrecognised (or absent) reading as [defaultFor]. A
         * stored value is the reader's own choice and is kept whatever the connection is doing.
         */
        fun fromStored(
            value: String?,
            wanikaniConfigured: Boolean
        ): KanjiReferenceSource =
            entries.firstOrNull { it.name == value } ?: defaultFor(wanikaniConfigured)

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

/**
 * Reads and writes the selected reference source. [wanikaniConfigured] is only what a device that
 * never chose falls back to; the caller reads the connection, so the store itself knows nothing
 * about WaniKani beyond that answer.
 */
internal class KanjiReferencePreference(
    private val storage: KanjiReferenceStorage,
    private val wanikaniConfigured: Boolean
) {

    fun source(): KanjiReferenceSource =
        KanjiReferenceSource.fromStored(storage.source(), wanikaniConfigured)

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

/** The preference file the choice lives in, with the connection a never-chosen device follows. */
internal fun kanjiReferencePreference(
    context: Context,
    wanikaniConfigured: Boolean
): KanjiReferencePreference =
    KanjiReferencePreference(
        SharedPreferencesStorage(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        ),
        wanikaniConfigured
    )

private const val PREFERENCES = "gaten_settings"
private const val KEY_SOURCE = "kanji_reference_source"
