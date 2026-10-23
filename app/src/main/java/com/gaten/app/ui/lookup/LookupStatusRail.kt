/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaten.app.ui.theme.Spacing

/**
 * The one surface the overlay says anything on: what it is doing, what it wants, or what went wrong.
 *
 * Two placements, one body. [FloatingStatusRail] sits over the frozen screen while the reader is
 * choosing; [InlineStatusRail] sits in the result sheet's own column, on the sheet's opaque paper,
 * once a crop has been read and the answer is empty or failed. Both are the same object: a square,
 * neutral rail marked by a 2 dp accent rule at its leading edge — a rule and the app's own paper, not
 * another rounded card floating over somebody else's screen.
 *
 * Deliberately not a Material pill: the overlay is drawn over arbitrary foreign pixels, so the rail
 * carries its own opaque backing and a hairline rather than borrowing an elevation it cannot have.
 * The accent rule is drawn behind the content at draw time, so it always spans the rail's real
 * height, whatever the message wraps to at the reader's text scale.
 */

/** The static glyph a settled rail shows in place of a spinner. */
internal enum class RailGlyph { Gesture, SearchOff, Info, Error }

/** A neutral status, or a failure the reader has to act on. */
internal enum class RailTone { Neutral, Error }

/** The rail's one action, e.g. "Select again" on a terminal result. */
internal data class RailAction(val label: String, val onClick: () -> Unit)

private fun RailGlyph.icon(): ImageVector = when (this) {
    RailGlyph.Gesture -> Icons.Rounded.Gesture
    RailGlyph.SearchOff -> Icons.Rounded.SearchOff
    RailGlyph.Info -> Icons.Rounded.Info
    RailGlyph.Error -> Icons.Rounded.ErrorOutline
}

/** What a screen reader announces for a rail: both lines, once, politely. */
private fun railDescription(text: String, supporting: String?): String =
    if (supporting == null) text else "$text. $supporting"

/**
 * The rail's body: the accent rule, the glyph or spinner, and up to two lines of text. [modifier]
 * carries the placement's own backing, perimeter and insets.
 */
@Composable
private fun RailBody(
    text: String,
    busy: Boolean,
    glyph: RailGlyph?,
    tone: RailTone,
    supporting: String?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val ruleWidth = 2.dp
    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = railDescription(text, supporting)
            }
            .drawBehind {
                // The one accent mark that says this is the app talking, in a colour that never has
                // to fight the screenshot behind it.
                drawRect(
                    color = colors.primary,
                    size = Size(ruleWidth.toPx(), size.height)
                )
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = ruleWidth + Spacing.m,
                top = Spacing.m,
                end = Spacing.m,
                bottom = Spacing.m
            )
        ) {
            when {
                busy -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary
                    )
                    Spacer(Modifier.width(Spacing.s))
                }

                glyph != null -> {
                    Icon(
                        imageVector = glyph.icon(),
                        contentDescription = null,
                        tint = if (tone == RailTone.Error) colors.error else colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(Spacing.s))
                }
            }
            Column {
                Text(
                    text = text,
                    style = type.bodyMedium,
                    color = if (tone == RailTone.Error) colors.error else colors.onSurface
                )
                if (supporting != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = supporting,
                        style = type.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The rail over the frozen screen: top centre, below the real status-bar and cutout insets, capped at
 * 360 dp so a long sentence wraps instead of stretching across a tablet, and never shorter than a
 * 48 dp touch-sized band. The caller places it and fades it; it owns only its own backing.
 */
@Composable
internal fun FloatingStatusRail(
    text: String,
    busy: Boolean,
    glyph: RailGlyph?,
    tone: RailTone,
    supporting: String?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surface,
        modifier = modifier
            .padding(horizontal = Spacing.l)
            .widthIn(max = 360.dp)
            .heightIn(min = 48.dp)
            .border(1.dp, colors.outlineVariant)
    ) {
        RailBody(text = text, busy = busy, glyph = glyph, tone = tone, supporting = supporting)
    }
}

/**
 * The same rail inside the sheet: the sheet's own paper is opaque, so this one drops the backing and
 * the perimeter and keeps the accent rule and the spacing, with 16 dp of air above and below.
 * [action] is a terminal result's one way out ("Select again") — a text action at the full 48 dp
 * touch height, not a filled button, because the sheet already has exactly one filled action.
 */
@Composable
internal fun InlineStatusRail(
    text: String,
    glyph: RailGlyph?,
    tone: RailTone,
    supporting: String?,
    action: RailAction?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.l)) {
        RailBody(
            text = text,
            busy = false,
            glyph = glyph,
            tone = tone,
            supporting = supporting
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.s))
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClick = action.onClick)
            ) {
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
