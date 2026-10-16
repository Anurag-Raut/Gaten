/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.nav

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the reader goes, and what back does with it: the screens themselves are not this test's. */
class GatenNavigationTest {

    /** A shell with the four stacks, and the tab it treats as selected. */
    private class Shell(initialTab: GatenTab = GatenTab.Home) {
        var selected = initialTab
        private val stacks = GatenTab.entries.associateWith { mutableListOf<NavKey>(it.baseKey) }
        val navigation = GatenNavigation(
            stacks = stacks,
            currentTab = { selected },
            onSelectTab = { this.selected = it }
        )

        fun stack(tab: GatenTab): List<NavKey> = stacks.getValue(tab)

        /** The settings pages [tab]'s stack holds, in order. */
        fun pages(tab: GatenTab): List<String> =
            stack(tab).filterIsInstance<SettingsPageKey>().map { it.page }
    }

    @Test
    fun everyTabStartsOnItsOwnBase() {
        val shell = Shell()

        for (tab in GatenTab.entries) {
            assertEquals(listOf(tab.baseKey), shell.stack(tab))
        }
        assertEquals(GatenTab.Home, shell.navigation.selectedTab)
    }

    @Test
    fun selectingATabKeepsWhatTheOthersHadOpen() {
        val shell = Shell()
        shell.navigation.push(WordKey(entryId = 3))

        shell.navigation.selectTab(GatenTab.Review)

        assertEquals(listOf(SearchKey, WordKey(entryId = 3)), shell.stack(GatenTab.Search))
        assertEquals(listOf(ReviewKey), shell.stack(GatenTab.Review))
        assertEquals(GatenTab.Review, shell.navigation.selectedTab)
    }

    @Test
    fun selectingTheTabAlreadyOnChangesNothing() {
        val shell = Shell(initialTab = GatenTab.Search)
        shell.navigation.push(WordKey(entryId = 1))

        shell.navigation.selectTab(GatenTab.Search)

        assertEquals(listOf(SearchKey, WordKey(entryId = 1)), shell.stack(GatenTab.Search))
    }

    @Test
    fun aWordOpensOnTheSearchStack() {
        val shell = Shell()

        shell.navigation.push(WordKey(entryId = 4))

        assertEquals(listOf(SearchKey, WordKey(entryId = 4)), shell.stack(GatenTab.Search))
        assertEquals(GatenTab.Search, shell.navigation.selectedTab)
    }

    @Test
    fun theWordAlreadyOpenIsNotOpenedAgain() {
        val shell = Shell()

        shell.navigation.push(WordKey(entryId = 4))
        shell.navigation.push(WordKey(entryId = 4))

        assertEquals(listOf(SearchKey, WordKey(entryId = 4)), shell.stack(GatenTab.Search))
    }

    @Test
    fun anotherKanjiReplacesTheKanjiPageAndTheSameOneClosesIt() {
        val shell = Shell()
        shell.navigation.push(WordKey(entryId = 1))

        shell.navigation.openKanji("猫")
        assertEquals(
            listOf(SearchKey, WordKey(entryId = 1), KanjiKey("猫")),
            shell.stack(GatenTab.Search)
        )

        // A word shows one kanji page, so another kanji takes its place rather than stacking above it.
        shell.navigation.openKanji("犬")
        assertEquals(
            listOf(SearchKey, WordKey(entryId = 1), KanjiKey("犬")),
            shell.stack(GatenTab.Search)
        )

        shell.navigation.openKanji("犬")
        assertEquals(listOf(SearchKey, WordKey(entryId = 1)), shell.stack(GatenTab.Search))
    }

    @Test
    fun aRadicalPageOpensInsideTheKanjiPageAndReplacesItself() {
        val shell = Shell()
        shell.navigation.push(WordKey(entryId = 1))
        shell.navigation.openKanji("猫")

        shell.navigation.openRadical(12)
        assertEquals(
            listOf(SearchKey, WordKey(entryId = 1), KanjiKey("猫"), RadicalKey(12)),
            shell.stack(GatenTab.Search)
        )

        shell.navigation.openRadical(30)
        assertEquals(
            listOf(SearchKey, WordKey(entryId = 1), KanjiKey("猫"), RadicalKey(30)),
            shell.stack(GatenTab.Search)
        )

        shell.navigation.openRadical(30)
        assertEquals(
            listOf(SearchKey, WordKey(entryId = 1), KanjiKey("猫")),
            shell.stack(GatenTab.Search)
        )
    }

