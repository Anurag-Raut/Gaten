/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLinePolicyTest {

    @Test
    fun japaneseLinesAreReadByMangaOcr() {
        assertTrue(OcrLinePolicy.readWithMangaOcr("猫が水を飲む"))
        assertTrue(OcrLinePolicy.readWithMangaOcr("コンピュータ"))
        assertTrue(OcrLinePolicy.readWithMangaOcr("「こんにちは。」"))
        assertTrue(OcrLinePolicy.readWithMangaOcr("１２３"))
        assertTrue(OcrLinePolicy.readWithMangaOcr("ﾃｨﾛ"))
    }

    @Test
    fun latinAndDigitLinesAreLeftToMlKit() {
        // manga-ocr is Japanese-trained and widens ASCII to full width: nothing to gain here.
        assertFalse(OcrLinePolicy.readWithMangaOcr("Hello"))
        assertFalse(OcrLinePolicy.readWithMangaOcr("11:21"))
        assertFalse(OcrLinePolicy.readWithMangaOcr("Play now"))
        assertFalse(OcrLinePolicy.readWithMangaOcr("ABC123"))
    }

    @Test
    fun veryShortLinesAreReadWhateverScriptTheyAreIn() {
        assertTrue(OcrLinePolicy.readWithMangaOcr("OK"))
        assertTrue(OcrLinePolicy.readWithMangaOcr("5"))
        assertTrue(OcrLinePolicy.readWithMangaOcr(""))
    }

    @Test
    fun aLineWithOneJapaneseCharacterIsJapanese() {
        assertTrue(OcrLinePolicy.readWithMangaOcr("cat猫cat"))
        assertTrue(OcrLinePolicy.readWithMangaOcr("あ"))
    }

    @Test
    fun anOrdinaryReadingIsAcceptedWithoutTheDictionary() {
        // The point of the engine: stylized text the dictionary has never heard of.
        assertTrue(OcrLinePolicy.acceptsMangaText("描が水を飲む", "猫が水を飲む"))
        assertTrue(OcrLinePolicy.acceptsMangaText("ヤバい", "ヤバい"))
        assertTrue(OcrLinePolicy.acceptsMangaText("猫", "猫が水を飲む"))
    }

    @Test
    fun anEmptyOrPlaceholderReadingIsRefused() {
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", ""))
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", "   "))
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", "[UNK]"))
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", "□"))
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", "□□□"))
    }

    @Test
    fun aReadingLongerThanTheBoxCouldHoldIsRefused() {
        // A one character line has room for 3 x 1 + 4 = 7 characters, and no more.
        assertTrue(OcrLinePolicy.acceptsMangaText("猫", "猫が水を飲むよ"))
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", "猫が水を飲むよ！"))

        // The longer ML Kit's reading, the more room the box has: 6 x 3 + 4 = 22.
        val long = "猫が水を飲む"
        assertFalse(OcrLinePolicy.acceptsMangaText(long, "猫".repeat(23)))
        assertTrue(OcrLinePolicy.acceptsMangaText(long, "猫".repeat(22)))
    }

    @Test
    fun blankAndPlaceholderTextIsUnreadable() {
        assertTrue(OcrLinePolicy.isUnreadable(""))
        assertTrue(OcrLinePolicy.isUnreadable("  "))
        assertTrue(OcrLinePolicy.isUnreadable("[PAD]"))
        assertTrue(OcrLinePolicy.isUnreadable("□"))
        assertFalse(OcrLinePolicy.isUnreadable("猫"))
        assertFalse(OcrLinePolicy.isUnreadable("…"))
    }
}
