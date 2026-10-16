/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.dict

import dev.esnault.wanakana.core.Wanakana
import java.text.Normalizer

/**
 * How one result reached the query.
 *
 * [EXACT] is the query as the dictionary spells it. [NORMALIZED] is the query after the dictionary's
 * own folding — the kana fold (ネコ -> ねこ) or deinflection (食べた -> 食べる). [LOOSE] is the
 * query's long vowels collapsed (らめん -> ラーメン), a lossy fold only a romaji reader needs and only
 * reached after everything above has had its turn. [PREFIX] is a term that merely starts with the
 * query, offered behind the entries that match it exactly.
 */
enum class SearchMatch { EXACT, NORMALIZED, LOOSE, PREFIX }

/**
 * One row of the search screen: the entry, how it matched, and the form of it that matched.
 *
 * [form] is the spelling that reached [entry]: the query itself for an exact or a prefix match, the
 * folded or deinflected form for a normalised one (so 食べた shows the entry of 食べる under "from
 * 食べる"), and the collapsed spelling for a loose one.
 */
data class SearchHit(val entry: DictEntry, val match: SearchMatch, val form: String)

/**
 * The dictionary search behind the app's search screen: one typed query, ranked, bounded and
 * deduplicated.
 *
 * Deliberately not [WordScanner]: that identifies words inside a line of OCR output, so it drops the
 * lone kana a search box is entitled to ask about (が, ん), ranks kanji-bearing words over everything
 * else, and reads at most its own match length from each position. A search box holds the whole
 * query, so this looks that query up directly and answers with nothing the reader did not ask for.
 *
 * Pure: the dictionary arrives as two accessors, nothing here touches Android or a database, and the
 * normalisation, the ranking, the bounds and the deduplication are therefore unit-tested directly.
 */
object DictionarySearch {

    /** Rows one search may return, prefix suggestions included. */
    const val DEFAULT_LIMIT = 24

    /** Prefix suggestions kept, so a query of one kana cannot fill the list with its own extensions. */
    private const val PREFIX_LIMIT = 8

    /**
     * Exact matches kept before the folded and deinflected readings get their turn. A query with
     * several homophones (した is 下, 舌 and more) would otherwise take the whole list and lose the
     * reading the reader typed the form of.
     */
    private const val EXACT_LIMIT = 12

    /**
     * Prefix entries asked for per suggestion wanted. The query itself is the first term the index
     * range returns, and it is one of the entries already in the list, so one row per suggestion
     * would spend a slot on something the reader can already see.
     */
    private const val PREFIX_OVER_REQUEST = 2

    /**
     * Longest query acted on, in code points. The search box stops accepting input here and says so,
     * rather than looking up a truncated word as if the reader had typed it: a query that long is a
     * paste, not a word.
     */
    const val QUERY_LIMIT = 32

    /**
     * Characters read from [normalize]'s input before composing it. NFKC does work proportional to
     * the input, and a caller may hand this a paste; the bound is far above [QUERY_LIMIT] so nothing
     * a reader types is ever cut.
     */
    private const val MAX_INPUT_LENGTH = 128

    /**
     * Deinflection candidates probed before giving up. A query that is not an inflection costs this
     * many index probes at most — in practice none, since a candidate only exists when the query
     * ends in a rule's suffix.
     */
    private const val MAX_DEINFLECTION_LOOKUPS = 8

    /**
     * Deinflections kept behind the exact matches. A query can be read more than one way (話した is
     * 話す, 食べさせられた is 食べる through the deinflector's own chaining), but a list of a dozen
     * guesses is not an answer.
     */
    private const val MAX_DEINFLECTIONS = 3

    /** The last code point there is; U+10FFFF is a noncharacter, so no prefix ever ends on it. */
    private const val MAX_CODE_POINT = 0x10FFFF

    /** The last ASCII code point: what a romaji query is made of, and what a conversion must consume. */
    private const val ASCII_LIMIT = 0x7F

    /** Katakana ァ to ヶ, and what folding one of them down to hiragana subtracts. */
    private val KATAKANA = 'ァ'..'ヶ'
    private const val KATAKANA_FOLD = 0x60

    /** The prolonged sound mark, which a collapsed reading does not keep. */
    private const val LONG_VOWEL_MARK = 'ー'

