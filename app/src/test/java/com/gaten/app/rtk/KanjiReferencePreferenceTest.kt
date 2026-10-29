/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.rtk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kanji reference choice's own decisions: what a device that never chose shows, what survives a
 * restart, and what a write that did not take leaves in force. A fake store stands in for the
 * device, so none of this needs Android.
 */
class KanjiReferencePreferenceTest {

    private class FakeStorage(
        var stored: String? = null,
        var writable: Boolean = true
    ) : KanjiReferenceStorage {
        var writes = 0

        override fun source(): String? = stored

        override fun saveSource(value: String): Boolean {
            writes += 1
            if (!writable) return false
            stored = value
            return true
        }
    }

    @Test
    fun `a device that never chose shows WaniKani`() {
        val storage = FakeStorage()
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(storage).source()
        )
        assertEquals(0, storage.writes)
    }

    @Test
    fun `an unrecognised stored value falls back to WaniKani`() {
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(FakeStorage("heisig")).source()
        )
    }

    @Test
    fun `the choice is stored and survives a restart`() {
        val storage = FakeStorage()
        val preference = KanjiReferencePreference(storage)

        assertEquals(
            KanjiReferenceSource.RTK,
            preference.setSource(KanjiReferenceSource.RTK)
        )
        assertEquals("RTK", storage.stored)

        // A new instance over the same file is what a restart sees.
        assertEquals(KanjiReferenceSource.RTK, KanjiReferencePreference(storage).source())
    }

    @Test
    fun `a write that did not take leaves the previous source in force`() {
        val storage = FakeStorage(stored = "WANIKANI", writable = false)
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(storage).setSource(KanjiReferenceSource.RTK)
        )
        assertEquals("WANIKANI", storage.stored)
    }

    @Test
    fun `bridge spellings map to their sources and nothing else`() {
        assertEquals(KanjiReferenceSource.WANIKANI, KanjiReferenceSource.fromWire("wanikani"))
        assertEquals(KanjiReferenceSource.RTK, KanjiReferenceSource.fromWire("rtk"))
        assertNull(KanjiReferenceSource.fromWire("heisig"))
        assertNull(KanjiReferenceSource.fromWire(null))
    }
}
