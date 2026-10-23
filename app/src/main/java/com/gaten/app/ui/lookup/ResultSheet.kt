/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.gaten.app.R
import com.gaten.app.anki.AddResult
import com.gaten.app.anki.AnkiCard
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AnkiSettings
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.alternativeHeadwordFor
import com.gaten.app.dict.headwordFor
import com.gaten.app.dict.notedForm
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.FeedbackSound
import com.gaten.app.ui.components.KanjiStrip
import com.gaten.app.ui.components.pressOpacity
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.SheetShape
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.ui.theme.pageSlide
import com.gaten.app.ui.wanikani.WaniKaniDetailPanel
import com.gaten.app.wanikani.WaniKaniTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Dragging the handle toward the sheet's docked edge past this distance dismisses the overlay. */
private const val DISMISS_DRAG_DP = 80f

/** Bottom dock: the top corners round, as the portrait sheet has always had it. */
private val BottomSheetShape = RoundedCornerShape(topStart = SheetShape, topEnd = SheetShape)

/** Left dock: the free screen is on the right, so the end corners round. */
private val LeftSheetShape = RoundedCornerShape(topEnd = SheetShape, bottomEnd = SheetShape)

/** Right dock: the free screen is on the left, so the start corners round. */
private val RightSheetShape = RoundedCornerShape(topStart = SheetShape, bottomStart = SheetShape)

/**
 * The width a panel has to reach before the sheet spends the house content inset on its margin: a
 * narrow window (or a narrow side dock) would rather have the 6 dp back for its words.
 */
private val NarrowPanelWidth = 360.dp

/**
 * The measure per scaled dp the pinned row has to reach before the source line keeps its place on
 * it: the line moves into the body rather than the controls or the words being cut down.
 */
private val ReflowWidth = 300.dp

/** The pinned band's four 48 dp targets — Copy, Search, Select again, Close — in real dp. */
private val FourControlWidth = 192.dp

/** Three of them: Search, Select again and Close. Copy is the first to leave the band. */
private val ThreeControlWidth = 144.dp

/** Two: Search and Close. Below this even Search moves into the body. */
private val TwoControlWidth = 96.dp

/** What the source line needs left over beside the four targets before it keeps its row. */
private val SourceRowRemainder = 96.dp

/** The house content column, insets included, centred when the window is wider. */
private val ContentColumnMaxWidth = 560.dp

/** The pinned header's height, and the floor a body needs before the footer gives up its pin. */
private val HeaderMinHeight = 48.dp

/** The pinned footer's floor: rule, 12 dp, deck row 48 dp, 8 dp, the 52 dp action, 24 dp. */
private val FooterMinHeight = 144.dp
private val ReadableBodyHeight = 48.dp

/** The deck row's own floor — the row is a control now, so it carries a full 48 dp target. */
private val DeckRowMinHeight = 48.dp
private const val DeckStackFontScale = 1.2f

/**
 * The air under the footer's action, inside the panel and above the navigation-bar inset: the action
 * is the last thing the sheet draws, and the 8 dp it used to keep read as pinned to the strip below
 * it. It is also part of [FooterMinHeight], so a short window gives up the pinned footer before it
 * gives up this gap and clips the action.
 */
private val FooterBottomGap = Spacing.xxl

/** Every target in the sheet keeps this, whatever the glyph inside it measures. */
private val MinTarget = 48.dp

/** The neutral outline glyphs, and the one filled action's own sizes. */
private val GlyphSize = 20.dp
private val ActionMinHeight = 52.dp
private val ActionGlyphSize = 18.dp
private val SpinnerSize = 16.dp
private val ActionShape = RoundedCornerShape(13.dp)

/** A word tab's reserved check slot, its rule, and how much of the strip one arrow pages. */
private val TabCheckSlot = 16.dp
private val TabRuleHeight = 2.dp
private const val PAGE_FRACTION = 0.85f

/** How far a disabled or finished control fades into the paper under it. */
private const val DisabledFillAlpha = 0.12f
private const val DisabledContentAlpha = 0.38f

/** The grip: a 48 dp band around a painted handle the reader aims at. */
private val HandleBand = 48.dp
private val HandleLong = 32.dp
private val HandleThickness = 4.dp
private val HandleRadius = 2.dp
private const val HandleAlpha = 0.60f

/** The gloss index gutter never closes below this, however short the numbers are. */
private val GlossIndexGutter = 24.dp

/**
 * The sheet's own type roles: the theme's, unchanged, in both docks.
 *
 * One scale is deliberate. The side dock used to carry a tighter copy of the whole panel — a 26 sp
 * headword, 16 sp glosses — so the same word was a different size depending on how the phone was
 * held. A dock now changes geometry and nothing else: 45% of a landscape screen is still a
 * comfortable measure for the house scale, and the responsive header plus the one body scroller are
 * what make room when the reader's own text size is large.
 */
internal data class SheetType(
    /** The recognized source line: `bodyMedium`. */
    val header: TextStyle,
    /** The word itself: `displaySmall`, the one step read as a glyph. */
    val headword: TextStyle,
    /** Readings and runner-up headwords: `titleMedium`. */
    val reading: TextStyle,
    /** Metadata, mnemonics and runner-up glosses: `bodyMedium`. */
    val body: TextStyle,
    /** The numbered glosses and WaniKani's prose: `bodyLarge`. */
    val gloss: TextStyle,
    /** Notes and the full part-of-speech values: `bodySmall`. */
    val small: TextStyle,
    /** Section captions: `labelMedium`. */
    val caption: TextStyle,
    /** The full-POS caption, a step quieter than the sections above it: `labelSmall`. */
    val tiny: TextStyle,
    /** Word tabs and the one filled action: `labelLarge`. */
    val label: TextStyle
)

/** The sheet's roles, taken from the theme's own scale. */
@Composable
internal fun sheetType(): SheetType {
    val type = MaterialTheme.typography
    return remember(type) {
        SheetType(
            header = type.bodyMedium,
            headword = type.displaySmall,
            reading = type.titleMedium,
            body = type.bodyMedium,
            gloss = type.bodyLarge,
            small = type.bodySmall,
            caption = type.labelMedium,
            tiny = type.labelSmall,
            label = type.labelLarge
        )
    }
}

/**
 * The result sheet drawn inside the overlay, on the edge [layout] picked.
 *
 * A side dock (landscape) fills the height and takes [SheetLayout.fraction] of the width, keeps its
 * content clear of the status bar, nav bar and camera cutout. The bottom dock (portrait) is full
 * width with a bottom-docked cap on its height and rounded top corners; it paints edge-to-edge to
 * the physical bottom, under the navigation strip, and insets only its content.
 *
 * Both docks are one flat surface: a grip band, a transparent header with its 48 dp control band, a
 * hairline, one body holding the recognized line, the word tabs and the entry (or the WaniKani page,
 * or a terminal notice), and the Anki action pinned below it. Nothing inside is a card, and the only
 * rules are the sheet's own boundary and the lines between sections.
 *
 * The header carries up to four controls — Copy, Search, Select again, Close — and gives up what the
 * measure cannot hold to labelled 48 dp actions at the top of the body; nothing is ever squeezed to
 * keep icon density. A result with nothing to read (no text, or no dictionary match) is a centred,
 * illustrated empty state with the two ways out, and a dictionary failure is the inline status rail.
 * A chosen search result is drawn by the same entry column, with its provenance above it and the OCR
 * word tabs hidden.
 *
 * Everything beside the sheet stays as scrim; tapping it dismisses the overlay, as does dragging the
 * grip — and the rest of the panel — toward the docked edge past [DISMISS_DRAG_DP]. While dragged,
 * the bottom sheet follows the finger and springs back if the drag was short. Word-tab selection
 * crossfades the entry.
 */
