/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.anki

import com.yomilens.app.dict.ScannedWord

/**
 * One Anki note: one property per field of the "YomiLens" note type.
 *
 * Anki renders every field as HTML, so the values are HTML too — the dictionary text is escaped and
 * the looked-up word in [sentence] is bolded. Nothing here touches Android, which is what makes the
 * mapping unit-testable.
 */
data class AnkiCard(
    val expression: String,
    val reading: String,
    val meaning: String,
    val sentence: String
) {
    companion object {
        /** Glosses kept in [meaning]; a flashcard is a memory aid, not a dictionary page. */
        private const val MAX_GLOSSES = 5

        /**
         * Builds the card for [word] from [sentence], the line the word was found in (empty when the
         * caller has no line to give).
         *
         * The sheet shows a word's first dictionary entry, so that entry is what lands on the card:
         * [expression] is its display form, falling back to [ScannedWord.baseForm] for a word the
         * dictionary has no entry for, [reading] its first kana form and [meaning] its first
         * [MAX_GLOSSES] glosses. In [sentence] the first occurrence of the surface is bolded.
         */
        fun from(word: ScannedWord, sentence: String): AnkiCard {
            val entry = word.entries.firstOrNull()
            return AnkiCard(
                expression = escapeHtml(entry?.primary ?: word.baseForm),
                reading = escapeHtml(entry?.kana?.firstOrNull().orEmpty()),
                meaning = escapeHtml(entry?.glosses?.take(MAX_GLOSSES)?.joinToString("; ").orEmpty()),
                sentence = boldSurface(sentence, word.surface)
            )
        }
    }
}

/**
 * [sentence] with the first occurrence of [surface] wrapped in `<b>`, HTML-escaped.
 *
 * The split runs on the raw text and each part is escaped on its own, so a surface holding HTML
 * metacharacters can neither be lost to escaping nor match in the wrong place.
 */
internal fun boldSurface(sentence: String, surface: String): String {
    val at = if (surface.isEmpty()) -1 else sentence.indexOf(surface)
    if (at < 0) return escapeHtml(sentence)
    return escapeHtml(sentence.take(at)) +
        "<b>" + escapeHtml(surface) + "</b>" +
        escapeHtml(sentence.drop(at + surface.length))
}

/** Escapes the three characters that would otherwise open markup inside an Anki field. */
internal fun escapeHtml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
