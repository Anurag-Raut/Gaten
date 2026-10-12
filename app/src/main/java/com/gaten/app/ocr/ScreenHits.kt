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
 * drawn by hand — both are resolved against [ScreenText]'s per-character boxes, with enough slack
 * that a near miss still yields the character or the words that were pointed at. The index is in
 * bitmap pixels, so every decision here is arithmetic on integers; nothing touches Android.
 */
object ScreenHits {

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
     * A hand-drawn loop is never tight: it clips whole characters at its ends and, drawn around a
     * word, touches the neighbours it passed over. Each character box is therefore grown by half a
     * character before intersecting — the loop still has to be at the character, but ending on the
     * seam between two characters pulls the second one in rather than losing it. The span of a line
     * is then the whole run from its first to its last selected character, so a word the loop
     * crossed in the middle is never delivered in pieces. Lines come back in index order, spans
     * within a line are contiguous, and indices address `ScreenLine.text`.
     *
     * A loop drawn between the lines — or one that caught no character at all — falls back to the
     * single nearest line when it is within one and a half line-heights of it, and that whole line
     * is selected: the reader clearly aimed at that line, and half an answer beats none. Beyond
     * that reach the gesture is taken as touching nothing and the result is empty.
     */
    fun spansInSelection(screen: ScreenText, selection: Box): List<LineSpan> {
        val spans = ArrayList<LineSpan>()
        var nearestLine = -1
        var nearestDistance = Int.MAX_VALUE

        screen.lines.forEachIndexed { lineIndex, line ->
            if (line.charBoxes.isEmpty()) return@forEachIndexed

            var first = -1
            var last = -1
            line.charBoxes.forEachIndexed { charIndex, box ->
                if (inflated(box).overlaps(selection)) {
                    if (first < 0) first = charIndex
                    last = charIndex
                }
            }

            if (first >= 0) {
                spans.add(LineSpan(lineIndex, first, last + 1))
            } else {
                val distance = squaredRectDistance(line.bounds, selection)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    nearestLine = lineIndex
                }
            }
        }

        if (spans.isNotEmpty()) return spans

        val fallback = screen.lines.getOrNull(nearestLine) ?: return emptyList()
        // 1.5 line-heights of slack, compared squared to stay in integers. The line's thickness is
        // its short axis: the height of a normal line, the width of a tategaki one.
        val reach = minOf(fallback.bounds.width, fallback.bounds.height) * 3 / 2
        return if (nearestDistance <= reach * reach) {
            listOf(LineSpan(nearestLine, 0, fallback.text.length))
        } else {
            emptyList()
        }
    }

    /** [box] grown by half its own size on every side: the slack a hand-drawn loop gets. */
    private fun inflated(box: Box): Box = Box(
        box.left - box.width / 2,
        box.top - box.height / 2,
        box.right + box.width / 2,
        box.bottom + box.height / 2
    )

    /** Distance from the point to the box, 0 when it is inside it. */
    private fun squaredPointDistance(x: Int, y: Int, box: Box): Int {
        val dx = maxOf(box.left - x, x - box.right, 0)
        val dy = maxOf(box.top - y, y - box.bottom, 0)
        return dx * dx + dy * dy
    }

    /** Distance between the two boxes' nearest edges, 0 when they share any pixel. */
    private fun squaredRectDistance(a: Box, b: Box): Int {
        val dx = maxOf(a.left - b.right, b.left - a.right, 0)
        val dy = maxOf(a.top - b.bottom, b.top - a.bottom, 0)
        return dx * dx + dy * dy
    }
}
