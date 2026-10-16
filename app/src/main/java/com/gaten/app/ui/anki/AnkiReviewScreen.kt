/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gaten.app.R
import com.gaten.app.anki.AnkiDeck
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.FeedbackSound
import com.gaten.app.ui.components.KanjiStrip
import com.gaten.app.ui.components.kanjiIn
import com.gaten.app.ui.components.pressScale
import com.gaten.app.ui.lookup.SheetType
import com.gaten.app.ui.lookup.sheetType
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.ui.theme.pageSlide
import com.gaten.app.ui.wanikani.WaniKaniDetailPanel
import com.gaten.app.wanikani.WaniKaniTarget
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long a rating's answer flash lasts: the card is tinted for this long, half of it fading in and
 * half out, so the sweep is over before the next card's page slide is.
 */
private const val FLASH_MS = 250L

/** How opaque that tint gets: enough to read as an answer, not enough to hide the word under it. */
private const val FLASH_ALPHA = 0.18f

/**
 * The size a page slide brings with it, for the one surface inside this screen whose height is its
 * content's — the inline WaniKani panel. It moves over the same [Motion.PAGE] and curve as the slide
 * itself, and it does not clip: the page leaving is still drawn after its own height is gone.
 */
private fun pageResize(): SizeTransform = SizeTransform(
    clip = false,
    sizeAnimationSpec = { _, _ -> tween(Motion.PAGE, easing = Motion.slideEasing) }
)

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
 * Whether [this] step is a card session, and so whether the host's own navigation has to stay out of
 * the way: a card on screen, the wait for one, or a card whose read or answer just failed inside the
 * deck the reader chose.
 *
 * [deck] is the deck the view model is reviewing, and it is what separates a failure inside a deck
 * from a failure that never got that far: a deck list that could not be read leaves the reader on
 * the picker's own ground, with nothing in flight and nothing to lose by leaving. The finished deck
 * is not a session either — the deck is over, and the banner that says so is the last word on it —
 * nor are the states before a deck is chosen.
 */
