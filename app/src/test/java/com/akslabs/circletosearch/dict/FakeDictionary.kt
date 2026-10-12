/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * YomiLens: Japanese screen lookup for immersion learners.
 *
 * Freehand circle / drag-select a region -> on-device Japanese OCR (ML Kit, Tesseract fallback)
 * -> offline JMdict lookup with deinflection -> result panel with real Anki (AnkiDroid) export.
 */

package com.akslabs.circletosearch.dict

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
