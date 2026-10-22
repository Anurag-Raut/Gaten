/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bounds an analytics read is held to before AnkiDroid is asked anything. The searches
 * themselves need a provider, so what is pinned here is what must never reach one: a blank search
 * (the provider would answer with the whole collection's card ids), an unbounded batch, and an
 * over-long string.
 */
class AnkiStatsTest {

    @Test
    fun aBlankSearchIsRefusedBecauseItWouldAskForTheWholeCollection() {
        assertNull(AnkiStats.invalidReason(listOf("deck:\"Gaten\"")))

        assertNotNull(AnkiStats.invalidReason(listOf("")))
        assertNotNull(AnkiStats.invalidReason(listOf("   ")))
        assertNotNull(AnkiStats.invalidReason(listOf("rated:1", " ")))
    }

    @Test
    fun anEmptyBatchIsRefused() {
        assertNotNull(AnkiStats.invalidReason(emptyList()))
    }

    @Test
    fun moreSearchesThanTheCapAreRefused() {
        val atCap = List(AnkiStats.MAX_QUERIES) { index -> "deck:d$index" }

        assertNull(AnkiStats.invalidReason(atCap))
        assertNotNull(AnkiStats.invalidReason(atCap + "deck:oneTooMany"))
    }

    @Test
    fun aSearchLongerThanTheCapIsRefused() {
        val long = "deck:\"" + "x".repeat(AnkiStats.MAX_QUERY_LENGTH) + "\""

        assertNotNull(AnkiStats.invalidReason(listOf(long)))
        assertNull(
            AnkiStats.invalidReason(listOf("deck:\"" + "x".repeat(AnkiStats.MAX_QUERY_LENGTH - 8) + "\""))
        )
    }

    @Test
    fun theDashboardsOwnSearchesAreAccepted() {
        val queries = listOf(
            "deck:\"Gaten\"",
            "deck:\"Gaten\" prop:ivl>=21",
            "rated:1",
            "prop:rated=0",
            "prop:rated=-1",
            "prop:rated=-2",
            "prop:rated=-3",
            "prop:rated=-4",
            "prop:rated=-5",
            "prop:rated=-6",
        )

        assertNull(AnkiStats.invalidReason(queries))
        assertEquals(10, queries.size)
    }
}
