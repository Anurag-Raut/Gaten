/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.gaten.app.dict.DictionarySearch
import com.gaten.app.dict.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The dictionary search, for the RN screens.
 *
 * Everything is [DictionarySearch]'s and [JmdictRepository]'s own work — the ranking, the folding,
 * the deinflection and the bounds the Compose search screen shows. The repository arrives from
 * [GatenDictStore], whose first open copies the bundled database to filesDir, so the cost sits
 * behind the first query and not behind the module.
 *
 * There is no entry-by-id read: [JmdictRepository] answers surfaces, not ids, and the search
 * results already carry everything the screens render.
 */
class GatenDictModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = bridgeScope()

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        scope.cancel()
    }

    /**
     * The results for [query], most likely first — [DictionarySearch.search]'s own rows, with the
     * entry's fields and the match that reached it.
     *
     * Rejects with `E_DICT_OPEN` when the repository could not be opened (the asset is missing or
     * the copy could not be installed); a query the dictionary cannot answer is not an error, it is
     * an empty list.
     */
    @ReactMethod
    fun search(query: String, promise: Promise) {
        scope.launch {
            try {
                val repository = GatenDictStore.repository(reactApplicationContext)
                val hits = DictionarySearch.search(
                    query,
                    repository::lookup,
                    repository::lookupPrefix,
                    repository::lookupLoose,
                )
                promise.resolve(hits.toJsArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_OPEN_FAILED, "Could not open the dictionary: ${e.message}", e)
            }
        }
    }

    /**
     * True when [query] is something the search can be asked about — Japanese, or romaji the
     * converter reads in full ([DictionarySearch.isSearchable]). The screen uses this to tell a
     * query it has no answer for from one it has not looked up yet.
     */
    @ReactMethod
    fun isSearchable(query: String, promise: Promise) {
        promise.resolve(DictionarySearch.isSearchable(DictionarySearch.normalize(query)))
    }

    private fun List<SearchHit>.toJsArray(): WritableArray =
        Arguments.createArray().apply {
            forEach { hit -> pushMap(hit.toJsMap()) }
        }

    private fun SearchHit.toJsMap() = Arguments.createMap().apply {
        putInt("id", entry.id)
        putString("primary", entry.primary)
        putArray("kanji", entry.kanji.toJsStrings())
        putArray("kana", entry.kana.toJsStrings())
        putArray("glosses", entry.glosses.toJsStrings())
        putArray("pos", entry.pos.toJsStrings())
        putBoolean("common", entry.common)
        putString("match", match.name)
        putString("form", form)
    }

    private fun List<String>.toJsStrings(): WritableArray =
        Arguments.createArray().apply { forEach { pushString(it) } }

    companion object {
        const val NAME = "GatenDict"

        /** The only failure the search can produce: the repository never opened. */
        const val CODE_OPEN_FAILED = "E_DICT_OPEN"
    }
}
