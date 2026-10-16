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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaten.app.R
import com.gaten.app.anki.AddResult
import com.gaten.app.anki.AnkiCard
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.ScannedWord
import com.gaten.app.dict.alternativeHeadwordFor
import com.gaten.app.dict.headwordFor
import com.gaten.app.dict.notedForm
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.FeedbackSound
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.components.pressScale
import com.gaten.app.ui.theme.pageSlide
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing
import com.gaten.app.ui.theme.SheetShape
import com.gaten.app.ui.components.KanjiStrip
import com.gaten.app.ui.wanikani.WaniKaniDetailPanel
import com.gaten.app.wanikani.WaniKaniTarget
import kotlinx.coroutines.delay
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
 * Minimum height of the compact "Add to Anki" button: shorter than the 40dp default, still a full
 * target — a floor, not a cap, so a larger display size makes the button taller instead of clipping
 * its label.
 */
private val CompactButtonHeight = 36.dp

/** The handle row's height: a generous 48dp touch target for the drag-to-dismiss grip. */
private val HandleTouchHeight = 48.dp

/** The handle pill's width at rest; it stretches by [HandleStretch] at a full dismiss drag. */
private val HandleWidth = 32.dp
private val HandleStretch = 16.dp

/**
 * The word chip row's minimum height, so scrolling chips can never grow the sheet past a comfortable
 * band — while still growing with their own text rather than clipping it at a larger display size.
 */
private val ChipRowMinHeight = 40.dp

/**
 * One sheet's whole text scale, so a dock only has to say whether it is compact.
 *
 * Both docks take the theme's own styles and keep their line heights, because this panel is mostly
 * prose — glosses, mnemonics, readings — and prose needs a rhythm rather than a size. The side dock's
 * copy is a step tighter throughout (a 26sp headword against the theme's 34sp, 16sp glosses against
 * 18sp) since that panel is a strip down one side of the screen. Nothing here touches the theme.
 */
internal data class SheetType(
    val header: TextStyle,
    val headword: TextStyle,
    val reading: TextStyle,
    val small: TextStyle,
    val gloss: TextStyle,
    val chip: TextStyle,
    val menu: TextStyle,
    val sub: TextStyle,
    val button: TextStyle
)

/** The scale for one sheet: the theme's own when [compact] is false, the tighter copy when true. */
@Composable
internal fun sheetType(compact: Boolean): SheetType {
    val type = MaterialTheme.typography
    return remember(compact, type) {
        if (compact) {
            SheetType(
                header = type.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                headword = type.displaySmall.copy(fontSize = 26.sp, lineHeight = 34.sp),
                reading = type.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
                small = type.labelSmall,
                gloss = type.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
                chip = type.labelLarge.copy(fontSize = 15.sp),
                menu = type.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
                sub = type.bodySmall,
                button = type.labelMedium
            )
        } else {
            SheetType(
                header = type.bodyMedium,
                headword = type.displaySmall,
                reading = type.titleMedium,
                small = type.labelSmall,
                gloss = type.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
                chip = type.labelLarge,
                menu = type.bodyMedium,
                sub = type.bodySmall,
                button = type.labelLarge
            )
        }
    }
}

/**
 * The result sheet drawn inside the overlay, on the edge [layout] picked: header, word chips and
 * dictionary entry.
 *
 * A side dock (landscape) fills the height and takes [SheetLayout.fraction] of the width, keeps its
 * content clear of the status bar, nav bar and camera cutout, and carries the same compact scale
 * throughout so far more of the entry fits without scrolling. The bottom dock (portrait) is full
 * width with a bottom-docked cap on its height and rounded top corners; it paints edge-to-edge to
 * the physical bottom, under the navigation strip, and insets only its content.
 *
 * Everything beside the sheet stays as scrim; tapping it dismisses the overlay, as does dragging
 * the handle — and the rest of the panel — toward the docked edge past [DISMISS_DRAG_DP]. While
 * dragged, the bottom sheet follows the finger and springs back if the drag was short. Chip
 * selection crossfades the entry; empty and error results collapse the body to one muted line plus
 * "Select again".
 */
