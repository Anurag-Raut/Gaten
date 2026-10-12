/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * When the OCR models go back to the system.
 *
 * Both engines are expensive to build — the manga-ocr encoder is ~87 MB on its own, and ML Kit's
 * Japanese recognizer is not much lighter — and both used to be held for the life of the process,
 * which is most of what an idle Gaten kept resident. They are only worth that while someone is
 * reading with them, so this is the single place that decides when they go: every use restarts an
 * [IDLE_TIMEOUT_MS] timer ([touch]), the timer firing releases both ([releaseAll]), and [releaseNow]
 * does the same when the system asks for memory. Whatever is dropped is built again on the next
 * call, from files already on disk.
 *
 * A release never lands inside a use. [MangaOcrEngine.release] waits on the engine's mutex and
 * [JapaneseOcr.release] skips a client a call is inside, so a use that is already running keeps the
 * models it started with; a touch arriving after the timer fired only costs a reload.
 */
object OcrModels {
    private const val TAG = "GatenOcr"

    /** How long the models may sit unused before they are released. */
    private const val IDLE_TIMEOUT_MS = 90_000L

    /** Off the main thread, since releasing the engines closes sessions and client bindings. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()

    /** The armed idle timer; null once it has fired or a release was asked for by hand. */
    private var idleTimer: Job? = null

    /**
     * Marks the models as in use and restarts the idle timer. Called at the start and at the end of
     * every use, so a lookup keeps them alive while it runs and the idle clock starts when it ends.
     */
    fun touch() {
        synchronized(lock) {
            idleTimer?.cancel()
            idleTimer = scope.launch {
                delay(IDLE_TIMEOUT_MS)
                releaseAll()
            }
        }
    }

    /**
     * Releases both engines at once, for the system asking for memory rather than for the idle
     * timer. Safe to call at any moment: a use that is in flight keeps the model it started with.
     */
    fun releaseNow() {
        synchronized(lock) {
            idleTimer?.cancel()
            idleTimer = null
            scope.launch { releaseAll() }
        }
    }

    /**
     * Hands the models back: manga-ocr's sessions are closed and ML Kit's client dropped, each of
     * them only once the use that was inside it has finished. Nothing is logged when neither had
     * been built, which is the usual case after a release.
     */
    private suspend fun releaseAll() {
        val mangaOcr = MangaOcrEngine.release()
        val mlKit = JapaneseOcr.release()
        if (mangaOcr || mlKit) {
            Log.i(TAG, "Released the OCR models (manga-ocr=$mangaOcr, ML Kit=$mlKit)")
        }
    }
}
