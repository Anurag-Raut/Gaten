/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/** One character of the index: which line, and which index of that line's text. */
data class CharHit(val lineIndex: Int, val charIndex: Int)

/** A run of characters inside one line's text, i.e. `text.substring(start, endExclusive)`. */
data class LineSpan(val lineIndex: Int, val start: Int, val endExclusive: Int)

/**
 * Answers a gesture from the pre-recognised screen index instead of another OCR round-trip.
 *
 * A tap lands wherever the finger was, never on the character the reader meant, and a lasso is
 * drawn by hand. Taps select the visible line bounds with enough slack for a near miss; character
 * hit tests remain available for narrower selections. A lasso uses character boxes with no slack,
 * because what a circle reads is the crop the reader drew and the index only supplements it. The index is
 * in bitmap pixels, so every decision here is arithmetic on integers; nothing touches Android.
 */
object ScreenHits {

    /**
     * The visible line box under the tap, or the nearest one within [tolerancePx].
     * Empty lines have no outline and are ignored; ties keep the earlier line.
     */
    fun hitTestLine(screen: ScreenText, x: Int, y: Int, tolerancePx: Int): Int? {
        var hit: Int? = null
        var bestDistance = Int.MAX_VALUE
        screen.lines.forEachIndexed { lineIndex, line ->
            if (line.charBoxes.isNotEmpty()) {
                val distance = squaredPointDistance(x, y, line.bounds)
                if (distance < bestDistance) {
                    bestDistance = distance
                    hit = lineIndex
                }
            }
        }
        return if (bestDistance <= tolerancePx * tolerancePx) hit else null
    }

    /**
     * The character under `(x, y)`, or the nearest one no further than [tolerancePx] away
     * ([Box]'s own rectangle distance, 0 for a point inside a box).
     *
     * A tie goes to the earlier line and the earlier character, so the answer is stable rather than
     * whichever box happened to be visited first. Null when the tap landed nowhere near any
     * character, which is the caller's cue to answer with "no text here".
     */
    fun hitTest(screen: ScreenText, x: Int, y: Int, tolerancePx: Int): CharHit? {
        var hit: CharHit? = null
        var bestDistance = Int.MAX_VALUE

        screen.lines.forEachIndexed { lineIndex, line ->
            line.charBoxes.forEachIndexed { charIndex, box ->
                val distance = squaredPointDistance(x, y, box)
                if (distance < bestDistance) {
                    bestDistance = distance
                    hit = CharHit(lineIndex, charIndex)
                }
            }
        }

        return if (bestDistance <= tolerancePx * tolerancePx) hit else null
    }

    /**
     * The characters a lasso covered, as one span per line.
     *
     * The loop has to be at the character: a character box is selected when it shares pixels with
     * the selection, and nothing else is. There is no slack and no nearest-line fallback, because a
     * circle's answer is the crop the reader drew ([com.gaten.app.ui.lookup.runLookup]) and the
     * index only supplements it — a neighbouring glyph the loop never touched must not ride along.
     *
     * The span of a line is then the whole run from its first to its last selected character, so a
     * word the loop crossed in the middle is never delivered in pieces. Lines come back in index
     * order, spans within a line are contiguous, and indices address `ScreenLine.text`.
     */
    fun spansInSelection(screen: ScreenText, selection: Box): List<LineSpan> {
        val spans = ArrayList<LineSpan>()

        screen.lines.forEachIndexed { lineIndex, line ->
            var first = -1
            var last = -1
            line.charBoxes.forEachIndexed { charIndex, box ->
                if (box.overlaps(selection)) {
                    if (first < 0) first = charIndex
                    last = charIndex
                }
            }

            if (first >= 0) {
                spans.add(LineSpan(lineIndex, first, last + 1))
            }
        }

        return spans
    }

    /**
     * The lines a lasso should be read for, in index order and without duplicates.
     *
     * That is the lines whose characters it covered ([spansInSelection]), plus any line with no
     * character boxes yet whose own box it covers: a meiki-only placeholder has no characters to
     * intersect, and it is offered to the reader here so manga-ocr can fill it in. A line whose
     * boxes are present is judged on the boxes alone, so a box the loop did not reach is never
     * read for the sake of its line's loose bounds.
     */
    fun linesInSelection(screen: ScreenText, selection: Box): List<Int> {
        val lines = ArrayList<Int>()
        spansInSelection(screen, selection).forEach { lines.add(it.lineIndex) }
        screen.lines.forEachIndexed { lineIndex, line ->
            if (line.charBoxes.isEmpty() && line.bounds.overlaps(selection)) lines.add(lineIndex)
        }
        return lines.distinct().sorted()
    }

    /** Distance from the point to the box, 0 when it is inside it. */
    private fun squaredPointDistance(x: Int, y: Int, box: Box): Int {
        val dx = maxOf(box.left - x, x - box.right, 0)
        val dy = maxOf(box.top - y, y - box.bottom, 0)
        return dx * dx + dy * dy
    }
}
