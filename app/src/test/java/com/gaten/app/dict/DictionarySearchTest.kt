/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySearchTest {

    // --- what comes back, and in what order ------------------------------------------------

    @Test
    fun exactMatchComesFirstAndThePrefixPassDoesNotRepeatIt() {
        val hits = search("猫", dictionary("猫" to entry("猫"), "猫舌" to entry("猫舌")))

        assertEquals(SearchMatch.EXACT, hits.first().match)
        assertEquals("猫", hits.first().entry.primary)
        assertEquals(listOf(SearchMatch.EXACT, SearchMatch.PREFIX), hits.map { it.match })
        assertEquals("猫舌", hits[1].entry.primary)
        assertEquals(2, hits.map { it.entry.id }.distinct().size)
    }

    @Test
    fun kanaQueryReachesTheEntryThroughItsKanaForm() {
        val hits = search("ネコ", dictionary("ねこ" to entry("猫", kana = listOf("ねこ"))))

        assertEquals(1, hits.size)
        assertEquals(SearchMatch.NORMALIZED, hits.single().match)
        assertEquals("猫", hits.single().entry.primary)
        assertEquals("ねこ", hits.single().form)
    }

    @Test
    fun halfwidthKanaQueryIsFoldedToTheDictionaryForm() {
        val hits = search("ﾈｺ", dictionary("ねこ" to entry("猫", kana = listOf("ねこ"))))

        assertEquals(SearchMatch.NORMALIZED, hits.single().match)
        assertEquals("ねこ", hits.single().form)
    }

    @Test
    fun inflectedQueryReachesItsDictionaryForm() {
        val hits = search("食べた", dictionary("食べる" to entry("食べる")))

        assertEquals(1, hits.size)
        assertEquals(SearchMatch.NORMALIZED, hits.single().match)
        assertEquals("食べる", hits.single().form)
    }

    @Test
    fun nestedInflectionReachesItsDictionaryForm() {
        val hits = search("食べさせられた", dictionary("食べる" to entry("食べる")))

        assertEquals(SearchMatch.NORMALIZED, hits.single().match)
        assertEquals("食べる", hits.single().form)
    }

    /**
     * した is a word of its own and the past of する: the exact reading comes first, and the
     * deinflected one is kept behind it rather than dropped for it.
     */
    @Test
    fun aWordThatIsAlsoAnInflectionKeepsBothReadings() {
        val hits = search("した", dictionary("した" to entry("下", id = 1), "する" to entry("する", id = 2)))

        assertEquals(listOf(SearchMatch.EXACT, SearchMatch.NORMALIZED), hits.map { it.match })
        assertEquals(listOf("下", "する"), hits.map { it.entry.primary })
        assertEquals("する", hits[1].form)
    }

    /** A query with many homophones must not spend the whole list on them. */
    @Test
    fun homophonesDoNotDisplaceTheDeinflectedReading() {
        val rows = mapOf(
            "した" to (1..20).map { entry("下$it", id = it) },
            "する" to listOf(entry("する", id = 99))
        )

        val hits = search("した", FakeDictionary(rows))

        assertTrue(hits.any { it.entry.primary == "する" && it.match == SearchMatch.NORMALIZED })
    }

    @Test
    fun everyHomophoneOfTheQueryIsReturned() {
        val hits = search(
            "こう",
            dictionary("こう" to entry("甲", id = 1), "こう" to entry("乙", id = 2))
        )

        assertEquals(listOf("甲", "乙"), hits.map { it.entry.primary })
    }

    /** A single kana is a particle, an auxiliary or a stem; the deinflector must not answer た with る. */
    @Test
    fun loneKanaIsLookedUpAndNeverDeinflected() {
        val hits = search("た", dictionary("る" to entry("る")))

        assertTrue(hits.isEmpty())
    }

    @Test
    fun loneKanaFindsItsOwnEntry() {
        val hits = search("た", dictionary("た" to entry("田")))

        assertEquals(SearchMatch.EXACT, hits.single().match)
        assertEquals("田", hits.single().entry.primary)
    }

    /** A lone katakana is folded even though it is not deinflected: タ is た. */
    @Test
    fun aLoneKatakanaIsFoldedToItsHiragana() {
        val hits = search("タ", dictionary("た" to entry("田")))

        assertEquals(SearchMatch.NORMALIZED, hits.single().match)
        assertEquals("た", hits.single().form)
    }

    @Test
    fun aKatakanaQueryIsFoldedForItsPrefixSuggestionsToo() {
        val hits = search("タベ", dictionary("たべる" to entry("食べる")))

        assertEquals(SearchMatch.PREFIX, hits.single().match)
        assertEquals("食べる", hits.single().entry.primary)
    }

    /** Both prefix passes draw on one budget, and the kana spelling is asked first. */
    @Test
    fun theTwoPrefixPassesShareOneBudget() {
        val rows = mapOf(
            "た" to listOf(entry("た", id = 1)),
            "たべる" to listOf(entry("食べる", id = 2)),
            "タオル" to listOf(entry("タオル", id = 3))
        )

        val hits = search("タ", FakeDictionary(rows), limit = 4)

        assertEquals(listOf("た", "食べる", "タオル"), hits.map { it.entry.primary })
    }

    // --- what is not searched --------------------------------------------------------------

    @Test
    fun unsupportedEnglishIsNotSearched() {
        assertTrue(search("cat", dictionary("猫" to entry("猫"))).isEmpty())
        assertTrue(search("english", dictionary("ねこ" to entry("猫"))).isEmpty())
        assertTrue(search("123", dictionary("猫" to entry("猫"))).isEmpty())
    }

    // --- romaji ----------------------------------------------------------------------------

    @Test
    fun romajiIsLookedUpAsTheKanaItStandsFor() {
        val hits = search("neko", dictionary("ねこ" to entry("猫")))

        assertEquals(SearchMatch.EXACT, hits.single().match)
        assertEquals("猫", hits.single().entry.primary)
        assertEquals("ねこ", hits.single().form)
    }

    /** The kana the reader typed deinflects to the kana base form, which is what the index holds. */
    @Test
    fun inflectedRomajiReachesItsDictionaryForm() {
        val hits = search("tabeta", dictionary("たべる" to entry("食べる", kana = listOf("たべる"))))

        assertEquals(SearchMatch.NORMALIZED, hits.single().match)
        assertEquals("食べる", hits.single().entry.primary)
        assertEquals("たべる", hits.single().form)
    }

    /** "tokyo" is ときょ, which no reading spells; the collapsed reading is what reaches 東京. */
    @Test
    fun shortenedRomajiFallsBackToTheCollapsedReadings() {
        val dictionary = FakeDictionary(
            emptyMap(),
            mapOf("ときょ" to listOf(entry("東京", kana = listOf("とうきょう"))))
        )

        val hits = search("tokyo", dictionary)

        assertEquals(SearchMatch.LOOSE, hits.single().match)
        assertEquals("東京", hits.single().entry.primary)
        assertEquals("ときょ", hits.single().form)
    }

    /** The collapsed pass is the last one before the guesses, and it repeats nothing. */
    @Test
    fun collapsedMatchesComeBehindTheExactOnes() {
        val dictionary = FakeDictionary(
            rows = mapOf("おはよ" to listOf(entry("おはよ", id = 2))),
            looseRows = mapOf(
                "おはよ" to listOf(
                    entry("お早う", kana = listOf("おはよう"), id = 1),
                    entry("おはよ", id = 2)
                )
            )
        )

        val hits = search("ohayo", dictionary)

        assertEquals(listOf("おはよ", "お早う"), hits.map { it.entry.primary })
        assertEquals(listOf(SearchMatch.EXACT, SearchMatch.LOOSE), hits.map { it.match })
    }

    /** Prefix suggestions are guesses; a collapsed reading the index knows is a match. */
    @Test
    fun prefixSuggestionsDoNotComeBeforeCollapsedMatches() {
        val rows = (1..30).associate { "がっこ$it" to listOf(entry("がっこ$it", id = it)) }
        val dictionary = FakeDictionary(
            rows,
            mapOf("がっこ" to listOf(entry("学校", kana = listOf("がっこう"), id = 100)))
        )

        val hits = search("gakko", dictionary, limit = 4)

        assertEquals(4, hits.size)
        assertEquals(SearchMatch.LOOSE, hits.first().match)
        assertEquals("学校", hits.first().entry.primary)
    }

    @Test
    fun collapsedMatchesAreBoundedByTheLimit() {
        val many = (1..30).map { entry("学校$it", kana = listOf("がっこう$it"), id = it) }

        val hits = search("gakko", FakeDictionary(emptyMap(), mapOf("がっこ" to many)), limit = 3)

        assertEquals(3, hits.size)
    }

    /**
     * The collapsed fold is lossy and only a romaji reader needs it: a query typed in kana is
     * answered exactly as it was, whatever the index holds.
     */
    @Test
    fun aKanaQueryIsNotCollapsed() {
        val dictionary = FakeDictionary(
            mapOf("らめん" to listOf(entry("らめん", id = 2))),
            mapOf("らめん" to listOf(entry("ラーメン", kana = listOf("ラーメン"), id = 1)))
        )

        val hits = search("らめん", dictionary)

        assertEquals(listOf("らめん"), hits.map { it.entry.primary })
    }

    /** What the converter makes of the spellings the romaji study verified against the real index. */
    @Test
    fun romajiIsConvertedAsTheStudiedSpellingsExpect() {
        val conversions = mapOf(
            "neko" to "ねこ",
            "taberu" to "たべる",
            "sushi" to "すし",
            "tokyo" to "ときょ",
            "ramen" to "らめん",
            "ohayo" to "おはよ",
            "gakko" to "がっこ",
            "arigato" to "ありがと",
            "saikou" to "さいこう",
            "kirei" to "きれい",
            "byouin" to "びょういん",
            "toukyou" to "とうきょう",
            "shin'you" to "しんよう",
            "konnichiwa" to "こんにちわ",
            "ra-men" to "らーめん",
            "TOKYO" to "ときょ"
        )

        for ((romaji, kana) in conversions) {
            assertEquals(romaji, kana, DictionarySearch.kanaFromRomaji(romaji))
        }
    }

    /** An English word is read only as far as its letters look like romaji, so it is not romaji. */
    @Test
    fun englishAndDigitsAreNotRomaji() {
        assertNull(DictionarySearch.kanaFromRomaji("cat"))
        assertNull(DictionarySearch.kanaFromRomaji("english"))
        assertNull(DictionarySearch.kanaFromRomaji("123"))
        assertNull(DictionarySearch.kanaFromRomaji(""))
        assertNull(DictionarySearch.kanaFromRomaji("ねこ"))
    }

    @Test
    fun aSearchableQueryIsJapaneseOrRomaji() {
        assertTrue(DictionarySearch.isSearchable("猫"))
        assertTrue(DictionarySearch.isSearchable("ねこ"))
        assertTrue(DictionarySearch.isSearchable("neko"))
        assertTrue(DictionarySearch.isSearchable("tokyo"))
        assertFalse(DictionarySearch.isSearchable("cat"))
        assertFalse(DictionarySearch.isSearchable("english"))
        assertFalse(DictionarySearch.isSearchable("123"))
        assertFalse(DictionarySearch.isSearchable(""))
    }

    /**
     * The fold both sides of the index use — `tools/build_jmdict_db.py` pins the same table in
     * `LOOSE_PROBES`, and the shipped `loose` table was built from it.
     */
    @Test
    fun theCollapsedFoldKeepsItsPinnedForms() {
        val pins = listOf(
            "ときょ" to "ときょ",
            "らめん" to "らめん",
            "おはよ" to "おはよ",
            "がっこ" to "がっこ",
            "ありがと" to "ありがと",
            "さいこう" to "さいこ",
            "きれい" to "きれい",
            "びょういん" to "びょいん",
            "とうきょう" to "ときょ",
            "ラーメン" to "らめん",
            "らーめん" to "らめん",
            "らあめん" to "らめん",
            "おはよう" to "おはよ",
            "がっこう" to "がっこ",
            "ありがとう" to "ありがと",
            "サイコー" to "さいこ",
            "じゅうどう" to "じゅど",
            "じゅど" to "じゅど",
            "おおきい" to "おき",
            "ええ" to "え"
        )

        for ((kana, folded) in pins) {
            assertEquals(kana, folded, DictionarySearch.looseForm(kana))
        }
    }

    @Test
    fun whitespaceOnlyQueryIsEmpty() {
        assertTrue(search("   ", dictionary("猫" to entry("猫"))).isEmpty())
        assertTrue(search("\u3000", dictionary("猫" to entry("猫"))).isEmpty())
    }

    @Test
    fun anEmptyQueryIsNotSearched() {
        assertTrue(search("", dictionary("猫" to entry("猫"))).isEmpty())
    }

    /**
     * Wildcards are ordinary characters: the prefix pass asks the index for terms that start with
     * what was typed, so `猫%` matches nothing rather than every term starting with 猫.
     */
    @Test
    fun wildcardCharactersAreLiteral() {
        val words = dictionary("猫" to entry("猫"), "猫舌" to entry("猫舌"))

        assertTrue(search("猫%", words).isEmpty())
        assertTrue(search("猫_", words).isEmpty())
        assertEquals(2, search("猫", words).size)
    }

    // --- bounds ----------------------------------------------------------------------------

    @Test
    fun resultsAreBoundedByTheLimit() {
        val rows = (1..30).associate { "猫$it" to entry("猫$it", id = it) }

        assertEquals(5, search("猫", dictionary(rows), limit = 5).size)
    }

    @Test
    fun prefixSuggestionsTakeOnlyWhatTheRealMatchesLeft() {
        val rows = mapOf(
            "猫" to entry("猫", id = 1),
            "猫舌" to entry("猫舌", id = 2),
            "猫背" to entry("猫背", id = 3),
            "猫目" to entry("猫目", id = 4)
        )

        val hits = search("猫", dictionary(rows), limit = 2)

        assertEquals(2, hits.size)
        assertEquals(SearchMatch.EXACT, hits.first().match)
    }

    @Test
    fun foldedAndOriginalPrefixesShareOneSuggestionBudget() {
        val rows = (1..20).flatMap { index ->
            listOf(
                "たべ$index" to entry("たべ$index", id = index),
                "タブ$index" to entry("タブ$index", id = index + 20)
            )
        }.toMap()

        val hits = search("タ", dictionary(rows))

        assertEquals(8, hits.size)
        assertTrue(hits.all { it.match == SearchMatch.PREFIX })
    }

    // --- normalisation ---------------------------------------------------------------------

    @Test
    fun whitespaceIsDroppedFromTheQuery() {
        assertEquals("猫", DictionarySearch.normalize(" 猫 "))
        assertEquals("猫が", DictionarySearch.normalize("猫\u3000が"))
    }

    @Test
    fun compatibilityFormsAreComposed() {
        // Halfwidth katakana, with the voiced mark as its own character.
        assertEquals("ガ", DictionarySearch.normalize("ｶﾞ"))
        assertEquals("ネコ", DictionarySearch.normalize("ﾈｺ"))
        // Fullwidth Latin.
        assertEquals("AB", DictionarySearch.normalize("ＡＢ"))
        // A decomposed voiced kana.
        assertEquals("が", DictionarySearch.normalize("か\u3099"))
    }

    @Test
    fun theQueryIsBoundedWithoutSplittingASurrogatePair() {
        val kanji = "𠮷"
        val long = kanji.repeat(DictionarySearch.QUERY_LIMIT + 4)

        val normalized = DictionarySearch.normalize(long)

        assertEquals(DictionarySearch.QUERY_LIMIT, normalized.codePointCount(0, normalized.length))
        assertEquals(kanji.repeat(DictionarySearch.QUERY_LIMIT), normalized)
    }

    @Test
    fun japaneseIsKanaKanjiAndIterationMarks() {
        assertTrue(DictionarySearch.isJapanese("猫"))
        assertTrue(DictionarySearch.isJapanese("ねこ"))
        assertTrue(DictionarySearch.isJapanese("ネコ"))
        assertTrue(DictionarySearch.isJapanese("ﾈｺ"))
        assertTrue(DictionarySearch.isJapanese("𠮷"))
        assertTrue(DictionarySearch.isJapanese("時々"))
        assertFalse(DictionarySearch.isJapanese("neko"))
        assertFalse(DictionarySearch.isJapanese("123"))
        assertFalse(DictionarySearch.isJapanese(""))
    }

    // --- the indexed prefix range ----------------------------------------------------------

    @Test
    fun thePrefixBoundIsTheNextCodePoint() {
        assertEquals("ac", DictionarySearch.prefixUpperBound("ab"))
        assertEquals("\u732c", DictionarySearch.prefixUpperBound("猫"))
        assertEquals("\uD842\uDFB8", DictionarySearch.prefixUpperBound("𠮷"))
        assertEquals("", DictionarySearch.prefixUpperBound(""))
    }

    /**
     * The predicate the repository runs: every term that starts with the prefix sorts at or after it
     * and before its bound, and a term that does not is outside the range.
     */
    @Test
    fun everyTermStartingWithThePrefixFallsInsideItsRange() {
        val prefix = "猫"
        val bound = DictionarySearch.prefixUpperBound(prefix)

        for (term in listOf("猫", "猫舌", "猫背", "猫いらず")) {
            assertTrue(term >= prefix && term < bound)
        }
        for (term in listOf("犬", "ねこ", "貓")) {
            assertFalse(term >= prefix && term < bound)
        }
    }

    // --- fixtures --------------------------------------------------------------------------

    private fun search(
        query: String,
        dictionary: FakeDictionary,
        limit: Int = DictionarySearch.DEFAULT_LIMIT
    ): List<SearchHit> = DictionarySearch.search(
        raw = query,
        lookup = dictionary::lookup,
        suggest = dictionary::suggest,
        loose = dictionary::lookupLoose,
        limit = limit
    )

    /** One entry, keyed by the term that reaches it; [kana] is what a kana query folds onto. */
    private fun entry(
        primary: String,
        kana: List<String> = listOf(primary),
        id: Int = primary.hashCode()
    ): DictEntry = DictEntry(
        id = id,
        primary = primary,
        kanji = listOf(primary),
        kana = kana,
        glosses = listOf("gloss of $primary"),
        pos = listOf("n"),
        common = false
    )

    private fun dictionary(vararg rows: Pair<String, DictEntry>): FakeDictionary =
        FakeDictionary(rows.groupBy({ it.first }, { it.second }))

    private fun dictionary(rows: Map<String, DictEntry>): FakeDictionary =
        FakeDictionary(rows.mapValues { listOf(it.value) })
}

/**
 * A dictionary of terms: an exact accessor, a prefix accessor with the semantics of the indexed
 * range query — every term that starts with what was typed, in term order, an entry that several of
 * its terms reach returned once, and at most the requested number of entries — and the collapsed
 * reading accessor the romaji fallback uses, keyed by the folded reading the build writes.
 */
private class FakeDictionary(
    private val rows: Map<String, List<DictEntry>>,
    private val looseRows: Map<String, List<DictEntry>> = emptyMap()
) {

    fun lookup(term: String, limit: Int): List<DictEntry> = rows[term].orEmpty().take(limit)

    fun suggest(prefix: String, limit: Int): List<DictEntry> {
        val out = ArrayList<DictEntry>(limit)
        val seen = HashSet<Int>()
        for (entry in rows.filterKeys { it.startsWith(prefix) }.values.flatten()) {
            if (out.size >= limit) break
            if (seen.add(entry.id)) out.add(entry)
        }
        return out
    }

    fun lookupLoose(reading: String, limit: Int): List<DictEntry> =
        looseRows[reading].orEmpty().take(limit)
}
