/*
 * Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * YomiLens: Japanese screen lookup for immersion learners.
 *
 * Freehand lasso a region -> on-device Japanese OCR (ML Kit, Tesseract fallback)
 * -> offline JMdict lookup with deinflection -> result panel with real dictionary entries.
 */

package com.akslabs.circletosearch.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.akslabs.circletosearch.R
import com.akslabs.circletosearch.dict.JmdictRepository
import com.akslabs.circletosearch.dict.ScannedWord
import com.akslabs.circletosearch.dict.WordScanner
import com.akslabs.circletosearch.ocr.Box
import com.akslabs.circletosearch.ocr.JapaneseOcr
import com.akslabs.circletosearch.ocr.SelectionSnap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val TAG = "JpLookup"
private val ACCENT = Color(0xFF7C4DFF)

/** Degenerate gestures (a tap or a sliver) are ignored below this side length, in view px. */
private const val MIN_SELECTION_PX = 24f

/** Padding around the snapped line bounds, so glyph edges are never clipped out of the crop. */
private const val SNAP_PAD_PX = 6

/** Everything the result panel needs after one lasso lookup. */
private data class LookupResult(
    val crop: Bitmap,
    val ocrText: String,
    val words: List<ScannedWord>,
    val error: String?
)

/** Lifecycle of the offline dictionary handle. */
private sealed interface RepoState {
    object Loading : RepoState
    data class Ready(val repo: JmdictRepository) : RepoState
    data class Failed(val message: String) : RepoState
}

/**
 * Full-screen lasso selector plus the lookup result panel.
 *
 * The user draws a freehand loop over the frozen screenshot; the trail's bounding box is cropped,
 * OCR'd on-device, tokenised against the offline JMdict and shown in an animated panel.
 */
