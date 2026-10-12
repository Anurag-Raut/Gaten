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
import com.gaten.app.ocr.JapaneseText
import com.gaten.app.ocr.MeikiDetector
import com.gaten.app.ocr.OcrLine
import com.gaten.app.ocr.OcrRepair
import com.gaten.app.ocr.RegionReadPolicy
import com.gaten.app.ocr.SelectionSnap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * The answer is only what was circled. Lines recognised in the region are kept only when they
 * overlap the drawn box ([SelectionSnap.selectLines] — no nearest-line fallback) and only when
 * their reading has Japanese in it ([JapaneseText.isJapaneseLine]), so a sloppy circle cannot
 * answer with text from elsewhere on the screen and ML Kit's Latin junk cannot reach the
 * dictionary. When nothing Japanese overlaps — nothing recognised, or a gesture between lines —
 * the box the user DREW goes to manga-ocr instead ([RegionReadPolicy.fallbackLines]), and its
 * result is filtered to Japanese by the same rule. The optional meiki model is run over the region
 * too ([MeikiDetector], [DetectionMerge]); the boxes it adds beyond ML Kit's are read by manga-ocr
 * directly, since they are precisely the lettering ML Kit never read at all.
 *
 * @return the snapped lines' text in reading order; empty when nothing Japanese overlapped and the
 *   drawn box itself yielded nothing readable
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
    // ML Kit and meiki are independent detectors over the same crop, so they run at once; the
    // merge needs ML Kit's boxes, so it waits for that half. Meiki sees the lettering ML Kit's
    // line detector misses; its boxes beyond ML Kit's become empty candidate lines, which
    // manga-ocr fills in during the read below. One meiki pass per lookup: the merge runs here
    // and never again. With no model installed the meiki lines are never read (they stay empty)
    // and are filtered out, so the behaviour is exactly as before.
    val detected: List<OcrLine>
    val candidates: List<OcrLine>
    coroutineScope {
        val mlKit = async { JapaneseOcr.detectLines(regionCrop) }
        val meiki = async { MeikiDetector.detect(context, regionCrop) }
        detected = mlKit.await()
        candidates = detected + DetectionMerge.merge(detected.map { it.bounds }, meiki.await())
            .added
            .map { OcrLine("", it) }
    }
    val read = JapaneseOcr.readLines(context, regionCrop, candidates)
        .filter { it.text.isNotEmpty() }

    // The answer is only what the user circled: the lines read in the region go back through the
    // overlap filter ([SelectionSnap.selectLines]), so text from outside the drawn box — or ML
    // Kit's Latin junk — can never reach the dictionary. When nothing Japanese overlaps (nothing
    // recognised, or a gesture between lines), the box the user DREW goes to manga-ocr instead
    // ([RegionReadPolicy.fallbackLines]); its result is filtered to Japanese for the same reason,
    // and `used` may end up empty — the caller already handles "no text".
    val japanese = read.filter { JapaneseText.isJapaneseLine(it.text) }
    val chosen = if (japanese.isNotEmpty()) {
        SelectionSnap.selectLines(SelectionSnap.translate(japanese, region.left, region.top), box)
    } else {
        emptyList()
    }
    val used = chosen.ifEmpty {
        RegionReadPolicy.fallbackLines(
            SelectionSnap.translate(detected, region.left, region.top),
            box,
            JapaneseOcr.readLineWithMangaOcr(context, screenshot, box)
        ).filter { JapaneseText.isJapaneseLine(it.text) }
    }
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