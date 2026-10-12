/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiExporterTest {

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
    fun cachedIdIsKeptWhileAnkiDroidStillListsIt() {
        assertEquals(7L, resolveId(stored = 7L, existing = setOf(6L, 7L)))
    }

    @Test
    fun vanishedIdIsRecreated() {
        assertNull(resolveId(stored = 7L, existing = setOf(6L)))
        assertNull(resolveId(stored = 7L, existing = emptySet()))
        assertNull(resolveId(stored = null, existing = setOf(6L)))
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
}