@Composable
fun ResultSheet(
    result: LookupResult,
    onDismiss: () -> Unit,
    onSelectAgain: () -> Unit,
    layout: SheetLayout,
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
    val dock = layout.dock
    val compact = dock != SheetDock.Bottom
    val type = sheetType(compact)
    val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
    var dragDistance by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Snapped while the finger is down, so the sheet tracks it exactly; springing back on release.
    // Never read sheetOffset in composition: the drag spring would recompose the whole sheet per
    // frame. It is read deferred in the Surface's graphicsLayer and inside HandleBar's layout.
    val sheetOffset by animateFloatAsState(
        targetValue = dragDistance,
        animationSpec = if (dragging) snap() else spring(),
        label = "sheetDrag"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim over the rest of the screen: a tap dismisses the overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        // Drag-to-dismiss toward the docked edge — down for the bottom, left or right for a side.
        // The scrollable entry wins its own scroll gesture, as intended. The bottom drag clamps at
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
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Before heightIn: the panel's height tracks its content smoothly — a chip
                    // selection whose entry has a different height would otherwise snap when the
                    // crossfade ends. The first measurement is not animated, so the sheet never
                    // resizes 0->full on open, and the cap still applies unchanged.
                    .animateContentSize(tween(250, easing = FastOutSlowInEasing))
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * layout.fraction)
                    // Dragged: the sheet rides the finger, past the physical bottom and back.
                    .graphicsLayer { translationY = sheetOffset },
                shape = BottomSheetShape,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(dragModifier)
                        .padding(horizontal = Spacing.l)
                        // Inset inside the panel, not around it: the sheet colour paints under the
                        // navigation strip, which stays transparent over it.
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    HandleBar { (sheetOffset / dismissDragPx).coerceIn(0f, 1f) }
                    SheetBody(
                        result = result,
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
                        target = wanikani.lastOrNull(),
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
                        compact = false
                    )
                }
            }

            SheetDock.Left, SheetDock.Right -> Surface(
                modifier = modifier
                    .align(
                        if (dock == SheetDock.Left) Alignment.CenterStart else Alignment.CenterEnd
                    )
                    .fillMaxHeight()
                    .width(LocalConfiguration.current.screenWidthDp.dp * layout.fraction),
                shape = if (dock == SheetDock.Left) LeftSheetShape else RightSheetShape,
                color = MaterialTheme.colorScheme.surface
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
                    if (dock == SheetDock.Right) HandleGutter()
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.m)
                    ) {
                        SheetBody(
                            result = result,
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
                            target = wanikani.lastOrNull(),
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
                            compact = true
                        )
                    }
                    if (dock == SheetDock.Left) HandleGutter()
                }
            }
        }
    }
}

/**
 * The grip at the inner edge of the bottom dock: the horizontal pill the sheet has always drawn,
 * inside a 48dp touch target. [dragProgress] is how far a dismiss drag has come, 0 to 1: the pill
 * stretches with it, so the handle answers the finger before the sheet is let go. The lambda keeps
 * the animated value out of composition — it is read only in the layout block below.
 */
@Composable
private fun HandleBar(dragProgress: () -> Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HandleTouchHeight)
            .padding(vertical = Spacing.s),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(4.dp)
                .layout { measurable, constraints ->
                    // Reading the progress here scopes each animation frame's invalidation to
                    // this pill's measure alone; the sheet's composition never sees it.
                    val width = constraints.constrainWidth(
                        (HandleWidth + HandleStretch * dragProgress()).roundToPx()
                    )
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = width, maxWidth = width)
                    )
                    layout(width, placeable.height) { placeable.placeRelative(0, 0) }
                }
                .background(MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

/** The grip at the inner edge of a side dock: the same pill standing up, against the free screen. */
@Composable
private fun HandleGutter() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(Spacing.l)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .background(MaterialTheme.colorScheme.outline, CircleShape)
        )
    }
}

/**
 * Everything inside the panel past the grip: the header row, the word chips and the entry.
 *
 * One implementation for every dock, because a dock changes only the panel around it. [compact]
 * tightens the typography and the gaps for the side dock and nothing else — with it false, every
 * value below is exactly what the portrait sheet has always used.
 */