@Composable
fun JpLookupOverlay(
    screenshot: Bitmap?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var visible by remember { mutableStateOf(false) }
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var frozenTrail by remember { mutableStateOf<Path?>(null) }
    var frozenBox by remember { mutableStateOf<Rect?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<LookupResult?>(null) }
    var cardVisible by remember { mutableStateOf(false) }
    var selectedWord by remember { mutableStateOf(0) }

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

    // The frozen lasso fades out once the scan finishes.
    val trailAlpha by animateFloatAsState(
        targetValue = if (frozenTrail != null && !scanning) 0f else 1f,
        animationSpec = tween(420),
        label = "trailAlpha"
    )

    val liveBox = remember(points) { boundingBoxOf(points) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(150)),
        modifier = Modifier.zIndex(3000f)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ---- Selection surface: dim + live lasso trail ----
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(screenshot, cardVisible) {
                        if (!cardVisible && screenshot != null) {
                            detectDragGestures(
                                onDragStart = { offset -> points = listOf(offset) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    points = points + change.position
                                },
                                onDragEnd = {
                                    val drawn = points
                                    points = emptyList()
                                    val box = boundingBoxOf(drawn)
                                    val bmp = screenshot
                                    if (bmp != null && box != null &&
                                        box.width >= MIN_SELECTION_PX && box.height >= MIN_SELECTION_PX
                                    ) {
                                        frozenTrail = buildTrailPath(drawn)
                                        frozenBox = box
                                        scanning = true
                                        scope.launch {
                                            val (text, crop) = withContext(Dispatchers.Default) {
                                                snapToLines(bmp, box, context)
                                            }
                                            val repo = awaitRepo()
                                            val words = if (repo == null) emptyList()
                                            else withContext(Dispatchers.Default) {
                                                WordScanner.scan(text, lookup = { term -> repo.lookup(term) })
                                            }
                                            withContext(Dispatchers.IO) {
                                                writeEvidence(context, bmp, crop, text, words)
                                            }
                                            val failure = (repoState as? RepoState.Failed)?.message
                                            result = LookupResult(crop, text, words, failure)
                                            selectedWord = 0
                                            scanning = false
                                            cardVisible = true
                                        }
                                    }
                                },
                                onDragCancel = { points = emptyList() }
                            )
                        }
                    }
                    .pointerInput(cardVisible) {
                        if (!cardVisible) {
                            detectTapGestures { close() }
                        }
                    }
            ) {
                val dim = Color.Black.copy(alpha = 0.45f)
                val box = frozenBox ?: liveBox
                if (box == null) {
                    drawRect(dim)
                } else {
                    // Dim everything outside the selection.
                    drawRect(dim, topLeft = Offset.Zero, size = Size(size.width, box.top.coerceAtLeast(0f)))
                    drawRect(dim, topLeft = Offset(0f, box.bottom), size = Size(size.width, (size.height - box.bottom).coerceAtLeast(0f)))
                    drawRect(dim, topLeft = Offset(0f, box.top), size = Size(box.left.coerceAtLeast(0f), box.height))
                    drawRect(dim, topLeft = Offset(box.right, box.top), size = Size((size.width - box.right).coerceAtLeast(0f), box.height))
                    drawRect(ACCENT.copy(alpha = 0.10f), topLeft = box.topLeft, size = box.size)
                    drawRect(ACCENT, topLeft = box.topLeft, size = box.size, style = Stroke(width = 2f))
                }
                // The frozen lasso trail, fading out on completion.
                frozenTrail?.let { trail ->
                    drawPath(trail, ACCENT.copy(alpha = trailAlpha * 0.16f))
                    drawPath(
                        path = trail,
                        color = ACCENT.copy(alpha = trailAlpha),
                        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                // The user's own stroke while drawing.
                if (points.size > 1) {
                    val live = buildTrailPath(points)
                    drawPath(live, ACCENT.copy(alpha = 0.16f))
                    drawPath(
                        path = live,
                        color = ACCENT,
                        style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // ---- Top hint bar ----
            AnimatedVisibility(
                visible = !cardVisible,
                enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(160)) { -it } + fadeOut(tween(160)),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { close() },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                            .size(40.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                    }
                    if (!scanning) {
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.jp_lookup_hint),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            // ---- Scanning indicator ----
            AnimatedVisibility(
                visible = scanning,
                enter = fadeIn(tween(140)),
                exit = fadeOut(tween(140)),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.jp_lookup_scanning),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            // ---- Result panel ----
            AnimatedVisibility(
                visible = cardVisible,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.92f, animationSpec = tween(200)),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.92f, animationSpec = tween(150)),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                result?.let { state ->
                    ResultPanel(
                        state = state,
                        selected = selectedWord,
                        onSelect = { selectedWord = it },
                        onClose = { close() },
                        onSelectAgain = {
                            cardVisible = false
                            frozenTrail = null
                            frozenBox = null
                            selectedWord = 0
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultPanel(
    state: LookupResult,
    selected: Int,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    onSelectAgain: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(16.dp)
            // Swallow taps so the scrim's dismiss gesture never fires from the card itself.
            .pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                }
            }

            // Cropped strip preview of what was OCR'd.
            Image(
                bitmap = state.crop.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            state.error?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.jp_dict_error, message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(12.dp))

            if (state.words.isEmpty()) {
                Text(
                    text = stringResource(R.string.jp_no_words),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.ocrText.ifBlank { stringResource(R.string.jp_lookup_no_text) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.words.forEachIndexed { index, word ->
                    WordRow(
                        word = word,
                        expanded = index == selected,
                        onClick = { onSelect(index) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.jp_dict_attribution),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onSelectAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.jp_lookup_select_again))
            }
        }
    }
}

/** One found word; tapping expands the dictionary fields. */
@Composable
private fun WordRow(
    word: ScannedWord,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val entry = word.entries.firstOrNull()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHighest
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = word.surface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry != null && entry.primary != word.surface) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = entry.primary,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (word.baseForm != word.surface) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.jp_word_base, word.baseForm),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded && entry != null) {
                Spacer(Modifier.height(8.dp))
                if (entry.kanji.isNotEmpty()) {
                    DetailLine(stringResource(R.string.jp_word_kanji), entry.kanji.joinToString("、"))
                }
                if (entry.kana.isNotEmpty()) {
                    DetailLine(stringResource(R.string.jp_word_kana), entry.kana.joinToString("、"))
                }
                if (entry.pos.isNotEmpty()) {
                    DetailLine(stringResource(R.string.jp_word_pos), entry.pos.joinToString(", "))
                }
                entry.glosses.take(4).forEachIndexed { index, gloss ->
                    Text(
                        text = "${index + 1}. $gloss",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                }
                // JMdict ships no frequency ranks, so homographs of one surface cannot be
                // disambiguated reliably — show the runners-up instead of hiding them.
                if (word.entries.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.jp_word_other_matches),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    word.entries.drop(1).take(3).forEach { alternative ->
                        Text(
                            text = buildString {
                                append(alternative.primary)
                                alternative.glosses.firstOrNull()?.let { append(" — ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
    }
}

/** Bounding box of a freehand stroke, in view pixels; null when there is nothing drawn. */
private fun boundingBoxOf(points: List<Offset>): Rect? {
    if (points.isEmpty()) return null
    return Rect(
        left = points.minOf { it.x },
        top = points.minOf { it.y },
        right = points.maxOf { it.x },
        bottom = points.maxOf { it.y }
    )
}

/** Polyline through the sampled drag points. */
private fun buildTrailPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        lineTo(points[i].x, points[i].y)
    }
}

/**
 * Recognises a generous region around the user's gesture, then snaps to the text lines it touched.
 *
 * This is what makes a near-miss forgiving: circling 猫が水を飲む a character short used to lose
 * the verb entirely, because the tight crop of the trail simply did not contain it. Recognising
 * wider and keeping only the lines the selection overlaps returns the whole line the user meant.
 *
 * @return the text to scan plus the crop to show (the snapped lines, not the raw gesture box)
 */
private suspend fun snapToLines(
    screenshot: Bitmap,
    selection: Rect,
    context: Context
): Pair<String, Bitmap> {
    val box = Box(
        selection.left.toInt(),
        selection.top.toInt(),
        selection.right.toInt(),
        selection.bottom.toInt()
    )
    val region = SelectionSnap.searchRegion(
        box,
        SelectionSnap.horizontalMargin(box, screenshot.width),
        SelectionSnap.verticalMargin(box),
        screenshot.width,
        screenshot.height
    )

    val regionCrop = cropBitmap(screenshot, region)
    val onScreen = SelectionSnap.translate(
        JapaneseOcr.recognizeLines(context, regionCrop),
        region.left,
        region.top
    )
    val chosen = SelectionSnap.selectLines(onScreen, box)
    // Nothing overlapped (nothing recognised, or a gesture between lines): keep whatever was read.
    val used = chosen.ifEmpty { onScreen }

    val snapped = SelectionSnap.unionBounds(chosen) ?: box
    val finalBox = SelectionSnap.padAndClamp(
        snapped,
        SNAP_PAD_PX,
        SNAP_PAD_PX,
        screenshot.width,
        screenshot.height
    )
    return used.joinToString("\n") { it.text } to cropBitmap(screenshot, finalBox)
}

/** Crops [box] (bitmap px) out of the capture, clamped so the result is never empty. */
private fun cropBitmap(screenshot: Bitmap, box: Box): Bitmap {
    val left = box.left.coerceIn(0, screenshot.width - 1)
    val top = box.top.coerceIn(0, screenshot.height - 1)
    val right = box.right.coerceIn(left + 1, screenshot.width)
    val bottom = box.bottom.coerceIn(top + 1, screenshot.height)
    return Bitmap.createBitmap(screenshot, left, top, right - left, bottom - top)
}

/**
 * Debug evidence: full capture, cropped region and raw OCR text followed by one line per scanned
 * word (`surface\tbaseForm\tprimary\tgloss0`). Written to the app's external files dir.
 */
private fun writeEvidence(
    context: Context,
    full: Bitmap,
    crop: Bitmap,
    ocrText: String,
    words: List<ScannedWord>
) {
    try {
        val dir = File(context.getExternalFilesDir(null), "jp-prototype")
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create evidence dir ${dir.absolutePath}")
            return
        }
        FileOutputStream(File(dir, "capture-full.png")).use { out ->
            full.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        FileOutputStream(File(dir, "capture-region.png")).use { out ->
            crop.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val report = StringBuilder(ocrText).append('\n')
        words.forEach { word ->
            val entry = word.entries.firstOrNull()
            report.append(word.surface).append('\t')
                .append(word.baseForm).append('\t')
                .append(entry?.primary.orEmpty()).append('\t')
                .append(entry?.glosses?.firstOrNull().orEmpty()).append('\n')
        }
        File(dir, "ocr-output.txt").writeText(report.toString())
    } catch (e: Exception) {
        Log.w(TAG, "Evidence dump failed: ${e.message}")
    }
}
