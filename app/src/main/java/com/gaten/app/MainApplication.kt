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

/**
 * The application class React requires: it owns the [ReactHost] the activity renders through and
 * the list of packages the JS side reaches — the bridge modules in [GatenBridgePackage] plus the
 * core RN packages.
 *
 * It adds no behaviour of its own: everything Gaten did before launching the UI (dictionary state,
 * service connections) lives where it was, attached lazily by whatever asks for it.
 */
class MainApplication : Application(), ReactApplication {

    override val reactHost: ReactHost by lazy {
        getDefaultReactHost(
            applicationContext,
            packageList,
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
        // Manrope under the name JS asks for: `tokens.ts` names the family "Manrope" on every role
        // except the two display steps (which the Kotlin type scale draws in the system face).
        //
        // It is registered rather than named directly because `res/font/manrope.xml` is what carries
        // the weights: its three entries pin the bundled variable font to wght 400/500/600, and
        // React Native resolves a text's weight against a registered family's own entries —
        // `ReactFontManager` hands the requested weight to `Typeface.create`, which a bare .ttf is
        // not asked about. The name is resolved through this registration alone; it is not a system
        // family. Registered before the first surface exists, so the first frame a screen draws
        // already has the face, and a missing family costs the face, never a crash.
        ReactFontManager.getInstance().addCustomFont(this, "Manrope", R.font.manrope)
        loadReactNative(this)
    }
}
