/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.dict

/** Stand-in entry for the fake dictionaries used by the pure JVM tests. */
internal fun dictEntry(term: String, id: Int = 1): DictEntry = DictEntry(
    id = id,
    primary = term,
    kanji = listOf(term),
    kana = listOf(term),
    glosses = listOf("gloss of $term"),
    pos = listOf("n"),
    common = false
)

/** A lookup lambda that only knows [terms]; every other term yields no entries. */
internal fun dictionaryOf(vararg terms: String): (String) -> List<DictEntry> {
    val byTerm = terms
        .mapIndexed { index, term -> term to listOf(dictEntry(term, index + 1)) }
        .toMap()
    return { term -> byTerm[term] ?: emptyList() }
}
