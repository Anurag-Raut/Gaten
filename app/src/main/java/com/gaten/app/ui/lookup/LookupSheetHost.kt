/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.Context
import android.os.Bundle
import android.view.View
import com.facebook.react.ReactHost
import com.facebook.react.bridge.Arguments
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.runtime.ReactSurfaceImpl
import com.gaten.app.MainApplication
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.SearchMatch
import com.gaten.app.dict.ScannedWord
import com.gaten.app.rn.GatenLookupSheetModule
import org.json.JSONArray
import org.json.JSONObject

/**
 * The React Native side of the lookup overlay.
 *
 * The overlay window stays native — the accessibility service owns it, freezes the capture and
 * draws the selection — but every sheet inside it is the app's own RN UI. One session mounts one
 * [ReactSurface] onto the **application's existing [ReactHost]** ([MainApplication.reactHost]), so
 * the sheet shares the instance, JS bundle, TurboModules and Hermes runtime the main app already
 * runs: no second runtime and no Activity. Each overlay session opens its own surface and stops it
 * on close; the shared host is never touched.
 *
 * All calls are main-thread, like the window they add to. State reaches JS two ways: the first map
 * rides the surface's initial props, and every later map is also emitted as
 * [com.gaten.app.rn.GatenBridgeEmitter.LOOKUP_SHEET_STATE], so a late listener can catch up through
 * [GatenLookupSheetModule.getState].
 */
object LookupSheetHost {

    /** An open sheet: its root view and the way to stop it. */
    interface Session {
        val view: View
        fun stop()
    }

    /** What the sheet can ask the overlay to do. */
    interface Actions {
        fun onClose()
        fun onSelectAgain()
        fun onTextEntryActive(active: Boolean)
        fun onReady()
        /** Open the app's WaniKani settings page (an existing permission flow, not a new route). */
        fun onOpenSettingsPage(page: String)
    }

    const val MODULE_NAME = "GatenLookupSheet"

    /** The state key names the RN root reads. Kept together so both sides agree. */
    const val KEY_MODE = "mode"
    const val KEY_SOURCE = "source"
    const val KEY_WORDS = "words"
    const val KEY_SELECTED = "selected"
    const val KEY_QUERY = "query"
    const val KEY_EMPTY_KIND = "emptyKind"
    const val MODE_SHEET = "sheet"
    const val MODE_SEARCH = "search"
    const val MODE_EMPTY = "empty"
    const val EMPTY_NO_TEXT = "noText"
    const val EMPTY_NO_MATCHES = "noMatches"

    /** Creates a surface for one overlay session and returns its handle, or null if RN cannot mount. */
    fun open(
        context: Context,
        initialProps: Bundle,
        hostActions: Actions
    ): Session? {
        val app = context.applicationContext as? MainApplication ?: return null
        val reactHost: ReactHost = app.reactHost
        val surface: ReactSurface = reactHost.createSurface(context, MODULE_NAME, initialProps)
        val view = surface.view ?: return null
        surface.start()
        return object : Session {
            override val view: View = view
            override fun stop() {
                surface.stop()
            }
        }.also { GatenLookupSheetModule.attach(hostActions) }
    }

    /** The surface was stopped and taken down; forget the session's actions. */
    fun close() {
        GatenLookupSheetModule.detach()
    }

    /**
     * Builds and publishes the whole state the RN sheet renders. [words] are the resolved
     * [ScannedWord]s with their actual dictionary entries — the overlay already chose them, so the
     * RN side never re-searches a surface or guesses which entry the reader's selection meant.
     */
    fun publish(
        mode: String,
        source: String,
        words: List<ScannedWord> = emptyList(),
        selected: Int = 0,
        searchQuery: String = "",
        emptyKind: String? = null
    ) {
        val state = Arguments.createMap().apply {
            putString(KEY_MODE, mode)
            putString(KEY_SOURCE, source)
            putInt(KEY_SELECTED, selected)
            putString(KEY_QUERY, searchQuery)
            if (emptyKind != null) putString(KEY_EMPTY_KIND, emptyKind)
            putString(KEY_WORDS, wordsToJson(words))
        }
        GatenLookupSheetModule.publish(state)
    }

    /** The initial props for a fresh surface: the same map [publish] emits, as a Bundle. */
    fun initialState(
        mode: String,
        source: String,
        words: List<ScannedWord> = emptyList(),
        selected: Int = 0,
        searchQuery: String = "",
        emptyKind: String? = null
    ): Bundle = Bundle().apply {
        putString(KEY_MODE, mode)
        putString(KEY_SOURCE, source)
        putInt(KEY_SELECTED, selected)
        putString(KEY_QUERY, searchQuery)
        if (emptyKind != null) putString(KEY_EMPTY_KIND, emptyKind)
        putString(KEY_WORDS, wordsToJson(words))
    }

    /** Serializes the resolved words to the JSON the RN sheet parses. */
    fun wordsToJson(words: List<ScannedWord>): String {
        val array = JSONArray()
        words.forEach { word ->
            array.put(
                JSONObject().apply {
                    put("surface", word.surface)
                    put("baseForm", word.baseForm)
                    put("context", word.context)
                    put(
                        "entries",
                        JSONArray().apply {
                            word.entries.forEach { entry ->
                                put(entryToJson(entry, word.surface))
                            }
                        }
                    )
                }
            )
        }
        return array.toString()
    }

    private fun entryToJson(entry: DictEntry, form: String): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("primary", entry.primary)
        put("kanji", JSONArray(entry.kanji))
        put("kana", JSONArray(entry.kana))
        put("glosses", JSONArray(entry.glosses))
        put("pos", JSONArray(entry.pos))
        put("common", entry.common)
        put("match", SearchMatch.EXACT.name)
        put("form", form)
    }
}
