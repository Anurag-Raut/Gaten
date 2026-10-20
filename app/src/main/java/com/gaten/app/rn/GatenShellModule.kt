/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.MainActivity

/**
 * The shell's own module: the settings page an outside launch asked for, held by [MainActivity]
 * since it can arrive after the app is up.
 *
 * JS reads the request once on mount and again whenever [GatenBridgeEmitter.SETTINGS_PAGE_REQUEST]
 * announces a fresh one, then calls [consumeSettingsPageRequest] so each intent is acted on once.
 */
class GatenShellModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    /** The pending request, or null when there is none — read, never cleared. */
    @ReactMethod
    fun getSettingsPageRequest(promise: Promise) {
        val activity = getCurrentActivity() as? MainActivity
        promise.resolve(activity?.settingsPageRequest)
    }

    /** Clears the pending request: the screen that read it has navigated. */
    @ReactMethod
    fun consumeSettingsPageRequest() {
        (getCurrentActivity() as? MainActivity)?.consumeSettingsPageRequest()
    }

    companion object {
        const val NAME = "GatenShell"
    }
}
