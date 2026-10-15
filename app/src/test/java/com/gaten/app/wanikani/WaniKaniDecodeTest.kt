/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * What WaniKani's answers decode into: the fields the UI draws, the awkward cases (a radical with no
 * character and only an image, a body of another kind, a malformed one, a document url that is not
 * WaniKani's) and the plain text the mnemonics become.
 */
class WaniKaniDecodeTest {

    @Test
    fun `a kanji resource decodes into the fields the UI shows`() {
        val decoded = decodeSubjectResource(WaniKaniJson.kanji())!!
        assertEquals(440, decoded.id)
        assertEquals(1, decoded.level)
        assertNull(decoded.imageUrl)

        val kanji = decoded.subject as WaniKaniSubject.Kanji
        assertEquals("一", kanji.character)
        assertEquals(listOf("One", "Flat"), kanji.meanings)
        assertEquals("One", kanji.primaryMeaning)
        assertEquals(listOf("いち"), kanji.onyomi)
        assertEquals(listOf("ひと"), kanji.kunyomi)
        assertEquals(listOf(1), kanji.components.map { it.id })
        // A component is only an id until the client resolves it from the cache.
        assertNull(kanji.components.single().character)
        assertNull(kanji.components.single().name)
        assertEquals("https://www.wanikani.com/kanji/%E4%B8%80", kanji.documentUrl)
        assertEquals("The ground holds a single One stroke.", kanji.meaningMnemonic)
        assertEquals("Say specimen (いち) next to the One.", kanji.readingMnemonic)
    }

    @Test
    fun `a cached resource decodes back into the same subject`() {
        val decoded = decodeSubjectResource(WaniKaniJson.kanji())!!
        assertEquals(decoded.subject, decodeSubjectResource(decoded.json)?.subject)
        assertEquals(decoded.imageUrl, decodeSubjectResource(decoded.json)?.imageUrl)
    }

    @Test
    fun `an image-only radical keeps its image url and no character`() {
        val decoded = decodeSubjectResource(WaniKaniJson.radical(character = null))!!
        val radical = decoded.subject as WaniKaniSubject.Radical
        assertNull(radical.character)
        assertEquals("Ground", radical.primaryMeaning)
        assertEquals(listOf("Ground"), radical.meanings)
        assertEquals("https://cdn.wanikani.com/images/legacy/576-subject-1.svg?1520987227", decoded.imageUrl)
        // The image itself belongs to the client, which knows the image cache.
        assertNull(radical.svg)
    }

    @Test
    fun `a radical with a character still carries the image WaniKani offers`() {
        val decoded = decodeSubjectResource(WaniKaniJson.radical())!!
        val radical = decoded.subject as WaniKaniSubject.Radical
        assertEquals("一", radical.character)
        assertEquals("Ground", radical.primaryMeaning)
        assertEquals("https://www.wanikani.com/radicals/ground", radical.documentUrl)
        assertTrue(decoded.imageUrl!!.startsWith("https://cdn.wanikani.com/"))
    }

    @Test
    fun `a radical with no images at all has no image url`() {
        val decoded = decodeSubjectResource(WaniKaniJson.radical(character = null, imageUrl = null))!!
        assertNull(decoded.imageUrl)
        assertNull((decoded.subject as WaniKaniSubject.Radical).character)
    }

    @Test
    fun `a subject with no meanings falls back to its slug as the name`() {
        val withoutMeanings = WaniKaniJson.radical()
            .replace("""[{"meaning": "Ground", "primary": true, "accepted_answer": true}]""", "[]")
        val radical = decodeSubjectResource(withoutMeanings)!!.subject as WaniKaniSubject.Radical
        assertEquals(emptyList<String>(), radical.meanings)
        assertEquals("ground", radical.primaryMeaning)
    }

    @Test
    fun `a vocabulary subject is refused rather than half-read`() {
        assertNull(decodeSubjectResource(WaniKaniJson.vocabulary()))
        val collection = decodeSubjectCollection(WaniKaniJson.collection(WaniKaniJson.vocabulary()))
        assertEquals(emptyList<DecodedSubject>(), collection)
    }

    @Test
    fun `a malformed body is a miss, and a malformed collection is not an empty one`() {
        assertNull(decodeSubjectResource("not json"))
        assertNull(decodeSubjectResource(""))
        assertNull(decodeSubjectResource("""{"object":"kanji","id":440}"""))
        assertNull(decodeSubjectCollection("not json"))
        assertEquals(emptyList<DecodedSubject>(), decodeSubjectCollection(WaniKaniJson.collection()))
    }

    @Test
    fun `a by-id body says whether it was malformed or another kind of subject`() {
        assertEquals(SubjectBody.Malformed, decodeSubjectBody("not json"))
        assertEquals(SubjectBody.Malformed, decodeSubjectBody("<html>captive portal</html>"))
        assertEquals(SubjectBody.NotASubject, decodeSubjectBody(WaniKaniJson.vocabulary(id = 7)))
        assertEquals(SubjectBody.NotASubject, decodeSubjectBody("""{"object":"kanji","id":440}"""))

        val subject = decodeSubjectBody(WaniKaniJson.radical()) as SubjectBody.Subject
        assertEquals(1, subject.decoded.id)
    }

