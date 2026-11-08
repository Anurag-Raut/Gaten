/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Play's fast-follow asset pack for the optional PP-OCRv6 Medium model.
 *
 * On a Play install the `:ppocr` pack carries `pp-ocr/medium/rec.onnx` and `dict.txt`; Play fetches
 * it after install — without the reader opening Settings — and verifies every byte of the transfer,
 * so [PpOcrModel] hands ONNX Runtime files straight out of the pack's directory. Anywhere else the
 * pack does not exist, and [PpOcrModel.download] uses the in-app downloader instead.
 *
 * Play reports a fetch as soon as it has *accepted* it: `fetch()` resolving means "the request is
 * queued", not "the files are on disk". Completion is the pack reaching [AssetPackStatus.COMPLETED],
 * which is why a state listener is registered before anything is requested or queried, and why
 * [isTransferring] reads the live state rather than an in-module flag — a fast-follow download can
 * start before this process exists and outlive it, so only Play knows whether one is running.
 */
internal object PpOcrPack {
    private const val TAG = "GatenOcr"

    /** The pack's name, as declared by the `:ppocr` module's `assetPack` block. */
    const val PACK_NAME = "ppocr"

    /** Where the model and dictionary sit inside the pack's assets; the runtime path Gaten reads. */
    const val ASSET_DIR = "pp-ocr/medium"

    /** The package Play installs from; any other installer means the pack cannot be delivered. */
    private const val PLAY_PACKAGE = "com.android.vending"

    /** How long a requested install may stay unfinished before it is reported as failed. */
    private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last state Play reported, kept live by [manager]'s listener; UNKNOWN until it speaks. */
    private val _status = MutableStateFlow(AssetPackStatus.UNKNOWN)

    private var manager: AssetPackManager? = null

    /**
     * True while Play is actually moving bytes. `WAITING_FOR_WIFI` and a user-confirmation prompt
     * are not transfers — nothing moves until the network or the user says so — and reporting them
     * as one would leave the settings screen saying "Downloading…" indefinitely.
     */
    val isTransferring: Boolean
        get() {
            val status = _status.value
            return status == AssetPackStatus.PENDING ||
                status == AssetPackStatus.DOWNLOADING ||
                status == AssetPackStatus.TRANSFERRING
        }

    /**
     * The pack's asset directory when Play has delivered a *complete* copy of [files] into it, else
     * null. A pack location alone is not enough: extraction can be reported before every file is on
     * disk, so each file's pinned size is checked here, keeping a half-delivered pack from being
     * handed to ONNX Runtime.
     */
    fun completeDir(context: Context, files: List<PpOcrModel.ModelFile>): File? {
        val manager = manager(context) ?: return null
        val assetsPath = try {
            manager.getPackLocation(PACK_NAME)?.assetsPath()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the recognition pack's location", e)
            null
        } ?: return null
        val directory = File(assetsPath, ASSET_DIR)
        return directory.takeIf { candidate -> files.all { it.isPresentIn(candidate) } }
    }

    /**
     * Re-reads the pack's state from Play and waits for the answer; it can have changed while this
     * process was not watching. Suspending, so a caller that is about to *report* the state (the
     * settings read) cannot resolve before the answer arrives and call a live transfer "not
     * downloading" — a mistake nothing on the JS side would ever correct, because it only re-reads
     * on demand.
     */
    suspend fun refreshNow(context: Context) {
        val manager = manager(context) ?: return
        try {
            val state = manager.getPackStates(listOf(PACK_NAME)).await().packStates()[PACK_NAME]
            if (state != null) _status.value = state.status()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the recognition pack's state", e)
        }
    }

    /** Fire-and-forget [refreshNow], for callers that have no coroutine: module init, app foreground. */
    fun refresh(context: Context) {
        if (manager(context) == null) return
        scope.launch { refreshNow(context) }
    }

