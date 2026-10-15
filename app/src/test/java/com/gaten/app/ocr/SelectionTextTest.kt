/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence contract: the whole lines a gesture selected, in reading order, with offsets that
 * address the joined text.
 *
 * The order is the part of this that no other test covers — the index carries no reading order of
 * its own — so the tategaki cases below are the ones to keep honest if [selectionOrder] is ever
 * touched.
 */
class SelectionTextTest {

    private fun line(
        text: String,
        bounds: Box,
        vertical: Boolean = false
    ): ScreenLine = ScreenLine(
        text,
        bounds,
        vertical,
        interpolateCharBoxes(text, bounds, vertical)
    )

    /** A horizontal line of 40 px characters at [top], starting at [left]. */
    private fun hline(text: String, top: Int, left: Int = 0): ScreenLine =
        line(text, Box(left, top, left + 40 * text.length, top + 60), vertical = false)

    /** One character-wide tategaki cell of [height] px at ([left], [top]). */
    private fun vcell(text: String, left: Int, top: Int, height: Int): ScreenLine =
        line(text, Box(left, top, left + 40, top + height), vertical = true)

    @Test
    fun aHorizontalSelectionReadsTopToBottom() {
        val lines = listOf(hline("今日は", top = 100), hline("晴れです", top = 300))

        // The indices arrive in the order a hostile caller would give them.
        assertEquals(listOf(0, 1), selectionOrder(lines, listOf(1, 0)))
        assertEquals("今日は\n晴れです", selectionText(lines, listOf(1, 0)).text)
    }

    @Test
    fun twoHorizontalLinesOnOneRowReadLeftToRight() {
        val lines = listOf(hline("今日は", top = 100, left = 200), hline("晴れです", top = 110))

        assertEquals(listOf(1, 0), selectionOrder(lines, listOf(0, 1)))
        assertEquals("晴れです\n今日は", selectionText(lines, listOf(0, 1)).text)
    }

    @Test
    fun aTategakiSelectionReadsColumnsRightToLeft() {
        // Three columns of two 80 px characters each. Index 0/1 is the LEFTMOST column, so index
        // order and reading order are opposites: anything that merely sorts by index gets this wrong.
        val leftmost = listOf(vcell("下の行", left = 260, top = 100, height = 80), vcell("上の行", left = 260, top = 180, height = 80))
        val middle = listOf(vcell("中の行", left = 390, top = 100, height = 80), vcell("中の行", left = 390, top = 180, height = 80))
        val rightmost = listOf(vcell("右の行", left = 520, top = 100, height = 80), vcell("右の行", left = 520, top = 180, height = 80))
        val lines = leftmost + middle + rightmost

        val order = selectionOrder(lines, lines.indices.toList())

        assertEquals(listOf(4, 5, 2, 3, 0, 1), order)
        assertEquals(
            "右の行\n右の行\n中の行\n中の行\n下の行\n上の行",
            selectionText(lines, lines.indices.toList()).text
        )
    }

    @Test
    fun everySelectedLineAddressesItsOwnSliceOfTheText() {
        val lines = listOf(
            hline("今日は", top = 100),
            hline("", top = 200),
            hline("晴れです", top = 300)
        )

        val selected = selectionText(lines, listOf(2, 0, 1))

        assertEquals("今日は\n晴れです", selected.text)
        // The empty line contributes nothing: no blank line, and no shift in what follows it.
        assertEquals(listOf(0, 2), selected.lines.map { it.lineIndex })
        for (line in selected.lines) {
            assertEquals(lines[line.lineIndex].text, selected.text.substring(line.start, line.endExclusive))
        }
        assertEquals(0, selected.lines[0].start)
        assertEquals(3, selected.lines[0].endExclusive)
        assertEquals(4, selected.lines[1].start)
        assertEquals(8, selected.lines[1].endExclusive)
    }

    @Test
    fun theLinesCarryTheirOwnGeometry() {
        val lines = listOf(hline("今日は", top = 100), hline("晴れです", top = 300))

        val selected = selectionText(lines, listOf(0, 1))

        assertEquals(listOf(lines[0].bounds, lines[1].bounds), selected.lines.map { it.bounds })
        assertEquals(listOf(false, false), selected.lines.map { it.vertical })
    }

    @Test
    fun aSurrogatePairIsTakenWhole() {
        val text = "猫🐈水を飲む"
        val lines = listOf(hline(text, top = 100))

        val selected = selectionText(lines, listOf(0))

        assertEquals(text, selected.text)
        assertEquals(0, selected.lines.single().start)
        assertEquals(text.length, selected.lines.single().endExclusive)
    }

    @Test
    fun theSentenceIsTheScriptRepairedText() {
        // The dictionary-free pass of OcrRepair, which is the only pass a sentence can afford: the
        // katakana-shaped 口 of 口ケット is a broken ロ, and a prolonged-mark lookalike inside or at
        // the end of katakana is a ー. Both are decided on the neighbouring characters alone.
        assertEquals("ロケット", selectionText(listOf(hline("口ケット", top = 100)), listOf(0)).text)
        assertEquals("コーヒー", selectionText(listOf(hline("コーヒ一", top = 100)), listOf(0)).text)
    }

    @Test
    fun aMixedSelectionOrdersTheTwoBlocks() {
        val column = vcell("右の行", left = 520, top = 100, height = 80)
        val heading = hline("見出し", top = 400)
        val lines = listOf(column, heading)

        // The tategaki column is higher, so it reads first, whatever order the indices arrive in.
        assertEquals(listOf(0, 1), selectionOrder(lines, listOf(1, 0)))

        val lower = listOf(vcell("右の行", left = 520, top = 400, height = 80), hline("見出し", top = 100))
        assertEquals(listOf(1, 0), selectionOrder(lower, listOf(0, 1)))
    }

    @Test
    fun outOfRangeAndDuplicateIndicesAreIgnored() {
        val lines = listOf(hline("今日は", top = 100), hline("晴れです", top = 300))

        assertEquals(listOf(0, 1), selectionOrder(lines, listOf(0, 1, 1, 7, -1, 0)))
        assertEquals("今日は\n晴れです", selectionText(lines, listOf(0, 1, 1, 7, -1, 0)).text)
    }

    @Test
    fun aSelectionWithNothingAddressableIsEmpty() {
        val lines = listOf(hline("", top = 100))

        assertEquals(emptyList<Int>(), selectionOrder(lines, listOf(0)))
        assertEquals("", selectionText(lines, listOf(0)).text)
        assertTrue(selectionText(lines, emptyList()).lines.isEmpty())
    }

    @Test
    fun oneLineIsItsOwnSentence() {
        val lines = listOf(hline("猫が水を飲む", top = 100))

        val selected = selectionText(lines, listOf(0))

        assertEquals("猫が水を飲む", selected.text)
        assertEquals(1, selected.lines.size)
    }
}
