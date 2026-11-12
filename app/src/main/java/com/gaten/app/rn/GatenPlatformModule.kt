/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.GatenAccessibilityService
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

    /**
     * Opens the system's Accessibility screen — only ever after the disclosure was agreed to — and
     * arms the one-shot return to Gaten for when the service connects. On API 29+ this is Gaten's
     * own service details page; older releases get the list with Gaten's row highlighted. If the
     * details action does not resolve or its Settings build guards it with a system permission, the
     * same highlighted list is the fallback.
     */
    @ReactMethod
    fun openAccessibilitySettings(promise: Promise) {
        val context = reactApplicationContext
        // Armed here as well as through [markAwaitingAccessibilityReturn]: the hand-off is only
        // smooth if every caller that opens this screen gets the return, even one that forgot the
        // explicit mark.
        AccessibilityReturn.mark(context)
        val component = ComponentName(context, GatenAccessibilityService::class.java)
            .flattenToString()
        val plan = AccessibilitySettingsTarget.plan(Build.VERSION.SDK_INT, component)
        // The list screen's highlight target: Settings reads the flattened name from the key extra
        // and repeats it inside the fragment-arguments bundle.
        val highlightedList = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(AccessibilitySettingsTarget.FRAGMENT_ARGS_KEY, component)
            putExtra(
                AccessibilitySettingsTarget.SHOW_FRAGMENT_ARGS,
                Bundle().apply {
                    putString(AccessibilitySettingsTarget.FRAGMENT_ARGS_KEY, component)
                }
            )
        }
        val intent = if (plan.highlightComponent) {
            highlightedList
        } else {
            Intent(plan.action).apply {
                // The details page reads this as a String and unflattens it itself.
                putExtra(Intent.EXTRA_COMPONENT_NAME, plan.componentName)
            }
        }
        // If the targeted action does not resolve (or a Settings build guards its details page with
        // a system permission), the list with Gaten highlighted is still the closest reachable place
        // — and never a failed hand-off.
        startIntent(intent, promise, fallback = highlightedList)
    }

    /**
     * Arms the next accessibility-service connection to bring Gaten back to the front. JS calls this
     * immediately before opening Settings; [openAccessibilitySettings] also arms it, so a caller
     * that opens the screen directly still gets the same hand-off.
     */
    @ReactMethod
    fun markAwaitingAccessibilityReturn(promise: Promise) {
        try {
            AccessibilityReturn.mark(reactApplicationContext)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject(CODE_ARM_RETURN, "Could not arm the return to Gaten", e)
        }
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

    /**
     * Turns Gaten's own accessibility service off. Only the service can do that, through
     * `disableSelf`, and only from the main thread. Resolves `false` when no service instance is
     * connected: the caller then falls back to the system screen instead of pretending it moved.
     */
    @ReactMethod
    fun disableAccessibilityService(promise: Promise) {
        val service = GatenAccessibilityService.instance
        if (service == null) {
            promise.resolve(false)
            return
        }
        mainHandler.post {
            try {
                service.disableSelf()
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject(CODE_DISABLE_SERVICE, "Could not turn the accessibility service off", e)
            }
        }
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

    /**
     * Runs one activity-bound intent from the current activity; `false` when nothing can take it.
     * [fallback] is tried when the first action has no activity on this device or its activity
     * rejects the caller, and is reported with the same `true`/`false` contract.
     */
    private fun startIntent(intent: Intent, promise: Promise, fallback: Intent? = null) {
        mainHandler.post {
            val activity = reactApplicationContext.currentActivity
            if (activity == null) {
                promise.resolve(false)
                return@post
            }
            try {
                activity.startActivity(intent)
                promise.resolve(true)
            } catch (e: ActivityNotFoundException) {
                startFallback(activity, fallback, promise)
            } catch (e: SecurityException) {
                // Some Settings builds guard the targeted page with a system permission. That is
                // not a reason to leave the reader stuck on the app: the list is the useful answer.
                startFallback(activity, fallback, promise)
            } catch (e: Exception) {
                promise.reject(CODE_START, "Could not start ${intent.action}", e)
            }
        }
    }

    /** Runs [fallback] after the targeted action did not resolve; `false` when there is none. */
    private fun startFallback(activity: Activity, fallback: Intent?, promise: Promise) {
        if (fallback == null) {
            promise.resolve(false)
            return
        }
        try {
            activity.startActivity(fallback)
            promise.resolve(true)
        } catch (e: ActivityNotFoundException) {
            promise.resolve(false)
        } catch (e: Exception) {
            promise.reject(CODE_START, "Could not start ${fallback.action}", e)
        }
    }

    companion object {
        const val NAME = "GatenPlatform"

        private const val CODE_START = "E_PLATFORM_START"
        private const val CODE_CLIPBOARD = "E_PLATFORM_CLIPBOARD"
        private const val CODE_DISABLE_SERVICE = "E_PLATFORM_DISABLE_SERVICE"
        private const val CODE_ARM_RETURN = "E_PLATFORM_ARM_RETURN"
    }
}
