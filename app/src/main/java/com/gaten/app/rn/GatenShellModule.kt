/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.os.Build
import android.view.WindowInsets
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.uimanager.PixelUtil
import com.gaten.app.MainActivity

/**
 * The shell's own module: the settings page an outside launch asked for, held by [MainActivity]
 * since it can arrive after the app is up, and the window's system-gesture insets.
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

    /**
     * The window's system-gesture insets, in dp — the strips along each edge the platform reserves
     * for its own gestures. Android 15's edge-to-edge gesture navigation reports a zero
     * navigation-bar inset, and `react-native-safe-area-context` reads only the navigation bar, so
     * the bottom part of this is what keeps the floating bar and every bottom-padded screen clear
     * of the back/home gesture handle. The union of the normal and mandatory gesture sources is
     * used because a device may classify its bottom strip as either one.
     *
     * Resolves `null` before the window has laid out, or when no activity is attached: JS keeps the
     * safe-area value it already has and re-reads on the next resume.
     */
    @ReactMethod
    fun getSystemGestureInsets(promise: Promise) {
        val windowInsets = getCurrentActivity()?.window?.decorView?.rootWindowInsets
        if (windowInsets == null) {
            promise.resolve(null)
            return
        }
        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowInsets.getInsets(
                WindowInsets.Type.systemGestures() or
                    WindowInsets.Type.mandatorySystemGestures()
            )
        } else {
            @Suppress("DEPRECATION")
            windowInsets.systemGestureInsets
        }
        val map = Arguments.createMap().apply {
            putDouble("top", PixelUtil.toDIPFromPixel(insets.top.toFloat()).toDouble())
            putDouble("right", PixelUtil.toDIPFromPixel(insets.right.toFloat()).toDouble())
            putDouble("bottom", PixelUtil.toDIPFromPixel(insets.bottom.toFloat()).toDouble())
            putDouble("left", PixelUtil.toDIPFromPixel(insets.left.toFloat()).toDouble())
        }
        promise.resolve(map)
    }

    companion object {
        const val NAME = "GatenShell"
    }
}
