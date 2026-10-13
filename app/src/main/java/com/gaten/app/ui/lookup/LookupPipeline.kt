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
import com.gaten.app.ocr.CharBoxAlign
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.MangaOcrModel
import com.gaten.app.ocr.JapaneseText
import com.gaten.app.ocr.OcrLinePolicy
import com.gaten.app.ocr.OcrRepair
import com.gaten.app.ocr.ScreenLine
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.interpolateCharBoxes
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

/**
 * One selection lookup: the exact crop is read by manga-ocr, and the selected index lines
 * supplement that reading's words. Circles pass their drawn bounds; taps use [runTapLookup].
 *
 * The crop is read first and unconditionally — whatever the index found on the screen at large,
 * whether or not its lines have Japanese in them, and whether or not there is an index at all.
 * Stylized lettering is exactly what ML Kit misreads, and there is no way to tell from the index
 * whether that happened, so the circle goes to the model regardless. The crop is cut first and read
 * with its own full bounds, so [com.gaten.app.ocr.OcrPreprocess]'s padding has nowhere to reach but
 * the crop's own edge: the selection never grows into the lines beside it. The crop is never
 * recycled — `Bitmap.createBitmap` may hand back the source itself.
 *
 * [index] is asked for only after that, so the answer the reader asked for never queues behind
 * reading the lines beside it. What it returns is the live index, with the lines the selection
 * covers already read; null means there is no index yet, and ML Kit's own reading of the crop
 * stands in for it. Either way those lines only supplement the crop ([mergeLookup]) and nothing
 * outside the drawn box is ever read or answered with.
 *
 * [repo] is null when the dictionary failed to open; the caller turns that into [LookupResult.error],
 * and no words are produced — the text is still the crop's, or the Japanese the selection covered.
 */
