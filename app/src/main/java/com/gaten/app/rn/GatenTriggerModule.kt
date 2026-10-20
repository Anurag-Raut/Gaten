/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.LookupTileService
import com.gaten.app.R
import com.gaten.app.trigger.TriggerPreferences

/**
 * The floating trigger's settings, as the home dashboard and the lookup page read and write them.
 *
 * The setter does what the Compose dashboard did: it stores the flag and then sends
 * [TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED] with the package set explicitly — a manifest
 * receiver only receives implicit broadcasts that name its package on API 26+, and the bubble has
 * to react even when no Gaten screen is open.
 */
class GatenTriggerModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    /** Whether the on-screen bubble is switched on. */
    @ReactMethod
    fun getFloatingButtonEnabled(promise: Promise) {
        promise.resolve(TriggerPreferences.floatingButtonEnabled(reactApplicationContext))
    }

    /**
     * Stores whether the bubble is switched on and tells the running service through the
     * floating-button-changed broadcast.
     */
    @ReactMethod
    fun setFloatingButtonEnabled(enabled: Boolean, promise: Promise) {
        val context = reactApplicationContext
        TriggerPreferences.setFloatingButtonEnabled(context, enabled)
        context.sendBroadcast(
            Intent(TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED)
                .setPackage(context.packageName)
        )
        promise.resolve(null)
    }

    /** Whether the lookup tile is already in the reader's Quick Settings, as Gaten recorded it. */
    @ReactMethod
    fun tileAdded(promise: Promise) {
        promise.resolve(TriggerPreferences.tileAdded(reactApplicationContext))
    }

    /** Records the tile's presence; the system's own editor owns removing it. */
    @ReactMethod
    fun setTileAdded(added: Boolean, promise: Promise) {
        TriggerPreferences.setTileAdded(reactApplicationContext, added)
        promise.resolve(null)
    }

    /**
     * Asks the system to add the lookup tile, API 33's own request rather than an intent — the
     * system answers the reader directly and tells us which way it went. "added" covers both the
     * tile going in and the reader asking about one already there; anything the API reports beyond
     * the two answers it defines is a failure the page states as a toast.
     */
    @ReactMethod
    fun requestAddTile(promise: Promise) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            promise.resolve("notAdded")
            return
        }
        try {
            val context = reactApplicationContext
            val statusBar = context.getSystemService(StatusBarManager::class.java)
            statusBar.requestAddTileService(
                ComponentName(context, LookupTileService::class.java),
                context.getString(R.string.tile_label),
                android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_qs_lookup),
                context.mainExecutor
            ) { result ->
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                        promise.resolve("added")

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                        promise.resolve("notAdded")

                    else -> promise.resolve("failed")
                }
            }
        } catch (e: Exception) {
            promise.reject(CODE_ADD_TILE, "Could not ask the system to add the tile", e)
        }
    }

    companion object {
        const val NAME = "GatenTrigger"

        private const val CODE_ADD_TILE = "E_TRIGGER_ADD_TILE"
    }
}
