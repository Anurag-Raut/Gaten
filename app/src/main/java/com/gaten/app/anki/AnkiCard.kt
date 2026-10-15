/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.ScannedWord

/**
 * One Anki note: one property per field of the "Gaten" note type.
 *
 * Anki renders every field as HTML, so the values are HTML too — the dictionary text is escaped.
 * Nothing here touches Android, which is what makes the mapping unit-testable.
 *
 * A card is the word and what the dictionary says about it: its readings and its meanings — never a
 * sentence, and never the line the word was read from.
 */
data class AnkiCard(
    val expression: String,
    val reading: String,
    val meaning: String
) {
    companion object {
        /** Glosses kept in [meaning]; a flashcard is a memory aid, not a dictionary page. */
        private const val MAX_GLOSSES = 5

        /**
         * Builds the card for [word] from its first dictionary entry — the one the sheet shows.
         *
         * [expression] is the entry's display form, falling back to [ScannedWord.baseForm] for a word
         * the dictionary has no entry for, [reading] its first kana form, and [meaning] its first
         * [MAX_GLOSSES] glosses over its parts of speech.
         */
        fun from(word: ScannedWord): AnkiCard {
            val entry = word.entries.firstOrNull()
            return AnkiCard(
                expression = escapeHtml(entry?.primary ?: word.baseForm),
                reading = escapeHtml(entry?.kana?.firstOrNull().orEmpty()),
                meaning = meaningOf(entry)
            )
        }

        /**
         * The glosses and, under them, the parts of speech — "cat; feline" over "noun (common)
         * (futsuumeishi)". A noun and a verb glossed the same are told apart by that second line,
         * which is what makes the field worth reviewing. Empty parts of speech leave it out.
         */
        private fun meaningOf(entry: DictEntry?): String {
            if (entry == null) return ""
            val glosses = escapeHtml(entry.glosses.take(MAX_GLOSSES).joinToString("; "))
            val pos = escapeHtml(entry.pos.joinToString(", "))
            return when {
                pos.isEmpty() -> glosses
                glosses.isEmpty() -> "<small>$pos</small>"
                else -> "$glosses<br><small>$pos</small>"
            }
        }
    }
}

/** Escapes the three characters that would otherwise open markup inside an Anki field. */
private fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
