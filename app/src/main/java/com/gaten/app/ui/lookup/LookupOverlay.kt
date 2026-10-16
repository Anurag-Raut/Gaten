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
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gaten.app.R
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.MangaOcrEngine
import com.gaten.app.ocr.ScanState
import com.gaten.app.ocr.ScreenHits
import com.gaten.app.ocr.ScreenLineReader
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.ScreenTextScanner
import com.gaten.app.ocr.prefetchOrder
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.overlay.ViewMapping
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
 * Lines a circle waits for after reading its crop; beyond three the wait would be longer than
 * the answer is worth, and the rest keep their indexed text.
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
 * The sheet slides in from its own docked edge and back out that way. The transitions travel the
 * measured panel's own size — they are applied to the sheet Surface via [Modifier.animateEnterExit],
 * so the portrait panel moves its own height and a side panel its own width, never the whole
 * screen — over polished timings: ~260ms to open, ~200ms to leave.
 */
private fun sheetEnter(dock: SheetDock): EnterTransition = when (dock) {
    SheetDock.Bottom -> slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it }
    SheetDock.Left -> slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it }
    SheetDock.Right -> slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { it }
}

/** @see sheetEnter */
private fun sheetExit(dock: SheetDock): ExitTransition = when (dock) {
    SheetDock.Bottom -> slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { it }
    SheetDock.Left -> slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { -it }
    SheetDock.Right -> slideOutHorizontally(tween(200, easing = FastOutSlowInEasing)) { it }
}

/**
 * Full-screen lasso selector plus the result sheet, shown over a frozen screenshot.
 *
 * On open the whole capture is detected and read once by ML Kit, in the background; the overlay is
 * usable the moment that index exists, and manga-ocr then reads its lines behind it
 * ([ScreenLineReader]), replacing each one's text as it lands. Both gestures read
 * their exact crop first: the tapped line's bounds or the circle's selection. The index supplements
 * that reading, restricted to the tapped line or the lines covered by the circle. Words are
 * tokenised against the offline JMdict and shown in a bottom sheet. The dictionary is opened once
 * and kept warm for the whole overlay lifetime.
 */
