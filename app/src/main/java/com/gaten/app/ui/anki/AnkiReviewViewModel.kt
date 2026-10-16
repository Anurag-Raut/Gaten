/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.anki

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiDecks
import com.gaten.app.anki.AnkiGrade
import com.gaten.app.anki.AnkiQueue
import com.gaten.app.anki.AnkiReview
import com.gaten.app.anki.AnkiReviewCard
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.anki.AnkiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the review screen is doing. */
sealed interface Step {
    /** Reading AnkiDroid's status and deck list. */
    data object Starting : Step

    /** Review cannot run at all: no AnkiDroid, no permission, no collection. */
    data class Blocked(val failure: AnkiReviewFailure) : Step

    /** The deck picker. */
    data class Decks(val decks: List<AnkiDeck>) : Step

    /**
     * One card, before ([revealed] false) or after the answer is shown.
     *
     * [token], [buttonLabels] and [mediaFiles] come from the queue reading the card was taken from:
     * the labels belong to this card, and the token is the only thing AnkiDroid accepts as an answer
     * for it.
     */
    data class Session(
        val card: AnkiReviewCard.Available,
        val token: Long,
        val buttonLabels: List<String>,
        val mediaFiles: List<String>,
        val startedAt: Long,
        val revealed: Boolean
    ) : Step

    /** Nothing is due in this deck. */
    data class Done(val deckName: String) : Step

    /**
     * Something went wrong. [afterGrade] is true when an answer was sent and AnkiDroid did not say
     * whether it was kept: that answer is never sent again, so the only way on is the next card.
     */
    data class Failed(
        val failure: AnkiReviewFailure,
        val detail: String?,
        val afterGrade: Boolean
    ) : Step
}

/**
 * How deep a step sits, so a move to a deeper one slides forward and a move back slides back.
 * The two failure-shaped steps stand where the reader met them.
 */
internal fun Step.depth(): Int = when (this) {
    Step.Starting -> 0
    is Step.Blocked -> 0
    is Step.Decks -> 1
    is Step.Session -> 2
    is Step.Done -> 3
    is Step.Failed -> 2
}

/**
 * The review session's state, held outside the composition so a rotation, a dark-mode switch or a
 * split-screen change does not drop the reader back to the deck picker — and so the card's timer
 * keeps running instead of restarting mid-card. [AnkiReview] works off an application context, which
 * is why this is an [AndroidViewModel].
 *
 * Every provider call runs in [viewModelScope], and the screen only reads [step], [deck], [busy] and
 * the inline-panel state from here: nothing about the session is left in the composition, and a
 * control that starts work goes through [ifIdle], since a finished grade hands straight into the next
 * read without waiting for a tap.
 */
class AnkiReviewViewModel(application: Application) : AndroidViewModel(application) {

    private val _step = MutableStateFlow<Step>(Step.Starting)
    val step: StateFlow<Step> = _step.asStateFlow()

    private val _deck = MutableStateFlow<AnkiDeck?>(null)
    val deck: StateFlow<AnkiDeck?> = _deck.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _expandedKanji = MutableStateFlow<String?>(null)
    val expandedKanji: StateFlow<String?> = _expandedKanji.asStateFlow()

    private val _expandedRadical = MutableStateFlow<Int?>(null)
    val expandedRadical: StateFlow<Int?> = _expandedRadical.asStateFlow()

    // The kanji whose details were last shown: [expandedKanji] is already null while the panel
    // shrinks away, so the exit animation reads this one instead.
    private val _lastKanji = MutableStateFlow<String?>(null)
    val lastKanji: StateFlow<String?> = _lastKanji.asStateFlow()

    /**
     * When the app went to the background with a card on screen, so the time spent away can be taken
     * back out of AnkiDroid's answer time. Survival across a rotation is also why the timer no longer
     * restarts while the reader is still on the same card.
     */
    private var pausedAt: Long? = null

    /** The deck Gaten exports to, offered as the picker's default selection. */
    val configuredDeckName: String = AnkiReview.configuredDeckName(application)

    /** Runs [action] only when no provider call is in flight. */
    private fun ifIdle(action: () -> Unit) {
        if (_busy.value) return
        action()
    }

    /** Reads AnkiDroid's status, then its decks: what the screen does when it opens. */
    fun start() {
        when (AnkiReview.status(getApplication())) {
            AnkiStatus.NotInstalled -> _step.value = Step.Blocked(AnkiReviewFailure.NOT_INSTALLED)
            AnkiStatus.NeedsPermission -> _step.value = Step.Blocked(AnkiReviewFailure.PERMISSION)
            AnkiStatus.Ready -> openDecks()
        }
    }

    /** The permission dialog came back refused, so review cannot run yet. */
    fun permissionDenied() {
        _step.value = Step.Blocked(AnkiReviewFailure.PERMISSION)
    }

    /** Reads the deck list, fresh. */
    fun openDecks() {
        if (_busy.value) return
        // Busy is set before the launch, so two taps in one frame cannot start two reads.
        _busy.value = true
        collapseDetails()
        viewModelScope.launch {
            when (val decks = AnkiReview.decks(getApplication())) {
                is AnkiDecks.Available -> _step.value = Step.Decks(decks.decks)
                is AnkiDecks.Failed ->
                    _step.value = Step.Failed(decks.failure, decks.detail, afterGrade = false)
            }
            _busy.value = false
        }
    }

