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
 * React Native. JS calls these methods to act on the session — close, open the shared dictionary
 * search, drop the result and select again, report that its field is editing — and native pushes
 * each new state through [GatenBridgeEmitter.LOOKUP_SHEET_STATE]. [getState] lets the sheet read the
 * current state once on mount, so an event that fired before JS was listening costs nothing.
 */
class GatenLookupSheetModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        GatenBridgeEmitter.attach(reactContext)
        instance = this
    }

    override fun getName(): String = NAME

    /** Close the lookup and dismiss the overlay. */
    @ReactMethod
    fun close() {
        LookupSheetHost.dispatchClose()
    }

    /** Open the shared dictionary search inside the same surface. */
    @ReactMethod
    fun openSearch() {
        LookupSheetHost.dispatchOpenSearch()
    }

    /** Discard the current result and return to selection. */
    @ReactMethod
    fun selectAgain() {
        LookupSheetHost.dispatchSelectAgain()
    }

    /** The sheet's field gained or lost editing focus, for the overlay's focus-loss attribution. */
    @ReactMethod
    fun textEntryActive(active: Boolean) {
        LookupSheetHost.dispatchTextEntryActive(active)
    }

    /** The sheet has drawn its first frame. */
    @ReactMethod
    fun ready() {
        LookupSheetHost.dispatchReady()
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

        /** The module now mounted, if any; native publishes state through it. */
        @Volatile
        private var instance: GatenLookupSheetModule? = null

        /** Publishes one sheet state to JS and remembers it for the next [getState]. */
        fun publish(state: WritableMap) {
            lastState = state
            GatenBridgeEmitter.emitLookupSheetState(state)
        }
    }
}
