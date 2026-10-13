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
import com.gaten.app.ocr.ScreenLine
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.withText

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
 * The full line is repaired and scanned, then ranked nearest the tap first — the reader picked a
 * line, not a position, so the word under the finger heads the list and the scan's complex-first
 * order survives as ties. The highlight stays on the line bounds even when no dictionary word
 * matches. Returns null when no visible box is within [tolerancePx].
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
        .scan(text, cachedLookup, maxWords)
        .map { it.copy(context = text) }
    return TapLookup(
        LookupResult(ocrText = text, words = rankWordsByTapDistance(words, line, x, y)),
        line.bounds
    )
}

/**
 * Ranks [words] nearest the tapped point first: a word's distance is the smallest squared gap
 * from ([x], [y]) to any glyph box inside its span, so the word under the finger (distance 0)
 * heads the list and a tap just outside the line still orders by the nearest edge — the metric is
 * plain 2D rectangles, so horizontal and tategaki lines both work. Ties keep the list's own order,
 * which is the scan's complex-first ranking. A word's spans address its own line reading
 * ([ScannedWord.context]), while [line]'s boxes address the index's reading, so the spans are
 * mapped onto the geometry with [ScreenLine.withText], aligned once per distinct context. Words
 * with no placeable span sort last. Pure: boxes in, order out — no OCR, no dictionary work.
 */
internal fun rankWordsByTapDistance(
    words: List<ScannedWord>,
    line: ScreenLine,
    x: Int,
    y: Int
): List<ScannedWord> {
    if (words.size < 2) return words
    val alignedBoxes = HashMap<String, List<Box>>(2)
    return words
        .mapIndexed { index, word ->
            val boxes = alignedBoxes.getOrPut(word.context) {
                line.withText(word.context).charBoxes
            }
            Triple(tapDistanceSq(boxes, word.start, word.end, x, y), index, word)
        }
        .sortedWith(compareBy({ it.first }, { it.second }))
        .map { it.third }
}

/** Smallest squared distance from ([x], [y]) to the boxes covering [start] until [end]; a span with no boxes sorts last. */
private fun tapDistanceSq(boxes: List<Box>, start: Int, end: Int, x: Int, y: Int): Long {
    val from = start.coerceIn(0, boxes.size)
    val to = end.coerceIn(from, boxes.size)
    if (to <= from) return Long.MAX_VALUE
    var best = Long.MAX_VALUE
    for (index in from until to) {
        val box = boxes[index]
        val dx = maxOf(box.left - x, 0, x - box.right)
        val dy = maxOf(box.top - y, 0, y - box.bottom)
        val distance = dx.toLong() * dx + dy.toLong() * dy
        if (distance < best) best = distance
    }
    return best
}
