/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gaten.app.R
import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiReviewCard
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.ui.lookup.SheetType
import com.gaten.app.ui.lookup.sheetType
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.ui.wanikani.KanjiStrip
import com.gaten.app.ui.wanikani.WaniKaniDetailPanel
import com.gaten.app.ui.wanikani.kanjiIn
import com.gaten.app.wanikani.WaniKaniTarget
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.launch

/**
 * The tick a tap gives back. [VIRTUAL_KEY] is the light one every supported device has, and
 * [HapticFeedbackConstants.LONG_PRESS] is the firmest widely supported one — the app never passes
 * `FLAG_IGNORE_GLOBAL_SETTING`, so a reader who turned system haptics off gets none of this.
 */
private fun View.tapFeedback() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

/** @see tapFeedback */
private fun View.commitFeedback() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

/** One rating button: the name to show, and the ease Anki expects for it. */
private data class Rating(@StringRes val name: Int, val ease: Int)

/**
 * The buttons a deck actually uses, from AnkiDroid's own button count: all four when it reports
 * four, Again/Good/Easy when it reports three, Again/Good when it reports two. The ease is
 * positional — button `i` is ease `i + 1` — because that is how AnkiDroid computed the interval
 * labels in the same answer, and its own legacy scheduler maps those positions onto its own ratings.
 */
private fun ratingsFor(buttonCount: Int): List<Rating> = when (buttonCount.coerceIn(2, 4)) {
    2 -> listOf(
        Rating(R.string.ankireview_rating_again, 1),
        Rating(R.string.ankireview_rating_good, 2)
    )

    3 -> listOf(
        Rating(R.string.ankireview_rating_again, 1),
        Rating(R.string.ankireview_rating_good, 2),
        Rating(R.string.ankireview_rating_easy, 3)
    )

    else -> listOf(
        Rating(R.string.ankireview_rating_again, 1),
        Rating(R.string.ankireview_rating_hard, 2),
        Rating(R.string.ankireview_rating_good, 3),
        Rating(R.string.ankireview_rating_easy, 4)
    )
}

