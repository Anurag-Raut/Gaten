/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.ocr.Box
import com.gaten.app.ocr.ScreenLine
import com.gaten.app.ocr.ScreenText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [tapPhraseCrop] window geometry: cell windowing, surrogate pairs, shared-geometry unions,
 * cross padding from the detector thickness, neighbour guards, line ends, invalid geometry,
 * and tategaki.
 */
class TapPhraseCropTest {

    /** A horizontal line of [text]; cells [charWidth] wide with a 2px glyph inset. */
    private fun hline(
        text: String,
        left: Int = 0,
        top: Int = 100,
        charWidth: Int = 40,
        height: Int = 60,
        detectorPad: Int = 6
    ): ScreenLine {
        val n = text.length
        val boxes = List(n) { i ->
            Box(left + charWidth * i, top + 2, left + charWidth * (i + 1), top + height - 2)
        }
        return ScreenLine(
            text,
            Box(left, top, left + charWidth * n, top + height),
            false,
            boxes,
            detectorBounds = Box(
                left - detectorPad,
                top - detectorPad,
                left + charWidth * n + detectorPad,
                top + height + detectorPad
            )
        )
    }

    /** A vertical line of [text]; cells [charHeight] tall with a 2px glyph inset. */
    private fun vline(
        text: String,
        left: Int = 100,
        top: Int = 0,
        charHeight: Int = 60,
        width: Int = 40,
        detectorPad: Int = 6
    ): ScreenLine {
        val n = text.length
        val boxes = List(n) { i ->
            Box(left + 2, top + charHeight * i, left + width - 2, top + charHeight * (i + 1))
        }
        return ScreenLine(
            text,
            Box(left, top, left + width, top + charHeight * n),
            true,
            boxes,
            detectorBounds = Box(
                left - detectorPad,
                top - detectorPad,
                left + width + detectorPad,
                top + charHeight * n + detectorPad
            )
        )
    }

    private fun screenOf(vararg lines: ScreenLine): ScreenText =
        ScreenText(lines.toList(), width = 1080, height = 2400)

    @Test
    fun windowIsTwelveCellsAroundTheTap() {
        val screen = screenOf(hline("あいうえおかきくけこさしすせそ", charWidth = 20))
        // Tap on cell 6 (x 120..140): 5 before, the tapped one, 6 after -> [1, 13).
        val phrase = tapPhraseCrop(screen, 0, x = 130, y = 130)!!
        assertEquals(1, phrase.start)
        assertEquals(13, phrase.endExclusive)
        assertEquals(12, phrase.endExclusive - phrase.start)
    }

    @Test
    fun windowTruncatesAtLineStart() {
        val screen = screenOf(hline("あいうえおかきくけこさしすせそ", charWidth = 20))
        val phrase = tapPhraseCrop(screen, 0, x = 10, y = 130)!!
        assertEquals(0, phrase.start)
        assertEquals(12, phrase.endExclusive)
    }

    @Test
    fun surrogatePairIsNeverSplit() {
        // The normal window would start at cell 2, the low surrogate of 𠮟.
        val screen = screenOf(hline("あ𠮟い" + "か".repeat(12)))
        val phrase = tapPhraseCrop(screen, 0, x = 300, y = 130)!!
        assertEquals(1, phrase.start)
        assertEquals(14, phrase.endExclusive)
    }

    @Test
    fun sharedGeometryCellJoinsTheWindow() {
        // Cell 13 is outside the normal [1, 13) window but shares its last glyph's geometry.
        val line = hline("あいうえおかきくけこさしすせそた", charWidth = 20)
        val boxes = line.charBoxes.toMutableList()
        boxes[13] = boxes[12]
        val screen = screenOf(
            ScreenLine(line.text, line.bounds, false, boxes, line.detectorBounds)
        )
        val phrase = tapPhraseCrop(screen, 0, x = 130, y = 130)!!
        assertEquals(1, phrase.start)
        assertEquals(14, phrase.endExclusive)
    }

    @Test
    fun crossRestoresDetectorBoundsAndAddsBreathingRoom() {
        // Restore the raw 94..166 extent, then add 7px (10% of its 72px thickness).
        val line = hline("あ", detectorPad = 6)
        val screen = screenOf(line)
        val phrase = tapPhraseCrop(screen, 0, x = 20, y = 130)!!
        assertEquals(87, phrase.bounds.top)
        assertEquals(173, phrase.bounds.bottom)
        assertEquals(86, phrase.bounds.height)
    }

    @Test
    fun crossFallsBackToGlyphPadWhenDetectorDisagrees() {
        // A detector extent that does NOT contain its glyphs (malformed geometry): the crop grows
        // the glyphs by the fallback pad instead — 10% of the detector thickness, min 2px.
        val line = hline("あ", detectorPad = 6)
        val screen = screenOf(
            ScreenLine(
                line.text, line.bounds, false, line.charBoxes,
                detectorBounds = Box(line.bounds.left, 500, line.bounds.right, 560)
            )
        )
        val phrase = tapPhraseCrop(screen, 0, x = 20, y = 130)!!
        assertEquals(96, phrase.bounds.top)
        assertEquals(164, phrase.bounds.bottom)
    }

