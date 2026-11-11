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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * choosing; [InlineStatusRail] sits in the result sheet's own column — a dictionary failure, say —
 * on the sheet's paper. Both are the same object: an opaque neutral rail, 13 dp on every corner, with
 * a 1 dp hairline and 12 dp of padding. Nothing decorates it: there is no accent leading rule and no
 * substitute for one, because the paper, the hairline and the ink are what identify the tool over
 * somebody else's screen. The accent appears only where the tool is asking rather than reporting —
 * the spinner of real work, and the gesture glyph that asks for a selection.
 *
 * Deliberately not a Material card: the overlay is drawn over arbitrary foreign pixels, so the rail
 * carries its own opaque backing and a hairline rather than borrowing an elevation it cannot have,
 * and it never samples the screenshot behind it for contrast.
 */

/** The static glyph a settled rail shows in place of a spinner. */
internal enum class RailGlyph { Gesture, SearchOff, Info, Error }

/** A neutral status, or a failure the reader has to act on. */
internal enum class RailTone { Neutral, Error }

/** The rail's one action, e.g. "Select again" on a dictionary failure. */
internal data class RailAction(val label: String, val onClick: () -> Unit)

/** The rail's corner: the house control radius, on all four corners, in both placements. */
private val RailShape = RoundedCornerShape(13.dp)

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
 * The rail's body: the glyph or spinner, then up to two lines of text. [modifier] carries the
 * placement's own backing, perimeter and insets.
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = railDescription(text, supporting)
            }
            .padding(Spacing.m)
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
                    // The gesture glyph is the tool asking the reader for a selection, so it is the
                    // one status mark in the accent; a report about the world — nothing found, an
                    // error — stays neutral ink or error, and never borrows the accent.
                    tint = when {
                        tone == RailTone.Error -> colors.error
                        glyph == RailGlyph.Gesture -> colors.primary
                        else -> colors.onSurfaceVariant
                    },
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
                // The cause stays secondary ink even under a failure title: it is the line the
                // reader has to actually read, and painting it in the error tone twice over would
                // make the two lines compete instead of ranking them.
                Text(
                    text = supporting,
                    style = type.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The rail over the frozen screen: top centre, below the real status-bar and cutout insets, content
 * sized up to 360 dp so a long sentence wraps instead of stretching across a tablet, and never
 * shorter than a 48 dp band. The caller places it and fades it; it owns only its own backing.
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
        shape = RailShape,
        modifier = modifier
            .padding(horizontal = Spacing.l)
            .widthIn(max = 360.dp)
            .heightIn(min = 48.dp)
            .border(1.dp, colors.outlineVariant, RailShape)
    ) {
        RailBody(text = text, busy = busy, glyph = glyph, tone = tone, supporting = supporting)
    }
}

/**
 * The same rail inside the sheet, at the column's full width: the sheet's paper is already opaque, so
 * this one keeps the hairline — which is what makes an error read as a notice rather than as another
 * paragraph of the entry — and the same 13 dp corners, with 16 dp of air above and below.
 *
 * [action] is the failure's one way out ("Select again"): a text action at the full 48 dp touch
 * height inside the rail's own padding, not a filled button, because the sheet already has exactly
 * one filled action.
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
    Surface(
        color = colors.surface,
        shape = RailShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.l)
            .border(1.dp, colors.outlineVariant, RailShape)
    ) {
        Column {
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
                        .padding(horizontal = Spacing.m)
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
}
