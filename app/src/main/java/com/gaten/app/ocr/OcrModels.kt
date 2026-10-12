/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the OCR models go back to the system.
 *
 * Counts actual recognition work with [begin] and [end], then releases all three engines after
 * [IDLE_TIMEOUT_MS] idle. [releaseNow] skips that delay once active work finishes. Released models
 * are rebuilt from local files or APK assets on the next call.
 *
 * A release never lands inside a use: each engine refuses to close a model a pass is still inside
 * (manga-ocr and meiki hold their inference locks, ML Kit skips while a pass is pinned).
 */
object OcrModels {
    private const val TAG = "GatenOcr"

    /** How long the models may sit unused before they are released. */
    private const val IDLE_TIMEOUT_MS = 90_000L

    /** Off the main thread, since releasing closes native sessions and ML Kit bindings. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val idle = IdleTimer(IDLE_TIMEOUT_MS, scope) { releaseAll() }

    /** Marks a use of the models as running; its idle clock is held until the matching [end]. */
    fun begin() = idle.begin()

    /** Marks a use as finished; the idle clock starts once the last one has. */
    fun end() = idle.end()

    /** Requests cleanup after active work finishes, without blocking the caller. */
    fun releaseNow() = idle.releaseNow()

    /**
     * Hands the three engines back, one at a time: a failure releasing one is logged and the others
     * are still tried, so one bad close cannot leave another engine resident. A
     * [CancellationException] is held back until the rest are tried, so cancelling the release job
     * still propagates.
     */
    private suspend fun releaseAll() {
        var released = false
        var cancelled: CancellationException? = null
        suspend fun release(action: suspend () -> Boolean) {
            try {
                if (action()) released = true
            } catch (e: CancellationException) {
                cancelled = e
            } catch (e: Exception) {
                Log.w(TAG, "Releasing an OCR model failed", e)
            }
        }
        release { MangaOcrEngine.release() }
        release { JapaneseOcr.release() }
        release { MeikiDetector.release() }
        if (released) Log.i(TAG, "Released the idle OCR models")
        cancelled?.let { throw it }
    }
}

/**
 * Fires [onIdle] once every counted use has finished and [timeoutMs] has passed since the last one
 * ended. A use begins with [begin] and ends with [end]; the timer is armed only when the count
 * returns to zero and a new [begin] cancels it, so overlapping uses hold the models for as long as
 * the longest one runs. [releaseNow] skips the idle delay once active work finishes. Engine locks
 * protect a new use that starts after cleanup has already been dispatched.
 */
internal class IdleTimer(
    private val timeoutMs: Long,
    private val scope: CoroutineScope,
    private val onIdle: suspend () -> Unit
) {
    private val lock = Any()
    private var active = 0
    private var timer: Job? = null
    private var revision = 0L
    private var releaseRequested = false

    fun begin() {
        synchronized(lock) {
            active++
            revision++
            timer?.cancel()
            timer = null
        }
    }

    fun end() {
        synchronized(lock) {
            check(active > 0) { "OCR use ended without a matching begin" }
            active--
            if (active > 0) return
            schedule()
        }
    }

    fun releaseNow() {
        synchronized(lock) {
            releaseRequested = true
            revision++
            timer?.cancel()
            timer = null
            if (active == 0) schedule()
        }
    }

    /** Arms one release under [lock]; stale timers cannot claim a newer use's models. */
    private fun schedule() {
        timer?.cancel()
        val scheduledRevision = ++revision
        val waitMs = if (releaseRequested) 0L else timeoutMs
        timer = scope.launch {
            delay(waitMs)
            val ready = synchronized(lock) {
                if (active != 0 || revision != scheduledRevision) false
                else {
                    timer = null
                    releaseRequested = false
                    true
                }
            }
            if (ready) onIdle()
        }
    }
}
