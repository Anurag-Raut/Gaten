/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.rtk.KanjiReferenceSource
import com.gaten.app.rtk.RtkEntry
import com.gaten.app.rtk.RtkRepository
import com.gaten.app.rtk.kanjiReferencePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The offline Remembering the Kanji reference, for the RN screens, plus the choice of which
 * reference a kanji page shows.
 *
 * [lookupKanji] answers from the bundled database and nothing else: no token, no account, no
 * request, no cache to go stale. It resolves one row per character rather than streaming the
 * dataset, so 2,991 stories never cross the bridge together. The choice is stored by
 * [kanjiReferencePreference] in the app's own settings file; it decides what a reference page
 * displays and deliberately leaves the WaniKani connection and every WaniKani surface untouched.
 */
class GatenRtkModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = bridgeScope()

    override fun getName(): String = NAME

    override fun invalidate() {
        scope.cancel()
    }

    /** The Remembering the Kanji row for [character], or `{status: "notAvailable"}` when absent. */
    @ReactMethod
    fun lookupKanji(character: String, promise: Promise) {
        scope.launch {
            try {
                val entry = RtkStore.repository(reactApplicationContext).lookup(character)
                promise.resolve(
                    Arguments.createMap().apply {
                        if (entry == null) {
                            putString("status", "notAvailable")
                        } else {
                            putString("status", "available")
                            putMap("entry", entry.toJsMap())
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(
                    CODE_LOOKUP,
                    e.message ?: "Could not read the Remembering the Kanji data",
                    e
                )
            }
        }
    }

    /** `"wanikani"` or `"rtk"`, read from the device on every call. */
    @ReactMethod
    fun getReferenceSource(promise: Promise) {
        try {
            promise.resolve(kanjiReferencePreference(reactApplicationContext).source().wire)
        } catch (e: Exception) {
            promise.reject(CODE_PREFERENCE, e.message ?: "Could not read the kanji reference", e)
        }
    }

    /** Stores the choice and resolves the source actually on disk afterwards. */
    @ReactMethod
    fun setReferenceSource(source: String, promise: Promise) {
        val requested = KanjiReferenceSource.fromWire(source)
        if (requested == null) {
            promise.reject(CODE_INVALID_SOURCE, "Unknown kanji reference source: $source")
            return
        }
        try {
            promise.resolve(kanjiReferencePreference(reactApplicationContext).setSource(requested).wire)
        } catch (e: Exception) {
            promise.reject(CODE_PREFERENCE, e.message ?: "Could not save the kanji reference", e)
        }
    }

    private fun RtkEntry.toJsMap(): WritableMap = Arguments.createMap().apply {
        putString("character", character)
        putString("keyword", keyword)
        putString("story", story)
        putString("primitive", primitive)
        putArray(
            "mnemonics",
            Arguments.createArray().apply {
                this@toJsMap.mnemonics.forEach { mnemonic ->
                    pushMap(
                        Arguments.createMap().apply {
                            putInt("index", mnemonic.index)
                            putString("author", mnemonic.author)
                            mnemonic.profileUrl?.let { putString("profileUrl", it) }
                            putString("date", mnemonic.date)
                            putInt("score", mnemonic.score)
                            putString("body", mnemonic.body)
                        }
                    )
                }
            }
        )
    }

    companion object {
        const val NAME = "GatenRtk"

        private const val CODE_LOOKUP = "E_RTK"
        private const val CODE_PREFERENCE = "E_REFERENCE_SOURCE"
        private const val CODE_INVALID_SOURCE = "RTK_INVALID_SOURCE"
    }
}

/**
 * The app's one open [RtkRepository], shared by every lookup. The database is read-only and copied
 * out of the assets on first use, so the mutex keeps two simultaneous taps from racing that copy.
 */
private object RtkStore {
    private val mutex = Mutex()
    private var repository: RtkRepository? = null

    suspend fun repository(context: Context): RtkRepository {
        repository?.let { return it }
        return mutex.withLock {
            repository ?: RtkRepository.open(context.applicationContext).also { repository = it }
        }
    }
}
