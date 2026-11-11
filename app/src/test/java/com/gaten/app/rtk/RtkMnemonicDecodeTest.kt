/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rtk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `mnemonics` column's own decisions: what survives the trip from the dataset, what is dropped,
 * and that a damaged row never costs the kanji it belongs to.
 */
class RtkMnemonicDecodeTest {

    private val json = """
        [
          {"index":2,"author":"Katsuo","profileUrl":"http://kanji.koohii.com/profile/Katsuo",
           "date":"14-2-2008","score":50,"body":"**El** _sol_ 日本"},
          {"index":1,"author":"Piitaa","profileUrl":"https://evil.example/profile/Piitaa",
           "date":"29-6-2006","score":263,"body":"One down, 2041 to go."}
        ]
    """.trimIndent()

    @Test
    fun `stories keep their attribution, unicode and emphasis`() {
        val stories = decodeMnemonics(json)

        assertEquals(2, stories.size)
        // Ordered by the number the story carries, not by the order the row stored it in.
        val first = stories[0]
        assertEquals(1, first.index)
        assertEquals("Piitaa", first.author)
        assertEquals("29-6-2006", first.date)
        assertEquals(263, first.score)
        assertEquals("One down, 2041 to go.", first.body)
        // The link is not a Koohii page, so it is dropped rather than offered to the reader.
        assertNull(first.profileUrl)

        val second = stories[1]
        assertEquals(2, second.index)
        assertEquals("http://kanji.koohii.com/profile/Katsuo", second.profileUrl)
        assertEquals("**El** _sol_ 日本", second.body)
    }

    @Test
    fun `unreadable json reads as no stories`() {
        assertTrue(decodeMnemonics("").isEmpty())
        assertTrue(decodeMnemonics("not json").isEmpty())
        assertTrue(decodeMnemonics("{}").isEmpty())
    }

    @Test
    fun `a story without a body is skipped`() {
        val stories = decodeMnemonics(
            """[{"index":1,"author":"Nobody","profileUrl":"","date":"","score":0,"body":""}]"""
        )
        assertTrue(stories.isEmpty())
    }

    @Test
    fun `a missing index falls back to the entry's position`() {
        val stories = decodeMnemonics(
            """[{"author":"A","profileUrl":"","date":"","score":0,"body":"first"},
                {"author":"B","profileUrl":"","date":"","score":0,"body":"second"}]"""
        )
        assertEquals(listOf(1, 2), stories.map { it.index })
    }

    @Test
    fun `only a kanji koohii page is an allowed profile link`() {
        assertTrue(isAllowedProfileUrl("http://kanji.koohii.com/profile/Piitaa"))
        assertTrue(isAllowedProfileUrl("https://kanji.koohii.com"))
        assertTrue(isAllowedProfileUrl("https://www.kanji.koohii.com/profile/x?y=1"))
        assertTrue(!isAllowedProfileUrl("https://evilkanji.koohii.com.example/profile"))
        assertTrue(!isAllowedProfileUrl("ftp://kanji.koohii.com/profile/x"))
        assertTrue(!isAllowedProfileUrl("//kanji.koohii.com/profile/x"))
        assertTrue(!isAllowedProfileUrl(""))
        assertTrue(!isAllowedProfileUrl(null))
    }
}
