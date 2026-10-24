/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.ui.lookup.LookupSheetHost

/**
 * The contract between the RN lookup sheet and the native overlay that owns its window.
 *
 * The overlay stays native (capture, OCR, selection, accessibility window); the sheet inside it is
 * React Native. JS calls these methods to act on the session — close, drop the result and select
 * again, report that its field is editing — and native pushes each new state through
 * [GatenBridgeEmitter.LOOKUP_SHEET_STATE]. [getState] lets the sheet read the current state once on
 * mount, so an event that fired before JS was listening costs nothing.
 *
 * The session's actions are attached when the overlay opens the surface and detached when it
 * closes, so a stale call after close is a no-op rather than reaching the next lookup.
 */
class GatenLookupSheetModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    /** Close the lookup and dismiss the overlay. */
    @ReactMethod
    fun close() {
        actions?.onClose()
    }

    /** The sheet wants the shared dictionary search; the sheet itself renders it. */
    @ReactMethod
    fun openSearch() {
        // Owned entirely inside the RN sheet now; nothing for the overlay to do.
    }

    /** Discard the current result and return to selection. */
    @ReactMethod
    fun selectAgain() {
        actions?.onSelectAgain()
    }

    /** The sheet's field gained or lost editing focus, for the overlay's focus-loss attribution. */
    @ReactMethod
    fun textEntryActive(active: Boolean) {
        actions?.onTextEntryActive(active)
    }

    /** The sheet has drawn its first frame. */
    @ReactMethod
    fun ready() {
        actions?.onReady()
    }

    /** Open the app's WaniKani settings page from the sheet's kanji panel. */
    @ReactMethod
    fun openSettingsPage(page: String) {
        actions?.onOpenSettingsPage(page)
    }

    /** The current state, for the sheet's first read on mount. */
    @ReactMethod
    fun getState(promise: Promise) {
        promise.resolve(lastState ?: Arguments.createMap())
    }

    companion object {
        const val NAME = "GatenLookupSheet"

        /** The last state native published, so a late mount still gets it. */
        @Volatile
        private var lastState: WritableMap? = null

        /** The open session's actions, if any. */
        @Volatile
        private var actions: LookupSheetHost.Actions? = null

        /** Binds the open session's actions. */
        fun attach(sessionActions: LookupSheetHost.Actions) {
            actions = sessionActions
            lastState = null
        }

        /** Forgets the closed session's actions, so a late call cannot reach the next lookup. */
        fun detach() {
            actions = null
            lastState = null
        }

        /** Publishes one sheet state to JS and remembers it for the next [getState]. */
        fun publish(state: WritableMap) {
            lastState = state
            GatenBridgeEmitter.emitLookupSheetState(state)
        }
    }
}
