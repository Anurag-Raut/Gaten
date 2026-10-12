/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [chooseSheetLayout]: which edge the result sheet docks to, and how much of the screen it takes.
 *
 * The sizes are the owner's phone: 1272x2772 px, so landscape leaves 2772 px of width beside the
 * word and portrait has none to spare.
 */
class SheetPlacementTest {

    private val landscapeWidth = 2772f
    private val landscapeHeight = 1272f
    private val portraitWidth = 1272f
    private val portraitHeight = 2772f

    /** A word box in the right half of a landscape screen. */
    private val anchorRight = Rect(2000f, 400f, 2400f, 600f)

    /** The same word box in the left half. */
    private val anchorLeft = Rect(200f, 400f, 600f, 600f)

    @Test
    fun aLandscapeAnchorOnTheRightHalfDocksTheSheetLeft() {
        val layout = chooseSheetLayout(anchorRight, landscapeWidth, landscapeHeight)

        assertEquals(SheetDock.Left, layout.dock)
    }

    @Test
    fun aLandscapeAnchorOnTheLeftHalfDocksTheSheetRight() {
        val layout = chooseSheetLayout(anchorLeft, landscapeWidth, landscapeHeight)

        assertEquals(SheetDock.Right, layout.dock)
    }

    @Test
    fun aWideLandscapeAnchorDocksTheSheetOnTheSideItCoversLess() {
        // 300..2000 px: 809 px of it fall under the left panel against 337 px under the right one.
        val anchor = Rect(300f, 100f, 2000f, 300f)

        val layout = chooseSheetLayout(anchor, landscapeWidth, landscapeHeight)

        assertEquals(SheetDock.Right, layout.dock)
    }

    @Test
    fun anAnchorSpanningTheWholeWidthFallsThroughToTheRightDock() {
        // Symmetric about the screen, so both panels are covered equally; the documented default
        // has to settle it rather than the order the candidates happen to be tried in.
        val anchor = Rect(0f, 100f, landscapeWidth, 300f)

        val layout = chooseSheetLayout(anchor, landscapeWidth, landscapeHeight)

        assertEquals(SheetDock.Right, layout.dock)
    }

    @Test
    fun anAnchorBetweenThePanelsClearsBothSoTheFartherCentreWins() {
        // In the gap at 1109..1663 neither panel is covered at all; the right panel's centre is
        // 1016 px from the anchor's, the left panel's only 864 px.
        val anchor = Rect(1200f, 100f, 1400f, 300f)

        val layout = chooseSheetLayout(anchor, landscapeWidth, landscapeHeight)

        assertEquals(SheetDock.Right, layout.dock)
    }

    @Test
    fun aLandscapeScreenWithoutAnAnchorTakesTheRightDock() {
        val layout = chooseSheetLayout(null, landscapeWidth, landscapeHeight)

        assertEquals(SheetDock.Right, layout.dock)
    }

    @Test
    fun aPortraitAnchorInTheLowerHalfStillDocksTheSheetAtTheBottom() {
        val layout = chooseSheetLayout(Rect(300f, 2200f, 900f, 2400f), portraitWidth, portraitHeight)

        assertEquals(SheetDock.Bottom, layout.dock)
    }

    @Test
    fun aPortraitAnchorInTheUpperHalfStillDocksTheSheetAtTheBottom() {
        val layout = chooseSheetLayout(Rect(300f, 200f, 900f, 400f), portraitWidth, portraitHeight)

        assertEquals(SheetDock.Bottom, layout.dock)
    }

    @Test
    fun aPortraitScreenWithoutAnAnchorTakesTheBottomDock() {
        val layout = chooseSheetLayout(null, portraitWidth, portraitHeight)

        assertEquals(SheetDock.Bottom, layout.dock)
    }

    @Test
    fun aSquareScreenCountsAsPortrait() {
        val layout = chooseSheetLayout(Rect(300f, 200f, 900f, 400f), 1000f, 1000f)

        assertEquals(SheetDock.Bottom, layout.dock)
        assertEquals(SheetLayout(SheetDock.Bottom, BOTTOM_SHEET_HEIGHT_FRACTION), layout)
    }

    @Test
    fun aSideDockTakesTwoFifthsOfTheWidthAndTheBottomDockThreeFifthsOfTheHeight() {
        assertEquals(0.40f, SIDE_SHEET_WIDTH_FRACTION, 0f)
        assertEquals(0.60f, BOTTOM_SHEET_HEIGHT_FRACTION, 0f)
        assertEquals(
            SIDE_SHEET_WIDTH_FRACTION,
            chooseSheetLayout(anchorLeft, landscapeWidth, landscapeHeight).fraction,
            0f
        )
        assertEquals(
            BOTTOM_SHEET_HEIGHT_FRACTION,
            chooseSheetLayout(anchorLeft, portraitWidth, portraitHeight).fraction,
            0f
        )
    }
}