@Composable
private fun ColumnScope.SheetBody(
    result: LookupResult,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    onSelectAgain: () -> Unit,
    /** The WaniKani page open over the entry, or null while the entry itself is showing. */
    target: WaniKaniTarget?,
    onKanji: (String) -> Unit,
    onRadical: (Int) -> Unit,
    onWaniKaniBack: () -> Unit,
    type: SheetType,
    compact: Boolean
) {
    val context = LocalContext.current
    val iconSize = if (compact) 18.dp else 20.dp
    val feedback = rememberFeedback()
    val copyInteractions = remember { MutableInteractionSource() }
    val selectInteractions = remember { MutableInteractionSource() }
    val closeInteractions = remember { MutableInteractionSource() }

    // The scanned line, on its own card: it is what the answer below is an answer to, and it is the
    // one row of the sheet that does not scroll. The three taps beside it act on the whole sheet —
    // copy the text, select again, close — so they are controls on that card rather than marks
    // floating over the words.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (compact) Spacing.m else Spacing.l,
                end = Spacing.xs
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = result.ocrText,
                style = type.header,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("text", result.ocrText))
                    feedback.haptic(FeedbackHaptic.Tick)
                    Toast.makeText(context, R.string.lookup_toast_copied, Toast.LENGTH_SHORT).show()
                },
                interactionSource = copyInteractions,
                modifier = Modifier.size(48.dp).pressScale(copyInteractions)
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.lookup_cd_copy),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(
                onClick = onSelectAgain,
                interactionSource = selectInteractions,
                modifier = Modifier.size(48.dp).pressScale(selectInteractions)
            ) {
                Icon(
                    Icons.Rounded.Gesture,
                    contentDescription = stringResource(R.string.lookup_cd_select_again),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(
                onClick = onDismiss,
                interactionSource = closeInteractions,
                modifier = Modifier.size(48.dp).pressScale(closeInteractions)
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.lookup_cd_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
    Spacer(Modifier.height(if (compact) Spacing.xs else Spacing.s))

    when {
        result.error != null -> EmptyState(
            message = stringResource(R.string.lookup_dict_error, result.error),
            onSelectAgain = onSelectAgain,
            type = type,
            compact = compact
        )

        result.ocrText.isBlank() -> EmptyState(
            message = stringResource(R.string.lookup_no_text),
            onSelectAgain = onSelectAgain,
            type = type,
            compact = compact
        )

        result.words.isEmpty() -> EmptyState(
            message = stringResource(R.string.lookup_no_words),
            onSelectAgain = onSelectAgain,
            type = type,
            compact = compact
        )

        else -> {
            ChipRow(
                words = result.words,
                selected = selected,
                onSelect = onSelect,
                type = type,
                compact = compact
            )
            Spacer(Modifier.height(if (compact) Spacing.xs else Spacing.s))
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    val backward = targetState == null ||
                        (initialState is WaniKaniTarget.Radical && targetState is WaniKaniTarget.Kanji)
                    pageSlide(forward = !backward) using null
                },
                label = "lookupReference",
                modifier = Modifier.weight(1f, fill = compact)
            ) { page ->
                Column(
                    modifier = Modifier.fillMaxWidth().then(if (compact) Modifier.fillMaxHeight() else Modifier)
                ) {
                    if (page != null) {
                        WaniKaniDetailPanel(
                            target = page,
                            onBack = onWaniKaniBack,
                            onRadical = onRadical,
                            type = type,
                            compact = compact,
                            modifier = Modifier.weight(1f, fill = compact)
                        )
                    } else {
                        // Word selection is content within one surface; it keeps the short crossfade.
                        Crossfade(
                            targetState = selected,
                            animationSpec = tween(Motion.SHORT),
                            label = "entry",
                            modifier = Modifier.weight(1f, fill = compact)
                        ) { index ->
                            EntryColumn(result.words[index], type, compact, onKanji)
                        }
                        AddToAnki(result.words[selected], type, compact)
                    }
                }
            }
        }
    }
}