@Composable
fun ResultSheet(
    result: LookupResult,
    /**
     * The entry the reader typed their way to, or null while the captured result is showing. It
     * replaces the selected word's own entry, hides the OCR word tabs and carries its provenance.
     */
    searchedEntry: DictEntry?,
    /** The committed query that produced [searchedEntry]. */
    searchedQuery: String?,
    /**
     * Opens the dictionary search. The sheet never draws the search surface itself: the caller grows
     * this panel's own bounds into the window.
     */
    onSearch: () -> Unit,
    /** Puts the captured result, its word and its scroll back. The parent owns that state. */
    onOriginalLookup: () -> Unit,
    onDismiss: () -> Unit,
    onSelectAgain: () -> Unit,
    layout: SheetLayout,
    /**
     * The panel's size as it was actually measured in this overlay's own pixels, reported on every
     * layout. The search surface grows out of exactly this rectangle — the size this content asked
     * for, on the edge [layout] docked it to — so the reader's query starts on the panel they tapped
     * Search on, never on a container that was itself never drawn.
     */
    onPanelSize: (Size) -> Unit = {},
    /**
     * Applied only to the sheet Surface: the caller's enter/exit slide ([Modifier.animateEnterExit])
     * rides on the measured panel itself. The scrim beside it stays fixed.
     */
    modifier: Modifier = Modifier
) {
    val feedback = rememberFeedback()
    var selected by remember { mutableStateOf(0) }
    // The WaniKani pages open over the entry, and a radical opens over the kanji it came from: a
    // stack, so Back walks the pages down to the dictionary entry with no further lookup, and the
    // selected word never has to be read again. Back is handled here — after the overlay's own
    // handler, which closes the lookup — so it unwinds the pages first and only then the overlay.
    val wanikani = remember { mutableStateListOf<WaniKaniTarget>() }
    // Guarded: the back handler and the page's own back button can both arrive in one frame.
    fun closeWaniKaniPage() {
        if (wanikani.isNotEmpty()) wanikani.removeAt(wanikani.lastIndex)
    }
    BackHandler(enabled = wanikani.isNotEmpty()) { closeWaniKaniPage() }
    // Choosing a search result discards the reference pages the lookup had open: the chosen entry is
    // the new starting point, and Original lookup restores the original dictionary result rather
    // than a stack that belonged to the word it replaced. Cancelling search touches nothing, which
    // is what puts the reader back on the exact page they left.
    //
    // It also announces itself once. The reader picked a row on a cover that has just closed, so the
    // surface under their finger changed without anything they touched saying so; after this, the
    // ordinary reading order takes over and nothing else is spoken on their behalf.
    //
    // A one-shot announcement has no Compose-native equivalent: a live region speaks when the text
    // inside it *changes*, and here there is nothing to change — the entry simply replaces a page
    // that is gone. The View call is the platform's own answer to exactly this, and the replacement
    // API is still being settled, so it stays with the deprecation suppressed rather than replaced
    // by a live region that would announce nothing.
    val entryOpened = stringResource(R.string.lookup_entry_opened)
    val view = LocalView.current
    @Suppress("DEPRECATION")
    LaunchedEffect(searchedEntry) {
        if (searchedEntry != null) {
            wanikani.clear()
            view.announceForAccessibility(entryOpened)
        }
    }
    // The chosen entry is drawn instead of whatever page the stack held, in the frame it arrives:
    // the entry must not cross-fade with a reference page, and the page must not flash first.
    val wanikaniPage = if (searchedEntry != null) null else wanikani.lastOrNull()
    val dock = layout.dock
    val compact = dock != SheetDock.Bottom
    val type = sheetType()
    val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
    var dragDistance by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Snapped while the finger is down, so the sheet tracks it exactly; springing back on release.
    // Never read sheetOffset in composition: the drag spring would recompose the whole sheet per
    // frame. It is read deferred in the Surface's graphicsLayer, which is the only reader.
    val sheetOffset by animateFloatAsState(
        targetValue = dragDistance,
        animationSpec = if (dragging) snap() else spring(),
        label = "sheetDrag"
    )
    val rule = MaterialTheme.colorScheme.outlineVariant
    // The Anki destination the footer exports with, and the deck chooser that can change it. The
    // settings live here because the chooser is a layer of this sheet's own window, drawn over
    // everything else — never a second window, which would take the overlay's focus and dismiss the
    // lookup. Choosing a deck persists a preference and nothing else: the footer's own action stays
    // the only thing that writes a card.
    val context = LocalContext.current
    var ankiSettings by remember { mutableStateOf(AnkiPreferences.load(context)) }
    var deckPickerUp by remember { mutableStateOf(false) }
    // While the chooser is up it is the only thing that can be acted on: the sheet goes on drawing
    // behind it, but the screen reader's traversal stops at the chooser until it is closed.
    val sheetTraversal = if (deckPickerUp) Modifier.clearAndSetSemantics {} else Modifier

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim over the rest of the screen: a tap dismisses the overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        // Drag-to-dismiss toward the docked edge — down for the bottom, left or right for a side.
        // The scrollable body wins its own scroll gesture, as intended. The bottom drag clamps at
        // zero: the sheet follows the finger down and must never lift off its docked edge.
        val dragModifier = when (dock) {
            SheetDock.Bottom -> Modifier.pointerInput(dismissDragPx) {
                detectVerticalDragGestures(
                    onDragStart = { dragging = true },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDistance = (dragDistance + amount).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        dragging = false
                        val dragged = dragDistance
                        // Hold a completed dismiss drag in place while the exit continues it.
                        if (dragged > dismissDragPx) onDismiss() else dragDistance = 0f
                    },
                    onDragCancel = {
                        dragging = false
                        dragDistance = 0f
                    }
                )
            }

            SheetDock.Left, SheetDock.Right -> Modifier.pointerInput(dismissDragPx, dock) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragEnd = {
                        val dragged = dragDistance
                        dragDistance = 0f
                        // Toward the edge: leftwards (negative) for a left dock, rightwards for a right.
                        val towardEdge = if (dock == SheetDock.Left) -dragged else dragged
                        if (towardEdge > dismissDragPx) onDismiss()
                    },
                    onDragCancel = { dragDistance = 0f }
                )
            }
        }

        when (dock) {
            SheetDock.Bottom -> Surface(
                modifier = modifier
                    // Only the deck chooser is reachable while it is up; see [sheetTraversal].
                    .then(sheetTraversal)
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Before heightIn: the panel's height tracks its content — the cap still applies
                    // unchanged, and nothing animates the sheet's own height, which used to clip the
                    // entry at a large text scale while the tween was in flight.
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * layout.fraction)
                    .onGloballyPositioned { onPanelSize(it.size.toSize()) }
                    .sheetEdgeRule(BottomSheetShape, SheetEdge.Top, rule)
                    // Dragged: the sheet rides the finger, past the physical bottom and back.
                    .graphicsLayer { translationY = sheetOffset },
                shape = BottomSheetShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        // Inset inside the panel, not around it: the sheet colour paints under the
                        // navigation strip, which stays transparent over it.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    HandleBar(onDismiss = onDismiss)
                    CappedContentColumn(sideDock = false) {
                        SheetBody(
                            result = result,
                            searchedEntry = searchedEntry,
                            searchedQuery = searchedQuery,
                            selected = selected,
                            onSelect = {
                                if (selected != it) feedback.haptic(FeedbackHaptic.Tick)
                                selected = it
                                wanikani.clear()
                            },
                            onDismiss = onDismiss,
                            onSelectAgain = {
                                wanikani.clear()
                                onSelectAgain()
                            },
                            onSearch = onSearch,
                            onOriginalLookup = onOriginalLookup,
                            target = wanikaniPage,
                            onKanji = {
                                feedback.haptic(FeedbackHaptic.Tick)
                                wanikani.add(WaniKaniTarget.Kanji(it))
                            },
                            onRadical = {
                                feedback.haptic(FeedbackHaptic.Tick)
                                wanikani.add(WaniKaniTarget.Radical(it))
                            },
                            onWaniKaniBack = { closeWaniKaniPage() },
                            type = type,
                            compact = false,
                            settings = ankiSettings,
                            onChangeDeck = { deckPickerUp = true }
                        )
                    }
                }
            }

            SheetDock.Left, SheetDock.Right -> Surface(
                modifier = modifier
                    // Only the deck chooser is reachable while it is up; see [sheetTraversal].
                    .then(sheetTraversal)
                    .align(
                        if (dock == SheetDock.Left) Alignment.CenterStart else Alignment.CenterEnd
                    )
                    .fillMaxHeight()
                    .width(LocalConfiguration.current.screenWidthDp.dp * layout.fraction)
                    .onGloballyPositioned { onPanelSize(it.size.toSize()) }
                    .sheetEdgeRule(
                        shape = if (dock == SheetDock.Left) LeftSheetShape else RightSheetShape,
                        edge = if (dock == SheetDock.Left) SheetEdge.InnerRight else SheetEdge.InnerLeft,
                        color = rule
                    ),
                shape = if (dock == SheetDock.Left) LeftSheetShape else RightSheetShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(dragModifier)
                        // Edge-to-edge: the content stays clear of the status bar, nav bar and
                        // camera cutout - but only the screen edge this panel sits on. A cutout on
                        // the far side is nowhere near it and used to pad the handle off the panel.
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Vertical + if (dock == SheetDock.Left) {
                                    WindowInsetsSides.Left
                                } else {
                                    WindowInsetsSides.Right
                                }
                            )
                        )
                ) {
                    if (dock == SheetDock.Right) HandleGutter(onDismiss = onDismiss)
                    Column(modifier = Modifier.weight(1f)) {
                        CappedContentColumn(sideDock = true) {
                            SheetBody(
                                result = result,
                                searchedEntry = searchedEntry,
                                searchedQuery = searchedQuery,
                                selected = selected,
                                onSelect = {
                                    if (selected != it) feedback.haptic(FeedbackHaptic.Tick)
                                    selected = it
                                    wanikani.clear()
                                },
                                onDismiss = onDismiss,
                                onSelectAgain = {
                                    wanikani.clear()
                                    onSelectAgain()
                                },
                                onSearch = onSearch,
                                onOriginalLookup = onOriginalLookup,
                                target = wanikaniPage,
                                onKanji = {
                                    feedback.haptic(FeedbackHaptic.Tick)
                                    wanikani.add(WaniKaniTarget.Kanji(it))
                                },
                                onRadical = {
                                    feedback.haptic(FeedbackHaptic.Tick)
                                    wanikani.add(WaniKaniTarget.Radical(it))
                                },
                                onWaniKaniBack = { closeWaniKaniPage() },
                                type = type,
                                compact = true,
                                settings = ankiSettings,
                                onChangeDeck = { deckPickerUp = true }
                            )
                        }
                    }
                    if (dock == SheetDock.Left) HandleGutter(onDismiss = onDismiss)
                }
            }
        }

        // The deck chooser, drawn after the sheet so it is above every part of it — the scrim that
        // dismisses the overlay and the grip that drags it included. It is a layer of this window,
        // so opening it neither dismisses the lookup nor outlives it.
        DeckPicker(
            visible = deckPickerUp,
            selected = ankiSettings.deckName,
            onPick = { deck ->
                ankiSettings = persistDeckSelection(context, deck)
                deckPickerUp = false
            },
            onDismiss = { deckPickerUp = false }
        )
    }
}

