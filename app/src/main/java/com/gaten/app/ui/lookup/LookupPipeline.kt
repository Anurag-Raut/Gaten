/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.WordScanner
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.JapaneseText
import com.gaten.app.ocr.OcrLinePolicy
import com.gaten.app.ocr.OcrRepair
import com.gaten.app.ocr.ScreenLine
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.interpolateCharBoxes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the result sheet needs after one lasso lookup. */
data class LookupResult(
    val ocrText: String,
    val words: List<ScannedWord>,
    val error: String? = null
)

/** The dictionary accessor when the dictionary failed to open: every word list comes back empty. */
private val NO_LOOKUP: (String) -> List<DictEntry> = { emptyList() }

/**
 * One circle lookup: the selection the reader drew is read by manga-ocr, and the index lines it
 * actually covered supplement that reading's words.
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
suspend fun runLookup(
    context: Context,
    screenshot: Bitmap,
    selection: Rect,
    repo: JmdictRepository?,
    index: suspend () -> ScreenText?
): LookupResult {
    val box = Box(
        selection.left.toInt(),
        selection.top.toInt(),
        selection.right.toInt(),
        selection.bottom.toInt()
    )
    val crop = cropBitmap(screenshot, box)
    val reading = withContext(Dispatchers.Default) {
        JapaneseOcr.readLineWithMangaOcr(context, crop, Box(0, 0, crop.width, crop.height))
    }

    return withContext(Dispatchers.Default) {
        val lookup: (String) -> List<DictEntry> =
            if (repo == null) NO_LOOKUP else { term -> repo.lookup(term) }
        val screen = index()
        val supplement = if (screen != null) {
            lookupFromSelection(japaneseLines(screen), box, lookup)
        } else {
            cropIndex(crop)?.let {
                lookupFromSelection(japaneseLines(it), Box(0, 0, crop.width, crop.height), lookup)
            }
        }
        mergeLookup(reading, supplement, lookup)
    }
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
    val cropText = acceptedReading(cropReading)?.let { OcrRepair.repair(it, lookup) }
    val cropWords = cropText?.let { text ->
        WordScanner.scan(text, lookup).map { it.copy(context = text) }
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

/** Crops [box] (bitmap px) out of the capture, clamped so the result is never empty. */
private fun cropBitmap(screenshot: Bitmap, box: Box): Bitmap {
    val left = box.left.coerceIn(0, screenshot.width - 1)
    val top = box.top.coerceIn(0, screenshot.height - 1)
    val right = box.right.coerceIn(left + 1, screenshot.width)
    val bottom = box.bottom.coerceIn(top + 1, screenshot.height)
    return Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top)
}
