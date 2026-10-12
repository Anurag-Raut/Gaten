/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ui.lookup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yomilens.app.R
import com.yomilens.app.dict.ScannedWord
import com.yomilens.app.ui.theme.Spacing

/** Dragging the handle down past this distance dismisses the overlay. */
private const val DISMISS_DRAG_DP = 80f

/** Fraction of the screen the sheet may occupy. */
private const val MAX_SHEET_HEIGHT_FRACTION = 0.6f

/** Top corners only; the same 20dp radius as [MaterialTheme.shapes.large]. */
private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/**
 * The bottom result sheet drawn inside the overlay: header, word chips and dictionary entry.
 *
 * Everything above the sheet stays as scrim; tapping it dismisses the overlay, as does dragging
 * the handle down past [DISMISS_DRAG_DP]. Chip selection crossfades the entry; empty and error
 * results collapse the body to one muted line plus "Select again".
 */
@Composable
fun ResultSheet(
    result: LookupResult,
    onDismiss: () -> Unit,
    onSelectAgain: () -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(0) }
    val maxSheetHeight = LocalConfiguration.current.screenHeightDp.dp * MAX_SHEET_HEIGHT_FRACTION

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim above the sheet: a tap dismisses the overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheetHeight)
                .navigationBarsPadding(),
            shape = SheetShape,
            color = MaterialTheme.colorScheme.surface
        ) {
            val dismissDragPx = with(LocalDensity.current) { DISMISS_DRAG_DP.dp.toPx() }
            var dragDistance by remember { mutableStateOf(0f) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Drag-to-dismiss on the handle/header; the scrollable entry area below wins
                    // the gesture when the user scrolls there, as intended.
                    .pointerInput(dismissDragPx) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragDistance += amount
                            },
                            onDragEnd = {
                                val dragged = dragDistance
                                dragDistance = 0f
                                if (dragged > dismissDragPx) onDismiss()
                            },
                            onDragCancel = { dragDistance = 0f }
                        )
                    }
                    .padding(horizontal = Spacing.l)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.s),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = result.ocrText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("text", result.ocrText))
                        Toast.makeText(context, R.string.lookup_toast_copied, Toast.LENGTH_SHORT)
                            .show()
                    }) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.lookup_cd_copy),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onSelectAgain) {
                        Icon(
                            Icons.Rounded.Gesture,
                            contentDescription = stringResource(R.string.lookup_cd_select_again),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.lookup_cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                when {
                    result.error != null ->
                        EmptyState(stringResource(R.string.lookup_dict_error, result.error), onSelectAgain)
                    result.ocrText.isBlank() -> EmptyState(stringResource(R.string.lookup_no_text), onSelectAgain)
                    result.words.isEmpty() -> EmptyState(stringResource(R.string.lookup_no_words), onSelectAgain)
                    else -> {
                        ChipRow(
                            words = result.words,
                            selected = selected,
                            onSelect = { selected = it }
                        )
                        Spacer(Modifier.height(Spacing.s))
                        Crossfade(
                            targetState = selected,
                            animationSpec = tween(150),
                            label = "entry",
                            modifier = Modifier.weight(1f, fill = false)
                        ) { index ->
                            EntryColumn(result.words[index])
                        }
                    }
                }
            }
        }
    }
}

/** One muted line plus "Select again"; replaces the word chips and entry on empty/error results. */
@Composable
private fun EmptyState(message: String, onSelectAgain: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))
        TextButton(onClick = onSelectAgain) {
            Text(stringResource(R.string.lookup_select_again))
        }
    }
}

/** Horizontally scrolling chips of the scanned words; the selected one is filled primary. */
@Composable
private fun ChipRow(words: List<ScannedWord>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth()
    ) {
        itemsIndexed(words) { index, word ->
            val isSelected = index == selected
            Surface(
                onClick = { onSelect(index) },
                shape = MaterialTheme.shapes.large,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = word.surface,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s)
                )
            }
        }
    }
}

/** Dictionary entry for the selected word's first match; scrolls when long. */
@Composable
private fun EntryColumn(word: ScannedWord) {
    val entry = word.entries.firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = Spacing.s, bottom = Spacing.l)
    ) {
        if (entry == null) {
            // WordScanner only emits surfaces with real entries; this is a defensive fallback.
            Text(
                text = word.surface,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            return
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.primary,
                style = MaterialTheme.typography.displaySmall,
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
                        style = MaterialTheme.typography.labelSmall,
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
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (word.baseForm != word.surface) {
            Text(
                text = stringResource(R.string.lookup_base_form, word.surface),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (entry.pos.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = entry.pos.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.s))
        entry.glosses.forEachIndexed { index, gloss ->
            Text(
                text = "${index + 1}. $gloss",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        if (word.entries.size > 1) {
            OtherMatches(word)
        }
    }
}

/**
 * Collapsed "Other matches (N)" row expanding to the runner-up entries: headword + reading on one
 * line, the first two glosses below. JMdict ships no frequency ranks, so homographs of one
 * surface cannot be disambiguated reliably — show them instead of hiding them.
 */
@Composable
private fun OtherMatches(word: ScannedWord) {
    var expanded by remember { mutableStateOf(false) }
    val extras = word.entries.drop(1)

    Spacer(Modifier.height(Spacing.m))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.lookup_other_matches, extras.size),
            style = MaterialTheme.typography.bodyMedium,
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
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (alternative.kana.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        text = alternative.kana.joinToString("・"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            alternative.glosses.take(2).forEach { gloss ->
                Text(
                    text = gloss,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}