    /**
     * Kana a dropped vowel may follow ([looseForm]), by sound rather than spelling: a voiced or
     * half-voiced kana belongs to its row (ど takes う like と), without which じゅうどう would keep a
     * vowel the reader's own じゅど never had.
     */
    private val ROW_U = "うくすつぬふむゆるゅゔぐずづぶぷ".toSet()
    private val ROW_O = "こそとのほもよろをぉょごぞどぼぽ".toSet()
    private val ROW_A = "かさたなはまやらわゃがざだばぱ".toSet()
    private val ROW_I = "きしちにひみりゅぎじぢびぴ".toSet()

    /** Vowels that collapse when written twice (おお -> お, ええ -> え). */
    private val VOWELS = "あいうえお".toSet()

    /**
     * The results for [raw], most likely first: the whole query as a dictionary form, then its kana
     * fold and its deinflections, then the long vowels of a romaji query collapsed, then the terms
     * that merely start with it.
     *
     * The query is normalised first ([normalize]) and romaji is turned into kana before anything is
     * looked up ([kanaFromRomaji]), because the dictionary holds no romaji: "neko" is ねこ and
     * "tabeta" is 食べた. A query that is neither Japanese nor romaji the converter can read in full
     * is answered with nothing at all, and the screen says so rather than showing an empty list.
     *
     * The folded and deinflected readings come behind the exact matches rather than instead of them,
     * because a query can honestly be both: した is 下 and 舌, and it is also the past of する, and
     * only the reader knows which was meant. The single exception is a query of one character — a
     * lone kana is a particle, an auxiliary or a verb stem, and the deinflector would answer た with
     * る.
     *
     * [lookup] is the exact accessor (`JmdictRepository::lookup`), [suggest] the indexed prefix one
     * (`JmdictRepository::lookupPrefix`) and [loose] the collapsed-reading one
     * (`JmdictRepository::lookupLoose`). Every pass is bounded, an entry reached twice is returned
     * once, and at most [limit] rows come back.
     */
    fun search(
        raw: String,
        lookup: (String, Int) -> List<DictEntry>,
        suggest: (String, Int) -> List<DictEntry>,
        loose: (String, Int) -> List<DictEntry>,
        limit: Int = DEFAULT_LIMIT
    ): List<SearchHit> {
        val query = normalize(raw)
        if (query.isEmpty() || limit <= 0) return emptyList()

        val romaji = kanaFromRomaji(query)
        val kana = romaji ?: query
        if (!isJapanese(kana)) return emptyList()

        val hits = ArrayList<SearchHit>(minOf(limit, 16))
        val seen = HashSet<Int>(32)

        fun add(entries: List<DictEntry>, match: SearchMatch, form: String, maxHits: Int = limit) {
            for (entry in entries) {
                if (hits.size >= maxHits) return
                if (seen.add(entry.id)) hits.add(SearchHit(entry, match, form))
            }
        }

        add(lookup(kana, minOf(limit, EXACT_LIMIT)), SearchMatch.EXACT, kana)

        if (hits.size < limit) {
            val folded = foldKatakanaToHiragana(kana)
            if (folded != kana) add(lookup(folded, limit), SearchMatch.NORMALIZED, folded)
            // Deinflection is skipped for a single character — a lone kana is a particle, an
            // auxiliary or a verb stem, and the deinflector would answer た with る — but the kana
            // fold above is not: タ is た, and the dictionary is written in た.
            if (folded.codePointCount(0, folded.length) > 1) {
                for ((form, entries) in deinflections(folded, lookup, limit)) {
                    if (hits.size >= limit) break
                    add(entries, SearchMatch.NORMALIZED, form)
                }
            }
        }

        // The reader who spells a word in romaji usually leaves the long vowel out — "tokyo",
        // "ramen", "gakko" — and no dictionary stores those spellings, so the kana above is looked
        // up once more with its long vowels collapsed. That fold is lossy, so it comes behind
        // everything the reader may have meant exactly and ahead of the prefix suggestions, which
        // are guesses rather than matches. A query typed in kana is never folded this way: らめん is
        // not how a Japanese reader shortens ラーメン, and a Japanese query's results are what they
        // were.
        if (romaji != null && hits.size < limit) {
            val collapsed = looseForm(kana)
            add(loose(collapsed, limit), SearchMatch.LOOSE, collapsed)
        }

        // Suggestions take what the exact matches left, and nothing when they filled the list: a
        // query the dictionary knows exactly has already answered the reader. Twice the room is
        // asked for because the query is itself the first term the index returns, and that row is
        // one of the entries already above.
        val suggestions = minOf(PREFIX_LIMIT, limit - hits.size)
        if (suggestions > 0) {
            val prefixEnd = hits.size + suggestions
            val folded = foldKatakanaToHiragana(kana)
            // The kana spelling first: a katakana query is usually reaching for the dictionary's
            // hiragana terms (タベ -> たべる) rather than for its own rare katakana ones, and the two
            // passes share one budget so the list cannot grow past it.
            if (folded != kana) {
                add(suggest(folded, suggestions * PREFIX_OVER_REQUEST), SearchMatch.PREFIX, folded, prefixEnd)
            }
            val left = prefixEnd - hits.size
            if (left > 0) add(suggest(kana, left * PREFIX_OVER_REQUEST), SearchMatch.PREFIX, kana, prefixEnd)
        }

        return hits
    }

