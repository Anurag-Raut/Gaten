/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import android.content.Context
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.ocr.PpOcrModel
import com.gaten.app.ocr.PpOcrPack
import com.gaten.app.ocr.RecognitionMode
import com.gaten.app.ocr.RecognitionPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The text recognition settings bridge: the selected mode, install state for every engine (the
 * bundled ML Kit and the optional PP-OCRv6 Medium download), and the explicit download that puts the
 * optional engine on the device. The bundled ML Kit has no download; PP-OCRv6 Medium ships no
 * weights in the APK — Play's fast-follow asset pack on a Play install, the in-app downloader
 * otherwise.
 *
 * Every call that touches storage runs on [Dispatchers.IO]. The reader confirms the download in the
 * settings UI — which warns that mobile data may incur charges — and the delivery path verifies
 * what it installs ([PpOcrModel.download]). An engine already downloading rejects a second request
 * here instead of racing two writes over the same files. The scope is cancelled with the module, so
 * an in-flight download is cancelled rather than finishing into a dead React context.
 */
class GatenRecognitionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Engine ids with a download running; guarded by its own concurrent set. */
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    init {
        GatenBridgeEmitter.attach(reactContext)
        // A fast-follow pack can have been downloaded by Play before this module (or this process)
        // existed, so the live state is re-read here rather than assumed.
        PpOcrPack.refresh(reactContext)
    }

    override fun getName(): String = NAME

    /** The selected mode and whether each optional engine is installed. */
    @ReactMethod
    fun getRecognitionSettings(promise: Promise) {
        scope.launch {
            try {
                promise.resolve(settings(reactApplicationContext))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_STATUS_FAILED, e.message ?: "Could not read text recognition settings.", e)
            }
        }
    }

    /** Stores the reader's manual choice. An uninstalled PP mode is allowed and falls back at read time. */
    @ReactMethod
    fun setRecognitionMode(modeId: String, promise: Promise) {
        val mode = RecognitionMode.entries.firstOrNull { it.id == modeId }
        if (mode == null) {
            promise.reject(CODE_INVALID_MODE, "Unknown text recognition mode: $modeId")
            return
        }
        scope.launch {
            try {
                RecognitionPreference.setMode(reactApplicationContext, mode)
                promise.resolve(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_SAVE_FAILED, e.message ?: "Could not save the text recognition mode.", e)
            }
        }
    }

    /** Downloads the optional engine, then answers with the refreshed state. */
    @ReactMethod
    fun downloadRecognitionEngine(engineId: String, promise: Promise) {
        val engine = ENGINES.firstOrNull { it.id == engineId }
        if (engine == null) {
            promise.reject(CODE_INVALID_ENGINE, "Unknown recognition engine: $engineId")
            return
        }
        if (engine.bundled) {
            promise.reject(
                CODE_ALREADY_BUNDLED,
                "${engineName(engine)} is bundled with Gaten; there is nothing to download."
            )
            return
        }
        val context = reactApplicationContext
        // The reader has already confirmed this tap in the settings UI, which warns about mobile
        // data; the model downloader owns the transfer and its file verification.
        if (!downloading.add(engine.id)) {
            promise.reject(CODE_DOWNLOAD_IN_PROGRESS, "That model is already downloading.")
            return
        }
        scope.launch {
            try {
                // [currentActivity] only matters when Play asks the user to accept the pack download.
                download(context, engine, reactApplicationContext.currentActivity)
                // Publish the finished state: the id must be out of [downloading] before the
                // refreshed settings are read, or they would still report the download running.
                downloading.remove(engine.id)
                promise.resolve(settings(context))
            } catch (e: CancellationException) {
                promise.reject(CODE_DOWNLOAD_CANCELLED, "The model download was cancelled.")
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_DOWNLOAD_FAILED, e.message ?: "The model could not be downloaded.", e)
            } finally {
                downloading.remove(engine.id)
            }
        }
    }

    override fun invalidate() {
        scope.cancel()
        super.invalidate()
    }

    private suspend fun settings(context: Context): WritableMap {
        // Awaited, not fired and forgotten: this read is what the settings screen shows, and a
        // refresh that resolved after it would report a live fast-follow transfer as not
        // downloading — a claim the JS side never re-checks on its own.
        PpOcrPack.refreshNow(context)
        val map = Arguments.createMap()
        map.putString("mode", RecognitionPreference.mode(context).id)
        val engines = Arguments.createArray()
        for (engine in ENGINES) {
            val item = Arguments.createMap()
            item.putString("id", engine.id)
            item.putString("name", engineName(engine))
            item.putDouble("downloadBytes", engineBytes(engine).toDouble())
            item.putBoolean("installed", isInstalled(context, engine))
            item.putBoolean("bundled", engine.bundled)
            item.putBoolean("recommended", engine.recommended)
            engines.pushMap(item)
        }
        map.putArray("engines", engines)
        // In-flight work comes from two places: a request this module is running, and Play's own
        // fast-follow transfer, which can have started before this module existed and outlives the
        // process. Both are reported, so a settings screen opened mid-transfer still shows the
        // engine downloading instead of offering a second Download.
        val inFlight = LinkedHashSet<String>()
        inFlight.addAll(downloading)
        if (PpOcrPack.isTransferring) {
            ENGINES.firstOrNull { it.ppEngine() != null }?.let { inFlight.add(it.id) }
        }
        val downloadingArray = Arguments.createArray()
        for (id in inFlight) downloadingArray.pushString(id)
        map.putArray("downloading", downloadingArray)
        return map
    }

    /** Display name: the PP catalog's own for the optional engine, the bundled name otherwise. */
    private fun engineName(engine: RecognitionMode): String =
        engine.ppEngine()?.displayName ?: ML_KIT_NAME

    /** Expected install size from the catalog; nothing for the bundled recognizer. */
    private fun engineBytes(engine: RecognitionMode): Long =
        engine.ppEngine()?.approximateDownloadBytes ?: 0L

    /** Installed means the engine's own files are complete; the bundled recognizer always is. */
    private fun isInstalled(context: Context, engine: RecognitionMode): Boolean {
        if (engine.bundled) return true
        val pp = engine.ppEngine() ?: return false
        return PpOcrModel.isInstalled(context, pp)
    }

    /** Runs the delivery path that owns [engine]'s model; it verifies what it installs itself. */
    private suspend fun download(context: Context, engine: RecognitionMode, activity: Activity?) {
        val pp = engine.ppEngine() ?: return
        PpOcrModel.download(context, pp, activity)
    }

    companion object {
        const val NAME = "GatenRecognition"

        private const val ML_KIT_NAME = "ML Kit"

        /** Every engine, in the order the settings screen shows them (the bundled ML Kit first). */
        private val ENGINES = RecognitionMode.entries

        private const val CODE_STATUS_FAILED = "RECOGNITION_STATUS_FAILED"
        private const val CODE_INVALID_MODE = "INVALID_RECOGNITION_MODE"
        private const val CODE_SAVE_FAILED = "RECOGNITION_MODE_SAVE_FAILED"
        private const val CODE_INVALID_ENGINE = "INVALID_RECOGNITION_ENGINE"
        private const val CODE_ALREADY_BUNDLED = "ENGINE_ALREADY_BUNDLED"
        private const val CODE_DOWNLOAD_IN_PROGRESS = "DOWNLOAD_IN_PROGRESS"
        private const val CODE_DOWNLOAD_CANCELLED = "DOWNLOAD_CANCELLED"
        private const val CODE_DOWNLOAD_FAILED = "RECOGNITION_DOWNLOAD_FAILED"
    }
}
