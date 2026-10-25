/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app

import android.content.Intent
import android.os.Bundle
import com.facebook.react.ReactActivity
import com.gaten.app.rn.GatenBridgeEmitter
import com.gaten.app.rn.ReactHostLifecycle
import com.swmansion.rnscreens.fragment.restoration.RNScreensFragmentFactory

/**
 * The app's only activity: it hosts the React Native UI (component name [MAIN_COMPONENT_NAME],
 * registered from the repo-root `index.js`) and hands the rest to it.
 *
 * The only thing it tells the service is when the user is done with the app: a task that is really
 * going away (not a rotation) takes any lookup and pending capture down with it, which is also what
 * covers the app being taken off recents when the platform does not deliver `onTaskRemoved` to a
 * service it itself binds. Later triggers are unaffected — the service stays connected.
 */
class MainActivity : ReactActivity() {

    /**
     * The settings page this launch asked for, from [EXTRA_SETTINGS_PAGE], until a screen has taken
     * it. Held here rather than inside the rendered content because it can arrive after the app is
     * up: an outside launch carries `FLAG_ACTIVITY_NEW_TASK`, which delivers the intent to this
     * running instance instead of creating another one.
     */
    var settingsPageRequest: String? = null
        private set

    override fun getMainComponentName(): String = MAIN_COMPONENT_NAME

    override fun onCreate(savedInstanceState: Bundle?) {
        // Why this comes before super.onCreate: a restart — an Activity recreation, "Don't keep
        // activities", or the system restoring a task it killed — hands the FragmentManager a saved
        // fragment list, and the fragments react-native-screens draws its screens with refuse to be
        // rebuilt that way ("Screen fragments should never be restored"). The library's own factory
        // answers such a restore with a fragment that detaches itself, so the framework gets a valid
        // instance and React mounts fresh screens over it. Required by the library: see
        // react-native-screens README → Installation → Android.
        supportFragmentManager.fragmentFactory = RNScreensFragmentFactory()
        super.onCreate(savedInstanceState)

        // A rotation replays onCreate with the same intent, and whatever it asked for has already
        // been dealt with once: only a fresh start may act on it.
        if (savedInstanceState == null) {
            settingsPageRequest = intent.getStringExtra(EXTRA_SETTINGS_PAGE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        settingsPageRequest = intent.getStringExtra(EXTRA_SETTINGS_PAGE)
        GatenBridgeEmitter.emitSettingsPageRequest(settingsPageRequest)
    }

    /** Registers this Activity as an owner of the shared host's lifecycle. */
    override fun onResume() {
        super.onResume()
        ReactHostLifecycle.onActivityResumed(this)
    }

    /**
     * Runs after `super`, so React's delegate has paused the host and this immediately corrects it
     * when a lookup sheet is still open.
     */
    override fun onPause() {
        super.onPause()
        ReactHostLifecycle.onActivityPaused(this)
    }

    /** Clears the pending request once a screen has navigated for it (see GatenShellModule). */
    fun consumeSettingsPageRequest() {
        settingsPageRequest = null
    }

    /** Closes lookups when the task finishes and releases this Activity's React host ownership. */
    override fun onDestroy() {
        // Only when the app is really finished: a configuration change destroys this activity too,
        // and a lookup must survive that.
        if (isFinishing && !isChangingConfigurations) {
            GatenAccessibilityService.dismissForAppExit()
        }
        super.onDestroy()
        ReactHostLifecycle.onActivityPaused(this)
    }

    companion object {
        /**
         * The name the JS side registers with `AppRegistry.registerComponent` in `index.js`.
         */
        const val MAIN_COMPONENT_NAME = "Gaten"

        /**
         * The extra an outside launcher sends to open one settings page: the settings route key, so
         * "wanikani", "anki" or "lookup". Anything else opens the Settings root.
         */
        const val EXTRA_SETTINGS_PAGE = "settings_page"
    }
}