/**
 * The content measure: capped at [ContentColumnMaxWidth] and centred, with the dock's own horizontal
 * inset — the house 22 dp on the bottom dock (16 dp under 360 dp of panel), and 16 dp on a side
 * dock, whose panel is a strip down the edge rather than the width of the screen.
 */
@Composable
private fun CappedContentColumn(sideDock: Boolean, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        val inset = if (!sideDock && maxWidth >= NarrowPanelWidth) Spacing.content else Spacing.l
        Column(
            modifier = Modifier
                .widthIn(max = ContentColumnMaxWidth)
                .padding(horizontal = inset),
            content = content
        )
    }
}

/**
 * The grip band at the inner edge of the bottom dock: 48 dp of the sheet's own paper that exists to
 * be dragged, with the painted handle centred in it.
 *
 * The handle keeps one width. It used to stretch with the drag, which made its size a function of how
 * far the sheet had been pulled — a second, redundant readout of the sheet's own travel, and a
 * moving target under the finger that is aiming at it.
 *
 * The band is also the dismissal a reader who cannot drag needs: [onDismiss] is a named accessibility
 * action on it, and the Close button in the header stays where it is.
 */
@Composable
private fun HandleBar(onDismiss: () -> Unit) {
    val dismiss = stringResource(R.string.lookup_cd_dismiss)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HandleBand)
            .semantics {
                customActions = listOf(CustomAccessibilityAction(dismiss) { onDismiss(); true })
            },
        contentAlignment = Alignment.Center
    ) {
        HandlePill(width = HandleLong, height = HandleThickness)
    }
}

/** The grip at the inner edge of a side dock: the same pill standing up, against the free screen. */
@Composable
private fun HandleGutter(onDismiss: () -> Unit) {
    val dismiss = stringResource(R.string.lookup_cd_dismiss)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(HandleBand)
            .semantics {
                customActions = listOf(CustomAccessibilityAction(dismiss) { onDismiss(); true })
            },
        contentAlignment = Alignment.Center
    ) {
        HandlePill(width = HandleThickness, height = HandleLong)
    }
}

/**
 * The painted handle itself: quiet, hairline-thin and never a control of its own. It marks where the
 * drag lives; the band around it is what is dragged and what a screen reader can dismiss from.
 */
@Composable
private fun HandlePill(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HandleAlpha),
                RoundedCornerShape(HandleRadius)
            )
    )
}

/** Which edge of the sheet the frozen screen can actually see. */
private enum class SheetEdge { Top, InnerLeft, InnerRight }

/**
 * The one rule the sheet draws for itself: a hairline along the exposed boundary, following the
 * dock's own corners.
 *
 * Only the exposed edge gets one. The edges that touch the display are the window's own limit, not a
 * separation between the sheet and the screen it is read over, and a border there would just outline
 * the window — so the stroke is clipped to the edge the reader can see the sheet end on.
 */
