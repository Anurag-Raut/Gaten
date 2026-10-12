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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gaten.app.R
import com.gaten.app.anki.AddResult
import com.gaten.app.anki.AnkiCard
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.ScannedWord
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Dragging the handle toward the sheet's docked edge past this distance dismisses the overlay. */
private const val DISMISS_DRAG_DP = 80f

/** The sheet's corner radius; the same 20dp as [MaterialTheme.shapes.large]. */
private val SheetCorner = 20.dp

/** Bottom dock: the top corners round, as the portrait sheet has always had it. */
private val BottomSheetShape = RoundedCornerShape(topStart = SheetCorner, topEnd = SheetCorner)

/** Left dock: the free screen is on the right, so the end corners round. */
private val LeftSheetShape = RoundedCornerShape(topEnd = SheetCorner, bottomEnd = SheetCorner)

/** Right dock: the free screen is on the left, so the start corners round. */
private val RightSheetShape = RoundedCornerShape(topStart = SheetCorner, bottomStart = SheetCorner)

/** Height of the compact "Add to Anki" button: shorter than the 40dp default, still tappable. */
private val CompactButtonHeight = 36.dp

/** The handle row's height: a generous 48dp touch target for the drag-to-dismiss grip. */
private val HandleTouchHeight = 48.dp

/** The handle pill's width at rest; it stretches by [HandleStretch] at a full dismiss drag. */
private val HandleWidth = 32.dp
private val HandleStretch = 16.dp

/** The word chip row's fixed height, so scrolling chips can never grow the sheet. */
private val ChipRowHeight = 40.dp

/**
 * One sheet's whole text scale, so a dock only has to say whether it is compact.
 *
 * The bottom dock keeps the theme's own styles, only a step larger for the glosses (bodyLarge
 * 16sp -> 17sp) and the small muted lines (bodySmall 12sp -> 13sp). The side dock swaps in a tighter
 * copy of them: the headword drops from [MaterialTheme] displaySmall (32sp) to titleLarge (22sp),
 * the reading 16sp -> 15sp, the part-of-speech and badge line 12sp -> 11sp, the glosses to 14sp on a
 * 17sp line, the small muted lines to 12sp on a 15sp line, chips 14sp -> 12sp and the Add button's
 * label 14sp -> 12sp. Nothing here touches the theme.
 */
