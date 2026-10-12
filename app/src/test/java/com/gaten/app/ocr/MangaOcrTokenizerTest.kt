/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrTokenizerTest {

    /** The five special entries `vocab.txt` starts with, then the tokens [tokens] adds. */
    private fun tokenizer(vararg tokens: String) =
        MangaOcrTokenizer(listOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]") + tokens)

    @Test
    fun specialTokensAreDroppedFromTheDecodedText() {
        // [CLS] starts the sequence and [SEP] ends it; both are structure, not text.
        val tokenizer = tokenizer("猫", "水")

        assertEquals("猫水", tokenizer.decode(longArrayOf(2, 5, 6, 3), 0, 4))
        assertEquals("猫", tokenizer.decode(longArrayOf(0, 1, 5, 4), 0, 4))
    }

    @Test
    fun wordpiecePrefixesAreStripped() {
        val tokenizer = tokenizer("##ん", "だ")

        assertEquals("んだ", tokenizer.decode(longArrayOf(1, 5, 6), 0, 3))
    }

    @Test
    fun charactersAreJoinedWithoutSpaces() {
        // The vocabulary is character-level, so every token is one character and decode is a join.
        val tokenizer = tokenizer("こ", "れ", "は")

        assertEquals("これは", tokenizer.decode(longArrayOf(2, 5, 6, 7, 3), 0, 5))
    }

    @Test
    fun onlyTheGivenWindowIsDecoded() {
        val tokenizer = tokenizer("猫", "水")

        assertEquals("猫水", tokenizer.decode(longArrayOf(0, 5, 6, 0), 1, 2))
    }

    @Test
    fun idsOutsideTheVocabularyAreDropped() {
        val tokenizer = tokenizer("猫")

        assertEquals("猫", tokenizer.decode(longArrayOf(9999, 5, 9999), 0, 3))
    }
}
