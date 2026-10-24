/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place native code hands an event to the RN screens.
 *
 * The React context arrives when the first bridge module is created and may be replaced on a
 * reload, so it is held in an [AtomicReference] rather than captured once. [emitThemeChanged] is
 * what a theme change calls — the Compose activity's settings page, or the bridge's own theme
 * setters — and it is quiet when JS is not listening yet: the screens read the theme through
 * [GatenThemeModule] on mount anyway, so a lost event costs a stale screen only until the next
 * change, and there is nothing to retry.
 */
object GatenBridgeEmitter {
    private const val TAG = "GatenBridge"

    /** The JS event name; the RN side listens for it through RCTDeviceEventEmitter. */
    const val THEME_CHANGED = "gaten.themeChanged"

    /** Carries the settings page an outside launch asked for: {"page": "wanikani"} or empty. */
    const val SETTINGS_PAGE_REQUEST = "gaten.settingsPageRequest"

    /**
     * The floating-button preference changed, from the on-screen remove button or from a settings
     * write. The payload carries nothing; the Screen lookup page re-reads the preference, which is
     * what keeps its switch in step with a dismissal it did not perform.
     */
    const val FLOATING_BUTTON_CHANGED = "gaten.floatingButtonChanged"

    private val reactContext = AtomicReference<ReactContext?>(null)

    /** Records the context events are sent through. Called by every module on creation. */
    fun attach(reactContext: ReactContext) {
        this.reactContext.set(reactContext)
    }

    /** Tells JS the theme choices changed; the payload carries nothing, so the screens re-read. */
    fun emitThemeChanged() {
        emit(THEME_CHANGED, Arguments.createMap())
    }

    /**
     * Tells JS the floating-button preference changed; the payload carries nothing, so the Screen
     * lookup page re-reads the switch.
     */
    fun emitFloatingButtonChanged() {
        emit(FLOATING_BUTTON_CHANGED, Arguments.createMap())
    }

    /**
     * Tells JS an outside launch asked for one settings page, or that the pending request is gone
     * (an empty page). Fired from [MainActivity.onNewIntent]; a lost event costs nothing because
     * the field is also readable through [GatenShellModule].
     */
    fun emitSettingsPageRequest(page: String?) {
        val payload = Arguments.createMap()
        if (page != null) payload.putString("page", page)
        emit(SETTINGS_PAGE_REQUEST, payload)
    }

    /** Sends one event through the shared context; quiet when JS is not listening yet. */
    private fun emit(name: String, payload: Any) {
        val context = reactContext.get() ?: return
        try {
            context
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, payload)
        } catch (e: Exception) {
            Log.w(TAG, "Could not deliver $name; JS not ready", e)
        }
    }
}
