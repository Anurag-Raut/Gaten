/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The cache on disk: what is written comes back, what is missing or corrupt is a miss rather than a
 * crash or a wrong answer, and nothing is left half-written behind.
 */
class WaniKaniStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root by lazy { File(folder.root, "wanikani") }

    private fun store() = WaniKaniStore(root)

    @Test
    fun `a subject is cached and read back`() {
        val store = store()
        assertNull(store.subject(440))

        assertTrue(store.saveSubject(440, WaniKaniJson.kanji()))
        assertEquals(WaniKaniJson.kanji(), store.subject(440))
        assertNull(store.subject(1))

        // A second store over the same directory sees it: the cache is the directory, not the object.
        assertEquals(WaniKaniJson.kanji(), store().subject(440))
    }

    @Test
    fun `the character index is kept across instances, without repeats`() {
        val store = store()
        assertEquals(emptyMap<String, Int>(), store.index())

        assertTrue(store.putIndex("一", 440))
        assertTrue(store.putIndex("語", 500))
        assertTrue(store.putIndex("一", 440))
        assertEquals(mapOf("一" to 440, "語" to 500), store().index())

        assertTrue(store.putIndex("一", 441))
        assertEquals(mapOf("一" to 441, "語" to 500), store().index())
    }

    @Test
    fun `a corrupt index line is dropped, not guessed`() {
        val store = store()
        store.putIndex("一", 440)
        File(root, "index.tsv").writeText("一\t440\n語\tnonsense\nbroken\n\n奔走\t500\n")

        assertEquals(mapOf("一" to 440, "奔走" to 500), store.index())
    }

    @Test
    fun `an image is cached by url`() {
        val store = store()
        val first = "https://cdn.wanikani.com/images/legacy/576-subject-1.svg?1520987227"
        val second = "https://cdn.wanikani.com/images/legacy/577-subject-2.svg?1520987227"

        assertNull(store.image(first))
        assertTrue(store.saveImage(first, "<svg>ground</svg>"))
        assertTrue(store.saveImage(second, "<svg>two</svg>"))
        assertEquals("<svg>ground</svg>", store.image(first))
        assertEquals("<svg>two</svg>", store.image(second))

        assertTrue(store.saveImage(first, "<svg>replaced</svg>"))
        assertEquals("<svg>replaced</svg>", store().image(first))
        assertEquals("<svg>two</svg>", store().image(second))
    }

    @Test
    fun `the entitlement is cached and read back`() {
        val store = store()
        assertNull(store.entitlement())

        val entitlement = WaniKaniEntitlement(60, WaniKaniEntitlement.Type.RECURRING, 1_544_495_139_485L)
        assertTrue(store.saveEntitlement(entitlement))
        assertEquals(entitlement, store().entitlement())
    }

    @Test
    fun `a corrupt entitlement file reads as no entitlement`() {
        val store = store()
        store.saveEntitlement(WaniKaniEntitlement(60, WaniKaniEntitlement.Type.LIFETIME, null))
        File(root, "entitlement.json").writeText("{\"max_level_granted\":")

        assertNull(store.entitlement())
    }

    @Test
    fun `clearing leaves nothing, and reports itself`() {
        val store = store()
        store.saveSubject(440, WaniKaniJson.kanji())
        store.putIndex("一", 440)
        store.saveImage("https://cdn.wanikani.com/x.svg", "<svg/>")
        store.saveEntitlement(WaniKaniEntitlement(60, WaniKaniEntitlement.Type.LIFETIME, null))

        assertTrue(store.clear())
        assertFalse(root.exists())

        assertNull(store.subject(440))
        assertEquals(emptyMap<String, Int>(), store.index())
        assertNull(store.entitlement())
        // Clearing a cache that is not there is not a failure.
        assertTrue(store.clear())
    }

    @Test
    fun `a not-found answer is recorded by character and by subject id, without collision`() {
        val store = store()
        assertNull(store.missingCharacter("験"))
        assertNull(store.missingSubject(7))

        assertTrue(store.putMissingCharacter("験", 20_000))
        assertTrue(store.putMissingSubject(7, 20_001))
        assertEquals(20_000L, store.missingCharacter("験"))
        assertEquals(20_001L, store.missingSubject(7))

        // The same text as a character and as an id are two different answers.
        assertTrue(store.putMissingCharacter("7", 3))
        assertEquals(3L, store.missingCharacter("7"))
        assertEquals(20_001L, store.missingSubject(7))
        assertNull(store.missingSubject(3))

        // Kept across instances, and rewritten rather than repeated.
        assertEquals(20_000L, store().missingCharacter("験"))
    }

    @Test
    fun `a corrupt not-found line is dropped, not trusted`() {
        val store = store()
        assertTrue(store.putMissingCharacter("験", 20_000))
        File(root, "missing.tsv").writeText(
            "c:験\t20000\nc:飴\tnonsense\nbroken\nc:弩\t-5\n\ni:7\t20001\n"
        )

        assertEquals(20_000L, store.missingCharacter("験"))
        assertEquals(20_001L, store.missingSubject(7))
        assertNull(store.missingCharacter("飴"))
        assertNull(store.missingCharacter("弩"))
        assertNull(store.missingSubject(8))
    }

    @Test
    fun `the budget evicts the least recently used content first`() {
        // A budget small enough to be exercised: four entries of ~1000 bytes fit, a fifth does not.
        val store = WaniKaniStore(root, budgetBytes = 4_500)
        val body = "x".repeat(1_000)
        val subjects = (1..4).associateWith { id ->
            assertTrue(store.saveSubject(id, body))
            File(root, "subjects/$id.json")
        }

        // The most recently written entry is the oldest by use, and must be the one that goes.
        val stamp = System.currentTimeMillis() - 10_000
        subjects.forEach { (id, file) -> file.setLastModified(stamp + (10 - id) * 1_000L) }

        assertTrue(store.saveSubject(5, body))

        assertNull(store.subject(4))
        assertEquals(body, store.subject(1))
        assertEquals(body, store.subject(2))
        assertEquals(body, store.subject(3))
        assertEquals(body, store.subject(5))
    }

    @Test
    fun `reading an entry makes it the most recently used one`() {
        val store = WaniKaniStore(root, budgetBytes = 4_500)
        val body = "y".repeat(1_000)
        listOf(1, 2, 3).forEach { id -> assertTrue(store.saveSubject(id, body)) }
        val stamp = System.currentTimeMillis() - 10_000
        listOf(1, 2, 3).forEach { id -> File(root, "subjects/$id.json").setLastModified(stamp + id * 1_000L) }

        // Reading the oldest entry re-dates it, so the budget takes the next one instead.
        assertEquals(body, store.subject(1))
        assertTrue(store.saveSubject(9, "z".repeat(1_600)))

        assertEquals(body, store.subject(1))
        assertNull(store.subject(2))
        assertEquals(body, store.subject(3))
    }

    @Test
    fun `the budget never evicts the entitlement or the indexes`() {
        val store = WaniKaniStore(root, budgetBytes = 500)
        val entitlement = WaniKaniEntitlement(60, WaniKaniEntitlement.Type.LIFETIME, null)
        assertTrue(store.saveEntitlement(entitlement))
        assertTrue(store.putIndex("一", 440))
        assertTrue(store.putMissingSubject(7, 20_000))
        assertTrue(store.saveSubject(1, "x".repeat(4_000)))
        assertTrue(store.saveImage("https://cdn.wanikani.com/x.svg", "<svg/>".repeat(400)))

        // The budget cannot be met with the entitlement and the indexes in place, so every subject
        // and image is gone - and what a lookup is allowed to show, and what it can read offline, is
        // not.
        assertNull(store.subject(1))
        assertNull(store.image("https://cdn.wanikani.com/x.svg"))
        assertTrue(root.exists())
        assertEquals(entitlement, store.entitlement())
        assertEquals(mapOf("一" to 440), store.index())
        assertEquals(20_000L, store.missingSubject(7))
    }

    @Test
    fun `a subject that exists forgets the not-found answer it overtook`() {
        val store = store()
        assertTrue(store.putMissingSubject(7, 20_000))
        assertTrue(store.putMissingCharacter("験", 20_000))

        assertTrue(store.saveSubject(7, WaniKaniJson.kanji(id = 7)))
        assertTrue(store.putIndex("験", 700))

        assertNull(store.missingSubject(7))
        assertNull(store.missingCharacter("験"))

        // An answer for anything else is left where it is.
        assertTrue(store.putMissingCharacter("飴", 20_000))
        assertTrue(store.saveSubject(9, "x"))
        assertEquals(20_000L, store.missingCharacter("飴"))
        assertNull(store.missingSubject(9))
    }

    @Test
    fun `no temp file survives a write`() {
        val store = store()
        store.saveSubject(440, WaniKaniJson.kanji())
        store.putIndex("一", 440)
        store.saveImage("https://cdn.wanikani.com/x.svg", "<svg/>")
        store.saveEntitlement(WaniKaniEntitlement(60, WaniKaniEntitlement.Type.LIFETIME, null))

        val leftovers = root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()
        assertEquals(emptyList<File>(), leftovers)
    }
}
