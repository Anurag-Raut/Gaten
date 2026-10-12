/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHitsTest {

    /** A horizontal line of [charWidth] x [charHeight] px characters, boxed the way the scanner boxes them. */
    private fun line(text: String, top: Int, charWidth: Int = 40, charHeight: Int = 60): ScreenLine {
        val bounds = Box(0, top, charWidth * text.length, top + charHeight)
        return ScreenLine(
            text,
            bounds,
            vertical = false,
            charBoxes = interpolateCharBoxes(text, bounds, vertical = false)
        )
    }

    private fun screenOf(vararg lines: ScreenLine) = ScreenText(lines.toList(), width = 1080, height = 2400)

    @Test
    fun aTapInsideABoxHitsThatCharacter() {
        // 日本語 in 40 px characters: 日 is 0..40, 本 40..80, 語 80..120.
        val screen = screenOf(line("日本語", top = 100))

        assertEquals(CharHit(0, 1), ScreenHits.hitTest(screen, x = 50, y = 130, tolerancePx = 0))
    }

    @Test
    fun aTapBesideABoxHitsWithinToleranceAndMissesBeyondIt() {
        val screen = screenOf(line("日本語", top = 100))

        // x = 126 sits 6 px right of 語's box, which ends at 120.
        assertEquals(CharHit(0, 2), ScreenHits.hitTest(screen, x = 126, y = 130, tolerancePx = 6))
        assertNull(ScreenHits.hitTest(screen, x = 126, y = 130, tolerancePx = 5))
    }

    @Test
    fun theNearestOfTwoCharactersWins() {
        // Two stacked lines; the tap is in the gutter, 10 px above the lower one.
        val screen = screenOf(line("あい", top = 100), line("うえ", top = 300))

        assertEquals(CharHit(1, 0), ScreenHits.hitTest(screen, x = 10, y = 290, tolerancePx = 200))
    }

    @Test
    fun aTapOnAnEmptyIndexHitsNothing() {
        val screen = ScreenText(emptyList(), width = 1080, height = 2400)

        assertNull(ScreenHits.hitTest(screen, x = 10, y = 10, tolerancePx = 500))
        assertTrue(ScreenHits.spansInSelection(screen, Box(0, 0, 500, 500)).isEmpty())
        assertTrue(ScreenHits.linesInSelection(screen, Box(0, 0, 500, 500)).isEmpty())
    }

    @Test
    fun aLoopSelectsExactlyTheCharactersItCovers() {
        // 日本語を in 40 px characters: 日 0..40, 本 40..80, 語 80..120, を 120..160.
        val screen = screenOf(line("日本語を", top = 100))

        // The loop ends exactly on the seam at 40, so 日 is covered and 本 is not.
        assertEquals(listOf(LineSpan(0, 0, 1)), ScreenHits.spansInSelection(screen, Box(30, 100, 40, 160)))
        // One pixel further and 本 is covered too: there is no slack either way.
        assertEquals(listOf(LineSpan(0, 0, 2)), ScreenHits.spansInSelection(screen, Box(30, 100, 41, 160)))
        // Starting one pixel past 語's right edge at 120 keeps 語 out and pulls を in.
        assertEquals(listOf(LineSpan(0, 3, 4)), ScreenHits.spansInSelection(screen, Box(121, 100, 140, 160)))
    }

    @Test
    fun aLoopAcrossTwoLinesReturnsOneSpanPerLine() {
        val screen = screenOf(line("日本語", top = 100), line("漢字とかな", top = 200))

        val spans = ScreenHits.spansInSelection(screen, Box(0, 130, 60, 270))

        assertEquals(listOf(LineSpan(0, 0, 2), LineSpan(1, 0, 2)), spans)
    }

    @Test
    fun aLoopThatCoversNoCharacterSelectsNothing() {
        val screen = screenOf(line("上の行", top = 100, charWidth = 100), line("下の行", top = 1000, charWidth = 100))
        val gutters = listOf(
            // 80 px below the upper line, and 70 px above the lower one: close to both, and still
            // nothing — the nearest-line fallback is gone, because a circle answers with its crop.
            Box(0, 240, 300, 250),
            Box(0, 920, 300, 930),
            // Far from everything, and empty for the same reason.
            Box(0, 400, 300, 410)
        )

        for (gutter in gutters) {
            assertTrue(ScreenHits.spansInSelection(screen, gutter).isEmpty())
            // Not even offered to the reader: the loop never reached either line's box.
            assertTrue(ScreenHits.linesInSelection(screen, gutter).isEmpty())
        }
    }

    @Test
    fun aTategakiLineIsSplitDownItsHeight() {
        val bounds = Box(500, 100, 560, 280)
        val screen = screenOf(
            ScreenLine(
                "あいう",
                bounds,
                vertical = true,
                charBoxes = interpolateCharBoxes("あいう", bounds, vertical = true)
            )
        )

        // あ is 100..160, い 160..220, う 220..280: the loop sits inside う, past い's edge.
        assertEquals(listOf(LineSpan(0, 2, 3)), ScreenHits.spansInSelection(screen, Box(505, 260, 555, 270)))
        assertEquals(CharHit(0, 1), ScreenHits.hitTest(screen, x = 520, y = 200, tolerancePx = 10))
    }

    @Test
    fun linesInSelectionOffersOnlyTheLinesTheLoopCovered() {
        val screen = screenOf(line("日本語", top = 100), line("漢字とかな", top = 200))

        // 日 0..40, 本 40..80: the loop covers 日本 of the first line and nothing of the second.
        assertEquals(listOf(0), ScreenHits.linesInSelection(screen, Box(30, 130, 80, 160)))
    }

    @Test
    fun aLineWithNoCharacterBoxesIsOfferedWhenItsOwnBoxIsCovered() {
        // A meiki-only placeholder: a box, no text and no characters until manga-ocr reads it.
        val screen = screenOf(
            line("日本語", top = 100),
            ScreenLine("", Box(0, 200, 400, 260), vertical = false, charBoxes = emptyList())
        )

        // Offered to the reader, but with no characters to contribute to a span.
        assertEquals(listOf(1), ScreenHits.linesInSelection(screen, Box(0, 210, 100, 250)))
        assertTrue(ScreenHits.spansInSelection(screen, Box(0, 210, 100, 250)).isEmpty())
        // A placeholder the loop never reached is not offered.
        assertTrue(ScreenHits.linesInSelection(screen, Box(0, 400, 100, 450)).isEmpty())
    }

    @Test
    fun aLineWhoseLooseBoxIsCoveredButWhoseCharactersAreNotIsNotOffered() {
        // The model reported a wide line box; its only character sits at the left of it.
        val screen = screenOf(
            ScreenLine("猫", Box(0, 100, 400, 160), vertical = false, charBoxes = listOf(Box(0, 100, 40, 160)))
        )

        assertTrue(ScreenHits.spansInSelection(screen, Box(200, 110, 300, 140)).isEmpty())
        assertTrue(ScreenHits.linesInSelection(screen, Box(200, 110, 300, 140)).isEmpty())
    }
}
