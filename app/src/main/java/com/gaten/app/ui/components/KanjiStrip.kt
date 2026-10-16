/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.lookup.SheetType
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing

/**
 * The distinct kanji of [text], in order, one string per code point.
 *
 * Iterating code points rather than Chars is what keeps a supplementary-plane kanji such as 𠮷
 * whole: as Chars it is a surrogate pair, and `Character.isIdeographic` on half of it says no.
 */
internal fun kanjiIn(text: String): List<String> {
    val found = LinkedHashSet<String>()
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (Character.isIdeographic(codePoint)) found.add(String(Character.toChars(codePoint)))
        index += Character.charCount(codePoint)
    }
    return found.toList()
}

/**
 * The kanji of the word the entry is showing, each one a tap away from its WaniKani page.
 *
 * Drawn from the headword the entry is already headed with, and only when it has a kanji at all: a
 * kana-only word has nothing to look up. Every kanji is tappable whatever WaniKani covers, because
 * what WaniKani has is not known before the tap; a character it does not cover says so there.
 */
@Composable
internal fun KanjiStrip(
    headword: String,
    onKanji: (String) -> Unit,
    type: SheetType,
    modifier: Modifier = Modifier
) {
    KanjiStrip(remember(headword) { kanjiIn(headword) }, onKanji, type, modifier = modifier)
}

/**
 * The same strip for a caller that already knows which characters to offer — the review screen,
 * which keeps only the kanji its question side already shows, so a tap can never reveal part of the
 * answer. The characters are drawn in the order given, one chip each, and [selected] marks the one
 * whose details are open underneath: the chip that opened them is also the chip that closes them.
 *
 * [modifier] lands on the chip row itself, which is what a caller wanting to scroll the strip into
 * view measures; the label above it is part of the strip's own spacing.
 */
@Composable
internal fun KanjiStrip(
    characters: List<String>,
    onKanji: (String) -> Unit,
    type: SheetType,
    selected: String? = null,
    modifier: Modifier = Modifier
) {
    if (characters.isEmpty()) return

    Spacer(Modifier.height(Spacing.s))
    Text(
        text = stringResource(R.string.wk_strip_label),
        style = type.small,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.xs))
    Row(
        modifier = Modifier.fillMaxWidth().then(modifier).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        characters.forEach { character ->
            val description = stringResource(R.string.wk_cd_kanji, character)
            val open = character == selected
            val interactions = remember { MutableInteractionSource() }
            val container by animateColorAsState(
                if (open) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                tween(Motion.SHORT), label = "kanjiChipColor"
            )
            val check by animateFloatAsState(
                if (open) 1f else 0f, Motion.overshoot, label = "kanjiChipCheck"
            )
            Surface(
                onClick = { onKanji(character) },
                interactionSource = interactions,
                shape = MaterialTheme.shapes.small,
                color = container,
                // The open character is the only one ringed: the chip that opened the details below
                // is the one that closes them, and the ring says which one that is.
                border = if (open) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
                },
                modifier = Modifier.pressScale(interactions).semantics {
                    contentDescription = description
                    this.selected = open
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (open) {
                        Icon(
                            Icons.Rounded.Check, contentDescription = null,
                            modifier = Modifier.size(18.dp).graphicsLayer { scaleX = check; scaleY = check }
                        )
                    }
                    Text(
                        text = character,
                        style = type.headword,
                        color = if (open) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}