/** Nothing to read: one card, one explanation, and the one way back to the screen. */
@Composable
private fun EmptyState(
    message: String,
    onSelectAgain: () -> Unit,
    type: SheetType,
    compact: Boolean
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) Spacing.m else Spacing.l,
                    vertical = if (compact) Spacing.l else Spacing.xl
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Rounded.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (compact) 22.dp else 28.dp)
            )
            Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
            Text(
                text = message,
                style = type.header,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(if (compact) Spacing.m else Spacing.l))
            Button(onClick = onSelectAgain) {
                Text(stringResource(R.string.lookup_select_again), style = type.button)
            }
        }
    }
}

/**
 * The scanned words as chips, the selected one filled primary. One horizontally scrolling row at
 * every dock — portrait bottom sheet and landscape side panel alike — so the words never wrap and
 * the panel's height cannot change with how many were scanned. The paging arrows float over the
 * row's clipped viewport: when visible they mask the chips sliding beneath them with the sheet's
 * own surface colour, and hiding one leaves no slot, no padding and no layout change behind.
 */
@Composable
private fun ChipRow(
    words: List<ScannedWord>,
    selected: Int,
    onSelect: (Int) -> Unit,
    type: SheetType,
    compact: Boolean
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // One paging animation at a time: a second tap cancels the first instead of the two
    // coroutines fighting over the scroll position.
    var pageJob by remember { mutableStateOf<Job?>(null) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChipRowMinHeight)
    ) {
        // Width fills, height wraps: the row is a floor tall and as tall as the chips in it, so a
        // larger display size makes it taller instead of cutting the words — and it never asks for
        // the whole column, which would starve the entry beneath it.
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
        ) {
            itemsIndexed(words) { index, word ->
                Chip(word, index == selected, { onSelect(index) }, type, compact)
            }
        }

        // The arrows overlay the viewport's edges instead of measuring into its row, so their
        // appearance never changes the layout — no snap, no shift, at either scroll boundary.
        ChipRowArrow(
            visible = listState.canScrollBackward,
            icon = Icons.Rounded.ChevronLeft,
            description = R.string.lookup_cd_scroll_back,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            // A tap pages the row by most of its width, a sweep's worth of words at a time.
            pageJob?.cancel()
            pageJob = scope.launch {
                val page = (listState.layoutInfo.viewportEndOffset -
                    listState.layoutInfo.viewportStartOffset).toFloat()
                listState.animateScrollBy(-page * 0.85f)
            }
        }

        ChipRowArrow(
            visible = listState.canScrollForward,
            icon = Icons.Rounded.ChevronRight,
            description = R.string.lookup_cd_scroll_forward,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            pageJob?.cancel()
            pageJob = scope.launch {
                val page = (listState.layoutInfo.viewportEndOffset -
                    listState.layoutInfo.viewportStartOffset).toFloat()
                listState.animateScrollBy(page * 0.85f)
            }
        }
    }
}

/**
 * One paging arrow, floating over the chip viewport with an opaque sheet-coloured mask behind it:
 * chips never show through under the control, and a hidden arrow leaves no slot or padding. Fade
 * only — the overlay takes no part in the row's measurement, so there is no layout to animate.
 */
@Composable
private fun ChipRowArrow(
    visible: Boolean,
    icon: ImageVector,
    @StringRes description: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    icon,
                    contentDescription = stringResource(description),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(Spacing.xs).size(20.dp)
                )
            }
        }
    }
}

/** One word chip: filled primary when selected, a quiet card on the row otherwise. */
@Composable
private fun Chip(
    word: ScannedWord,
    selected: Boolean,
    onSelect: () -> Unit,
    type: SheetType,
    compact: Boolean
) {
    val interactions = remember { MutableInteractionSource() }
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        tween(Motion.SHORT), label = "wordChipColor"
    )
    val checkScale by animateFloatAsState(
        if (selected) 1f else 0f, Motion.overshoot, label = "wordChipCheck"
    )
    Surface(
        onClick = onSelect,
        interactionSource = interactions,
        modifier = Modifier.pressScale(interactions).semantics { this.selected = selected },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) Spacing.m else Spacing.l,
                vertical = if (compact) Spacing.xs else Spacing.s
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    Icons.Rounded.Check, contentDescription = null,
                    modifier = Modifier.size(18.dp).graphicsLayer {
                        scaleX = checkScale
                        scaleY = checkScale
                    }
                )
                Spacer(Modifier.width(Spacing.xs))
            }
            Text(text = word.surface, style = type.chip)
        }
    }
}

