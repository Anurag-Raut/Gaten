/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiDecks
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.anki.AnkiStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two things on Home that are decided rather than drawn: which settings route key names a page,
 * and what the review card can say about AnkiDroid.
 *
 * What a back press does is navigation now, and is decided in `GatenNavigationTest`.
 */
class HomeStateTest {

    private fun deck(name: String, new: Int = 0, learning: Int = 0, review: Int = 0) = AnkiDeck(
        id = name.hashCode().toLong(),
        name = name,
        newCount = new,
        learnCount = learning,
        reviewCount = review,
        filtered = false
    )

    @Test
    fun everyPageSettingsCanDrawIsRecognised() {
        // The three the rest of the app opens by name and the three only Settings itself opens: the
        // shell recognises all six because an outside launch may name any of them.
        val pages = listOf(
            SettingsRoutes.LOOKUP,
            SettingsRoutes.RECOGNITION,
            SettingsRoutes.ANKI,
            SettingsRoutes.WANIKANI,
            SettingsRoutes.APPEARANCE,
            SettingsRoutes.ABOUT
        )

        for (key in pages) {
            assertEquals(key, settingsRouteOf(key))
        }
        assertEquals(pages, SettingsRoutes.PAGES)
    }

    @Test
    fun anOutsideRequestTheAppHasNoPageForNamesNoPage() {
        // A key the app has no page for — a typo, a route from a newer version, an empty extra — is
        // not a page: the host opens nothing for it, so the reader keeps whatever they had open.
        for (request in listOf("nonsense", "", "wanikani::extra", null)) {
            assertNull(settingsRouteOf(request))
        }
    }

    @Test
    fun anAnkiDroidThatMayNotBeReadYetIsReportedAndNeverAsked() {
        assertEquals(DeckStatus.AnkiDroidMissing, deckStatusWithoutRead(AnkiStatus.NotInstalled))
        assertEquals(DeckStatus.PermissionNeeded, deckStatusWithoutRead(AnkiStatus.NeedsPermission))
        // Only a granted AnkiDroid is read at all; that read is what a permission dialog would follow.
        assertNull(deckStatusWithoutRead(AnkiStatus.Ready))
    }

    @Test
    fun theConfiguredDeckIsReadOnItsOwnAndNotSummedWithItsSubdecks() {
        // AnkiDroid's counts for a deck already include its subdecks, so a parent and its child must
        // never be added together: the deck itself is the answer, both counts and name.
        val decks = AnkiDecks.Available(
            listOf(
                deck("Gaten", new = 2, learning = 1, review = 3),
                deck("Gaten::Grammar", new = 40, learning = 40, review = 40)
            )
        )

        val status = deckStatusOf("Gaten", decks)

        assertEquals(
            DeckStatus.Counts(deckName = "Gaten", newCount = 2, learnCount = 1, reviewCount = 3),
            status
        )
        assertEquals(6, (status as DeckStatus.Counts).due)
    }

    @Test
    fun theDeckNameIsMatchedAsWrittenFirstThenCaseFolded() {
        val decks = AnkiDecks.Available(listOf(deck("Japanese::Genki", new = 7)))

        assertEquals(
            DeckStatus.Counts("Japanese::Genki", newCount = 7, learnCount = 0, reviewCount = 0),
            deckStatusOf("japanese::genki", decks)
        )
    }

    @Test
    fun aConfiguredDeckThatIsGoneIsNotAFinishedDeck() {
        // An empty AnkiDroid, and one that has decks but not this one, both mean the configured deck
        // has to be picked again — neither is a deck with nothing due.
        val empty = deckStatusOf("Gaten", AnkiDecks.Available(emptyList()))
        assertEquals(DeckStatus.DeckMissing("Gaten"), empty)

        val others = deckStatusOf("Gaten", AnkiDecks.Available(listOf(deck("Mining", new = 9))))
        assertEquals(DeckStatus.DeckMissing("Gaten"), others)
    }

    @Test
    fun aDeckWithNothingDueIsStillADeck() {
        val status = deckStatusOf("Gaten", AnkiDecks.Available(listOf(deck("Gaten"))))

        assertEquals(DeckStatus.Counts("Gaten", 0, 0, 0), status)
        assertEquals(0, (status as DeckStatus.Counts).due)
    }

    @Test
    fun aFailedReadIsReportedWithItsReason() {
        assertEquals(
            DeckStatus.Unreadable(AnkiReviewFailure.PROVIDER),
            deckStatusOf("Gaten", AnkiDecks.Failed(AnkiReviewFailure.PROVIDER, "no decks cursor"))
        )
    }
}
