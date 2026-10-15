/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * the overlay is usable at once. This class is what happens next: every line is read in the
 * background (see [prefetchOrder]) and [text] republishes the index with each line's text replaced
 * as it lands. A gesture asks for the lines it touched with [read], which waits for them and falls
 * back to ML Kit's text when the model is missing, fails, or takes longer than [timeoutMillis] —
 * the reader must never be the reason a tap does nothing.
 *
 * Every line ML Kit found is offered to the model, whatever script it read it in: a line read as
 * Latin can still be stylized Japanese. The line's final text is what decides whether it stays in
 * the index — manga-ocr's reading, or ML Kit's own text when there is no model — and a line with no
 * Japanese in it is dropped ([withDroppedLine]) as soon as that is known.
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
    private val onLog: (String) -> Unit = { message -> Log.d(TAG, message) },
    private val readLineBackground: suspend (ScreenLine) -> String? = readLine
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One read at a time: the model is one model, and the phone has no use for two. */
    private val mutex = Mutex()

    /** Per-line results, in flight or finished. A second request for a line joins the first. */
    private val results = ConcurrentHashMap<Int, Deferred<String>>()

    /** Lines whose read is over, so a later request can be reported as a cache hit. */
    private val read = ConcurrentHashMap.newKeySet<Int>()

    /** In-flight reads explicitly promoted to foreground; concurrent taps join these. */
    private val foregroundReads = ConcurrentHashMap.newKeySet<Int>()

    private var prefetchJob: Job? = null

    private val _text = MutableStateFlow(screen)

    /** The index as it is read: ML Kit's lines, with the text manga-ocr read in place. */
    val text: StateFlow<ScreenText> = _text.asStateFlow()

    /**
     * The text of line [lineIndex]: manga-ocr's when it read the line in time, ML Kit's otherwise.
     * Waits at most [timeoutMillis] for an answer, so a gesture can rely on it returning.
     */
    suspend fun read(lineIndex: Int): String {
        val line = _text.value.lines.getOrNull(lineIndex) ?: return ""
        val alreadyRead = lineIndex in read
        val deferred = deferredFor(lineIndex, MangaOcrEngine.Priority.FOREGROUND) ?: return line.text

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
     * is reused unless a foreground gesture promotes it; lines already read are not read twice.
     */
    fun prefetch(order: List<Int>) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            for (lineIndex in order) {
                val deferred = deferredFor(lineIndex, MangaOcrEngine.Priority.BACKGROUND) ?: continue
                // Awaiting one before starting the next is what keeps a tap from queueing behind a
                // whole screen's worth of reads.
                try {
                    deferred.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // A tap may have promoted this line by cancelling its background Deferred.
                    // Join the replacement, then keep the rest of the prefetch order alive.
                    results[lineIndex]?.takeUnless { it === deferred }?.await()
                }
            }
            onLog("manga-ocr prefetch finished (${order.size} lines)")
        }
    }

    /** Stops everything: in-flight reads are cancelled and nothing more is queued. */
    fun close() {
        prefetchJob?.cancel()
        scope.cancel()
    }

    /**
     * The read of [lineIndex], started now if it has not been started. Null when there is no such
     * line.
     *
     * The line is resolved from the live index ([text]) so it uses the latest reading and geometry.
     */
    private fun deferredFor(
        lineIndex: Int,
        priority: MangaOcrEngine.Priority
    ): Deferred<String>? {
        val line = _text.value.lines.getOrNull(lineIndex) ?: return null
        return synchronized(results) {
            results[lineIndex]?.let { existing ->
                if (priority == MangaOcrEngine.Priority.FOREGROUND &&
                    !existing.isCompleted && lineIndex !in foregroundReads
                ) {
                    existing.cancel()
                } else {
                    return@synchronized existing
                }
            }
            if (priority == MangaOcrEngine.Priority.FOREGROUND) foregroundReads.add(lineIndex)
            scope.async {
                readOnce(
                    lineIndex,
                    line,
                    priority,
                    if (priority == MangaOcrEngine.Priority.BACKGROUND) readLineBackground else readLine
                )
            }.also { results[lineIndex] = it }
        }
    }

    /** One read, with the model's answer guarded and logged. ML Kit's text when nothing is kept. */
    private suspend fun readOnce(
        lineIndex: Int,
        line: ScreenLine,
        priority: MangaOcrEngine.Priority,
        reader: suspend (ScreenLine) -> String?
    ): String {
        val startedAt = System.nanoTime()
        var timedOut = false
        if (priority == MangaOcrEngine.Priority.BACKGROUND) OcrPriority.awaitBackgroundTurn()
        val raw = mutex.withLock {
            try {
                // withTimeout rather than withTimeoutOrNull: a read that returns nothing (the model
                // is not installed) is not a timeout and is not logged.
                withTimeout(timeoutMillis) { reader(line) }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                null
            }
        }
        val elapsed = (System.nanoTime() - startedAt) / 1_000_000
        val accepted = raw?.takeIf { OcrLinePolicy.acceptsMangaText(line.text, it) }
        if (raw != null) {
            read.add(lineIndex)
            onLog(
                "manga-ocr read \"${line.text}\" -> \"${accepted ?: line.text}\" in $elapsed ms" +
                    " (cache miss)" + if (accepted == null) " [rejected]" else ""
            )
        } else if (timedOut) {
            onLog("manga-ocr read \"${line.text}\" timed out after $timeoutMillis ms; keeping ML Kit's text")
        }

        val text = accepted ?: line.text
        if (JapaneseText.isJapaneseLine(text)) {
            if (accepted != null) _text.update { it.withLineText(lineIndex, accepted) }
        } else {
            onLog("dropped line \"$text\" (no Japanese)")
            _text.update { it.withDroppedLine(lineIndex) }
        }
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
 * — the text a reader is most likely to circle — while a small one is incidental. Every line is in
 * the order, whatever script ML Kit read it in; a line it already read is answered from the cache,
 * so the tail of the order costs nothing. Pure.
 */
internal fun prefetchOrder(screen: ScreenText, fromLine: Int? = null): List<Int> {
    val anchor = fromLine?.let { screen.lines.getOrNull(it)?.bounds }

    return if (anchor == null) {
        screen.lines.indices.sortedWith(
            compareByDescending<Int> { screen.lines[it].bounds.area() }.thenBy { it }
        )
    } else {
        screen.lines.indices.sortedWith(
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
