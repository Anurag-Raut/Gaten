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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.OcrPreferences
import com.gaten.app.ocr.ScanState
import com.gaten.app.ocr.ScreenHits
import com.gaten.app.ocr.ScreenLineReader
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.ScreenTextScanner
import com.gaten.app.ocr.prefetchOrder
import com.gaten.app.ui.overlay.ViewMapping
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "Gaten"

/** How far from a visible text box a tap may land and still hit it, converted to bitmap px. */
private val TapTolerance = 24.dp

/**
 * Lines one gesture waits for. A tap touches one line and a lasso two or three; beyond that the
 * wait would be longer than the answer is worth, and the rest keep ML Kit's text.
 */
private const val MAX_GESTURE_LINES = 3

/** Lifecycle of the offline dictionary handle. */
private sealed interface RepoState {
    object Loading : RepoState
    data class Ready(val repo: JmdictRepository) : RepoState
    data class Failed(val message: String) : RepoState
}

/** The three states of one overlay session. */
private enum class Phase { Selecting, Scanning, Result }

/**
 * The sheet slides in from its own docked edge and back out that way, over the same timings as
 * ever. A side panel travels its own width — [fraction] of the screen — while the bottom panel
 * keeps the full-screen slide it has always had, so the portrait animation is untouched.
 */
private fun sheetEnter(dock: SheetDock, fraction: Float): EnterTransition = when (dock) {
    SheetDock.Bottom -> slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it }
    SheetDock.Left ->
        slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { -(it * fraction).toInt() }
    SheetDock.Right ->
        slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { (it * fraction).toInt() }
}

/** @see sheetEnter */
private fun sheetExit(dock: SheetDock, fraction: Float): ExitTransition = when (dock) {
    SheetDock.Bottom -> slideOutVertically(tween(150, easing = FastOutSlowInEasing)) { it }
    SheetDock.Left ->
        slideOutHorizontally(tween(150, easing = FastOutSlowInEasing)) { -(it * fraction).toInt() }
    SheetDock.Right ->
        slideOutHorizontally(tween(150, easing = FastOutSlowInEasing)) { (it * fraction).toInt() }
}

/**
 * Full-screen lasso selector plus the result sheet, shown over a frozen screenshot.
 *
 * On open the whole capture is detected and read once by ML Kit, in the background; the overlay is
 * usable the moment that index exists, and manga-ocr then reads its lines behind it
 * ([ScreenLineReader]), replacing each one's text as it lands. Ready is ML Kit's index: the
 * optional meiki model runs afterwards, entirely behind the ready overlay, and the lines it finds
 * beyond ML Kit's are appended to the live index and read like every other line. A tap waits for the
 * line it touched — and only that one — before answering from the index. A circle is read from its
 * own crop first, whatever the index holds; the lines it covered are read after that, and only then,
 * and supplement the crop's words. Words are tokenised against the offline JMdict and shown in a
 * bottom sheet. The dictionary is opened once and kept warm for the whole overlay lifetime.
 */
