/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.savedstate.serialization.SavedStateConfiguration
import java.util.UUID
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Where the app is: one back stack per tab, and which of them is on screen.
 *
 * Every tab keeps its stack, so switching tabs costs nothing — a query, a half-read word, a review
 * session, an unsaved settings page are all still where they were left. The stack on screen is the
 * Home stack with the selected tab's above it, so back from any tab's own ground lands on Home, and
 * back at Home belongs to the activity.
 *
 * Nothing here draws anything: a screen reports what the reader asked for, and this turns it into a
 * list of entries.
 *
 * @param stacks the four stacks, one per tab, each seeded with its [GatenTab.baseKey].
 * @param currentTab reads the tab on screen; a function, so the tab and the stacks stay saved by the
 *   composition that owns them.
 * @param onSelectTab shows another tab.
 */
class GatenNavigation(
    val stacks: Map<GatenTab, MutableList<NavKey>>,
    private val currentTab: () -> GatenTab,
    private val onSelectTab: (GatenTab) -> Unit
) {

    /** The tab on screen. */
    val selectedTab: GatenTab get() = currentTab()

    /** The stacks on screen, Home first. */
    fun displayedTabs(): List<GatenTab> =
        if (selectedTab == GatenTab.Home) {
            listOf(GatenTab.Home)
        } else {
            listOf(GatenTab.Home, selectedTab)
        }

    /**
     * True while the reader has opened Search to type and the field has not taken the cursor yet.
     *
     * It is the shell's, not the screen's: the screen is thrown away whenever the reader looks at
     * another tab, and the open that asks for the keyboard is exactly the one that has to outlive it.
     * A rotation rebuilds this navigator with the flag false, so a cursor request is never replayed —
     * the same reason a return from a word opens nothing.
     */
    var searchFocusRequested by mutableStateOf(false)
        private set

    /**
     * Shows [tab], leaving every stack as it is. Tapping the tab already on is nothing.
     *
     * A tap on Search whose own ground — the results — is what is on top is a reader about to type,
     * so the field is asked for the cursor. A page left open above the results is where the reader
     * was, and is shown as it is, with the keyboard down.
     */
    fun selectTab(tab: GatenTab) {
        if (tab == selectedTab) return
        switchTab(tab)
        if (tab == GatenTab.Search && stacks.getValue(tab).size == 1) searchFocusRequested = true
    }

    /** Shows [tab] without asking the search field for anything: a move that is not a tap on Search. */
    private fun switchTab(tab: GatenTab) {
        if (tab != selectedTab) onSelectTab(tab)
    }

    /**
     * Opens Search at its results — whatever page was left above them — and asks the field for the
     * cursor: the reader is opening the screen to type, and a word left open is not where they meant
     * to go. The query, the results and the reading position live in the search view model, so they
     * are all still here.
     */
    fun openSearch() {
        val stack = stacks.getValue(GatenTab.Search)
        while (stack.size > 1) stack.removeLast()
        switchTab(GatenTab.Search)
        searchFocusRequested = true
    }

    /** The screen has taken the cursor request; nothing raises the keyboard for it a second time. */
    fun consumeSearchFocus() {
        searchFocusRequested = false
    }

    /**
     * Opens [key] above the entry beneath it.
     *
     * A page a search reached belongs to Search, wherever the reader is; a settings page belongs to
     * the tab it was opened from, because the reader is meant to come back to what they were doing.
     */
    fun push(key: GatenDetailKey) {
        when (key) {
            is WordKey, is KanjiKey, is RadicalKey -> {
                switchTab(GatenTab.Search)
                pushOn(GatenTab.Search, key)
            }

            is SettingsPageKey -> pushPage(key)
        }
    }

    /** Opens [page] inside Settings, on the tab the reader is on. */
    fun openSettingsPage(page: String) =
        push(SettingsPageKey(page = page, instance = UUID.randomUUID().toString()))

    /**
     * Opens the reference page of [character] on the open word, or closes it when it is the page
     * already open: a word shows one kanji page, so another kanji replaces it.
     */
    fun openKanji(character: String) =
        toggleReference(KanjiKey(character)) { it is KanjiKey || it is RadicalKey }

    /** Opens [id]'s radical page inside the open kanji page, or closes it when it is the one open. */
    fun openRadical(id: Int) = toggleReference(RadicalKey(id)) { it is RadicalKey }

    /**
     * One step back: the page above the base closes, and a tab that is not Home hands the reader to
     * Home. At Home with nothing open there is nothing to close: that press is the activity's.
     */
    fun goBack() {
        val stack = stacks.getValue(selectedTab)
        when {
            stack.size > 1 -> stack.removeLastOrNull()
            selectedTab != GatenTab.Home -> selectTab(GatenTab.Home)
        }
    }

    /** Leaves the review flow for Home: the review screen is a destination, not a stack of its own. */
    fun exitToHome() = selectTab(GatenTab.Home)

    /**
     * Whether [key] is the destination the reader is on, in [tab]'s stack.
     *
     * A destination that has just been left is still composed while it moves out, so a guard inside it
     * — a draft that asks before discarding, a session that owns Back — has to know it is no longer
     * the one being looked at: otherwise it goes on answering for a screen that is on its way out, or
     * for one whose tab the reader has left. Hence both halves: the tab on screen, and the entry on
     * top of that tab's stack.
     */
    fun isCurrent(tab: GatenTab, key: GatenKey): Boolean =
        selectedTab == tab && stacks.getValue(tab).lastOrNull() == key

    /** Adds [key] to [tab]'s stack unless it is the page already open there. */
    private fun pushOn(tab: GatenTab, key: GatenDetailKey) {
        val stack = stacks.getValue(tab)
        if (stack.lastOrNull() == key) return
        stack.add(key)
    }

    /**
     * Opens a settings page on the current tab, unless that page is the one already open there. A
     * page that is open further down is left alone — it is not the reader's screen, and what it
     * holds is not this push's to discard.
     */
    private fun pushPage(key: SettingsPageKey) {
        val stack = stacks.getValue(selectedTab)
        val open = stack.lastOrNull()
        if (open is SettingsPageKey && open.page == key.page) return
        stack.add(key)
    }

    /**
     * Opens [key] as the one reference page it is: it closes when it is the page already open, and
     * the page it replaces — a kanji for a kanji, a radical for a radical — is dropped rather than
     * left under it, so the chain never grows past one kanji and one radical.
     */
    private fun toggleReference(key: GatenDetailKey, replaced: (NavKey) -> Boolean) {
        val stack = stacks.getValue(GatenTab.Search)
        val dropped = mutableListOf<NavKey>()
        while (stack.size > 1 && replaced(stack.last())) dropped += stack.removeLast()
        if (dropped == listOf(key)) return
        stack.add(key)
    }
}

