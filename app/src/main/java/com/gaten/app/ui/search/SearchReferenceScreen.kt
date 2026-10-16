/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.lookup.sheetType
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.wanikani.WaniKaniDetailPanel
import com.gaten.app.wanikani.WaniKaniTarget

/**
 * One WaniKani reference page on a screen of its own: the kanji the reader tapped out of a word, or
 * a radical of that kanji.
 *
 * The page is [WaniKaniDetailPanel] — the same panel the overlay's result sheet and the review screen
 * draw — so nothing here decides what WaniKani sent or how it is cached. The panel brings its own
 * heading and its own back arrow, which is why this screen adds no top bar: what the page is called
 * is not known until the lookup answers, so a title above it could only repeat it.
 *
 * [target] is the whole key of the page: a new target restarts the lookup, so the route carrying it
 * is what makes this a different screen.
 *
 * @param onBack where the panel's back arrow goes, back to the page that opened this one.
 * @param onRadical where a tapped component of a kanji goes, with the WaniKani subject id.
 * @param onSettings where the panel's own "connect" notices go, for the WaniKani token.
 */
@Composable
fun SearchReferenceScreen(
    target: WaniKaniTarget,
    onBack: () -> Unit,
    onRadical: (Int) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val feedback = rememberFeedback()
    Scaffold(modifier = modifier) { padding ->
        WaniKaniDetailPanel(
            target = target,
            onBack = onBack,
            onRadical = { id ->
                // One tick for choosing a component; the panel's own buttons give nothing back.
                feedback.haptic(FeedbackHaptic.Tick)
                onRadical(id)
            },
            type = sheetType(compact = false),
            compact = false,
            // A screen of its own: the panel owns its arrow, and its page scrolls inside it.
            showBack = true,
            scrollable = true,
            onOpenSettings = onSettings,
            modifier = Modifier.padding(padding).fillMaxSize()
        )
    }
}
