/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.ui.geometry.Rect
import kotlin.math.sqrt

/**
 * Share of the screen width a side-docked sheet occupies. Two fifths leaves the word the reader
 * picked — and the video it came from — visible beside the entry on a phone held sideways.
 */
const val SIDE_SHEET_WIDTH_FRACTION = 0.40f

/**
 * Share of the screen height a bottom-docked sheet may occupy: the cap the portrait sheet has
 * always had.
 */
const val BOTTOM_SHEET_HEIGHT_FRACTION = 0.60f

/** The edge of the screen the result sheet is docked to. A portrait screen always docks [Bottom]. */
enum class SheetDock { Bottom, Left, Right }

/**
 * Where the sheet sits and how much of the screen it takes.
 *
 * [fraction] is the share of the screen *width* the sheet occupies when it is docked to a side,
 * and of the screen *height* when it is docked to the bottom.
 */
data class SheetLayout(val dock: SheetDock, val fraction: Float)

/**
 * The dock and size for one result, chosen so the sheet covers as little as possible of the word it
 * is explaining.
 *
 * A landscape screen has room beside the word, so the sheet docks left or right — whichever covers
 * [anchor] less; ties go to the panel whose centre is farther from the anchor's, and a remaining
 * tie to [SheetDock.Right]. A portrait (or square) screen has no such room, so it keeps the bottom
 * dock at [BOTTOM_SHEET_HEIGHT_FRACTION]. A null [anchor] (nothing highlighted) takes the default:
 * [SheetDock.Right] in landscape, [SheetDock.Bottom] otherwise.
 *
 * @param screenWidth the screen's width in pixels, from the same origin as [anchor].
 * @param screenHeight the screen's height in the same unit; only the ratio of the two is read.
 */
fun chooseSheetLayout(anchor: Rect?, screenWidth: Float, screenHeight: Float): SheetLayout {
    if (screenWidth <= screenHeight) {
        return SheetLayout(SheetDock.Bottom, BOTTOM_SHEET_HEIGHT_FRACTION)
    }
    if (anchor == null) return SheetLayout(SheetDock.Right, SIDE_SHEET_WIDTH_FRACTION)

    // Smaller overlap wins; a tie goes to the panel whose centre is farther from the anchor's,
    // and a remaining tie to the right dock, so the choice is never arbitrary.
    val ranked = listOf(SheetDock.Left, SheetDock.Right).map { dock ->
        val panel = sidePanelRect(dock, screenWidth, screenHeight)
        Triple(dock, overlapArea(panel, anchor), -centreDistance(panel, anchor))
    }
    val best = ranked.minWith(
        compareBy({ it.second }, { it.third }, { if (it.first == SheetDock.Right) 0 else 1 })
    )
    return SheetLayout(best.first, SIDE_SHEET_WIDTH_FRACTION)
}

/** The full-height rectangle a [SIDE_SHEET_WIDTH_FRACTION]-wide panel on [dock] would cover. */
private fun sidePanelRect(dock: SheetDock, screenWidth: Float, screenHeight: Float): Rect {
    val width = screenWidth * SIDE_SHEET_WIDTH_FRACTION
    return if (dock == SheetDock.Left) {
        Rect(0f, 0f, width, screenHeight)
    } else {
        Rect(screenWidth - width, 0f, screenWidth, screenHeight)
    }
}

/** Area the two rectangles share; zero when they do not overlap. */
private fun overlapArea(a: Rect, b: Rect): Float {
    val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
    val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
    return width * height
}

/** Distance between the rectangles' centres, which settles an overlap tie. */
private fun centreDistance(a: Rect, b: Rect): Float {
    val dx = a.center.x - b.center.x
    val dy = a.center.y - b.center.y
    return sqrt(dx * dx + dy * dy)
}