/**
 * The app's navigation state, saved: the tab on screen and the four stacks with it, so a rotation or
 * a process death comes back to the same tab, the same pages and the same drafts inside them.
 */
@Composable
fun rememberGatenNavigation(): GatenNavigation {
    var tab by rememberSaveable { mutableStateOf(GatenTab.Home) }
    val home = rememberNavBackStack(GATEN_SAVED_STATE, GatenTab.Home.baseKey)
    val search = rememberNavBackStack(GATEN_SAVED_STATE, GatenTab.Search.baseKey)
    val review = rememberNavBackStack(GATEN_SAVED_STATE, GatenTab.Review.baseKey)
    val settings = rememberNavBackStack(GATEN_SAVED_STATE, GatenTab.Settings.baseKey)

    return remember(home, search, review, settings) {
        GatenNavigation(
            stacks = mapOf(
                GatenTab.Home to home,
                GatenTab.Search to search,
                GatenTab.Review to review,
                GatenTab.Settings to settings
            ),
            currentTab = { tab },
            onSelectTab = { tab = it }
        )
    }
}

/**
 * How the four stacks are read back from saved state.
 *
 * Every key this app can push is registered under [NavKey] by hand, and the module is what decodes a
 * restored stack: the Android-only overload of [rememberNavBackStack] would look the serializer up
 * reflectively instead, which R8's full mode would have to be told to keep.
 */
private val GATEN_SAVED_STATE = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(HomeKey::class)
            subclass(SearchKey::class)
            subclass(ReviewKey::class)
            subclass(SettingsKey::class)
            subclass(WordKey::class)
            subclass(KanjiKey::class)
            subclass(RadicalKey::class)
            subclass(SettingsPageKey::class)
        }
    }
}