/**
 * Reviews AnkiDroid's own decks inside Gaten, with AnkiDroid doing the scheduling: the queue, the
 * intervals on the rating buttons and the answer itself all come from AnkiDroid's content provider,
 * and Gaten keeps no progress of its own.
 *
 * The flow is AnkiDroid's: pick a deck (the configured Gaten deck preselected), see the question,
 * reveal the answer, rate it once. Each rating is sent once and only once — while it is being sent
 * the buttons are disabled, and after an attempt whose outcome AnkiDroid did not confirm the card is
 * never re-sent, because the provider applies every answer it receives and offers no way to undo
 * one. The next card always comes from a fresh query, since the scheduler — not this screen —
 * decides what is due next.
 *
 * WaniKani rides on the answer side only, from the note's own raw fields: the card the reader is
 * being graded on cannot leak its answer through a kanji page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiReviewScreen(
    onClose: () -> Unit,
    viewModel: AnkiReviewViewModel = viewModel()
) {
    val context = LocalContext.current
    // The lookup sheet's own scale, so the WaniKani page drawn here is the page from the sheet.
    val type = sheetType(compact = false)
    // The session lives in the ViewModel: a rotation, a dark-mode switch or a split-screen change
    // keeps the card, its timer and its open details instead of dropping back to the deck picker.
    val step by viewModel.step.collectAsState()
    val deck by viewModel.deck.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val expandedKanji by viewModel.expandedKanji.collectAsState()
    val expandedRadical by viewModel.expandedRadical.collectAsState()
    val lastExpandedKanji by viewModel.lastKanji.collectAsState()
    // The app's window, for the small haptic ticks the taps give back.
    val view = LocalView.current
    // Leaving asks first only while a card is on screen; the answer is the screen's own, not the
    // session's, so a rotation may as well drop it.
    var confirmLeave by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.openDecks() else viewModel.permissionDenied()
    }

    // The session's own start: AnkiDroid's status, then its deck list.
    LaunchedEffect(Unit) { viewModel.start() }

    // Back from another app, the card on screen may no longer be the one AnkiDroid would hand out —
    // it may have been answered in AnkiDroid meanwhile — so the queue is read again instead of
    // grading a stale card. Nothing is ever re-sent on its own. The time spent away is not time
    // spent on the card, so it is shifted out of the answered-in time. The view model owns both
    // halves, so they survive a rotation with the session they belong to.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /**
     * Leaves the screen, asking first while a card is on it: walking away mid-card is a decision, and
     * the card on screen has not been answered. Every other step leaves at once — nothing in flight
     * is lost there — and the confirmation itself never grades or discards anything.
     */
    fun leave() {
        if (step is Step.Session) confirmLeave = true else onClose()
    }

    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = deck?.name ?: stringResource(R.string.ankireview_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.ankireview_back)
                        )
                    }
                },
                actions = {
                    if (deck != null) {
                        TextButton(enabled = !busy, onClick = { viewModel.changeDeck() }) {
                            Text(stringResource(R.string.ankireview_change_deck))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // One animation per step boundary: moving deeper slides in from the right and settling
            // (emphasized decelerate), going back slides the other way and leaves quickly
            // (emphasized accelerate). The key is the step's *type*, so a new card — same step —
            // swaps without a slide. No size animation: the pinned actions must not jump.
            AnimatedContent(
                targetState = step,
                contentKey = { it::class },
                transitionSpec = {
                    val forward = targetState.depth() >= initialState.depth()
                    val enter = slideInHorizontally(
                        animationSpec = tween(Motion.MEDIUM, easing = Motion.emphasizedDecelerate)
                    ) { width -> if (forward) width / 6 else -width / 6 } +
                        fadeIn(tween(Motion.MEDIUM, easing = Motion.emphasizedDecelerate))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(Motion.MEDIUM, easing = Motion.emphasizedAccelerate)
                    ) { width -> if (forward) -width / 6 else width / 6 } +
                        fadeOut(tween(Motion.MEDIUM, easing = Motion.emphasizedAccelerate))
                    enter togetherWith exit
                },
                label = "reviewStep",
                modifier = Modifier.fillMaxSize()
            ) { current ->
                when (current) {
                    Step.Starting -> BusyRow(stringResource(R.string.ankireview_starting))

                    is Step.Blocked -> BlockedView(
                        failure = current.failure,
                        onGrant = { permission.launch(AddContentApi.READ_WRITE_PERMISSION) },
                        onOpenAnkiDroid = { openAnkiDroid(context) },
                        modifier = Modifier.fillMaxWidth().padding(Spacing.xl)
                    )

                    is Step.Decks -> DeckPicker(
                        decks = current.decks,
                        configured = viewModel.configuredDeckName,
                        busy = busy,
                        onStart = { chosen ->
                            view.tapFeedback()
                            viewModel.startDeck(chosen)
                        }
                    )

                    is Step.Session -> SessionView(
                        session = current,
                        busy = busy,
                        type = type,
                        expandedKanji = expandedKanji,
                        lastKanji = lastExpandedKanji,
                        expandedRadical = expandedRadical,
                        onReveal = {
                            view.tapFeedback()
                            viewModel.reveal()
                        },
                        // Committing an answer is the one tap worth a firmer tick than a glance.
                        onGrade = { ease ->
                            view.commitFeedback()
                            viewModel.grade(current, ease)
                        },
                        onKanji = { character ->
                            view.tapFeedback()
                            viewModel.toggleKanji(character)
                        },
                        onRadical = { id ->
                            view.tapFeedback()
                            viewModel.toggleRadical(id)
                        },
                        onCloseDetails = {
                            view.tapFeedback()
                            viewModel.closeDetails()
                        },
                        onOpenAnkiDroid = { openAnkiDroid(context) }
                    )

                    is Step.Done -> DoneView(
                        deckName = current.deckName,
                        busy = busy,
                        onFinish = onClose,
                        onChangeDeck = { viewModel.changeDeck() }
                    )

                    is Step.Failed -> FailedView(
                        step = current,
                        busy = busy,
                        onRetry = { viewModel.retry() },
                        onOpenAnkiDroid = { openAnkiDroid(context) },
                        onChangeDeck = { viewModel.changeDeck() }
                    )
                }
            }
        }
    }

    // A card is on screen, so leaving is worth one question — never a grade, never a discard.
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.ankireview_leave_title)) },
            text = { Text(stringResource(R.string.ankireview_leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onClose()
                }) { Text(stringResource(R.string.ankireview_leave_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) {
                    Text(stringResource(R.string.ankireview_leave_stay))
                }
            }
        )
    }
}

