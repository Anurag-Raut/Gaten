/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.wanikani

/**
 * The lesson screen's own shapes: one new subject to teach and quiz, what asking for the lesson queue
 * answered, and what starting one answered.
 *
 * Lessons are WaniKani's other half of the review screen. The queue is read from
 * `GET /assignments?immediately_available_for_lessons=true&hidden=false` and resolved through one
 * `GET /subjects?ids=…`; a subject is finished with `PUT /assignments/<assignmentId>/start`, which is
 * what moves it from the lessons queue to the review queue. WaniKani's own reference is explicit that
 * a lesson quiz is *not* submitted as a review, so [WaniKaniReviewItem]'s `POST /reviews` is never
 * used here.
 *
 * Nothing is cached: a queue is a fact about right now, and a subject introduced a minute ago is a
 * stale question. The teach fields are the same ones the reference lookup renders for a tapped
 * character, decoded for all four subject kinds rather than the two the reference targets.
 */

/** One sentence WaniKani teaches a vocabulary word with: the Japanese, and its English. */
data class WaniKaniContextSentence(
    val japanese: String,
    val english: String
)

/**
 * One subject waiting to be learned, as the teach screen draws it and the quiz judges it.
 *
 * [meanings] and [readings] are the *accepted* answers only, exactly as [WaniKaniReviewItem] builds
 * them - the whitelisted auxiliary meanings, and every reading folded to hiragana - so the lesson
 * quiz and the review quiz cannot drift apart. [readings] is empty for a radical or a kana
 * vocabulary, which WaniKani does not quiz on a reading.
 *
 * [assignmentId] is what finishing the subject sends; [subjectId] is WaniKani's own id for the
 * content, which the teach screen can use to draw an image-only radical through the reference lookup.
 */
data class WaniKaniLessonItem(
    /** The assignment's id: what `PUT /assignments/<id>/start` takes. */
    val assignmentId: Int,
    /** WaniKani's subject id, for the content itself. */
    val subjectId: Int,
    /** `radical`, `kanji`, `vocabulary` or `kana_vocabulary`, as WaniKani names the subject. */
    val type: String,
    /** Null for an image-only radical, which has no character to be drawn as. */
    val characters: String?,
    /** WaniKani's level for the subject (1..60). */
    val level: Int,
    /** WaniKani's order within the level; the queue is sorted by level then this. */
    val lessonPosition: Int,
    /** Every meaning the quiz accepts, the primary one first, without repeats. Never empty. */
    val meanings: List<String>,
    /** The first of [meanings], the one the subject is taught as. Never empty. */
    val primaryMeaning: String,
    /** Every reading the quiz accepts, folded to hiragana, the primary one first. Empty when none. */
    val readings: List<String>,
    /** The first of [readings], or null when the subject is not quizzed on one. */
    val primaryReading: String?,
    /** The reading mnemonic as plain text; empty when WaniKani lists none. */
    val readingMnemonic: String,
    /** The meaning mnemonic as plain text; empty when WaniKani lists none. */
    val meaningMnemonic: String,
    /**
     * The subjects this one is built from, in WaniKani's order. Only the ids: the teach screen draws
     * a chip per id, and fills the character and name in from the reference cache when it has them.
     */
    val components: List<WaniKaniComponent>,
    /** Example sentences, for a vocabulary subject; empty for the kinds WaniKani gives none. */
    val contextSentences: List<WaniKaniContextSentence>,
    /** The subject's page on wanikani.com, or null when WaniKani's url was not one. */
    val documentUrl: String?
)

/**
 * What asking for the lesson queue answered: the same states the review queue has, because a read can
 * fail in the same ways and an account can be up to date with nothing new, which is an answer rather
 * than a failure.
 */
sealed interface WaniKaniLessonQueue {

    /** The subjects waiting to be learned, in WaniKani's own lesson order. */
    data class Available(val items: List<WaniKaniLessonItem>) : WaniKaniLessonQueue

    /** Nothing new: every subject has been started, or the account has none. */
    data object Empty : WaniKaniLessonQueue

    /**
     * WaniKani has lessons waiting, but every one of them is above what the subscription grants, so
     * none can be started until the level or plan allows it. Distinct from [Empty], which is a real
     * zero: the hub says the account is restricted rather than that there is nothing to learn.
     */
    data object Locked : WaniKaniLessonQueue

    /** No token is configured. */
    data object NeedsToken : WaniKaniLessonQueue

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniLessonQueue

    /**
     * Nothing could be answered: [failure] says why, and [retryAfterSeconds] how long WaniKani asked
     * us to wait when it said so. Never retried here - retrying is what makes a rate limit worse.
     */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniLessonQueue
}

/** What starting a lesson answered. The same outcomes a finished review has, for the same reasons. */
sealed interface WaniKaniLessonStart {

    /** WaniKani moved the assignment out of the lessons queue: the subject is now in reviews. */
    data object Started : WaniKaniLessonStart

    /** No token is configured. */
    data object NeedsToken : WaniKaniLessonStart

    /** WaniKani rejected the stored token: the user has to reconnect. */
    data object AuthRejected : WaniKaniLessonStart

    /**
     * The lesson was not started: [failure] says why, and [retryAfterSeconds] how long to wait when
     * WaniKani asked for one. A start that failed is never retried on our own.
     */
    data class Unreachable(
        val failure: WaniKaniFailure,
        val retryAfterSeconds: Long? = null
    ) : WaniKaniLessonStart
}
