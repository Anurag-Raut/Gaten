/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.media.hasNotificationAccess

/**
 * The platform bits the settings pages reach for directly: the system screens the two permissions
 * live behind, the browser, the clipboard, toasts, and the notification-access read the lookup
 * page reports on.
 *
 * Every opener answers `false` when nothing on the device can take the intent — the screens show a
 * toast instead of pretending the move landed. The activity-bound ones hop to the main thread,
 * because the current activity is only safe to touch from there.
 */
class GatenPlatformModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Opens the system's Accessibility screen — only ever after the disclosure was agreed to. */
    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        startIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), promise)
    }

    /** Opens the system's Notification Access screen, where the media-pause permission lives. */
    @ReactMethod
    fun openNotificationListenerSettings(promise: Promise) {
        startIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), promise)
    }

    /** Opens a URL in the browser; silent no-op when no browser can take it (the app is offline). */
    @ReactMethod
    fun openUrl(url: String, promise: Promise) {
        startIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), promise)
    }

    /** True when Gaten is an enabled notification listener — `hasNotificationAccess`, re-read. */
    @ReactMethod
    fun isNotificationAccessEnabled(promise: Promise) {
        promise.resolve(hasNotificationAccess(reactApplicationContext))
    }

    /** Puts text on the clipboard, the way the Key Mapper instructions offer it. */
    @ReactMethod
    fun copyToClipboard(text: String, promise: Promise) {
        try {
            val context = reactApplicationContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Gaten", text))
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject(CODE_CLIPBOARD, "Could not copy to the clipboard", e)
        }
    }

    /** Shows a toast, in the same short way the Compose screens did. */
    @ReactMethod
    fun toast(message: String, promise: Promise) {
        mainHandler.post {
            Toast.makeText(reactApplicationContext, message, Toast.LENGTH_LONG).show()
        }
        promise.resolve(true)
    }

    /** Runs one activity-bound intent from the current activity; `false` when nothing can take it. */
    private fun startIntent(intent: Intent, promise: Promise) {
        mainHandler.post {
            try {
                val activity = reactApplicationContext.currentActivity
                if (activity == null) {
                    promise.resolve(false)
                } else {
                    activity.startActivity(intent)
                    promise.resolve(true)
                }
            } catch (e: ActivityNotFoundException) {
                promise.resolve(false)
            } catch (e: Exception) {
                promise.reject(CODE_START, "Could not start ${intent.action}", e)
            }
        }
    }

    companion object {
        const val NAME = "GatenPlatform"

        private const val CODE_START = "E_PLATFORM_START"
        private const val CODE_CLIPBOARD = "E_PLATFORM_CLIPBOARD"
    }
}