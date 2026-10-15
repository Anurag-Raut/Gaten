/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiExporterTest {

    private val defaults = AnkiSettings()

    @Test
    fun ankiDroidMissingHidesTheAction() {
        assertEquals(
            AnkiStatus.NotInstalled,
            ankiStatus(installed = false, permissionGranted = true)
        )
        assertEquals(
            AnkiStatus.NotInstalled,
            ankiStatus(installed = false, permissionGranted = false)
        )
    }

    @Test
    fun ankiDroidWithoutItsPermissionAsksForIt() {
        assertEquals(
            AnkiStatus.NeedsPermission,
            ankiStatus(installed = true, permissionGranted = false)
        )
    }

    @Test
    fun ankiDroidWithItsPermissionIsReady() {
        assertEquals(AnkiStatus.Ready, ankiStatus(installed = true, permissionGranted = true))
    }

    @Test
    fun existingNoteWinsOverTheAdd() {
        assertEquals(AddOutcome.DUPLICATE, addOutcome(duplicate = true, added = true))
        assertEquals(AddOutcome.DUPLICATE, addOutcome(duplicate = true, added = false))
    }

    @Test
    fun acceptedNoteReportsAdded() {
        assertEquals(AddOutcome.ADDED, addOutcome(duplicate = false, added = true))
    }

    @Test
    fun rejectedNoteReportsFailure() {
        assertEquals(AddOutcome.FAILED, addOutcome(duplicate = false, added = false))
    }

    @Test
    fun cachedDeckIsKeptWhileItStillCarriesTheConfiguredName() {
        val decks = mapOf(1L to "Default", 7L to "Gaten")
        assertEquals(7L, resolveDeckId(cachedId = 7L, decks = decks, deckName = "Gaten"))
    }

    @Test
    fun deckRenamedInAnkiDroidIsNotKeptByItsCachedId() {
        val decks = mapOf(1L to "Default", 7L to "Japanese")
        assertNull(resolveDeckId(cachedId = 7L, decks = decks, deckName = "Gaten"))
    }

    @Test
    fun deckIsFoundByItsExactName() {
        val decks = mapOf(1L to "Default", 3L to "Gaten", 4L to "Gaten::JLPT")
        assertEquals(3L, resolveDeckId(cachedId = null, decks = decks, deckName = "Gaten"))
        assertEquals(4L, resolveDeckId(cachedId = 1L, decks = decks, deckName = "Gaten::JLPT"))
        assertNull(resolveDeckId(cachedId = 1L, decks = decks, deckName = "Gaten::JLPT N5"))
    }

    @Test
    fun missingDeckIsCreated() {
        assertNull(resolveDeckId(cachedId = null, decks = emptyMap(), deckName = "Gaten"))
        assertNull(resolveDeckId(cachedId = 9L, decks = mapOf(1L to "Default"), deckName = "Gaten"))
    }

    @Test
    fun duplicateKeyIsTheFirstField() {
        assertEquals("食べる", duplicateKey(arrayOf("食べる", "たべる")))
        assertEquals("", duplicateKey(emptyArray()))
        assertEquals("", duplicateKey(arrayOf("", "たべる")))
    }

    @Test
    fun defaultLayoutKeepsTheNoteTypeNameOlderGatenCreated() {
        assertEquals("Gaten", managedModelName(defaults))
    }

    @Test
    fun eachLayoutGetsItsOwnNoteTypeName() {
        val custom = defaults.copy(front = "{{Expression}}<br>{{Reading}}")
        val other = defaults.copy(css = ".card { text-align: left; }")
        val name = managedModelName(custom)
        assertTrue(name.startsWith("Gaten "))
        assertNotEquals("Gaten", name)
        assertEquals(16, name.removePrefix("Gaten ").length)
        assertTrue(name.removePrefix("Gaten ").all { it in "0123456789abcdef" })
        assertEquals(name, managedModelName(custom.copy(deckName = "JLPT", tags = "n5")))
        assertNotEquals(name, managedModelName(other))
    }
}
