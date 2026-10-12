/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ui.lookup

import com.yomilens.app.dict.DictEntry
import com.yomilens.app.dict.ScannedWord
import com.yomilens.app.dict.WordScanner
import com.yomilens.app.ocr.Box
import com.yomilens.app.ocr.OcrRepair
import com.yomilens.app.ocr.ScreenHits
import com.yomilens.app.ocr.ScreenText

/** A tap answered from the screen index: the words under the finger and the box to highlight. */
data class TapLookup(val result: LookupResult, val wordBox: Box)

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

    val words = ArrayList<ScannedWord>()
    val selected = ArrayList<String>(spans.size)
    for (span in spans) {
        val text = OcrRepair.repair(screen.lines[span.lineIndex].text, lookup)
        selected.add(text.substring(span.start, span.endExclusive))
        words.addAll(
            WordScanner.scanLine(text, span.start, span.endExclusive, lookup)
                .map { it.copy(context = text) }
        )
    }
    return LookupResult(ocrText = selected.joinToString("\n"), words = words.take(maxWords))
}

/**
 * Answers a tap from the pre-recognised screen index, with no further OCR.
 *
 * The tapped line is repaired and scanned from the word the reader meant ([resolveTapStart]
 * walks back from the tapped character, because a finger lands mid-word far more often than on
 * its first character); the first word returned is the tapped one and the rest of the line
 * follows as context. [wordBox] is the union of the first word's character boxes — the box the
 * overlay highlights. Null when the tap landed near no character at all, within [tolerancePx].
 */
fun lookupFromTap(
    screen: ScreenText,
    x: Int,
    y: Int,
    tolerancePx: Int,
    lookup: (String) -> List<DictEntry>,
    maxWords: Int = 24
): TapLookup? {
    val hit = ScreenHits.hitTest(screen, x, y, tolerancePx) ?: return null
    val line = screen.lines[hit.lineIndex]
    val text = OcrRepair.repair(line.text, lookup)
    val words = WordScanner
        .scanFrom(text, resolveTapStart(text, hit.charIndex, lookup), lookup, maxWords)
        .map { it.copy(context = text) }

    // No dictionary word on the line: the sheet answers "No dictionary words found", and the
    // highlight falls back to the tapped character itself.
    val wordBox = words.firstOrNull()?.let { unionOf(line.charBoxes.subList(it.start, it.end)) }
        ?: line.charBoxes[hit.charIndex]
    return TapLookup(LookupResult(ocrText = text, words = words), wordBox)
}

/** How far back from the tapped character a word may start: the scanner never matches past 12. */
private const val TAP_LOOKBACK = 11

/**
 * The index the tap's scan should really start at, when the tapped character sits mid-word.
 *
 * [WordScanner.scanFrom] only matches words that start at its index, and a tap on the ン of
 * コンピュータ would step forward and miss the word entirely. So probe backwards from the tap:
 * every start whose one-word scan begins exactly there and reaches past the tap is a candidate
 * cover of the tapped character, and the longest cover wins — with ties going to the earliest
 * start, so コンピュータ beats a shorter word that also happens to contain the tap. When nothing
 * covers the tap (a particle, or a gap between words) the tap index itself is returned and
 * [WordScanner.scanFrom]'s own step-forward takes over.
 */
internal fun resolveTapStart(text: String, tapIndex: Int, lookup: (String) -> List<DictEntry>): Int {
    var bestStart = -1
    var bestEnd = -1
    for (start in tapIndex downTo maxOf(0, tapIndex - TAP_LOOKBACK)) {
        val word = WordScanner.scanFrom(text, start, lookup, maxWords = 1).firstOrNull() ?: continue
        // scanFrom steps forward when nothing starts at [start], so a word that begins later
        // than the probe does not count as a cover of it.
        if (word.start != start || word.end <= tapIndex) continue
        // Probes run backwards, so on an equal end the later probe — the earlier start — replaces
        // the incumbent and the tie resolves to the earliest start.
        if (word.end >= bestEnd) {
            bestStart = start
            bestEnd = word.end
        }
    }
    return if (bestStart < 0) tapIndex else bestStart
}

/** The rectangle that encloses every one of [boxes]. */
private fun unionOf(boxes: List<Box>): Box = Box(
    boxes.minOf { it.left },
    boxes.minOf { it.top },
    boxes.maxOf { it.right },
    boxes.maxOf { it.bottom }
)
