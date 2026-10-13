/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.WordScanner
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.OcrRepair
import com.gaten.app.ocr.ScreenHits
import com.gaten.app.ocr.ScreenText

/** A tap answered from the screen index: every word in the tapped box and the box to highlight. */
data class TapLookup(val result: LookupResult, val selectionBox: Box)

/** Reuses exact-term results for one lookup operation, including empty results. */
internal fun memoizedLookup(lookup: (String) -> List<DictEntry>): (String) -> List<DictEntry> {
    val results = HashMap<String, List<DictEntry>>()
    return { term -> results.getOrPut(term) { lookup(term) } }
}

/**
 * Answers a lasso from the pre-recognised screen index, with no further OCR.
 *
 * The spans the selection covered are scanned one line at a time — the line is scanned entire so
 * that context (ます behind 飲み) survives, then only the words the span touches are kept — and
 * the lines are concatenated in reading order. Each line's text is repaired with [OcrRepair]
 * first: one character for one, so the spans still address the repaired text. Every word carries
 * its repaired line as [ScannedWord.context], which is what the Anki export shows. At most
 * [maxWords] are returned.
 */
fun lookupFromSelection(
    screen: ScreenText,
    selection: Box,
    lookup: (String) -> List<DictEntry>,
    maxWords: Int = 24
): LookupResult {
    val spans = ScreenHits.spansInSelection(screen, selection)
    if (spans.isEmpty()) return LookupResult(ocrText = "", words = emptyList())

    val cachedLookup = memoizedLookup(lookup)
    val words = ArrayList<ScannedWord>()
    val selected = ArrayList<String>(spans.size)
    for (span in spans) {
        val text = OcrRepair.repair(screen.lines[span.lineIndex].text, cachedLookup)
        selected.add(text.substring(span.start, span.endExclusive))
        words.addAll(
            WordScanner.scanLine(text, span.start, span.endExclusive, cachedLookup)
                .map { it.copy(context = text) }
        )
    }
    return LookupResult(ocrText = selected.joinToString("\n"), words = words.take(maxWords))
}

/**
 * Selects the entire visible line box under a tap, with no further OCR.
 * The full line is repaired and scanned in reading order, including words before the tap.
 * The highlight stays on the line bounds even when no dictionary word matches.
 * Returns null when no visible box is within [tolerancePx].
 */
fun lookupFromTap(
    screen: ScreenText,
    x: Int,
    y: Int,
    tolerancePx: Int,
    lookup: (String) -> List<DictEntry>,
    maxWords: Int = 24
): TapLookup? {
    val lineIndex = ScreenHits.hitTestLine(screen, x, y, tolerancePx) ?: return null
    val line = screen.lines[lineIndex]
    val cachedLookup = memoizedLookup(lookup)
    val text = OcrRepair.repair(line.text, cachedLookup)
    val words = WordScanner
        .scanFrom(text, 0, cachedLookup, maxWords)
        .map { it.copy(context = text) }
    return TapLookup(LookupResult(ocrText = text, words = words), line.bounds)
}
