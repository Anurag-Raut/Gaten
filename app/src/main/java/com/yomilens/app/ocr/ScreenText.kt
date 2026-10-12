/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ocr

/**
 * One recognised line of on-screen text.
 *
 * [bounds] are bitmap pixels, [vertical] marks a tategaki line. [charBoxes] holds one box per
 * UTF-16 character of [text] — every index a hit test or a selection hands back addresses [text]
 * itself, so the two can never drift apart: `charBoxes.size == text.length` always holds.
 */
data class ScreenLine(
    val text: String,
    val bounds: Box,
    val vertical: Boolean,
    val charBoxes: List<Box>
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
