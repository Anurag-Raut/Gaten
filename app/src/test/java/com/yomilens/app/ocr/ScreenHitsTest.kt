/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ocr

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
    }

    @Test
    fun aLoopOverPartOfALineSelectsTheCharactersItCrossed() {
        val screen = screenOf(line("日本語を", top = 100))

        // The loop ends exactly on the seam between 日 and 本: half a character of slack is what
        // pulls 本 in, and 語 one character further on stays out.
        assertEquals(listOf(LineSpan(0, 0, 2)), ScreenHits.spansInSelection(screen, Box(30, 100, 40, 160)))
    }

    @Test
    fun aLoopGetsHalfACharacterOfSlackOnEitherSide() {
        val screen = screenOf(line("日本語を", top = 100))

        // 100 is the slack edge of both 本's box (40..80) and 語's box (80..120): the first stays
        // out, the second comes in.
        assertEquals(listOf(LineSpan(0, 2, 4)), ScreenHits.spansInSelection(screen, Box(100, 100, 130, 160)))
    }

    @Test
    fun aLoopAcrossTwoLinesReturnsOneSpanPerLine() {
        val screen = screenOf(line("日本語", top = 100), line("漢字とかな", top = 200))

        val spans = ScreenHits.spansInSelection(screen, Box(0, 130, 60, 270))

        assertEquals(listOf(LineSpan(0, 0, 2), LineSpan(1, 0, 2)), spans)
    }

    @Test
    fun aLoopTouchingNoCharacterFallsBackToTheNearestLine() {
        val screen = screenOf(line("上の行", top = 100, charWidth = 100), line("下の行", top = 1000, charWidth = 100))

        // 80 px below the upper line: inside the 1.5 line-height reach (60 px line -> 90 px).
        assertEquals(listOf(LineSpan(0, 0, 3)), ScreenHits.spansInSelection(screen, Box(0, 240, 300, 250)))
        // 70 px above the lower line, and nearer to it than to the upper one.
        assertEquals(listOf(LineSpan(1, 0, 3)), ScreenHits.spansInSelection(screen, Box(0, 920, 300, 930)))
    }

    @Test
    fun aLoopTooFarFromEveryLineSelectsNothing() {
        val screen = screenOf(line("上の行", top = 100, charWidth = 100), line("下の行", top = 1000, charWidth = 100))

        // 100 px below the upper line is past its 90 px reach, and the lower line is far away.
        assertTrue(ScreenHits.spansInSelection(screen, Box(0, 260, 300, 270)).isEmpty())
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

        // あ is 100..160, い 160..220, う 220..280: the loop sits inside う, past い's slack.
        assertEquals(listOf(LineSpan(0, 2, 3)), ScreenHits.spansInSelection(screen, Box(505, 260, 555, 270)))
        assertEquals(CharHit(0, 1), ScreenHits.hitTest(screen, x = 520, y = 200, tolerancePx = 10))
    }
}
