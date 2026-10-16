/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.search

import com.gaten.app.anki.AnkiSettings
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.SearchHit
import com.gaten.app.dict.SearchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchStateTest {
    private val original = AnkiSettings()
    private val changed = original.copy(front = "{{Expression}}<br>{{Reading}}")

    @Test
    fun changingCardTemplatesMakesAnOldExportAvailableAgain() {
        val completed = ExportProgress(ExportState.Done(ExportOutcome.ADDED), original, AnkiStatus.Ready)
        val state = SearchState(
            ankiSettings = changed,
            ankiStatus = AnkiStatus.Ready,
            exports = mapOf(1 to completed)
        )

        assertEquals(ExportState.Idle, state.export(1))
        assertEquals(completed.state, state.copy(ankiSettings = original).export(1))
    }

    @Test
    fun changingSettingsKeepsAnInFlightExportVisiblyBusy() {
        val state = SearchState(
            ankiSettings = changed,
            ankiStatus = AnkiStatus.Ready,
            exports = mapOf(1 to ExportProgress(ExportState.Busy, original, AnkiStatus.Ready))
        )

        assertEquals(ExportState.Busy, state.export(1))
    }

    @Test
    fun anEntryIsResolvedByTheIdItsRouteCarries() {
        val state = SearchState(hits = listOf(hit(7), hit(9)))

        assertEquals(9, state.hitFor(9)?.entry?.id)
        assertNull(state.hitFor(8))
    }

    /**
     * The lookup is over the rows and nothing else. A word page whose row is not back yet reads as
     * unresolved — which is what keeps it on screen while the restored search runs — and resolves
     * the moment it is, whatever the screen's own status says.
     */
    @Test
    fun anEntryIsResolvedFromTheRowsAloneWhateverTheStatus() {
        assertNull(SearchState(status = SearchStatus.Searching).hitFor(7))
        assertEquals(
            7,
            SearchState(status = SearchStatus.Searching, hits = listOf(hit(7))).hitFor(7)?.entry?.id
        )
    }

    private fun hit(id: Int) = SearchHit(
        entry = DictEntry(
            id = id,
            primary = "猫",
            kanji = listOf("猫"),
            kana = listOf("ねこ"),
            glosses = listOf("cat"),
            pos = listOf("noun"),
            common = true
        ),
        match = SearchMatch.EXACT,
        form = "猫"
    )
}
