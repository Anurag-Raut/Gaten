/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts the React Native lookup sheet inside the native overlay.
 *
 * One [LookupSheetHost.Session] is opened per [sessionKey] (the displayed result's own identity) and
 * stopped exactly once, when that session is replaced or the overlay leaves the composition. The
 * native host is never touched beyond the session: the application's shared `ReactHost` keeps the
 * main app running, so mounting and stopping a sheet is a surface, not a runtime.
 *
 * [phaseVisible] separates "there is a result" from "the sheet is on screen": the surface is still
 * mounted while the sheet plays its exit, but native publishes [LookupSheetHost.MODE_HIDDEN] so the
 * RN panel can animate away instead of blinking out with the surface. Every later [result] the same
 * session publishes — the primary crop answer arriving before the final one — rides
 * [LookupSheetHost.publish], so the RN side never re-searches and the reader's first frame is the
 * resolved result, not an empty shell.
 */
@Composable
internal fun LookupSheetView(
    sessionKey: Any?,
    result: LookupResult?,
    phaseVisible: Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onSelectAgain: () -> Unit,
    onTextEntryActive: (Boolean) -> Unit,
    onReady: () -> Unit,
    onOpenSettingsPage: (String) -> Unit
) {
    val context = LocalContext.current
    // The RN session outlives a single result publication but not the reader's next lookup: the
    // actions are kept fresh so a callback from an old surface still lands on the current overlay.
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnSelectAgain by rememberUpdatedState(onSelectAgain)
    val currentOnTextEntryActive by rememberUpdatedState(onTextEntryActive)
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnOpenSettingsPage by rememberUpdatedState(onOpenSettingsPage)
    val actions = remember {
        object : LookupSheetHost.Actions {
            override fun onClose() = currentOnClose()
            override fun onSelectAgain() = currentOnSelectAgain()
            override fun onTextEntryActive(active: Boolean) = currentOnTextEntryActive(active)
            override fun onReady() = currentOnReady()
            override fun onOpenSettingsPage(page: String) = currentOnOpenSettingsPage(page)
        }
    }

    // One identity per lookup session. It rides both the surface's initial props and every
    // published state, so a surface from an earlier lookup can never apply a newer lookup's state
    // (and a delayed read can never overwrite a newer event): stale maps are recognised and dropped.
    val sessionId = remember(sessionKey) { java.util.UUID.randomUUID().toString() }

    var session by remember { mutableStateOf<LookupSheetHost.Session?>(null) }

    // Open once per lookup session and stop exactly once. `initialState` carries the first result so
    // a surface that mounts before the first publish still has something to draw.
    DisposableEffect(sessionKey) {
        session = if (sessionKey == null) {
            null
        } else {
            LookupSheetHost.open(
                context,
                LookupSheetHost.initialState(
                    sessionId = sessionId,
                    mode = if (phaseVisible) LookupSheetHost.MODE_SHEET else LookupSheetHost.MODE_HIDDEN,
                    source = result?.ocrText.orEmpty(),
                    words = result?.words.orEmpty()
                ),
                actions
            )
        }
        onDispose {
            session?.stop()
            LookupSheetHost.close()
            session = null
        }
    }

    // Every publication after the first rides the event, including this session's own result
    // changing under it (a late OCR reading replacing the primary one).
    LaunchedEffect(session, result, phaseVisible) {
        if (session == null) return@LaunchedEffect
        val words = result?.words.orEmpty()
        val empty = words.isEmpty()
        LookupSheetHost.publish(
            sessionId = sessionId,
            mode = when {
                !phaseVisible -> LookupSheetHost.MODE_HIDDEN
                empty -> LookupSheetHost.MODE_EMPTY
                else -> LookupSheetHost.MODE_SHEET
            },
            source = result?.ocrText.orEmpty(),
            words = words,
            emptyKind = if (empty) {
                if (result?.ocrText.isNullOrBlank()) {
                    LookupSheetHost.EMPTY_NO_TEXT
                } else {
                    LookupSheetHost.EMPTY_NO_MATCHES
                }
            } else {
                null
            }
        )
    }

    // A session's view is a real View, so it is keyed so a replaced session cannot be reused.
    val live = session ?: return
    key(sessionKey) {
        AndroidView(factory = { live.view }, modifier = modifier)
    }
}
