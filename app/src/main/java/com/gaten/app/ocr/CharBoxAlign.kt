/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * Puts a re-read line's characters back onto the boxes the detector drew for it.
 *
 * ML Kit finds the line and, when it can, one box per symbol; manga-ocr then reads the same line
 * again, and its reading rarely matches character for character — it spells `…` as three dots,
 * widens ASCII to full width, and now and then adds or drops a character. Rebuilding the line's
 * boxes from scratch would throw away the only per-symbol geometry on the screen, so the two
 * readings are aligned instead and every character the boxes already describe keeps its own: a tap
 * or a highlight then lands on the glyph the reader pointed at, not on the slice of the line box a
 * character count happened to fall on.
 *
 * The alignment is a plain Levenshtein edit distance over characters (lines are short, so the
 * O(n*m) table costs nothing): a substitution pairs a new character with an old one — the glyph did
 * not move, only the reading did — an insertion marks a character the detector never saw, and a
 * deletion drops an old box. Two characters are the same when they match after folding the
 * differences that are not really differences: full-width ASCII to half-width, the ideographic space
 * to a space, and the middle dot to a full stop. An ellipsis is spelled out before the alignment
 * runs, so `…` becomes three dots sharing its single box — without that one character becoming three
 * would shift every box after it.
 *
 * Characters with no old partner are placed in the gap their neighbours left along the line's own
 * axis (x for a horizontal line, y for a tategaki one); a gap open at one end runs to the line's
 * [Box]. The result is one box per character of the new text, inside the line's bounds and at least
 * a pixel on each axis, so [ScreenHits] can always address the text it is given.
 *
 * Pure Kotlin; no Android, so the alignment is unit-tested on the JVM.
 */
object CharBoxAlign {

    /**
     * One box per UTF-16 character of [newText], inherited from [oldBoxes] wherever the two texts
     * agree.
     *
     * [oldText]/[oldBoxes] are the detector's reading of the line and its per-character boxes,
     * [newText] the second reader's string, [bounds] the line's own box that a character with no
     * counterpart is placed inside of, and [vertical] the line's axis. When the boxes do not
     * describe [oldText] at all there is nothing to align to and the even split is the fallback.
     *
     * A placeholder line from the second detector ([ScreenTextScanner]) has no reading and no boxes
     * at all: there is no geometry to keep, so the reader's own text is simply tiled over the
     * line's box, one character per even slice.
     */
    fun align(
        oldText: String,
        oldBoxes: List<Box>,
        newText: String,
        bounds: Box,
        vertical: Boolean
    ): List<Box> {
        // A meiki-only placeholder: no reading, no boxes, nothing to align to — the new text is
        // tiled over the whole line box exactly as [interpolateCharBoxes] does it.
        if (oldText.isEmpty()) return interpolateCharBoxes(newText, bounds, vertical)
        if (oldBoxes.size != oldText.length) return interpolateCharBoxes(newText, bounds, vertical)
        // Nothing has to move: the boxes already address this text, exactly as the detector drew
        // them. Equal length covers "the reading did not change" with it, and is the common case.
        if (newText.length == oldText.length) return oldBoxes

        val (oldChars, boxes) = expandEllipses(oldText, oldBoxes, vertical)
        val paired = pairUp(oldChars, newText)

        val result = ArrayList<Box>(newText.length)
        var index = 0
        while (index < newText.length) {
            val oldIndex = paired[index]
            if (oldIndex >= 0) {
                result.add(clamped(boxes[oldIndex], bounds))
                index++
                continue
            }
            // A run of inserted characters shares the one gap between its neighbours.
            var end = index
            while (end < newText.length && paired[end] < 0) end++
            val next = paired.getOrNull(end)?.takeIf { it >= 0 }?.let { clamped(boxes[it], bounds) }
            val previous = result.lastOrNull()
            val count = end - index
            if (gapSize(previous, next, bounds, vertical) >= count * minimumSize(boxes, vertical)) {
                result.addAll(gapBoxes(previous, next, count, bounds, vertical))
                index = end
                continue
            }
            // The detector's boxes touch, so the gap is too thin to tap: the run shares the span of
            // its neighbours with them instead, and the neighbours shrink to make room.
            val from = previous?.let { if (vertical) it.top else it.left }
                ?: if (vertical) bounds.top else bounds.left
            val to = next?.let { if (vertical) it.bottom else it.right }
                ?: if (vertical) bounds.bottom else bounds.right
            if (previous != null) result.removeAt(result.lastIndex)
            val shared = count + (if (previous != null) 1 else 0) + (if (next != null) 1 else 0)
            val cells = List(shared) { cell ->
                val start = from + (to - from) * cell / shared
                val stop = from + (to - from) * (cell + 1) / shared
                if (vertical) clamped(Box(bounds.left, start, bounds.right, stop), bounds)
                else clamped(Box(start, bounds.top, stop, bounds.bottom), bounds)
            }
            result.addAll(cells)
            index = if (next != null) end + 1 else end
        }
        return result
    }

    /** The pixels between [previous] and [next] along the line's axis, the line's edge standing in for a missing one. */
    private fun gapSize(previous: Box?, next: Box?, bounds: Box, vertical: Boolean): Int {
        val from = if (vertical) previous?.bottom ?: bounds.top else previous?.right ?: bounds.left
        val to = if (vertical) next?.top ?: bounds.bottom else next?.left ?: bounds.right
        return to - from
    }

    /** Half the median character size along the line's axis: the least an inserted character may get. */
    private fun minimumSize(boxes: List<Box>, vertical: Boolean): Int {
        if (boxes.isEmpty()) return 1
        val sizes = boxes.map { if (vertical) it.height else it.width }.sorted()
        return (sizes[sizes.size / 2] / 2).coerceAtLeast(1)
    }

