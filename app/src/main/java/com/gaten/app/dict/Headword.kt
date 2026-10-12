/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

/**
 * The form to show as [entry]'s headword for the scanned [word].
 *
 * The reader picked a form; the headword is that form whenever the entry has it — the direct hit
 * (事, こと) and the katakana surface over the entry's hiragana form (コーヒー) alike. A deinflected
 * surface (食べた) heads the entry with the dictionary form the match used, in this entry's own
 * spelling when it has one folding to the same string (食べる). Falls back to [DictEntry.primary].
 */
fun headwordFor(word: ScannedWord, entry: DictEntry): String =
    matchingForm(word, entry) ?: word.baseForm.ifEmpty { entry.primary }

/**
 * The headword for one entry in "Other matches": the selected form when this entry has it, else the
 * entry's own primary written form. Unlike [headwordFor] it never invents a form the entry lacks.
 */
fun alternativeHeadwordFor(word: ScannedWord, entry: DictEntry): String =
    matchingForm(word, entry) ?: entry.primary.ifEmpty { word.surface }

/**
 * The entry's own primary written form when it differs from the headword [headwordFor] shows —
 * "Also written: …" — or null when the headword already is that form.
 */
fun notedForm(word: ScannedWord, entry: DictEntry): String? {
    val primary = entry.primary
    return primary.takeIf { it.isNotEmpty() && it != headwordFor(word, entry) }
}

/**
 * The form of [entry] the reader's [word] should head with, or null when the entry has none of it.
 *
 * The exact surface wins first, then a katakana surface that only reaches the entry through its
 * hiragana fold. A deinflected surface instead yields the dictionary form [ScannedWord.baseForm] as
 * this entry spells it — exact, or a form folding to the same string.
 */
private fun matchingForm(word: ScannedWord, entry: DictEntry): String? {
    val surface = word.surface
    val baseForm = word.baseForm

    if (surface.isNotEmpty()) {
        if (entry.kanji.contains(surface) || entry.kana.contains(surface)) return surface
        if (surface != baseForm && baseForm == foldKatakanaToHiragana(surface)) return surface
    }
    if (baseForm.isEmpty()) return null

    if (entry.kanji.contains(baseForm) || entry.kana.contains(baseForm)) return baseForm
    val folded = foldKatakanaToHiragana(baseForm)
    return entry.kanji.firstOrNull { foldKatakanaToHiragana(it) == folded }
        ?: entry.kana.firstOrNull { foldKatakanaToHiragana(it) == folded }
}
