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
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.gaten.app.R
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.ocr.Box
import com.gaten.app.ocr.JapaneseOcr
import com.gaten.app.ocr.MangaOcrEngine
import com.gaten.app.ocr.RecognitionPreference
import com.gaten.app.ocr.ScanState
import com.gaten.app.ocr.ScreenHits
import com.gaten.app.ocr.ScreenLineReader
import com.gaten.app.ocr.ScreenText
import com.gaten.app.ocr.ScreenTextScanner
import com.gaten.app.ocr.prefetchOrder
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.overlay.ViewMapping
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.SheetShape
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

/**
 * How long the RN sheet surface stays mounted after it is told to hide, so the panel's own
 * Reanimated exit can play before the touch-consuming root view is taken down.
 */
private const val EXIT_WINDOW_MS = 320L

/**
 * How long a settled rail message stays up before the next one replaces it, or the rail goes. The
 * first message takes the longer dwell: an instruction that vanishes after two seconds is not
 * enough time to read it and act on it.
 */
private const val NOTICE_MS = 3000L

/**
 * How long the message that follows one in the same sequence stays — the instruction behind a tap
 * miss, the recovery hint behind an empty scan. Shorter than the first, so a miss and its
 * instruction cannot hold the rail for six seconds while the reader is already drawing.
 */
private const val FOLLOW_UP_MS = 2000L

/** Lifecycle of the offline dictionary handle. */
private sealed interface RepoState {
    object Loading : RepoState
    data class Ready(val repo: JmdictRepository) : RepoState
    /**
     * The dictionary could not be opened. [message] is whatever the failure itself can say; null
     * when it can say nothing usable, in which case the sheet shows the failure without inventing a
     * cause for it — an exception class name is not a reason a reader can act on.
     */
    data class Failed(val message: String?) : RepoState
}

/** The three states of one overlay session. */
private enum class Phase { Selecting, Scanning, Result }

/**
 * A settled message for the floating rail, and the instruction that replaces it two seconds later:
 * a miss says "No text here" and then how to try again; an index that came back empty names the zero
 * it really found before naming the one path that can still recover text.
 */