    @Test
    fun aSettingsPageOpensOnTheTabTheReaderIsOn() {
        val shell = Shell()

        shell.navigation.openSettingsPage("anki")

        assertEquals(listOf("anki"), shell.pages(GatenTab.Home))
        assertEquals(emptyList<String>(), shell.pages(GatenTab.Settings))
    }

    @Test
    fun thePageAlreadyOpenIsNotOpenedAgainButABuriedOneIsItsOwnEntry() {
        val shell = Shell()

        shell.navigation.openSettingsPage("anki")
        shell.navigation.openSettingsPage("anki")
        assertEquals(listOf("anki"), shell.pages(GatenTab.Home))

        // The reader has gone on to another page, and this page is opened again underneath it: the
        // copy below is not this push's to discard, and two entries may not share a content key.
        shell.navigation.openSettingsPage("wanikani")
        shell.navigation.openSettingsPage("anki")

        assertEquals(listOf("anki", "wanikani", "anki"), shell.pages(GatenTab.Home))
        val instances = shell.stack(GatenTab.Home)
            .filterIsInstance<SettingsPageKey>()
            .filter { it.page == "anki" }
            .map { it.instance }
        assertEquals(2, instances.toSet().size)
    }

    @Test
    fun theSamePageOpenedFromAnotherTabLeavesTheFirstAlone() {
        val shell = Shell()

        shell.navigation.openSettingsPage("anki")
        shell.navigation.selectTab(GatenTab.Settings)
        shell.navigation.openSettingsPage("anki")

        // Both are real destinations: one is the page the reader was editing on Home, the other the
        // one the Settings hub opened. Neither is taken away by the other.
        assertEquals(listOf("anki"), shell.pages(GatenTab.Home))
        assertEquals(listOf("anki"), shell.pages(GatenTab.Settings))
    }

    @Test
    fun backClosesTheOpenPageFirst() {
        val shell = Shell()
        shell.navigation.push(WordKey(entryId = 7))
        shell.navigation.openKanji("猫")

        shell.navigation.goBack()
        assertEquals(listOf(SearchKey, WordKey(entryId = 7)), shell.stack(GatenTab.Search))

        shell.navigation.goBack()
        assertEquals(listOf(SearchKey), shell.stack(GatenTab.Search))

        // At its own ground, the tab hands the reader to Home; only the display knows to stop there.
        shell.navigation.goBack()
        assertEquals(GatenTab.Home, shell.navigation.selectedTab)
        assertEquals(listOf(SearchKey), shell.stack(GatenTab.Search))
    }

    @Test
    fun backAtHomeChangesNothing() {
        // Home is the last thing there is: with nothing open there, the press belongs to the
        // activity and the app closes.
        val shell = Shell()

        shell.navigation.goBack()

        assertEquals(GatenTab.Home, shell.navigation.selectedTab)
        assertEquals(listOf(HomeKey), shell.stack(GatenTab.Home))
    }

    @Test
    fun aDestinationKnowsWhetherItIsTheOneOnScreen() {
        val shell = Shell()
        val word = WordKey(entryId = 1)
        shell.navigation.push(word)
        assertTrue(shell.navigation.isCurrent(GatenTab.Search, word))

        // Another tab is on screen: the page is still in its stack, and still not the one being read.
        shell.navigation.selectTab(GatenTab.Home)
        assertFalse(shell.navigation.isCurrent(GatenTab.Search, word))

        // A page that has been popped is not current either, however briefly it is still traversing.
        shell.navigation.selectTab(GatenTab.Search)
        shell.navigation.goBack()
        assertFalse(shell.navigation.isCurrent(GatenTab.Search, word))
    }

