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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A tap answered from the screen index: every word in the tapped line and the box to highlight. */
data class TapLookup(val result: LookupResult, val selectionBox: Box)

/**
 * The phrase window a tap reads with the model: the [Box] the crop is cut from, and the tapped
 * line's character-cell span it covers. [start]/[endExclusive] index the line's own text —
 * character cells, so a surrogate pair is never split.
 */
internal data class TapPhrase(val bounds: Box, val start: Int, val endExclusive: Int)

/**
 * Selects up to twelve cells around the nearest glyph of the chosen line, extending boundaries
 * to keep shared glyphs and surrogate pairs whole. Restores detector slack plus a cross-axis
 * margin, clips added space against nearby text, and returns null if screenshot bounds lose ink.
 */
internal fun tapPhraseCrop(screen: ScreenText, lineIndex: Int, x: Int, y: Int): TapPhrase? {
    val line = screen.lines.getOrNull(lineIndex) ?: return null
    if (screen.width <= 0 || screen.height <= 0 || line.text.isEmpty() ||
        line.charBoxes.size != line.text.length
    ) return null
    // Work in horizontal coordinates for both orientations: x is along the line, y across it.
    val boxes = line.charBoxes.map { it.forLineAxis(line.vertical) }
    if (boxes.any { it.width <= 0 || it.height <= 0 }) return null
    val tapX = if (line.vertical) y else x
    val tapY = if (line.vertical) x else y
    val cell = boxes.indices.minByOrNull { pointDistanceSq(boxes[it], tapX, tapY) } ?: return null
    var start = (cell - 5).coerceIn(0, (boxes.size - 12).coerceAtLeast(0))
    var end = min(boxes.size, start + 12)
    var glyphs: Box
    while (true) {
        val oldStart = start
        val oldEnd = end
        if (start > 0 && line.text[start].isLowSurrogate() && line.text[start - 1].isHighSurrogate()) start--
        if (end < boxes.size && line.text[end - 1].isHighSurrogate() && line.text[end].isLowSurrogate()) end++
        val selected = boxes.subList(start, end)
        glyphs = Box(selected.minOf { it.left }, selected.minOf { it.top },
            selected.maxOf { it.right }, selected.maxOf { it.bottom })
        if (start > 0 && boxes[start - 1].right > glyphs.left && boxes[start - 1].left < glyphs.right) start--
        if (end < boxes.size && boxes[end].left < glyphs.right && boxes[end].right > glyphs.left) end++
        if (start == oldStart && end == oldEnd) break
    }
    val detector = line.detectorBounds.forLineAxis(line.vertical)
    val detectorValid = detector.width > 0 && detector.height > 0
    val pad = max(2, ((if (detectorValid) detector.height else glyphs.height) * 0.1).roundToInt())
    val detectorContainsGlyphs = detectorValid && detector.top <= glyphs.top && detector.bottom >= glyphs.bottom
    val crossStart = if (detectorContainsGlyphs) detector.top else glyphs.top
    val crossEnd = if (detectorContainsGlyphs) detector.bottom else glyphs.bottom
    var low = crossStart - pad
    var high = crossEnd + pad
    var mainStart = glyphs.left - 2
    var mainEnd = glyphs.right + 2
    if (start > 0) mainStart = max(mainStart, (boxes[start - 1].right + glyphs.left) / 2)
    if (end < boxes.size) mainEnd = min(mainEnd, (glyphs.right + boxes[end].left) / 2)
    for ((index, neighbour) in screen.lines.withIndex()) {
        if (index == lineIndex) continue
        val other = neighbour.detectorBounds.forLineAxis(line.vertical)
        if (other.width <= 0 || other.height <= 0 || other.left >= mainEnd || other.right <= mainStart) continue
        when {
            other.bottom <= glyphs.top -> {
                val boundary = max(other.bottom, (other.bottom + crossStart) / 2)
                low = max(low, min(glyphs.top, boundary))
            }
            other.top >= glyphs.bottom -> {
                val boundary = min(other.top, (crossEnd + other.top) / 2)
                high = min(high, max(glyphs.bottom, boundary))
            }
            else -> {
                // Overlapping detector extents leave no dependable space for extra padding.
                low = glyphs.top
                high = glyphs.bottom
            }
        }
    }
    val bounds = if (line.vertical) {
        Box(low, mainStart, high, mainEnd)
    } else {
        Box(mainStart, low, mainEnd, high)
    }
    val clamped = Box(bounds.left.coerceIn(0, screen.width), bounds.top.coerceIn(0, screen.height),
        bounds.right.coerceIn(0, screen.width), bounds.bottom.coerceIn(0, screen.height))
    val ink = if (line.vertical) Box(glyphs.top, glyphs.left, glyphs.bottom, glyphs.right) else glyphs
    if (clamped.width <= 0 || clamped.height <= 0 || clamped.left > ink.left || clamped.top > ink.top ||
        clamped.right < ink.right || clamped.bottom < ink.bottom
    ) return null
    return TapPhrase(clamped, start, end)
}