private data class RailNotice(val text: Int, val glyph: RailGlyph? = null, val then: RailNotice? = null)

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
@OptIn(ExperimentalLayoutApi::class)
fun LookupOverlay(
    screenshot: Bitmap?,
    /**
     * The capture's pixels -> this window's pixels. The frozen frame is drawn through it, and so is
     * every box and gesture: it is identity while the window is the capture's own size and origin.
     */
    mapping: ViewMapping,
    earlyScan: Deferred<ScreenText?>,
    /**
     * Whether the reader is typing in the overlay's own query field. The window keeps its focus while
     * they type and the keyboard takes the window focus instead, so the controller has to know that a
     * focus loss belongs to this session's IME rather than to another app.
     */
    onTextEntryActive: (Boolean) -> Unit,
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
    // A tap that lands before the screen index exists: the latest one is kept and replayed through
    // the ordinary indexed path once the index arrives, so the reader's first touch is not dropped.
    var pendingTap by remember { mutableStateOf<Offset?>(null) }
    var frozenBox by remember { mutableStateOf<Rect?>(null) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    // The lookup session the displayed result belongs to, and the anchor box (view px) its dock
    // was chosen from. Both change only when a result is published, so the exiting sheet keeps
    // its layout even after selectAgain clears the frozen selection, and the sheet instance
    // survives the primary->final publication of one lookup.
    var displayedResultSession by remember { mutableStateOf<Any?>(null) }
    var feedbackSession by remember { mutableStateOf<Any?>(null) }
    // Whether the RN sheet surface is in the composition. The RN root consumes every touch while
    // it is attached, so it is present only while a result is showing — plus a short window so the
    // panel can play its slide out — and never during selection or a running crop, where the lasso
    // must keep the screen. A fresh lookup opens its own surface on its own result session.
    var sheetPresent by remember { mutableStateOf(false) }
    LaunchedEffect(displayedResultSession, phase) {
        if (displayedResultSession == null) {
            sheetPresent = false
        } else if (phase == Phase.Result) {
            sheetPresent = true
        } else {
            delay(EXIT_WINDOW_MS)
            sheetPresent = false
        }
    }
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

    // The floating rail names the work that is really running — the full-screen index, then the
    // committed crop — and otherwise reads out one settled message for two seconds before it goes.
    // [railText] holds the text last shown so the rail still has something to draw while it fades.
    var railText by remember { mutableStateOf(R.string.lookup_reading_screen) }
    var railGlyph by remember { mutableStateOf<RailGlyph?>(null) }
    var noticeUp by remember { mutableStateOf(false) }
    // Re-keyed on every request: the next notice has to outlive whatever countdown the last one
    // left running, whether that is a second miss or "Select again" after an answer.
    var notice by remember { mutableStateOf<RailNotice?>(null) }
    var noticeSerial by remember { mutableStateOf(0) }
    // Work outranks any notice: a status that names what is happening is never hidden by a message
    // about what already happened.
    val busyText = when {
        scanState == ScanState.Scanning -> R.string.lookup_reading_screen
        phase == Phase.Scanning -> R.string.lookup_reading_selection
        else -> null
    }
    val railUp = busyText != null || noticeUp

    // A confirmed zero-line index opens the no-text sheet once per lookup: asking for another
    // selection must not immediately put the same empty shell back in front of the reader.
    var zeroShellSpent by remember { mutableStateOf(false) }

    // The keyboard takes the window focus while the RN sheet's own field is editing; the controller
    // attributes the loss through this flag so a lookup being typed into is never dismissed by its
    // own IME.
    DisposableEffect(Unit) { onDispose { onTextEntryActive(false) } }

    fun showNotice(next: RailNotice) {
        notice = next
        noticeSerial++
    }

    /**
     * The confirmed zero-line index, shown as the sheet's no-text body rather than as a timed notice.
     *
     * Once per lookup: after the reader has asked to select again, the empty shell does not come back
     * by itself — the circle-only instruction does, and a fresh selection can still recover text.
     * No selection is fabricated: there is no anchor, so the dock is the default one (bottom in
     * portrait, the reading edge in landscape).
     */
    fun openZeroShell() {
        if (zeroShellSpent || closing || result != null) return
        if (phase != Phase.Selecting || frozenBox != null || points.isNotEmpty()) return
        zeroShellSpent = true
        val session = Any()
        result = LookupResult(ocrText = "", words = emptyList(), error = null)
        displayedResultSession = session
        resultAnchor = null
        phase = Phase.Result
        sheetState.targetState = true
    }

    // The rail's whole life, in one place: the running work, then the last settled message and its
    // follow-up — two seconds each — and then away. Text replacement is deliberately immediate
    // rather than animated: the rail relayouts its own height, and a height animation over clipped
    // text is the one thing a status line must not do.
    LaunchedEffect(busyText, noticeSerial) {
        // Work first: while something is really running, its message takes the rail and whatever was
        // settling is dropped — a notice about the last state must never outlive the new one.
        if (busyText != null) {
            railText = busyText
            railGlyph = null
            notice = null
            return@LaunchedEffect
        }
        val scheduled = notice
        if (scheduled != null) {
            railText = scheduled.text
            railGlyph = scheduled.glyph
            noticeUp = true
            delay(NOTICE_MS)
            val followUp = scheduled.then
            if (followUp != null) {
                railText = followUp.text
                railGlyph = followUp.glyph
                delay(FOLLOW_UP_MS)
            }
        }
        noticeUp = false
        // Read once, then gone: a result the reader is already looking at never gets a message from
        // the state before it replayed over the top.
        notice = null
    }

    // A drag takes the instruction away at once — it must not sit over the mark being drawn — and
    // restarts nothing: the request is dropped so the countdown cannot bring it back mid-gesture.
    fun hideInstruction() {
        notice = null
        noticeUp = false
        noticeSerial++
    }

    /**
     * What the rail asks for once the screen has settled: the instruction when there is an index to
     * use, and the circle alone when there is not. An index that never arrived and one that came
     * back with no lines are both only recoverable by hand, and neither is an instruction to tap.
     */
    fun instruction(state: ScanState): RailNotice =
        if (state is ScanState.Ready && state.screen.lines.isNotEmpty()) {
            RailNotice(R.string.lookup_hint, RailGlyph.Gesture)
        } else {
            RailNotice(R.string.lookup_circle_hint)
        }

    // A scan that settled says what it settled on, once: the instruction it can honour, or — for an
    // index that never arrived — the circle, because a missing index is not the same fact as an
    // empty screen and must never be reported as one.
    //
    // A *confirmed* zero is not a message at all: the screen really holds no Japanese, so the sheet
    // opens with the no-text body, where the reader can search the dictionary by hand or go back to
    // selecting. That only happens while the overlay is idle and has shown nothing yet — never over
    // a gesture in progress, a crop being read, an existing result or an open search.
    LaunchedEffect(scanState) {
        val settled = scanState
        when {
            settled == ScanState.Scanning -> Unit
            settled is ScanState.Ready && settled.screen.lines.isEmpty() -> openZeroShell()
            else -> showNotice(instruction(settled))
        }
    }

    // ML Kit's index is ready instantly. When Manga OCR is selected, the reader replaces each
    // line's text in that index as it reads; with ML Kit selected, skip the background reader.
    val scannedScreen = (scanState as? ScanState.Ready)?.screen
    val mangaOcrEnabled = RecognitionPreference.isMangaOcrEnabled(context)
    val reader = remember(scannedScreen, screenshot, mangaOcrEnabled) {
        if (scannedScreen == null || screenshot == null || !mangaOcrEnabled) {
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
            RepoState.Failed(e.message)
        }
    }
    val readyRepo = (repoState as? RepoState.Ready)?.repo
    DisposableEffect(readyRepo) {
        onDispose { readyRepo?.close() }
    }

    LaunchedEffect(Unit) { overlayState.targetState = true }

    // Closing runs both exits at once — the sheet leaves toward its dock while the dim and the rail
    // fade — and hands control back once they have both finished, so teardown never waits out a
    // decorative animation any longer than the longest of them. (The sheet's AnimatedVisibility
    // animates nothing itself; its transition still waits for the Surface's animateEnterExit slide
    // to finish before currentState settles on false, which is why both are awaited here.)
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        sheetState.targetState = false
        overlayState.targetState = false
        snapshotFlow {
            overlayState.isIdle && !overlayState.currentState &&
                sheetState.isIdle && !sheetState.currentState
        }.first { it }
        onDismiss()
    }

    fun close() {
        lookupJob?.cancel()
        pendingTap = null
        if (closing) return
        closing = true
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
            // Nothing was selected: the reader is back where they started, so the instruction the
            // drag took away comes back instead of leaving the screen silently without one.
            showNotice(instruction(scanState))
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
                LookupResult(ocrText = "", words = emptyList(), error = e.message)
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
            // No index yet: keep the latest tap while the screen is still being read, and spend it
            // when the index never arrives, where the circle is the only way in.
            if (scanState == ScanState.Scanning) {
                pendingTap = at
                Log.d(TAG, "Tap queued while the screen index is still building")
            } else {
                pendingTap = null
                showNotice(instruction(scanState))
            }
            return
        }
        // Any tap that really ran supersedes a buffered one.
        pendingTap = null
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
            // Absence, then the way to try again: a miss never dismisses the overlay, so the reader
            // is told what happened and how to recover, in that order.
            showNotice(RailNotice(R.string.lookup_tap_miss, then = instruction(scanState)))
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
                LookupResult(ocrText = "", words = emptyList(), error = e.message)
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
        pendingTap = null
        phase = Phase.Selecting
        sheetState.targetState = false
        // Asking for another selection discards everything this lookup had shown: the typed entry and
        // its provenance, the draft the reader left in search, and the shell a zero index opened. The
        // search surface goes with it and without its move — there is no panel to land it back on,
        // because the sheet it was covering is on its way out.
        // The instruction comes back with the state it belongs to, over the outgoing sheet: the
        // reader who just asked for another selection should not have to guess the gesture again.
        showNotice(instruction(scanState))
    }

    // Replays the tap buffered before the index existed, through the same indexed `onTap`, so its
    // line and phrase resolve identically; consumes it once, and only for an index that holds lines
    // while the overlay is still selecting. A still-scanning index is left untouched.
    LaunchedEffect(scanState) {
        val settled = scanState
        if (settled == ScanState.Scanning) return@LaunchedEffect
        val at = pendingTap ?: return@LaunchedEffect
        pendingTap = null
        if (settled !is ScanState.Ready || settled.screen.lines.isEmpty()) return@LaunchedEffect
        if (phase != Phase.Selecting || closing) return@LaunchedEffect
        Log.d(TAG, "Replaying the queued tap")
        onTap(at)
    }

    // Fade in on open and fade out on close, so the overlay never pops. The frozen capture is not
    // part of this: the window behind already shows the undimmed frame, and only the dim, the rail
    // and the sheet ride this alpha — so 0 -> 0.40 is the dim's own entry, over SHORT/emphasized.
    AnimatedVisibility(
        visibleState = overlayState,
        enter = fadeIn(tween(Motion.SHORT, easing = Motion.emphasized)),
        exit = fadeOut(tween(Motion.SHORT, easing = Motion.emphasized)),
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
                // A drag takes the instruction away at once; a lasso that produced nothing puts it
                // back, so the rail is never left blank just because a gesture ended.
                onLassoStart = {
                    hideInstruction()
                    // A lasso takes over the gesture: a tap buffered before this must not fire later.
                    pendingTap = null
                    points = listOf(it)
                },
                onLassoUpdate = { points = points + it },
                onLassoEnd = { startLookup() },
                onLassoCancel = {
                    points = emptyList()
                    showNotice(instruction(scanState))
                },
                onTap = { onTap(it) }
            )

            EdgeOutline(
                busy = busyText != null,
                shown = phase != Phase.Result,
                modifier = Modifier.fillMaxSize()
            )

            // The rail: opaque paper with a neutral hairline and its own rounded corners, so it reads
            // over somebody else's screen without borrowing an accent from it. It sits below the real
            // status-bar and cutout insets — the frozen frame still carries the app's own status bar,
            // and a rail drawn over the clock reads as broken chrome — and only its opacity animates.
            AnimatedVisibility(
                visible = railUp,
                enter = fadeIn(tween(Motion.SHORT, easing = Motion.emphasized)),
                exit = fadeOut(tween(Motion.SHORT, easing = Motion.emphasized)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(
                        WindowInsets.statusBarsIgnoringVisibility
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    )
                    .padding(top = Spacing.l)
            ) {
                FloatingStatusRail(
                    text = stringResource(railText),
                    busy = busyText != null,
                    glyph = railGlyph,
                    tone = RailTone.Neutral,
                    supporting = null
                )
            }

            // The sheet body itself is React Native. One surface per displayed result session is
            // mounted on the application's shared React host and driven by published state; the
            // native overlay keeps the frozen frame, the lasso, the rail and the accessibility
            // window. Nothing mounts RN while the reader is still selecting or a crop is being
            // read — the surface appears with the first resolved result and stays through the
            // sheet's own exit so the panel can slide away instead of blinking out.
            if (sheetPresent) {
                LookupSheetView(
                    sessionKey = displayedResultSession,
                    result = result,
                    phaseVisible = phase == Phase.Result,
                    modifier = Modifier.fillMaxSize(),
                    onClose = { close() },
                    onSelectAgain = { selectAgain() },
                    onTextEntryActive = onTextEntryActive,
                    onReady = {},
                    // The overlay never offered WaniKani settings navigation; the reused reference
                    // pages hide that affordance, so this is never called from the sheet.
                    onOpenSettingsPage = {}
                )
            }
        }
    }
}