    @Test
    fun neighbourGuardClampsPaddingAtMidpoint() {
        // A's added margin would reach 173; B starts at 174. Keep a gap by stopping at 170.
        val a = hline("あいう", top = 100, detectorPad = 6)
        val b = hline("かきく", top = 180, detectorPad = 6)
        val screen = screenOf(a, b)
        val phrase = tapPhraseCrop(screen, 0, x = 20, y = 130)!!
        assertEquals(87, phrase.bounds.top)
        assertEquals(170, phrase.bounds.bottom)
        assertFalse(phrase.bounds.bottom > b.detectorBounds.top)
    }

    @Test
    fun neighbourGuardRetreatsToGlyphsOnOverlappingExtents() {
        // B's detector extent (104..176) crosses A's glyph extent (102..158): the crop retreats
        // to A's glyphs, which stay whole, so the window survives at the glyph extent.
        val a = hline("あいう", top = 100, detectorPad = 6)
        val b = hline("かきく", top = 110, detectorPad = 6)
        val screen = screenOf(a, b)
        val phrase = tapPhraseCrop(screen, 0, x = 20, y = 130)!!
        assertEquals(102, phrase.bounds.top)
        assertEquals(158, phrase.bounds.bottom)
    }

    @Test
    fun boxCountMismatchYieldsNull() {
        val line = hline("あいう")
        val screen = screenOf(
            ScreenLine(line.text, line.bounds, false, line.charBoxes.drop(1), line.detectorBounds)
        )
        assertNull(tapPhraseCrop(screen, 0, x = 20, y = 130))
    }

    @Test
    fun emptyTextYieldsNull() {
        val line = hline("あいう")
        val screen = screenOf(
            ScreenLine("", line.bounds, false, line.charBoxes, line.detectorBounds)
        )
        assertNull(tapPhraseCrop(screen, 0, x = 20, y = 130))
    }

    @Test
    fun offImageGlyphsYieldNull() {
        // A line entirely left of the image: the screenshot clamp swallows its glyphs -> null.
        val boxes = listOf(
            Box(-500, 102, -460, 158),
            Box(-460, 102, -420, 158),
            Box(-420, 102, -380, 158)
        )
        val screen = ScreenText(
            listOf(
                ScreenLine(
                    "あいう",
                    Box(-500, 100, -380, 160),
                    false,
                    boxes,
                    detectorBounds = Box(-506, 94, -374, 166)
                )
            ),
            width = 1080,
            height = 2400
        )
        assertNull(tapPhraseCrop(screen, 0, x = -440, y = 130))
    }

    @Test
    fun verticalLineWindowsDownward() {
        val screen = screenOf(vline("あいうえおかきくけこさしすせそ", charHeight = 20, width = 40))
        // Tap on cell 6 (y 120..140).
        val phrase = tapPhraseCrop(screen, 0, x = 120, y = 130)!!
        assertEquals(1, phrase.start)
        assertEquals(13, phrase.endExclusive)
        // The cross axis is horizontal now: glyphs 102..138, detector 94..146 contains them.
        assertEquals(89, phrase.bounds.left)
        assertEquals(151, phrase.bounds.right)
    }

    @Test
    fun windowShiftsBackAtTheLineEnd() {
        val phrase = tapPhraseCrop(screenOf(hline("あいうえおかきくけこさしすせそ", charWidth = 20)),
            0, x = 290, y = 130)!!
        assertEquals(3, phrase.start)
        assertEquals(15, phrase.endExclusive)
    }

    @Test
    fun surrogatePairAtTheWindowEndStaysWhole() {
        val phrase = tapPhraseCrop(screenOf(hline("あ".repeat(11) + "𠮟" + "い".repeat(5))),
            0, x = 20, y = 130)!!
        assertEquals(0, phrase.start)
        assertEquals(13, phrase.endExclusive)
    }

    @Test
    fun cropMarginsClampAtTheScreenshotEdgesWithoutLosingGlyphs() {
        val line = hline("あ", top = 0)
        val phrase = tapPhraseCrop(ScreenText(listOf(line), 60, 60), 0, 20, 20)!!
        assertEquals(Box(0, 0, 42, 60), phrase.bounds)
    }

    @Test
    fun alongAxisPaddingUsesOnlyTheGapBeforeTheNextGlyph() {
        val line = hline("あいうえおかきくけこさしすせそ", charWidth = 20)
        val boxes = line.charBoxes.map { it.copy(left = it.left + 2, right = it.right - 2) }
        val phrase = tapPhraseCrop(screenOf(line.copy(charBoxes = boxes)), 0, 130, 130)!!
        assertEquals(20, phrase.bounds.left)
        assertEquals(260, phrase.bounds.right)
        assertTrue(phrase.bounds.left >= boxes[0].right)
        assertTrue(phrase.bounds.right <= boxes[13].left)
    }

    @Test
    fun aTapBetweenUnevenGlyphsUsesTheNearestEdgeRatherThanCentre() {
        val line = hline("あいうえおかきくけこさしすせそた", charWidth = 10)
        val boxes = line.charBoxes.mapIndexed { index, box ->
            when {
                index < 7 -> box
                index == 7 -> box.copy(left = 70, right = 170)
                else -> box.copy(left = 190 + (index - 8) * 10, right = 200 + (index - 8) * 10)
            }
        }
        // At x=173 the wide glyph is 3px away, but its centre is farther than the next glyph's.
        val phrase = tapPhraseCrop(screenOf(line.copy(charBoxes = boxes,
            bounds = Box(0, 100, 270, 160), detectorBounds = Box(0, 94, 270, 166))),
            0, 173, 130)!!
        assertEquals(2, phrase.start)
        assertEquals(14, phrase.endExclusive)
    }
}
