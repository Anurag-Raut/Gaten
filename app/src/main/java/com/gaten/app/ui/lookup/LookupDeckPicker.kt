/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.anki.AnkiDecks
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AnkiReview
import com.gaten.app.anki.AnkiReviewFailure
import com.gaten.app.anki.AnkiSettings
import com.gaten.app.ui.components.pressOpacity
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.SheetShape
import com.gaten.app.ui.theme.Spacing

/** The panel's scrim: the overlay's own veil value, so the sheet recedes without a second colour. */
private const val PickerScrimAlpha = 0.40f

/** How much of the overlay the panel may take before its deck list scrolls instead. */
private const val PickerMaxHeightFraction = 0.6f

/** The panel's own measure, and the floor under every deck row and the one control in the header. */
private val PickerPanelMaxWidth = 360.dp
private val PickerTouchTarget = 48.dp
private val PickerGlyphSize = 18.dp
private val PickerSpinnerSize = 16.dp
private val PickerShape = RoundedCornerShape(SheetShape)

/** The deck list as the panel sees it: still reading, read, or unreadable with a way back. */
private sealed interface DeckListState {
    data object Loading : DeckListState
    data class Ready(val decks: List<String>) : DeckListState
    data class Failed(@param:StringRes val message: Int) : DeckListState
}

/**
 * Persists [deckName] as the export destination and returns the settings to export with.
 *
 * The settings are read fresh and only the deck name is replaced, so a note type, a field mapping,
 * tags or templates edited in the customise screen are written back exactly as they were read.
 * Nothing is written to AnkiDroid: choosing a deck is a preference, not an export, and the note
 * itself is only ever written by an explicit tap on the footer's action.
 */
internal fun persistDeckSelection(context: Context, deckName: String): AnkiSettings {
    val updated = AnkiPreferences.load(context).copy(deckName = deckName)
    AnkiPreferences.save(context, updated)
    return updated
}

/**
 * The deck chooser, as a layer of the lookup overlay itself.
 *
 * A dialog or a popup is a second window, and this overlay watches its own focus: Gaten learns that
 * another app came forward from the lookup's window losing focus, so a real window here would dismiss
 * the very lookup it is choosing a deck for. This draws inside the sheet's own composition instead —
 * a veil over the whole overlay, the panel over the middle of it — and takes every touch and the Back
 * gesture while it is up, so nothing beneath it can be dragged, tapped or dismissed by accident.
 *
 * AnkiDroid's decks are read through the existing read-only review API on IO, once per opening and
 * once per retry; a filtered deck is left out of the list because Anki will not accept an added
 * note in one. Nothing is written until a deck is actually chosen, and a chosen
 * deck is persisted before the panel closes, so a cancel can never change the destination.
 */