@Composable
fun LookupOverlay(
    screenshot: Bitmap?,
    /**
     * The capture's pixels -> this window's pixels. The frozen frame is drawn through it, and so is
     * every box and gesture: it is identity while the window is the capture's own size and origin.
     */
    mapping: ViewMapping,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The hit tests are in the capture's pixels, so the tolerance is too: one view pixel is worth
    // mapping.minScale of them, and the same physical slack has to follow the window's scale.
    val tapTolerancePx = with(LocalDensity.current) {
        (TapTolerance.toPx() / mapping.minScale).roundToInt()
    }
    // Debug aid, read for each overlay: the recognised boxes themselves, over the frozen frame.
    val showCharBoxes = OcrPreferences.showCharBoxes(context)

    var visible by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.Selecting) }
    var points by remember { mutableStateOf(emptyList<Offset>()) }
    var frozenTrail by remember { mutableStateOf<Path?>(null) }
    var frozenBox by remember { mutableStateOf<Rect?>(null) }
    var result by remember { mutableStateOf<LookupResult?>(null) }

    // One full-screen OCR pass on open, keyed to the screenshot so each new capture rescans. It
    // answers taps from the index for the rest of the session, and a circle from its own crop
    // whatever the index holds.
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

    // The pill at the top is up only while it has something to say: "Reading…" for as long as any
    // work runs, then — once the scan has settled — one of the two short notices. [pillText] holds
    // the text last shown, so the pill still has something to draw while it fades out.
    var pillText by remember { mutableStateOf(R.string.lookup_reading) }
    var noticeUp by remember { mutableStateOf(false) }
    val busy = phase == Phase.Scanning || scanState == ScanState.Scanning

    // A scan that settled without an index to tap says so for a couple of seconds: no text on the
    // screen at all, or no scan at all — and while the latter holds, the circle-to-read path is
    // still the way in.
    LaunchedEffect(scanState) {
        val settled = scanState
        val notice = when {
            settled is ScanState.Ready && settled.screen.lines.isEmpty() -> R.string.lookup_no_text
            settled == ScanState.Unavailable -> R.string.lookup_hint
            else -> null
        } ?: return@LaunchedEffect
        pillText = notice
        noticeUp = true
        delay(2000)
        noticeUp = false
    }

    // ML Kit's index is what the overlay goes Ready with, instantly. The reader then replaces each
    // line's text as manga-ocr reads it, and [screen] is that live index: the gestures and the
    // faint highlights use it, so a line read a moment ago is already the better text.
    val scannedScreen = (scanState as? ScanState.Ready)?.screen
    val reader = remember(scannedScreen, screenshot) {
        if (scannedScreen == null || screenshot == null) {
            null
        } else {
            ScreenLineReader(
                screen = scannedScreen,
                readLine = { line ->
                    JapaneseOcr.readLineWithMangaOcr(context, screenshot, line.bounds)
                },
                onLog = { message -> Log.d(TAG, message) }
            )
        }
    }
    // Dropped when the overlay closes or a new screenshot replaces the index.
    DisposableEffect(reader) { onDispose { reader?.close() } }

    val readFlow: StateFlow<ScreenText?> = remember(reader) { reader?.text ?: MutableStateFlow(null) }
    val readScreen by readFlow.collectAsState()
    val screen = readScreen ?: scannedScreen

    // Read the screen in the background as soon as the index is in, biggest boxes first.
    LaunchedEffect(reader) {
        val current = screen ?: return@LaunchedEffect
        reader?.prefetch(prefetchOrder(current))
    }

    // Meiki is a second, slower pass over the capture, run entirely behind the ready overlay: the
    // reader was built from ML Kit's index alone, and the lines meiki finds beyond it join that
    // index here as empty placeholders (append is the only structural change the index takes, so
    // the gesture line-indices already handed out stay valid). The effect is keyed to the reader,
    // so a disposed reader — overlay closed, screenshot replaced — never receives them; when lines
    // were added they are prefetched like the rest, and read or dropped by the same rules.
    LaunchedEffect(reader, screenshot) {
        val current = reader ?: return@LaunchedEffect
        val index = scannedScreen ?: return@LaunchedEffect
        val bmp = screenshot ?: return@LaunchedEffect
        val added = withContext(Dispatchers.Default) {
            ScreenTextScanner.meikiLines(context, bmp, index.lines)
        }
        if (added.isNotEmpty()) {
            current.append(added)
            current.prefetch(prefetchOrder(current.text.value))
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

    /**
     * The index with [lineIndices] read by manga-ocr: waits for those lines (bounded by the
     * reader's own timeout, so a gesture always gets an answer) and re-prioritises the rest of the
     * screen from there. The rest keeps ML Kit's text.
     */
    suspend fun readGestureLines(from: ScreenText, lineIndices: List<Int>): ScreenText {
        val active = reader ?: return from
        val touched = lineIndices.distinct().take(MAX_GESTURE_LINES)
        for (lineIndex in touched) active.read(lineIndex)
        active.prefetch(prefetchOrder(active.text.value, fromLine = touched.firstOrNull()))
        return active.text.value
    }

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
        // The trail is drawn in view pixels; the index, the hit tests and the selection crop are all in
        // the capture's pixels, so the selection is converted on the way in.
        val bitmapBox = mapping.toBitmap(box)
        frozenTrail = buildTrailPath(drawn)
        frozenBox = box
        phase = Phase.Scanning
        scope.launch {
            result = try {
                val repo = awaitRepo()
                val selection = Box(
                    bitmapBox.left.toInt(),
                    bitmapBox.top.toInt(),
                    bitmapBox.right.toInt(),
                    bitmapBox.bottom.toInt()
                )
                // The circle is read from its own crop inside runLookup, index or no index, before
                // anything else. The lines it touched are handed over as a callback and read only
                // once the crop is in, so the answer never waits on the lines beside it; which
                // lines those are comes from the index as it stands, and the spans themselves are
                // resolved again inside runLookup, because a line that has been read no longer has
                // the same offsets.
                val live = screen
                Log.d(TAG, "Circle path: crop read (index ${if (live == null) "unavailable" else "ready"})")
                val lookup = runLookup(context, bmp, bitmapBox, repo) {
                    live?.let { readGestureLines(it, ScreenHits.linesInSelection(it, selection)) }
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

    /** Select the entire tapped text box when the index is ready; dismiss on a miss. */
    fun onTap(at: Offset) {
        val live = screen
        if (screenshot == null || live == null) {
            close()
            return
        }
        // The finger is in view pixels; the index, and everything it is hit-tested against, is in
        // the capture's pixels.
        val tapped = mapping.toBitmap(at.x, at.y)
        val x = tapped.x.toInt()
        val y = tapped.y.toInt()
        val tolerance = tapTolerancePx
        // Hit-test the same line bounds that the overlay outlines.
        val lineIndex = ScreenHits.hitTestLine(live, x, y, tolerance)
        if (lineIndex == null) {
            Log.d(TAG, "Tap missed: no text box within $tolerance px")
            close()
            return
        }
        Log.d(TAG, "Tap hit line $lineIndex")
        frozenTrail = null
        // Select the entire visible box immediately, including while OCR is running.
        frozenBox = mapping.toView(live.lines[lineIndex].bounds)
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
                    // Read only the selected line and keep the lookup on it if OCR drops it,
                    // rather than letting a nearby line take over the selection.
                    val indexed = readGestureLines(live, listOf(lineIndex))
                    val tap = withContext(Dispatchers.Default) {
                        lookupFromTap(
                            indexed.copy(lines = listOf(indexed.lines[lineIndex])),
                            x,
                            y,
                            tolerance,
                            lookup = { term -> repo.lookup(term) }
                        )
                    }
                    if (tap == null) null else {
                        frozenBox = mapping.toView(tap.selectionBox)
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
                // The reader dropped the selected line because it contained no Japanese.
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
        // The constraints are the full-screen overlay in view pixels — the same space the selection
        // and the highlight boxes are placed in — so the panel can be placed against the word it
        // explains.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            LassoLayer(
                screenshot = screenshot,
                screen = screen,
                mapping = mapping,
                showCharBoxes = showCharBoxes,
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

            // Pure drawing, over the selection canvas and under the pill: the ring around the edge
            // of the display, telling work apart from idle without ever taking a touch.
            EdgeOutline(busy = busy, modifier = Modifier.fillMaxSize())

            AnimatedVisibility(
                visible = busy || noticeUp,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(150)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                StatusPill(
                    text = if (busy) R.string.lookup_reading else pillText,
                    busy = busy
                )
            }

            // Chosen once per result, from the box this session highlighted, so the panel never
            // jumps from one edge to the other while it is open.
            val sheetLayout = remember(
                result,
                frozenBox,
                constraints.maxWidth,
                constraints.maxHeight
            ) {
                chooseSheetLayout(
                    anchor = frozenBox,
                    screenWidth = constraints.maxWidth.toFloat(),
                    screenHeight = constraints.maxHeight.toFloat()
                )
            }

            AnimatedVisibility(
                visible = phase == Phase.Result && result != null,
                enter = sheetEnter(sheetLayout.dock, sheetLayout.fraction),
                exit = sheetExit(sheetLayout.dock, sheetLayout.fraction)
            ) {
                result?.let { state ->
                    ResultSheet(
                        result = state,
                        onDismiss = { close() },
                        onSelectAgain = { selectAgain() },
                        layout = sheetLayout
                    )
                }
            }
        }
    }
}

/**
 * The one pill at the top: "Reading…" with a spinner for as long as work runs — the screen scan or
 * a gesture lookup — and a short notice in its place once the scan has settled. It is only up while
 * it has something to say, so it never covers the text the reader is selecting.
 */
@Composable
private fun StatusPill(text: Int, busy: Boolean) {
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
                end = Spacing.l,
                bottom = Spacing.xs
            )
        ) {
            Text(
                text = stringResource(text),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = Spacing.s)
            )
            if (busy) {
                Spacer(Modifier.width(Spacing.s))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}