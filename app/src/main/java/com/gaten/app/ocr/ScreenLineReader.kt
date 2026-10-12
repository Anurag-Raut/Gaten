/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads the lines of one screen with manga-ocr, one at a time, and hands out better text as it
 * arrives.
 *
 * The overlay's index is ML Kit's — boxes, lines and a first reading — and it is built instantly so
 * the overlay is usable at once. This class is what happens next: the lines worth reading are read
 * in the background (see [prefetchOrder]) and [text] republishes the index with each line's text
 * replaced as it lands. A gesture asks for the lines it touched with [read], which waits for them
 * and falls back to ML Kit's text when the model is missing, fails, or takes longer than
 * [timeoutMillis] — the reader must never be the reason a tap does nothing.
 *
 * One line is read at a time ([mutex]): there is one model and one phone. A request for a line
 * already read, or being read, joins that read instead of starting a second one, and [close] — when
 * the overlay goes away or the screenshot changes — drops everything in flight.
 *
 * The recogniser arrives as [readLine], so the whole class is free of Android and its cache, its
 * timeouts and its fallbacks are unit-tested; the app passes a lambda that crops the line's box and
 * runs [MangaOcrEngine].
 */
class ScreenLineReader(
    private val screen: ScreenText,
    private val readLine: suspend (ScreenLine) -> String?,
    private val timeoutMillis: Long = READ_TIMEOUT_MS,
    private val onLog: (String) -> Unit = { message -> Log.d(TAG, message) }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One read at a time: the model is one model, and the phone has no use for two. */
    private val mutex = Mutex()

    /** Per-line results, in flight or finished. A second request for a line joins the first. */
    private val results = ConcurrentHashMap<Int, Deferred<String>>()

    /** Lines whose read is over, so a later request can be reported as a cache hit. */
    private val read = ConcurrentHashMap.newKeySet<Int>()

    private var prefetchJob: Job? = null

    private val _text = MutableStateFlow(screen)

    /** The index as it is read: ML Kit's lines, with the text manga-ocr read in place. */
    val text: StateFlow<ScreenText> = _text.asStateFlow()

    /**
     * The text of line [lineIndex]: manga-ocr's when it read the line in time, ML Kit's otherwise.
     * Waits at most [timeoutMillis] for an answer, so a gesture can rely on it returning.
     */
    suspend fun read(lineIndex: Int): String {
        val line = screen.lines.getOrNull(lineIndex) ?: return ""
        val alreadyRead = lineIndex in read
        val deferred = deferredFor(lineIndex) ?: return line.text

        val text = withTimeoutOrNull(timeoutMillis) { deferred.await() }
        if (text == null) {
            onLog("manga-ocr read \"${line.text}\" timed out after $timeoutMillis ms; keeping ML Kit's text")
            return line.text
        }
        if (alreadyRead) {
            onLog("manga-ocr read \"${line.text}\" -> \"$text\" in 0 ms (cache hit)")
        }
        return text
    }

    /**
     * Reads [order] in the background, one line at a time and in that order. Calling it again
     * re-prioritises — the lines the reader just touched go first — while a read already under way
     * is left to finish; lines already read are not read twice.
     */
    fun prefetch(order: List<Int>) {
        prefetchJob?.cancel()
        val work = order.filter { lineIndex ->
            screen.lines.getOrNull(lineIndex)?.let { OcrLinePolicy.readWithMangaOcr(it.text) } == true
        }
        prefetchJob = scope.launch {
            for (lineIndex in work) {
                val deferred = deferredFor(lineIndex) ?: continue
                // Awaiting one before starting the next is what keeps a tap from queueing behind a
                // whole screen's worth of reads.
                deferred.await()
            }
            onLog("manga-ocr prefetch finished (${work.size} lines)")
        }
    }

    /** Stops everything: in-flight reads are cancelled and nothing more is queued. */
    fun close() {
        prefetchJob?.cancel()
        scope.cancel()
    }

    /**
     * The read of [lineIndex], started now if it has not been started. Null when the line is not
     * one manga-ocr should read at all — its text stays exactly as ML Kit read it.
     */
    private fun deferredFor(lineIndex: Int): Deferred<String>? {
        val line = screen.lines.getOrNull(lineIndex) ?: return null
        if (!OcrLinePolicy.readWithMangaOcr(line.text)) return null
        return results.computeIfAbsent(lineIndex) { scope.async { readOnce(lineIndex, line) } }
    }

    /** One read, with the model's answer guarded and logged. ML Kit's text when nothing is kept. */
    private suspend fun readOnce(lineIndex: Int, line: ScreenLine): String {
        val startedAt = System.nanoTime()
        var timedOut = false
        val raw = mutex.withLock {
            try {
                // withTimeout rather than withTimeoutOrNull: a read that returns nothing (the model
                // is not installed, the reader turned it off) is not a timeout and is not logged.
                withTimeout(timeoutMillis) { readLine(line) }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                null
            }
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        if (raw == null) {
            if (timedOut) {
                onLog("manga-ocr read \"${line.text}\" timed out after $timeoutMillis ms; keeping ML Kit's text")
            }
            return line.text
        }

        val accepted = raw.takeIf { OcrLinePolicy.acceptsMangaText(line.text, it) }
        val text = accepted ?: line.text
        read.add(lineIndex)
        onLog(
            "manga-ocr read \"${line.text}\" -> \"$text\" in $elapsed ms (cache miss)" +
                if (accepted == null) " [rejected]" else ""
        )
        if (accepted != null) _text.update { it.withLineText(lineIndex, accepted) }
        return text
    }

    companion object {
        private const val TAG = "GatenOcr"

        /** How long a gesture waits for one line before falling back to ML Kit's text. */
        const val READ_TIMEOUT_MS = 2_500L
    }
}

/**
 * The order to read a screen's lines in, best first.
 *
 * Once a gesture has happened, the lines nearest the one it touched are the ones about to be needed,
 * so they go first. Before that, the biggest boxes do: a big box is a headline, a sign or a caption
 * — the text a reader is most likely to circle — while a small one is incidental. Lines
 * [OcrLinePolicy] would not read are left out, so the order is the work itself and nothing else.
 * Pure.
 */
internal fun prefetchOrder(screen: ScreenText, fromLine: Int? = null): List<Int> {
    val readable = screen.lines.indices.filter { OcrLinePolicy.readWithMangaOcr(screen.lines[it].text) }
    val anchor = fromLine?.let { screen.lines.getOrNull(it)?.bounds }

    return if (anchor == null) {
        readable.sortedWith(compareByDescending<Int> { screen.lines[it].bounds.area() }.thenBy { it })
    } else {
        readable.sortedWith(
            compareBy<Int> { centreDistanceSquared(screen.lines[it].bounds, anchor) }
                .thenByDescending { screen.lines[it].bounds.area() }
                .thenBy { it }
        )
    }
}

/** Pixels of a box; a phone-sized line is nowhere near an Int overflow. */
private fun Box.area(): Int = width * height

/** Squared distance between two boxes' centres: enough to order by, and exact in integers. */
private fun centreDistanceSquared(a: Box, b: Box): Int {
    val dx = a.centreX - b.centreX
    val dy = a.centreY - b.centreY
    return dx * dx + dy * dy
}
