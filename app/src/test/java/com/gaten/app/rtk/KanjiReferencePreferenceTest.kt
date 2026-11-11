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
 * The kanji reference choice's own decisions: what a device that never chose shows, how that
 * default follows the WaniKani connection, what a stored choice does, and what a write that did not
 * take leaves in force. A fake store stands in for the device, so none of this needs Android.
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
    fun `a device that never chose with no account shows Remembering the Kanji`() {
        val storage = FakeStorage()
        assertEquals(
            KanjiReferenceSource.RTK,
            KanjiReferencePreference(storage, wanikaniConfigured = false).source()
        )
        assertEquals(0, storage.writes)
    }

    @Test
    fun `a device that never chose with a connected account shows WaniKani`() {
        val storage = FakeStorage()
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(storage, wanikaniConfigured = true).source()
        )
        assertEquals(0, storage.writes)
    }

    @Test
    fun `an unrecognised stored value follows the connection`() {
        assertEquals(
            KanjiReferenceSource.RTK,
            KanjiReferencePreference(FakeStorage("heisig"), wanikaniConfigured = false).source()
        )
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(FakeStorage("heisig"), wanikaniConfigured = true).source()
        )
    }

    @Test
    fun `a stored choice is kept whatever the connection does`() {
        assertEquals(
            KanjiReferenceSource.RTK,
            KanjiReferencePreference(FakeStorage("RTK"), wanikaniConfigured = true).source()
        )
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(FakeStorage("WANIKANI"), wanikaniConfigured = false).source()
        )
    }

    @Test
    fun `the choice is stored and survives a restart`() {
        val storage = FakeStorage()
        val preference = KanjiReferencePreference(storage, wanikaniConfigured = true)

        assertEquals(KanjiReferenceSource.RTK, preference.setSource(KanjiReferenceSource.RTK))
        assertEquals("RTK", storage.stored)

        // A new instance over the same file is what a restart sees; a stored choice needs no
        // connection to read back, because the reader made it explicitly.
        assertEquals(
            KanjiReferenceSource.RTK,
            KanjiReferencePreference(storage, wanikaniConfigured = false).source()
        )
    }

    @Test
    fun `a write that did not take leaves the previous source in force`() {
        val storage = FakeStorage(stored = "WANIKANI", writable = false)
        assertEquals(
            KanjiReferenceSource.WANIKANI,
            KanjiReferencePreference(storage, wanikaniConfigured = true)
                .setSource(KanjiReferenceSource.RTK)
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
