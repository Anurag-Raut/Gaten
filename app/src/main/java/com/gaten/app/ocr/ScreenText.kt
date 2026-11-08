/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * One recognised line of on-screen text.
 *
 * [bounds] are bitmap pixels, [vertical] marks a tategaki line. [charBoxes] holds one box per
 * UTF-16 character of [text] — every index a hit test or a selection hands back addresses [text]
 * itself, so the two can never drift apart: `charBoxes.size == text.length` always holds.
 *
 * A line whose final text turned out to have no Japanese in it is dropped ([withDroppedLine]): it
 * keeps its place in the list, because every line index a gesture hands back addresses that list,
 * but it is left with no text and no boxes, so nothing can reach it.
 */
data class ScreenLine(
    val text: String,
    val bounds: Box,
    val vertical: Boolean,
    val charBoxes: List<Box>,
    /** Original detector extent for tap crop margins; outlines and hit tests still use [bounds]. */
    val detectorBounds: Box = bounds
)

/** Everything OCR found on one screen: the lines in reading order, plus the bitmap they came from. */
data class ScreenText(val lines: List<ScreenLine>, val width: Int, val height: Int)

/** Where the full-screen pass stands, as the overlay has to present it. */
sealed interface ScanState {
    data object Scanning : ScanState
    data class Ready(val screen: ScreenText) : ScanState
    data object Unavailable : ScanState
}

/**
 * Splits [bounds] evenly along its long axis into one box per UTF-16 character of [text].
 *
 * The OCR model reports layout down to the line, and only sometimes down to the symbol, so without
 * this a tap or a lasso could never address anything narrower than a whole line. Cutting the line
 * box up is the cheap approximation to fall back on: each slice is `lineLength / text.length`
 * wide, the last one ends exactly on [bounds]'s edge, and the slices tile the line with no gap and
 * no overhang, which is all [ScreenHits] needs.
 *
 * [vertical] picks the axis: a tategaki line is cut into horizontal slices down its height (all of
 * them as wide as the line), an ordinary line into vertical slices across its width.
 */
fun interpolateCharBoxes(text: String, bounds: Box, vertical: Boolean): List<Box> {
    if (text.isEmpty()) return emptyList()

    return List(text.length) { index ->
        if (vertical) {
            Box(
                bounds.left,
                bounds.top + bounds.height * index / text.length,
                bounds.right,
                bounds.top + bounds.height * (index + 1) / text.length
            )
        } else {
            Box(
                bounds.left + bounds.width * index / text.length,
                bounds.top,
                bounds.left + bounds.width * (index + 1) / text.length,
                bounds.bottom
            )
        }
    }
}

/**
 * True when every character of [text] is full-width: kana, kanji, CJK punctuation or the full-width
 * forms block. Such a line is laid out on an even grid, one em per character, which is what lets
 * [interpolateCharBoxes] place its characters better than the model's own symbol boxes do. Any
 * Latin letter, digit or space makes the widths uneven, so it is false for those and for "".
 */
fun isFullWidthText(text: String): Boolean = text.isNotEmpty() && text.all { char ->
    val code = char.code
    code in 0x3000..0x30FF || code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
        code in 0xFF00..0xFFEF
}

/**
 * The line with its text replaced by [text], and its character boxes re-aligned to it.
 *
 * A second reader gives back a different string — usually a different length — so the detector's
 * boxes no longer address it one for one. [CharBoxAlign] keeps the geometry anyway: every character
 * both readings share keeps the box the detector drew for it, the characters only the reader saw are
 * cut out of the gap their neighbours left, and the `…` it spells as dots takes the ellipsis's own
 * box. A tap or a highlight then lands on the glyph that was pointed at. The invariant holds:
 * `charBoxes.size == text.length`.
 */
fun ScreenLine.withText(text: String): ScreenLine =
    copy(text = text, charBoxes = CharBoxAlign.align(this.text, this.charBoxes, text, bounds, vertical))

/** [screen] with the text of line [index] replaced, or [screen] itself when there is no such line. */
fun ScreenText.withLineText(index: Int, text: String): ScreenText {
    val line = lines.getOrNull(index) ?: return this
    return copy(lines = lines.toMutableList().also { it[index] = line.withText(text) })
}

/**
 * [screen] with line [index] dropped, or [screen] itself when there is no such line.
 *
 * The line is left in place — indices are what a gesture hands back, and they address this list —
 * but with no text and no character boxes, so no tap, lasso or outline can reach it. That is the
 * state a line whose final text has no Japanese in it ends up in.
 */
fun ScreenText.withDroppedLine(index: Int): ScreenText {
    val line = lines.getOrNull(index) ?: return this
    return copy(
        lines = lines.toMutableList().also {
            it[index] = line.copy(text = "", charBoxes = emptyList())
        }
    )
}
