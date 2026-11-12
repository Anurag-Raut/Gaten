/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.gaten.app.ocr.PpOcrModel
import com.gaten.app.ocr.PpOcrPack
import com.gaten.app.ocr.PpOcrPackState
import com.gaten.app.ocr.RecognitionAutoSelect
import com.gaten.app.ocr.RecognitionMode
import com.gaten.app.ocr.RecognitionPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * The text recognition settings bridge: the selected mode, install state for every engine (the
 * bundled ML Kit and the optional PP-OCRv6 Medium), and the explicit download that asks Google Play
 * for the optional engine's asset pack. The bundled ML Kit has no download, and PP-OCRv6 Medium ships
 * no weights in the APK: on a Play install it arrives through Play's `ppocr` fast-follow asset pack,
 * and on an install Play did not make it cannot be installed at all.
 *
 * Every call that touches storage runs on [Dispatchers.IO]. The reader confirms the download in the
 * settings UI — which warns that mobile data may incur charges — and Play verifies the transfer it
 * makes ([PpOcrModel.download]). An engine already downloading rejects a second request here instead
 * of issuing a second fetch. The scope is cancelled with the module, so an in-flight request is
 * abandoned rather than finishing into a dead React context.
 *
 * While Play transfers the pack the module emits [GatenBridgeEmitter.RECOGNITION_DOWNLOAD_PROGRESS]
 * with Play's own byte counts, throttled by [DownloadProgressThrottle], including for a transfer that
 * began before this module (or this process) existed. When that transfer completes it makes the one
 * automatic mode selection [RecognitionAutoSelect] allows, even if no screen is subscribed.
 */
class GatenRecognitionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Engine ids with a download running; guarded by its own concurrent set. */
    private val downloading = ConcurrentHashMap.newKeySet<String>()

    /** One throttle per transfer, reset when the transfer ends; touched only by [observePack]. */
    private var progressThrottle: DownloadProgressThrottle? = null

    init {
        GatenBridgeEmitter.attach(reactContext)
        // Watch Play's pack state for the life of the module. A fast-follow transfer can start, and
        // finish, while no screen is listening; progress and the completion's auto-select are native
        // decisions, not something a mounted screen has to be present for.
        scope.launch { observePack() }
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
                // A transfer about to be requested still counts as "began": the window is what lets
                // the completion select the engine when the reader has left the download screen.
                RecognitionPreference.beginDownloadWindow(context)
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

    override fun invalidate() {
        scope.cancel()
        super.invalidate()
    }

    /**
     * Keeps Play's pack report flowing into JS and into the one-time auto-select. The initial read is
     * awaited so a transfer that began before this process existed is reported from its current byte
     * count, not from zero.
     */
    private suspend fun observePack() {
        val context = reactApplicationContext
        PpOcrPack.refreshNow(context)
        PpOcrPack.state.collect { state -> onPackState(context, state) }
    }

    /** Turns one Play state into progress events, and a completion into the auto-select decision. */
    private fun onPackState(context: Context, state: PpOcrPackState) {
        when {
            state.isActivelyTransferring -> {
                // A fast-follow transfer can predate the module; opening here is what makes its
                // completion eligible for auto-select without any screen having requested it.
                RecognitionPreference.beginDownloadWindow(context)
                reportProgress(state)
            }
            state.isCompleted -> {
                reportComplete(state)
                applyAutoSelect(context)
            }
            else -> stopProgress()
        }
    }

    /** Reports the bytes Play has moved so far; the throttle owns the event rate and the phase. */
    private fun reportProgress(state: PpOcrPackState) {
        val total = packTotal(state)
        val done = state.bytesDownloaded.coerceIn(0L, total)
        throttle().onBytes(done, total)
    }

    /**
     * Closes the bar at exactly the total. Play normally reports the full byte count on completion;
     * if it does not, the engine's catalogue size is the best total available, and forcing the tick
     * guarantees the single "verifying" event JS waits for.
     */
    private fun reportComplete(state: PpOcrPackState) {
        val total = packTotal(state)
        val done = maxOf(state.bytesDownloaded, total)
        throttle().onBytes(done, total)
        stopProgress()
    }

    /** Play's total when it has one, else the catalogue size the settings screen already shows. */
    private fun packTotal(state: PpOcrPackState): Long =
        state.totalBytesToDownload.takeIf { it > 0 }
            ?: PpOcrModel.Engine.MEDIUM.approximateDownloadBytes

    private fun throttle(): DownloadProgressThrottle =
        progressThrottle ?: DownloadProgressThrottle(
            intervalMs = PROGRESS_INTERVAL_MS,
            nowMs = { SystemClock.uptimeMillis() },
            emit = { phase, bytesDone, bytesTotal -> emitProgress(phase, bytesDone, bytesTotal) }
        ).also { progressThrottle = it }

    private fun stopProgress() {
        progressThrottle?.stop()
        progressThrottle = null
    }

    /**
     * Selects Enhanced once a completed pack's window is closed. A mode the reader picked while the
     * download ran is the veto; a completion outside a window (a pack delivered before this process
     * watched it) changes nothing. Closing afterwards makes later completion reports harmless.
     */
    private fun applyAutoSelect(context: Context) {
        if (!RecognitionPreference.isDownloadWindowOpen(context)) return
        val select = RecognitionAutoSelect.shouldSelectEnhanced(
            downloadCompleted = true,
            downloadWindowOpen = true,
            manualChoiceSinceDownloadBegan =
                RecognitionPreference.manualChoiceSinceDownloadBegan(context),
            currentMode = RecognitionPreference.mode(context)
        )
        if (select) RecognitionPreference.selectEnhancedAutomatically(context)
        RecognitionPreference.closeDownloadWindow(context)
    }

    /** One progress event: the engine, the phase, and Play's cumulative byte counts. */
    private fun emitProgress(phase: String, bytesDone: Long, bytesTotal: Long) {
        val payload = Arguments.createMap()
        payload.putString("engine", PpOcrModel.Engine.MEDIUM.id)
        payload.putString("phase", phase)
        payload.putDouble("bytesDone", bytesDone.toDouble())
        payload.putDouble("bytesTotal", bytesTotal.toDouble())
        GatenBridgeEmitter.emitRecognitionDownloadProgress(payload)
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

    /** Installed means the pack's files are complete; the bundled recognizer always is. */
    private fun isInstalled(context: Context, engine: RecognitionMode): Boolean {
        if (engine.bundled) return true
        if (engine.ppEngine() == null) return false
        return PpOcrModel.isInstalled(context)
    }

    /** Drives Play's fetch for [engine]'s model, and Play verifies the transfer itself. */
    private suspend fun download(context: Context, engine: RecognitionMode, activity: Activity?) {
        val pp = engine.ppEngine() ?: return
        PpOcrModel.download(context, pp, activity)
    }

    companion object {
        const val NAME = "GatenRecognition"

        private const val ML_KIT_NAME = "Standard"

        /** Five progress events a second: enough for a smooth bar, cheap across the RN bridge. */
        private const val PROGRESS_INTERVAL_MS = 200L

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