/** Transposes a vertical line so geometry can use the same main/cross axes as a horizontal line. */
private fun Box.forLineAxis(vertical: Boolean): Box = if (vertical) Box(top, left, bottom, right) else this

/** Squared distance to the nearest rectangle edge; zero inside it. */
private fun pointDistanceSq(box: Box, x: Int, y: Int): Long {
    val dx = maxOf(box.left.toLong() - x, 0L, x.toLong() - box.right)
    val dy = maxOf(box.top.toLong() - y, 0L, y.toLong() - box.bottom)
    return dx * dx + dy * dy
}

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
    if (maxWords <= 0) return TapLookup(LookupResult(text, emptyList()), line.bounds)
    // Rank before the cap and the dedup: the cap must fall on the ranked list, or the words
    // nearest the tap on a long line are cut by words that merely scanned earlier.
    val words = WordScanner
        .scan(text, cachedLookup, max(24, text.length))
        .map { it.copy(context = text) }
    val ranked = rankWordsByTapDistance(words, line, x, y)
        .distinctBy { it.surface }
        .take(maxWords)
    return TapLookup(LookupResult(ocrText = text, words = ranked), line.bounds)
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
 *
 * [preferredStart]/[preferredEndExclusive], when not both 0, are a span of [line]'s own text —
 * the crop the model read itself. Words overlapping it tie-break ahead of words outside it, so
 * the tapped phrase keeps its lead even against equally distant words read from the line alone.
 */
internal fun rankWordsByTapDistance(
    words: List<ScannedWord>,
    line: ScreenLine,
    x: Int,
    y: Int,
    preferredStart: Int = 0,
    preferredEndExclusive: Int = 0
): List<ScannedWord> {
    if (words.size < 2) return words
    val hasPreferred = preferredStart < preferredEndExclusive
    val alignedBoxes = HashMap<String, List<Box>>(2)
    return words
        .mapIndexed { index, word ->
            val boxes = alignedBoxes.getOrPut(word.context) {
                line.withText(word.context).charBoxes
            }
            val distance = tapDistanceSq(boxes, word.start, word.end, x, y)
            // The preferred span addresses the line's own text, while a word's span addresses its
            // context; every caller that passes one ranks against words whose context IS the
            // line's text, so the overlap test reads the boxes that span maps to.
            val preferred = hasPreferred && word.context == line.text &&
                word.start < preferredEndExclusive && preferredStart < word.end
            Ranked(distance, if (preferred) 0 else 1, index, word)
        }
        .sortedWith(compareBy({ it.distance }, { it.preferred }, { it.order }))
        .map { it.word }
}

/** One word's ranking keys, kept together for the comparator. */
private data class Ranked(
    val distance: Long,
    val preferred: Int,
    val order: Int,
    val word: ScannedWord
)

/** Smallest squared distance from ([x], [y]) to the boxes covering [start] until [end]; a span with no boxes sorts last. */
private fun tapDistanceSq(boxes: List<Box>, start: Int, end: Int, x: Int, y: Int): Long {
    val from = start.coerceIn(0, boxes.size)
    val to = end.coerceIn(from, boxes.size)
    if (to <= from) return Long.MAX_VALUE
    var best = Long.MAX_VALUE
    for (index in from until to) {
        val distance = pointDistanceSq(boxes[index], x, y)
        if (distance < best) best = distance
    }
    return best
}