internal fun Step.holdsSession(deck: AnkiDeck?): Boolean = when (this) {
    is Step.Session -> true
    is Step.Failed -> deck != null
    // The wait for a chosen deck's first card: the picker is still drawn, but the reader has already
    // left it, and the card on its way is the one the next tap would grade.
    is Step.Decks -> deck != null
    is Step.Done -> false
    Step.Starting -> false
    is Step.Blocked -> false
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
 *
 * [onSessionActiveChange] tells the host whether a card session is open — a card on screen, or a
 * card whose read or answer just failed — so the host's own navigation can stay out of the way until
 * the reader is back at the deck picker. The view model belongs to the host (it is the activity's),
 * so a session survives a rotation, a theme change and this screen being recomposed away, and it
 * ends when the reader leaves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiReviewScreen(
    onClose: () -> Unit,
    onSessionActiveChange: (Boolean) -> Unit = {},
    viewModel: AnkiReviewViewModel = viewModel(),
    isCurrentDestination: Boolean = true
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
    // Ticks and cues come from the app's one feedback path, so what this screen gives back is what
    // the rest of the app gives back: a tick for a selection and for a submitted rating, nothing for
    // an ordinary button — a reveal is not a choice — and never for a dialog.
    val feedback = rememberFeedback()
    // Leaving asks first only while a card is on screen; the answer is the screen's own, not the
    // session's, so a rotation may as well drop it.
    var confirmLeave by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.openDecks() else viewModel.permissionDenied()
    }

    // What the host needs to know: whether a card session is open, so its own navigation can stay
    // out of the way. Reported whenever that fact changes — and only then, so a recomposition that
    // changes nothing reports nothing.
    val sessionActive = step.holdsSession(deck)
    LaunchedEffect(sessionActive) { onSessionActiveChange(sessionActive) }

    // The finished deck's one cue. It fires on the move *into* the finished state and never because
    // a composition found the session already over: the step first seen is remembered, so a rotation,
    // a recomposition or coming back to a session left finished all compose with Done already in
    // hand and stay silent. The sound is off unless the reader turned sounds on, where
    // [Feedback.sound] decides it.
    var seenStep by remember { mutableStateOf(step) }
    LaunchedEffect(step) {
        if (step is Step.Done && seenStep !is Step.Done) {
            feedback.haptic(FeedbackHaptic.Confirm)
            feedback.sound(FeedbackSound.Done)
        }
        seenStep = step
    }

    // The screen's own start, once per entry: AnkiDroid's status, then its deck list. A session
    // already running is left alone — the view model outlives this screen, so coming back to one has
    // to land on the card the reader left. Anything else is read again, because every state before a
    // card is stale by the time the reader comes back: AnkiDroid may have been installed, its access
    // granted, or its collection set up in the meantime.
    LaunchedEffect(Unit) {
        val current = viewModel.step.value
        if (!current.holdsSession(viewModel.deck.value)) viewModel.refresh()
    }

    // Back from another app, the card on screen may no longer be the one AnkiDroid would hand out —
    // it may have been answered in AnkiDroid meanwhile — so the queue is read again instead of
    // grading a stale card. Nothing is ever re-sent on its own. The time spent away is not time
    // spent on the card, so it is shifted out of the answered-in time. The view model owns both
    // halves, so they survive a rotation with the session they belong to.
    //
    // The Activity owns background time; destination transitions must not trigger queue refreshes.
    val lifecycleOwner = LocalActivity.current as? LifecycleOwner ?: LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // This screen can leave the composition without its owner ever pausing — a contextual
            // jump out to Settings uncomposes it while the activity stays resumed — so the moment of
            // leaving is taken now. That is what makes the time away answer time-free, and it is what
            // the observer's own catch-up ON_RESUME reads on the way back in to re-read the queue.
            viewModel.onPaused()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    /**
     * Back from this screen. Inside a deck it stays inside the review flow: with a card on screen it
     * asks first — walking away mid-card is a decision, and the card has not been answered — and
     * confirming goes back to the picker, as does a card that failed, so the reader's next choice is
     * always a deck. The picker, the states before it and the finished deck leave for Home at once:
     * none of them has anything in flight to lose.
     *
     * While a card is being read or an answer is being written, Back waits for it instead: AnkiDroid
     * applies every answer it receives and cannot undo one, and the chosen deck's first card is not a
     * read to walk out of either. This is the same window the host's navigation is hidden in, so Back
     * and the hidden bar agree, and it settles on its own — the step that follows decides where Back
     * goes next. The screen already says so — the saving row under the rating bar.
     */
    fun leave() {
        if (busy && sessionActive) return
        when {
            // A card on screen: leaving is a decision, so it asks first.
            step is Step.Session -> confirmLeave = true
            // A card that failed inside a deck: there is no answer left to give, so Back goes to the
            // picker rather than out of the review flow.
            step is Step.Failed && sessionActive -> viewModel.changeDeck()
            else -> onClose()
        }
    }
    // Registered only while a card session is open. With no session there is nothing here to lose,
    // so Back is left to the host's own navigation — which leaves with its own transition — while a
    // card, a card on its way, or a card whose read or answer just failed inside a chosen deck is
    // this screen's to answer.
    // An outgoing scene can remain composed during a transition; only the current page may guard Back.
    BackHandler(enabled = sessionActive && isCurrentDestination) { leave() }

    Scaffold(
        // The bottom is left to the content: the pinned actions carry the navigation-bar inset
        // themselves, and taking it here as well would pad for the same bar twice. The top is this
        // Scaffold's own bar, the sides are the system's.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
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
            // One page slide per step boundary — the same move the route above makes, [pageSlide].
            // The reveal and the next card both push, and a move between kinds of step goes forward
            // when the new one sits deeper and back when it does not, so a failure or the picker
            // returns the way the reader came. The key is the card itself: the reveal and the next
            // card are the same step type, and keying on the type alone would swap them silently. No
            // size animation: the pinned actions must not jump.
            AnimatedContent(
                targetState = step,
                contentKey = { current ->
                    when (current) {
                        is Step.Session ->
                            "${current.card.noteId}:${current.card.ord}:${current.token}:${current.revealed}"
                        else -> current::class
                    }
                },
                transitionSpec = {
                    val forward = when {
                        // A card's own boundaries — the reveal and the next card — are page turns.
                        initialState is Step.Session && targetState is Step.Session -> true
                        else -> targetState.depth() > initialState.depth()
                    }
                    pageSlide(forward = forward) using null
                },
                label = "reviewStep",
                modifier = Modifier.fillMaxSize()
            ) { current ->
                when (current) {
                    Step.Starting -> BusyRow(
                        message = stringResource(R.string.ankireview_starting),
                        modifier = Modifier.fillMaxWidth().padding(Spacing.xl)
                    )

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
                        // Choosing a deck is a selection, so it ticks; starting the session is an
                        // ordinary button and does not.
                        onDeckSelected = { feedback.haptic(FeedbackHaptic.Tick) },
                        onStart = { chosen -> viewModel.startDeck(chosen) }
                    )

                    is Step.Session -> SessionView(
                        session = current,
                        busy = busy,
                        type = type,
                        expandedKanji = expandedKanji,
                        expandedRadical = expandedRadical,
                        // The reveal is an ordinary tap: the page slide and the flash are what it
                        // gives back, so it gets no tick of its own.
                        onReveal = { viewModel.reveal() },
                        // Submitting a rating changes the state of the card, so it ticks — the same
                        // tick a chip's selection gets, not a firmer one.
                        onGrade = { ease ->
                            feedback.haptic(FeedbackHaptic.Tick)
                            viewModel.grade(current, ease)
                        },
                        // One tick per *selection*: opening a kanji or a radical the reader was not
                        // already on. Tapping the open one closes it, and a close is not a selection.
                        onKanji = { character ->
                            if (character != expandedKanji) feedback.haptic(FeedbackHaptic.Tick)
                            viewModel.toggleKanji(character)
                        },
                        onRadical = { id ->
                            if (id != expandedRadical) feedback.haptic(FeedbackHaptic.Tick)
                            viewModel.toggleRadical(id)
                        },
                        onCloseDetails = { viewModel.closeDetails() },
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
            text = { Text(stringResource(R.string.experience_anki_leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    // Back to the deck list, not out of the review flow: the reader still has decks
                    // to review, and the card they walked away from is not this screen's to answer.
                    viewModel.changeDeck()
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
private fun BusyRow(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(Spacing.l),
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
            text = stringResource(R.string.experience_anki_blocked_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.m))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = stringResource(failureMessage(failure)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        when (failure) {
            AnkiReviewFailure.PERMISSION -> Button(
                onClick = onGrant,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            ) {
                Text(stringResource(R.string.ankireview_grant))
            }

            AnkiReviewFailure.NOT_SET_UP -> OutlinedButton(
                onClick = onOpenAnkiDroid,
                modifier = Modifier.fillMaxWidth()
            ) {
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
    onDeckSelected: () -> Unit,
    onStart: (AnkiDeck) -> Unit
) {
    var selected by remember(decks) {
        mutableStateOf(decks.firstOrNull { it.name == configured } ?: decks.firstOrNull())
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.xl)) {
        Text(
            text = stringResource(R.string.ankireview_choose_deck),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.experience_anki_picker_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.l))
        if (decks.isEmpty()) {
            Text(
                text = stringResource(R.string.ankireview_no_decks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        // One group, so a reader who cannot see the cards is told which deck of how many each one
        // is, and a list, so a deck count nobody bounded costs only what is on screen.
        LazyColumn(
            modifier = Modifier.weight(1f).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            items(items = decks, key = { it.id }) { deck ->
                DeckRow(
                    deck = deck,
                    selected = deck.id == selected?.id,
                    configured = deck.name == configured,
                    // A selection *change*: tapping the deck already selected changes nothing, so it
                    // gives nothing back.
                    onSelect = {
                        if (deck.id != selected?.id) {
                            selected = deck
                            onDeckSelected()
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(Spacing.m))
        val startInteractions = remember { MutableInteractionSource() }
        Button(
            onClick = { selected?.let(onStart) },
            enabled = selected != null && !busy,
            interactionSource = startInteractions,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .pressScale(startInteractions)
        ) { Text(stringResource(R.string.ankireview_start)) }
        Spacer(Modifier.height(Spacing.l))
    }
}

/**
 * One deck as a card: a radio, its name, AnkiDroid's own counts as tags, and — when it is the deck
 * Gaten exports to — what that means. The whole card is the target: the selectable surface is what
 * is clicked, it is what carries the card's own selection state, and it is what states the radio
 * role the drawn control cannot state for itself. The radio is decoration over that target —
 * `onClick = null` is what keeps it from being a second one, and it is also what keeps it at its
 * icon size rather than the library's floor for a control the reader touches — so the mark that is
 * drawn and the one that is announced agree without the row growing for a target it does not need.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeckRow(
    deck: AnkiDeck,
    selected: Boolean,
    configured: Boolean,
    onSelect: () -> Unit
) {
    // The press animation and the row's own click share one source, so the card under the finger is
    // what shrinks, for exactly as long as the press is the row's own.
    val interactions = remember { MutableInteractionSource() }
    Surface(
        selected = selected,
        onClick = onSelect,
        interactionSource = interactions,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .semantics { role = Role.RadioButton }
    ) {
        Column(modifier = Modifier.padding(Spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = deck.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(Spacing.s))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                CountTag(stringResource(R.string.experience_anki_deck_new, deck.newCount))
                CountTag(stringResource(R.string.experience_anki_deck_learning, deck.learnCount))
                CountTag(stringResource(R.string.experience_anki_deck_review, deck.reviewCount))
                if (deck.filtered) {
                    CountTag(stringResource(R.string.ankireview_deck_filtered))
                }
            }
            if (configured) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = stringResource(R.string.experience_anki_deck_default),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** One of AnkiDroid's own counts, as a tag on the deck's card. */
@Composable
private fun CountTag(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp)
        )
    }
}

/**
 * One card: its own rendered HTML filling the free space, the kanji of the note's fields under it,
 * and Gaten's actions pinned at the bottom. The side on screen is stated above the card — question
 * or answer — so which one it is never has to be inferred from the note type's own layout, and the
 * rating is asked for in words before the buttons offer it.
 *
 * WaniKani opens in place: a kanji expands its details under the strip as a page of its own, tapping
 * it again or the panel's own close slides that page away, another kanji slides over it, and a
 * radical inside opens one level deeper. Nothing here is a screen of its own, so no back control has
 * to unwind it. On the question side the strip offers only kanji the question already shows, so a
 * tap cannot reveal part of the answer.
 */
@Composable
private fun SessionView(
    session: Step.Session,
    busy: Boolean,
    type: SheetType,
    expandedKanji: String?,
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
    // The two tones a rating flashes on the card: the error tone for the one answer that means the
    // card was not known, the accent for the three that mean it was (§1.6).
    val flashWrong = MaterialTheme.colorScheme.error
    val flashRight = MaterialTheme.colorScheme.primary
    // The rating just sent, from the card it was sent for on: a new card starts with nothing
    // flashing, and the sweep is half [FLASH_MS] fading in and half fading out.
    var flashTint by remember(session.token) { mutableStateOf<Color?>(null) }
    val flashAlpha by animateFloatAsState(
        targetValue = if (flashTint != null) FLASH_ALPHA else 0f,
        animationSpec = tween((FLASH_MS / 2).toInt(), easing = Motion.pressEasing),
        label = "answerFlash"
    )
    LaunchedEffect(flashTint) {
        if (flashTint == null) return@LaunchedEffect
        delay(FLASH_MS / 2)
        flashTint = null
    }

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

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // The height the card is centred in: what the pinned actions leave. The surface still
            // scrolls as one when the card is taller than that.
            val viewport = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(surfaceScroll)
                    .onGloballyPositioned { surfaceViewportY = it.positionInRoot().y }
                    // The screen's own horizontal rhythm: the card, the strip and the inline panel
                    // all share it, and the top inset keeps the card off the top bar.
                    .padding(horizontal = Spacing.l)
                    .padding(top = Spacing.l)
                    // At least as tall as the space it has: a one-word question then sits in the
                    // middle of the screen instead of hanging under the bar, and a card taller than
                    // the viewport simply scrolls.
                    .heightIn(min = (viewport - Spacing.l).coerceAtLeast(0.dp))
                    .wrapContentHeight(Alignment.CenterVertically)
            ) {
                // Which side of the card is showing, said outright: the reader should never have to
                // work out from the note type's own layout whether the answer is already there.
                CardSideTag(revealed = session.revealed)
                Spacer(Modifier.height(Spacing.m))
                // The answer flash is drawn over the rendered card, never through it: the note's own
                // HTML is untouched, and the tint is only a layer the Compose surface around it adds.
                Box(modifier = Modifier.fillMaxWidth()) {
                    CardHtml(
                        css = card.css,
                        html = if (session.revealed) card.answer else card.question,
                        ord = card.ord,
                        frontWord = !session.revealed,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val tint = flashTint
                    if (tint != null) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(flashAlpha)
                                .background(tint)
                        )
                    }
                }

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
                    // The details move as pages, with the slide every other page change uses: the
                    // kanji panel is one, a radical opened inside it is the next one deeper, and
                    // closing a level goes back the way the reader came. Each level is keyed on its
                    // own target, so a kanji swap slides the new page over the old one. The one
                    // thing animated besides the slide is the size, because the strip above must not
                    // jump as the panel opens and closes.
                    AnimatedContent(
                        targetState = expandedKanji,
                        transitionSpec = {
                            pageSlide(forward = targetState != null) using pageResize()
                        },
                        label = "kanjiDetails"
                    ) { character ->
                        if (character == null) return@AnimatedContent
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
                            // One level deeper: the radical the reader tapped is a page of its own,
                            // opened inside the kanji's page rather than over it, so the kanji panel
                            // above stands still while the radical slides in.
                            AnimatedContent(
                                targetState = expandedRadical,
                                transitionSpec = {
                                    pageSlide(forward = targetState != null) using pageResize()
                                },
                                label = "radicalDetails"
                            ) { id ->
                                if (id == null) return@AnimatedContent
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

        // Pinned: the honest notes, then the one action the reader has right now — the rating row
        // above all. It carries the navigation-bar inset itself, so the row sits clear of the
        // gesture band rather than under it (and the Scaffold above does not take that inset too).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.l, vertical = Spacing.l)
        ) {
            if (session.mediaFiles.isNotEmpty()) {
                NoteCard(
                    text = stringResource(R.string.ankireview_media, session.mediaFiles.size),
                    actionLabel = stringResource(R.string.ankireview_media_open),
                    onAction = onOpenAnkiDroid
                )
                Spacer(Modifier.height(Spacing.s))
            }
            if (session.revealed && card.typedAnswer) {
                NoteCard(text = stringResource(R.string.ankireview_typed))
                Spacer(Modifier.height(Spacing.s))
            }
            if (!session.revealed) {
                val revealInteractions = remember { MutableInteractionSource() }
                Button(
                    onClick = onReveal,
                    enabled = !busy,
                    interactionSource = revealInteractions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .pressScale(revealInteractions)
                ) { Text(stringResource(R.string.ankireview_show_answer)) }
            } else {
                Text(
                    text = stringResource(R.string.experience_anki_rate_prompt),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.s))
                RatingBar(
                    labels = session.buttonLabels,
                    enabled = !busy,
                    // The rating that goes back is also what the card flashes with: "Again" is the
                    // one answer that means the card was not known.
                    onGrade = { ease ->
                        flashTint = if (ease == 1) flashWrong else flashRight
                        onGrade(ease)
                    }
                )
            }
            if (busy) {
                Spacer(Modifier.height(Spacing.s))
                BusyRow(stringResource(R.string.ankireview_saving))
            }
        }
    }
}

/** The side of the card on screen: the accent for the answer, a quiet neutral for the question. */
@Composable
private fun CardSideTag(revealed: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (revealed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = stringResource(
                if (revealed) R.string.experience_anki_answer else R.string.experience_anki_question
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (revealed) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs)
        )
    }
}

/**
 * One of the screen's honest notes — media it cannot show, a card that expects typed input — on a
 * quiet card of its own, with the action that answers it when there is one.
 */
@Composable
private fun NoteCard(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.s))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(Spacing.s))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

/**
 * The deck's ratings. The label over each button is the interval the scheduler itself reported for
 * that rating's position, so a tap sends back exactly the rating its interval was computed for.
 *
 * "Again" takes the error tones and the other three the tonal component's own pair — which the theme
 * already points at the accent's container — because the one answer that means the card was not
 * known is the one that should read differently. No colour carries meaning by itself — the rating's
 * name is on every button, and the intervals are what a decision is made on.
 * Each button is a floor rather than a fixed height, and its two lines may wrap, so a large display
 * size makes the bar taller instead of cutting the words in it.
 */
@Composable
private fun RatingBar(
    labels: List<String>,
    enabled: Boolean,
    onGrade: (Int) -> Unit
) {
    val ratings = remember(labels) { ratingsFor(labels.size) }
    // One interaction source per button, so each one's own press is what scales it: the press
    // animation and the click share the source, and neither swallows the other's gesture.
    val interactions = remember(ratings) { List(ratings.size) { MutableInteractionSource() } }
    Row(modifier = Modifier.fillMaxWidth()) {
        ratings.forEachIndexed { index, rating ->
            if (index > 0) Spacer(Modifier.width(Spacing.s))
            FilledTonalButton(
                onClick = { onGrade(rating.ease) },
                enabled = enabled,
                interactionSource = interactions[index],
                // "Again" keeps its own tones — the one answer that means the card was not known is
                // the one that reads differently — and the other three are the component's own
                // tonal pair, which the theme points at the accent's container already.
                colors = if (rating.ease == 1) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
                contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = Spacing.s),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .pressScale(interactions[index])
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(rating.name),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = labels.getOrNull(rating.ease - 1).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current,
                        maxLines = 2,
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        // A finished deck is a good outcome, and it is the one state on this screen that says so.
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = stringResource(R.string.experience_anki_done_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = stringResource(R.string.ankireview_nothing_due, deckName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        val finishInteractions = remember { MutableInteractionSource() }
        Button(
            onClick = onFinish,
            interactionSource = finishInteractions,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .pressScale(finishInteractions)
        ) {
            Text(stringResource(R.string.ankireview_finish))
        }
        Spacer(Modifier.height(Spacing.s))
        val deckInteractions = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = onChangeDeck,
            enabled = !busy,
            interactionSource = deckInteractions,
            modifier = Modifier.fillMaxWidth().pressScale(deckInteractions)
        ) {
            Text(stringResource(R.string.ankireview_change_deck))
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.experience_anki_failed_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.m))
        val message = if (step.failure == AnkiReviewFailure.PROVIDER && step.detail != null) {
            stringResource(R.string.ankireview_provider_error, step.detail)
        } else {
            stringResource(failureMessage(step.failure))
        }
        // The failure is the one block on this screen worth colour: it is what the reader has to
        // read, and everything below it is only a way out of it.
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(Spacing.s))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (step.afterGrade) {
                        Spacer(Modifier.height(Spacing.s))
                        Text(
                            text = stringResource(R.string.ankireview_after_grade_failure),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.l))
        Button(
            onClick = onRetry,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
        ) {
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
            OutlinedButton(
                onClick = onOpenAnkiDroid,
                modifier = Modifier.fillMaxWidth()
            ) {
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
private fun CardHtml(
    css: String,
    html: String,
    ord: Int,
    frontWord: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface.toArgb()
    val onSurface = colors.onSurface.toArgb()
    // The question side carries the word the reader is being asked about, so it takes the theme's
    // display step — the largest thing this app draws. It is written into the document in the units
    // this viewport is laid out in, which are dp, and with the theme's own sp number: the WebView
    // already applies the reader's font scale to the text it draws, so a factor of our own here
    // would apply it twice. Nothing caps that scale. It is said on `#content` — the same place the
    // text colour is forced, and for the same reason — because a note type's own `.card{font-size}`
    // would otherwise outrank a default set on the body; a size the note type sets *inside* the
    // content, on its own elements and classes, still wins over what they inherit.
    val face = MaterialTheme.typography.displayMedium
    val frontRule = if (frontWord) {
        // The line height is stated as the theme's own ratio rather than in pixels: the text scale
        // the reader set multiplies the font size, and a fixed pixel line height would not follow it.
        "#content{font-size:${face.fontSize.value}px;line-height:${face.lineHeight.value / face.fontSize.value}}"
    } else {
        ""
    }
    val document = remember(css, html, ord, onSurface, frontRule) {
        cardDocument(css, html, ord, onSurface, frontRule)
    }
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
 * Two style blocks sandwich the note type's own CSS. The first carries the defaults — margins,
 * wrapping, image sizing, the text colour the theme asks for and, on the question side, the front
 * word's display size ([frontRule]); the second comes after the note type's own CSS and states the
 * colour again. Both of the ones the app's design has to own are said on `#content` itself, and for
 * the same reason: a note type's base rules are written for `.card` on the body element — the shipped
 * layout carries `.card{color:#111;font-size:24px}` — and a rule on the body is outranked by them,
 * while a rule on the content root is not. Anything the note type states *inside* `#content` — a
 * styled span, `small{font-size:16px}` — still wins over what that root hands down, so a note type's
 * own formatting is never overwritten, only its base default. Backgrounds are the one thing forced
 * outright: `transparent !important`, so the Compose surface is what the card sits on.
 */
private fun cardDocument(
    css: String,
    html: String,
    ord: Int,
    onSurface: Int,
    frontRule: String
): String {
    val foreground = "#" + (0xFFFFFF and onSurface).toString(16).padStart(6, '0')
    return "<!doctype html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html,body{background:transparent !important;margin:0;padding:0}" +
        "body{color:$foreground;overflow-wrap:break-word}" +
        "#content{padding:1em}$frontRule" +
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
