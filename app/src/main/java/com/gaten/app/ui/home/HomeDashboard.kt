/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.home

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaten.app.R
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.trigger.TriggerPreferences
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.components.pressScale
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.theme.Spacing

/** Two cards are put beside each other from here on; below it a phone has width for one. */
private val TwoColumnWidth = 640.dp

/** Nothing in the app is wider than this, however wide the screen is. */
private val MaxContentWidth = 840.dp

/**
 * Home: the way into a search, the state of the deck Gaten reviews, and the state of screen lookup.
 *
 * Everything here is read, never kept. The deck is AnkiDroid's and its counts are re-read whenever
 * this screen appears again or the app is resumed; nothing is remembered between visits, because
 * AnkiDroid is the only thing that schedules and Gaten has no progress of its own to show. No read
 * here raises a dialog: with AnkiDroid missing or ungranted the card says which of the two it is,
 * and the asking is left to the review screen.
 */
@Composable
fun HomeDashboard(
    onOpenSearch: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenSettings: (page: String) -> Unit
) {
    val context = LocalContext.current
    // Haptics come from the view that is on screen, so the tick and the tap that caused it belong to
    // the same window.
    val feedback = rememberFeedback()
    // Read again when the Activity resumes, independently of destination lifecycle changes.
    val lifecycleOwner = LocalActivity.current as? LifecycleOwner ?: LocalLifecycleOwner.current
    // The platform's enabled-services setting, read through the same helper the lookup settings page
    // uses for its own status row.
    var screenAccess by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var floatingButton by remember {
        mutableStateOf(TriggerPreferences.floatingButtonEnabled(context))
    }
    var deckStatus by remember { mutableStateOf<DeckStatus>(DeckStatus.NotRead) }
    // A fresh read on every arrival and every resume: the counts move inside AnkiDroid and screen
    // access is granted outside Gaten, so neither can be trusted from the last time it was looked at.
    var reads by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                screenAccess = isAccessibilityServiceEnabled(context)
                floatingButton = TriggerPreferences.floatingButtonEnabled(context)
                reads++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reads) {
        deckStatus = readDeckStatus(context)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The bar below owns the bottom inset, so only the top and the sides are taken here:
            // the status bar and any cutout stay clear of the cards, and nothing is padded twice.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = MaxContentWidth)
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl)
        ) {
            Spacer(modifier = Modifier.height(Spacing.xxl))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.dash_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Spacing.xl))

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                AdaptivePair(
                    sideBySide = maxWidth >= TwoColumnWidth,
                    first = { modifier ->
                        SearchHeroCard(onOpen = onOpenSearch, modifier = modifier)
                    },
                    second = { modifier ->
                        Column(modifier = modifier) {
                            ReviewCard(
                                status = deckStatus,
                                onOpenReview = onOpenReview,
                                onOpenCardSettings = { onOpenSettings(SettingsRoutes.ANKI) },
                                onRefresh = { reads++ }
                            )
                            Spacer(modifier = Modifier.height(Spacing.m))
                            LookupCard(
                                screenAccess = screenAccess,
                                floatingButton = floatingButton,
                                onToggleFloating = { enabled ->
                                    // A change the reader made is the one thing on this card that
                                    // ticks; tapping a card only turns a page.
                                    feedback.haptic(FeedbackHaptic.Tick)
                                    floatingButton = enabled
                                    TriggerPreferences.setFloatingButtonEnabled(context, enabled)
                                    // Explicit package: a manifest receiver only receives implicit
                                    // broadcasts that name it (or its package) on API 26+.
                                    context.sendBroadcast(
                                        Intent(TriggerPreferences.ACTION_FLOATING_BUTTON_CHANGED)
                                            .setPackage(context.packageName)
                                    )
                                },
                                onSetup = { onOpenSettings(SettingsRoutes.LOOKUP) }
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xxl))
        }
    }
}

/**
 * One of Home's cards: the neutral surface they all sit on, whatever they hold.
 *
 * The 1 dp shadow is the whole depth hierarchy. No card draws a border, and a card that has to stand
 * out does it with its container's colour rather than with a heavier shadow, so the accent still
 * means something where it is used.
 */
@Composable
private fun HomeCard(
    container: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(Spacing.l), content = content)
    }
}

