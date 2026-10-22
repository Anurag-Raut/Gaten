/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The romaji path against the database this build ships: the asset is opened the way the search
 * screen opens it, and the spellings the romaji study verified are looked up through it.
 *
 * The build script pins the same expectations against the produced file with sqlite3
 * (`ROMAJI_PROBES` in `tools/build_jmdict_db.py`); this pins that the app's own converter, fold and
 * queries arrive at them too — a `loose` table built from a different fold, or missing its index,
 * answers the build and not the device. Needs the generated asset
 * (`python3 tools/build_jmdict_db.py`), which `preBuild` already checks for.
 */
@RunWith(AndroidJUnit4::class)
class RomajiDictionarySearchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** What the reader types, and a spelling the answer must carry (headword or reading). */
    private val pins = listOf(
        "tokyo" to "東京",
        "ramen" to "ラーメン",
        "ohayo" to "お早う",
        "gakko" to "学校",
        "arigato" to "有難う",
        "kirei" to "綺麗",
        "saikou" to "最高",
        "byouin" to "病院"
    )

    @Test
    fun everyRomajiPinIsAnsweredFromTheShippedDatabase() = runBlocking {
        val repository = JmdictRepository.open(context)
        try {
            for ((romaji, expected) in pins) {
                val hits = search(repository, romaji)
                assertTrue(
                    "$romaji did not reach $expected: " +
                        hits.map { "${it.entry.primary}(${it.match})" },
                    hits.any { it.entry.carries(expected) }
                )
            }
        } finally {
            repository.close()
        }
    }

    /**
     * The shortened spellings are the ones the exact pass cannot answer, so they must be answered by
     * the collapsed one — and only ever behind everything the exact passes reached.
     */
    @Test
    fun shortenedRomajiIsAnsweredByTheCollapsedReadingsBehindTheExactOnes() = runBlocking {
        val repository = JmdictRepository.open(context)
        try {
            for ((romaji, expected) in pins.take(5)) {
                val hits = search(repository, romaji)
                val firstCollapsed = hits.indexOfFirst { it.match == SearchMatch.LOOSE }
                assertTrue("$romaji: no collapsed match: ${hits.map { it.match }}", firstCollapsed >= 0)
                assertTrue(
                    "$romaji: a guess came ahead of the collapsed matches: ${hits.map { it.match }}",
                    hits.take(firstCollapsed).none {
                        it.match == SearchMatch.PREFIX
                    }
                )
                assertTrue(
                    "$romaji: the collapsed pass did not reach $expected",
                    hits.drop(firstCollapsed).any { it.entry.carries(expected) }
                )
            }
        } finally {
            repository.close()
        }
    }

    private fun search(repository: JmdictRepository, query: String): List<SearchHit> =
        DictionarySearch.search(
            raw = query,
            lookup = repository::lookup,
            suggest = repository::lookupPrefix,
            loose = repository::lookupLoose
        )

    /** True when [spelling] is one of the entry's own forms. */
    private fun DictEntry.carries(spelling: String): Boolean =
        primary == spelling || spelling in kanji || spelling in kana
}
