/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context
import android.content.SharedPreferences
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableArray
import com.gaten.app.dict.DictionarySearch
import com.gaten.app.dict.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The dictionary search, for the RN screens.
 *
 * Everything is [DictionarySearch]'s and [JmdictRepository]'s own work — the ranking, the folding,
 * the deinflection and the bounds the Compose search screen shows. The repository arrives from
 * [GatenDictStore], whose first open copies the bundled database to filesDir, so the cost sits
 * behind the first query and not behind the module.
 *
 * There is no dictionary entry-by-id read: [JmdictRepository] answers surfaces, not ids. Recent
 * history reuses the search results the screen already has, so reopening one needs no new dictionary
 * query.
 */
class GatenDictModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    private val scope = bridgeScope()
    private val historyMutex = Mutex()

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

    /** The most recently opened dictionary entries, in the order they were opened. */
    @ReactMethod
    fun recentLookups(promise: Promise) {
        scope.launch {
            try {
                val entries = historyMutex.withLock {
                    withContext(Dispatchers.IO) {
                        preferences().getString(KEY_RECENT_LOOKUPS, "[]")?.let(::JSONArray)
                            ?: JSONArray()
                    }
                }
                promise.resolve(entries.toJsArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_HISTORY, "Could not read recent lookups", e)
            }
        }
    }

    /** Saves the entry the reader opened in word detail, keeping the latest twelve unique ids. */
    @ReactMethod
    fun rememberLookup(entry: ReadableMap, promise: Promise) {
        scope.launch {
            try {
                val value = entry.toHistoryJson()
                historyMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val preferences = preferences()
                        val old = preferences.getString(KEY_RECENT_LOOKUPS, "[]")?.let(::JSONArray)
                            ?: JSONArray()
                        val updated = JSONArray().put(value)
                        for (index in 0 until old.length()) {
                            val prior = old.getJSONObject(index)
                            if (prior.getInt("id") != value.getInt("id") &&
                                updated.length() < MAX_RECENT_LOOKUPS
                            ) {
                                updated.put(prior)
                            }
                        }
                        if (!preferences.edit().putString(KEY_RECENT_LOOKUPS, updated.toString()).commit()) {
                            throw IOException("Recent lookup history was not saved")
                        }
                    }
                }
                promise.resolve(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                promise.reject(CODE_HISTORY_INPUT, e.message, e)
            } catch (e: Exception) {
                promise.reject(CODE_HISTORY, "Could not save recent lookup", e)
            }
        }
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

    private fun JSONArray.toJsArray(): WritableArray = Arguments.createArray().apply {
        for (index in 0 until length()) {
            pushMap(getJSONObject(index).toJsMap())
        }
    }

    // Qualify JSON reads: the React Native output map and arrays also expose getters.
    private fun JSONObject.toJsMap() = Arguments.createMap().apply {
        putInt("id", this@toJsMap.getInt("id"))
        putString("primary", this@toJsMap.getString("primary"))
        putArray("kanji", this@toJsMap.getJSONArray("kanji").toJsStrings())
        putArray("kana", this@toJsMap.getJSONArray("kana").toJsStrings())
        putArray("glosses", this@toJsMap.getJSONArray("glosses").toJsStrings())
        putArray("pos", this@toJsMap.getJSONArray("pos").toJsStrings())
        putBoolean("common", this@toJsMap.getBoolean("common"))
        putString("match", this@toJsMap.getString("match"))
        putString("form", this@toJsMap.getString("form"))
    }

    private fun JSONArray.toJsStrings(): WritableArray = Arguments.createArray().apply {
        for (index in 0 until length()) pushString(this@toJsStrings.getString(index))
    }

    private fun ReadableMap.toHistoryJson(): JSONObject {
        val id = if (hasKey("id") && !isNull("id")) getInt("id") else 0
        val primary = if (hasKey("primary") && !isNull("primary")) getString("primary")?.trim() else null
        val match = if (hasKey("match") && !isNull("match")) getString("match") else null
        val form = if (hasKey("form") && !isNull("form")) getString("form") else null
        require(id != 0) { "Entry id is missing" }
        require(!primary.isNullOrEmpty()) { "Entry spelling is missing" }
        require(match != null && match in MATCHES) { "Entry match type is invalid" }
        require(!form.isNullOrEmpty()) { "Entry search form is missing" }

        return JSONObject()
            .put("id", id)
            .put("primary", primary)
            .put("kanji", getStringList("kanji"))
            .put("kana", getStringList("kana"))
            .put("glosses", getStringList("glosses"))
            .put("pos", getStringList("pos"))
            .put("common", hasKey("common") && !isNull("common") && getBoolean("common"))
            .put("match", match)
            .put("form", form)
    }

    private fun ReadableMap.getStringList(name: String): JSONArray {
        val values = if (hasKey(name) && !isNull(name)) getArray(name) else null
        return JSONArray().apply {
            if (values != null) {
                for (index in 0 until values.size()) {
                    require(values.getType(index) == ReadableType.String) { "$name contains a non-string value" }
                    put(values.getString(index).orEmpty())
                }
            }
        }
    }

    private fun preferences(): SharedPreferences = reactApplicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        const val NAME = "GatenDict"

        /** The only failure the search can produce: the repository never opened. */
        const val CODE_OPEN_FAILED = "E_DICT_OPEN"
        const val CODE_HISTORY = "E_DICT_HISTORY"
        const val CODE_HISTORY_INPUT = "E_DICT_HISTORY_INPUT"

        private const val PREFERENCES = "gaten_dictionary_history"
        private const val KEY_RECENT_LOOKUPS = "recent_lookups"
        private const val MAX_RECENT_LOOKUPS = 12
        private val MATCHES = setOf("EXACT", "NORMALIZED", "LOOSE", "PREFIX")
    }
}