    /**
     * The query as the search acts on it: NFKC-composed, whitespace removed, bounded to
     * [QUERY_LIMIT] code points.
     *
     * NFKC is what folds the forms an IME can produce but the dictionary never stores: halfwidth
     * katakana with their separate voiced marks (ｶﾞ -> ガ), fullwidth Latin (Ａ -> A), and the
     * spacing dakuten (か + ゛ -> が). Whitespace is dropped rather than treated as a separator,
     * because Japanese is written without it — a pasted 猫 が is one query, and a query of spaces
     * alone is empty.
     */
    fun normalize(raw: String): String {
        var input = raw.take(MAX_INPUT_LENGTH)
        // A cut can land between the halves of a surrogate pair; a lone one is not text NFKC can
        // compose.
        if (input.isNotEmpty() && input.last().isHighSurrogate()) input = input.dropLast(1)

        val composed = Normalizer.normalize(input, Normalizer.Form.NFKC)
        val out = StringBuilder(minOf(composed.length, QUERY_LIMIT))
        var codePoints = 0
        var index = 0
        while (index < composed.length && codePoints < QUERY_LIMIT) {
            val codePoint = composed.codePointAt(index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) continue
            out.appendCodePoint(codePoint)
            codePoints++
        }
        return out.toString()
    }

