/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.WordScanner
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.DetectionMerge
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.MeikiDetector
import com.gaten.app.ocr.OcrLine
import com.gaten.app.ocr.OcrRepair
import com.gaten.app.ocr.RegionReadPolicy
import com.gaten.app.ocr.SelectionSnap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the result sheet needs after one lasso lookup. */
data class LookupResult(
    val ocrText: String,
    val words: List<ScannedWord>,
    val error: String? = null
)

/**
 * One full lookup: snap the gesture box to the OCR text lines, repair each line, then scan the
 * text into dictionary words. Both stages are CPU/database work and run off the main thread.
 * [repo] is null when the dictionary failed to open; the caller turns that into
 * [LookupResult.error], and the repair — which is dictionary-checked — is skipped.
 */
suspend fun runLookup(
    context: Context,
    screenshot: Bitmap,
    selection: Rect,
    repo: JmdictRepository?
): LookupResult {
    val lines = withContext(Dispatchers.Default) {
        snapToLines(screenshot, selection, context)
    }
    // One character for one, so the repaired text still lines up with everything the region path
    // reported. The joined text is what gets copied and what the dictionary sees.
    val text = withContext(Dispatchers.Default) {
        if (repo == null) lines else lines.map { OcrRepair.repair(it) { term -> repo.lookup(term) } }
    }.joinToString("\n")
    val words = if (repo == null) {
        emptyList()
    } else {
        withContext(Dispatchers.Default) {
            WordScanner.scan(text, lookup = { term -> repo.lookup(term) })
        }
    }
    return LookupResult(ocrText = text, words = words)
}

/**
 * Recognises a generous region around the user's gesture, then snaps to the text lines it touched.
 *
 * This is what makes a near-miss forgiving: circling 猫が水を飲む a character short used to lose
 * the verb entirely, because the tight bounding box of the trail simply did not contain it. Recognising
 * wider and keeping only the lines the selection overlaps returns the whole line the user meant.
 * One text per line, so the caller can repair each line before the lines are joined.
 *
 * When ML Kit found no Japanese in the region, the lines it did report are not a reading of the
 * text the reader circled: the selection box goes to manga-ocr instead ([RegionReadPolicy]), so a
 * stylized line ML Kit read as garbage still reaches the reader. The optional meiki model is run
 * over the region too ([MeikiDetector], [DetectionMerge]); the boxes it adds beyond ML Kit's are
 * read by manga-ocr directly, since they are precisely the lettering ML Kit never read at all.
 *
 * @return the snapped lines' text in reading order (or whatever was recognised when nothing
 *   overlapped)
 */
private suspend fun snapToLines(
    screenshot: Bitmap,
    selection: Rect,
    context: Context
): List<String> {
    val box = Box(
        selection.left.toInt(),
        selection.top.toInt(),
        selection.right.toInt(),
        selection.bottom.toInt()
    )
    val region = SelectionSnap.searchRegion(
        box,
        SelectionSnap.horizontalMargin(box, screenshot.width),
        SelectionSnap.verticalMargin(box),
        screenshot.width,
        screenshot.height
    )

    val regionCrop = cropBitmap(screenshot, region)
    val detected = JapaneseOcr.detectLines(regionCrop)
    // Meiki sees the lettering ML Kit's line detector misses; its boxes beyond ML Kit's become
    // empty candidate lines, which manga-ocr fills in during the read below. One meiki pass per
    // lookup: the merge runs here and never again. With no model installed the meiki lines are
    // never read (they stay empty) and are filtered out, so the behaviour is exactly as before.
    val meikiBoxes = DetectionMerge.merge(
        detected.map { it.bounds },
        MeikiDetector.detect(context, regionCrop)
    ).added
    val candidates = detected + meikiBoxes.map { OcrLine("", it) }
    val read = JapaneseOcr.readLines(context, regionCrop, candidates)
        .filter { it.text.isNotEmpty() }

    // ML Kit found Japanese — in its own lines, or in a meiki box manga-ocr read: those lines are
    // what the reader gets. When neither found any, the stylized glyphs ML Kit saw as something
    // else are exactly what the reader circled, so the selection box itself goes to manga-ocr
    // instead.
    val onScreen = if (RegionReadPolicy.hasJapaneseLines(read)) {
        SelectionSnap.translate(read, region.left, region.top)
    } else {
        RegionReadPolicy.fallbackLines(
            SelectionSnap.translate(detected, region.left, region.top),
            box,
            JapaneseOcr.readLineWithMangaOcr(context, screenshot, box)
        )
    }

    val chosen = SelectionSnap.selectLines(onScreen, box)
    // Nothing overlapped (nothing recognised, or a gesture between lines): keep whatever was read.
    val used = chosen.ifEmpty { onScreen }

    return used.map { it.text }
}

/** Crops [box] (bitmap px) out of the capture, clamped so the result is never empty. */
private fun cropBitmap(screenshot: Bitmap, box: Box): Bitmap {
    val left = box.left.coerceIn(0, screenshot.width - 1)
    val top = box.top.coerceIn(0, screenshot.height - 1)
    val right = box.right.coerceIn(left + 1, screenshot.width)
    val bottom = box.bottom.coerceIn(top + 1, screenshot.height)
    return Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top)
}