    /** Starts reviewing [deck]. */
    fun startDeck(deck: AnkiDeck) = ifIdle {
        _deck.value = deck
        nextCard()
    }

    /** Leaves this deck and shows the picker again. */
    fun changeDeck() = ifIdle {
        _deck.value = null
        openDecks()
    }

    /** Retries what failed: the deck list when no deck is chosen, the next card otherwise. */
    fun retry() = ifIdle {
        if (_deck.value == null) openDecks() else nextCard()
    }

    /** Reveals the answer of the card on screen. */
    fun reveal() {
        val session = _step.value as? Step.Session ?: return
        _step.value = session.copy(revealed = true)
    }

    /** Sends one rating for [session]'s card. Guarded: a card is answered once, never twice. */
    fun grade(session: Step.Session, ease: Int) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val taken = SystemClock.elapsedRealtime() - session.startedAt
            when (val result = AnkiReview.grade(getApplication(), session.token, ease, taken)) {
                // Submitted: AnkiDroid took the answer. The next read only serves cards that are
                // actually due, so a learning card sent minutes into the future stays away.
                AnkiGrade.Submitted -> nextCard()

                // NotFound: the card is gone. Ignored: nothing was sent at all, so the card is still
                // the scheduler's to hand out — either way the queue is read as it is. All three end
                // this card, and busy stays set through the hand-off, so two taps cannot answer two.
                AnkiGrade.NotFound, AnkiGrade.Ignored -> nextCard()

                is AnkiGrade.Failed -> {
                    // The answer may have landed anyway, and the provider cannot undo one, so the
                    // only way on is a fresh read from the scheduler.
                    _step.value = Step.Failed(result.failure, result.detail, afterGrade = true)
                    _busy.value = false
                }
            }
        }
    }

    /** Toggles the inline details: the open kanji closes them, another one swaps them, and the
     *  radical under them goes back with either. */
    fun toggleKanji(character: String) {
        val open = character.takeIf { it != _expandedKanji.value }
        _expandedKanji.value = open
        _expandedRadical.value = null
        if (open != null) _lastKanji.value = open
    }

    /** Toggles one radical inside the open details. */
    fun toggleRadical(id: Int) {
        _expandedRadical.value = id.takeIf { it != _expandedRadical.value }
    }

    /** Closes the inline details from the panel's own control. */
    fun closeDetails() {
        _expandedKanji.value = null
        _expandedRadical.value = null
    }

    /** The app went to the background, so the time it spends there is not answer time. */
    fun onPaused() {
        if (_step.value is Step.Session) pausedAt = SystemClock.elapsedRealtime()
    }

    /**
     * The app came back: the card on screen may no longer be the one AnkiDroid would hand out — it
     * may have been answered in AnkiDroid meanwhile — so the queue is read again instead of grading a
     * stale card. Nothing is ever re-sent on its own, and the time spent away is shifted out of the
     * answered-in time.
     */
    fun onResumed() {
        val away = pausedAt?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        pausedAt = null
        if (_busy.value) return
        val session = _step.value as? Step.Session ?: return
        nextCard(keep = session.copy(startedAt = session.startedAt + (away ?: 0L)))
    }

    /**
     * Asks AnkiDroid for the next due card in the chosen deck, fresh, and reads it.
     *
     * [keep] is the card the reader is already on: when the scheduler hands out the same card again
     * it keeps its revealed side and its timer, so coming back from another app does not reset the
     * reader's place. A different card always starts from the front.
     */
    private fun nextCard(keep: Step.Session? = null) {
        val deck = _deck.value ?: return
        _busy.value = true
        collapseDetails()
        viewModelScope.launch {
            when (val queue = AnkiReview.nextCard(getApplication(), deck.id)) {
                is AnkiQueue.Due -> {
                    val card = AnkiReview.card(getApplication(), queue.noteId, queue.ord)
                    _step.value = when (card) {
                        is AnkiReviewCard.Available -> {
                            val same = keep?.takeIf {
                                it.card.noteId == card.noteId && it.card.ord == card.ord
                            }
                            Step.Session(
                                card = card,
                                token = queue.token,
                                buttonLabels = queue.buttonLabels,
                                mediaFiles = queue.mediaFiles,
                                startedAt = same?.startedAt ?: SystemClock.elapsedRealtime(),
                                revealed = same?.revealed == true
                            )
                        }

                        is AnkiReviewCard.Failed ->
                            Step.Failed(card.failure, card.detail, afterGrade = false)
                    }
                }

                AnkiQueue.Empty -> _step.value = Step.Done(deck.name)
                is AnkiQueue.Failed ->
                    _step.value = Step.Failed(queue.failure, queue.detail, afterGrade = false)
            }
            _busy.value = false
        }
    }

    /** Drops the open details, so a new card never shows the last one's. */
    private fun collapseDetails() {
        _expandedKanji.value = null
        _expandedRadical.value = null
        _lastKanji.value = null
    }
}