/**
 * [first] beside [second] once the width is there for two, and above it otherwise.
 *
 * The two are stacked rather than given equal heights when they sit side by side: each measures
 * itself, so a long status line in one cannot stretch the other.
 */
@Composable
private fun AdaptivePair(
    sideBySide: Boolean,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit
) {
    if (sideBySide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m)
        ) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            first(Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(Spacing.m))
            second(Modifier.fillMaxWidth())
        }
    }
}

/**
 * The way into Search: a card shaped like the search field it opens.
 *
 * Nothing is typed here. Search is one screen shared by the tab and by this card — the query, the
 * results and the panel's own state all live there — so the whole card is a single button, and what
 * it shows is what a field shows: an icon, the hint, and a chevron. The one row measures a field's
 * own 56 dp once the card's padding is added, because a paragraph of explanation above the hint is a
 * paragraph to read before every search, and the hint already says what to type.
 */
@Composable
private fun SearchHeroCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    // The click stays Gaten's own, on the card itself: the library's clickable cards cannot carry an
    // action label, and the label is how a reader who cannot see the card is told what it opens. The
    // click's own semantics already read the whole card as one thing.
    //
    // The press scale shares the click's interactions, so the card shrinks for exactly as long as
    // the press is the card's own.
    val interactions = remember { MutableInteractionSource() }
    HomeCard(
        container = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .pressScale(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                onClickLabel = stringResource(R.string.dash_search_hero_action),
                role = Role.Button,
                onClick = onOpen
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.m))
            Text(
                text = stringResource(R.string.dash_search_hero_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The deck Gaten reviews: what AnkiDroid reports for it right now, and the ways on from here.
 *
 * The card takes the accent's container only while something is due, so a glance answers "is there
 * work" before any number is read. Every count is AnkiDroid's own for that one deck, and the deck's
 * name is the title once it is known — never a stand-in like a deck that is not the one configured.
 */
@Composable
private fun ReviewCard(
    status: DeckStatus,
    onOpenReview: () -> Unit,
    onOpenCardSettings: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val due = (status as? DeckStatus.Counts)?.due ?: 0
    val active = due > 0
    val container = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val heading = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val body = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    HomeCard(container = container, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Style,
                contentDescription = null,
                tint = heading,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.s))
            Text(
                text = when (status) {
                    is DeckStatus.Counts -> status.deckName
                    else -> stringResource(R.string.dash_review_title)
                },
                style = MaterialTheme.typography.titleMedium,
                color = heading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.dash_review_refresh),
                    tint = heading
                )
            }
        }

        if (status is DeckStatus.Counts) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.l)
            ) {
                CountTile(
                    count = status.newCount,
                    label = stringResource(R.string.dash_review_new),
                    numberColor = heading,
                    labelColor = body,
                    modifier = Modifier.weight(1f)
                )
                CountTile(
                    count = status.learnCount,
                    label = stringResource(R.string.dash_review_learning),
                    numberColor = heading,
                    labelColor = body,
                    modifier = Modifier.weight(1f)
                )
                CountTile(
                    count = status.reviewCount,
                    label = stringResource(R.string.dash_review_review),
                    numberColor = heading,
                    labelColor = body,
                    modifier = Modifier.weight(1f)
                )
            }
            if (due == 0) {
                Spacer(modifier = Modifier.height(Spacing.s))
                Text(
                    text = stringResource(R.string.dash_review_nothing_due),
                    style = MaterialTheme.typography.bodyMedium,
                    color = body
                )
            }
        } else {
            reviewStatusText(status)?.let { sentence ->
                Spacer(modifier = Modifier.height(Spacing.s))
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodyMedium,
                    color = body
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.m))

        when (status) {
            // Nothing to offer yet: the first read is still in flight.
            DeckStatus.NotRead -> Unit

            DeckStatus.AnkiDroidMissing -> CardActions(
                primaryLabel = stringResource(R.string.dash_action_card_settings),
                onPrimary = onOpenCardSettings
            )

            // Review is where AnkiDroid is asked for its permission, so this is the one place a tap
            // can lead to that dialog, and the card itself never raises one.
            DeckStatus.PermissionNeeded -> CardActions(
                primaryLabel = stringResource(R.string.dash_action_review),
                onPrimary = onOpenReview
            )

            // The one state with a second way on: a deck with counts is both something to review and
            // something to configure. Every state without counts is fixed by doing one thing — the
            // sentence above it says which — so it offers exactly that one action.
            is DeckStatus.Counts -> CardActions(
                primaryLabel = stringResource(R.string.dash_action_review),
                onPrimary = onOpenReview,
                secondaryLabel = stringResource(R.string.dash_action_card_settings),
                onSecondary = onOpenCardSettings
            )

            // A deck that is not there is not a finished deck: the export deck is picked again.
            is DeckStatus.DeckMissing -> CardActions(
                primaryLabel = stringResource(R.string.dash_action_choose_deck),
                onPrimary = onOpenCardSettings
            )

            is DeckStatus.Unreadable -> CardActions(
                primaryLabel = stringResource(R.string.dash_action_try_again),
                onPrimary = onRefresh
            )
        }
    }
}

