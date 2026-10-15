/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import org.json.JSONArray
import org.json.JSONException

/** Anki's own button numbering: ease 1..4, in the order AnkiDroid lays its answer buttons out. */
internal const val MAX_EASE = 4

/** The deck counts AnkiDroid reports, in the order it reports them. */
internal data class DeckCounts(val learn: Int, val review: Int, val new: Int)

/**
 * The answer buttons to offer for a queued card: [labels] as AnkiDroid sent them, padded (or
 * trimmed) to [count] so index `i` always means ease `i + 1`. Empty when AnkiDroid reported none.
 *
 * AnkiDroid's own count is authoritative and is not always four: a current build hardcodes four, but
 * older ones derive it from the card's state and can send two or three.
 */
internal fun answerButtons(count: Int, labels: List<String>): List<String> =
    List(count.coerceIn(0, MAX_EASE)) { index -> labels.getOrElse(index) { "" } }

/**
 * The ease to send AnkiDroid, or null when [ease] is not one of [buttonCount]'s buttons.
 *
 * Out-of-range matters: AnkiDroid resolves the ease through `Ease.fromValue`, which throws for
 * anything outside 1..4 rather than ignoring it.
 */
internal fun clampEase(ease: Int, buttonCount: Int): Int? =
    ease.takeIf { buttonCount in 1..MAX_EASE && it in 1..buttonCount }

/** Answer time in ms. AnkiDroid reads a negative value as "start the timer now". */
internal fun normaliseTimeTaken(ms: Long): Long = ms.coerceAtLeast(0)

/**
 * The `deck_count` column — a JSON array AnkiDroid orders as `[learn, review, new]` — as a
 * [DeckCounts]. A missing or malformed array counts as zero rather than failing the whole list.
 */
internal fun deckCountsFrom(raw: String?): DeckCounts {
    val counts = jsonInts(raw)
    return DeckCounts(
        learn = counts.getOrElse(0) { 0 },
        review = counts.getOrElse(1) { 0 },
        new = counts.getOrElse(2) { 0 }
    )
}

/** Whether the `deck_dyn` column marks the deck as a filtered deck. AnkiDroid writes a boolean. */
internal fun filteredFrom(raw: String?): Boolean = when (raw?.trim()?.lowercase()) {
    null, "", "0", "false" -> false
    else -> true
}

/** A note's raw field values. AnkiDroid joins them with [ANKI_FIELD_SEPARATOR]. */
internal fun splitNoteFields(raw: String?): List<String> =
    if (raw.isNullOrEmpty()) emptyList() else raw.split(ANKI_FIELD_SEPARATOR)

/** Whether either template asks AnkiDroid for a typed answer (`{{type:Field}}`). */
internal fun hasTypedAnswer(questionFormat: String?, answerFormat: String?): Boolean =
    questionFormat.orEmpty().contains(TYPED_ANSWER_MARKER) ||
        answerFormat.orEmpty().contains(TYPED_ANSWER_MARKER)

/**
 * The field only the note type Gaten wrote before the sentence line was dropped has. Its presence is
 * what marks a note type as one of those, so every other note type is left alone without asking
 * AnkiDroid anything.
 */
internal const val LEGACY_SENTENCE_FIELD = "Sentence"

/**
 * The back template Gaten wrote before the sentence line was dropped, exactly as it wrote it.
 *
 * Only a template still byte-for-byte this is migrated: anything else is either the current template
 * or one the user edited, and neither may be touched.
 */
internal const val LEGACY_SENTENCE_BACK =
    "{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}<br><small>{{Sentence}}</small>"

/** Whether [fieldNames] belong to the note type Gaten wrote before the sentence line was dropped. */
internal fun isLegacyNoteType(fieldNames: List<String>): Boolean =
    LEGACY_SENTENCE_FIELD in fieldNames

/**
 * Whether [answerFormat] is still, exactly, the back template Gaten wrote before the sentence line
 * was dropped. A template the user has edited differs by at least a character, so it is not this.
 */
internal fun isLegacySentenceBack(answerFormat: String?): Boolean =
    answerFormat == LEGACY_SENTENCE_BACK

/**
 * The two things the due check needs from a queued card: its type code and, for a learning card, the
 * second it becomes due. Either is null when AnkiDroid could not say.
 */
internal data class QueuedCard(val type: Int?, val dueEpochSeconds: Long?)

/**
 * Whether AnkiDroid would show this card now.
 *
 * AnkiDroid's queue lists an intraday learning card ahead of its due time, so the head of the queue
 * can be a card its own reviewer is still waiting for. Only a learning card is affected: a new
 * card's due value is a position and a review card's is a day count, so neither can be read as a
 * time and both count as due.
 *
 * A card whose type or due time could not be read counts as due too. Showing a card early is a
 * smaller error than hiding a session that still had cards in it, and an AnkiDroid without these
 * columns at all cannot be asked any other way.
 */
