/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * One whole line of a gesture's selection, and where it sits inside [SelectionText.text].
 *
 * [lineIndex] addresses [ScreenText.lines] and stays valid for the overlay's lifetime, because a
 * line is only ever dropped in place ([ScreenText.withDroppedLine]) and new lines are only ever
 * appended. [start]/[endExclusive] are UTF-16 offsets into [SelectionText.text], so
 * `text.substring(start, endExclusive)` is exactly this line's repaired text.
 */
data class SelectedLine(
    val lineIndex: Int,
    val start: Int,
    val endExclusive: Int,
    /** Capture pixels: the line's outline, for a highlight or a sheet anchor through `ViewMapping`. */
    val bounds: Box,
    val vertical: Boolean
)

/**
 * The exact source text of one gesture: the whole lines it touched, in reading order, with the
 * offsets that address each of them. No dictionary is involved anywhere in producing it, so a
 * selection still has its text when the dictionary failed to open.
 *
 * `lines` is empty for text that has no line structure at all — a circle read before the screen
 * index existed, whose whole answer is one unanchored model reading.
 */
data class SelectionText(val text: String, val lines: List<SelectedLine>)

/**
 * The lines a gesture touched, in reading order. Pure: boxes in, order out, no OCR, no dictionary.
 *
 * The order is derived from geometry, because the index does not carry one: ML Kit's lines come
 * back in the order it reported them ([ScreenTextScanner]), meiki's are appended after them
 * ([ScreenLineReader.append]), and nothing re-derives tategaki columns.
 *
 * - Horizontal lines read in rows: top to bottom, and left to right inside a row. A row ends at
 *   the first line whose top does not reach into it, which is what keeps two side-by-side blocks
 *   from interleaving by x alone.
 * - Tategaki columns read right to left, and top to bottom inside a column. A column ends at the
 *   first line whose right edge does not reach into it — the same rule on the swapped axis.
 * - A selection that mixes the two orientations has no single reading order. Each group is ordered
 *   by its own rule and the two then follow each other as blocks, the one whose box is higher (and
 *   then further left) first. Interleaved layouts are genuinely ambiguous; this is the documented
 *   tie-break rather than an emergent one.
 *
 * Indices out of range are ignored, duplicates are collapsed, and a line with no text — dropped
 * because its final reading held no Japanese, or a meiki placeholder not yet read — contributes
 * nothing: it is never an empty line in the middle of a sentence.
 */
fun selectionOrder(lines: List<ScreenLine>, lineIndices: List<Int>): List<Int> {
    val valid = lineIndices
        .filter { it in lines.indices && lines[it].text.isNotEmpty() }
        .distinct()
    if (valid.size < 2) return valid

    val vertical = valid.filter { lines[it].vertical }
    val horizontal = valid.filterNot { lines[it].vertical }
    if (vertical.isEmpty()) return orderedGroup(lines, horizontal, vertical = false)
    if (horizontal.isEmpty()) return orderedGroup(lines, vertical, vertical = true)

    val columns = orderedGroup(lines, vertical, vertical = true)
    val rows = orderedGroup(lines, horizontal, vertical = false)
    return if (blockComesFirst(lines, columns, rows)) columns + rows else rows + columns
}

/**
 * [lines] in reading order across [lineIndices]: whole lines only, `\n`-joined, one [SelectedLine]
 * per line with the offsets that address it.
 *
 * Every line is taken whole, never a code-unit run, so a surrogate pair can never be split; and each
 * is repaired with [OcrRepair.scriptRepair] first — the dictionary-free pass, one character for one,
 * so the offsets stay valid. Pure.
 *
 * That pass is the script-only half of the repair the word path applies: it decides 口ケット and
 * コーヒ一 on their neighbouring characters alone, and it cannot make the repairs that need a
 * dictionary to confirm (コーヒ一を注文する stays as it is, where the word path repairs it to
 * コーヒー). A sentence must exist whether or not the dictionary opened, so it cannot depend on one;
 * the difference is the price of that, and the sheet shows the sentence it translated.
 */
fun selectionText(lines: List<ScreenLine>, lineIndices: List<Int>): SelectionText {
    val ordered = selectionOrder(lines, lineIndices)
    if (ordered.isEmpty()) return SelectionText("", emptyList())

    val builder = StringBuilder()
    val selected = ArrayList<SelectedLine>(ordered.size)
    for (index in ordered) {
        val line = lines[index]
        if (builder.isNotEmpty()) builder.append('\n')
        val start = builder.length
        builder.append(OcrRepair.scriptRepair(line.text))
        selected.add(SelectedLine(index, start, builder.length, line.bounds, line.vertical))
    }
    return SelectionText(builder.toString(), selected)
}

/**
 * One orientation group in reading order: the lines are walked from the edge the reading starts at
 * (the top for rows, the right for columns) and a new group begins at the first line that does not
 * reach back into the current one. Within a group the order is the reading direction itself — left
 * to right for a row, top to bottom for a column.
 *
 * The sort is stable, so equal keys keep the index order they arrived in and the answer never
 * depends on which box happened to be visited first.
 */
private fun orderedGroup(lines: List<ScreenLine>, indices: List<Int>, vertical: Boolean): List<Int> {
    if (indices.size < 2) return indices
    val sorted = if (vertical) {
        indices.sortedWith(compareByDescending { lines[it].bounds.right })
    } else {
        indices.sortedWith(compareBy { lines[it].bounds.top })
    }

    val ordered = ArrayList<Int>(sorted.size)
    var group = ArrayList<Int>()
    // Leading edge of the current group: its bottom for a row, its left for a column. A line joins
    // it only if it overlaps that edge; otherwise the group is closed and a new one starts here.
    var edge = 0
    for (index in sorted) {
        val box = lines[index].bounds
        val overlaps = if (group.isEmpty()) true else if (vertical) box.right > edge else box.top < edge
        if (!overlaps) {
            ordered.addAll(withinGroup(lines, group, vertical))
            group = ArrayList()
        }
        group.add(index)
        edge = when {
            group.size > 1 -> if (vertical) minOf(edge, box.left) else maxOf(edge, box.bottom)
            vertical -> box.left
            else -> box.bottom
        }
    }
    ordered.addAll(withinGroup(lines, group, vertical))
    return ordered
}

/** Reading direction inside one row (left to right) or column (top to bottom). */
private fun withinGroup(lines: List<ScreenLine>, group: List<Int>, vertical: Boolean): List<Int> =
    if (vertical) group.sortedBy { lines[it].bounds.top } else group.sortedBy { lines[it].bounds.left }

/** True when the block [a] reads before the block [b]: higher first, then further left. */
private fun blockComesFirst(lines: List<ScreenLine>, a: List<Int>, b: List<Int>): Boolean {
    val first = boundsOf(lines, a)
    val second = boundsOf(lines, b)
    return if (first.top != second.top) first.top < second.top else first.left <= second.left
}

/** The union of the boxes of [indices]. */
private fun boundsOf(lines: List<ScreenLine>, indices: List<Int>): Box = Box(
    indices.minOf { lines[it].bounds.left },
    indices.minOf { lines[it].bounds.top },
    indices.maxOf { lines[it].bounds.right },
    indices.maxOf { lines[it].bounds.bottom }
)