    @Test
    fun `a collection decodes every subject it carries`() {
        val collection = decodeSubjectCollection(
            WaniKaniJson.collection(WaniKaniJson.kanji(), WaniKaniJson.radical())
        )!!
        assertEquals(listOf(440, 1), collection.map { it.id })
        assertEquals("一", (collection[0].subject as WaniKaniSubject.Kanji).character)
        assertEquals("Ground", (collection[1].subject as WaniKaniSubject.Radical).primaryMeaning)
    }

    @Test
    fun `a document url that is not an https wanikani page is dropped`() {
        val elsewhere = decodeSubjectResource(WaniKaniJson.radical(documentUrl = "https://evil.example/radicals"))!!
        assertNull(elsewhere.subject.documentUrl)

        val insecure = decodeSubjectResource(
            WaniKaniJson.radical(documentUrl = "http://www.wanikani.com/radicals/ground")
        )!!
        assertNull(insecure.subject.documentUrl)

        val credentials = decodeSubjectResource(
            WaniKaniJson.radical(documentUrl = "https://user@www.wanikani.com/radicals/ground")
        )!!
        assertNull(credentials.subject.documentUrl)

        val subdomain = decodeSubjectResource(
            WaniKaniJson.radical(documentUrl = "https://www.wanikani.com.evil.example/radicals/ground")
        )!!
        assertNull(subdomain.subject.documentUrl)
    }

    @Test
    fun `a subscription decodes into the entitlement it grants`() {
        val recurring = decodeEntitlement(WaniKaniJson.user())!!
        assertEquals(60, recurring.maxLevelGranted)
        assertEquals(WaniKaniEntitlement.Type.RECURRING, recurring.type)
        val expiry = recurring.periodEndsAt!!
        assertTrue(expiry > 0)
        assertTrue(Instant.ofEpochMilli(expiry).toString().startsWith("2018-12-11T13:32:19"))
        assertEquals(60, recurring.maxLevelAt(expiry - 1))
        assertEquals(3, recurring.maxLevelAt(expiry))

        val free = decodeEntitlement(WaniKaniJson.user(type = "free", maxLevelGranted = 3, periodEndsAt = null))!!
        assertEquals(WaniKaniEntitlement.Type.FREE, free.type)
        assertNull(free.periodEndsAt)
        assertEquals(3, free.maxLevelAt(0))

        val lifetime = decodeEntitlement(WaniKaniJson.user(type = "lifetime", periodEndsAt = null))!!
        assertEquals(WaniKaniEntitlement.Type.LIFETIME, lifetime.type)
        assertEquals(60, lifetime.maxLevelAt(0))
    }

    @Test
    fun `a subscription whose period cannot be read grants only the free level`() {
        val missing = decodeEntitlement(WaniKaniJson.user(type = "recurring", periodEndsAt = null))!!
        assertEquals(0L, missing.periodEndsAt)
        assertEquals(3, missing.maxLevelAt(0))

        val broken = decodeEntitlement(WaniKaniJson.user(type = "recurring", periodEndsAt = "whenever"))!!
        assertEquals(0L, broken.periodEndsAt)
        assertEquals(3, broken.maxLevelAt(0))

        val unknown = decodeEntitlement(WaniKaniJson.user(type = "something_new"))!!
        assertEquals(WaniKaniEntitlement.Type.UNKNOWN, unknown.type)
        assertEquals(3, unknown.maxLevelAt(0))
    }

    @Test
    fun `a user body without a subscription, or another kind of body, is refused`() {
        assertNull(decodeEntitlement("not json"))
        assertNull(decodeEntitlement("""{"object":"user","data":{"username":"example_user"}}"""))
        assertNull(decodeEntitlement(WaniKaniJson.kanji()))
    }

    @Test
    fun `mnemonics become plain text with no markup left to inject`() {
        assertEquals("One and a half", plainWaniKaniText("<kanji>One</kanji> and a half"))
        assertEquals("ground", plainWaniKaniText("<radical>ground</radical>"))
        assertEquals("a & b < c", plainWaniKaniText("a &amp; b &lt; c"))
        assertEquals("it's", plainWaniKaniText("it&#39;s"))
        assertEquals("🍣", plainWaniKaniText("&#x1F363;"))
        assertEquals("🍣", plainWaniKaniText("&#127843;"))
        assertEquals("&bogus;", plainWaniKaniText("&bogus;"))
        assertEquals("plain", plainWaniKaniText("plain"))
        assertEquals("", plainWaniKaniText(""))
        // A tag with content that would be dangerous in a renderer keeps only its words.
        assertEquals("alert(1)", plainWaniKaniText("<script>alert(1)</script>"))
    }
}