/** The next card is on its way, or the deck list is. */
@Composable
private fun BusyRow(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(Spacing.m))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Review cannot run at all: what is missing, and the one thing that fixes it. */
@Composable
private fun BlockedView(
    failure: AnkiReviewFailure,
    onGrant: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(failureMessage(failure)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.l))
        when (failure) {
            AnkiReviewFailure.PERMISSION -> Button(onClick = onGrant) {
                Text(stringResource(R.string.ankireview_grant))
            }

            AnkiReviewFailure.NOT_SET_UP -> OutlinedButton(onClick = onOpenAnkiDroid) {
                Text(stringResource(R.string.ankireview_media_open))
            }

            // Nothing to install, nothing to open: the text above is the whole answer.
            else -> Unit
        }
    }
}

/**
 * The deck picker: every deck AnkiDroid reports, with its own counts, and the deck Gaten exports to
 * preselected. The counts are AnkiDroid's, not a promise about this session.
 */
@Composable
private fun DeckPicker(
    decks: List<AnkiDeck>,
    configured: String,
    busy: Boolean,
    onStart: (AnkiDeck) -> Unit
) {
    var selected by remember(decks) {
        mutableStateOf(decks.firstOrNull { it.name == configured } ?: decks.firstOrNull())
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl)) {
        Text(
            text = stringResource(R.string.ankireview_choose_deck),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.m))
        if (decks.isEmpty()) {
            Text(
                text = stringResource(R.string.ankireview_no_decks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            decks.forEach { deck ->
                DeckRow(
                    deck = deck,
                    selected = deck.id == selected?.id,
                    onSelect = { selected = deck }
                )
                Spacer(Modifier.height(Spacing.s))
            }
        }
        Spacer(Modifier.height(Spacing.m))
        Button(
            onClick = { selected?.let(onStart) },
            enabled = selected != null && !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.ankireview_start)) }
        Spacer(Modifier.height(Spacing.l))
    }
}

