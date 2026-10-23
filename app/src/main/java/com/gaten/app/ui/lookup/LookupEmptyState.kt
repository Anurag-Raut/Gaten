/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.ui.theme.Spacing

/**
 * What the sheet shows when there is nothing to read: sparse artwork, one plain caption, and the two
 * ways out — search the dictionary by hand, or go back and circle something else.
 *
 * This is not a rail, a card or an illustrated alert. It centres one group in the body it is given
 * and scrolls that group when the body cannot hold it, so the top is always reachable: a group
 * taller than the viewport is padded and top-aligned rather than clipped by a centring that has
 * nowhere to put the overflow.
 *
 * [artwork] is one of the two marks in `res/drawable` — an empty reading frame for text that was
 * never read, a dictionary and lens for text that was read but matched nothing. Both are drawn in a
 * single neutral tone supplied here from `onSurfaceVariant`, so there is one asset per fact and both
 * themes share it.
 *
 * It expects a parent with a **bounded height** (the sheet's body area, not a scroller): that height
 * is what the group centres inside.
 */
@Composable
internal fun LookupEmptyState(
    @DrawableRes artwork: Int,
    message: String,
    onSearch: () -> Unit,
    onSelectAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    BoxWithConstraints(modifier = modifier) {
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        // The artwork steps down rather than squeezing the group: 140 dp is the mark's own size, and
        // 96 dp is what still reads in a narrow panel or a short body. It never scales with the
        // reader's text size — the type does that, and a growing picture would only crowd it.
        val artworkSize = if (viewportWidth < 240.dp || viewportHeight < 344.dp) 96.dp else 140.dp
        val groupWidth = minOf(280.dp, viewportWidth)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // At least the whole viewport tall, so the group centres when it fits; taller than
                // the viewport when it does not, which is what hands the overflow to this scroller.
                .heightIn(min = viewportHeight)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(artwork),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(minOf(artworkSize, viewportWidth))
            )
            Spacer(Modifier.size(Spacing.l))
            Text(
                text = message,
                style = type.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = groupWidth)
            )
            Spacer(Modifier.size(Spacing.l))
            Button(
                onClick = onSearch,
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.m),
                modifier = Modifier
                    .widthIn(max = groupWidth)
                    .heightIn(min = 52.dp)
            ) {
                Text(text = stringResource(R.string.lookup_search), style = type.labelLarge)
            }
            Spacer(Modifier.size(Spacing.xs))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .widthIn(max = groupWidth)
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onSelectAgain)
            ) {
                Text(
                    text = stringResource(R.string.lookup_select_again),
                    style = type.labelLarge,
                    color = colors.primary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
