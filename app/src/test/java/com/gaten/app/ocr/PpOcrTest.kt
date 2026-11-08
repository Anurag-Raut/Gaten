/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

/** Geometry, CTC decoding, the dictionary layout, and the pack's file catalogue. */
class PpOcrTest {

    // ---- PpOcrGeometry: the crop is turned when tall; width is ratio-preserving, padded and bounded. ----
    // Fixtures are square-pixel (height 48) where convenient, so natural width == source width.

    @Test
    fun aWideCropKeepsItsAspectAndIsPaddedUpToTheOfficialMinimum() {
        val plan = PpOcrGeometry.plan(sourceWidth = 240, sourceHeight = 48)
        assertFalse(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(240, plan.resizedWidth)
    }

    @Test
    fun aSquareCropIsPaddedRatherThanSquashedUp() {
        val plan = PpOcrGeometry.plan(sourceWidth = 48, sourceHeight = 48)
        assertFalse(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(48, plan.resizedWidth)
    }

    @Test
    fun aTallCropIsTurnedAndUsesTheTurnedAspect() {
        // 20 wide, 100 tall -> turned; the turned crop is 100 wide, 20 tall.
        val plan = PpOcrGeometry.plan(sourceWidth = 20, sourceHeight = 100)
        assertTrue(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(240, plan.resizedWidth)
    }

    @Test
    fun aTurnedCropWiderThanTheMinimumUsesItsOwnTurnedWidth() {
        // 48 wide, 200 tall -> turned; the turned crop is 200 wide, 48 tall.
        val plan = PpOcrGeometry.plan(sourceWidth = 48, sourceHeight = 200)
        assertTrue(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(200, plan.resizedWidth)
    }

    @Test
    fun aCropBetweenTheBoundsKeepsItsOwnWidth() {
        val plan = PpOcrGeometry.plan(sourceWidth = 480, sourceHeight = 48)
        assertEquals(480, plan.width)
        assertEquals(480, plan.resizedWidth)
    }

    @Test
    fun aLineAtTheUpperBoundIsNotSqueezed() {
        val plan = PpOcrGeometry.plan(sourceWidth = 1280, sourceHeight = 48)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.width)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.resizedWidth)
    }

    @Test
    fun aLinePastTheUpperBoundIsSqueezedIntoIt() {
        val plan = PpOcrGeometry.plan(sourceWidth = 2000, sourceHeight = 48)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.width)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.resizedWidth)
    }

    // ---- PpOcrCtc: blanks dropped, runs collapsed, spaces kept. ----

    @Test
    fun blankAndDuplicateClassesAreCollapsed() {
        // a a _ a b ' '  ->  "aab "
        val logits = scores(intArrayOf(1, 1, 0, 1, 2, 3), classes = 4)
        val text = PpOcrCtc.decode(logits, steps = 6, classes = 4, vocab = listOf("blank", "a", "b", " "))
        assertEquals("aab ", text)
    }

    @Test
    fun aClassRepeatedAcrossABlankSurvives() {
        // a _ a  ->  "aa"
        val logits = scores(intArrayOf(1, 0, 1), classes = 3)
        val text = PpOcrCtc.decode(logits, steps = 3, classes = 3, vocab = listOf("blank", "a", "b"))
        assertEquals("aa", text)
    }

    @Test
    fun anAllBlankRunDecodesToNothing() {
        val logits = scores(intArrayOf(0, 0, 0), classes = 2)
        assertEquals("", PpOcrCtc.decode(logits, steps = 3, classes = 2, vocab = listOf("blank", "a")))
    }

    private fun scores(best: IntArray, classes: Int): FloatBuffer {
        val buffer = FloatArray(best.size * classes)
        for (step in best.indices) buffer[step * classes + best[step]] = 1f
        return FloatBuffer.wrap(buffer)
    }

    // ---- PpOcrVocabulary: blank at 0, a space last, symbols from line 1. ----

    @Test
    fun theVocabularyIsBlankThenTheLinesThenASpace() {
        val file = File.createTempFile("dict", ".txt").apply {
            writeText("A\nB\n")
            deleteOnExit()
        }
        assertEquals(listOf("blank", "A", "B", " "), PpOcrVocabulary.load(file))
    }

    // ---- The pack catalogue: what Gaten reads out of Google Play's asset pack. ----

    @Test
    fun theCatalogPinsThePackFileNamesAndTheirExactSizes() {
        val files = PpOcrModel.files

        // The names are the paths PpOcrEngine opens inside the pack; the sizes are what
        // PpOcrPack.completeDir checks, so a wrong number would either read a half-delivered pack as
        // installed or never accept a complete one.
        assertEquals(listOf("rec.onnx", "dict.txt"), files.map { it.name })
        assertEquals(76_554_979L, files.first { it.name == PpOcrModel.MODEL_FILE }.size)
        assertEquals(74_947L, files.first { it.name == PpOcrModel.DICT_FILE }.size)

        // And the size the settings screen advertises as the download is exactly their sum.
        assertEquals(PpOcrModel.Engine.MEDIUM.approximateDownloadBytes, files.sumOf { it.size })
    }
}
