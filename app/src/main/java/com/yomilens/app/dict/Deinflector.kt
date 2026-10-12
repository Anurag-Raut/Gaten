/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.dict

/**
 * Suffix-based deinflection for Japanese dictionary lookup.
 *
 * The rule set is intentionally generous: a rule may produce a candidate that is not a real word,
 * but [WordScanner] re-looks-up every candidate in JMdict and keeps only the ones that exist. A
 * wrong candidate is therefore filtered out, while a missing rule would silently lose a word.
 */
object Deinflector {
    /** How many rules may be chained (食べさせられた = 3 steps). */
    private const val MAX_DEPTH = 3

    /**
     * Suffix rewriting rule.
     *
     * [priority] breaks ties between rules sharing the same [from] suffix, e.g. した -> する for
     * する-verbs against した -> す for a 話す-like godan verb: lower wins.
     *
     * [wholeWord] marks rules whose suffix already is the complete inflected word (する / 来る /
     * 行く readings such as します or きた), as opposed to rules that keep a stem in front of the
     * suffix (します inside 書きます). Which kind is preferred depends on the surface: した alone is
     * する, 話した is 話す.
     */
    private data class Rule(
        val from: String,
        val to: String,
        val priority: Int,
        val wholeWord: Boolean = false
    )

    private const val PRIORITY_IRREGULAR = 0
    private const val PRIORITY_GODAN = 1
    private const val PRIORITY_ICHIDAN = 2
    private const val PRIORITY_ADJECTIVE = 3
    private const val PRIORITY_REDUCTION = 4
    private const val PRIORITY_IMPERATIVE = 5

    /**
     * Godan endings with their four stems: a (ない/れる/せる), i (ます/たい), e (ば/可能/命令),
     * o (意向).
     */
    private val GODAN_STEMS = listOf(
        "う" to listOf("わ", "い", "え", "お"),
        "く" to listOf("か", "き", "け", "こ"),
        "ぐ" to listOf("が", "ぎ", "げ", "ご"),
        "す" to listOf("さ", "し", "せ", "そ"),
        "つ" to listOf("た", "ち", "て", "と"),
        "ぬ" to listOf("な", "に", "ね", "の"),
        "ぶ" to listOf("ば", "び", "べ", "ぼ"),
        "む" to listOf("ま", "み", "め", "も"),
        "る" to listOf("ら", "り", "れ", "ろ")
    )

    /** て-form and past-tense endings for every godan ending. */
    private val GODAN_TE_TA = mapOf(
        "う" to ("って" to "った"),
        "く" to ("いて" to "いた"),
        "ぐ" to ("いで" to "いだ"),
        "す" to ("して" to "した"),
        "つ" to ("って" to "った"),
        "ぬ" to ("んで" to "んだ"),
        "ぶ" to ("んで" to "んだ"),
        "む" to ("んで" to "んだ"),
        "る" to ("って" to "った")
    )

    /** Auxiliaries attached to the i-stem (連用形): 買います, 買いたい, 買いそう. */
    private val I_STEM_AUX = listOf(
        "ます", "ません", "ました", "ませんでした", "ませんか", "ましょう",
        "たい", "たかった", "たくない", "たくなかった", "たがる",
        "ながら", "なさい", "そう", "すぎる"
    )

    /** Auxiliaries attached to the a-stem (未然形): 買わない, 買われる, 買わせる, 買わず. */
    private val A_STEM_AUX = listOf(
        "ない", "なかった", "なくて", "ないで", "ないでください",
        "ず", "ずに", "ぬ",
        "れる", "れた", "れない", "れなかった",
        "せる", "せた", "せない", "せられる",
        "される", "された", "させられる",
        "なきゃ", "なけりゃ"
    )

    /** Ichidan (る-verb) endings: the auxiliary replaces る. */
    private val ICHIDAN_SUFFIXES = listOf(
        "ます", "ません", "ました", "ませんでした", "ませんか", "ましょう",
        "たい", "たかった", "たくない", "たくなかった", "たがる",
        "ながら", "なさい", "そう", "すぎる",
        "て", "た", "ない", "なかった", "なくて", "ないで", "ないでください",
        "ず", "ずに", "ぬ", "れば", "ろ", "よう",
        "られる", "られない", "られた", "られなかった",
        "れる", "れない", "れた",
        "させる", "させない", "させた", "させられる", "させられた",
        "ている", "ています", "ていた", "ていません",
        "てる", "てた", "てください",
        "てしまう", "てしまった", "てしまいます", "てしまいません",
        "ちゃった", "ちゃう", "ちゃ"
    )

