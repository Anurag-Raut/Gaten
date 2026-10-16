/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections

/** Why a review call could not be served. */
enum class AnkiReviewFailure {
    /** AnkiDroid is not installed, or its provider is not visible to Gaten. */
    NOT_INSTALLED,

    /** AnkiDroid is installed but its database permission has not been granted. */
    PERMISSION,

    /** AnkiDroid has no collection folder chosen yet. */
    NOT_SET_UP,

    /** The note or card asked for is no longer in the collection. */
    NOT_FOUND,

    /** AnkiDroid failed, including a collection it cannot open. */
    PROVIDER,

    /** AnkiDroid answered something Gaten could not make sense of. */
    MALFORMED
}

/** One deck of the AnkiDroid collection, with the counts AnkiDroid reports for it. */
data class AnkiDeck(
    val id: Long,
    val name: String,
    val newCount: Int,
    val learnCount: Int,
    val reviewCount: Int,
    val filtered: Boolean
)

/** Every deck AnkiDroid has, or why they could not be read. */
sealed interface AnkiDecks {
    data class Available(val decks: List<AnkiDeck>) : AnkiDecks

    data class Failed(val failure: AnkiReviewFailure, val detail: String?) : AnkiDecks
}

/**
 * The card AnkiDroid would show next, or why there is none.
 *
 * [Due.buttonLabels] and [Due.mediaFiles] come from the same scheduling answer as the card's
 * identity, so the labels always belong to the card being graded.
 */
sealed interface AnkiQueue {
    /**
     * The next card AnkiDroid has scheduled for the deck.
     *
     * [token] identifies this reading of the queue and is the only thing [AnkiReview.grade] accepts,
     * so a stale screen cannot answer the card that has taken this one's place.
     *
     * [buttonLabels] holds one label per answer button, in AnkiDroid's own order, so index `i` is
     * ease `i + 1` and its size is AnkiDroid's button count — not always four. [mediaFiles] holds
     * file names only: AnkiDroid's provider can add media but never serve it.
     */
    data class Due(
        val token: Long,
        val noteId: Long,
        val ord: Int,
        val buttonLabels: List<String>,
        val mediaFiles: List<String>
    ) : AnkiQueue

    /** AnkiDroid has nothing to show for the deck. */
    data object Empty : AnkiQueue

    data class Failed(val failure: AnkiReviewFailure, val detail: String?) : AnkiQueue
}

/**
 * One card of the collection, rendered by AnkiDroid.
 *
 * [question] and [answer] are AnkiDroid's own HTML for the card's templates. Whether they already
 * carry the note type's CSS depends on the AnkiDroid version — builds up to roughly 2.16 prepend
 * it, current ones send the template's HTML alone — so [css] is handed over separately for the
 * caller to place itself.
 *
 * [fieldNames] and [fields] are the note type's field names and the note's raw values in the same
 * order, so a caller can read a field by name without parsing the rendered HTML.
 */
sealed interface AnkiReviewCard {
    data class Available(
        val noteId: Long,
        val ord: Int,
        val deckName: String,
        val question: String,
        val answer: String,
        val css: String,
        val fieldNames: List<String>,
        val fields: List<String>,
        val typedAnswer: Boolean
    ) : AnkiReviewCard

    data class Failed(val failure: AnkiReviewFailure, val detail: String?) : AnkiReviewCard
}

/** What answering a card did. */
sealed interface AnkiGrade {
    /**
     * AnkiDroid accepted the answer.
     *
     * This is an acknowledgement, not a promise: AnkiDroid reports the row as updated even when its
     * own scheduling call threw, so a card may come back with the same question.
     */
    data object Submitted : AnkiGrade

    /** AnkiDroid knows no such card; it was deleted or answered elsewhere. */
    data object NotFound : AnkiGrade

    /**
     * Nothing was sent: the token is not the card AnkiDroid currently has queued, so this is a stale
     * screen or a second tap. Not an error to report.
     */
    data object Ignored : AnkiGrade

    /**
     * AnkiDroid refused the answer.
     *
     * Whether the card was rescheduled is unknown: the call can fail after the provider has written
     * it and before its reply arrives, and an answer cannot be undone. Read the queue again rather
     * than sending the same answer a second time.
     */
    data class Failed(val failure: AnkiReviewFailure, val detail: String?) : AnkiGrade
}