internal fun dueNow(cardType: Int?, dueEpochSeconds: Long?, nowSeconds: Long): Boolean {
    if (cardType != LEARNING_CARD_TYPE) return true
    if (dueEpochSeconds == null || dueEpochSeconds < 0) return true
    return dueEpochSeconds <= nowSeconds + DUE_ALLOWANCE_SECONDS
}

/**
 * The first of [cards] AnkiDroid would show, or null when none of them is due yet.
 *
 * The queue is in the order AnkiDroid would serve it, so the first due card is the one to show.
 */
internal fun firstDueIndex(cards: List<QueuedCard>, nowSeconds: Long): Int? =
    cards.indexOfFirst { dueNow(it.type, it.dueEpochSeconds, nowSeconds) }.takeIf { it >= 0 }

/**
 * How a provider call failed, from the exception that reached this process.
 *
 * AnkiDroid rewrites its own "storage not configured" failure into an [IllegalStateException]
 * because the binder cannot carry the original type, so that message is the only handle on it. A
 * locked collection is not distinguishable this way at all and lands on [AnkiReviewFailure.PROVIDER].
 */
internal fun reviewFailureOf(e: Throwable): AnkiReviewFailure = when {
    e is SecurityException -> AnkiReviewFailure.PERMISSION
    e is IllegalStateException &&
        e.message.orEmpty().contains(STORAGE_NOT_CONFIGURED, ignoreCase = true) ->
        AnkiReviewFailure.NOT_SET_UP
    e is IllegalArgumentException || e is UnsupportedOperationException -> AnkiReviewFailure.MALFORMED
    else -> AnkiReviewFailure.PROVIDER
}

/** The exception class and message a failure carries as its detail. */
internal fun detailOf(e: Throwable): String =
    e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

/**
 * A JSON array of strings, or an empty list when AnkiDroid sent nothing usable.
 *
 * A null element reads as an empty string rather than the text "null", which is what `optString`
 * would make of it.
 */
internal fun jsonStrings(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            if (array.isNull(index)) "" else array.optString(index, "")
        }
    } catch (e: JSONException) {
        emptyList()
    }
}

private fun jsonInts(raw: String?): List<Int> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(raw)
        List(array.length()) { index -> array.optInt(index, 0) }
    } catch (e: JSONException) {
        emptyList()
    }
}

/**
 * The card AnkiDroid has queued, under a token that changes on every queue read.
 *
 * AnkiDroid has no idempotency of its own — a repeated grade really does reschedule — so a grade is
 * only ever sent for the token the card was loaded under, and the token is spent when the grade is
 * attempted, before AnkiDroid is called. Spending it first is what makes a reply that never arrives
 * safe: the provider cannot undo an answer, so the answer must not be sendable a second time.
 *
 * A token stops matching on the next queue read, so a stale screen cannot answer the card that has
 * taken the old one's place — the same note and card ordinal coming back after "Again" included.
 */
internal class ReviewGate {

    /** The card a token was issued for, the deck it was read from, and its answer button count. */
    data class Armed(val token: Long, val deckId: Long, val noteId: Long, val ord: Int, val buttonCount: Int)

    private var issued = 0L
    private var armed: Armed? = null

    /** Issues a token for the card just read from AnkiDroid, replacing any earlier one. */
    @Synchronized
    fun arm(deckId: Long, noteId: Long, ord: Int, buttonCount: Int): Long {
        issued += 1
        armed = Armed(issued, deckId, noteId, ord, buttonCount)
        return issued
    }

    /** Spends [token] and returns the card it was issued for, or null when it is not the live one. */
    @Synchronized
    fun take(token: Long): Armed? {
        val current = armed ?: return null
        if (current.token != token) return null
        armed = null
        return current
    }

    /** Forgets the queued card, so nothing can be graded until the queue is read again. */
    @Synchronized
    fun clear() {
        armed = null
    }
}

private const val TYPED_ANSWER_MARKER = "{{type:"

/** AnkiDroid's own wording for the storage-not-configured failure it re-wraps for the binder. */
private const val STORAGE_NOT_CONFIGURED = "storage is not configured"

/** AnkiDroid's type code for an intraday learning card, the only type with a due time. */
private const val LEARNING_CARD_TYPE = 1

/**
 * How close to its due time a learning card counts as due. AnkiDroid's own Again step is a minute,
 * so a card graded that way is served at once, while an interval the user just chose is never
 * served minutes early.
 */
private const val DUE_ALLOWANCE_SECONDS = 60L