    /** する / 来る / 行く readings, whose suffix is the inflected word itself. */
    private val IRREGULAR_RULES = listOf(
        Rule("します", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しません", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しました", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しませんでした", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しましょう", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しない", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しなかった", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しなくて", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しないで", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("せず", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("せぬ", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("すれば", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しろ", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しよう", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("して", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("した", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("してる", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("している", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しています", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("しそう", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("したい", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("したくない", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("される", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("された", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("させられる", "する", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("できます", "できる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("できません", "できる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("できた", "できる", PRIORITY_IRREGULAR, wholeWord = true),
        // 来る read in kana.
        Rule("きます", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("きません", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("きました", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("きて", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("きた", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こない", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こなかった", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こなくて", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こられる", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こられた", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こさせる", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こさせられる", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("くれば", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こい", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こよう", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("こず", "くる", PRIORITY_IRREGULAR, wholeWord = true),
        // 行く is the one godan verb with irregular て/た forms (行って, 行った).
        Rule("行って", "行く", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("行った", "行く", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("行っちゃった", "行く", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("いって", "いく", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("いった", "いく", PRIORITY_IRREGULAR, wholeWord = true),
        Rule("いっちゃった", "いく", PRIORITY_IRREGULAR, wholeWord = true)
    )

    /** い-adjectives (高かった -> 高い) and copula endings on nouns and sentences. */
    private val ADJECTIVE_RULES = listOf(
        Rule("くなかった", "い", PRIORITY_ADJECTIVE),
        Rule("かった", "い", PRIORITY_ADJECTIVE),
        Rule("くなくて", "い", PRIORITY_ADJECTIVE),
        Rule("くない", "い", PRIORITY_ADJECTIVE),
        Rule("くて", "い", PRIORITY_ADJECTIVE),
        Rule("ければ", "い", PRIORITY_ADJECTIVE),
        Rule("かったら", "い", PRIORITY_ADJECTIVE),
        Rule("かったり", "い", PRIORITY_ADJECTIVE),
        Rule("そう", "い", PRIORITY_ADJECTIVE),
        Rule("すぎる", "い", PRIORITY_ADJECTIVE),
        Rule("く", "い", PRIORITY_ADJECTIVE),
        Rule("さ", "い", PRIORITY_ADJECTIVE),
        // いい has an irregular stem (よかった, よくない).
        Rule("よかった", "いい", PRIORITY_ADJECTIVE),
        Rule("よくない", "いい", PRIORITY_ADJECTIVE),
        Rule("よくて", "いい", PRIORITY_ADJECTIVE),
        Rule("よく", "いい", PRIORITY_ADJECTIVE),
        // Copula endings, stripped instead of rewritten.
        Rule("ではありません", "", PRIORITY_ADJECTIVE),
        Rule("ではない", "", PRIORITY_ADJECTIVE),
        Rule("じゃなかった", "", PRIORITY_ADJECTIVE),
        Rule("ではなかった", "", PRIORITY_ADJECTIVE),
        Rule("でしょう", "", PRIORITY_ADJECTIVE),
        Rule("だろう", "", PRIORITY_ADJECTIVE),
        Rule("じゃない", "", PRIORITY_ADJECTIVE),
        Rule("でした", "", PRIORITY_ADJECTIVE),
        Rule("だった", "", PRIORITY_ADJECTIVE),
        Rule("です", "", PRIORITY_ADJECTIVE)
    )

    /** Rewritings that make a longer inflection reachable in one step. */
    private val REDUCTION_RULES = listOf(
        Rule("なかった", "ない", PRIORITY_REDUCTION),
        Rule("なくて", "ない", PRIORITY_REDUCTION),
        Rule("ませんでした", "ません", PRIORITY_REDUCTION),
        Rule("ましょう", "ます", PRIORITY_REDUCTION),
        Rule("ています", "ている", PRIORITY_REDUCTION),
        Rule("いました", "います", PRIORITY_REDUCTION),
        Rule("いません", "います", PRIORITY_REDUCTION),
        Rule("たかった", "たい", PRIORITY_REDUCTION),
        Rule("たくなかった", "たくない", PRIORITY_REDUCTION)
    )

    /** The complete rule table; for equal keys the declaration order above decides. */
    private val RULES: List<Rule> = buildRules()

    /** One rule applied to one form, with everything needed to rank it. */
    private data class Match(
        val base: String,
        val suffixLength: Int,
        val bucket: Int,
        val priority: Int
    )

    /** A longer suffix is a more specific reading, then the reading kind, then the rule priority. */
    private val MATCH_ORDER = compareByDescending<Match> { it.suffixLength }
        .thenBy { it.bucket }
        .thenBy { it.priority }

    private fun buildRules(): List<Rule> {
        val rules = ArrayList<Rule>(256)

        for ((ending, stems) in GODAN_STEMS) {
            val aStem = stems[0]
            val iStem = stems[1]
            val eStem = stems[2]
            val oStem = stems[3]

            for (aux in I_STEM_AUX) rules.add(Rule(iStem + aux, ending, PRIORITY_GODAN))
            for (aux in A_STEM_AUX) rules.add(Rule(aStem + aux, ending, PRIORITY_GODAN))
            rules.add(Rule(eStem + "ば", ending, PRIORITY_GODAN))
            rules.add(Rule(eStem + "る", ending, PRIORITY_GODAN))
            rules.add(Rule(oStem + "う", ending, PRIORITY_GODAN))
            // The bare e-stem (命令形) shares て with the ichidan て-form, which is far more common.
            rules.add(Rule(eStem, ending, PRIORITY_IMPERATIVE))

            val (te, ta) = GODAN_TE_TA.getValue(ending)
            rules.add(Rule(te, ending, PRIORITY_GODAN))
            rules.add(Rule(ta, ending, PRIORITY_GODAN))
            rules.add(Rule(ta + "ら", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "いる", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "います", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "いた", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "いません", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "る", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "た", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "ください", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "しまう", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "しまった", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "しまいます", ending, PRIORITY_GODAN))
            rules.add(Rule(te + "しまいません", ending, PRIORITY_GODAN))

            // Colloquial 〜てしまう -> 〜ちゃう / 〜でしまう -> 〜じゃう.
            val contraction = te.dropLast(1) + if (te.endsWith("で")) "じゃう" else "ちゃう"
            rules.add(Rule(contraction, ending, PRIORITY_GODAN))
            rules.add(Rule(contraction.dropLast(1) + "った", ending, PRIORITY_GODAN))
        }

        for (suffix in ICHIDAN_SUFFIXES) rules.add(Rule(suffix, "る", PRIORITY_ICHIDAN))

        rules.addAll(IRREGULAR_RULES)
        rules.addAll(ADJECTIVE_RULES)
        rules.addAll(REDUCTION_RULES)

        return rules
    }

    /**
     * Base-form candidates for an inflected [surface], most likely first, deduplicated, never
     * containing [surface] itself. Rules are chained (up to [MAX_DEPTH] steps) breadth-first, so
     * single-step readings are offered before multi-step ones.
     *
     * Pure: no Android or dictionary dependency. The caller re-looks-up every candidate.
     */
    fun candidates(surface: String): List<String> {
        if (surface.isEmpty()) return emptyList()

        val found = ArrayList<String>(8)
        val seen = HashSet<String>(16)
        seen.add(surface)
        val matches = ArrayList<Match>(12)

        var frontier = listOf(surface)
        repeat(MAX_DEPTH) {
            val next = ArrayList<String>(8)
            for (form in frontier) {
                matches.clear()
                for (rule in RULES) {
                    val from = rule.from
                    if (from.length > form.length || !form.endsWith(from)) continue
                    val base = form.dropLast(from.length) + rule.to
                    if (base.isEmpty() || base == form) continue
                    // The rule either consumed the whole form (くる in きます) or kept a stem in
                    // front of it (くる inside 書きます), and the surface tells which kind to trust.
                    val pureSuffix = from.length == form.length
                    matches.add(
                        Match(base, from.length, if (pureSuffix == rule.wholeWord) 0 else 1, rule.priority)
                    )
                }
                matches.sortWith(MATCH_ORDER)
                for (match in matches) {
                    if (seen.add(match.base)) next.add(match.base)
                }
            }
            if (next.isEmpty()) return found
            found.addAll(next)
            frontier = next
        }
        return found
    }
}