private fun Modifier.sheetEdgeRule(shape: Shape, edge: SheetEdge, color: Color): Modifier =
    drawWithCache {
        val stroke = 1.dp.toPx()
        // Half a stroke in from the panel's own edge, so the whole width of the line lands inside the
        // sheet instead of half of it falling outside the window.
        val inset = stroke / 2f
        // Geometry, not paint: built once per size rather than once per frame, so the 300 ms slide
        // the sheet enters on is not also 18 rebuilds of its own outline.
        val outline = shape.createOutline(
            Size(size.width - stroke, size.height - stroke),
            layoutDirection,
            this
        )
        val exposed = Rect(
            left = if (edge == SheetEdge.InnerRight) size.width - stroke else 0f,
            top = 0f,
            right = if (edge == SheetEdge.InnerLeft) stroke else size.width,
            bottom = if (edge == SheetEdge.Top) stroke else size.height
        )
        onDrawWithContent {
            drawContent()
            clipRect(exposed.left, exposed.top, exposed.right, exposed.bottom) {
                translate(inset, inset) {
                    drawOutline(outline, color = color, style = Stroke(stroke))
                }
            }
        }
    }

/**
 * Everything inside the panel past the grip: the header row, then the one body area and the pinned
 * action under it.
 *
 * One implementation for every dock, because a dock changes only the panel around it. [compact]
 * survives for the one thing that is genuinely smaller in a side dock — a WaniKani radical's
 * artwork box — and for nothing else.
 */