/** One deck: name, AnkiDroid's counts, and whether it is the one Gaten is set up to export to. */
@Composable
private fun DeckRow(deck: AnkiDeck, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = deck.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(
                    R.string.ankireview_deck_counts,
                    deck.newCount,
                    deck.learnCount,
                    deck.reviewCount
                ) + if (deck.filtered) " · " + stringResource(R.string.ankireview_deck_filtered) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One card: its own rendered HTML filling the free space, the kanji of the note's fields under it,
 * and Gaten's actions pinned at the bottom.
 *
 * WaniKani opens in place: a kanji expands its details under the strip, tapping it again or the
 * panel's own close collapses them, another kanji swaps them, and a radical inside them opens one
 * level deeper. Nothing here is a screen of its own, so no back control has to unwind it. On the
 * question side the strip offers only kanji the question already shows, so a tap cannot reveal part
 * of the answer.
 */
@Composable
private fun SessionView(
    session: Step.Session,
    busy: Boolean,
    type: SheetType,
    expandedKanji: String?,
    lastKanji: String?,
    expandedRadical: Int?,
    onReveal: () -> Unit,
    onGrade: (Int) -> Unit,
    onKanji: (String) -> Unit,
    onRadical: (Int) -> Unit,
    onCloseDetails: () -> Unit,
    onOpenAnkiDroid: () -> Unit
) {
    val card = session.card
    // The kanji the note's own fields hold. The strip is drawn after the reveal only, so there is
    // nothing to filter here: before the answer, none of this is offered at all.
    val kanji = remember(card.fields) { card.fields.flatMap { kanjiIn(it) }.distinct() }

    Column(modifier = Modifier.fillMaxSize()) {
        // The deck space is one surface: the card, its kanji strip and the inline details scroll
        // together, so a tall card or a long mnemonic moves the whole page instead of a box inside
        // it, and nothing here scrolls on its own. The actions below stay pinned outside it.
        val surfaceScroll = rememberScrollState()
        // The strip's y on screen, and the scroll surface's y. Opening a kanji scrolls by their
        // difference, measured live, so the move is right wherever the reader has scrolled to.
        var stripViewportY by remember { mutableStateOf(0f) }
        var surfaceViewportY by remember { mutableStateOf(0f) }
        // The inset the strip lands at, in pixels.
        val stripInset = with(LocalDensity.current) { Spacing.s.toPx() }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(surfaceScroll)
                    .onGloballyPositioned { surfaceViewportY = it.positionInRoot().y }
                    // The screen's own horizontal rhythm: the card, the strip and the inline panel
                    // all share it, and the top inset keeps the card off the top bar.
                    .padding(horizontal = Spacing.l)
                    .padding(top = Spacing.l)
            ) {
                CardHtml(
                    css = card.css,
                    html = if (session.revealed) card.answer else card.question,
                    ord = card.ord,
                    modifier = Modifier.fillMaxWidth()
                )

                // Answer side only: a tap can never reveal part of the answer, because before the
                // reveal there is no strip to tap.
                if (session.revealed) {
                    KanjiStrip(
                        characters = kanji,
                        onKanji = onKanji,
                        type = type,
                        selected = expandedKanji,
                        // Where the strip sits on screen, so opening a kanji can bring its
                        // details into view.
                        modifier = Modifier.onGloballyPositioned {
                            stripViewportY = it.positionInRoot().y
                        }
                    )
                    AnimatedVisibility(
                        visible = expandedKanji != null,
                        enter = fadeIn(tween(120)) + expandVertically(tween(160)),
                        exit = fadeOut(tween(100)) + shrinkVertically(tween(140))
                    ) {
                        // Swapping kanji swaps this content in place: the panel stays mounted while
                        // only the target it reads changes, so there is no flicker between two, and
                        // the exit draws [lastKanji] because [expandedKanji] is already null then.
                        lastKanji?.let { character ->
                            Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
                                WaniKaniDetailPanel(
                                    target = WaniKaniTarget.Kanji(character),
                                    onBack = onCloseDetails,
                                    onRadical = onRadical,
                                    type = type,
                                    compact = false,
                                    // No arrow here: the chip that opened these details closes
                                    // them, and nothing scrolls inside them: the deck space above
                                    // scrolls as one surface.
                                    showBack = false,
                                    scrollable = false
                                )
                                // One level deeper: the radical the reader tapped, drawn inside the
                                // kanji's own details rather than over them.
                                expandedRadical?.let { id ->
                                    WaniKaniDetailPanel(
                                        target = WaniKaniTarget.Radical(id),
                                        onBack = { onRadical(id) },
                                        onRadical = onRadical,
                                        type = type,
                                        compact = false,
                                        showBack = false,
                                        scrollable = false,
                                        modifier = Modifier.padding(start = Spacing.xl, top = Spacing.s)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Opening brings the details into view; closing simply settles. Keyed on *whether* a kanji
        // is open, not which one, so switching between two kanji leaves the surface exactly where it
        // is while the skeleton holds the layout.
        LaunchedEffect(expandedKanji != null) {
            if (expandedKanji == null) return@LaunchedEffect
            // The strip lands Spacing.s below the surface's top, so the panel below it gets the
            // room. Already at or above that line scrolls nothing.
            val delta = stripViewportY - surfaceViewportY - stripInset
            if (delta > 0f) {
                surfaceScroll.animateScrollBy(
                    delta,
                    tween(Motion.MEDIUM, easing = Motion.emphasizedDecelerate)
                )
            }
        }

        // Pinned: the honest notes, then the one action the reader has right now.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.l)
        ) {
            if (session.mediaFiles.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.ankireview_media, session.mediaFiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onOpenAnkiDroid) {
                        Text(stringResource(R.string.ankireview_media_open))
                    }
                }
            }
            if (session.revealed && card.typedAnswer) {
                Text(
                    text = stringResource(R.string.ankireview_typed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.s))
            if (!session.revealed) {
                Button(
                    onClick = onReveal,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.ankireview_show_answer)) }
            } else {
                RatingBar(labels = session.buttonLabels, enabled = !busy, onGrade = onGrade)
            }
            if (busy) {
                Spacer(Modifier.height(Spacing.s))
                BusyRow(stringResource(R.string.ankireview_saving))
            }
        }
    }
}

/**
 * The deck's ratings. The label over each button is the interval the scheduler itself reported for
 * that rating's position, so a tap sends back exactly the rating its interval was computed for.
 */
@Composable
private fun RatingBar(
    labels: List<String>,
    enabled: Boolean,
    onGrade: (Int) -> Unit
) {
    val ratings = remember(labels) { ratingsFor(labels.size) }
    Row(modifier = Modifier.fillMaxWidth()) {
        ratings.forEachIndexed { index, rating ->
            if (index > 0) Spacer(Modifier.width(Spacing.s))
            Button(
                onClick = { onGrade(rating.ease) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.s),
                modifier = Modifier.weight(1f)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(rating.name),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                    Text(
                        text = labels.getOrNull(rating.ease - 1).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Nothing is due: the deck is finished for now, which is not the same as a failure. */
@Composable
private fun DoneView(
    deckName: String,
    busy: Boolean,
    onFinish: () -> Unit,
    onChangeDeck: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.xl)) {
        Text(
            text = stringResource(R.string.ankireview_nothing_due, deckName),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.l))
        OutlinedButton(
            onClick = onChangeDeck,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ankireview_change_deck))
        }
        Spacer(Modifier.height(Spacing.s))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ankireview_finish))
        }
    }
}

