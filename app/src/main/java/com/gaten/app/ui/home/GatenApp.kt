/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gaten.app.ui.anki.AnkiReviewScreen
import com.gaten.app.ui.anki.AnkiReviewViewModel
import com.gaten.app.ui.anki.holdsSession
import com.gaten.app.ui.nav.GatenNavigation
import com.gaten.app.ui.nav.GatenRootScene
import com.gaten.app.ui.nav.GatenTab
import com.gaten.app.ui.nav.HomeKey
import com.gaten.app.ui.nav.KanjiKey
import com.gaten.app.ui.nav.RadicalKey
import com.gaten.app.ui.nav.ReviewKey
import com.gaten.app.ui.nav.SearchKey
import com.gaten.app.ui.nav.SettingsKey
import com.gaten.app.ui.nav.SettingsPageKey
import com.gaten.app.ui.nav.WordKey
import com.gaten.app.ui.nav.gatenContentKey
import com.gaten.app.ui.nav.gatenPopTransitionSpec
import com.gaten.app.ui.nav.gatenPredictivePopTransitionSpec
import com.gaten.app.ui.nav.gatenTransitionSpec
import com.gaten.app.ui.nav.rememberGatenNavigation
import com.gaten.app.ui.search.SearchReferenceScreen
import com.gaten.app.ui.search.SearchScreen
import com.gaten.app.ui.search.SearchViewModel
import com.gaten.app.ui.search.SearchWordScreen
import com.gaten.app.ui.theme.AccentColor
import com.gaten.app.ui.theme.Palette
import com.gaten.app.ui.theme.ThemeMode
import com.gaten.app.wanikani.WaniKaniTarget

/**
 * The app: four destinations, each with a back stack of its own, shown by one navigation display.
 *
 * Everything the reader can be on is a destination — a settings page, one dictionary entry, a kanji's
 * WaniKani page, a radical's — so back, the predictive gesture and what is underneath are the
 * display's own business rather than a flag per screen. Which page is on screen is
 * [GatenNavigation]; what each page draws is [gatenEntryProvider].
 *
 * The three view models a flow outlives its destination with are held here, above the entries: the
 * search results and an export under way, the review session on the card the reader is on, and a
 * model download that has to finish. Everything else a page keeps — a settings draft, a scroll
 * position, an open sheet — belongs to the entry and goes with it when the page closes.
 */
@Composable
fun GatenApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    palette: Palette,
    onPaletteChange: (Palette) -> Unit,
    accent: AccentColor,
    onAccentChange: (AccentColor) -> Unit,
    settingsPageRequest: String? = null,
    onSettingsPageRequestHandled: () -> Unit = {}
) {
    val navigator = rememberGatenNavigation()
    val search = viewModel<SearchViewModel>()
    val review = viewModel<AnkiReviewViewModel>()
    val recognition = viewModel<RecognitionViewModel>()

    // The theme behind a stable reference: an entry is built once per stack change, so it has to read
    // the theme in force now rather than the one that was in force when it was built.
    val theme = rememberUpdatedState(GatenTheme(themeMode, palette, accent))
    val scope = remember(navigator, search, review, recognition, theme) {
        GatenEntryScope(
            navigator = navigator,
            search = search,
            review = review,
            recognition = recognition,
            theme = theme,
            onThemeModeChange = onThemeModeChange,
            onPaletteChange = onPaletteChange,
            onAccentChange = onAccentChange
        )
    }

    // A launch from outside — the lookup panel's "Open Gaten" link — asks for one settings page by
    // name, once per intent. A name this app has no page for changes nothing: showing the reader a
    // page their request did not name would be worse than leaving them where they were.
    LaunchedEffect(settingsPageRequest) {
        val requested = settingsPageRequest ?: return@LaunchedEffect
        settingsRouteOf(requested)?.let { page ->
            navigator.selectTab(GatenTab.Settings)
            navigator.openSettingsPage(page)
        }
        onSettingsPageRequestHandled()
    }

    // One decorated entry list per stack, all four built every composition: that is what keeps a tab
    // that is not on screen holding its own saved state and its own view models.
    val homeEntries = tabEntries(scope, GatenTab.Home)
    val searchEntries = tabEntries(scope, GatenTab.Search)
    val reviewEntries = tabEntries(scope, GatenTab.Review)
    val settingsEntries = tabEntries(scope, GatenTab.Settings)

    // What is on screen: the Home stack with the selected tab's above it, so back from any tab's own
    // ground lands on Home. A stack that is not on screen is not drawn, but nothing about it is lost.
    val stackEntries = mapOf(
        GatenTab.Home to homeEntries,
        GatenTab.Search to searchEntries,
        GatenTab.Review to reviewEntries,
        GatenTab.Settings to settingsEntries
    )
    val displayed = navigator.displayedTabs().flatMap { stackEntries.getValue(it) }

    NavDisplay(
        entries = displayed,
        onBack = navigator::goBack,
        transitionSpec = gatenTransitionSpec,
        popTransitionSpec = gatenPopTransitionSpec,
        predictivePopTransitionSpec = gatenPredictivePopTransitionSpec,
        modifier = Modifier.fillMaxSize()
    )
}

/** The theme the app is drawn in, read by the entries through a reference that outlives them. */
private data class GatenTheme(val mode: ThemeMode, val palette: Palette, val accent: AccentColor)