@Composable
private fun ColumnScope.SheetBody(
    result: LookupResult,
    /** The entry the reader typed their way to, or null while the captured result is showing. */
    searchedEntry: DictEntry?,
    /** The committed query that produced [searchedEntry]. */
    searchedQuery: String?,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSelectAgain: () -> Unit,
    onSearch: () -> Unit,
    onOriginalLookup: () -> Unit,
    /** The WaniKani page open over the entry, or null while the entry itself is showing. */
    target: WaniKaniTarget?,
    onKanji: (String) -> Unit,
    onRadical: (Int) -> Unit,
    onWaniKaniBack: () -> Unit,
    type: SheetType,
    compact: Boolean,
    /** The Anki settings the footer exports with; the deck chooser replaces the deck name in them. */
    settings: AnkiSettings,
    /** Opens the deck chooser, which the sheet draws over itself. */
    onChangeDeck: () -> Unit
) {
    val context = LocalContext.current
    val feedback = rememberFeedback()
    val bodyScroll = rememberScrollState()
    // A chosen search result arrives at the top of the body, where its provenance is, and Original
    // lookup puts the reader back on the offset the captured result was left at: the two states share
    // one scroller, so the offset is saved the first time a searched entry replaces the result —
    // searching again from a searched entry must not overwrite it. Both moves are immediate: a chosen
    // entry commits beneath the opaque cover, with no travel and nothing animated.
    val capturedScroll = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(searchedEntry) {
        val saved = capturedScroll.value
        if (searchedEntry != null) {
            if (saved == null) capturedScroll.value = bodyScroll.value
            bodyScroll.scrollTo(0)
        } else if (saved != null) {
            bodyScroll.scrollTo(saved)
            capturedScroll.value = null
        }
    }
    val copy: () -> Unit = {
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("text", result.ocrText))
        feedback.haptic(FeedbackHaptic.Tick)
        Toast.makeText(context, R.string.lookup_toast_copied, Toast.LENGTH_SHORT).show()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val fontScale = LocalDensity.current.fontScale
        // What this measure can hold: the four 48 dp targets are measured in real dp, the source
        // line in scaled dp, because only the line is text. What does not fit leaves the row in the
        // order below rather than a target being shrunk or the words cut to an ellipsis.
        val fit = headerFit(maxWidth, fontScale)
        // Physically insufficient height: the sheet gives up its pinned action before it gives up
        // readable text, and the action rides at the true end of the one scroller instead.
        val unpinned = maxHeight < HeaderMinHeight + FooterMinHeight + ReadableBodyHeight
        // A result with nothing to read: the centred empty state replaces the terminal rail it used
        // to be, and it comes with no word tabs, no export action and no invented OCR line.
        val empty = searchedEntry == null && result.error == null &&
            (result.ocrText.isBlank() || result.words.isEmpty())
        // The action under the body is keyed to the entry on screen: the chosen dictionary entry, or
        // the selected word's own first match. Absent for a terminal result and for both empty
        // states, which have nothing to add to Anki.
        val footerCard = if (target == null && result.error == null && !empty) {
            searchedEntry?.let { AnkiCard.from(it) }
                ?: result.words.getOrNull(selected)?.let { AnkiCard.from(it) }
        } else {
            null
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            SheetHeader(
                text = result.ocrText,
                fit = fit,
                onCopy = if (result.ocrText.isBlank()) null else copy,
                onSearch = onSearch,
                onSelectAgain = onSelectAgain,
                onDismiss = onDismiss,
                type = type
            )
            SectionRule()
            if (empty) {
                // The empty state centres, so it needs a bounded parent rather than the body
                // scroller: the reflowed source line, when there is one, takes its measured block
                // first and the illustrated group centres in the height that is left, scrolling
                // itself if the group still does not fit. The sheet keeps its full dock height here
                // — an empty shell is not a notice-height sheet.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = true)
                ) {
                    BodyFallbacks(
                        text = result.ocrText,
                        fit = fit,
                        copy = copy,
                        onSearch = onSearch,
                        onSelectAgain = onSelectAgain,
                        emptyState = true,
                        failure = false,
                        type = type
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        LookupEmptyState(
                            // The two facts are told apart by the mark: text that was never read,
                            // and text that was read and matched nothing.
                            artwork = if (result.ocrText.isBlank()) {
                                R.drawable.ic_lookup_no_text
                            } else {
                                R.drawable.ic_lookup_no_matches
                            },
                            message = stringResource(
                                if (result.ocrText.isBlank()) {
                                    R.string.lookup_no_text
                                } else {
                                    R.string.lookup_no_words
                                }
                            ),
                            onSearch = onSearch,
                            onSelectAgain = onSelectAgain
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // A floor, not a fixed height: the body is as tall as its content up to what
                        // the sheet has left, and scrolls past that. A side dock fills its panel
                        // instead, so the pinned action closes the panel rather than floating
                        // mid-height in it.
                        .weight(1f, fill = compact)
                        .verticalScroll(bodyScroll)
                ) {
                    BodyFallbacks(
                        text = result.ocrText,
                        fit = fit,
                        copy = copy,
                        onSearch = onSearch,
                        onSelectAgain = onSelectAgain,
                        emptyState = false,
                        failure = result.error != null,
                        type = type
                    )
                    when {
                        // A dictionary failure keeps the header and the recognized text, and says
                        // what happened in the same rail the overlay uses over the frozen screen.
                        // It is never illustrated as an empty state and never invents a retry: the
                        // rail's own action is the way back.
                        result.error != null -> InlineStatusRail(
                            text = stringResource(R.string.lookup_dict_error),
                            glyph = RailGlyph.Error,
                            tone = RailTone.Error,
                            supporting = result.error
                                ?.takeIf { it.isNotBlank() }
                                ?.let { stringResource(R.string.lookup_dict_error_cause, it) },
                            action = RailAction(
                                stringResource(R.string.lookup_select_again),
                                onSelectAgain
                            )
                        )

                        else -> {
                            // The chosen entry says where it came from: the committed rectangle and
                            // the source line above cannot claim a word that was typed. The
                            // recognized sentence's tabs are hidden rather than rewritten as its
                            // tokens — a search result is an alternative the reader picked, not a
                            // word that was read.
                            if (searchedEntry != null) {
                                Text(
                                    text = stringResource(
                                        R.string.lookup_searched,
                                        searchedQuery.orEmpty()
                                    ),
                                    style = type.small,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(Spacing.s))
                                BodyTextAction(
                                    label = R.string.lookup_original_result,
                                    onClick = onOriginalLookup,
                                    type = type
                                )
                                Spacer(Modifier.height(Spacing.l))
                            }
                            // The tabs scroll away with the entry: a permanently pinned strip would
                            // take its height from the reading at every text scale, and the word
                            // being read is named by the headword anyway.
                            if (searchedEntry == null && result.words.size > 1) {
                                WordTabs(
                                    words = result.words,
                                    selected = selected,
                                    onSelect = onSelect,
                                    type = type
                                )
                                Spacer(Modifier.height(Spacing.l))
                            }
                            AnimatedContent(
                                targetState = target,
                                transitionSpec = {
                                    val backward = targetState == null ||
                                        (initialState is WaniKaniTarget.Radical &&
                                            targetState is WaniKaniTarget.Kanji)
                                    pageSlide(forward = !backward) using null
                                },
                                label = "lookupReference",
                                // The slide is clipped to the body's measure, not to the sheet: a
                                // page arriving moves within the column the reader is reading in.
                                modifier = Modifier.clipToBounds()
                            ) { openPage ->
                                if (openPage != null) {
                                    WaniKaniDetailPanel(
                                        target = openPage,
                                        onBack = onWaniKaniBack,
                                        onRadical = onRadical,
                                        type = type,
                                        compact = compact
                                    )
                                } else {
                                    // Word selection is content within one surface; it keeps the
                                    // short crossfade, and the scroller above holds the reader's
                                    // place. A chosen search result is not a word at another index:
                                    // it arrives with no crossfade at all, in the frame the sheet
                                    // shows it.
                                    Crossfade(
                                        targetState = selected,
                                        animationSpec = tween(Motion.SHORT, easing = Motion.emphasized),
                                        label = "entry"
                                    ) { index ->
                                        // One entry of a word that may have several is drawn by the
                                        // same column as a recognized word, with no recognized word
                                        // behind it when the reader typed their way here.
                                        val word = result.words.getOrNull(index)
                                        EntryColumn(
                                            entry = searchedEntry ?: word?.entries?.firstOrNull(),
                                            word = if (searchedEntry == null) word else null,
                                            type = type,
                                            onKanji = onKanji
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (unpinned && footerCard != null) {
                        AnkiFooter(
                            card = footerCard,
                            type = type,
                            settings = settings,
                            onChangeDeck = onChangeDeck
                        )
                    }
                }
            }
            if (!unpinned && footerCard != null) {
                AnkiFooter(
                    card = footerCard,
                    type = type,
                    settings = settings,
                    onChangeDeck = onChangeDeck
                )
            }
        }
    }
}

/**
 * What the pinned control band can hold at one content measure, and whether the source line keeps its
 * place on the row.
 *
 * The band is measured in real dp — a 48 dp target is 48 dp whatever the reader's text size is — and
 * the source line, which is text, in scaled dp.
 */
private data class HeaderFit(
    /** The complete source line stays on the pinned row; otherwise it leads the body. */
    val sourceOnRow: Boolean,
    val copySlot: Boolean,
    val searchSlot: Boolean,
    val selectSlot: Boolean
)

/**
 * The physical-fit ladder, in the order the controls yield: Copy first, then Select again, then
 * Search. Close is never dropped — the escape stays at the band's edge, where it has always been.
 *
 * [contentWidth] is the real measure the band has after safe insets, the grip gutter and the column's
 * own insets. The two empty states carry their own Search and Select again and the failure rail its
 * own Select again, which is what makes the last two rungs safe: no state loses a way out when a
 * control leaves the band.
 */
private fun headerFit(contentWidth: Dp, fontScale: Float): HeaderFit {
    val width = contentWidth.value
    return HeaderFit(
        // The line keeps its row only with room for the words themselves, not just for the targets.
        sourceOnRow = width / fontScale >= ReflowWidth.value &&
            width - FourControlWidth.value >= SourceRowRemainder.value,
        copySlot = width >= FourControlWidth.value,
        selectSlot = width >= ThreeControlWidth.value,
        searchSlot = width >= TwoControlWidth.value
    )
}

/**
 * The recognized line and the controls that act on the whole sheet. Transparent: the sheet's own
 * paper is the surface, and a second container inside it would be a card drawn around a caption.
 *
 * The band is 48 dp high and never grows for a label: [fit] decides which of Copy, Search, Select
 * again and Close share it, in that order, each a 48 dp target with no backing. The line is one line
 * at normal measure, with the complete string still going to the clipboard and to TalkBack; when the
 * reader's own text size — or the panel's own width — leaves it no room, it moves into the body and
 * this row is controls only.
 */
@Composable
private fun SheetHeader(
    text: String,
    fit: HeaderFit,
    /** Null when there is no recognized text to copy; the slot stays, visibly out of action. */
    onCopy: (() -> Unit)?,
    onSearch: () -> Unit,
    onSelectAgain: () -> Unit,
    onDismiss: () -> Unit,
    type: SheetType
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = HeaderMinHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (fit.sourceOnRow && text.isNotBlank()) {
            Text(
                text = text,
                style = type.header,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (fit.copySlot) HeaderAction(Icons.Rounded.ContentCopy, R.string.lookup_cd_copy, onCopy)
        // Search sits in the top-right cluster with the other controls, but Close keeps the far edge:
        // the escape does not move because another action arrived beside it.
        if (fit.searchSlot) HeaderAction(Icons.Rounded.Search, R.string.lookup_search, onSearch)
        if (fit.selectSlot) {
            HeaderAction(Icons.Rounded.Gesture, R.string.lookup_cd_select_again, onSelectAgain)
        }
        HeaderAction(Icons.Rounded.Close, R.string.lookup_cd_close, onDismiss)
    }
}

/**
 * One of the header's controls: a bare neutral glyph in a 48 dp target, with no backing of its own. A
 * null [onClick] is the Copy slot with nothing to copy — it keeps its place and its size so the
 * controls beside it do not move, and fades so it is never mistaken for a control that would work.
 */
@Composable
private fun HeaderAction(icon: ImageVector, @StringRes description: Int, onClick: (() -> Unit)?) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    if (onClick == null) {
        Box(
            modifier = Modifier.size(MinTarget).alpha(DisabledContentAlpha),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(GlyphSize))
        }
        return
    }
    val interactions = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.size(MinTarget).pressOpacity(interactions)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = tint,
            modifier = Modifier.size(GlyphSize)
        )
    }
}

/**
 * The source line and the controls the pinned band could not hold, in the band's own order: the
 * complete source first, then Copy, Search and Select again, with 12 dp after the block so the body
 * content starts where it always did.
 *
 * [emptyState] and [failure] are the two states that already carry their own way out — the empty
 * state's Search button and Select again, the failure rail's Select again — so a relocated control is
 * never a duplicate of one the reader can already reach.
 */
@Composable
private fun BodyFallbacks(
    text: String,
    fit: HeaderFit,
    /** Null when there is nothing to copy — which is also when no Copy action belongs here. */
    copy: (() -> Unit)?,
    onSearch: () -> Unit,
    onSelectAgain: () -> Unit,
    /** The body is one of the empty states, which carry their own Search and Select again. */
    emptyState: Boolean,
    /** The body is the dictionary failure rail, which carries its own Select again. */
    failure: Boolean,
    type: SheetType
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.m)
    ) {
        // The whole recognized line, wrapping: it is here because the pinned band could not hold it,
        // and it stays here for a terminal result too, which is the point of keeping the source
        // beside the notice about it. A blank source never invents a line.
        if (!fit.sourceOnRow && text.isNotBlank()) {
            Text(
                text = text,
                style = type.header,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Copy is the first control to leave the band and it leaves it entirely: an absent Copy
        // reserves no width, and a blank source omits the action rather than leaving a dead one.
        if (!fit.copySlot && copy != null) {
            BodyTextAction(R.string.lookup_cd_copy, copy, type)
        }
        if (!fit.searchSlot && !emptyState) {
            BodyTextAction(R.string.lookup_search, onSearch, type)
        }
        if (!fit.selectSlot && !emptyState && !failure) {
            BodyTextAction(R.string.lookup_select_again, onSelectAgain, type)
        }
    }
    Spacer(Modifier.height(Spacing.m))
}

/**
 * One control that had to leave the pinned band: the same 48 dp target, now a labelled text action at
 * the start of the body. It wraps and grows with the reader's text size rather than being clipped —
 * a relocated control that cannot be read in full is a control that was lost.
 */
@Composable
private fun BodyTextAction(@StringRes label: Int, onClick: () -> Unit, type: SheetType) {
    val interactions = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTarget)
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

/** A 1 dp hairline between two parts of the sheet: rules, never nested cards, mark a boundary. */
@Composable
private fun SectionRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * The scanned words as unfilled tabs: the selected one wears the accent on its text, a 2 dp rule
 * under it and a check in the slot every tab reserves.
 *
 * One horizontally scrolling row at every dock, so the words never wrap and the panel's height cannot
 * change with how many were scanned. The paging arrows float over the row's clipped viewport: when
 * visible they mask the tabs sliding beneath them with the sheet's own paper, and hiding one leaves
 * no slot, no padding and no layout change behind.
 */
@Composable
private fun WordTabs(
    words: List<ScannedWord>,
    selected: Int,
    onSelect: (Int) -> Unit,
    type: SheetType
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // One paging animation at a time: a second tap cancels the first instead of the two
    // coroutines fighting over the scroll position.
    var pageJob by remember { mutableStateOf<Job?>(null) }

    fun page(forward: Boolean) {
        pageJob?.cancel()
        pageJob = scope.launch {
            val viewport = (listState.layoutInfo.viewportEndOffset -
                listState.layoutInfo.viewportStartOffset).toFloat()
            val step = viewport * PAGE_FRACTION
            listState.animateScrollBy(
                if (forward) step else -step,
                tween(Motion.MEDIUM, easing = Motion.emphasized)
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Width fills, height wraps: the row is as tall as the tabs in it, so a larger display size
        // makes it taller instead of cutting the words — and it never asks for the whole column.
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clipToBounds()
        ) {
            itemsIndexed(words) { index, word ->
                WordTab(word, index == selected, { onSelect(index) }, type)
            }
        }

        // The arrows overlay the viewport's edges instead of measuring into its row, so their
        // appearance never changes the layout — no snap, no shift, at either scroll boundary.
        PagingArrow(
            visible = listState.canScrollBackward,
            icon = Icons.Rounded.ChevronLeft,
            description = R.string.lookup_cd_scroll_back,
            alignment = Alignment.CenterStart,
            onClick = { page(forward = false) }
        )
        PagingArrow(
            visible = listState.canScrollForward,
            icon = Icons.Rounded.ChevronRight,
            description = R.string.lookup_cd_scroll_forward,
            alignment = Alignment.CenterEnd,
            onClick = { page(forward = true) }
        )
    }
}

/**
 * One paging arrow, floating over the strip's clipped viewport with an opaque sheet-coloured mask
 * behind it: tabs never show through under the control, and a hidden arrow leaves no slot or padding.
 * Fade only — the overlay takes no part in the row's measurement, so there is no layout to animate.
 */
@Composable
private fun BoxScope.PagingArrow(
    visible: Boolean,
    icon: ImageVector,
    @StringRes description: Int,
    alignment: Alignment,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(Motion.SHORT, easing = Motion.emphasized)),
        exit = fadeOut(tween(Motion.SHORT, easing = Motion.emphasized)),
        modifier = Modifier.align(alignment)
    ) {
        val interactions = remember { MutableInteractionSource() }
        val label = stringResource(description)
        Box(
            modifier = Modifier
                .width(MinTarget)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
                .pressOpacity(interactions)
                .clickable(
                    interactionSource = interactions,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClick = onClick
                )
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(GlyphSize)
            )
        }
    }
}

/**
 * One word tab: unfilled, square-cornered and the same width whether or not it is the selected one.
 *
 * The check's slot is reserved in every tab, so choosing another word moves no text and the tab
 * under the finger cannot slide away from it. Selection is carried by the accent text, the rule
 * under the tab and that check — never by a fill, which would make the selected word look like the
 * action rather than the current one.
 */
@Composable
private fun WordTab(
    word: ScannedWord,
    selected: Boolean,
    onSelect: () -> Unit,
    type: SheetType
) {
    val interactions = remember { MutableInteractionSource() }
    val accent = MaterialTheme.colorScheme.primary
    val textColor by animateColorAsState(
        if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(Motion.SHORT, easing = Motion.emphasized),
        label = "wordTabText"
    )
    val ruleAlpha by animateFloatAsState(
        if (selected) 1f else 0f,
        tween(Motion.SHORT, easing = Motion.emphasized),
        label = "wordTabRule"
    )
    val checkAlpha by animateFloatAsState(
        if (selected) 1f else 0f,
        tween(Motion.SHORT, easing = Motion.emphasized),
        label = "wordTabCheck"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .defaultMinSize(minWidth = MinTarget, minHeight = MinTarget)
            .pressOpacity(interactions)
            // Drawn before the padding, so the rule sits on the tab's own bottom edge rather than
            // under the text, where it would read as an underline on the word.
            .drawBehind {
                if (ruleAlpha > 0f) {
                    val thickness = TabRuleHeight.toPx()
                    drawRect(
                        color = accent.copy(alpha = ruleAlpha),
                        topLeft = Offset(0f, size.height - thickness),
                        size = Size(size.width, thickness)
                    )
                }
            }
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onSelect
            )
            .semantics { this.selected = selected }
            .padding(horizontal = Spacing.m, vertical = Spacing.m)
    ) {
        Box(modifier = Modifier.size(TabCheckSlot), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(TabCheckSlot).alpha(checkAlpha)
            )
        }
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = word.surface,
            style = type.label,
            color = textColor,
            // One line, but never cut: the row scrolls sideways, so a long word is scrolled to
            // rather than shortened to a fragment of itself.
            maxLines = 1
        )
    }
}

