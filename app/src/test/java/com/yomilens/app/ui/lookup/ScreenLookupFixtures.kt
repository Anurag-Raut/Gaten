/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ui.lookup

import com.yomilens.app.ocr.Box
import com.yomilens.app.ocr.ScreenLine
import com.yomilens.app.ocr.ScreenText
import com.yomilens.app.ocr.interpolateCharBoxes

/**
 * Screen-index fixtures for the [ScreenLookup] tests: lines boxed the way the scanner boxes
 * them, so tap and selection geometry can be reasoned about in character-sized units.
 */

/** A line of one `charHeight` x `charWidth` px box per character, starting at (`left`, [top]). */
internal fun screenLine(
    text: String,
    top: Int,
    left: Int = 0,
    charWidth: Int = 40,
    charHeight: Int = 60,
    vertical: Boolean = false
): ScreenLine {
    val bounds = if (vertical) {
        Box(left, top, left + charWidth, top + charHeight * text.length)
    } else {
        Box(left, top, left + charWidth * text.length, top + charHeight)
    }
    return ScreenLine(text, bounds, vertical, interpolateCharBoxes(text, bounds, vertical))
}

/** A screen of [lines] on a phone-sized bitmap. */
internal fun screenOf(vararg lines: ScreenLine): ScreenText =
    ScreenText(lines.toList(), width = 1080, height = 2400)
