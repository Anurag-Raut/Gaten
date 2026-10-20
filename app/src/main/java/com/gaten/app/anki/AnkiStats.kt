/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import com.ichi2.anki.FlashCardsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The counts an analytics screen needs from AnkiDroid, and nothing else.
 *
 * Two kinds of number exist on the public Android interface, and this file reads only those:
 *
 *  - the **deck list** ([AnkiReview.decks]), whose `deck_count` values are AnkiDroid's own "to
 *    review today" numbers — daily limits applied, subdecks included, learning a lower bound;
 *  - a **browser-search count**: a query against the provider's `cards` URI asking for `_id`
 *    alone, which AnkiDroid answers from its search without fetching or rendering a card.
 *
 * Nothing here renders a card, writes a template, arms a queue token or answers anything: the
 * review path's own reads and writes are untouched, no method here can change the collection or
 * its preferences, and no query is ever sent without a search term (an empty selection would ask
 * the provider for every card id in the collection).
 *
 * "Today" is AnkiDroid's own study day, not local midnight: the backend computes the day from the
 * collection's rollover hour ("Next day starts at", 4 AM by default), which is what `rated:` and
 * `prop:rated` answer in — and both are answered from the review log as a set of *cards*, so the
 * same card graded five times today still counts once.
 */
object AnkiStats {
    /** How many searches one call may carry: the whole v1 dashboard is well under this. */
    const val MAX_QUERIES = 12

    /** How long one search string may be. A deck name is the only variable part of one. */
    const val MAX_QUERY_LENGTH = 256

    /** One count per search, in order, or why none could be read at all. */
    sealed interface Counts {
        data class Available(val counts: List<Int?>) : Counts

        data class Failed(val failure: AnkiReviewFailure, val detail: String?) : Counts
    }

    /**
     * Why [queries] cannot be asked, in the caller's own words, or null when they can.
     *
     * A blank search is refused rather than sent: the provider treats no selection as "every card"
     * and would answer with the whole collection's ids. Pure, so the bounds are testable without a
     * provider.
     */
    internal fun invalidReason(queries: List<String>): String? = when {
        queries.isEmpty() -> "no searches"
        queries.size > MAX_QUERIES -> "more than $MAX_QUERIES searches"
        queries.any { it.isBlank() } -> "a blank search asks for the whole collection"
        queries.any { it.length > MAX_QUERY_LENGTH } ->
            "a search longer than $MAX_QUERY_LENGTH characters"

        else -> null
    }

    /**
     * One count per search, in order. A search this AnkiDroid will not answer — an unknown search
     * term on an older provider — comes back as `null` for that entry alone, so one unavailable
     * metric never takes the rest of the screen down with it.
     *
     * @throws IllegalArgumentException when [invalidReason] refuses [queries]; nothing is asked of
     *   AnkiDroid in that case.
     */
    suspend fun counts(context: Context, queries: List<String>): Counts {
        invalidReason(queries)?.let { throw IllegalArgumentException(it) }
        return withContext(Dispatchers.IO) {
            when (AnkiReview.status(context)) {
                AnkiStatus.NotInstalled -> Counts.Failed(AnkiReviewFailure.NOT_INSTALLED, null)
                AnkiStatus.NeedsPermission -> Counts.Failed(AnkiReviewFailure.PERMISSION, null)
                AnkiStatus.Ready -> readCounts(context.contentResolver, queries)
            }
        }
    }

    /** The searches themselves, once AnkiDroid is installed and has given Gaten its permission. */
    private fun readCounts(resolver: ContentResolver, queries: List<String>): Counts =
        try {
            Counts.Available(queries.map { countOf(resolver, it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reading AnkiDroid's card counts failed", e)
            Counts.Failed(reviewFailureOf(e), detailOf(e))
        }

    /**
     * One search's count, or null when AnkiDroid refuses that search: a term its backend does not
     * know makes the provider throw, and that is a missing metric rather than a broken screen.
     * Only `_id` is asked for, which is the provider's fast path — no card is fetched or rendered —
     * and the cursor is closed with the count read, never walked.
     */
    private fun countOf(resolver: ContentResolver, query: String): Int? =
        try {
            resolver.query(CARDS_URI, ID_PROJECTION, query, null, null)?.use { it.count }
        } catch (e: IllegalArgumentException) {
            Log.i(TAG, "AnkiDroid refused a search: ${e.message}")
            null
        } catch (e: UnsupportedOperationException) {
            Log.i(TAG, "AnkiDroid does not answer this search: ${e.message}")
            null
        }
}

private const val TAG = "Gaten"

/**
 * The provider's collection-wide card search, and the id column it answers fastest.
 *
 * Both are written out rather than taken from [FlashCardsContract]: Gaten compiles against the
 * published `api-v1.1.0` artifact, which predates `Card.CONTENT_URI` and `Card._ID`, while the
 * provider itself has served `content://<authority>/cards` for far longer. The value of the id
 * column is `_id` — the same string the current contract names and the one the provider's
 * id-only fast path checks for, so asking for it alone means no card is fetched or rendered.
 */
private val CARDS_URI: Uri = Uri.withAppendedPath(FlashCardsContract.AUTHORITY_URI, "cards")
private val ID_PROJECTION = arrayOf("_id")
