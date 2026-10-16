/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The app's four root destinations. Each one owns a back stack of its own. */
enum class GatenTab { Home, Search, Review, Settings }

/** The key [GatenTab]'s stack is seeded with: what that tab shows when nothing is open above it. */
internal val GatenTab.baseKey: GatenKey
    get() = when (this) {
        GatenTab.Home -> HomeKey
        GatenTab.Search -> SearchKey
        GatenTab.Review -> ReviewKey
        GatenTab.Settings -> SettingsKey
    }

/**
 * Every place the app can be.
 *
 * Only the leaf keys carry [Serializable]: the two markers below are never serialized on their own —
 * the back stacks are stored as a [NavKey] list, and the module that decodes them registers each leaf
 * — so nothing depends on a serializer being generated for an interface.
 */
sealed interface GatenKey : NavKey

/** A tab's own root. It seeds that tab's stack and is never pushed onto one. */
@Serializable
data object HomeKey : GatenKey

@Serializable
data object SearchKey : GatenKey

@Serializable
data object ReviewKey : GatenKey

@Serializable
data object SettingsKey : GatenKey

/**
 * A destination opened above a tab's base, and so the only kind of key the navigator pushes.
 *
 * Nothing here carries an entry, a draft or a position: a key names a destination, and the screen
 * behind it reads the rest from where it already lives — the dictionary results, the review queue,
 * the settings the reader is editing.
 */
sealed interface GatenDetailKey : GatenKey

/** One dictionary entry, by id: its own row is resolved from the current results. */
@Serializable
data class WordKey(val entryId: Int) : GatenDetailKey

/** One WaniKani page: a kanji of the open word, or a radical of the open kanji. */
@Serializable
data class KanjiKey(val character: String) : GatenDetailKey

@Serializable
data class RadicalKey(val id: Int) : GatenDetailKey

/**
 * One page inside Settings: its route key ([com.gaten.app.ui.home.SettingsRoutes]), and the instance
 * of that page.
 *
 * Two entries may not share a content key, and the same page can be open twice on one stack — the
 * reader has one page open, an outside launch asks for the same one, and a second page is open over
 * it. [instance] is what tells those apart; the page underneath, drafts and all, is never touched.
 */
@Serializable
data class SettingsPageKey(val page: String, val instance: String) : GatenDetailKey

/**
 * The [androidx.navigation3.runtime.NavEntry.contentKey] of [key] inside [tab]'s stack: what the
 * entry's saved state and view model store are held under, and what tells one tab's copy of a page
 * from another tab's.
 *
 * The tab is part of it because the same settings page can be open on two stacks at once — opened
 * from Home it belongs to Home — and two entries may not share a content key.
 */
internal fun gatenContentKey(key: GatenKey, tab: GatenTab): String = "${key.tag()}@${tab.name}"

/** The tab a content key was stamped with; null for a key this app did not build. */
internal fun tabStampOf(contentKey: Any?): String? = (contentKey as? String)?.substringAfterLast('@')

/** What makes [this] a different destination from every other, arguments and all. */
private fun GatenKey.tag(): String = when (this) {
    HomeKey -> "home"
    SearchKey -> "search"
    ReviewKey -> "review"
    SettingsKey -> "settings"
    is WordKey -> "word=$entryId"
    is KanjiKey -> "kanji=$character"
    is RadicalKey -> "radical=$id"
    is SettingsPageKey -> "page=$page#$instance"
}
