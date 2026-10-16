/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiReviewPolicyTest {

    @Test
    fun answerButtonsFollowTheCountAnkiDroidReported() {
        assertEquals(listOf("1m", "10m"), answerButtons(2, listOf("1m", "10m")))
        assertEquals(listOf("1m", "10m", "1d", "4d"), answerButtons(4, listOf("1m", "10m", "1d", "4d")))
    }

    @Test
    fun answerButtonsArePaddedToTheCountSoIndexIsStillTheEase() {
        assertEquals(listOf("1m", "", "", ""), answerButtons(4, listOf("1m")))
        assertEquals(listOf("1m", "10m", "1d"), answerButtons(3, listOf("1m", "10m", "1d", "4d")))
    }

    @Test
    fun answerButtonsAreEmptyWithoutACountOrWithoutAnkisFourRatings() {
        assertEquals(emptyList<String>(), answerButtons(0, listOf("1m")))
        assertEquals(emptyList<String>(), answerButtons(-1, emptyList()))
        assertEquals(4, answerButtons(9, List(9) { "x" }).size)
    }

    @Test
    fun easeIsOnlyAcceptedWithinTheCardsOwnButtons() {
        assertEquals(1, clampEase(1, 4))
        assertEquals(4, clampEase(4, 4))
        assertEquals(1, clampEase(1, 2))
    }

    @Test
    fun easeOutsideTheCardsOwnButtonsIsRefused() {
        assertNull(clampEase(3, 2))
        assertNull(clampEase(0, 4))
        assertNull(clampEase(5, 4))
        assertNull(clampEase(1, 0))
    }

    @Test
    fun timeTakenIsNeverNegative() {
        assertEquals(0L, normaliseTimeTaken(-1))
        assertEquals(0L, normaliseTimeTaken(0))
        assertEquals(4200L, normaliseTimeTaken(4200))
    }

    @Test
    fun deckCountsAreReadInAnkiDroidsLearnReviewNewOrder() {
        assertEquals(DeckCounts(learn = 2, review = 3, new = 4), deckCountsFrom("[2,3,4]"))
    }

    @Test
    fun deckCountsThatAreMissingOrMalformedCountAsZero() {
        assertEquals(DeckCounts(0, 0, 0), deckCountsFrom(null))
        assertEquals(DeckCounts(0, 0, 0), deckCountsFrom(""))
        assertEquals(DeckCounts(0, 0, 0), deckCountsFrom("not json"))
        assertEquals(DeckCounts(learn = 1, review = 0, new = 0), deckCountsFrom("[1]"))
    }

    @Test
    fun aFilteredDeckIsReadFromTheBooleanAnkiDroidWrites() {
        assertTrue(filteredFrom("true"))
        assertTrue(filteredFrom("1"))
        assertTrue(filteredFrom(" TRUE "))
    }

    @Test
    fun anUnfilteredDeckIsAnythingElse() {
        assertFalse(filteredFrom("false"))
        assertFalse(filteredFrom("0"))
        assertFalse(filteredFrom(""))
        assertFalse(filteredFrom("  "))
        assertFalse(filteredFrom(null))
    }

    @Test
    fun noteFieldsSplitOnAnkiDroidsSeparator() {
        assertEquals(
            listOf("食べる", "たべる", "to eat"),
            splitNoteFields("食べる\u001fたべる\u001fto eat")
        )
    }

    @Test
    fun aNoteWithoutFieldsHasNone() {
        assertEquals(emptyList<String>(), splitNoteFields(null))
        assertEquals(emptyList<String>(), splitNoteFields(""))
    }

    @Test
    fun aTypedAnswerIsRecognisedInEitherTemplate() {
        assertTrue(hasTypedAnswer("{{type:Word}}", "{{FrontSide}}"))
        assertTrue(hasTypedAnswer("{{Word}}", "{{type:Word}}"))
    }

    @Test
    fun aCardWithoutATypeTemplateHasNoTypedAnswer() {
        assertFalse(hasTypedAnswer("{{Word}}", "{{Meaning}}"))
        assertFalse(hasTypedAnswer(null, null))
    }

    @Test
    fun theBackTemplateGatenWroteBeforeTheSentenceLineWasDroppedIsRecognised() {
        assertTrue(isLegacySentenceBack(LEGACY_SENTENCE_BACK))
    }

    @Test
    fun anyOtherBackTemplateIsLeftAlone() {
        assertFalse(isLegacySentenceBack(null))
        assertFalse(isLegacySentenceBack(""))
        assertFalse(isLegacySentenceBack(AnkiSettings.DEFAULT_BACK))
        assertFalse(isLegacySentenceBack("$LEGACY_SENTENCE_BACK "))
        assertFalse(isLegacySentenceBack(LEGACY_SENTENCE_BACK.dropLast(1)))
    }

    @Test
    fun aNoteTypeIsOneOfGatensOwnOnlyWhileItHasTheSentenceField() {
        assertTrue(isLegacyNoteType(listOf("Expression", "Reading", "Meaning", "Sentence")))
    }

    @Test
    fun aNoteTypeWithoutTheSentenceFieldIsNeverTouched() {
        assertFalse(isLegacyNoteType(AnkiSettings.NOTE_FIELDS))
        assertFalse(isLegacyNoteType(emptyList()))
    }

    @Test
    fun aCardThatIsNotLearningIsAlwaysDue() {
        val now = 1_000_000L

        assertTrue(dueNow(cardType = 0, dueEpochSeconds = 42, nowSeconds = now))
        assertTrue(dueNow(cardType = 2, dueEpochSeconds = 20_000, nowSeconds = now))
        assertTrue(dueNow(cardType = 3, dueEpochSeconds = 20_000, nowSeconds = now))
    }

    @Test
    fun aLearningCardWhoseTimeHasComeIsDue() {
        val now = 1_000_000L

        assertTrue(dueNow(cardType = 1, dueEpochSeconds = now, nowSeconds = now))
        assertTrue(dueNow(cardType = 1, dueEpochSeconds = now - 600, nowSeconds = now))
    }

    @Test
    fun aLearningCardWithinTheMinuteIsDueSoAgainStillRepeatsAtOnce() {
        assertTrue(dueNow(cardType = 1, dueEpochSeconds = 1_000_059, nowSeconds = 1_000_000))
        assertTrue(dueNow(cardType = 1, dueEpochSeconds = 1_000_060, nowSeconds = 1_000_000))
    }

    @Test
    fun aLearningCardDueLaterIsNotDue() {
        assertFalse(dueNow(cardType = 1, dueEpochSeconds = 1_000_061, nowSeconds = 1_000_000))
        assertFalse(dueNow(cardType = 1, dueEpochSeconds = 1_000_600, nowSeconds = 1_000_000))
    }

    @Test
    fun aCardWhoseTypeOrDueTimeCouldNotBeReadIsDue() {
        val now = 1_000_000L

        assertTrue(dueNow(cardType = null, dueEpochSeconds = null, nowSeconds = now))
        assertTrue(dueNow(cardType = 1, dueEpochSeconds = null, nowSeconds = now))
        assertTrue(dueNow(cardType = 1, dueEpochSeconds = -1, nowSeconds = now))
    }

    @Test
    fun theFirstDueCardOfTheQueueIsTheOneToShow() {
        val now = 1_000_000L
        val queued = listOf(
            QueuedCard(type = 1, dueEpochSeconds = now + 600),
            QueuedCard(type = 0, dueEpochSeconds = 5)
        )

        assertEquals(1, firstDueIndex(queued, now))
    }

    @Test
    fun aDueLearningCardBehindOneStillWaitingIsShown() {
        val now = 1_000_000L
        val queued = listOf(
            QueuedCard(type = 1, dueEpochSeconds = now + 600),
            QueuedCard(type = 1, dueEpochSeconds = now + 60),
            QueuedCard(type = 0, dueEpochSeconds = 5)
        )

        assertEquals(1, firstDueIndex(queued, now))
    }

    @Test
    fun aQueueWithNothingDueYetHasNoCardToShow() {
        val now = 1_000_000L
        val queued = listOf(
            QueuedCard(type = 1, dueEpochSeconds = now + 600),
            QueuedCard(type = 1, dueEpochSeconds = now + 120)
        )

        assertNull(firstDueIndex(queued, now))
    }

    @Test
    fun anEmptyQueueHasNoCardToShow() {
        assertNull(firstDueIndex(emptyList(), 1_000_000))
    }

    @Test
    fun aRefusedCallIsReportedAsAMissingPermission() {
        assertEquals(AnkiReviewFailure.PERMISSION, reviewFailureOf(SecurityException("denied")))
    }

    @Test
    fun anUnconfiguredStorageIsRecognisedFromTheProvidersOwnWording() {
        assertEquals(
            AnkiReviewFailure.NOT_SET_UP,
            reviewFailureOf(IllegalStateException("AnkiDroid storage is not configured"))
        )
    }

    @Test
    fun aCollectionAnkiDroidCannotOpenIsAProviderFailure() {
        assertEquals(
            AnkiReviewFailure.PROVIDER,
            reviewFailureOf(IllegalStateException("Collection is locked"))
        )
        assertEquals(AnkiReviewFailure.PROVIDER, reviewFailureOf(RuntimeException("binder died")))
    }

    @Test
    fun anAnswerAnkiDroidDoesNotUnderstandIsMalformed() {
        assertEquals(
            AnkiReviewFailure.MALFORMED,
            reviewFailureOf(IllegalArgumentException("Unknown column question"))
        )
        assertEquals(AnkiReviewFailure.MALFORMED, reviewFailureOf(UnsupportedOperationException()))
    }

    @Test
    fun detailNamesTheExceptionAndItsMessage() {
        assertEquals("IllegalStateException: boom", detailOf(IllegalStateException("boom")))
        assertEquals("IllegalStateException", detailOf(IllegalStateException()))
    }

    @Test
    fun aJsonArrayOfStringsIsReadIntoAList() {
        assertEquals(listOf("a", "b"), jsonStrings("""["a","b"]"""))
        assertEquals(listOf("a", "", "b"), jsonStrings("""["a",null,"b"]"""))
    }

    @Test
    fun aJsonArrayThatIsMissingOrMalformedIsEmpty() {
        assertEquals(emptyList<String>(), jsonStrings("[]"))
        assertEquals(emptyList<String>(), jsonStrings(null))
        assertEquals(emptyList<String>(), jsonStrings(""))
        assertEquals(emptyList<String>(), jsonStrings("[1,2"))
    }

    @Test
    fun aTokenIsSpentWhenItIsTaken() {
        val gate = ReviewGate()
        val token = gate.arm(deckId = 1, noteId = 5, ord = 0, buttonCount = 4)

        assertEquals(ReviewGate.Armed(token, 1, 5, 0, 4), gate.take(token))
        assertNull("a token answers one card once", gate.take(token))
    }

    @Test
    fun aTokenRemembersTheDeckItWasReadFrom() {
        val gate = ReviewGate()
        val token = gate.arm(deckId = 42, noteId = 5, ord = 1, buttonCount = 3)

        assertEquals(42L, gate.take(token)?.deckId)
    }

    @Test
    fun aTokenFromABeforeAnAgainReappearanceCannotAnswerTheNewCard() {
        val gate = ReviewGate()
        val first = gate.arm(deckId = 1, noteId = 5, ord = 0, buttonCount = 4)
        // The same card is queued again: the screen holding the old token must not answer it.
        val second = gate.arm(deckId = 1, noteId = 5, ord = 0, buttonCount = 4)

        assertNotEquals(first, second)
        assertNull(gate.take(first))
        assertEquals(ReviewGate.Armed(second, 1, 5, 0, 4), gate.take(second))
    }

    @Test
    fun nothingIsGradeableBeforeTheQueueIsReadOrAfterItIsCleared() {
        val gate = ReviewGate()
        assertNull(gate.take(1))

        val token = gate.arm(deckId = 1, noteId = 5, ord = 0, buttonCount = 4)
        gate.clear()
        assertNull(gate.take(token))
    }
}