/**
 * Reviews an AnkiDroid collection through its `flashcards` provider.
 *
 * AnkiDroid stays the only thing that schedules: Gaten reads the card it is about to show and hands
 * the answer straight back, and keeps no progress of its own. Every call is a provider round-trip, so
 * each runs on [Dispatchers.IO] and fails by result rather than throwing; only cancellation escapes.
 *
 * AnkiDroid must be declared in the manifest's `<queries>` for the provider to be visible at all on
 * Android 11+, and answering needs its `READ_WRITE_DATABASE` permission, which the API library
 * declares for us.
 *
 * The provider cannot undo an answer, so a grade is only ever sent for the card [nextCard] queued,
 * and only once: see [ReviewGate].
 */
object AnkiReview {

    /**
     * Serialises reading the queue with answering it, so a grade cannot be in flight while the card
     * it belongs to is replaced. Every other call is a plain read and needs no lock.
     */
    private val reviewLock = Mutex()

    private val gate = ReviewGate()

    /**
     * Note types whose back template has already been looked at, so the check runs once per note type
     * per process rather than on every card. A model id goes in before the attempt, so a note type
     * whose migration failed is not tried again until the next process.
     *
     * A synchronized set because [card] reads are independent: adding is the check and the mark at
     * once, so two readers of the same note type cannot both migrate it.
     */
    private val checkedNoteTypes: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())

    /** Whether the review screen can do anything on this device. */
    fun status(context: Context): AnkiStatus = AnkiExporter.status(context)

    /** The deck Gaten exports into, offered as the default for a review. */
    fun configuredDeckName(context: Context): String = AnkiPreferences.load(context).deckName

    /**
     * Every deck AnkiDroid has, case-insensitively sorted. Filtered decks are included, because
     * AnkiDroid schedules them like any other.
     */
    suspend fun decks(context: Context): AnkiDecks = withContext(Dispatchers.IO) {
        try {
            preflight(context)?.let { return@withContext AnkiDecks.Failed(it, null) }
            val decks = ArrayList<AnkiDeck>()
            val cursor = context.contentResolver.query(DECKS_URI, DECK_PROJECTION, null, null, null)
                ?: return@withContext AnkiDecks.Failed(
                    AnkiReviewFailure.PROVIDER,
                    "AnkiDroid returned no decks cursor"
                )
            cursor.use {
                while (it.moveToNext()) {
                    val counts = deckCountsFrom(it.string(FlashCardsContract.Deck.DECK_COUNTS))
                    decks += AnkiDeck(
                        id = it.long(FlashCardsContract.Deck.DECK_ID),
                        name = it.string(FlashCardsContract.Deck.DECK_NAME).orEmpty(),
                        newCount = counts.new,
                        learnCount = counts.learn,
                        reviewCount = counts.review,
                        filtered = filteredFrom(it.string(FlashCardsContract.Deck.DECK_DYN))
                    )
                }
            }
            AnkiDecks.Available(decks.sortedBy { it.name.lowercase() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Listing AnkiDroid's decks failed", e)
            AnkiDecks.Failed(reviewFailureOf(e), detailOf(e))
        }
    }

    /**
     * The card AnkiDroid would show next in [deckId], asked for afresh every time so the queue is
     * never stale.
     *
     * [deckId] has to be one AnkiDroid reported. It answers an id it does not know the same way as a
     * deck with nothing due, so the deck is checked first and reported as
     * [AnkiReviewFailure.NOT_FOUND] rather than as an empty queue.
     *
     * AnkiDroid's queue lists an intraday learning card ahead of its due time, so its head can be a
     * card its own reviewer is still waiting for. Each queued card is therefore checked against its
     * due time and the first due one is served, which is why the queue is read several cards deep;
     * [AnkiQueue.Empty] then means AnkiDroid really has nothing to show.
     */
    suspend fun nextCard(context: Context, deckId: Long): AnkiQueue = withContext(Dispatchers.IO) {
        try {
            reviewLock.withLock {
                // Reading the queue always invalidates the card the last read handed out, even when
                // this read cannot be served, so an old screen cannot keep answering from a token.
                gate.clear()
                preflight(context)?.let { return@withLock AnkiQueue.Failed(it, null) }
                val resolver = context.contentResolver
                if (!deckExists(resolver, deckId)) {
                    return@withLock AnkiQueue.Failed(
                        AnkiReviewFailure.NOT_FOUND,
                        "no deck $deckId"
                    )
                }
                val cursor = resolver.query(
                    SCHEDULE_URI,
                    SCHEDULE_PROJECTION,
                    SCHEDULE_SELECTION,
                    arrayOf(deckId.toString()),
                    null
                ) ?: return@withLock AnkiQueue.Failed(
                    AnkiReviewFailure.PROVIDER,
                    "AnkiDroid returned no schedule cursor"
                )
                // The schedule comes from AnkiDroid's content provider, so the collection's own
                // clock is not reachable here; wall time is the only clock the due comparison has.
                @Suppress("DirectSystemCurrentTimeMillisUsage")
                val nowSeconds = System.currentTimeMillis() / 1000
                val queued = cursor.use { it.readQueue(resolver) }
                val index = firstDueIndex(queued.map { row -> row.due }, nowSeconds)
                    ?: return@withLock AnkiQueue.Empty
                val served = queued[index]
                AnkiQueue.Due(
                    token = gate.arm(deckId, served.noteId, served.ord, served.buttonLabels.size),
                    noteId = served.noteId,
                    ord = served.ord,
                    buttonLabels = served.buttonLabels,
                    mediaFiles = served.mediaFiles
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is left armed on this path: the gate was cleared on the way in, and the card
            // is only ever armed once nothing after it can throw.
            Log.w(TAG, "Reading AnkiDroid's next card failed", e)
            AnkiQueue.Failed(reviewFailureOf(e), detailOf(e))
        }
    }

    /** The question, answer and raw note of one card of the collection. */
    suspend fun card(context: Context, noteId: Long, ord: Int): AnkiReviewCard =
        withContext(Dispatchers.IO) {
            try {
                preflight(context)?.let { return@withContext AnkiReviewCard.Failed(it, null) }
                val resolver = context.contentResolver

                // The note carries the raw fields and the note type the CSS and field names hang off.
                var fields: List<String> = emptyList()
                var mid = 0L
                val note = resolver.query(noteUri(noteId), NOTE_PROJECTION, null, null, null)
                    ?: return@withContext AnkiReviewCard.Failed(
                        AnkiReviewFailure.PROVIDER,
                        "AnkiDroid returned no note cursor"
                    )
                val hasNote = note.use {
                    if (!it.moveToFirst()) {
                        false
                    } else {
                        fields = splitNoteFields(it.string(FlashCardsContract.Note.FLDS))
                        mid = it.long(FlashCardsContract.Note.MID)
                        true
                    }
                }
                if (!hasNote) {
                    return@withContext AnkiReviewCard.Failed(
                        AnkiReviewFailure.NOT_FOUND,
                        "no note $noteId"
                    )
                }

                // The card itself, rendered by AnkiDroid's own template renderer.
                var rendered = renderedCard(resolver, noteId, ord)
                    ?: return@withContext AnkiReviewCard.Failed(
                        AnkiReviewFailure.NOT_FOUND,
                        "no card $noteId/$ord"
                    )

                // CSS and field names belong to the note type, and neither is in the card's HTML.
                var css = ""
                var fieldNames: List<String> = emptyList()
                resolver.query(modelUri(mid), MODEL_PROJECTION, null, null, null)?.use {
                    if (it.moveToFirst()) {
                        css = it.string(FlashCardsContract.Model.CSS).orEmpty()
                        fieldNames = splitNoteFields(it.string(FlashCardsContract.Model.FIELD_NAMES))
                    }
                }

                // A note type of Gaten's own from before the sentence line was dropped still draws
                // it, so its template is rewritten once and the card read again to show the result.
                if (isLegacyNoteType(fieldNames) && checkedNoteTypes.add(mid) &&
                    migrateLegacyBack(resolver, mid)
                ) {
                    rendered = renderedCard(resolver, noteId, ord) ?: rendered
                }

                var typedAnswer = false
                resolver.query(templatesUri(mid), TEMPLATE_PROJECTION, null, null, null)?.use {
                    while (it.moveToNext()) {
                        if (hasTypedAnswer(
                                it.string(FlashCardsContract.CardTemplate.QUESTION_FORMAT),
                                it.string(FlashCardsContract.CardTemplate.ANSWER_FORMAT)
                            )
                        ) {
                            typedAnswer = true
                        }
                    }
                }

                AnkiReviewCard.Available(
                    noteId = noteId,
                    ord = ord,
                    deckName = deckName(resolver, rendered.deckId),
                    question = rendered.question,
                    answer = rendered.answer,
                    css = css,
                    fieldNames = fieldNames,
                    fields = fields,
                    typedAnswer = typedAnswer
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Reading AnkiDroid card $noteId/$ord failed", e)
                AnkiReviewCard.Failed(reviewFailureOf(e), detailOf(e))
            }
        }

    /**
     * Answers the card [token] was issued for, with [ease] counted from one and [timeTakenMs] the
     * time spent on it. A token that is not the live one — a stale screen, or a second tap — is
     * [AnkiGrade.Ignored].
     *
     * AnkiDroid schedules this itself, so the caller must keep no progress of its own, and must read
     * the queue again afterwards rather than assuming which card comes next. The token is spent
     * before AnkiDroid is called, so even a failed answer is not sent twice.
     */
    suspend fun grade(
        context: Context,
        token: Long,
        ease: Int,
        timeTakenMs: Long
    ): AnkiGrade = withContext(Dispatchers.IO) {
        try {
            reviewLock.withLock {
                val card = gate.take(token) ?: return@withLock AnkiGrade.Ignored
                val rating = clampEase(ease, card.buttonCount) ?: return@withLock AnkiGrade.Ignored
                // Spent by now, and only answered while the deck is still serving this very card:
                // a screen that went stale while this waited must reload instead of answering.
                if (!stillQueued(context, card)) return@withLock AnkiGrade.Ignored
                val values = ContentValues().apply {
                    put(FlashCardsContract.ReviewInfo.NOTE_ID, card.noteId)
                    put(FlashCardsContract.ReviewInfo.CARD_ORD, card.ord)
                    put(FlashCardsContract.ReviewInfo.EASE, rating)
                    put(
                        FlashCardsContract.ReviewInfo.TIME_TAKEN,
                        normaliseTimeTaken(timeTakenMs)
                    )
                }
                val updated = context.contentResolver.update(SCHEDULE_URI, values, null, null)
                // AnkiDroid answers with the number of rows it touched: zero means it found no such
                // card. A row is "touched" even when the scheduling itself failed, so this is an
                // acknowledgement and not a promise that the card was rescheduled.
                if (updated > 0) AnkiGrade.Submitted else AnkiGrade.NotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Answering AnkiDroid card for token $token failed", e)
            AnkiGrade.Failed(reviewFailureOf(e), detailOf(e))
        }
    }

    /** The deck name AnkiDroid reports for [deckId], or nothing when it no longer lists the deck. */
    private fun deckName(resolver: ContentResolver, deckId: Long): String =
        resolver.query(deckUri(deckId), DECK_NAME_PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.string(FlashCardsContract.Deck.DECK_NAME).orEmpty()
            } else {
                ""
            }
        } ?: ""

    /**
     * Whether AnkiDroid still lists [deckId]. Reading its schedule cannot say so: a deck that is
     * gone and a deck with nothing due both answer with an empty cursor.
     */
    private fun deckExists(resolver: ContentResolver, deckId: Long): Boolean =
        resolver.query(deckUri(deckId), DECK_NAME_PROJECTION, null, null, null)
            ?.use { it.moveToFirst() } ?: false

    /** The queued cards AnkiDroid reported, in the order it would serve them, with each due check. */
    private fun Cursor.readQueue(resolver: ContentResolver): List<QueueRow> = buildList {
        while (size < SCHEDULE_LIMIT && moveToNext()) {
            val noteId = long(FlashCardsContract.ReviewInfo.NOTE_ID)
            val ord = int(FlashCardsContract.ReviewInfo.CARD_ORD)
            add(
                QueueRow(
                    noteId = noteId,
                    ord = ord,
                    buttonLabels = answerButtons(
                        int(FlashCardsContract.ReviewInfo.BUTTON_COUNT),
                        jsonStrings(string(FlashCardsContract.ReviewInfo.NEXT_REVIEW_TIMES))
                    ),
                    mediaFiles = jsonStrings(string(FlashCardsContract.ReviewInfo.MEDIA_FILES)),
                    due = queuedCard(resolver, noteId, ord)
                )
            )
        }
    }

    /**
     * A queued card's type and, for a learning card, the second it becomes due.
     *
     * Neither column is in the API library Gaten compiles against, and an AnkiDroid whose provider
     * predates them refuses the whole projection, so a card that cannot be read is reported as
     * unreadable and counts as due.
     */
    private fun queuedCard(resolver: ContentResolver, noteId: Long, ord: Int): QueuedCard =
        try {
            resolver.query(cardUri(noteId, ord), DUE_PROJECTION, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    QueuedCard(cursor.int(CARD_TYPE_COLUMN), cursor.long(CARD_DUE_COLUMN))
                } else {
                    UNREADABLE_CARD
                }
            } ?: UNREADABLE_CARD
        } catch (e: UnsupportedOperationException) {
            // An AnkiDroid whose card cursor has no type or due column.
            UNREADABLE_CARD
        }

    /** The card as AnkiDroid renders it right now, or null when the note has no such card. */
    private fun renderedCard(resolver: ContentResolver, noteId: Long, ord: Int): RenderedCard? {
        val cursor = resolver.query(cardUri(noteId, ord), CARD_PROJECTION, null, null, null)
            ?: error("AnkiDroid returned no card cursor")
        return cursor.use {
            if (!it.moveToFirst()) {
                null
            } else {
                RenderedCard(
                    question = it.string(FlashCardsContract.Card.QUESTION).orEmpty(),
                    answer = it.string(FlashCardsContract.Card.ANSWER).orEmpty(),
                    deckId = it.long(FlashCardsContract.Card.DECK_ID)
                )
            }
        }
    }

    /**
     * Drops the sentence line from a note type Gaten wrote before the field existed, and reports
     * whether anything was written.
     *
     * The template is only rewritten when it is still byte-for-byte Gaten's own: the current template
     * and one the user edited both differ, and neither may be touched. Best effort throughout — a
     * provider that refuses leaves the card as it was read, and the note type is not tried again
     * this process.
     */
    private fun migrateLegacyBack(resolver: ContentResolver, mid: Long): Boolean =
        try {
            val templateUri = templateUri(mid, LEGACY_TEMPLATE_ORD)
            val answerFormat = resolver.query(templateUri, TEMPLATE_BACK_PROJECTION, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.string(FlashCardsContract.CardTemplate.ANSWER_FORMAT)
                    } else {
                        null
                    }
                }
            if (!isLegacySentenceBack(answerFormat)) {
                false
            } else {
                val values = ContentValues().apply {
                    put(FlashCardsContract.CardTemplate.ANSWER_FORMAT, AnkiSettings.DEFAULT_BACK)
                }
                resolver.update(templateUri, values, null, null) > 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Dropping the sentence line from AnkiDroid note type $mid failed", e)
            false
        }

    /**
     * Whether [deckId] is still serving [card] next, checked before an answer is written. Best
     * effort: anything that cannot be read counts as stale, and the caller reads the queue again.
     */
    private fun stillQueued(context: Context, card: ReviewGate.Armed): Boolean {
        val cursor = context.contentResolver.query(
            SCHEDULE_URI,
            QUEUED_PROJECTION,
            SCHEDULE_SELECTION,
            arrayOf(card.deckId.toString()),
            null
        ) ?: return false
        return cursor.use {
            it.moveToFirst() &&
                it.long(FlashCardsContract.ReviewInfo.NOTE_ID) == card.noteId &&
                it.int(FlashCardsContract.ReviewInfo.CARD_ORD) == card.ord
        }
    }

    /**
     * What stops a call before it is made: the provider is only visible while AnkiDroid is installed,
     * and it refuses every caller without its database permission.
     */
    private fun preflight(context: Context): AnkiReviewFailure? = when {
        AddContentApi.getAnkiDroidPackageName(context) == null -> AnkiReviewFailure.NOT_INSTALLED
        context.checkSelfPermission(FlashCardsContract.READ_WRITE_PERMISSION) !=
            PackageManager.PERMISSION_GRANTED -> AnkiReviewFailure.PERMISSION
        else -> null
    }
}

private const val TAG = "Gaten"

private val DECKS_URI: Uri = FlashCardsContract.Deck.CONTENT_ALL_URI

private val SCHEDULE_URI: Uri = FlashCardsContract.ReviewInfo.CONTENT_URI

/**
 * How many queued cards one read looks at.
 *
 * The queue puts near-due learning cards first, so a card that is not due yet can sit in front of
 * cards that are. A handful is well past the number of learning cards a collection holds at once,
 * and reading deeper would only cost provider round-trips.
 */
private const val SCHEDULE_LIMIT = 8

/**
 * The queue of the deck the caller asked for. The provider temporarily selects that deck and puts the
 * previous one back; an id it does not know answers with an empty cursor.
 */
private const val SCHEDULE_SELECTION = "limit=$SCHEDULE_LIMIT, deckID=?"

private val DECK_PROJECTION = arrayOf(
    FlashCardsContract.Deck.DECK_ID,
    FlashCardsContract.Deck.DECK_NAME,
    FlashCardsContract.Deck.DECK_COUNTS,
    FlashCardsContract.Deck.DECK_DYN
)

private val DECK_NAME_PROJECTION = arrayOf(FlashCardsContract.Deck.DECK_NAME)

private val SCHEDULE_PROJECTION = arrayOf(
    FlashCardsContract.ReviewInfo.NOTE_ID,
    FlashCardsContract.ReviewInfo.CARD_ORD,
    FlashCardsContract.ReviewInfo.BUTTON_COUNT,
    FlashCardsContract.ReviewInfo.NEXT_REVIEW_TIMES,
    FlashCardsContract.ReviewInfo.MEDIA_FILES
)

/** Only what identifies the card, for checking that the deck is still serving it. */
private val QUEUED_PROJECTION = arrayOf(
    FlashCardsContract.ReviewInfo.NOTE_ID,
    FlashCardsContract.ReviewInfo.CARD_ORD
)

/**
 * The card columns the due check reads. Their names are spelled out because the API library Gaten
 * compiles against predates them: AnkiDroid maps "type" to the card's type code — 0 new, 1 learning,
 * 2 review, 3 relearning — and "due" to its raw due value, which is a Unix second only for a
 * learning card.
 */
private val DUE_PROJECTION = arrayOf(CARD_TYPE_COLUMN, CARD_DUE_COLUMN)

private const val CARD_TYPE_COLUMN = "type"

private const val CARD_DUE_COLUMN = "due"

/** What the due check is handed when AnkiDroid cannot say what a card is or when it is due. */
private val UNREADABLE_CARD = QueuedCard(type = null, dueEpochSeconds = null)

/** One card as AnkiDroid has it rendered, with the deck it sits in. */
private class RenderedCard(val question: String, val answer: String, val deckId: Long)

/** The template of a note type's card, at the ordinal AnkiDroid addresses it by. */
private fun templateUri(mid: Long, ord: Int): Uri =
    Uri.withAppendedPath(templatesUri(mid), ord.toString())

/** Only the back template, for deciding whether it is still the one Gaten wrote. */
private val TEMPLATE_BACK_PROJECTION = arrayOf(FlashCardsContract.CardTemplate.ANSWER_FORMAT)

/** Gaten's note type has one card, so its back template is the only one. */
private const val LEGACY_TEMPLATE_ORD = 0

/** One row of the queue: what it takes to serve it, and what it takes to tell whether it is due. */
private class QueueRow(
    val noteId: Long,
    val ord: Int,
    val buttonLabels: List<String>,
    val mediaFiles: List<String>,
    val due: QueuedCard
)

private val NOTE_PROJECTION = arrayOf(
    FlashCardsContract.Note.FLDS,
    FlashCardsContract.Note.MID
)

private val CARD_PROJECTION = arrayOf(
    FlashCardsContract.Card.QUESTION,
    FlashCardsContract.Card.ANSWER,
    FlashCardsContract.Card.DECK_ID
)

private val MODEL_PROJECTION = arrayOf(
    FlashCardsContract.Model.CSS,
    FlashCardsContract.Model.FIELD_NAMES
)

private val TEMPLATE_PROJECTION = arrayOf(
    FlashCardsContract.CardTemplate.QUESTION_FORMAT,
    FlashCardsContract.CardTemplate.ANSWER_FORMAT
)

private fun noteUri(noteId: Long): Uri =
    Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())

private fun cardUri(noteId: Long, ord: Int): Uri =
    Uri.withAppendedPath(Uri.withAppendedPath(noteUri(noteId), "cards"), ord.toString())

private fun modelUri(mid: Long): Uri =
    Uri.withAppendedPath(FlashCardsContract.Model.CONTENT_URI, mid.toString())

private fun templatesUri(mid: Long): Uri = Uri.withAppendedPath(modelUri(mid), "templates")

private fun deckUri(deckId: Long): Uri =
    Uri.withAppendedPath(FlashCardsContract.Deck.CONTENT_ALL_URI, deckId.toString())

/** A column's text, or null when the provider sent nothing there. */
private fun Cursor.string(column: String): String? =
    getColumnIndex(column).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }

private fun Cursor.long(column: String): Long =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getLong(it) } ?: 0L

private fun Cursor.int(column: String): Int =
    getColumnIndex(column).takeIf { it >= 0 }?.let { getInt(it) } ?: 0
