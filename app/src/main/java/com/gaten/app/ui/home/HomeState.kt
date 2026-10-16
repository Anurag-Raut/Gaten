/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.content.Context
import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiDecks
import com.gaten.app.anki.AnkiReview
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.anki.AnkiStatus

/**
 * The pages inside Settings, by route key: the three the rest of the app opens by name and the three
 * only Settings itself opens.
 *
 * The shell recognises all six because a launch from outside the app may name any of them — see
 * [settingsRouteOf] — and because a settings destination is built from its route key.
 */
object SettingsRoutes {
    const val LOOKUP = "lookup"
    const val RECOGNITION = "recognition"
    const val ANKI = "anki"
    const val WANIKANI = "wanikani"
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"

    /** Every page there is, in the order the hub lists them. */
    val PAGES = listOf(LOOKUP, RECOGNITION, ANKI, WANIKANI, APPEARANCE, ABOUT)
}

/** Returns a recognized settings page; unknown external requests are ignored without changing stacks. */
fun settingsRouteOf(requested: String?): String? = requested?.takeIf { it in SettingsRoutes.PAGES }

/** What Home can say about the deck Gaten exports into, from a read that never asked for anything. */
sealed interface DeckStatus {
    /** The first read is in flight. */
    data object NotRead : DeckStatus

    /** AnkiDroid is not installed, or its provider is not visible to Gaten. */
    data object AnkiDroidMissing : DeckStatus

    /** AnkiDroid is installed, but Gaten may not read its collection yet. */
    data object PermissionNeeded : DeckStatus

    /** AnkiDroid has no deck named [configuredName]: the deck the cards go into is gone. */
    data class DeckMissing(val configuredName: String) : DeckStatus

    /**
     * The configured deck and the counts AnkiDroid reports for it.
     *
     * [newCount], [learnCount] and [reviewCount] are AnkiDroid's own numbers for that one deck, and
     * [due] is their sum: a deck's reported counts already include its subdecks, so nothing is added
     * to them from anywhere else.
     */
    data class Counts(
        val deckName: String,
        val newCount: Int,
        val learnCount: Int,
        val reviewCount: Int
    ) : DeckStatus {
        val due: Int get() = newCount + learnCount + reviewCount
    }

    /** AnkiDroid answered, but the read failed. */
    data class Unreadable(val failure: AnkiReviewFailure) : DeckStatus
}

/**
 * The state that needs no read at all, or null when AnkiDroid may be read.
 *
 * [AnkiStatus.Ready] is the only status under which [AnkiReview.decks] is called: a deck list is not
 * worth a permission dialog, so an ungranted AnkiDroid is reported instead of asked.
 */
fun deckStatusWithoutRead(status: AnkiStatus): DeckStatus? = when (status) {
    AnkiStatus.NotInstalled -> DeckStatus.AnkiDroidMissing
    AnkiStatus.NeedsPermission -> DeckStatus.PermissionNeeded
    AnkiStatus.Ready -> null
}

/**
 * The configured deck's own counts, or the reason there are none.
 *
 * The deck is matched by name and read on its own. Never by summing: the provider's counts for a
 * deck already include its subdecks, so a parent's numbers plus a child's would count the same cards
 * twice.
 */
fun deckStatusOf(configuredName: String, decks: AnkiDecks): DeckStatus = when (decks) {
    is AnkiDecks.Failed -> DeckStatus.Unreadable(decks.failure)

    is AnkiDecks.Available -> decks.decks.named(configuredName)?.let { deck ->
        DeckStatus.Counts(
            deckName = deck.name,
            newCount = deck.newCount,
            learnCount = deck.learnCount,
            reviewCount = deck.reviewCount
        )
    } ?: DeckStatus.DeckMissing(configuredName)
}

/** The deck AnkiDroid has under [name]: as written first, then case-folded. */
private fun List<AnkiDeck>.named(name: String): AnkiDeck? =
    firstOrNull { it.name == name } ?: firstOrNull { it.name.equals(name, ignoreCase = true) }

/**
 * Reads the configured deck's counts without asking for anything.
 *
 * AnkiDroid's permission is the reader's to grant, and the review screen is where it is asked for;
 * this only ever reports that it is missing.
 */
suspend fun readDeckStatus(context: Context): DeckStatus {
    deckStatusWithoutRead(AnkiReview.status(context))?.let { return it }
    return deckStatusOf(AnkiReview.configuredDeckName(context), AnkiReview.decks(context))
}