/** Dictionary entry for the selected word's first match; scrolls when long. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryColumn(
    word: ScannedWord,
    type: SheetType,
    compact: Boolean,
    onKanji: (String) -> Unit
) {
    val entry = word.entries.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                top = if (compact) Spacing.xs else Spacing.s,
                bottom = if (compact) Spacing.s else Spacing.l
            )
    ) {
        if (entry == null) {
            // WordScanner only emits surfaces with real entries; this is a defensive fallback.
            Text(
                text = word.surface,
                style = type.reading,
                color = MaterialTheme.colorScheme.onSurface
            )
            return
        }

        val headword = headwordFor(word, entry)
        val alsoWritten = notedForm(word, entry)
        // The headword keeps its own line at the sheet's largest size: it is what the reader looked
        // up, so nothing shares its line. The reading and the badge below it leave room for both a
        // long word and a large display size.
        Text(
            text = headword,
            style = type.headword,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (entry.kana.isNotEmpty()) {
                Text(
                    text = entry.kana.joinToString("・"),
                    style = type.reading,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (entry.common) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(start = Spacing.s)
                ) {
                    Text(
                        text = stringResource(R.string.lookup_common),
                        style = type.small,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp)
                    )
                }
            }
        }
        // The kanji of the headword, each one a tap from its WaniKani page (ui/wanikani). Drawn
        // from the headword, so a kana-only word has nothing to offer, and shown for every word:
        // what WaniKani covers is not known before the tap.
        KanjiStrip(headword = headword, onKanji = onKanji, type = type)
        if (alsoWritten != null) {
            Text(
                text = stringResource(R.string.lookup_also_written, alsoWritten),
                style = type.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (word.baseForm != word.surface) {
            Text(
                text = stringResource(R.string.lookup_base_form, word.surface),
                style = type.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.pos.isNotEmpty()) {
            Spacer(Modifier.height(if (compact) Spacing.xs else Spacing.s))
            // Parts of speech are facts about the word, so each gets its own tag instead of one
            // dotted line the reader has to take apart.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                entry.pos.forEach { part ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = part,
                            style = type.small,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) Spacing.xs else Spacing.s))
        // One sense per line, numbered in the accent down the left edge: a long entry is scanned
        // down that edge instead of being read as a single block.
        entry.glosses.forEachIndexed { index, gloss ->
            if (index > 0) Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${index + 1}.",
                    style = type.small,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    text = gloss,
                    style = type.gloss,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (word.entries.size > 1) {
            OtherMatches(word, type, compact)
        }
    }
}

/** The add button's own state: checking, nothing yet, working, or finished with its final label. */
private sealed interface AnkiUiState {
    data object Idle : AnkiUiState

    data object Checking : AnkiUiState

    data object Adding : AnkiUiState

    /** Added, or already there; either way the button is done and shows [label]. */
    data class Done(@StringRes val label: Int) : AnkiUiState
}

/**
 * "Add to Anki" for [word]: the configured note type is checked when this entry opens, then a tap
 * adds the card, or — when AnkiDroid's database permission is not granted, which the overlay cannot
 * ask for — hands the word to AnkiDroid's share handler instead. Hidden entirely when AnkiDroid is
 * not installed.
 *
 * The sheet pins this button below the scrolling entry. The state is keyed on the card and the
 * AnkiDroid status, so selecting another word chip starts from a fresh button.
 */