internal suspend fun runLookup(
    context: Context,
    screenshot: Bitmap,
    selection: Rect,
    repo: JmdictRepository?,
    cache: ExactCropOcrCache? = null,
    onPrimary: suspend (LookupResult) -> Unit = {},
    index: suspend () -> ScreenText?
): LookupResult {
    val box = Box(
        selection.left.toInt(),
        selection.top.toInt(),
        selection.right.toInt(),
        selection.bottom.toInt()
    )
    val clamped = clampedBox(screenshot, box)
    val crop = Bitmap.createBitmap(screenshot, clamped.left, clamped.top,
        clamped.right - clamped.left, clamped.bottom - clamped.top)
    val reading = readExactCrop(context, crop, clamped, cache)

    return withContext(Dispatchers.Default) {
        val lookup: (String) -> List<DictEntry> =
            if (repo == null) NO_LOOKUP else { term -> repo.lookup(term) }
        val cachedLookup = memoizedLookup(lookup)
        val primaryStarted = SystemClock.elapsedRealtime()
        val primary = mergeLookup(reading, null, cachedLookup)
        Log.d(LOOKUP_TAG, "primary dictionary=${SystemClock.elapsedRealtime() - primaryStarted}ms")
        if (primary.ocrText.isNotEmpty() || primary.words.isNotEmpty()) onPrimary(primary)
        val supplementStarted = SystemClock.elapsedRealtime()
        val screen = index()
        // An index with no lines at all (failed scan, or ML Kit read nothing) stands for "no index
        // yet", so the crop's own ML Kit reading still supplements the circle. An index with lines
        // that merely don't intersect the box does not: the index is the record of the screen.
        val supplement = if (screen != null && screen.lines.isNotEmpty()) {
            lookupFromSelection(japaneseLines(screen), box, cachedLookup)
        } else {
            cropIndex(crop)?.let {
                lookupFromSelection(japaneseLines(it), Box(0, 0, crop.width, crop.height), cachedLookup)
            }
        }
        val result = if (supplement == null) primary else mergePrimary(primary, supplement)
        Log.d(LOOKUP_TAG, "supplement=${SystemClock.elapsedRealtime() - supplementStarted}ms")
        result
    }
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

/** Reads a padded phrase, then answers the whole frozen line without waiting for background OCR. */
internal suspend fun runTapLookup(
    context: Context,
    screenshot: Bitmap,
    line: ScreenLine,
    phrase: TapPhrase?,
    x: Int,
    y: Int,
    repo: JmdictRepository?,
    cache: ExactCropOcrCache
): LookupResult = withContext(Dispatchers.Default) {
    val reading = phrase?.let {
        val box = clampedBox(screenshot, it.bounds)
        val crop = Bitmap.createBitmap(screenshot, box.left, box.top, box.width, box.height)
        readExactCrop(context, crop, box, cache)
    }
    val lookup: (String) -> List<DictEntry> =
        if (repo == null) NO_LOOKUP else { term -> repo.lookup(term) }
    tapLookupResult(line, phrase, reading, x, y, lookup)
}

/**
 * Corrects only the frozen phrase span, keeping surrounding text and geometry intact. Scanning the
 * corrected sentence keeps word offsets and Anki context consistent, even when OCR inserts characters.
 * Distance ranking precedes deduplication and the display cap so the tapped occurrence survives.
 */
internal fun tapLookupResult(
    line: ScreenLine,
    phrase: TapPhrase?,
    reading: String?,
    x: Int,
    y: Int,
    lookup: (String) -> List<DictEntry>,
    maxWords: Int = 24
): LookupResult {
    val cachedLookup = memoizedLookup(lookup)
    var corrected = line.withText(OcrRepair.repair(line.text, cachedLookup))
    val region = phrase?.takeIf {
        it.start >= 0 && it.endExclusive > it.start && it.endExclusive <= line.text.length &&
            line.charBoxes.size == line.text.length && it.bounds.width > 0 && it.bounds.height > 0
    }
    val replacement = if (region == null) null else acceptedReading(reading)
        ?.let { OcrRepair.repair(it, cachedLookup) }
    if (region != null && replacement != null) {
        val from = region.start
        val to = region.endExclusive
        val boxes = CharBoxAlign.align(
            corrected.text.substring(from, to), corrected.charBoxes.subList(from, to),
            replacement, region.bounds, line.vertical
        )
        corrected = corrected.copy(
            text = corrected.text.replaceRange(from, to, replacement),
            charBoxes = corrected.charBoxes.take(from) + boxes + corrected.charBoxes.drop(to)
        )
    }
    if (maxWords <= 0) return LookupResult(corrected.text, emptyList())
    val words = WordScanner.scan(corrected.text, cachedLookup, maxWords = maxOf(1, corrected.text.length))
        .map { it.copy(context = corrected.text) }
    val preferredStart = if (replacement != null && region != null) region.start else 0
    val preferredEnd = preferredStart + (replacement?.length ?: 0)
    return LookupResult(
        ocrText = corrected.text,
        words = rankWordsByTapDistance(words, corrected, x, y, preferredStart, preferredEnd)
            .distinctBy { it.surface }
            .take(maxWords)
    )
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

/**
 * [screen] with every line that holds no Japanese dropped, indices kept.
 *
 * The index keeps every line ML Kit read — a line it read as Latin can still be stylized Japanese —
 * and drops the ones whose final text has no Japanese in it. This applies the same rule, in one
 * pass, to what the index still holds unread, so only actually intersecting Japanese text can
 * supplement the crop. A dropped line keeps its place (empty text, no boxes), so the indices a
 * gesture handed back stay valid.
 */
private fun japaneseLines(screen: ScreenText): ScreenText = screen.copy(
    lines = screen.lines.map { line ->
        if (JapaneseText.isJapaneseLine(line.text)) {
            line
        } else {
            line.copy(text = "", charBoxes = emptyList())
        }
    }
)

/**
 * ML Kit's lines inside [crop], as a throwaway index in the crop's own coordinates.
 *
 * This supplements a circle drawn before the screen index exists — while the scan is still running,
 * or when ML Kit found nothing to index. The crop is the selection, so every line ML Kit reads in
 * it is strictly inside the circle by construction. Null when it read nothing, and never read with
 * manga-ocr: the crop itself already was.
 */
private suspend fun cropIndex(crop: Bitmap): ScreenText? {
    val lines = JapaneseOcr.detectLines(crop)
    if (lines.isEmpty()) return null
    return ScreenText(
        lines.map { line ->
            val vertical = line.bounds.height > line.bounds.width * 1.5f
            ScreenLine(
                line.text,
                line.bounds,
                vertical,
                interpolateCharBoxes(line.text, line.bounds, vertical)
            )
        },
        crop.width,
        crop.height
    )
}

private fun clampedBox(screenshot: Bitmap, box: Box): Box {
    val left = box.left.coerceIn(0, screenshot.width - 1)
    val top = box.top.coerceIn(0, screenshot.height - 1)
    val right = box.right.coerceIn(left + 1, screenshot.width)
    val bottom = box.bottom.coerceIn(top + 1, screenshot.height)
    return Box(left, top, right, bottom)
}