@Composable
internal fun DeckPicker(
    visible: Boolean,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Back closes the picker first: the sheet's own handler and the overlay's stay behind this one,
    // which is registered last and therefore answers first.
    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SHORT, easing = Motion.emphasized)),
        exit = fadeOut(tween(Motion.SHORT, easing = Motion.emphasized)),
        modifier = modifier
    ) {
        val context = LocalContext.current
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelCap = maxHeight * PickerMaxHeightFraction

            // The veil, and the way out for a reader who taps outside the panel. It is a modifier
            // node over the sheet, so the drag that dismisses the overlay cannot reach it either.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = PickerScrimAlpha))
                    .pointerInput(Unit) { detectTapGestures { onDismiss() } }
            )

            var state by remember { mutableStateOf<DeckListState>(DeckListState.Loading) }
            var attempt by remember { mutableStateOf(0) }
            // Keyed on the attempt, not on a timer: every opening and every retry reads the decks
            // again, and a listing that is a few seconds old is never worth a stale destination. The
            // read leaves for IO inside the exporter.
            LaunchedEffect(attempt) {
                state = DeckListState.Loading
                state = when (val answer = AnkiReview.decks(context)) {
                    // Filtered decks are left out: AnkiDroid offers them for review, but a
                    // filtered deck is a temporary query over cards that live elsewhere and Anki
                    // will not accept an added note in one, so offering one would promise a
                    // destination the export can only fail to reach.
                    is AnkiDecks.Available -> DeckListState.Ready(
                        answer.decks.filterNot { it.filtered }.map { it.name }
                    )
                    is AnkiDecks.Failed -> DeckListState.Failed(
                        when (answer.failure) {
                            AnkiReviewFailure.PERMISSION -> R.string.anki_error_permission
                            AnkiReviewFailure.NOT_INSTALLED -> R.string.anki_not_installed
                            else -> R.string.anki_settings_load_failed
                        }
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = PickerShape,
                    modifier = Modifier
                        .padding(Spacing.l)
                        // The panel swallows its own taps; only the veil outside it closes the
                        // picker, so a tap meant for a deck never cancels the choice.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .widthIn(max = PickerPanelMaxWidth)
                        .heightIn(max = panelCap)
                ) {
                    DeckPanel(
                        selected = selected,
                        state = state,
                        onPick = onPick,
                        onRetry = { attempt++ },
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

/**
 * The panel: the deck in use, the one control that leaves, then the list itself — reading, failed
 * with its retry, empty, or the decks AnkiDroid reports.
 */
@Composable
private fun DeckPanel(
    selected: String,
    state: DeckListState,
    onPick: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val type = sheetType()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.l, top = Spacing.m, end = Spacing.s, bottom = Spacing.m)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.anki_deck),
                    style = type.body,
                    color = colors.onSurface
                )
                // The deck exports are going to right now, whether or not AnkiDroid knows it yet:
                // a name the customise screen typed and no export has created is still the name the
                // next export will use, and the picker must never pretend otherwise.
                Text(
                    text = selected,
                    style = type.small,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(Spacing.s))
            IconButton(onClick = onDismiss, modifier = Modifier.size(PickerTouchTarget)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.anki_deck_cancel),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(PickerGlyphSize)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.outlineVariant)
        )
        when (state) {
            DeckListState.Loading -> PickerNotice {
                CircularProgressIndicator(
                    modifier = Modifier.size(PickerSpinnerSize),
                    strokeWidth = 2.dp,
                    color = colors.primary
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = stringResource(R.string.anki_settings_loading),
                    style = type.body,
                    color = colors.onSurfaceVariant
                )
            }

            is DeckListState.Failed -> PickerNotice {
                Text(
                    text = stringResource(state.message),
                    style = type.body,
                    color = colors.error,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.s))
                PickerTextAction(label = R.string.anki_deck_retry, onClick = onRetry, type = type)
            }

            is DeckListState.Ready -> if (state.decks.isEmpty()) {
                PickerNotice {
                    Text(
                        text = stringResource(R.string.anki_deck_empty),
                        style = type.body,
                        color = colors.onSurfaceVariant
                    )
                }
            } else {
                DeckList(
                    decks = state.decks,
                    selected = selected,
                    onPick = onPick,
                    type = type
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
    }
}

/**
 * One row of the panel's states: a fixed 48 dp band, so a reading spinner, a failure and an empty
 * collection all hold the panel's height steady instead of resizing it under the reader's finger.
 */
@Composable
private fun PickerNotice(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PickerTouchTarget)
            .padding(horizontal = Spacing.l),
        content = content
    )
}

/** The decks, in AnkiDroid's own order; the list takes what height is left and scrolls past that. */
@Composable
private fun ColumnScope.DeckList(
    decks: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    type: SheetType
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // A floor, not a fill: a short list leaves the panel short, a long one stops at the
            // panel's cap and scrolls, and neither ever pushes the header out of the window.
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState())
    ) {
        decks.forEach { deck ->
            DeckOption(
                deck = deck,
                // AnkiDroid's deck names are unique ignoring case, so the deck in use is the row
                // whose name matches ignoring case — a name retyped with different capitals in the
                // customise screen is still the same deck.
                isSelected = deck.equals(selected, ignoreCase = true),
                onClick = { onPick(deck) },
                type = type
            )
        }
    }
}

/**
 * One deck: a 48 dp row that is the whole target, the row's name and its check taking the accent when
 * it is the deck in use.
 *
 * The deck in use is marked by the check and the accent ink rather than by a fill, because on this
 * surface a fill means "action" — the export button's own treatment — not "this one is chosen".
 */
@Composable
private fun DeckOption(
    deck: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    type: SheetType
) {
    val colors = MaterialTheme.colorScheme
    val interactions = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PickerTouchTarget)
            .pressOpacity(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = Spacing.l)
            .semantics(mergeDescendants = true) { selected = isSelected }
    ) {
        Text(
            text = deck,
            style = type.body,
            color = if (isSelected) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Spacer(Modifier.width(Spacing.s))
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(PickerGlyphSize)
            )
        }
    }
}

/** The panel's one text action, at the same 48 dp height as a row: the retry after a failed read. */
@Composable
private fun PickerTextAction(@StringRes label: Int, onClick: () -> Unit, type: SheetType) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = PickerTouchTarget)
            .pressOpacity(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Text(
            text = stringResource(label),
            style = type.label,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