@Composable
private fun AddToAnki(word: ScannedWord, type: SheetType, compact: Boolean) {
    val context = LocalContext.current
    val feedback = rememberFeedback()
    val interactions = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val status = remember { AnkiExporter.status(context) }
    val settings = AnkiPreferences.load(context)

    val card = AnkiCard.from(word)
    var state by remember(card, status, settings) {
        mutableStateOf<AnkiUiState>(
            if (status == AnkiStatus.Ready) AnkiUiState.Checking else AnkiUiState.Idle
        )
    }

    var showAdded by remember(card, status, settings) { mutableStateOf(false) }
    LaunchedEffect(showAdded) {
        if (showAdded) {
            delay(1600)
            showAdded = false
        }
    }
    if (status == AnkiStatus.NotInstalled) return

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
        state = AnkiUiState.Adding
        when (val result = AnkiExporter.add(context, card, settings)) {
            AddResult.Added -> {
                state = AnkiUiState.Done(R.string.anki_added)
                showAdded = true
                feedback.haptic(FeedbackHaptic.Confirm)
                feedback.sound(FeedbackSound.Added)
            }
            AddResult.Duplicate -> state = AnkiUiState.Done(R.string.anki_duplicate)
            is AddResult.Failed -> {
                state = AnkiUiState.Idle
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
    Button(
        onClick = {
            if (status == AnkiStatus.NeedsPermission) {
                // The overlay is the accessibility service's window and cannot show the system
                // permission dialog, so an ungranted database permission takes the same route as a
                // declined one: AnkiDroid's own add screen.
                if (!AnkiExporter.shareFallback(context, card)) {
                    Toast.makeText(context, R.string.anki_error_share, Toast.LENGTH_SHORT).show()
                }
            } else {
                scope.launch { add() }
            }
        },
        enabled = state is AnkiUiState.Idle,
        interactionSource = interactions,
        modifier = (if (compact) {
            Modifier
                .fillMaxWidth()
                .heightIn(min = CompactButtonHeight)
        } else {
            Modifier.fillMaxWidth()
        }).pressScale(interactions)
    ) {
        when (val current = state) {
            AnkiUiState.Checking, AnkiUiState.Adding -> CircularProgressIndicator(
                modifier = Modifier.size(if (compact) 16.dp else 18.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )

            is AnkiUiState.Done -> Text(
                if (showAdded) stringResource(R.string.anki_added) + " ✓"
                else stringResource(if (current.label == R.string.anki_added) R.string.anki_add else current.label),
                style = type.button
            )

            AnkiUiState.Idle -> {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 16.dp else 18.dp)
                )
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.anki_add), style = type.button)
            }
        }
    }
    // A little air under the button: in a side dock it would otherwise sit flush on the panel's
    // bottom edge, since only the docked screen edge carries the system-bar inset.
    Spacer(Modifier.height(Spacing.s))
}

/**
 * Collapsed "Other matches (N)" row expanding to the runner-up entries: headword + reading on one
 * line, the first two glosses below. JMdict ships no frequency ranks, so homographs of one
 * surface cannot be disambiguated reliably — show them instead of hiding them.
 */
@Composable
private fun OtherMatches(word: ScannedWord, type: SheetType, compact: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val interactions = remember { MutableInteractionSource() }
    val feedback = rememberFeedback()
    val extras = word.entries.drop(1)
    val iconSize = if (compact) 18.dp else 24.dp

    Spacer(Modifier.height(if (compact) Spacing.m else Spacing.l))
    // A hairline marks the end of the entry above and the start of the alternatives: the two are
    // different kinds of thing, and the rule says so before the label has to.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
    Spacer(Modifier.height(Spacing.xs))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // clickable carries no minimum touch target of its own, unlike Surface and IconButton.
            .heightIn(min = 48.dp)
            .pressScale(interactions)
            .clickable(interactionSource = interactions, indication = LocalIndication.current) {
                expanded = !expanded
                feedback.haptic(FeedbackHaptic.Tick)
            }
            .padding(vertical = if (compact) Spacing.xs else Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.lookup_other_matches, extras.size),
            style = type.menu,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }
    if (expanded) {
        extras.forEach { alternative ->
            Spacer(Modifier.height(Spacing.s))
            // Each runner-up is its own card: with no frequency data to rank homographs by, they are
            // shown as separate entries rather than as a paragraph under the first one.
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(Spacing.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = alternativeHeadwordFor(word, alternative),
                            style = type.gloss,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (alternative.kana.isNotEmpty()) {
                            Spacer(Modifier.width(Spacing.s))
                            Text(
                                text = alternative.kana.joinToString("・"),
                                style = type.menu,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    alternative.glosses.take(2).forEach { gloss ->
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = gloss,
                            style = type.sub,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
