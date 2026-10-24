/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

/**
 * The React Native bridge: every native module the RN screens talk to, under one package the app's
 * React host registers. Each module wraps an existing Kotlin object or class — the dictionary, the
 * AnkiDroid providers, WaniKani, the theme, the feedback cues and the small app facts — and adds no
 * logic of its own beyond turning the wrapped results into promises.
 */
class GatenBridgePackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(
            GatenDictModule(reactContext),
            GatenAnkiModule(reactContext),
            GatenWaniKaniModule(reactContext),
            GatenThemeModule(reactContext),
            GatenHapticsModule(reactContext),
            GatenAppInfoModule(reactContext),
            GatenShellModule(reactContext),
            GatenTriggerModule(reactContext),
            GatenPlatformModule(reactContext),
            GatenRecognitionModule(reactContext),
            GatenLookupSheetModule(reactContext),
        )

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(GatenCardWebViewManager(), GatenThemeTransitionManager())
}