/**
 * One dictionary entry: the word, its reading and metadata, the numbered meanings, the full part of
 * speech, and the kanji that are one tap from their WaniKani page. Laid out at its natural height —
 * the sheet's one body scroller is what moves it.
 *
 * The same column draws both entries the sheet can show: the first match of the word the reader
 * recognized ([word]), and the entry they typed their way to ([word] null, so the headword is the
 * entry's own and there is no recognized sentence to quote a base form from).
 */
@Composable
private fun EntryColumn(
    /** The entry to draw; null only for a recognized word the dictionary has no entry for. */
    entry: DictEntry?,
    /** The recognized word this entry matched, or null for an entry the reader searched for. */
    word: ScannedWord?,
    type: SheetType,
    onKanji: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 16 dp between the last row and the footer's own rule.
            .padding(bottom = Spacing.l)
    ) {
        if (entry == null) {
            // WordScanner only emits surfaces with real entries; this is a defensive fallback.
            Text(
                text = word?.surface.orEmpty(),
                style = type.reading,
                color = MaterialTheme.colorScheme.onSurface
            )
            return
        }

        val headword = word?.let { headwordFor(it, entry) } ?: entry.primary
        val alsoWritten = word?.let { notedForm(it, entry) }
        val reading = entry.kana.joinToString("・")
        // The word keeps its own line at the sheet's largest size: it is what the reader looked up,
        // so nothing shares its line. It is drawn as content, not as a coloured action.
        Text(
            text = headword,
            style = type.headword,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (reading.isNotEmpty() && reading != headword) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = reading,
                style = type.reading,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // One plain line of metadata instead of a capsule: "common" and the word's first part of
        // speech are facts about the word, and neither is something the reader can act on.
        val summary = listOfNotNull(
            if (entry.common) stringResource(R.string.lookup_common) else null,
            entry.pos.firstOrNull()?.let { compactPartOfSpeech(it) }?.takeIf { it.isNotEmpty() }
        ).joinToString(" · ")
        if (summary.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = summary,
                style = type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (alsoWritten != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.lookup_also_written, alsoWritten),
                style = type.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // The dictionary form the recognized surface matched. Neither this line nor "Also written"
        // above it can exist for an entry the reader typed: there is no surface to have come from.
        if (word != null && word.baseForm != word.surface) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.lookup_base_form, word.surface),
                style = type.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.xl))
        SectionRule()
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = stringResource(R.string.lookup_meaning_caption),
            style = type.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))
        if (entry.glosses.isEmpty()) {
            // No translation is manufactured for an entry that has none.
            Text(
                text = stringResource(R.string.lookup_no_meaning),
                style = type.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Glosses(entry.glosses, type)
        }

        if (entry.pos.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.l))
            // The summary above is a shorthand; this is the dictionary's own list, unshortened and
            // unwrapped into pills — it is read, not tapped.
            Text(
                text = stringResource(R.string.lookup_pos_caption),
                style = type.tiny,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = entry.pos.joinToString(" / "),
                style = type.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // The kanji of the headword, each one a tap from its WaniKani page (ui/wanikani). Drawn
        // from the headword, so a kana-only word has nothing to offer, and shown for every word:
        // what WaniKani covers is not known before the tap.
        KanjiStrip(headword = headword, onKanji = onKanji, type = type)

        if (word != null && word.entries.size > 1) {
            OtherMatches(word, type)
        }
    }
}