private data class SheetType(
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
private fun sheetType(compact: Boolean): SheetType {
    val type = MaterialTheme.typography
    return remember(compact, type) {
        if (compact) {
            SheetType(
                header = type.bodyMedium.copy(fontSize = 12.sp),
                headword = type.titleLarge,
                reading = type.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                small = type.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
                gloss = type.bodyLarge.copy(fontSize = 14.sp, lineHeight = 17.sp),
                chip = type.labelLarge.copy(fontSize = 12.sp),
                menu = type.bodyMedium.copy(fontSize = 12.sp),
                sub = type.bodySmall.copy(fontSize = 12.sp, lineHeight = 15.sp),
                button = type.labelSmall
            )
        } else {
            SheetType(
                header = type.bodyMedium,
                headword = type.displaySmall,
                reading = type.titleMedium,
                small = type.labelSmall,
                gloss = type.bodyLarge.copy(fontSize = 17.sp),
                chip = type.labelLarge,
                menu = type.bodyMedium,
                sub = type.bodySmall.copy(fontSize = 13.sp),
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
    layout: SheetLayout
) {
    var selected by remember { mutableStateOf(0) }
    val dock = layout.dock
    val compact = dock != SheetDock.Bottom
    val type = sheetType(compact)
    val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
    var dragDistance by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Snapped while the finger is down, so the sheet tracks it exactly; springing back on release.
    val sheetOffset by animateFloatAsState(
        targetValue = dragDistance,
        animationSpec = if (dragging) snap() else spring(),
        label = "sheetDrag"
    )
    val dragProgress = (sheetOffset / dismissDragPx).coerceIn(0f, 1f)

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
                        dragDistance = 0f
                        if (dragged > dismissDragPx) onDismiss()
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
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
                    HandleBar(dragProgress)
                    SheetBody(
                        result = result,
                        selected = selected,
                        onSelect = { selected = it },
                        onDismiss = onDismiss,
                        onSelectAgain = onSelectAgain,
                        type = type,
                        compact = false
                    )
                }
            }

            SheetDock.Left, SheetDock.Right -> Surface(
                modifier = Modifier
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
                        // camera cutout, wherever this panel happens to sit on the screen.
                        .windowInsetsPadding(WindowInsets.safeDrawing)
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
                            onSelect = { selected = it },
                            onDismiss = onDismiss,
                            onSelectAgain = onSelectAgain,
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
 * stretches with it, so the handle answers the finger before the sheet is let go.
 */
@Composable
private fun HandleBar(dragProgress: Float = 0f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HandleTouchHeight)
            .padding(vertical = Spacing.s),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(HandleWidth + HandleStretch * dragProgress)
                .height(4.dp)
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
    type: SheetType,
    compact: Boolean
) {
    val context = LocalContext.current
    val iconSize = if (compact) 18.dp else 20.dp

    Row(
        modifier = Modifier.fillMaxWidth(),
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
                Toast.makeText(context, R.string.lookup_toast_copied, Toast.LENGTH_SHORT).show()
            },
            modifier = if (compact) Modifier.size(36.dp) else Modifier
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
            modifier = if (compact) Modifier.size(36.dp) else Modifier
        ) {
            Icon(
                Icons.Rounded.Gesture,
                contentDescription = stringResource(R.string.lookup_cd_select_again),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize)
            )
        }
        // 40dp, not 36: the close control is the one tap that has to land every time.
        IconButton(
            onClick = onDismiss,
            modifier = if (compact) Modifier.size(40.dp) else Modifier
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = stringResource(R.string.lookup_cd_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize)
            )
        }
    }

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
            Crossfade(
                targetState = selected,
                animationSpec = tween(150),
                label = "entry",
                modifier = Modifier.weight(1f, fill = false)
            ) { index ->
                EntryColumn(result.words[index], type, compact)
            }
        }
    }
}

/** One muted line plus "Select again"; replaces the word chips and entry on empty/error results. */
@Composable
private fun EmptyState(
    message: String,
    onSelectAgain: () -> Unit,
    type: SheetType,
    compact: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (compact) Spacing.l else Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = type.header,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))
        TextButton(onClick = onSelectAgain) {
            Text(stringResource(R.string.lookup_select_again))
        }
    }
}

/**
 * The scanned words as chips, the selected one filled primary. One horizontally scrolling row at
 * every dock — portrait bottom sheet and landscape side panel alike — so the words never wrap and
 * the panel's height cannot change with how many were scanned.
 */
@Composable
private fun ChipRow(
    words: List<ScannedWord>,
    selected: Int,
    onSelect: (Int) -> Unit,
    type: SheetType,
    compact: Boolean
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        contentPadding = PaddingValues(horizontal = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ChipRowHeight, max = ChipRowHeight)
    ) {
        itemsIndexed(words) { index, word ->
            Chip(word, index == selected, { onSelect(index) }, type, compact)
        }
    }
}

/** One word chip: filled primary when selected, outlined otherwise. */
@Composable
private fun Chip(
    word: ScannedWord,
    selected: Boolean,
    onSelect: () -> Unit,
    type: SheetType,
    compact: Boolean
) {
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Text(
            text = word.surface,
            style = type.chip,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                horizontal = if (compact) Spacing.m else Spacing.l,
                vertical = if (compact) Spacing.xs else Spacing.s
            )
        )
    }
}

