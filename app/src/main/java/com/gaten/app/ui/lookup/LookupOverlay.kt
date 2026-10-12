/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gaten.app.R
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.ScanState
import com.gaten.app.ocr.ScreenHits
import com.gaten.app.ocr.ScreenTextScanner
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Gaten"

/** How far from a character a tap may land and still hit it, converted to bitmap px. */
private val TapTolerance = 24.dp

/** [ocr.Box]'s bounds as the compose [Rect] the overlay highlights. */
private fun Box.toRect(): Rect =
    Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

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
 * On open the whole capture is recognised once in the background; from then on a tap on a
 * character — or a lasso, whose box is answered from the index — needs no further OCR, and the
 * region OCR path stays as the fallback while the scan runs and when it is unavailable. Words
 * are tokenised against the offline JMdict and shown in a bottom sheet. The dictionary is
 * opened once and kept warm for the whole overlay lifetime.
 */
@Composable
fun LookupOverlay(
    screenshot: Bitmap?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tapTolerancePx = with(LocalDensity.current) { TapTolerance.toPx() }

    var visible by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.Selecting) }
    var points by remember { mutableStateOf(emptyList<Offset>()) }
    var frozenTrail by remember { mutableStateOf<Path?>(null) }
    var frozenBox by remember { mutableStateOf<Rect?>(null) }
    var result by remember { mutableStateOf<LookupResult?>(null) }

    // One full-screen OCR pass on open, keyed to the screenshot so each new capture rescans. It
    // answers taps and lassos from the index for the rest of the session; the region OCR path
    // stays as the fallback while it runs and when it is unavailable.
    val scanState by produceState<ScanState>(ScanState.Scanning, screenshot) {
        val bmp = screenshot
        if (bmp == null) {
            value = ScanState.Unavailable
            return@produceState
        }
        Log.d(TAG, "Screen scan started")
        val startedAt = SystemClock.elapsedRealtime()
        val screen = withContext(Dispatchers.Default) { ScreenTextScanner.scan(context, bmp) }
        value = screen?.let { ScanState.Ready(it) } ?: ScanState.Unavailable
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (screen == null) {
            Log.d(TAG, "Screen scan unavailable in $elapsed ms")
        } else {
            Log.d(TAG, "Screen scan ready in $elapsed ms (${screen.lines.size} lines)")
        }
    }

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
            result = try {
                val repo = awaitRepo()
                val screen = (scanState as? ScanState.Ready)?.screen
                val lookup = if (screen == null) {
                    Log.d(TAG, "Circle path: region")
                    runLookup(context, bmp, box, repo)
                } else {
                    Log.d(TAG, "Circle path: index")
                    withContext(Dispatchers.Default) {
                        lookupFromSelection(
                            screen,
                            Box(
                                box.left.toInt(),
                                box.top.toInt(),
                                box.right.toInt(),
                                box.bottom.toInt()
                            ),
                            lookup = { term -> repo?.lookup(term) ?: emptyList() }
                        )
                    }
                }
                val failure = (repoState as? RepoState.Failed)?.message
                if (failure != null) lookup.copy(error = failure) else lookup
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Dismissing mid-lookup closes the dictionary under the blocking query, which
                // cannot be interrupted; the overlay is already going away, so just report it.
                Log.w(TAG, "Lookup failed", e)
                LookupResult(ocrText = "", words = emptyList(), error = e.message ?: e.javaClass.simpleName)
            }
            phase = Phase.Result
        }
    }

    /** A tap while selecting: the word under the finger when the index is ready, dismiss otherwise. */
    fun onTap(at: Offset) {
        val bmp = screenshot
        val screen = (scanState as? ScanState.Ready)?.screen
        if (bmp == null || screen == null) {
            close()
            return
        }
        val x = at.x.toInt()
        val y = at.y.toInt()
        val tolerance = tapTolerancePx.toInt()
        val hit = ScreenHits.hitTest(screen, x, y, tolerance)
        if (hit == null) {
            Log.d(TAG, "Tap missed: no character within $tolerance px")
            close()
            return
        }
        Log.d(TAG, "Tap hit line ${hit.lineIndex} char ${hit.charIndex}")
        frozenTrail = null
        // The tapped character's own box until the word around it has been resolved.
        frozenBox = screen.lines[hit.lineIndex].charBoxes[hit.charIndex].toRect()
        phase = Phase.Scanning
        scope.launch {
            val outcome: LookupResult? = try {
                val repo = awaitRepo()
                if (repo == null) {
                    // Same as the lasso: a dictionary that failed to open is an error result.
                    LookupResult(
                        ocrText = "",
                        words = emptyList(),
                        error = (repoState as? RepoState.Failed)?.message
                    )
                } else {
                    val tap = withContext(Dispatchers.Default) {
                        lookupFromTap(
                            screen,
                            x,
                            y,
                            tolerance,
                            lookup = { term -> repo.lookup(term) }
                        )
                    }
                    if (tap == null) null else {
                        frozenBox = tap.wordBox.toRect()
                        tap.result
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tap lookup failed", e)
                LookupResult(ocrText = "", words = emptyList(), error = e.message ?: e.javaClass.simpleName)
            }
            if (outcome == null) {
                // Unreachable while the hit test above hit, but a miss is a dismissal, not a result.
                close()
            } else {
                result = outcome
                phase = Phase.Result
            }
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
                screen = (scanState as? ScanState.Ready)?.screen,
                points = points,
                frozenTrail = frozenTrail,
                frozenBox = frozenBox,
                interactive = phase == Phase.Selecting,
                trailFading = phase == Phase.Result,
                onLassoStart = { points = listOf(it) },
                onLassoUpdate = { points = points + it },
                onLassoEnd = { startLookup() },
                onLassoCancel = { points = emptyList() },
                onTap = { onTap(it) }
            )

            AnimatedVisibility(
                visible = phase != Phase.Result,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                StatusPill(phase = phase, scanState = scanState, onClose = { close() })
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

/** The one pill at the top: the ready hint once the index is in, the instruction before it, and "Reading…" with a spinner while a lookup or the scan itself runs. */
@Composable
private fun StatusPill(phase: Phase, scanState: ScanState, onClose: () -> Unit) {
    val spinner = phase == Phase.Scanning || scanState == ScanState.Scanning
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
                text = stringResource(
                    when {
                        phase == Phase.Scanning -> R.string.lookup_reading
                        scanState is ScanState.Ready -> R.string.lookup_hint_ready
                        else -> R.string.lookup_hint
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.s)
            )
            Spacer(Modifier.width(Spacing.s))
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (spinner) {
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