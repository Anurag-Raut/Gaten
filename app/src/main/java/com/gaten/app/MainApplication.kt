/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.app.Application
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.PackageList
import com.facebook.react.ReactPackage
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.common.assets.ReactFontManager
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.runtime.ReactHostImpl
import com.gaten.app.rn.GatenBridgePackage
import com.gaten.app.ui.theme.LauncherIcon

/**
 * The application class React requires: it owns the [ReactHost] the activity renders through and
 * the list of packages the JS side reaches — the bridge modules in [GatenBridgePackage] plus the
 * core RN packages.
 *
 * It also starts the app's own theme-dependent state: the fonts the JS type scale asks for, and the
 * launcher tile ([LauncherIcon]), a second copy of the stored light/dark choice. Everything Gaten
 * did before launching the UI (dictionary state, service connections) lives where it was, attached
 * lazily by whatever asks for it.
 */
class MainApplication : Application(), ReactApplication {

    override val reactHost: ReactHost by lazy {
        getDefaultReactHost(
            applicationContext,
            packageList,
            useDevSupport = BuildConfig.DEBUG,
        )
    }

    /**
     * The bridge modules the RN screens call, plus every package autolinking found — the
     * generated `PackageList` carries the core RN package and each npm module with native code
     * (gesture handler, screens, safe-area, svg), without which their TurboModules are missing at
     * runtime. Order does not matter.
     */
    val packageList: List<ReactPackage>
        get() = PackageList(this).packages + GatenBridgePackage()

    override fun onCreate() {
        super.onCreate()
        // IBM Plex Sans JP under the name JS asks for: `tokens.ts` names the family on every role in
        // the type scale, so every string in the app — Latin or Japanese — is drawn in it.
        //
        // It is registered rather than named directly because `res/font/ibm_plex_sans_jp.xml` is what
        // carries the weights: its four entries name the bundled 400/500/600/700 faces, and React
        // Native resolves a text's weight against a registered family's own entries —
        // `ReactFontManager` hands the requested weight to `Typeface.create`, which a bare .ttf is
        // not asked about. The name is resolved through this registration alone; it is not a system
        // family. Registered before the first surface exists, so the first frame a screen draws
        // already has the face, and a missing family costs the face, never a crash.
        //
        // The JP faces carry IBM Plex's Latin design as well as the Japanese glyphs, so a mixed
        // string stays in one face. A glyph the face does not carry still falls back to the
        // platform's own, as any font does.
        ReactFontManager.getInstance().addCustomFont(this, "IBM Plex Sans JP", R.font.ibm_plex_sans_jp)
        // The launcher tile is the stored theme choice as the home screen sees it: the manifest's
        // alias entries, and LauncherIcon puts the app on the right one.
        LauncherIcon.install(this)
        loadReactNative(this)
    }
}
