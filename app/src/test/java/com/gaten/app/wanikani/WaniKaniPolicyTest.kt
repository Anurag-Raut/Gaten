/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * The rules that keep the feature pointed where it belongs: the hosts that may be contacted, the page
 * urls the UI may be handed, the token that may be put in a header, and the character a lookup will
 * accept. All pure, so none of it needs a device or a network.
 */
class WaniKaniPolicyTest {

    @Test
    fun `only https wanikani hosts may be contacted`() {
        assertTrue(WaniKaniPolicy.isAllowed(URI("https://api.wanikani.com/v2/subjects?types=kanji")))
        assertTrue(WaniKaniPolicy.isAllowed(URI("https://cdn.wanikani.com/images/legacy/576-subject-1.svg")))
        assertTrue(WaniKaniPolicy.isAllowed(URI("HTTPS://API.WANIKANI.COM/v2/user")))

        assertFalse(WaniKaniPolicy.isAllowed(URI("http://api.wanikani.com/v2/subjects")))
        assertFalse(WaniKaniPolicy.isAllowed(URI("https://evil.example/v2/subjects")))
        assertFalse(WaniKaniPolicy.isAllowed(URI("https://api.wanikani.com.evil.example/v2/subjects")))
        assertFalse(WaniKaniPolicy.isAllowed(URI("https://user:secret@api.wanikani.com/v2/subjects")))
        assertFalse(WaniKaniPolicy.isAllowed(URI("ftp://api.wanikani.com/v2/subjects")))
        assertFalse(WaniKaniPolicy.isAllowed(URI("file:///etc/passwd")))
    }

    @Test
    fun `only https wanikani pages may be opened`() {
        assertTrue(WaniKaniPolicy.isAllowedDocument("https://www.wanikani.com/kanji/%E4%B8%80"))
        assertTrue(WaniKaniPolicy.isAllowedDocument("https://wanikani.com/radicals/ground"))

        assertFalse(WaniKaniPolicy.isAllowedDocument(null))
        assertFalse(WaniKaniPolicy.isAllowedDocument(""))
        assertFalse(WaniKaniPolicy.isAllowedDocument("http://www.wanikani.com/kanji/%E4%B8%80"))
        assertFalse(WaniKaniPolicy.isAllowedDocument("https://evil.example/kanji/%E4%B8%80"))
        assertFalse(WaniKaniPolicy.isAllowedDocument("https://www.wanikani.com.evil.example/kanji"))
        assertFalse(WaniKaniPolicy.isAllowedDocument("https://user@www.wanikani.com/kanji"))
        assertFalse(WaniKaniPolicy.isAllowedDocument("javascript:alert(1)"))
        assertFalse(WaniKaniPolicy.isAllowedDocument("not a url"))
    }

    @Test
    fun `a token must be something a header can carry`() {
        assertTrue(looksLikeToken("0123456789abcdef0123456789abcdef"))
        assertTrue(looksLikeToken("A1-b"))

        assertFalse(looksLikeToken(""))
        assertFalse(looksLikeToken("abc def"))
        assertFalse(looksLikeToken("abc\nInjected: yes"))
        assertFalse(looksLikeToken("abc\r\n"))
        assertFalse(looksLikeToken("tökén"))
        assertFalse(looksLikeToken("x".repeat(257)))
    }

    @Test
    fun `a kanji target is exactly one ideographic code point`() {
        assertTrue(isSingleIdeograph("一"))
        assertTrue(isSingleIdeograph("食"))
        assertTrue(isSingleIdeograph("𠮷")) // U+20BB7, outside the basic plane
        assertTrue(isSingleIdeograph("\uD840\uDC00")) // the same, written as a surrogate pair
        assertTrue(isSingleIdeograph("〇"))

        assertFalse(isSingleIdeograph(""))
        assertFalse(isSingleIdeograph("食い"))
        assertFalse(isSingleIdeograph("a"))
        assertFalse(isSingleIdeograph("ア"))
        assertFalse(isSingleIdeograph("1"))
        assertFalse(isSingleIdeograph(" "))
        assertFalse(isSingleIdeograph("🍣"))
        assertFalse(isSingleIdeograph("\uD840")) // a lone surrogate is not a character
    }
}