    /**
     * True when [text] holds at least one Japanese character: kana, a kanji, an iteration mark or a
     * halfwidth kana.
     *
     * Deliberately narrow. A query of Latin or digits is not something this dictionary can be
     * searched for, and a screen that answered it with a list of unrelated kanji would be lying; the
     * ranges are [WordScanner]'s own (they are private there) plus the supplementary ideographic
     * planes, where a rare kanji such as 𠮷 lives.
     */
    fun isJapanese(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isJapaneseCodePoint(codePoint)) return true
        }
        return false
    }

    /**
     * True when [text] is something this dictionary can be asked about: Japanese, or romaji that can
     * be read in full. The screen uses this to tell a query it has no answer for from one it has not
     * looked up yet — an English word is not something to show a list of unrelated kanji for.
     */
    fun isSearchable(text: String): Boolean = isJapanese(text) || kanaFromRomaji(text) != null

    /**
     * The kana [text] is a romaji spelling of, or null when [text] is not romaji the converter can
     * read to the end.
     *
     * The dictionary stores Japanese, so an ASCII query has to be transliterated before it can be
     * looked up: "tokyo" is ときょ as far as this converter is concerned, and it is the loose index
     * ([looseForm]) that then turns that into 東京. Nothing but a conversion with no ASCII left over
     * counts: an English word is read as far as its letters happen to look like romaji and the rest
     * is left alone ("cat" -> かt, "english" -> えんgぃsh), and looking that up would answer the
     * reader with a word they never asked about.
     */
    fun kanaFromRomaji(text: String): String? {
        if (text.isEmpty() || text.any { it.code > ASCII_LIMIT }) return null
        val kana = Wanakana.toHiragana(text)
        return if (kana.any { it.code <= ASCII_LIMIT }) null else kana
    }

    /**
     * [kana] with its long vowels collapsed, the way they are left out in romaji: とうきょう ->
     * ときょ, らあめん -> らめん, サイコー -> さいこ.
     *
     * The reader types "tokyo" and the dictionary stores とうきょう, and nothing short of this fold
     * gets from one to the other. Both sides use this same function — the build script writes one
     * collapsed reading per reading into the indexed `loose` table, and a romaji query is folded here
     * — so an imperfect collapse costs nothing as long as it is the same on both sides. It is lossy,
     * which is why a query typed in kana is never folded this way, and why the results it reaches
     * rank below everything else.
     *
     * Rules, each judged against the last character kept, so a dropped vowel never stands in for its
     * own predecessor:
     * - katakana folds to hiragana, and the prolonged sound mark ー is dropped;
     * - う goes after a u-row or an o-row kana, あ after an a-row one, い after an i-row one — rows by
     *   sound, so that ど takes う like と, and ぼ like ほ;
     * - a doubled vowel collapses to one (おお -> お).
     */
    fun looseForm(kana: String): String {
        val out = StringBuilder(kana.length)
        for (char in kana) {
            val hiragana = if (char in KATAKANA) char - KATAKANA_FOLD else char
            if (hiragana == LONG_VOWEL_MARK) continue
            val previous = out.lastOrNull()
            if (previous != null && collapses(previous, hiragana)) continue
            out.append(hiragana)
        }
        return out.toString()
    }

    /** True when [current] is a vowel [previous] already spells out. */
    private fun collapses(previous: Char, current: Char): Boolean = when (current) {
        'う' -> previous in ROW_U || previous in ROW_O
        'あ' -> previous in ROW_A
        'い' -> previous in ROW_I
        else -> current == previous && current in VOWELS
    }

    /**
     * The exclusive upper bound of the terms a prefix search for [prefix] may match: [prefix] with
     * its last code point advanced by one.
     *
     * `term >= prefix AND term < bound` is exactly "term starts with prefix", and SQLite answers it
     * from `idx_terms_term`'s own B-tree — which `LIKE 'prefix%'` cannot, because the index is BINARY
     * and `LIKE` is not, so the planner would have to scan every term in the table. Wildcards are
     * therefore literal too, which is what a reader typing `%` or `_` means.
     *
     * U+10FFFF is the last code point and has no successor, so the bound closes on the prefix itself
     * and such a prefix matches nothing. No keyboard produces that noncharacter, and the query is
     * normalised to kana, kanji and ASCII before it gets here.
     */
    fun prefixUpperBound(prefix: String): String {
        if (prefix.isEmpty()) return prefix
        val lastIndex = prefix.offsetByCodePoints(prefix.length, -1)
        val last = prefix.codePointAt(lastIndex)
        if (last == MAX_CODE_POINT) return prefix
        return prefix.substring(0, lastIndex) + String(Character.toChars(last + 1))
    }

    /**
     * Every reading of [query] the deinflector reaches and the dictionary knows, most likely first,
     * with the form that matched — 食べた is 食べる, and 食べさせられた is too, because the
     * deinflector chains its rules (up to its own depth) before this sees the candidates.
     *
     * Candidates arrive most likely first ([Deinflector.candidates]) and the scan is bounded twice:
     * [MAX_DEINFLECTION_LOOKUPS] candidates probed, [MAX_DEINFLECTIONS] readings kept. A candidate
     * the dictionary does not have is not an answer, so it is simply skipped — unlike [WordScanner],
     * which must pick one reading for a surface it cannot ask about, the search box can offer them
     * all and let the reader choose.
     */
    private fun deinflections(
        query: String,
        lookup: (String, Int) -> List<DictEntry>,
        limit: Int
    ): List<Pair<String, List<DictEntry>>> {
        val found = ArrayList<Pair<String, List<DictEntry>>>(MAX_DEINFLECTIONS)
        for (candidate in Deinflector.candidates(query).take(MAX_DEINFLECTION_LOOKUPS)) {
            val direct = lookup(candidate, limit)
            if (direct.isNotEmpty()) {
                found.add(candidate to direct)
            } else {
                val folded = foldKatakanaToHiragana(candidate)
                if (folded != candidate) {
                    val foldedEntries = lookup(folded, limit)
                    if (foldedEntries.isNotEmpty()) found.add(folded to foldedEntries)
                }
            }
            if (found.size >= MAX_DEINFLECTIONS) break
        }
        return found
    }

    private fun isJapaneseCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x3040..0x309F, // hiragana
        in 0x30A0..0x30FF, // katakana
        in 0x31F0..0x31FF, // katakana phonetic extensions
        in 0xFF66..0xFF9D, // halfwidth katakana
        in 0x4E00..0x9FFF, // CJK unified ideographs
        in 0x3400..0x4DBF, // CJK unified ideographs extension A
        in 0x20000..0x2FA1F, // CJK unified ideographs extensions B to F, and compatibility
        0x3005, 0x3006 -> true // 々 and 〆

        else -> false
    }
}