/** Something went wrong: what it was, and the one action that can follow it. */
@Composable
private fun FailedView(
    step: Step.Failed,
    busy: Boolean,
    onRetry: () -> Unit,
    onOpenAnkiDroid: () -> Unit,
    onChangeDeck: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(Spacing.xl).verticalScroll(rememberScrollState())) {
        val message = if (step.failure == AnkiReviewFailure.PROVIDER && step.detail != null) {
            stringResource(R.string.ankireview_provider_error, step.detail)
        } else {
            stringResource(failureMessage(step.failure))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (step.afterGrade) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = stringResource(R.string.ankireview_after_grade_failure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.l))
        Button(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    if (step.afterGrade) {
                        R.string.ankireview_next_card
                    } else {
                        R.string.ankireview_retry
                    }
                )
            )
        }
        if (step.failure == AnkiReviewFailure.PROVIDER) {
            Spacer(Modifier.height(Spacing.s))
            OutlinedButton(onClick = onOpenAnkiDroid, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ankireview_media_open))
            }
        }
        Spacer(Modifier.height(Spacing.s))
        OutlinedButton(
            onClick = onChangeDeck,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ankireview_change_deck))
        }
    }
}

/**
 * The card's own HTML, drawn by AnkiDroid's renderer, on Gaten's surface rather than AnkiDroid's
 * white one: the view is painted with the theme's surface colour and the injected CSS forces the
 * html and body backgrounds transparent, so the Compose surface behind the view is what the card
 * sits on, and the text default comes from the theme — an unpainted note type reads in dark and
 * light alike. Only the defaults are ours: the note type's own CSS is injected after ours, so any
 * colour it sets still wins.
 *
 * The view can do nothing but draw: no JavaScript, no file or content access, no network, no
 * navigation, no zoom, and no touch. Media cannot resolve — AnkiDroid serves no media to other apps
 * — which is what the notice above the ratings is for.
 */