@Composable
fun LookupOverlay(
    screenshot: Bitmap?,
    /**
     * The capture's pixels -> this window's pixels. The frozen frame is drawn through it, and so is
     * every box and gesture: it is identity while the window is the capture's own size and origin.
     */
    mapping: ViewMapping,
    earlyScan: Deferred<ScreenText?>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val feedback = rememberFeedback()
    // The hit tests are in the capture's pixels, so the tolerance is too: one view pixel is worth
    // mapping.minScale of them, and the same physical slack has to follow the window's scale.
    val tapTolerancePx = with(LocalDensity.current) {
        (TapTolerance.toPx() / mapping.minScale).roundToInt()
    }
    // The overlay's fade and the sheet's slide are coordinated through these two transition
    // states: dismissals await the real transitions (a MutableTransitionState's currentState only
    // settles once every nested enter/exit child — the sheet's animateEnterExit slide included —
    // has finished) instead of a fixed delay. The sheet's AnimatedVisibility animates nothing
    // itself; its Surface carries the slide.
    val overlayState = remember { MutableTransitionState(false) }
    val sheetState = remember { MutableTransitionState(false) }
    var closing by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(Phase.Selecting) }
    var points by remember { mutableStateOf(emptyList<Offset>()) }
    var frozenBox by remember { mutableStateOf<Rect?>(null) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    // The lookup session the displayed result belongs to, and the anchor box (view px) its dock
    // was chosen from. Both change only when a result is published, so the exiting sheet keeps
    // its layout even after selectAgain clears the frozen selection, and the sheet instance
    // survives the primary->final publication of one lookup.
    var displayedResultSession by remember { mutableStateOf<Any?>(null) }
    var feedbackSession by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(displayedResultSession, result?.words?.isNotEmpty()) {
        val session = displayedResultSession
        if (session != null && result?.words?.isNotEmpty() == true && feedbackSession !== session) {
            feedbackSession = session
            feedback.haptic(FeedbackHaptic.Lookup)
        }
    }
    var resultAnchor by remember { mutableStateOf<Rect?>(null) }
    var lookupJob by remember { mutableStateOf<Job?>(null) }
    val cropCache = remember(screenshot) { ExactCropOcrCache() }
    DisposableEffect(cropCache) { onDispose { cropCache.clear() } }

    // The controller starts one scan as soon as it accepts the screenshot. Await that same pass;
    // the overlay's first-frame drawing and outline reveal never gate detector submission.
    val scanState by produceState<ScanState>(ScanState.Scanning, screenshot, earlyScan) {
        val bmp = screenshot
        if (bmp == null) {
            value = ScanState.Unavailable
            return@produceState
        }
        Log.d(TAG, "Awaiting capture's early screen scan")
        val startedAt = SystemClock.elapsedRealtime()
        val screen = earlyScan.await()
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
    var noticeJob by remember { mutableStateOf<Job?>(null) }
    val busy = phase == Phase.Scanning || scanState == ScanState.Scanning

    // Restart the hint's lifetime on each miss so an earlier notice cannot hide the new one.
    fun showNotice(text: Int) {
        noticeJob?.cancel()
        pillText = text
        noticeUp = true
        noticeJob = scope.launch {
            delay(2000)
            noticeUp = false
        }
    }

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
        showNotice(notice)
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
                readLineBackground = { line ->
                    JapaneseOcr.readLineWithMangaOcr(
                        context, screenshot, line.bounds, MangaOcrEngine.Priority.BACKGROUND
                    )
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

    LaunchedEffect(Unit) { overlayState.targetState = true }

    // Closing runs two coordinated transitions, each awaited on its real completion: the sheet
    // slides out first, then the overlay fades, and only then is control handed back — once.
    // (The sheet's AnimatedVisibility animates nothing itself; its transition still waits for the
    // Surface's animateEnterExit slide to finish before currentState settles on false.)
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        snapshotFlow { sheetState.isIdle && !sheetState.currentState }.first { it }
        overlayState.targetState = false
        snapshotFlow { overlayState.isIdle && !overlayState.currentState }.first { it }
        onDismiss()
    }

    fun close() {
        lookupJob?.cancel()
        if (closing) return
        closing = true
        sheetState.targetState = false
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
        if (closing) return
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
        frozenBox = box
        lookupJob?.cancel()
        phase = Phase.Scanning
        sheetState.targetState = false
        val startedAt = SystemClock.elapsedRealtime()
        lookupJob = scope.launch {
            // One identity per lookup: the primary and the final publication belong to the same
            // session, so the sheet keyed on it survives both without resetting its state.
            val session = Any()
            val outcome = try {
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
                val lookup = runLookup(
                    context, bmp, bitmapBox, repo,
                    cache = cropCache,
                    onPrimary = { primary ->
                        withContext(Dispatchers.Main.immediate) {
                            currentCoroutineContext().ensureActive()
                            val failure = (repoState as? RepoState.Failed)?.message
                            result = if (failure == null) primary else primary.copy(error = failure)
                            displayedResultSession = session
                            resultAnchor = box
                            phase = Phase.Result
                            sheetState.targetState = true
                            Log.d(TAG, "Circle primary ready in ${SystemClock.elapsedRealtime() - startedAt} ms")
                        }
                    }
                ) {
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
            currentCoroutineContext().ensureActive()
            result = outcome
            displayedResultSession = session
            resultAnchor = box
            phase = Phase.Result
            sheetState.targetState = true
            Log.d(TAG, "Circle complete in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    /** Select the tapped text box, keeping the overlay open with a retry hint on a miss. */
    fun onTap(at: Offset) {
        if (closing) return
        val live = screen
        val bmp = screenshot
        if (bmp == null || live == null) {
            showNotice(R.string.lookup_hint)
            return
        }
        // The finger is in view pixels; the index, and everything it is hit-tested against, is in
        // the capture's pixels.
        val tapped = mapping.toBitmap(at.x, at.y)
        val x = tapped.x.toInt()
        val y = tapped.y.toInt()
        val tolerance = tapTolerancePx
        // Hit-test the indexed line bounds without drawing screen-wide box outlines.
        val lineIndex = ScreenHits.hitTestLine(live, x, y, tolerance)
        if (lineIndex == null) {
            Log.d(TAG, "Tap missed: no text box within $tolerance px")
            showNotice(R.string.lookup_hint)
            return
        }
        Log.d(TAG, "Tap hit line $lineIndex")
        // Select the entire visible box immediately, including while OCR is running.
        val line = live.lines[lineIndex]
        val selection = line.bounds
        val phrase = tapPhraseCrop(live, lineIndex, x, y)
        val anchorBox = mapping.toView(selection)
        frozenBox = anchorBox
        lookupJob?.cancel()
        phase = Phase.Scanning
        sheetState.targetState = false
        val startedAt = SystemClock.elapsedRealtime()
        lookupJob = scope.launch {
            // One identity per lookup, as in the circle path above.
            val session = Any()
            val outcome = try {
                val repo = awaitRepo()
                // Keep this snapshot throughout the crop, correction and distance ranking;
                // background line reads must not shift the phrase's text offsets midway.
                val lookup = runTapLookup(context, bmp, line, phrase, x, y, repo, cropCache)
                val failure = (repoState as? RepoState.Failed)?.message
                if (failure != null) lookup.copy(error = failure) else lookup
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tap lookup failed", e)
                LookupResult(ocrText = "", words = emptyList(), error = e.message ?: e.javaClass.simpleName)
            }
            currentCoroutineContext().ensureActive()
            result = outcome
            displayedResultSession = session
            resultAnchor = anchorBox
            phase = Phase.Result
            sheetState.targetState = true
            Log.d(TAG, "Tap complete in ${SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    /** Fresh lasso: drop the selection, back to selecting. The result stays for the exit. */
    fun selectAgain() {
        if (closing) return
        lookupJob?.cancel()
        frozenBox = null
        phase = Phase.Selecting
        sheetState.targetState = false
    }

    // Fade in on open and fade out on close, so the overlay never pops.
    AnimatedVisibility(
        visibleState = overlayState,
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
                points = points,
                frozenBox = frozenBox,
                interactive = phase == Phase.Selecting && !closing,
                showingResult = phase == Phase.Result,
                onLassoStart = { points = listOf(it) },
                onLassoUpdate = { points = points + it },
                onLassoEnd = { startLookup() },
                onLassoCancel = { points = emptyList() },
                onTap = { onTap(it) }
            )

            // Pure drawing, over the selection canvas and under the pill: the ring around the edge
            // of the display, telling work apart from idle without ever taking a touch.
            EdgeOutline(
                busy = busy,
                shown = phase != Phase.Result,
                modifier = Modifier.fillMaxSize()
            )

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

            // Chosen from the anchor the displayed result was published with, not the live frozen
            // selection: selectAgain clears the latter, and the exiting sheet must keep its dock
            // until a new result publishes its own anchor.
            val sheetLayout = remember(
                resultAnchor,
                constraints.maxWidth,
                constraints.maxHeight
            ) {
                chooseSheetLayout(
                    anchor = resultAnchor,
                    screenWidth = constraints.maxWidth.toFloat(),
                    screenHeight = constraints.maxHeight.toFloat()
                )
            }

            // The sheet's AnimatedVisibility animates nothing itself (None on both sides): the
            // Surface carries the actual slide via Modifier.animateEnterExit — sized to the measured
            // panel — and this container only holds the composition until that exit is done. The
            // last result keeps composing through the exit: selectAgain and a fresh gesture slide
            // the old sheet out instead of blanking it mid-animation. Keyed on the lookup session
            // — not the result object — so the primary->final publications of one lookup keep the
            // same sheet (selection, scroll and drag state intact), while every new session,
            // equal-data ones included, starts fresh.
            AnimatedVisibility(
                visibleState = sheetState,
                enter = EnterTransition.None,
                exit = ExitTransition.None
            ) {
                result?.let { state ->
                    key(displayedResultSession) {
                        ResultSheet(
                            result = state,
                            modifier = Modifier.animateEnterExit(
                                enter = sheetEnter(sheetLayout.dock),
                                exit = sheetExit(sheetLayout.dock)
                            ),
                            onDismiss = { close() },
                            onSelectAgain = { selectAgain() },
                            layout = sheetLayout
                        )
                    }
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
@OptIn(ExperimentalLayoutApi::class)
private fun StatusPill(text: Int, busy: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        modifier = Modifier
            // Below whatever occupies the top band — the status bar even when the app behind
            // hides it, and the camera cutout — plus one fixed gap. Horizontal sides ride along
            // so a landscape side cutout cannot cover the pill either.
            .windowInsetsPadding(
                WindowInsets.statusBarsIgnoringVisibility
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(top = Spacing.l)
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
