/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseTextTest {

    @Test
    fun kanaAndKanjiLinesAreKept() {
        assertTrue(JapaneseText.isJapaneseLine("猫が水を飲む"))
        assertTrue(JapaneseText.isJapaneseLine("コンピュータ"))
        assertTrue(JapaneseText.isJapaneseLine("ﾃｨﾛ"))
        assertTrue(JapaneseText.isJapaneseLine("々"))
        assertTrue(JapaneseText.isJapaneseLine("〆切"))
    }

    @Test
    fun aMixedLineWithOneJapaneseCharacterIsKept() {
        assertTrue(JapaneseText.isJapaneseLine("Lv.5 勇者"))
        assertTrue(JapaneseText.isJapaneseLine("HP: 12 の回復"))
    }

    @Test
    fun latinDigitsSymbolsAndEmojiAreDropped() {
        assertFalse(JapaneseText.isJapaneseLine("Settings"))
        assertFalse(JapaneseText.isJapaneseLine("12:30"))
        assertFalse(JapaneseText.isJapaneseLine("★"))
        assertFalse(JapaneseText.isJapaneseLine("|"))
        assertFalse(JapaneseText.isJapaneseLine(""))
        assertFalse(JapaneseText.isJapaneseLine("   "))
        assertFalse(JapaneseText.isJapaneseLine("😀"))
    }

    @Test
    fun aLoneStrokeLikeCharacterIsDropped() {
        // UI dividers and arrows the model reads as a one-character line.
        assertFalse(JapaneseText.isJapaneseLine("ー"))
        assertFalse(JapaneseText.isJapaneseLine("一"))
        assertFalse(JapaneseText.isJapaneseLine("丨"))
        assertFalse(JapaneseText.isJapaneseLine("ノ"))
        assertFalse(JapaneseText.isJapaneseLine("・"))
    }

    @Test
    fun aStrokeLikeCharacterWithCompanyIsKept() {
        assertTrue(JapaneseText.isJapaneseLine("ーノ"))
        assertTrue(JapaneseText.isJapaneseLine("一文字"))
    }
}
