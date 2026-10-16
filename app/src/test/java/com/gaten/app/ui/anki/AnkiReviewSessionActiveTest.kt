/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.anki

import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiReviewCard
import com.gaten.app.anki.AnkiReviewFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the host is told while the review screen is open: whether a card session is running — a card
 * on screen, the wait for one, or a failure inside the deck the reader chose — and nothing else.
 *
 * The distinction is what the host hides its own navigation behind. Reporting too much would strand
 * the reader in a screen that only offers them a review; reporting too little would let the host's
 * navigation walk out of a session that is still the reader's place in the deck.
 */
class AnkiReviewSessionActiveTest {

    private val deck = AnkiDeck(1L, "Deck", newCount = 3, learnCount = 1, reviewCount = 12, filtered = false)

    private fun card() = AnkiReviewCard.Available(
        noteId = 1L,
        ord = 0,
        deckName = "Deck",
        question = "question",
        answer = "answer",
        css = "",
        fieldNames = emptyList(),
        fields = emptyList(),
        typedAnswer = false
    )

    private fun session() = Step.Session(
        card = card(),
        token = 7L,
        buttonLabels = listOf("10m", "1d"),
        mediaFiles = emptyList(),
        startedAt = 0L,
        revealed = false
    )

    @Test
    fun aCardOnScreenIsASessionOnEitherSide() {
        assertTrue(session().holdsSession(deck))
        assertTrue(session().copy(revealed = true).holdsSession(deck))
    }

    /** The wait for a chosen deck's first card: drawn as the picker, but already inside the deck. */
    @Test
    fun theWaitForAChosenDecksFirstCardIsASession() {
        assertTrue(Step.Decks(listOf(deck)).holdsSession(deck))
    }

    /** A card that failed inside a deck is still the reader's place, so the host still waits. */
    @Test
    fun aFailedCardReadOrAnswerInsideADeckIsStillASession() {
        assertTrue(Step.Failed(AnkiReviewFailure.PROVIDER, "detail", afterGrade = true).holdsSession(deck))
        assertTrue(Step.Failed(AnkiReviewFailure.NOT_FOUND, null, afterGrade = false).holdsSession(deck))
    }

    @Test
    fun theStatesBeforeADeckIsChosenAreNotSessions() {
        assertFalse(Step.Starting.holdsSession(null))
        assertFalse(Step.Blocked(AnkiReviewFailure.PERMISSION).holdsSession(null))
        assertFalse(Step.Decks(listOf(deck)).holdsSession(null))
    }

    /**
     * A deck list that could not be read leaves the reader on the picker's own ground: that failure
     * is not a card session, whatever state it is carried in.
     */
    @Test
    fun aFailureOutsideADeckIsNotASession() {
        assertFalse(Step.Failed(AnkiReviewFailure.PROVIDER, "detail", afterGrade = false).holdsSession(null))
    }

    @Test
    fun aFinishedDeckIsNotASession() {
        assertFalse(Step.Done("Deck").holdsSession(deck))
    }
}