    @Test
    fun theHomeStackIsAlwaysBeneathTheSelectedTab() {
        val shell = Shell(initialTab = GatenTab.Settings)

        assertEquals(listOf(GatenTab.Home, GatenTab.Settings), shell.navigation.displayedTabs())

        shell.navigation.selectTab(GatenTab.Home)
        assertEquals(listOf(GatenTab.Home), shell.navigation.displayedTabs())

        shell.navigation.selectTab(GatenTab.Review)
        assertEquals(listOf(GatenTab.Home, GatenTab.Review), shell.navigation.displayedTabs())
    }

    @Test
    fun everyKeyInOneStackHasItsOwnContentKey() {
        // The content key is what saved state and entry view models are held under, so two entries of
        // one stack may never share it — including the two copies of a page that can end up there.
        val shell = Shell()
        shell.navigation.push(WordKey(entryId = 1))
        shell.navigation.openKanji("猫")
        shell.navigation.selectTab(GatenTab.Home)
        shell.navigation.openSettingsPage("anki")
        shell.navigation.selectTab(GatenTab.Settings)
        shell.navigation.openSettingsPage("anki")

        val home = shell.stack(GatenTab.Home).map { gatenContentKey(it as GatenKey, GatenTab.Home) }
        val settings = shell.stack(GatenTab.Settings).map { gatenContentKey(it as GatenKey, GatenTab.Settings) }

        assertEquals(home.size, home.toSet().size)
        assertEquals(settings.size, settings.toSet().size)
        assertTrue(home.intersect(settings.toSet()).isEmpty())
    }

    @Test
    fun openingSearchDropsARetainedWordAndTakesTheCursor() {
        val shell = Shell()
        shell.navigation.push(WordKey(entryId = 5))
        shell.navigation.openKanji("猫")

        shell.navigation.openSearch()

        // The reader is opening Search to type: the results are the screen, not the pages left above
        // them, and the field is asked for the cursor.
        assertEquals(listOf(SearchKey), shell.stack(GatenTab.Search))
        assertEquals(GatenTab.Search, shell.navigation.selectedTab)
        assertTrue(shell.navigation.searchFocusRequested)

        shell.navigation.consumeSearchFocus()
        assertFalse(shell.navigation.searchFocusRequested)
    }

    @Test
    fun openingSearchLeavesEveryOtherStackAsItWas() {
        val shell = Shell()
        shell.navigation.openSettingsPage("anki")
        shell.navigation.selectTab(GatenTab.Settings)
        shell.navigation.openSettingsPage("wanikani")

        shell.navigation.openSearch()

        assertEquals(listOf("anki"), shell.pages(GatenTab.Home))
        assertEquals(listOf("wanikani"), shell.pages(GatenTab.Settings))
        assertEquals(GatenTab.Search, shell.navigation.selectedTab)
    }

    @Test
    fun tappingSearchOverItsResultsTakesTheCursorAndAPageLeftOpenDoesNot() {
        val shell = Shell(initialTab = GatenTab.Settings)

        // Search's own ground is the results: a reader who taps the tab there is about to type.
        shell.navigation.selectTab(GatenTab.Search)
        assertTrue(shell.navigation.searchFocusRequested)
        shell.navigation.consumeSearchFocus()
        assertFalse(shell.navigation.searchFocusRequested)

        // A page left open above the results is where the reader was: the tab shows it as it is, and
        // no keyboard comes up for it.
        shell.navigation.push(WordKey(entryId = 9))
        shell.navigation.selectTab(GatenTab.Settings)
        shell.navigation.selectTab(GatenTab.Search)

        assertFalse(shell.navigation.searchFocusRequested)
        assertEquals(listOf(SearchKey, WordKey(entryId = 9)), shell.stack(GatenTab.Search))
    }

    @Test
    fun tappingAnotherTabNeverTakesTheCursor() {
        val shell = Shell(initialTab = GatenTab.Settings)

        shell.navigation.selectTab(GatenTab.Review)

        assertFalse(shell.navigation.searchFocusRequested)
    }
}