/** Dictionary entry for the selected word's first match; scrolls when long. */
@Composable
private fun EntryColumn(word: ScannedWord, type: SheetType, compact: Boolean) {
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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.primary,
                style = type.headword,
                color = MaterialTheme.colorScheme.primary
            )
            if (entry.common) {
                Spacer(Modifier.width(Spacing.s))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = stringResource(R.string.lookup_common),
                        style = type.small,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xs)
                    )
                }
            }
        }
        if (entry.kana.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = entry.kana.joinToString("・"),
                style = type.reading,
                color = MaterialTheme.colorScheme.primary
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
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = entry.pos.joinToString(" · "),
                style = type.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(if (compact) Spacing.xs else Spacing.s))
        entry.glosses.forEachIndexed { index, gloss ->
            Text(
                text = "${index + 1}. $gloss",
                style = type.gloss,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        if (word.entries.size > 1) {
            OtherMatches(word, type, compact)
        }
        AddToAnki(word, type, compact)
    }
}

/** The add button's own state: nothing yet, working, or finished with the label it ended on. */
private sealed interface AnkiUiState {
    data object Idle : AnkiUiState

    data object Adding : AnkiUiState

    /** Added, or already there; either way the button is done and shows [label]. */
    data class Done(@StringRes val label: Int) : AnkiUiState
}

/**
 * "Add to Anki" for [word]: the tap adds the card, or first asks AnkiDroid for its database
 * permission, or — when the user declines that — hands the word to AnkiDroid's share handler
 * instead. Hidden entirely when AnkiDroid is not installed.
 *
 * The state lives with the entry it belongs to, so selecting another word chip starts from a fresh
 * button.
 */
@Composable
private fun AddToAnki(word: ScannedWord, type: SheetType, compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(AnkiExporter.status(context)) }
    var state by remember { mutableStateOf<AnkiUiState>(AnkiUiState.Idle) }
    val card = AnkiCard.from(word, sentence = word.context)

    if (status == AnkiStatus.NotInstalled) return

    suspend fun add() {
        state = AnkiUiState.Adding
        when (val result = AnkiExporter.add(context, card)) {
            AddResult.Added -> state = AnkiUiState.Done(R.string.anki_added)
            AddResult.Duplicate -> state = AnkiUiState.Done(R.string.anki_duplicate)
            is AddResult.Failed -> {
                state = AnkiUiState.Idle
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            status = AnkiStatus.Ready
            scope.launch { add() }
        } else if (!AnkiExporter.shareFallback(context, card)) {
            Toast.makeText(context, R.string.anki_error_share, Toast.LENGTH_SHORT).show()
        }
    }

    Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
    Button(
        onClick = {
            if (status == AnkiStatus.NeedsPermission) {
                requestPermission.launch(AnkiExporter.PERMISSION)
            } else {
                scope.launch { add() }
            }
        },
        enabled = state is AnkiUiState.Idle,
        modifier = if (compact) {
            Modifier
                .fillMaxWidth()
                .height(CompactButtonHeight)
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        when (val current = state) {
            AnkiUiState.Adding -> CircularProgressIndicator(
                modifier = Modifier.size(if (compact) 16.dp else 18.dp),
                color = LocalContentColor.current,
                strokeWidth = 2.dp
            )

            is AnkiUiState.Done -> Text(stringResource(current.label), style = type.button)

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
}

/**
 * Collapsed "Other matches (N)" row expanding to the runner-up entries: headword + reading on one
 * line, the first two glosses below. JMdict ships no frequency ranks, so homographs of one
 * surface cannot be disambiguated reliably — show them instead of hiding them.
 */
@Composable
private fun OtherMatches(word: ScannedWord, type: SheetType, compact: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val extras = word.entries.drop(1)

    Spacer(Modifier.height(if (compact) Spacing.s else Spacing.m))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (expanded) {
        extras.forEach { alternative ->
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = alternative.primary,
                    style = type.gloss,
                    color = MaterialTheme.colorScheme.onSurface
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
                Text(
                    text = gloss,
                    style = type.sub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}