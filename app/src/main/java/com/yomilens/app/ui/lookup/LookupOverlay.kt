/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yomilens.app.ui.lookup

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yomilens.app.R
import com.yomilens.app.dict.JmdictRepository
import com.yomilens.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "YomiLens"

/** Lifecycle of the offline dictionary handle. */
private sealed interface RepoState {
    object Loading : RepoState
    data class Ready(val repo: JmdictRepository) : RepoState
    data class Failed(val message: String) : RepoState
}

/** The three states of one overlay session. */
private enum class Phase { Selecting, Scanning, Result }

/**
 * Full-screen lasso selector plus the result sheet, shown over a frozen screenshot.
 *
 * The user draws a freehand loop over the capture; the trail's bounding box is cropped, OCR'd
 * on-device, tokenised against the offline JMdict and shown in a bottom sheet. The dictionary is
 * opened once and kept warm for the whole overlay lifetime.
 */
@Composable
fun LookupOverlay(
    screenshot: Bitmap?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.Selecting) }
    var points by remember { mutableStateOf(emptyList<Offset>()) }
    var frozenTrail by remember { mutableStateOf<Path?>(null) }
    var frozenBox by remember { mutableStateOf<Rect?>(null) }
    var result by remember { mutableStateOf<LookupResult?>(null) }

    // Open the offline dictionary once and keep it warm for the whole overlay lifetime.
    val repoState by produceState<RepoState>(RepoState.Loading, context) {
        value = try {
            RepoState.Ready(JmdictRepository.open(context))
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary open failed", e)
            RepoState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
    val readyRepo = (repoState as? RepoState.Ready)?.repo
    DisposableEffect(readyRepo) {
        onDispose { readyRepo?.close() }
    }

    LaunchedEffect(Unit) { visible = true }

    // Fade out before handing control back, so closing never snaps.
    fun close() {
        visible = false
        scope.launch {
            delay(160)
            onDismiss()
        }
    }

    // Suspend until the dictionary has finished opening (or failed).
    suspend fun awaitRepo(): JmdictRepository? {
        val current = repoState
        val settled = if (current is RepoState.Loading) {
            snapshotFlow { repoState }.first { it !is RepoState.Loading }
        } else {
            current
        }
        return (settled as? RepoState.Ready)?.repo
    }

    BackHandler { close() }

    /** Turn a finished lasso into a lookup; degenerate gestures are ignored. */
    fun startLookup() {
        val drawn = points
        points = emptyList()
        val box = boundingBoxOf(drawn)
        val bmp = screenshot
        if (bmp == null || box == null ||
            box.width < MIN_SELECTION_PX || box.height < MIN_SELECTION_PX
        ) {
            return
        }
        frozenTrail = buildTrailPath(drawn)
        frozenBox = box
        phase = Phase.Scanning
        scope.launch {
            val repo = awaitRepo()
            val lookup = runLookup(context, bmp, box, repo)
            val failure = (repoState as? RepoState.Failed)?.message
            result = if (failure != null) lookup.copy(error = failure) else lookup
            phase = Phase.Result
        }
    }

    /** Fresh lasso: drop the result and the frozen trail, back to selecting. */
    fun selectAgain() {
        result = null
        frozenTrail = null
        frozenBox = null
        phase = Phase.Selecting
    }

    // Fade in on open and fade out on close, so the overlay never pops.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(150)),
        modifier = Modifier.zIndex(3000f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LassoLayer(
                screenshot = screenshot,
                points = points,
                frozenTrail = frozenTrail,
                frozenBox = frozenBox,
                interactive = phase != Phase.Result,
                trailFading = phase == Phase.Result,
                onLassoStart = { points = listOf(it) },
                onLassoUpdate = { points = points + it },
                onLassoEnd = { startLookup() },
                onLassoCancel = { points = emptyList() },
                onTap = { close() }
            )

            AnimatedVisibility(
                visible = phase != Phase.Result,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                StatusPill(scanning = phase == Phase.Scanning, onClose = { close() })
            }

            AnimatedVisibility(
                visible = phase == Phase.Result && result != null,
                enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it },
                exit = slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                result?.let { state ->
                    ResultSheet(
                        result = state,
                        onDismiss = { close() },
                        onSelectAgain = { selectAgain() }
                    )
                }
            }
        }
    }
}

/** The one pill at the top: instruction while selecting, "Reading…" with a spinner while scanning. */
@Composable
private fun StatusPill(scanning: Boolean, onClose: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = Spacing.m)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                start = Spacing.l,
                top = Spacing.xs,
                end = Spacing.s,
                bottom = Spacing.xs
            )
        ) {
            Text(
                text = stringResource(if (scanning) R.string.lookup_reading else R.string.lookup_hint),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.s)
            )
            Spacer(Modifier.width(Spacing.s))
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (scanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.lookup_cd_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}