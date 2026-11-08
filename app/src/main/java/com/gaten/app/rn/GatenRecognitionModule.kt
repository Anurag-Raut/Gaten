/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.content.Context
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.ocr.MangaOcrModel
import com.gaten.app.ocr.PpOcrModel
import com.gaten.app.ocr.RecognitionMode
import com.gaten.app.ocr.RecognitionPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The text recognition settings bridge: the selected mode, install state for every engine (the
 * bundled ML Kit and the three downloads — Manga OCR and the two PP-OCRv6 models), and the explicit
 * downloads that put one on the device. The bundled ML Kit has no download; the other three ship no
 * weights in the APK.
 *
 * Every call that touches storage runs on [Dispatchers.IO]. The reader confirms each download in the
 * settings UI — which warns that mobile data may incur charges — and the model downloaders
 * ([PpOcrModel.download], [MangaOcrModel.download]) verify their files. Only one download runs at a
 * time: a second request while one is in flight is refused here rather than racing two writes over
 * the same files. The scope is cancelled with the module, so an in-flight download is cancelled
 * rather than finishing into a dead React context.
 *
 * While a download runs the module emits [GatenBridgeEmitter.RECOGNITION_DOWNLOAD_PROGRESS], at most
 * about five times a second, carrying the engine, the phase (`downloading` or `verifying`) and the
 * cumulative byte counts; the events stop when the download's promise settles. [cancelDownload]
 * aborts the download in flight, and its promise rejects with `DOWNLOAD_CANCELLED`; a failed one
 * rejects with `DOWNLOAD_OFFLINE`, `DOWNLOAD_NO_SPACE`, `DOWNLOAD_VERIFY_FAILED`, or the generic
 * `RECOGNITION_DOWNLOAD_FAILED` ([DownloadFailure]).
 */
class GatenRecognitionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The download running now, and the lock that guards it. The React bridge calls
     * [downloadRecognitionEngine] and [cancelDownload] on one thread, but the download itself
     * finishes on [Dispatchers.IO] and clears these, so both sides meet here.
     */
    private val downloadLock = Any()
    private var activeDownload: Job? = null

    /** Engine ids with a download running; guarded by its own concurrent set. */
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    init {
        GatenBridgeEmitter.attach(reactContext)
    }

    override fun getName(): String = NAME

    /** Legacy query: text recognition is always required and enabled. */
    @ReactMethod
    fun isMangaOcrEnabled(promise: Promise) {
        promise.resolve(RecognitionPreference.isMangaOcrEnabled(reactApplicationContext))
    }

    /** Legacy setter: the old "off" is still refused, the old "on" changes nothing. */
    @ReactMethod
    fun setMangaOcrEnabled(enabled: Boolean, promise: Promise) {
        if (!enabled) {
            promise.reject("MANGA_OCR_REQUIRED", "Text recognition is required for lookups.")
            return
        }
        promise.resolve(null)
    }

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

    /**
     * Downloads one engine (Manga OCR or a PP-OCRv6 model), reporting progress as it goes, then
     * answers with the refreshed state. Refused while another download is in flight, and before it
     * starts when the device has too little free storage.
     */
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
        synchronized(downloadLock) {
            if (activeDownload != null) {
                promise.reject(CODE_DOWNLOAD_IN_PROGRESS, "A model is already downloading.")
                return
            }
            // The model is written beside its files under noBackupFilesDir; refuse before a byte is
            // written if the free space cannot hold it. A zero answer (unknown) is not a refusal.
            val needed = engineBytes(engine)
            val free = context.noBackupFilesDir.usableSpace
            if (free in 1 until needed) {
                promise.reject(
                    DownloadFailure.NO_SPACE,
                    "Not enough free storage to download ${engineName(engine)}."
                )
                return
            }
            downloading.add(engine.id)
            activeDownload = scope.launch { runDownload(context, engine, promise) }
        }
    }

    /**
     * Aborts the download running now, if any: its pending promise rejects with
     * `DOWNLOAD_CANCELLED`, and the downloader removes the partial file. With nothing in flight it
     * answers quietly.
     */
    @ReactMethod
    fun cancelDownload(promise: Promise) {
        val job = synchronized(downloadLock) { activeDownload }
        if (job == null) {
            promise.resolve(null)
            return
        }
        job.cancel()
        promise.resolve(null)
    }

    /**
     * Required by React Native's `NativeEventEmitter` on Android; the listener count is managed by
     * the device emitter, so nothing is retained here. The event stream itself is emitted through
     * [GatenBridgeEmitter].
     */
    @ReactMethod
    fun addListener(eventName: String) {}

    /** See [addListener]. */
    @ReactMethod
    fun removeListeners(count: Double) {}

    /** Runs one download to its end and settles its promise, reporting progress as it goes. */
    private suspend fun runDownload(context: Context, engine: RecognitionMode, promise: Promise) {
        val throttle = DownloadProgressThrottle(
            intervalMs = PROGRESS_INTERVAL_MS,
            nowMs = { SystemClock.uptimeMillis() },
            emit = { phase, bytesDone, bytesTotal ->
                emitProgress(engine.id, phase, bytesDone, bytesTotal)
            }
        )
        try {
            download(context, engine) { bytesDone, bytesTotal -> throttle.onBytes(bytesDone, bytesTotal) }
            // Publish the finished state: the id must be out of [downloading] before the refreshed
            // settings are read, or they would still report the download running.
            downloading.remove(engine.id)
            promise.resolve(settings(context))
        } catch (e: CancellationException) {
            promise.reject(CODE_DOWNLOAD_CANCELLED, "The model download was cancelled.")
            throw e
        } catch (e: Exception) {
            promise.reject(DownloadFailure.code(e), e.message ?: "The model could not be downloaded.", e)
        } finally {
            throttle.stop()
            synchronized(downloadLock) {
                downloading.remove(engine.id)
                activeDownload = null
            }
        }
    }

    /** One progress event: the engine, the phase, and the cumulative byte counts. */
    private fun emitProgress(engineId: String, phase: String, bytesDone: Long, bytesTotal: Long) {
        val payload = Arguments.createMap()
        payload.putString("engine", engineId)
        payload.putString("phase", phase)
        payload.putDouble("bytesDone", bytesDone.toDouble())
        payload.putDouble("bytesTotal", bytesTotal.toDouble())
        GatenBridgeEmitter.emitRecognitionDownloadProgress(payload)
    }

    override fun invalidate() {
        scope.cancel()
        super.invalidate()
    }

    private fun settings(context: Context): WritableMap {
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
        // In-flight downloads are module state, not screen state: a settings screen reopened while
        // a download runs still shows it running instead of an enabled Download button.
        val inFlight = Arguments.createArray()
        for (id in downloading) inFlight.pushString(id)
        map.putArray("downloading", inFlight)
        return map
    }

    /** Display name: the bundled recognizer's, the PP catalog's own, or the Manga OCR name. */
    private fun engineName(engine: RecognitionMode): String = when (engine) {
        RecognitionMode.ML_KIT -> ML_KIT_NAME
        else -> engine.ppEngine()?.displayName ?: MANGA_OCR_NAME
    }

    /** Expected download size, from whichever model owns the engine; nothing for the bundled one. */
    private fun engineBytes(engine: RecognitionMode): Long = when {
        engine.bundled -> 0L
        else -> engine.ppEngine()?.approximateDownloadBytes ?: MangaOcrModel.approximateDownloadBytes
    }

    /** Installed means the engine's own files are complete; the bundled recognizer always is. */
    private fun isInstalled(context: Context, engine: RecognitionMode): Boolean {
        if (engine.bundled) return true
        val pp = engine.ppEngine()
        return if (pp == null) MangaOcrModel.isInstalled(context)
        else PpOcrModel.isInstalled(context, pp)
    }

    /** Runs the downloader that owns [engine]; each verifies its own files and reports progress. */
    private suspend fun download(
        context: Context,
        engine: RecognitionMode,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit
    ) {
        val pp = engine.ppEngine()
        if (pp == null) MangaOcrModel.download(context, onProgress)
        else PpOcrModel.download(context, pp, onProgress)
    }

    companion object {
        const val NAME = "GatenRecognition"

        private const val MANGA_OCR_NAME = "Manga OCR"

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

        /** At most one progress event per this many milliseconds; the contract asks for about five a second. */
        private const val PROGRESS_INTERVAL_MS = 200L
    }
}
