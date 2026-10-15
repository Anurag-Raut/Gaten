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
        // A line ML Kit read as one character still has room for 4 x 3 + 4 = 16 characters: its
        // reading is too short to be trusted as an estimate of the box, so it only sets a floor.
        assertTrue(OcrLinePolicy.acceptsMangaText("猫", "猫".repeat(16)))
        assertFalse(OcrLinePolicy.acceptsMangaText("猫", "猫".repeat(17)))

        // The longer ML Kit's reading, the more room the box has: 6 x 3 + 4 = 22.
        val long = "猫が水を飲む"
        assertFalse(OcrLinePolicy.acceptsMangaText(long, "猫".repeat(23)))
        assertTrue(OcrLinePolicy.acceptsMangaText(long, "猫".repeat(22)))
    }

    @Test
    fun aShortOrGarbageMlKitReadingDoesNotShrinkTheBox() {
        // ML Kit reading a stylized line as a couple of Latin characters — or as nothing — says
        // nothing about how much text the box holds, so the reading keeps a line's worth of room.
        assertTrue(OcrLinePolicy.acceptsMangaText("A", "猫が水を飲む"))
        assertTrue(OcrLinePolicy.acceptsMangaText("", "猫が水を飲む"))
        assertTrue(OcrLinePolicy.acceptsMangaText("ab", "猫が水を飲むよ！"))
    }

    @Test
    fun anEmptyMlKitReadingAcceptsARoomyMangaReading() {
        // With no ML Kit reading, the floor (4 characters) sets the room for a replacement:
        // a short reading is accepted, and 4 x 3 + 4 = 16 is the limit.
        assertTrue(OcrLinePolicy.acceptsMangaText("", "猫が水を飲む"))
        assertTrue(OcrLinePolicy.acceptsMangaText("", "猫".repeat(16)))
        assertFalse(OcrLinePolicy.acceptsMangaText("", "猫".repeat(17)))
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
