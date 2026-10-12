/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrPostProcessTest {

    @Test
    fun whitespaceIsRemoved() {
        assertEquals("猫が水を飲む", MangaOcrPostProcess.process("猫 が 水を 飲む"))
        assertEquals("これは", MangaOcrPostProcess.process("これ\nは"))
    }

    @Test
    fun theEllipsisBecomesThreeFullWidthDots() {
        // "…" is spelled as "..." and then widened along with every other ASCII character, which is
        // the order manga-ocr's post_process works in.
        assertEquals("え．．．", MangaOcrPostProcess.process("え…"))
        assertEquals("．．．", MangaOcrPostProcess.process("..."))
    }

    @Test
    fun runsOfDotsAreNormalizedAndASingleMiddleDotSurvives() {
        assertEquals("．．．", MangaOcrPostProcess.process("・・・"))
        assertEquals("．．", MangaOcrPostProcess.process("・."))
        assertEquals("・", MangaOcrPostProcess.process("・"))
        assertEquals("．", MangaOcrPostProcess.process("."))
    }

    @Test
    fun asciiIsWidenedIncludingDigits() {
        assertEquals("ＡＢＣ", MangaOcrPostProcess.process("ABC"))
        assertEquals("１２３", MangaOcrPostProcess.process("123"))
        assertEquals("！？", MangaOcrPostProcess.process("!?"))
    }

    @Test
    fun japaneseTextIsLeftAlone() {
        assertEquals("徹篇購訴", MangaOcrPostProcess.process("徹篇購訴"))
    }
}
