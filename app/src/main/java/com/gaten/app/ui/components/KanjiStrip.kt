/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.lookup.SheetType
import com.gaten.app.ui.theme.Spacing

/** Every character here is a target, and a target is never smaller than this. */
private val KanjiTarget = 48.dp

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
 * The same strip for a caller that already knows which characters to offer. The characters are drawn
 * in the order given, one bare action each.
 *
 * Nothing here is a chip: these characters really are actions, so the accent is the honest way to
 * say so, and a tile, a border or a radius around each one would only put a container where a target
 * belongs. [modifier] lands on the row itself, which is what a caller measuring it will read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KanjiStrip(
    characters: List<String>,
    onKanji: (String) -> Unit,
    type: SheetType,
    modifier: Modifier = Modifier
) {
    if (characters.isEmpty()) return

    // The 20 dp belongs to the strip's own place in the entry: it is a section after the meanings and
    // the parts of speech, not a paragraph of them.
    Spacer(Modifier.height(Spacing.xl))
    Text(
        text = stringResource(R.string.wk_strip_label),
        style = type.caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.s))
    // Wrapping rather than scrolling sideways: the whole set stays visible on a wide screen, and a
    // word with many characters never hides one past an edge the reader has to find.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier.fillMaxWidth().then(modifier)
    ) {
        characters.forEach { character ->
            KanjiAction(character, onKanji, type)
        }
    }
}

/**
 * One character as a bare action: the glyph itself in the accent, on the sheet's own paper, with no
 * tile under it. The 4 dp above and below is what makes the target grow with the glyph at a large
 * text scale instead of the glyph outgrowing its own target.
 */
@Composable
private fun KanjiAction(character: String, onKanji: (String) -> Unit, type: SheetType) {
    val interactions = remember { MutableInteractionSource() }
    val description = stringResource(R.string.wk_cd_kanji, character)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = KanjiTarget, minHeight = KanjiTarget)
            .pressOpacity(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                onClick = { onKanji(character) }
            )
            .semantics { contentDescription = description }
            .padding(vertical = Spacing.xs)
    ) {
        Text(
            text = character,
            style = type.headword,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
