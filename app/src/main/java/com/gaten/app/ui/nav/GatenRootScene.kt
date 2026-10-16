/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.gaten.app.R
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.rememberFeedback

/**
 * A tab's own ground: its screen, and the bar between the four tabs.
 *
 * The bar is here rather than above the whole display, so it belongs to the screen the reader is on:
 * a page opened over a tab is full screen, and mid-move each scene keeps its own layout instead of
 * both being stretched by one bar appearing.
 *
 * Which tab is lit is this scene's own [tab], not whichever tab the app considers selected: while a
 * back gesture previews the screen underneath, that screen's bar has to agree with it.
 *
 * @param barVisible false while a review session owns the whole screen.
 */
@Composable
fun GatenRootScene(
    tab: GatenTab,
    onSelect: (GatenTab) -> Unit,
    barVisible: Boolean = true,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                // The bar carries the navigation-bar inset itself, so the screen above it is told
                // that inset is already spent — otherwise a screen's own Scaffold would pad for it a
                // second time and leave a strip of nothing above the bar. With the bar away there is
                // nothing to spend, and the screen pads it for itself.
                .then(
                    if (barVisible) {
                        Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                    } else {
                        Modifier
                    }
                )
        ) {
            content()
        }

        if (barVisible) {
            GatenNavigationBar(tab = tab, onSelect = onSelect)
        }
    }
}

/** The four destinations. Its labels are all the bar shows; the icons carry no description. */
@Composable
private fun GatenNavigationBar(tab: GatenTab, onSelect: (GatenTab) -> Unit) {
    // The tick belongs to a tab actually being left for another: a tap on the lit tab, or a bar
    // that redraws around a tab change made elsewhere, gives nothing back.
    val feedback = rememberFeedback()
    NavigationBar {
        GatenTab.entries.forEach { entry ->
            NavigationBarItem(
                selected = entry == tab,
                onClick = {
                    if (entry != tab) {
                        feedback.haptic(FeedbackHaptic.Tick)
                        onSelect(entry)
                    }
                },
                icon = { Icon(imageVector = entry.icon(), contentDescription = null) },
                label = { Text(stringResource(entry.labelRes())) }
            )
        }
    }
}

/** One icon per destination, so the bar and the screens behind it agree on what each tab is. */
private fun GatenTab.icon(): ImageVector = when (this) {
    GatenTab.Home -> Icons.Outlined.Home
    GatenTab.Search -> Icons.Outlined.Search
    GatenTab.Review -> Icons.Outlined.Style
    GatenTab.Settings -> Icons.Outlined.Settings
}

/** The name on each destination; the tab itself is navigation state, not copy. */
@StringRes
private fun GatenTab.labelRes(): Int = when (this) {
    GatenTab.Home -> R.string.nav_home
    GatenTab.Search -> R.string.nav_search
    GatenTab.Review -> R.string.nav_review
    GatenTab.Settings -> R.string.nav_settings
}
