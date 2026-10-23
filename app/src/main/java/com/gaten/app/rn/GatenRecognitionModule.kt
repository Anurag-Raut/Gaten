/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rn

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.gaten.app.ocr.MangaOcrCatalog
import com.gaten.app.ocr.MangaOcrDownloader
import com.gaten.app.ocr.MangaOcrModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The optional manga-ocr model, as the recognition page reaches it: what is on the phone, the
 * catalog, and the ways onto the phone.
 *
 * The download runs in this module's scope rather than the page's — the Compose shell owned its
 * RecognitionViewModel for the same reason — so leaving the page neither cancels a running
 * download nor throws away what it has done. Progress travels to JS as the
 * [GatenBridgeEmitter.RECOGNITION_PROGRESS] event, and the final outcome as the promise's answer.
 * One mutation at a time is the rule: a second one would write over what the first is staging.
 */
class GatenRecognitionModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    /** The document picker's answer, or nothing when the reader dismissed it. */
    private var pickerPromise: Promise? = null

    private val pickerListener: ActivityEventListener =
        object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity,
                requestCode: Int,
                resultCode: Int,
                data: Intent?
            ) {
                if (requestCode != PICK_MODEL_FILES_REQUEST) return
                val promise = pickerPromise
                pickerPromise = null
                if (promise == null) return
                val uris = Arguments.createArray()
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.clipData?.let { clip ->
                        for (index in 0 until clip.itemCount) {
                            uris.pushString(clip.getItemAt(index).uri.toString())
                        }
                    }
                    data.data?.let { uris.pushString(it.toString()) }
                }
                promise.resolve(uris)
            }
        }

    init {
        GatenBridgeEmitter.attach(reactContext)
        reactContext.addActivityEventListener(pickerListener)
    }

    override fun getName(): String = NAME

    private val scope = bridgeScope()
    private val busy = AtomicBoolean(false)
    private val downloader = MangaOcrDownloader(MangaOcrModel.dir(reactApplicationContext))

    /** The model directory's state, re-read on every call. */
    @ReactMethod
    fun getInstalled(promise: Promise) {
        promise.resolve(installedState(reactApplicationContext))
    }

    /** The entries the catalog offers, with the sizes the confirm dialog shows. */
    @ReactMethod
    fun getCatalog(promise: Promise) {
        val catalog = Arguments.createArray()
        for (entry in MangaOcrCatalog.entries) {
            val map = Arguments.createMap()
            map.putString("id", entry.id)
            map.putString("label", entry.label)
            map.putString("description", entry.description)
            map.putInt("approxSizeMb", entry.approxSizeMb)
            catalog.pushMap(map)
        }
        promise.resolve(catalog)
    }

    /**
     * Fetches one catalog entry, whose size the reader has already confirmed. The promise answers
     * only when the whole download ends: `installed`, `cancelled`, or `failed` with the reason the
     * downloader produced — the same strings the Compose screen toasted.
     */
    @ReactMethod
    fun download(catalogId: String, promise: Promise) {
        val entry = MangaOcrCatalog.byId(catalogId)
        if (entry == null) {
            promise.reject(CODE_CATALOG, "No catalog entry named $catalogId")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            promise.resolve(outcomeMap("busy"))
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val outcome = downloader.download(entry) { progress ->
                    GatenBridgeEmitter.emitRecognitionProgress(progress.fraction, progress.fileName)
                }
                promise.resolve(
                    when (outcome) {
                        is MangaOcrDownloader.Outcome.Installed -> outcomeMap("installed")
                        is MangaOcrDownloader.Outcome.Cancelled -> outcomeMap("cancelled")
                        is MangaOcrDownloader.Outcome.Failed -> outcomeMap("failed").apply { putString("reason", outcome.reason) }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_DOWNLOAD, "The download could not run", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Asks the download in flight to stop; nothing is installed and the staged files are deleted. */
    @ReactMethod
    fun cancelDownload(promise: Promise) {
        downloader.cancel()
        promise.resolve(true)
    }

    /** Opens the document picker for the model files; resolves the chosen URIs. */
    @ReactMethod
    fun pickModelFiles(promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject(CODE_PICKER, "No activity to open the picker from")
            return
        }
        pickerPromise = promise
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            type = "*/*"
        }
        try {
            activity.startActivityForResult(intent, PICK_MODEL_FILES_REQUEST)
        } catch (e: Exception) {
            pickerPromise = null
            promise.reject(CODE_PICKER, "Could not open the document picker", e)
        }
    }

    /**
     * Copies the picked files in, each recognised by the role its name stands for; `done` tells
     * whether the model became complete. A picker that came back while a download was running, or
     * a second tap, is dropped rather than queued.
     */
    @ReactMethod
    fun importFiles(uris: ReadableArray, promise: Promise) {
        if (!busy.compareAndSet(false, true)) {
            promise.resolve(outcomeMap("busy"))
            return
        }
        val uriList = mutableListOf<Uri>()
        for (index in 0 until uris.size()) {
            val value = uris.getString(index) ?: continue
            uriList.add(Uri.parse(value))
        }
        scope.launch {
            try {
                val done = withContext(Dispatchers.IO) {
                    uriList.isNotEmpty() && MangaOcrModel.importFrom(reactApplicationContext, uriList)
                }
                promise.resolve(outcomeMap("imported").apply { putBoolean("done", done) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_IMPORT, "The imported files could not be copied in", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Copies the bundled model out of the APK's assets, which is how a removed model comes back.
     * `done` tells whether it is installed afterwards; false means this build has no bundle.
     */
    @ReactMethod
    fun installBundled(promise: Promise) {
        if (!busy.compareAndSet(false, true)) {
            promise.resolve(outcomeMap("busy"))
            return
        }
        scope.launch {
            try {
                val done = withContext(Dispatchers.IO) {
                    MangaOcrModel.installBundled(reactApplicationContext)
                }
                promise.resolve(outcomeMap("imported").apply { putBoolean("done", done) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_IMPORT, "The bundled model could not be installed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /**
     * Takes the model off the phone; `removed` tells whether the directory really went, read back
     * rather than assumed, because a directory that cannot be deleted leaves the model in place.
     */
    @ReactMethod
    fun removeModel(promise: Promise) {
        if (!busy.compareAndSet(false, true)) {
            promise.resolve(outcomeMap("busy"))
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { MangaOcrModel.remove(reactApplicationContext) }
                val removed = !MangaOcrModel.isInstalled(reactApplicationContext)
                promise.resolve(outcomeMap("removed").apply { putBoolean("removed", removed) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                promise.reject(CODE_REMOVE, "The model files could not be removed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    /** True when the active network is metered, i.e. a download would run on mobile data. */
    @ReactMethod
    fun isMetered(promise: Promise) {
        val metered = try {
            reactApplicationContext
                .getSystemService(ConnectivityManager::class.java)
                ?.isActiveNetworkMetered == true
        } catch (_: Exception) {
            false
        }
        promise.resolve(metered)
    }

    override fun invalidate() {
        scope.cancel()
    }

    private fun installedState(context: ReactApplicationContext): WritableMap =
        Arguments.createMap().apply {
            putBoolean("installed", MangaOcrModel.isInstalled(context))
            putBoolean("bundled", MangaOcrModel.isBundled(context))
            val catalogId = MangaOcrModel.installedCatalogId(context)
            if (catalogId != null) putString("catalogId", catalogId) else putNull("catalogId")
            putDouble("sizeBytes", MangaOcrModel.installedSizeBytes(context).toDouble())
        }

    /** The promise's answer for one long operation: the kind, plus a detail where one exists. */
    private fun outcomeMap(kind: String): WritableMap =
        Arguments.createMap().apply {
            putString("kind", kind)
            
        }

    companion object {
        const val NAME = "GatenRecognition"

        private const val PICK_MODEL_FILES_REQUEST = 7241
        private const val CODE_CATALOG = "E_RECOGNITION_CATALOG"
        private const val CODE_DOWNLOAD = "E_RECOGNITION_DOWNLOAD"
        private const val CODE_PICKER = "E_RECOGNITION_PICKER"
        private const val CODE_IMPORT = "E_RECOGNITION_IMPORT"
        private const val CODE_REMOVE = "E_RECOGNITION_REMOVE"
    }
}