    /**
     * [count] boxes tiling the space between two placed characters along the line's axis.
     *
     * A character the detector never saw has no box of its own, but the reader still has to be able
     * to tap it: between [previous] and [next] is exactly where it sits, so the pixels they left are
     * cut up and shared out. A neighbour missing at one end means the run starts or ends at the
     * line's own edge, and the cross axis is always the line's, so an inserted box is never taller
     * (or wider) than the line it belongs to.
     */
    private fun gapBoxes(previous: Box?, next: Box?, count: Int, bounds: Box, vertical: Boolean): List<Box> {
        val from = if (vertical) previous?.bottom ?: bounds.top else previous?.right ?: bounds.left
        val to = if (vertical) next?.top ?: bounds.bottom else next?.left ?: bounds.right
        return List(count) { index ->
            val start = from + (to - from) * index / count
            val end = from + (to - from) * (index + 1) / count
            if (vertical) clamped(Box(bounds.left, start, bounds.right, end), bounds)
            else clamped(Box(start, bounds.top, end, bounds.bottom), bounds)
        }
    }

    /**
     * The old character each new character stands for, or -1 where the new text inserted one.
     *
     * Textbook Levenshtein: the table holds the cheapest way to turn a prefix of the old text into a
     * prefix of the new one, at cost 1 for a substitution, an insertion or a deletion and 0 for a
     * match, and the walk back from the far corner reads the pairs off it. Ties go to the diagonal,
     * which is what keeps a changed character on its own old box: treating it as an insertion
     * instead would pair it with its neighbour and shift every box after it by one — the exact
     * failure this exists to avoid.
     */
    private fun pairUp(oldText: String, newText: String): IntArray {
        val rows = oldText.length
        val columns = newText.length
        val cost = Array(rows + 1) { IntArray(columns + 1) }
        for (row in 0..rows) cost[row][0] = row
        for (column in 0..columns) cost[0][column] = column
        for (row in 1..rows) {
            for (column in 1..columns) {
                cost[row][column] = minOf(
                    cost[row - 1][column - 1] + substitution(oldText[row - 1], newText[column - 1]),
                    cost[row - 1][column] + 1,
                    cost[row][column - 1] + 1
                )
            }
        }

        val paired = IntArray(columns) { -1 }
        var row = rows
        var column = columns
        while (row > 0 && column > 0) {
            val diagonal = cost[row - 1][column - 1] + substitution(oldText[row - 1], newText[column - 1])
            when {
                cost[row][column] == diagonal -> {
                    paired[column - 1] = row - 1
                    row--
                    column--
                }
                cost[row][column] == cost[row - 1][column] + 1 -> row--
                // An insertion: the new character stays at -1 and takes a box from its gap.
                else -> column--
            }
        }
        // Whatever is left of the new text is an insertion at its head, already -1.
        return paired
    }

    /** 0 when the two characters are one glyph the two readers wrote differently, 1 when not. */
    private fun substitution(old: Char, new: Char): Int = if (canonical(old) == canonical(new)) 0 else 1

    /**
     * The character a comparison looks at: full-width ASCII folded to half-width (`ＡＢＣ１` reads as
     * `ABC1`), the ideographic space to a space, and the middle dot to a full stop, so the dot run
     * manga-ocr writes for an ellipsis matches the dots the ellipsis was spelled out as. Kana is
     * left alone — hiragana and katakana are different characters, and folding one into the other
     * would put a box on the wrong glyph.
     */
    private fun canonical(char: Char): Char {
        val code = char.code
        return when {
            code in 0xFF01..0xFF5E -> (code - 0xFEE0).toChar()
            char == '\u3000' -> ' '
            char == '・' -> '.'
            else -> char
        }
    }

    /**
     * [text] with every ellipsis spelled out as dots, and [boxes] split the same way: `…` becomes
     * three characters and `‥` two, each owning an equal slice of the single box the ellipsis held.
     *
     * This is what makes the alignment see through the one difference that is not a difference at
     * all, and it has to happen before the edit distance: a `…` that came back as `...` is one old
     * character against three new ones, and a plain alignment would shift every box after it.
     */
    private fun expandEllipses(text: String, boxes: List<Box>, vertical: Boolean): Pair<String, List<Box>> {
        if (text.none { it == '…' || it == '‥' }) return text to boxes

        val chars = StringBuilder(text.length)
        val expanded = ArrayList<Box>(boxes.size)
        text.forEachIndexed { index, char ->
            val dots = when (char) {
                '…' -> 3
                '‥' -> 2
                else -> 0
            }
            if (dots == 0) {
                chars.append(char)
                expanded.add(boxes[index])
            } else {
                repeat(dots) { chars.append('.') }
                expanded.addAll(interpolateCharBoxes(".".repeat(dots), boxes[index], vertical))
            }
        }
        return chars.toString() to expanded
    }

    /** [box] pulled inside [bounds] and left at least a pixel wide and tall. */
    private fun clamped(box: Box, bounds: Box): Box {
        val left = box.left.coerceIn(bounds.left, (bounds.right - 1).coerceAtLeast(bounds.left))
        val right = box.right.coerceIn(left + 1, bounds.right.coerceAtLeast(left + 1))
        val top = box.top.coerceIn(bounds.top, (bounds.bottom - 1).coerceAtLeast(bounds.top))
        val bottom = box.bottom.coerceIn(top + 1, bounds.bottom.coerceAtLeast(top + 1))
        return Box(left, top, right, bottom)
    }
}