@Composable
private fun CardHtml(css: String, html: String, ord: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface.toArgb()
    val onSurface = colors.onSurface.toArgb()
    val document = remember(css, html, ord, onSurface) { cardDocument(css, html, ord, onSurface) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.blockNetworkLoads = true
                settings.setSupportZoom(false)
                settings.useWideViewPort = true
                setBackgroundColor(surface)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isClickable = false
                isFocusable = false
                // Display only, and now inside the deck space's own scroller: a disabled WebView
                // measures and draws the card exactly as before but cannot take the drag, so the
                // page scrolls instead of the card swallowing the gesture.
                isEnabled = false
                contentDescription = context.getString(R.string.ankireview_card_cd)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = true
                }
            }
        },
        update = { view ->
            // Set on every update as well, so a theme change repaints the view's own background.
            view.setBackgroundColor(surface)
            view.loadCardOnce(document)
        },
        // Detach before destroying: the WebView must not be torn down while it is in the view tree.
        onRelease = { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
    )
}

/** Loads [document] unless this view already holds it, so recomposing never reloads the card. */
private fun WebView.loadCardOnce(document: String) {
    if (tag == document) return
    tag = document
    loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
}

/**
 * The card as a document: AnkiDroid's own shell (`<body class="card cardN">` around `#content`),
 * without its scripts and `file://` stylesheets, which a WebView with no file access cannot load.
 * Compose centres the card, so this only lays it out.
 *
 * Two style blocks sandwich the note type's own CSS. The first carries the defaults the note type is
 * free to override — margins, wrapping, image sizing, and the text colour the theme asks for. The
 * second comes *after* it and fixes the colour on `#content` itself: an old Gaten note type carries
 * `.card{color:#111}`, and since that targets the body element it would otherwise beat any colour set
 * on the body. A rule on `#content` outranks what the card inherited, while anything the note type
 * colours *inside* `#content` — a styled span, `small{color:#666}` — still wins over it. Backgrounds
 * are the one thing forced outright: `transparent !important`, so the Compose surface is what the
 * card sits on.
 */
private fun cardDocument(css: String, html: String, ord: Int, onSurface: Int): String {
    val foreground = "#" + (0xFFFFFF and onSurface).toString(16).padStart(6, '0')
    return "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html,body{background:transparent !important;margin:0;padding:0}" +
        "body{color:$foreground;overflow-wrap:break-word}" +
        "#content{padding:1em}" +
        "img,video{max-width:100%}</style>" +
        "<style>$css</style>" +
        "<style>#content{color:$foreground}</style></head>" +
        "<body class=\"card card${ord + 1}\"><div id=\"content\" dir=\"auto\">$html</div></body></html>"
}

/** Brings AnkiDroid up, for the media or the session this screen cannot show. */
private fun openAnkiDroid(context: Context) {
    val packageName = AddContentApi.getAnkiDroidPackageName(context) ?: return
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // AnkiDroid cannot be opened from here; the reader can still open it by hand.
    }
}

/** The line for a failure, without any of the provider's internals. */
@StringRes
private fun failureMessage(failure: AnkiReviewFailure): Int = when (failure) {
    AnkiReviewFailure.NOT_INSTALLED -> R.string.ankireview_not_installed
    AnkiReviewFailure.PERMISSION -> R.string.ankireview_permission
    AnkiReviewFailure.NOT_SET_UP -> R.string.ankireview_not_set_up
    AnkiReviewFailure.NOT_FOUND -> R.string.ankireview_not_found
    AnkiReviewFailure.MALFORMED -> R.string.ankireview_malformed
    // The detail, when the provider gave one, is added at the call site.
    AnkiReviewFailure.PROVIDER -> R.string.ankireview_provider_generic
}
