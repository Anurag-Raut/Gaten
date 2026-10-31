/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Rect
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.WordScanner
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.MangaOcrModel
import com.gaten.app.ocr.JapaneseText
import com.gaten.app.ocr.OcrLinePolicy
import com.gaten.app.ocr.OcrRepair
import com.gaten.app.ocr.ScreenLine
import com.gaten.app.ocr.withText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

private const val LOOKUP_TAG = "GatenLookup"

/** Everything the result sheet needs after one selection lookup. */
data class LookupResult(
    val ocrText: String,
    val words: List<ScannedWord>,
    val error: String? = null
)

/** The dictionary accessor when the dictionary failed to open: every word list comes back empty. */
private val NO_LOOKUP: (String) -> List<DictEntry> = { emptyList() }

/**
 * Screenshot-scoped cache for raw manga-ocr readings. Calls for the same crop join one read;
 * failed, cancelled, and null reads are removed so a later lookup can retry. Create one instance
 * per screenshot and call [setModelRevision] when the installed model changes.
 */
internal class ExactCropOcrCache(
    modelRevision: String = "default",
    maxResults: Int = 24
) {
    private data class Key(val left: Int, val top: Int, val right: Int, val bottom: Int, val revision: String)

    private val lock = Any()
    private val maxResults = maxResults.coerceAtLeast(1)
    private val entries = LinkedHashMap<Key, CompletableDeferred<String?>>(0, 0.75f, true)
    private var revision = modelRevision

    /** Invalidates subsequent cache requests; in-flight callers finish their model snapshot. */
    fun setModelRevision(value: String) {
        synchronized(lock) {
            if (revision == value) return
            revision = value
            entries.clear()
        }
    }

    /** Returns a cached reading or runs [read] once for this exact, already-clamped crop. */
    suspend fun read(box: Box, read: suspend () -> String?): String? {
        val key: Key
        val deferred: CompletableDeferred<String?>
        val owner: Boolean
        synchronized(lock) {
            key = Key(box.left, box.top, box.right, box.bottom, revision)
            val existing = entries[key]
            if (existing != null) {
                deferred = existing
                owner = false
            } else {
                deferred = CompletableDeferred()
                entries[key] = deferred
                owner = true
            }
        }
        if (!owner) return deferred.await()

        try {
            val result = read()
            synchronized(lock) {
                if (result == null) {
                    if (entries[key] === deferred) entries.remove(key)
                } else {
                    deferred.complete(result)
                    while (entries.values.count { it.isCompleted } > maxResults) {
                        val completed = entries.entries.firstOrNull { it.value.isCompleted } ?: break
                        entries.remove(completed.key)
                    }
                }
            }
            if (result == null) deferred.complete(null)
            return result
        } catch (e: CancellationException) {
            synchronized(lock) { if (entries[key] === deferred) entries.remove(key) }
            deferred.cancel(e)
            throw e
        } catch (e: Throwable) {
            synchronized(lock) { if (entries[key] === deferred) entries.remove(key) }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    /** Releases screenshot results and cancels joined waiters; the overlay scope owns active reads. */
    fun clear() {
        synchronized(lock) {
            entries.values.forEach { if (!it.isCompleted) it.cancel() }
            entries.clear()
        }
    }
}

/** Reads the drawn selection with Manga OCR; detector text never supplies lookup results. */
internal suspend fun runLookup(
    context: Context,
    screenshot: Bitmap,
    selection: Rect,
    repo: JmdictRepository?,
    cache: ExactCropOcrCache? = null
): LookupResult {
    val box = clampedBox(screenshot, Box(selection.left.toInt(), selection.top.toInt(),
        selection.right.toInt(), selection.bottom.toInt()))
    val crop = Bitmap.createBitmap(screenshot, box.left, box.top, box.width, box.height)
    val reading = readExactCrop(context, crop, box, cache)
    return withContext(Dispatchers.Default) {
        mangaLookupResult(reading, if (repo == null) NO_LOOKUP else { term -> repo.lookup(term) })
    }
}

/** Rejects failed recognition instead of silently answering from the detector's text. */
internal fun mangaLookupResult(reading: String?, lookup: (String) -> List<DictEntry>): LookupResult {
    if (acceptedReading(reading) == null) {
        return LookupResult("", emptyList(), "Manga OCR could not read this text. Please select it again.")
    }
    return mergeLookup(reading, null, lookup)
}

/** Reads an already-cut bitmap with the same foreground OCR and screenshot cache for both gestures. */
private suspend fun readExactCrop(
    context: Context,
    crop: Bitmap,
    box: Box,
    cache: ExactCropOcrCache?
): String? = withContext(Dispatchers.Default) {
    cache?.setModelRevision(modelRevision(context))
    val started = SystemClock.elapsedRealtime()
    val read = suspend {
        JapaneseOcr.readLineWithMangaOcr(context, crop, Box(0, 0, crop.width, crop.height))
    }
    (if (cache == null) read() else cache.read(box, read)).also {
        Log.d(LOOKUP_TAG, "crop read=${SystemClock.elapsedRealtime() - started}ms")
    }
}

/** Reads the entire tapped line with Manga OCR, then ranks its words nearest the tap. */
internal suspend fun runTapLookup(
    context: Context,
    screenshot: Bitmap,
    line: ScreenLine,
    x: Int,
    y: Int,
    repo: JmdictRepository?,
    cache: ExactCropOcrCache
): LookupResult = withContext(Dispatchers.Default) {
    // Pad before cutting: preprocessing cannot restore pixels outside an already-cut bitmap.
    val bounds = line.detectorBounds
    val pad = maxOf(2, ((if (line.vertical) bounds.width else bounds.height) * 0.1f).toInt())
    val box = clampedBox(screenshot, Box(bounds.left - pad, bounds.top - pad,
        bounds.right + pad, bounds.bottom + pad))
    val crop = Bitmap.createBitmap(screenshot, box.left, box.top, box.width, box.height)
    val reading = readExactCrop(context, crop, box, cache)
    tapLookupResult(line, reading, x, y,
        if (repo == null) NO_LOOKUP else { term -> repo.lookup(term) })
}

/** Uses only Manga OCR text; the index supplies geometry for ordering, never dictionary words. */
internal fun tapLookupResult(
    line: ScreenLine,
    reading: String?,
    x: Int,
    y: Int,
    lookup: (String) -> List<DictEntry>,
    maxWords: Int = 24
): LookupResult {
    val text = acceptedReading(reading) ?: return mangaLookupResult(reading, lookup)
    val cachedLookup = memoizedLookup(lookup)
    val corrected = line.withText(OcrRepair.repair(text, cachedLookup))
    if (maxWords <= 0) return LookupResult(corrected.text, emptyList())
    val words = WordScanner.scan(corrected.text, cachedLookup,
        maxWords = maxOf(1, corrected.text.length)).map { it.copy(context = corrected.text) }
    return LookupResult(corrected.text, rankWordsByTapDistance(words, corrected, x, y)
        .distinctBy { it.surface }.take(maxWords))
}

internal fun mergePrimary(primary: LookupResult, supplement: LookupResult, maxWords: Int = 24): LookupResult =
    primary.copy(
        ocrText = primary.ocrText.ifEmpty { supplement.ocrText },
        words = (primary.words + supplement.words).distinctBy { it.surface }.take(maxWords)
    )

private fun modelRevision(context: Context): String = MangaOcrModel.Slot.entries.joinToString("|") { slot ->
    val file = MangaOcrModel.file(context, slot)
    "${file.path}:${file.length()}:${file.lastModified()}"
}

/**
 * The circle's answer, assembled: the crop reading first, the index's own words behind it.
 *
 * The crop is what was circled, so it is [LookupResult.ocrText] whenever it came back a reading at
 * all ([acceptedReading]), whatever the index read instead — a Latin or placeholder answer from the
 * model is noise and leaves the index's text in place. Its text is repaired one character for one
 * ([OcrRepair]), so the words' offsets address the text they were read from, and every crop word
 * carries that text as its context.
 *
 * The index's words only supplement it: appended after the crop's, deduplicated by surface so a
 * word the crop already gave is not listed twice, and capped at [maxWords] together. Each keeps the
 * context and the offsets it was read with. When there is no crop reading, the index's own answer
 * is the whole result, which may be empty.
 *
 * Pure: the dictionary arrives as [lookup], so the priority of the two answers, the acceptance of
 * the reading and the deduplication are unit-tested directly.
 */
fun mergeLookup(
    cropReading: String?,
    index: LookupResult?,
    lookup: (String) -> List<DictEntry>,
    maxWords: Int = 24
): LookupResult {
    val cachedLookup = memoizedLookup(lookup)
    val cropText = acceptedReading(cropReading)?.let { OcrRepair.repair(it, cachedLookup) }
    val cropWords = cropText?.let { text ->
        WordScanner.scan(text, cachedLookup).map { it.copy(context = text) }
    }.orEmpty()

    val words = (cropWords + (index?.words ?: emptyList()))
        .distinctBy { it.surface }
        .take(maxWords)

    return LookupResult(ocrText = cropText ?: index?.ocrText.orEmpty(), words = words)
}

/**
 * The crop reading when it is a reading at all: not blank, not one of the model's placeholders, and
 * Japanese — the decoder writes Japanese, so a reading without any is noise the sheet must not show
 * and the dictionary must not be handed.
 *
 * Deliberately not [OcrLinePolicy.acceptsMangaText]: the whole selection is one line, so there is
 * no ML Kit reading of it to measure the length against, and a long crop reading is a legitimate
 * one — a circle can hold a whole sentence.
 */
private fun acceptedReading(reading: String?): String? =
    reading?.trim()?.takeIf { !OcrLinePolicy.isUnreadable(it) && JapaneseText.isJapaneseLine(it) }

private fun clampedBox(screenshot: Bitmap, box: Box): Box {
    val left = box.left.coerceIn(0, screenshot.width - 1)
    val top = box.top.coerceIn(0, screenshot.height - 1)
    val right = box.right.coerceIn(left + 1, screenshot.width)
    val bottom = box.bottom.coerceIn(top + 1, screenshot.height)
    return Box(left, top, right, bottom)
}