/** What every entry draws with. */
private class GatenEntryScope(
    val navigator: GatenNavigation,
    val search: SearchViewModel,
    val review: AnkiReviewViewModel,
    val recognition: RecognitionViewModel,
    val theme: State<GatenTheme>,
    val onThemeModeChange: (ThemeMode) -> Unit,
    val onPaletteChange: (Palette) -> Unit,
    val onAccentChange: (AccentColor) -> Unit
)

/**
 * One stack's entries, decorated with what its pages save into: a state holder for their
 * `rememberSaveable` values and a view model store for anything scoped to an entry of their own.
 */
@Composable
private fun tabEntries(scope: GatenEntryScope, tab: GatenTab): List<NavEntry<NavKey>> =
    rememberDecoratedNavEntries(
        backStack = scope.navigator.stacks.getValue(tab),
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = gatenEntryProvider(scope, tab)
    )

/**
 * What each key draws, inside [tab].
 *
 * One provider per tab, because an entry's content key carries the tab it belongs to — which is what
 * lets the same settings page be open on two tabs at once, each with its own draft, without the two
 * sharing saved state or a view model.
 */
private fun gatenEntryProvider(scope: GatenEntryScope, tab: GatenTab): (NavKey) -> NavEntry<NavKey> =
    entryProvider {
        val navigator = scope.navigator

        // Home: the way into a search or the review, and the setup pages the reader reaches from here.
        entry<HomeKey>(clazzContentKey = { gatenContentKey(it, tab) }) {
            GatenRootScene(tab = GatenTab.Home, onSelect = navigator::selectTab) {
                HomeDashboard(
                    onOpenSearch = navigator::openSearch,
                    onOpenReview = { navigator.selectTab(GatenTab.Review) },
                    onOpenSettings = navigator::openSettingsPage
                )
            }
        }

        // Search: one query and its results. Opening one is a destination, not a swap inside this
        // screen.
        entry<SearchKey>(clazzContentKey = { gatenContentKey(it, tab) }) {
            GatenRootScene(tab = GatenTab.Search, onSelect = navigator::selectTab) {
                SearchScreen(
                    onOpenWord = { entryId -> navigator.push(WordKey(entryId)) },
                    focusRequested = navigator.searchFocusRequested,
                    onFocusConsumed = navigator::consumeSearchFocus,
                    viewModel = scope.search
                )
            }
        }

        // Review: one destination, because the flow inside it — the card, its answer, the rating — is
        // the session's own and goes back to nothing but the card before it.
        entry<ReviewKey>(clazzContentKey = { gatenContentKey(it, tab) }) {
            val step by scope.review.step.collectAsState()
            val deck by scope.review.deck.collectAsState()
            GatenRootScene(
                tab = GatenTab.Review,
                onSelect = navigator::selectTab,
                // A card session is the only copy of where the reader is in the deck, so the bar is
                // away while one is open and comes back at the deck picker.
                barVisible = !step.holdsSession(deck)
            ) {
                AnkiReviewScreen(
                    onClose = navigator::exitToHome,
                    viewModel = scope.review,
                    // The session owns Back only while its tab is the one on screen: on the way out,
                    // that press belongs to wherever the reader is going.
                    isCurrentDestination = navigator.isCurrent(GatenTab.Review, ReviewKey)
                )
            }
        }

        // Settings: the hub, and one destination per page over it.
        entry<SettingsKey>(clazzContentKey = { gatenContentKey(it, tab) }) {
            GatenRootScene(tab = GatenTab.Settings, onSelect = navigator::selectTab) {
                SettingsScreen(
                    themeMode = scope.theme.value.mode,
                    accent = scope.theme.value.accent,
                    onOpenPage = navigator::openSettingsPage
                )
            }
        }

        entry<SettingsPageKey>(clazzContentKey = { gatenContentKey(it, tab) }) { key ->
            SettingsDetailScreen(
                page = key.page,
                themeMode = scope.theme.value.mode,
                onThemeModeChange = scope.onThemeModeChange,
                palette = scope.theme.value.palette,
                onPaletteChange = scope.onPaletteChange,
                accent = scope.theme.value.accent,
                onAccentChange = scope.onAccentChange,
                onClose = navigator::goBack,
                recognitionViewModel = scope.recognition,
                // Read here, in this destination's own composition: a page on its way out is still
                // composed, and the guard inside it must know it is no longer the one on screen.
                isCurrentDestination = navigator.isCurrent(tab, key)
            )
        }

        // One dictionary entry, and the WaniKani pages of the kanji it is written with.
        entry<WordKey>(clazzContentKey = { gatenContentKey(it, tab) }) { key ->
            SearchWordScreen(
                entryId = key.entryId,
                onBack = navigator::goBack,
                onKanji = navigator::openKanji,
                onAnkiSettings = { navigator.openSettingsPage(SettingsRoutes.ANKI) },
                onWaniKaniSettings = { navigator.openSettingsPage(SettingsRoutes.WANIKANI) },
                viewModel = scope.search
            )
        }

        entry<KanjiKey>(clazzContentKey = { gatenContentKey(it, tab) }) { key ->
            SearchReferenceScreen(
                target = WaniKaniTarget.Kanji(key.character),
                onBack = navigator::goBack,
                onRadical = navigator::openRadical,
                onSettings = { navigator.openSettingsPage(SettingsRoutes.WANIKANI) }
            )
        }

        entry<RadicalKey>(clazzContentKey = { gatenContentKey(it, tab) }) { key ->
            SearchReferenceScreen(
                target = WaniKaniTarget.Radical(key.id),
                onBack = navigator::goBack,
                onRadical = navigator::openRadical,
                onSettings = { navigator.openSettingsPage(SettingsRoutes.WANIKANI) }
            )
        }
    }