/**
 * The meanings, one numbered row each: a long entry is scanned down the numbers instead of being
 * read as one block. The index is quiet — it is a place marker, not a label — and its gutter is
 * measured from the widest number this entry can reach, so a twenty-sense verb does not push its
 * prose half a line to the right of a two-sense one.
 */
@Composable
private fun Glosses(glosses: List<String>, type: SheetType) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter = remember(glosses.size, type) {
        val widest = measurer.measure("${glosses.size}.", style = type.caption).size.width
        maxOf(GlossIndexGutter, with(density) { widest.toDp() } + Spacing.s)
    }
    glosses.forEachIndexed { index, gloss ->
        if (index > 0) Spacer(Modifier.height(Spacing.m))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${index + 1}.",
                style = type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(gutter)
            )
            Text(
                text = gloss,
                style = type.gloss,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Collapsed "Other matches (N)" row expanding to the runner-up entries: headword, its reading, and
 * the first two glosses, in flat rows separated by hairlines. JMdict ships no frequency ranks, so
 * homographs of one surface cannot be disambiguated reliably — show them instead of hiding them.
 *
 * A runner-up is a preview, not a second entry: nothing here is selectable, exportable or claimed to
 * outrank the word the reader actually scanned.
 */
@Composable
private fun OtherMatches(word: ScannedWord, type: SheetType) {
    var expanded by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val feedback = rememberFeedback()
    val extras = word.entries.drop(1)

    Spacer(Modifier.height(Spacing.xl))
    // A hairline marks the end of the entry above and the start of the alternatives: the two are
    // different kinds of thing, and the rule says so before the label has to.
    SectionRule()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clickable carries no minimum touch target of its own, unlike Surface and IconButton.
            .heightIn(min = MinTarget)
            .pressOpacity(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                onClick = {
                    expanded = !expanded
                    feedback.haptic(FeedbackHaptic.Tick)
                }
            )
            // The chevron points the way visually; this publishes the same state as the one action a
            // reader can actually take, which is how Compose hands an expand/collapse to TalkBack
            // (it has no plain "expanded" property to set).
            .semantics {
                if (expanded) {
                    collapse { expanded = false; true }
                } else {
                    expand { expanded = true; true }
                }
            }
            .padding(vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.lookup_other_matches, extras.size),
            style = type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(GlyphSize)
        )
    }
    if (expanded) {
        extras.forEachIndexed { index, alternative ->
            if (index > 0) SectionRule()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.l)
            ) {
                Text(
                    text = alternativeHeadwordFor(word, alternative),
                    style = type.reading,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val reading = alternative.kana.joinToString("・")
                if (reading.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = reading,
                        style = type.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                alternative.glosses.take(2).forEachIndexed { glossIndex, gloss ->
                    Spacer(Modifier.height(if (glossIndex == 0) Spacing.s else Spacing.xs))
                    Text(
                        text = gloss,
                        style = type.body,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** The add button's own state: checking, nothing yet, working, or finished with its final label. */
private sealed interface AnkiUiState {
    data object Idle : AnkiUiState

    /** Real foreground work — the duplicate check or the write — with the label it is doing it under. */
    data class Busy(@param:StringRes val label: Int) : AnkiUiState

    /** Added, or already there; either way the button is done and shows [label]. */
    data class Done(@param:StringRes val label: Int) : AnkiUiState
}

/**
 * "Add to Anki" for [card]: the configured note type is checked when this entry opens, then a tap
 * adds the card, or — when AnkiDroid's database permission is not granted, which the overlay cannot
 * ask for — hands the word to AnkiDroid's own add screen instead.
 *
 * The card is the entry on screen, so a chosen search result exports its own readings and meanings
 * rather than the ones of whichever entry of that word happened to come first. The state is keyed on
 * the card and the AnkiDroid status, so selecting another word — or another search result — starts
 * from a fresh button. A finished button keeps its honest label for the rest of the entry: reverting
 * to "Add to Anki" on a disabled control would read as an unfinished action that had silently failed.
 *
 * A failure is stated above the button and stays there. The action is offered again — a provider can
 * be briefly unavailable — but only a fresh tap on it writes, so nothing here retries by itself.
 *
 * The deck row above the button is the destination itself and the way to change it: [onChangeDeck]
 * opens the sheet's own chooser, and a chosen deck comes back through [settings] — this footer then
 * re-checks the new deck and exports into it, with no card written until the action is tapped again.
 */
@Composable
private fun AnkiFooter(
    card: AnkiCard,
    type: SheetType,
    settings: AnkiSettings,
    onChangeDeck: () -> Unit
) {
    val context = LocalContext.current
    val feedback = rememberFeedback()
    val interactions = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val status = remember { AnkiExporter.status(context) }

    var state by remember(card, status, settings) {
        mutableStateOf<AnkiUiState>(
            if (status == AnkiStatus.Ready) {
                AnkiUiState.Busy(R.string.anki_checking)
            } else {
                AnkiUiState.Idle
            }
        )
    }
    var error by remember(card, status, settings) { mutableStateOf<String?>(null) }

    LaunchedEffect(card, status, settings) {
        if (status == AnkiStatus.Ready) {
            state = if (AnkiExporter.isDuplicate(context, card, settings)) {
                AnkiUiState.Done(R.string.anki_duplicate)
            } else {
                AnkiUiState.Idle
            }
        }
    }

    // Update the button from the export outcome; confirmation feedback follows a newly added note.
    suspend fun add() {
        state = AnkiUiState.Busy(R.string.anki_adding)
        error = null
        when (val result = AnkiExporter.add(context, card, settings)) {
            AddResult.Added -> {
                state = AnkiUiState.Done(R.string.anki_added)
                feedback.haptic(FeedbackHaptic.Confirm)
                feedback.sound(FeedbackSound.Added)
            }

            AddResult.Duplicate -> state = AnkiUiState.Done(R.string.anki_duplicate)

            is AddResult.Failed -> {
                state = AnkiUiState.Idle
                error = result.message
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionRule()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.m, bottom = FooterBottomGap)
        ) {
            if (status == AnkiStatus.NotInstalled) {
                Text(
                    text = stringResource(R.string.anki_not_installed),
                    style = type.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            // The destination is named only on the direct route. On the share route Gaten hands the
            // word to AnkiDroid's add screen and cannot claim which deck that screen will use.
            if (status == AnkiStatus.Ready) {
                // Changed only while nothing is in flight: a deck picked mid-export would name a
                // destination the write that is already running was not made for.
                DeckRow(
                    deck = settings.deckName,
                    type = type,
                    enabled = state !is AnkiUiState.Busy,
                    onChangeDeck = onChangeDeck
                )
                Spacer(Modifier.height(Spacing.s))
            }

            val message = error
            if (message != null) {
                Text(
                    text = message,
                    style = type.small,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(Spacing.s))
            }

            Button(
                onClick = {
                    if (status == AnkiStatus.NeedsPermission) {
                        if (!AnkiExporter.shareFallback(context, card)) {
                            error = context.getString(R.string.anki_error_share)
                        }
                    } else {
                        scope.launch { add() }
                    }
                },
                enabled = state is AnkiUiState.Idle,
                shape = ActionShape,
                colors = actionButtonColors(),
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m),
                interactionSource = interactions,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ActionMinHeight)
                    .pressOpacity(interactions)
            ) {
                when (val current = state) {
                    is AnkiUiState.Busy -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(SpinnerSize),
                            color = LocalContentColor.current,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(current.label), style = type.label)
                    }

                    is AnkiUiState.Done -> {
                        if (current.label == R.string.anki_added) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(ActionGlyphSize)
                            )
                            Spacer(Modifier.width(Spacing.s))
                        }
                        Text(stringResource(current.label), style = type.label)
                    }

                    AnkiUiState.Idle -> {
                        // An open glyph names the handoff: this tap leaves Gaten for AnkiDroid's own
                        // add screen instead of writing the card here.
                        val share = status == AnkiStatus.NeedsPermission
                        Icon(
                            if (share) Icons.AutoMirrored.Rounded.OpenInNew else Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(ActionGlyphSize)
                        )
                        Spacer(Modifier.width(Spacing.s))
                        Text(
                            stringResource(if (share) R.string.anki_send else R.string.anki_add),
                            style = type.label
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where a direct write would land, and the one way to change it: the deck in use over the whole 48 dp
 * row, with the change named on the glyph that opens the chooser.
 *
 * The name is AnkiDroid's own, so it is shown as it is; above 1.2 text scale it takes a line of its
 * own rather than squeezing the label beside it into an ellipsis. [enabled] is false while a check or
 * a write is in flight, so the destination cannot move under the note being written.
 */
@Composable
private fun DeckRow(
    deck: String,
    type: SheetType,
    enabled: Boolean,
    onChangeDeck: () -> Unit
) {
    val label = stringResource(R.string.anki_deck)
    val change = stringResource(R.string.anki_deck_change)
    val interactions = remember { MutableInteractionSource() }
    val target = Modifier
        .fillMaxWidth()
        .heightIn(min = DeckRowMinHeight)
        .pressOpacity(interactions)
        .clickable(
            interactionSource = interactions,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Button,
            onClick = onChangeDeck
        )
    if (LocalDensity.current.fontScale > DeckStackFontScale) {
        Column(modifier = target) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = type.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.s))
                DeckChangeGlyph(description = change, enabled = enabled)
            }
            Text(deck, style = type.caption, color = MaterialTheme.colorScheme.onSurface)
        }
        return
    }
    Row(
        modifier = target,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = type.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.m))
        Text(
            text = deck,
            style = type.caption,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Spacing.s))
        DeckChangeGlyph(description = change, enabled = enabled)
    }
}

/**
 * The deck row's one glyph: the change it opens, in the accent while it can be used and in the
 * neutral variant ink while an export is in flight and the destination is fixed.
 */
@Composable
private fun DeckChangeGlyph(description: String, enabled: Boolean) {
    Icon(
        imageVector = Icons.Rounded.SwapHoriz,
        contentDescription = description,
        tint = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(GlyphSize)
    )
}

/**
 * The one filled action in the sheet: the reader's accent, and the honest disabled treatment the
 * spec asks for — the ink at 12% for the fill and 38% for the label — rather than Material's own
 * grey ladder, which is a colour this app never draws.
 */
@Composable
private fun actionButtonColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledFillAlpha),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContentAlpha)
)

/**
 * One part of speech, as short as the RN detail page has always shown it: the dictionary's own
 * parenthetical qualifier dropped, the first of its "/"-separated alternatives kept, and the plural
 * trimmed. This summary is only a hint at what the word is; the full list is further down the entry.
 */
private fun compactPartOfSpeech(label: String): String {
    val short = label.substringBefore('(').trim()
    val named = if (short.equals("expression", ignoreCase = true) ||
        short.equals("expressions", ignoreCase = true)
    ) {
        "Expression / phrase"
    } else {
        short
    }
    return named.substringBefore(" / ").removeSuffix("s").removeSuffix("S")
}
