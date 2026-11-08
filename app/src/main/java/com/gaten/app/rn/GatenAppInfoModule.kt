/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.gaten.app.GatenAccessibilityService
import com.gaten.app.support.SupportPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The small facts about the app the RN screens put on their settings pages: the version, the
 * dictionary's schema version, whether lookup mode is on, the support popup's own state, and how
 * far the first-run setup flow has got.
 *
 * Everything but the onboarding step is a read of something that already exists: the platform's own
 * enabled-accessibility-services list, which is what lookup mode's "On" and "Off"
 * report on, and the support popup's counters in the app's settings file. The dictionary's schema
 * version is read from the SQLite header the way `app/build.gradle.kts` verifies the packaged
 * asset — `PRAGMA user_version` at bytes 60-63 — read from the extracted copy when there is one
 * and from the asset otherwise, so the number the build checked is the number JS sees.
 *
 * The onboarding step is the one thing this module writes: a single integer in the shared
 * `gaten_settings` file (0, 1 or 2 for the page being shown, 3 once the flow is finished), so the
 * setup flow runs on the first launch and never again.
 */
class GatenAppInfoModule(
    reactContext: com.facebook.react.bridge.ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    private val scope = bridgeScope()

    /** The app's own version name, as the package manager reports it. */
    @ReactMethod
    fun getAppVersion(promise: Promise) {
        try {
            val info = reactApplicationContext.packageManager.getPackageInfo(
                reactApplicationContext.packageName,
                0
            )
            promise.resolve(info.versionName.orEmpty())
        } catch (e: Exception) {
            promise.reject(CODE_VERSION, "Could not read the app version", e)
        }
    }

    /**
     * The dictionary schema the bundled database was built for (`PRAGMA user_version`), or a
     * rejection when neither the extracted copy nor the asset can be read — a build that verified
     * the asset would not reach this code, so a failure here is worth naming rather than hiding.
     */
    @ReactMethod
    fun getDictionarySchemaVersion(promise: Promise) {
        scope.launch {
            try {
                promise.resolve(schemaVersion(reactApplicationContext))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_SCHEMA, "Could not read the dictionary schema version", e)
            }
        }
    }

    /**
     * Whether lookup mode is on: Gaten's accessibility service is enabled in the system's own
     * settings. That grant is given outside the app, so this is re-read on every ask rather than
     * cached.
     */
    @ReactMethod
    fun isLookupModeActive(promise: Promise) {
        promise.resolve(isAccessibilityServiceEnabled(reactApplicationContext))
    }

    /**
     * The support popup's stored state: how many times the app has been opened, whether the reader
     * silenced the popup for good, and the earliest moment it may be offered again.
     */
    @ReactMethod
    fun getSupportPromptState(promise: Promise) {
        val context = reactApplicationContext
        promise.resolve(
            Arguments.createMap().apply {
                putInt("openCount", SupportPreferences.openCount(context))
                putBoolean("never", SupportPreferences.promptNever(context))
                putDouble("nextEligibleAt", SupportPreferences.promptNextEligibleAt(context).toDouble())
            }
        )
    }

    /** Counts one app open and resolves the running total, so JS can gate the first offer on it. */
    @ReactMethod
    fun recordSupportAppOpen(promise: Promise) {
        promise.resolve(SupportPreferences.recordAppOpen(reactApplicationContext))
    }

    /**
     * Records a dismissal: `permanent` is "Don't show again" and silences the popup for good,
     * otherwise it is "Not now" and postpones the next offer.
     */
    @ReactMethod
    fun dismissSupportPrompt(permanent: Boolean, promise: Promise) {
        SupportPreferences.dismissPrompt(reactApplicationContext, permanent)
        promise.resolve(null)
    }

    /**
     * How far the first-run setup flow has got: 0, 1 or 2 is the page being shown and 3 means it is
     * finished. A device that has never stored it answers 0, so the flow runs on the first launch.
     */
    @ReactMethod
    fun getOnboardingStep(promise: Promise) {
        promise.resolve(onboardingStep(reactApplicationContext))
    }

    /**
     * Stores the onboarding step — the page being shown, or 3 once the flow is finished. The write
     * is applied before the promise resolves, so a caller that advances only after this resolves
     * never leaves the stored step behind the page on screen.
     */
    @ReactMethod
    fun setOnboardingStep(step: Int, promise: Promise) {
        prefs(reactApplicationContext).edit().putInt(KEY_ONBOARDING_STEP, step).apply()
        promise.resolve(null)
    }

    override fun invalidate() {
        scope.cancel()
    }

    /** The stored onboarding step; 0 when the flow has never stored one. */
    private fun onboardingStep(context: Context): Int =
        prefs(context).getInt(KEY_ONBOARDING_STEP, 0)

    /** The app's shared settings file, the one every other pref is kept in. */
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True when [GatenAccessibilityService] is one of the platform's enabled accessibility services
     * — the only honest answer to "can Gaten read the screen" that does not depend on the service
     * being alive right now.
     */
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, GatenAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val services = TextUtils.SimpleStringSplitter(':')
        services.setString(enabled)
        while (services.hasNext()) {
            if (ComponentName.unflattenFromString(services.next()) == expected) return true
        }
        return false
    }

    /**
     * [PRAGMA user_version] of the dictionary database, read from the 64-byte SQLite header — the
     * same bytes the build's verification reads, from the extracted copy when one exists and from
     * the packaged asset otherwise.
     */
    private fun schemaVersion(context: Context): Long {
        val header = ByteArray(HEADER_SIZE)
        val read = try {
            val extracted = File(context.filesDir, DB_FILE_NAME)
            if (extracted.isFile) {
                extracted.inputStream().use { it.read(header) }
            } else {
                context.assets.open(ASSET_PATH).use { it.read(header) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Could not read the dictionary database header", e)
            -1
        }
        if (read < HEADER_SIZE) throw IOException("The dictionary header could not be read")
        return ((header[60].toLong() and 0xFF) shl 24) or ((header[61].toLong() and 0xFF) shl 16) or
            ((header[62].toLong() and 0xFF) shl 8) or (header[63].toLong() and 0xFF)
    }

    companion object {
        const val NAME = "GatenAppInfo"

        private const val TAG = "GatenBridge"
        private const val ASSET_PATH = "db/jmdict.sqlite"
        private const val DB_FILE_NAME = "jmdict.sqlite"

        /** The app's shared settings file, and the one key this module stores. */
        private const val PREFS_NAME = "gaten_settings"
        private const val KEY_ONBOARDING_STEP = "onboarding_step"

        /** The bytes of the SQLite header the schema version sits behind. */
        private const val HEADER_SIZE = 64

        private const val CODE_VERSION = "E_APPINFO_VERSION"
        private const val CODE_SCHEMA = "E_APPINFO_SCHEMA"
    }
}
