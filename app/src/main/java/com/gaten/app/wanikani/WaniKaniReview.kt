/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

import dev.esnault.wanakana.core.Wanakana
import java.text.Normalizer

/**
 * The review screen's own shapes: one item of the live review queue, what asking for the queue
 * answered, and what finishing a review answered.
 *
 * This is the half of Gaten that is not read-only. The queue is read from
 * `GET /assignments?immediately_available_for_review=true&hidden=false` and resolved through one
 * `GET /subjects?ids=…`, and a finished review is sent back with `POST /reviews`. Nothing here is
 * cached: a queue is a fact about right now, and an item answered a minute ago is a stale question,
 * so there is no file, no snapshot and no history behind any of this.
 */

/**
 * One subject due to be reviewed, as the screen draws it and checks an answer against.
 *
 * [meanings] and [readings] are the *accepted* answers only. For meanings that is the entries
 * WaniKani marks `accepted_answer`, plus the auxiliary meanings it marks `whitelist` - what the
 * account may type and still be right; the `blacklist` auxiliary meanings, the ones deliberately
 * wrong, are not here. For readings it is every entry marked `accepted_answer`, which is what a
 * reading question accepts whatever its type.
 *
 * [readings] are folded to hiragana as the item is built ([readingAnswerHiragana]), so an answer -
 * typed in kana, or converted from romaji by the same function - can be compared with them directly.
 * [meanings] keep WaniKani's own spelling and order: they are English words, not kana, and the UI
 * compares them case-insensitively on its own.
 */
data class WaniKaniReviewItem(
    val subjectId: Int,
    /** `radical`, `kanji`, `vocabulary` or `kana_vocabulary`, as WaniKani names the subject. */
    val type: String,
    /** Null for an image-only radical, which has no character to be drawn as. */
    val characters: String?,
    /**
     * Every meaning the review accepts, the primary one first, without repeats. Never empty: a
     * subject whose accepted meanings are all absent falls back to its own slug, so the screen always
     * has something to judge an answer against.
     */
    val meanings: List<String>,
    /** The first of [meanings], the one the subject is taught as. Never empty. */
    val primaryMeaning: String,
    /**
     * Every reading the review accepts, folded to hiragana, the primary one first, without repeats.
     * Empty when the subject is not quizzed on its reading at all: a radical, or a kana vocabulary
     * whose reading is its characters.
     */
    val readings: List<String>,
    /** The first of [readings], or null when there are no readings. */
    val primaryReading: String?,
    /** WaniKani's level for the subject, shown beside its type; 0 when the payload omitted it. */
    val level: Int,
    /** The subject's meaning mnemonic, or empty when WaniKani gives none. */
    val meaningMnemonic: String,
    /** The subject's reading mnemonic, or empty when WaniKani gives none. */
    val readingMnemonic: String,
    /** Example sentences for a vocabulary subject; empty for the kinds WaniKani gives none. */
    val contextSentences: List<WaniKaniContextSentence>
)

/**
 * What asking for the review queue answered. The states a read can be in are the ones the reference
 * lookup already has, plus emptiness: an account can be up to date with nothing to review, which is
 * an answer rather than a failure.
 */
sealed interface WaniKaniReviewQueue {

    /** The subjects due now, in the order they became available. */
    data class Available(val items: List<WaniKaniReviewItem>) : WaniKaniReviewQueue

    /** Nothing is due: every assignment is a future one, or the account has not started anything. */
    data object Empty : WaniKaniReviewQueue

    /** No token is configured. */
    data object NeedsToken : WaniKaniReviewQueue

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniReviewQueue

    /**
     * Nothing could be answered: [failure] says why, and [retryAfterSeconds] how long WaniKani asked
     * us to wait when it said so (a rate limit). Never retried here - retrying is what makes a rate
     * limit worse.
     */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniReviewQueue
}

/** What finishing a review answered. */
sealed interface WaniKaniReviewSubmission {

    /** WaniKani accepted the review; the account's scheduling has been updated. */
    data object Submitted : WaniKaniReviewSubmission

    /** No token is configured. */
    data object NeedsToken : WaniKaniReviewSubmission

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniReviewSubmission

    /**
     * The review was not accepted: [failure] says why, and [retryAfterSeconds] how long to wait when
     * WaniKani asked for one. A submission that failed is never retried on our own.
     */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniReviewSubmission
}

/**
 * [text] as a reading answer can be compared with an item's own readings: NFKC-composed, with every
 * whitespace character removed, katakana folded to hiragana, and a fully-ASCII answer transliterated
 * from romaji.
 *
 * The folding is what makes the comparison possible at all: WaniKani accepts a reading in kana, the
 * reader may type it in katakana or in romaji ("ichi"), and the converted answer would otherwise
 * never match. Only a romaji conversion the converter can read to the end is used - an English word
 * is read as far as its letters happen to look like romaji and then left alone ("cat" -> かt), and
 * that is not a reading, so what is returned is the normalised text the reader typed. A text that is
 * not ASCII at all is never converted: kana and kanji are already the alphabet of the answer.
 */
internal fun readingAnswerHiragana(text: String): String {
    val composed = Normalizer.normalize(text, Normalizer.Form.NFKC)
    val stripped = composed.filterNot { it.isWhitespace() }
    if (stripped.isEmpty()) return stripped
    if (stripped.all { it.code <= ASCII_LIMIT }) {
        val kana = Wanakana.toHiragana(stripped)
        return if (kana.any { it.code <= ASCII_LIMIT }) stripped else kana
    }
    val folded = StringBuilder(stripped.length)
    for (character in stripped) {
        folded.append(if (character in KATAKANA) character - KATAKANA_FOLD else character)
    }
    return folded.toString()
}

/** The last ASCII code point: what a romaji answer is made of, and what a conversion must consume. */
private const val ASCII_LIMIT = 0x7F

/** Katakana ァ to ヶ, and what folding one of them down to hiragana subtracts. */
private val KATAKANA = 'ァ'..'ヶ'
private const val KATAKANA_FOLD = 0x60
