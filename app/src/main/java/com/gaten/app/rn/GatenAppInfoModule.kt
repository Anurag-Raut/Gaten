/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.capture.GatenCaptureService
import com.gaten.app.ocr.MangaOcrModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * The small facts about the app the RN screens put on their settings pages: the version, the
 * dictionary's schema version, whether the optional manga-ocr model is on the phone, and whether
 * lookup mode is on.
 *
 * Everything is a read of something that already exists: [MangaOcrModel]'s directory and
 * [GatenCaptureService]'s own session. The dictionary's schema
 * version is read from the SQLite header the way `app/build.gradle.kts` verifies the packaged
 * asset — `PRAGMA user_version` at bytes 60-63 — read from the extracted copy when there is one
 * and from the asset otherwise, so the number the build checked is the number JS sees.
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
     * What is on the phone of the optional manga-ocr model: `{ installed, catalogId, sizeBytes }` —
     * the same three reads the recognition page's view model makes, answered fresh every ask,
     * because the directory can change while no screen is looking.
     */
    @ReactMethod
    fun getMangaOcrState(promise: Promise) {
        promise.resolve(mangaOcrState(reactApplicationContext))
    }

    /**
     * Whether lookup mode is on right now — a live media projection, so this is the service's own
     * state rather than a setting: it is off until the reader consents to a capture, and off again
     * the moment the projection ends, whichever way it ends.
     */
    @ReactMethod
    fun isLookupModeActive(promise: Promise) {
        promise.resolve(GatenCaptureService.isLookupModeActive())
    }

    override fun invalidate() {
        scope.cancel()
    }

    /** The three reads of the installed model, in one map. */
    private fun mangaOcrState(context: Context): WritableMap =
        Arguments.createMap().apply {
            putBoolean("installed", MangaOcrModel.isInstalled(context))
            val catalogId = MangaOcrModel.installedCatalogId(context)
            if (catalogId != null) putString("catalogId", catalogId) else putNull("catalogId")
            putDouble("sizeBytes", MangaOcrModel.installedSizeBytes(context).toDouble())
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

        /** The bytes of the SQLite header the schema version sits behind. */
        private const val HEADER_SIZE = 64

        private const val CODE_VERSION = "E_APPINFO_VERSION"
        private const val CODE_SCHEMA = "E_APPINFO_SCHEMA"
    }
}