/** One category of AnkiDroid's own counts: the number, then what it counts. */
@Composable
private fun CountTile(
    count: Int,
    label: String,
    numberColor: Color,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = numberColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )
    }
}

/**
 * The card's way on: its primary action, and — where the state offers two — the one that is a
 * different thing beside it.
 */
@Composable
private fun CardActions(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        Button(onClick = onPrimary) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            TextButton(onClick = onSecondary) {
                Text(secondaryLabel)
            }
        }
    }
}

/**
 * One honest sentence for every state that has no counts; the count states draw their numbers, so
 * there is nothing to say for them.
 */
@Composable
private fun reviewStatusText(status: DeckStatus): String? = when (status) {
    DeckStatus.NotRead -> stringResource(R.string.dash_review_checking)

    DeckStatus.AnkiDroidMissing -> stringResource(R.string.dash_review_not_installed)

    DeckStatus.PermissionNeeded -> stringResource(R.string.dash_review_permission)

    is DeckStatus.DeckMissing ->
        stringResource(R.string.dash_review_deck_missing, status.configuredName)

    is DeckStatus.Unreadable ->
        stringResource(R.string.dash_review_unreadable, stringResource(status.failure.labelRes()))

    is DeckStatus.Counts -> null
}

/** What went wrong, in the reader's words. */
@StringRes
private fun AnkiReviewFailure.labelRes(): Int = when (this) {
    AnkiReviewFailure.NOT_INSTALLED -> R.string.dash_anki_failure_not_installed
    AnkiReviewFailure.PERMISSION -> R.string.dash_anki_failure_permission
    AnkiReviewFailure.NOT_SET_UP -> R.string.dash_anki_failure_not_set_up
    AnkiReviewFailure.NOT_FOUND -> R.string.dash_anki_failure_not_found
    AnkiReviewFailure.PROVIDER -> R.string.dash_anki_failure_provider
    AnkiReviewFailure.MALFORMED -> R.string.dash_anki_failure_malformed
}

/**
 * Screen lookup: whether screen access is on, whether the floating bubble is wanted, and the way
 * into the rest of the setup.
 *
 * The status is the platform's own enabled-services setting, which is the honest answer to "can
 * Gaten read the screen" — and looking at it, or turning the bubble on, needs no permission at all.
 * The switch only records the choice: the accessibility service owns the window, hears the
 * broadcast, and is what makes a bubble appear, which is why the card says so while access is off.
 */
@Composable
private fun LookupCard(
    screenAccess: Boolean,
    floatingButton: Boolean,
    onToggleFloating: (Boolean) -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    // The platform switch draws no label of its own, so the row's title is what it is announced as.
    val floatingLabel = stringResource(R.string.dash_lookup_floating)
    HomeCard(
        container = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        // One row for the status: the dot, then what it is the status of and whether it is on.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (screenAccess) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
            )
            Spacer(modifier = Modifier.width(Spacing.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.dash_lookup_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(
                        if (screenAccess) R.string.dash_lookup_on else R.string.dash_lookup_off
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.m))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = floatingLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(
                        if (screenAccess) {
                            R.string.dash_lookup_floating_support
                        } else {
                            R.string.dash_lookup_floating_needs_access
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(Spacing.m))
            Switch(
                checked = floatingButton,
                onCheckedChange = onToggleFloating,
                modifier = Modifier.semantics { contentDescription = floatingLabel },
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }

        TextButton(onClick = onSetup) {
            Text(stringResource(R.string.dash_lookup_setup))
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}