    /**
     * Makes the pack's copy of [files] available, driving Play's fetch when it is not there yet.
     *
     * Returns true once the files are on disk, and false when this install has no pack to offer —
     * not installed from Play, or Play's delivery is unavailable on the device — which sends the
     * caller to the in-app downloader. A delivery Play accepted but could not finish throws
     * [PpOcrModel.DownloadException], so a failed Play transfer is never papered over with a
     * surprise 76 MB in-app download. Every way the wait can end is terminal — including
     * `WAITING_FOR_WIFI`, where Play has queued the fetch but will not move bytes — so a request
     * always answers instead of leaving the settings screen on "Downloading…" until it times out. A
     * request Play rejects outright ends the wait at once, carrying Play's own reason, and each
     * request arms the watch with a non-terminal state first, so a terminal state an earlier attempt
     * left behind can never answer a new one.
     *
     * [activity] is only used to raise Play's own confirmation prompt when the download needs the
     * user's agreement (size or cellular); without one the prompt falls back to Play's notification.
     */
    suspend fun ensureInstalled(
        context: Context,
        files: List<PpOcrModel.ModelFile>,
        activity: Activity?
    ): Boolean {
        if (completeDir(context, files) != null) return true
        val manager = manager(context) ?: return false
        if (!isPlayInstall(context)) return false

        // Arm the watch before the fetch. The state flow is conflated: it holds the last value Play
        // (or a previous attempt) left there, so `first {}` would answer this new request from an
        // earlier FAILED/CANCELED *synchronously*, while the fetch it just made is still running —
        // reporting a false failure for a download that is going fine. Overwriting the value with a
        // non-terminal one first makes the first matching emission necessarily this attempt's.
        // [fetchError] carries a rejection Play reports through the Task rather than as a state.
        val fetchError = AtomicReference<String?>(null)
        _status.value = AssetPackStatus.PENDING
        manager.fetch(listOf(PACK_NAME)).addOnFailureListener { error ->
            // Play can reject the request outright, with no listener state to follow it, so the
            // rejection itself is what ends the wait — promptly, carrying Play's own reason, rather
            // than leaving the caller on the timeout.
            fetchError.set(error.message ?: error.toString())
            _status.value = AssetPackStatus.FAILED
        }

        // The prompt is raised once, off the state watch: it is a dialog the user may leave open, and
        // blocking the watch on it would stop the very state that reports the download it authorised.
        val prompted = AtomicBoolean(false)
        val terminal = withTimeoutOrNull(INSTALL_TIMEOUT_MS) {
            _status.first { state ->
                when (state) {
                    AssetPackStatus.COMPLETED,
                    AssetPackStatus.FAILED,
                    AssetPackStatus.CANCELED,
                    AssetPackStatus.WAITING_FOR_WIFI -> true
                    AssetPackStatus.REQUIRES_USER_CONFIRMATION -> {
                        if (prompted.compareAndSet(false, true)) {
                            scope.launch { confirm(manager, activity) }
                        }
                        false
                    }
                    else -> false
                }
            }
        } ?: run {
            // Nothing became terminal within the budget: stop reporting a transfer this request no
            // longer owns, so a parked settings screen cannot read the armed sentinel as a download
            // that is still running. Play's own state, if it ever speaks, overwrites this.
            _status.value = AssetPackStatus.FAILED
            throw PpOcrModel.DownloadException("Play did not finish delivering the ${PACK_NAME} pack.")
        }
        if (terminal != AssetPackStatus.COMPLETED) {
            throw PpOcrModel.DownloadException(
                fetchError.get()?.let { "Google Play refused the ${PACK_NAME} download: $it" }
                    ?: refusal(terminal)
            )
        }
        if (completeDir(context, files) == null) {
            throw PpOcrModel.DownloadException(
                "Play reported the ${PACK_NAME} pack installed but its files are incomplete."
            )
        }
        return true
    }

    /**
     * True when the app was installed from Google Play, the only channel that can deliver the pack.
     * Read from the package manager rather than asked of Play Core, so a sideloaded build goes
     * straight to the in-app downloader instead of waiting on a pack that cannot exist.
     */
    fun isPlayInstall(context: Context): Boolean = try {
        val packageManager = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(context.packageName)
        }
        installer == PLAY_PACKAGE
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the installer of ${context.packageName}", e)
        false
    }

    /**
     * The Play manager, with its state listener attached before the first fetch or query. Null when
     * Play Core cannot be brought up at all (a bare JVM test, a device without Play services), which
     * every caller treats as "no pack here".
     */
    private fun manager(context: Context): AssetPackManager? = synchronized(this) {
        manager?.let { return it }
        val created = try {
            AssetPackManagerFactory.getInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Play asset delivery is unavailable", e)
            null
        } ?: return null
        // Attached before the manager is published, so no fetch or location query can precede it:
        // the callback writes every state Play reports into _status, which is what the fetch watch
        // and isTransferring read. Play Core keeps the listener alive for the life of the manager,
        // so no reference is held here.
        created.registerListener(AssetPackStateUpdateListener { state -> _status.value = state.status() })
        manager = created
        created
    }

    /**
     * Why Play stopped short, in words the settings screen can show. [AssetPackStatus.WAITING_FOR_WIFI]
     * is its own case: Play has the request and will download it later over Wi-Fi, but this caller's
     * request cannot finish now, and a spinner that never ends would hide that.
     */
    private fun refusal(status: Int): String = when (status) {
        AssetPackStatus.WAITING_FOR_WIFI ->
            "Google Play is waiting for a Wi-Fi connection to finish the ${PACK_NAME} download."
        AssetPackStatus.CANCELED -> "The Google Play download of the ${PACK_NAME} pack was cancelled."
        AssetPackStatus.FAILED -> "Google Play could not download the ${PACK_NAME} pack."
        else -> "Google Play did not finish delivering the ${PACK_NAME} pack (state $status)."
    }

    /** Raises Play's download-confirmation dialog, if there is an Activity to raise it in. */
    private suspend fun confirm(manager: AssetPackManager, activity: Activity?) {
        val parent = activity ?: run {
            Log.w(TAG, "Play asked to confirm the pack download with no activity to prompt in")
            return
        }
        try {
            manager.showConfirmationDialog(parent).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not show the pack's confirmation prompt", e)
        }
    }

    private fun PpOcrModel.ModelFile.isPresentIn(directory: File): Boolean {
        val file = File(directory, name)
        return file.isFile && file.length() == size
    }

    /** Bridges a Play [Task] into coroutines without pulling in kotlinx-coroutines-play-services. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
}
