/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import kotlin.math.abs

class MangaOcrCatalogTest {

    @Test
    fun idsAreUniqueSoTheInstalledModelCanBeNamed() {
        val ids = MangaOcrCatalog.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun everyUrlIsHuggingFaceOverHttps() {
        // The policy the downloader enforces, checked against every entry in the catalog.
        for (entry in MangaOcrCatalog.entries) {
            for (file in entry.files) {
                assertTrue(
                    "${entry.id}: ${file.url} is not an allowed URL",
                    DownloadPolicy.isAllowed(URI(file.url))
                )
            }
        }
    }

    @Test
    fun anEntryNamesEachFileOnceAndCoversEveryRole() {
        for (entry in MangaOcrCatalog.entries) {
            val names = entry.files.map { it.localName }

            assertEquals("${entry.id}: duplicate local names", names.size, names.toSet().size)
            assertEquals(
                "${entry.id}: the engine's three files",
                MangaOcrModel.Slot.entries.map { it.fileName }.toSet(),
                names.toSet()
            )
            assertTrue(
                "${entry.id}: no vocab file",
                names.contains(MangaOcrModel.Slot.VOCAB.fileName)
            )
        }
    }

    @Test
    fun theDeclaredSizeMatchesTheFiles() {
        for (entry in MangaOcrCatalog.entries) {
            val bytes = entry.files.sumOf { it.expectedBytes ?: 0L }

            // approxSizeMb is what the confirm dialog warns about, so it must not understate the
            // download by more than a rounding step.
            assertTrue(
                "${entry.id}: approxSizeMb ${entry.approxSizeMb} does not match $bytes bytes",
                abs(bytes / 1_000_000 - entry.approxSizeMb) <= 1
            )
        }
    }

    @Test
    fun theDefaultEntryIsOfferedAndFoundById() {
        assertTrue(MangaOcrCatalog.entries.contains(MangaOcrCatalog.defaultEntry))
        assertEquals(
            MangaOcrCatalog.defaultEntry,
            MangaOcrCatalog.byId(MangaOcrCatalog.defaultEntry.id)
        )
        assertNull(MangaOcrCatalog.byId("no-such-model"))
        assertNull(MangaOcrCatalog.byId(null))
    }

    @Test
    fun anInstalledModelIsIdentifiedByItsFileSizes() {
        val entry = MangaOcrCatalog.defaultEntry
        val sizes = entry.files.associate { file ->
            val slot = MangaOcrModel.Slot.entries.first { it.fileName == file.localName }
            slot to (file.expectedBytes ?: 0L)
        }

        assertEquals(entry, MangaOcrCatalog.identify(sizes))

        val wrong = sizes.toMutableMap().apply { this[MangaOcrModel.Slot.DECODER] = 1L }
        assertNull(MangaOcrCatalog.identify(wrong))
    }
}
