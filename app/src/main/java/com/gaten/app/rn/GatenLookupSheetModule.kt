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

    /** The sheet is listening: the latest state is flushed to it now. */
    @ReactMethod
    fun ready() {
        actions?.onReady()
        markReady()
    }

    /** Open the app's WaniKani settings page from the sheet's kanji panel. */
    @ReactMethod
    fun openSettingsPage(page: String) {
        actions?.onOpenSettingsPage(page)
    }

    /**
     * The current state, for a read that arrives out of band. The publish/ready handshake in
     * [LookupSheetStateGate] is the sheet's normal path; this remains for a caller that explicitly
     * asks for the latest map.
     */
    @ReactMethod
    fun getState(promise: Promise) {
        promise.resolve(gate.current() as? WritableMap ?: Arguments.createMap())
    }

    companion object {
        const val NAME = "GatenLookupSheet"

        /** The open session's state and readiness, in one testable place. */
        private val gate = LookupSheetStateGate()

        /** The open session's actions, if any. */
        @Volatile
        private var actions: LookupSheetHost.Actions? = null

        /** Binds the open session's actions and starts a fresh handshake. */
        fun attach(sessionActions: LookupSheetHost.Actions) {
            actions = sessionActions
            gate.attach()
        }

        /** Forgets the closed session's actions, so a late call cannot reach the next lookup. */
        fun detach() {
            actions = null
            gate.detach()
        }

        /** Publishes one sheet state to JS once it is listening, and remembers it until then. */
        fun publish(state: WritableMap) {
            (gate.publish(state) as? WritableMap)?.let { GatenBridgeEmitter.emitLookupSheetState(it) }
        }

        /** The RN root is listening: flush the latest state it may have missed while loading. */
        fun markReady() {
            (gate.ready() as? WritableMap)?.let { GatenBridgeEmitter.emitLookupSheetState(it) }
        }
    }
}
