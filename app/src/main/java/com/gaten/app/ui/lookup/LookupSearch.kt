/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.data.SearchHistory
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.DictionarySearch
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.dict.SearchHit
import com.gaten.app.dict.SearchMatch
import com.gaten.app.ui.components.pressOpacity
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The top bar's own height, and the floor every control in it keeps. */
private val TopBarHeight = 48.dp
private val MinTarget = 48.dp
private val GlyphSize = 20.dp

/** A result card's own trailing glyph, at Home's own icon size. */
private val CardGlyphSize = 24.dp

/** The house content column, insets included, and the width below which that inset narrows. */
private val ContentColumnMaxWidth = 560.dp
private val NarrowWidth = 360.dp

/**
 * The query field, at Home's own search field: a 14 dp radius filled with `surfaceVariant`, a 2 dp
 * outline that is invisible until focus lands on it and then fades into the accent, a 20 dp search
 * glyph 13 dp ahead of the text, and 15/12 dp of padding around both. The outline is drawn inside
 * the field's own edge rather than around it, so focus never moves the field or the text in it.
 */
private val FieldMinHeight = 52.dp
private val FieldOutline = 2.dp
private val FieldPaddingH = 15.dp
private val FieldPaddingV = 12.dp
private val FieldIconGap = 13.dp

/**
 * The control corner Home draws its search surfaces with (`shape.small`): the field, the result
 * cards and the chips on the empty prompt all keep it.
 */
private val ControlShape = RoundedCornerShape(14.dp)

/** The part-of-speech pill inside a card, at Home's own `shape.brandTile`. */
private val PillShape = RoundedCornerShape(10.dp)

/** How long typing settles before the dictionary is asked, as Home's own field settles. */
private const val SearchDebounceMs = 250L

/** A result card's own rough height, for the compact layout's "the field and one card fit" test. */
private val CardHeight = 96.dp

/** The words the empty prompt offers before the reader has searched at all — Home's own examples. */
private val QueryExamples = listOf("猫", "たべる", "食べた", "ありがとう", "学校", "面白い")

/** What one prompt chip keeps as a target, as every other control in the app does. */
private val ChipMinHeight = 48.dp

/** The artwork's own size, and the two viewport measures that step it down. */
private val ArtworkSize = 140.dp
private val ArtworkCompact = 96.dp
private val ArtworkNarrowWidth = 240.dp
private val ArtworkShortHeight = 344.dp
private val CaptionMaxWidth = 280.dp

/**
 * The space a readable list needs under the query block. When the keyboard leaves less than this,
 * the cover stops pretending it can keep a fixed chrome and becomes one scroller instead.
 */
private val ReadableListHeight = 48.dp

/**
 * The states the surface can be in: the repository failed, the query is empty, the query is
 * something the dictionary cannot be asked about, the search found nothing, or it found rows.
 */
private enum class SearchPhase { Failed, Prompt, Unsupported, NoMatches, Results }

/**
 * A query handed to the dictionary: the text, how it was asked, and which ask of that text it is.
 * The IME's own Search asks at once; a keystroke waits [SearchDebounceMs] first, so a word is looked
 * up once, when it is written. [attempt] is what makes asking the same text again a new question to
 * the effect, which is what a retry after a failure is.
 */
private data class SearchQuery(val text: String, val now: Boolean, val attempt: Int = 0)

/**
 * The rows on screen and the query they answer. Typing moves the field and leaves these alone, so a
 * row picked while the next answer is still on its way reports the query that really found it.
 */
private data class SearchAnswers(val query: String, val hits: List<SearchHit>)

/**
 * The full-screen search cover: the reader's own query, typed over the frozen lookup.
 *
 * This is a mode of the existing accessibility overlay, not a second window, an expanded sheet or a
 * side-docked panel. It paints opaque `surface` over the whole window — full width and height in
 * portrait and landscape, whatever dock the sheet behind it chose — with flat paper and no rounded
 * outer corner, extra dim, shadow or boundary of its own. The sheet, the frozen capture and the
 * committed mark stay underneath, unchanged and out of this surface's traversal; the parent owns
 * that visibility and the SHORT/emphasized opacity that brings the cover in and out. Nothing here
 * animates visibility or opens itself.
 *
 * Everything below the bar is one measure: the house column, capped at 560 dp including its own
 * inset and centred in the window. The query block, the count and the rows share it; the rows are
 * the only scroller in the ordinary case, and in a short window the query block joins them in one
 * scroller rather than being pinned into a sliver of list.
 *
 * The call is [DictionarySearch.search] — the same offline helper the rest of the app reads through,
 * with the repository the overlay already has open. It is bounded, and it reads SQLite, so it runs
 * off the main thread rather than in composition. As on Home, typing is what searches: every edit is
 * asked, composing text included, one [SearchDebounceMs] after the last keystroke, and the IME's own
 * Search asks at once instead of waiting. Nothing stands between the query and its answer but that
 * settle: no spinner, no skeleton, no commit to wait for, no key of its own to press. A failure is
 * the rail, never an invented zero.
 *
 * @param repo the repository already open for this overlay, or null when it could not be opened.
 * @param failure the sanitized readable cause, already resolved by the parent.
 * @param query the draft, owned by the parent so it survives closing search. The cursor and the
 *   IME's composing range belong to this field and start at the end of a restored draft.
 * @param onQueryChange the accepted text, as typed — normalization belongs to the helper.
 * @param onBack leave search and keep the draft; the saved sheet is already laid out beneath.
 * @param onClose end the lookup, IME or not.
 * @param onPick the chosen entry and the query the row really came from — the rows on screen keep
 *   answering the last query looked up while the next one is typed — for the parent to reveal in
 *   the sheet and to label the provenance with.
 * @param inputActive whether the cover has finished arriving. False while the parent's transition is
 *   still expanding the sheet into it: the field is inert and takes no focus then, so the keyboard
 *   cannot come up over a surface that is still moving and no keystroke lands on a field still on
 *   its way in. The keyboard is raised, once, when this turns true.
 */
@Composable
internal fun LookupSearchCover(
    repo: JmdictRepository?,
    failure: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onPick: (DictEntry, String) -> Unit,
    inputActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val title = stringResource(R.string.lookup_search)
    val focusManager = LocalFocusManager.current
    // Home's own field text, which the cover borrows wholesale: the placeholder is the hint every
    // reader of Home has already learnt, and the label is what the field is called when the only
    // reading of it is the one a screen reader gives.
    val fieldLabel = stringResource(R.string.lookup_search_query_label)
    val placeholder = stringResource(R.string.lookup_search_query_hint)

    // The field is the draft plus what only a field has: the caret and the IME's composing range.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // What has been asked of the dictionary, and how: a settled keystroke is asked
    // [SearchDebounceMs] after the last one, the IME's own Search at once.
    var asked by remember { mutableStateOf(SearchQuery(query, now = false)) }
    // The rows on screen, and the query they answer. Typing moves the field and nothing here, so the
    // list keeps what it has — labelled by the query that found it — until the next answer lands.
    var answers by remember { mutableStateOf(SearchAnswers(query, emptyList())) }
    var tooLong by remember { mutableStateOf(false) }
    // The cause of a query that failed after the repository had opened: the rail says it, with the
    // one action a failed query has — ask it again.
    var searchFailure by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }

    // The words the reader has searched with, newest first: the same history the RN screens read and
    // write, read once here and kept in step as this surface adds to it.
    var recents by remember { mutableStateOf(emptyList<String>()) }
    // A word the reader acted on is remembered — the IME's Search, a chip, a picked row — and a typed
    // fragment never is: the debounce asks about text they are still writing.
    val rememberQuery: (String) -> Unit = { text ->
        val trimmed = text.trim()
        val key = DictionarySearch.normalize(trimmed)
        if (key.isNotEmpty() && recents.firstOrNull() != trimmed) {
            recents = (listOf(trimmed) + recents.filterNot { DictionarySearch.normalize(it) == key })
                .take(SearchHistory.LIMIT)
            scope.launch {
                try {
                    SearchHistory.remember(context, trimmed)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // The search itself has already been asked: a history that cannot be written is
                    // not worth failing it over, as Home's own store decides.
                    Log.w("Gaten", "Could not save recent search", error)
                }
            }
        }
    }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberScrollState()
    val fieldIntoView = remember { BringIntoViewRequester() }
    val firstRowIntoView = remember { BringIntoViewRequester() }

    // A restored draft arrives as a new [query] while this surface still holds the old field.
    LaunchedEffect(query) {
        if (field.text != query) {
            field = TextFieldValue(query, TextRange(query.length))
            asked = SearchQuery(query, now = false)
        }
    }

    // Focus, and with it the keyboard, belongs to the settled cover. While [inputActive] is false the
    // field is inert, so the request waits for the expansion instead of raising a keyboard over a
    // surface that is still arriving. Whether focus also raises the IME is the field's own decision,
    // because it depends on who is reading.
    LaunchedEffect(inputActive) {
        if (inputActive) {
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    // The words kept on this device, read once, on the surface that shows them.
    LaunchedEffect(context) {
        recents = try {
            SearchHistory.recents(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A history that cannot be read leaves the empty prompt its examples.
            Log.w("Gaten", "Could not read recent searches", error)
            emptyList()
        }
    }

    // Home's own list behaviour: dragging the rows puts the keyboard away. Read off the list's own
    // drag interactions, so a programmatic scroll — the list returning to the top for a new answer —
    // leaves the keyboard exactly where it is.
    LaunchedEffect(listState, keyboard) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) keyboard?.hide()
        }
    }

    // The window is not laid out to insets — it keeps fitInsetsTypes at 0 and the capture never
    // moves — so the cover composes against the IME itself and tracks the platform's own progress,
    // with no tween of ours. The navigation bar is the floor, not an addition: a docked keyboard
    // already covers it, and a floating one that reports no docked inset leaves the viewport alone
    // rather than inventing a keyboard height for it.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(imeBottom, navBottom)

    // The one query path: the helper's own bounded order, deduplication and 24-row contract. Never
    // WordScanner (that finds words inside a line of OCR output) and never a second normalizer.
    // It reads SQLite, so it runs off the main thread. A keystroke that lands while an answer is on
    // its way cancels that question with its own wait, and only the answer to the text in the field
    // is ever published. A query that throws is the rail, never an invented empty list: the
    // repository may have opened and still fail to be read, and that is this surface's news to tell.
    LaunchedEffect(asked, repo, failure) {
        val question = asked
        // A new question clears the last one's failure, so the rail gives way to the answer.
        searchFailure = null
        if (repo == null || failure != null || DictionarySearch.normalize(question.text).isEmpty()) {
            // The parent's rail carries a failure it already resolved, and an emptied field has
            // nothing to ask about: those rows answer nothing any more, so they go at once rather
            // than a debounce later.
            answers = SearchAnswers(question.text, emptyList())
            return@LaunchedEffect
        }
        if (!question.now) delay(SearchDebounceMs)
        // Whether a query is searchable is part of asking it: a query the dictionary cannot answer
        // says so a debounce after the reader stopped, rather than on every key.
        val normalized = DictionarySearch.normalize(question.text)
        if (!DictionarySearch.isSearchable(normalized)) {
            answers = SearchAnswers(question.text, emptyList())
            return@LaunchedEffect
        }
        val found = try {
            withContext(Dispatchers.IO) {
                DictionarySearch.search(
                    question.text,
                    repo::lookup,
                    repo::lookupPrefix,
                    repo::lookupLoose,
                    limit = DictionarySearch.DEFAULT_LIMIT
                )
            }
        } catch (cancelled: CancellationException) {
            // The reader has typed on: this question is not answered, it is abandoned.
            throw cancelled
        } catch (error: Exception) {
            // The repository opened and its read failed. The rail names the cause and offers the one
            // thing that can be done about it — the same question, asked again.
            searchFailure = error.message ?: error.toString()
            answers = SearchAnswers(question.text, emptyList())
            return@LaunchedEffect
        }
        // A superseded run is cancelled, and a cancelled coroutine does not publish: those rows
        // answer text the field has left, and the rows for what it holds now are already coming.
        currentCoroutineContext().ensureActive()
        answers = SearchAnswers(question.text, found)
    }

    // The rail's one way out of a query that failed: the same text, asked again at once. Flipping
    // [SearchQuery.attempt] is what makes it a new question to the effect above.
    val retry: (() -> Unit)? = if (searchFailure == null) {
        null
    } else {
        { asked = asked.copy(now = true, attempt = asked.attempt + 1) }
    }

    val failed = repo == null || failure != null || searchFailure != null
    val hits = answers.hits
    val normalized = remember(answers.query) { DictionarySearch.normalize(answers.query) }
    val searchable = normalized.isNotEmpty() && DictionarySearch.isSearchable(normalized)
    val phase = when {
        failed -> SearchPhase.Failed
        normalized.isEmpty() -> SearchPhase.Prompt
        !searchable -> SearchPhase.Unsupported
        hits.isEmpty() -> SearchPhase.NoMatches
        else -> SearchPhase.Results
    }

    // A new answer starts the list at its top: the rows are the answer to what was last asked, not
    // where the reader had scrolled to for the one before it.
    LaunchedEffect(answers.query) { listState.scrollTo(0) }

    val prompt = stringResource(R.string.lookup_search_prompt)
    val unsupported = stringResource(R.string.lookup_search_unsupported)
    val noMatches = stringResource(R.string.lookup_no_words)
    val tooLongMessage = stringResource(R.string.lookup_search_too_long)
    val count = when {
        hits.size >= DictionarySearch.DEFAULT_LIMIT -> stringResource(R.string.lookup_search_capped)
        hits.size == 1 -> stringResource(R.string.lookup_search_one_result)
        else -> stringResource(R.string.lookup_search_results, hits.size)
    }
    // While the reader types the next word, the rows on screen still answer the last one: the count
    // says which search they belong to — Home's own "5 results for neko" — rather than letting the
    // reader take them for an answer to the text now in the field.
    val typed = remember(field.text) { DictionarySearch.normalize(field.text) }
    val countLine = if (normalized.isNotEmpty() && normalized != typed) {
        stringResource(R.string.lookup_search_for_query, count, normalized)
    } else {
        count
    }
    // What the prefix suggestions are grouped under, in Home's own words: the query they start with.
    val startsWith = stringResource(R.string.lookup_search_starts_with, normalized)
    // Why "neko" can be an exact ねこ match: the kana the helper read the romaji as.
    val kana = remember(normalized) { DictionarySearch.kanaFromRomaji(normalized) }
    // What the polite live region says for the state the reader is in now. The failure rail
    // announces itself, and the over-limit helper speaks for the surface while it is up.
    val announcement = when {
        tooLong -> tooLongMessage
        phase == SearchPhase.Prompt -> prompt
        phase == SearchPhase.Unsupported -> "$prompt. $unsupported"
        phase == SearchPhase.NoMatches -> noMatches
        phase == SearchPhase.Results -> countLine
        else -> null
    }

    val onEdit: (TextFieldValue) -> Unit = { next ->
        // The field is bounded exactly as Home's is: the limit cuts the text rather than refusing
        // the edit, and never between the halves of a surrogate pair.
        val text = boundQuery(next.text)
        tooLong = text.length < next.text.length
        field = if (text.length == next.text.length) {
            next
        } else {
            TextFieldValue(text, TextRange(text.length))
        }
        onQueryChange(text)
        // Every edit is a query, composing or not: Home's own field searches the text as it is typed,
        // and a Japanese IME's composing word is looked up like any other. The debounce is what keeps
        // that from being one lookup per keystroke, and it is the only thing standing between the
        // query and its answer — there is no commit to wait for and no separate way to ask.
        asked = SearchQuery(text, now = false)
    }
    // The IME's own Search: Home's submit — the word is remembered, the field blurs, and the same
    // query is asked at once rather than a debounce later. Nothing is picked and the sheet stays
    // where it is.
    val onSearchAction: () -> Unit = {
        rememberQuery(field.text)
        asked = SearchQuery(field.text, now = true)
        focusManager.clearFocus()
        keyboard?.hide()
    }

    // A chip is a choice rather than typing: the word lands in the field and is asked for at once,
    // and — as on Home — it is remembered, because the reader acted on it.
    val onChip: (String) -> Unit = { word ->
        field = TextFieldValue(word, TextRange(word.length))
        onQueryChange(word)
        rememberQuery(word)
        asked = SearchQuery(word, now = true)
    }

    // A picked row reports the query that really found it, and remembers that query rather than
    // whatever the reader has typed on to since.
    val pick: (DictEntry) -> Unit = { chosen ->
        rememberQuery(answers.query)
        onPick(chosen, answers.query)
    }

    // The empty prompt's own words to tap, or nothing at all for the states that offer no choice.
    val chips: (@Composable () -> Unit)? = if (phase == SearchPhase.Prompt) {
        { PromptChips(recents = recents, onChip = onChip) }
    } else {
        null
    }

    Surface(
        color = colors.surface,
        modifier = modifier
            .fillMaxSize()
            // The mode's own name, announced once when the cover takes the screen. The painted
            // title is omitted in a measure too narrow for it; this one never is.
            .semantics { paneTitle = title }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Top/side/cutout safe insets, and the IME's own edges where a landscape keyboard
                // reports them. The paper still paints the whole window: only the content is inset.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.union(WindowInsets.ime)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
        ) {
            SearchTopBar(title = title, onBack = onBack, onClose = onClose)

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val inset = if (maxWidth >= NarrowWidth) Spacing.content else Spacing.l
                val countHeight = with(density) { type.labelMedium.lineHeight.toDp() }
                val fieldMinHeight = maxOf(
                    FieldMinHeight,
                    with(density) { type.bodyMedium.lineHeight.toDp() } + FieldPaddingV * 2
                )
                // What the query block and the count cost before a row can start: the field, the gap
                // under it and the count line. There is no label above the field any more.
                val chrome = fieldMinHeight + Spacing.m + countHeight
                val viewportHeight = maxHeight - bottomInset
                // Read here, not inside the column below: a nested layout scope hides its parent's
                // (the layout scopes are DSL-marked), and this is the width the column is measured
                // against.
                val viewportWidth = maxWidth
                val compact = viewportHeight < chrome + ReadableListHeight

                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
                        .widthIn(max = ContentColumnMaxWidth)
                        .padding(horizontal = inset)
                        .padding(bottom = bottomInset)
                ) {
                    if (compact) {
                        // Not enough height for a pinned query block and a readable list: the
                        // 48 dp bar stays, and the query, its helper, the count and the rows share
                        // one scroller. Nothing is nested inside anything else, and no font or
                        // target shrinks to make room.
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(listState)
                        ) {
                            QueryBlock(
                                field = field,
                                onEdit = onEdit,
                                onSearchAction = onSearchAction,
                                focusRequester = focusRequester,
                                onFocusChange = { focused = it },
                                tooLong = tooLong,
                                intoView = fieldIntoView,
                                label = fieldLabel,
                                placeholder = placeholder,
                                enabled = inputActive
                            )
                            when (phase) {
                                // The rail carries its own 16 dp of air above and below.
                                SearchPhase.Failed -> FailureRail(
                                    failure = searchFailure ?: failure,
                                    onRetry = retry
                                )
                                SearchPhase.Results -> {
                                    Spacer(Modifier.height(Spacing.m))
                                    CountLine(kana = kana, count = countLine, announcement = announcement)
                                    Spacer(Modifier.height(Spacing.m))
                                    AnswerRows(
                                        hits = hits,
                                        startsWith = startsWith,
                                        onPick = pick,
                                        firstRowModifier = Modifier
                                            .bringIntoViewRequester(firstRowIntoView)
                                    )
                                }
                                else -> {
                                    Spacer(Modifier.height(Spacing.m))
                                    CentredState(
                                        // The column's own measure, not the window's: the state
                                        // group never overflows the inset it is drawn inside.
                                        viewportWidth = (viewportWidth - inset * 2).coerceAtLeast(0.dp),
                                        viewportHeight = viewportHeight,
                                        minHeight = null,
                                        artwork = if (phase == SearchPhase.NoMatches) {
                                            R.drawable.ic_lookup_no_matches
                                        } else {
                                            null
                                        },
                                        message = if (phase == SearchPhase.NoMatches) {
                                            noMatches
                                        } else {
                                            prompt
                                        },
                                        supporting = if (phase == SearchPhase.Unsupported) {
                                            unsupported
                                        } else {
                                            null
                                        },
                                        prompt = chips,
                                        announcement = announcement
                                    )
                                }
                            }
                            Spacer(Modifier.height(Spacing.l))
                        }
                        // The field is the active control, so an IME arrival or a regained focus
                        // scrolls it back; the first row follows only when the viewport can hold
                        // both, because the row must not push out the field that asked for it.
                        LaunchedEffect(focused, imeBottom) {
                            if (focused || imeBottom > 0.dp) {
                                fieldIntoView.bringIntoView()
                                if (imeBottom > 0.dp && viewportHeight >= chrome + CardHeight) {
                                    firstRowIntoView.bringIntoView()
                                }
                            }
                        }
                    } else {
                        QueryBlock(
                            field = field,
                            onEdit = onEdit,
                            onSearchAction = onSearchAction,
                            focusRequester = focusRequester,
                            onFocusChange = { focused = it },
                            tooLong = tooLong,
                            intoView = null,
                            label = fieldLabel,
                            placeholder = placeholder,
                            enabled = inputActive
                        )
                        when (phase) {
                            SearchPhase.Failed -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                FailureRail(failure = searchFailure ?: failure, onRetry = retry)
                            }

                            SearchPhase.Results -> {
                                Spacer(Modifier.height(Spacing.m))
                                CountLine(kana = kana, count = countLine, announcement = announcement)
                                Spacer(Modifier.height(Spacing.m))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(listState)
                                        // The last row can come 16 dp clear of the keyboard.
                                        .padding(bottom = Spacing.l)
                                ) {
                                    AnswerRows(hits = hits, startsWith = startsWith, onPick = pick)
                                }
                            }

                            else -> {
                                Spacer(Modifier.height(Spacing.m))
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    CentredState(
                                        viewportWidth = maxWidth,
                                        viewportHeight = maxHeight,
                                        minHeight = maxHeight,
                                        artwork = if (phase == SearchPhase.NoMatches) {
                                            R.drawable.ic_lookup_no_matches
                                        } else {
                                            null
                                        },
                                        message = if (phase == SearchPhase.NoMatches) {
                                            noMatches
                                        } else {
                                            prompt
                                        },
                                        supporting = if (phase == SearchPhase.Unsupported) {
                                            unsupported
                                        } else {
                                            null
                                        },
                                        prompt = chips,
                                        announcement = announcement
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The cover's bar: a leading back-chevron that leaves search, the mode's title, and a trailing
 * close that ends the lookup. Two distinct actions at the two ends, so escape is always where it
 * has always been; the chevron follows the locale's own back direction.
 *
 * The title is painted only when the two 48 dp targets leave room for it: below that measure it is
 * the pane title, not a clipped word, that names the mode.
 */
@Composable
private fun SearchTopBar(title: String, onBack: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val titleWidth = remember(title, type, density) {
        with(density) { measurer.measure(title, style = type.titleLarge).size.width.toDp() }
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TopBarHeight)
    ) {
        // Read before the row: inside a nested layout scope this box's own width is hidden by the
        // scope marker, and the fit test needs it.
        val barWidth = maxWidth
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TopBarHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarAction(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                description = R.string.lookup_search_back,
                onClick = onBack
            )
            if (barWidth - MinTarget * 2 - Spacing.xs >= titleWidth) {
                Text(
                    text = title,
                    style = type.titleLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Spacing.xs)
                )
            }
            Spacer(Modifier.weight(1f))
            BarAction(
                icon = Icons.Rounded.Close,
                description = R.string.lookup_cd_close,
                onClick = onClose
            )
        }
    }
}

/** One bar control: a bare neutral glyph in a 48 dp target, with no backing of its own. */
@Composable
private fun BarAction(icon: ImageVector, @StringRes description: Int, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier
            .size(MinTarget)
            .pressOpacity(interactions)
    ) {
        Icon(
            icon,
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(GlyphSize)
        )
    }
}

/**
 * The query field, at Home's own search field: filled with `surfaceVariant`, outlined in the accent
 * only while focus is on it, with Home's own magnifier ahead of the text and the same placeholder
 * under it. There is no painted label — the field carries its name as its accessible label instead,
 * the way Home's own field does and the way the cover's bar above it already says what the task is.
 *
 * The field itself is unchanged in what it accepts: one logical line that scrolls its own text
 * horizontally, Japanese, kana, kanji and romaji through whatever IME the reader has, with no
 * autocorrect, no automatic capitalisation and no keyboard type forced on it. Its IME action is
 * Search, and focus raises the IME only for a reader who is not exploring by touch: with touch
 * exploration on, the field takes the focus and waits for the reader's own edit activation rather
 * than opening a keyboard over their reading.
 *
 * [enabled] is false while the cover is still arriving, so the field takes neither focus nor input
 * until its parent's transition has settled.
 *
 * [intoView] is set only where the field shares a scroller with the results, so an IME arrival can
 * bring the field back without a responder having to exist in the pinned layout.
 */
@Composable
private fun QueryBlock(
    field: TextFieldValue,
    onEdit: (TextFieldValue) -> Unit,
    onSearchAction: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    tooLong: Boolean,
    intoView: BringIntoViewRequester?,
    label: String,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    // Who is reading decides whether gaining focus may raise a keyboard. A touch-exploration reader
    // is moved by the IME, not helped by it: the field takes focus silently and their own double
    // tap on it opens the keyboard.
    val touchExploration = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    // The field grows with the reader's own line height rather than clipping it: 12 dp of air above
    // and below the text, and never under the house 52 dp.
    val fieldMinHeight = maxOf(
        FieldMinHeight,
        with(density) { type.bodyMedium.lineHeight.toDp() } + FieldPaddingV * 2
    )
    // The one answer focus gets: the field's own outline, invisible while the reader is elsewhere and
    // the accent when focus lands on it, fading in rather than appearing — Home's own focus ring.
    val outline by animateColorAsState(
        targetValue = if (focused) colors.primary else Color.Transparent,
        animationSpec = tween(Motion.SHORT),
        label = "query field outline"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        BasicTextField(
            value = field,
            onValueChange = onEdit,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (intoView == null) {
                        Modifier
                    } else {
                        Modifier.bringIntoViewRequester(intoView)
                    }
                )
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChange(it.isFocused)
                }
                // The field's own name, for a reader who cannot see the placeholder: the painted
                // label the cover used to carry above the field is gone, so this is what names it.
                .semantics { contentDescription = label },
            textStyle = type.bodyMedium.copy(color = colors.onSurface),
            singleLine = true,
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Unspecified,
                imeAction = ImeAction.Search,
                showKeyboardOnFocus = !touchExploration
            ),
            keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = fieldMinHeight)
                        .background(colors.surfaceVariant, ControlShape)
                        .border(FieldOutline, outline, ControlShape)
                        .padding(horizontal = FieldPaddingH, vertical = FieldPaddingV),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchGlyph(tint = colors.glyphInk())
                    Spacer(Modifier.width(FieldIconGap))
                    Box(modifier = Modifier.weight(1f)) {
                        if (field.text.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = type.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            }
        )
        if (tooLong) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.lookup_search_too_long),
                style = type.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * The ink Home draws a glyph in: pure black on the light ladder and `onSurface` on the dark one,
 * which is what its `iconPrimary` role is. Read off the ladder's own paper rather than the system's
 * dark setting, because the reader's own choice in Settings can override that.
 */
private fun ColorScheme.glyphInk(): Color = if (surface.luminance() > 0.5f) Color.Black else onSurface

/**
 * Home's own magnifier, drawn from the path its `InkIcon` draws: a 6.5 dp lens with the handle to
 * its lower right, inside the 24 dp viewport that icon set is built in, stroked at 1.8/24 of that
 * viewport and round at both ends. Decorative — the field's own label names the field — so it takes
 * the ink of the text beside it and carries no description of its own.
 */
@Composable
private fun SearchGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(GlyphSize)) {
        val unit = size.minDimension / 24f
        val stroke = 1.8f * unit
        drawCircle(
            color = tint,
            radius = 6.5f * unit,
            center = Offset(10.5f * unit, 10.5f * unit),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = tint,
            start = Offset(16f * unit, 16f * unit),
            end = Offset(21f * unit, 21f * unit),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * [text] cut to [DictionarySearch.QUERY_LIMIT] characters, never between the halves of a surrogate
 * pair — Home's own bounding of its field, so a paste past the limit is cut rather than refused.
 */
private fun boundQuery(text: String): String {
    if (text.length <= DictionarySearch.QUERY_LIMIT) return text
    val cut = text.substring(0, DictionarySearch.QUERY_LIMIT)
    return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
}

/**
 * The romaji explanation and the honest count, in one block so a polite live region can cover both.
 *
 * The count is what came back, never a total: at the helper's own limit it says so rather than
 * implying there is a next page, and it is the same string the reader hears, so no announcement
 * ever claims a number the list does not show. It keeps Home's own 4 dp under it, which the list's
 * row gap then follows.
 */
@Composable
private fun CountLine(
    kana: String?,
    count: String,
    announcement: String?,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs)
            .announce(announcement)
    ) {
        if (kana != null) {
            Text(
                text = stringResource(R.string.lookup_search_converted, kana),
                style = type.bodySmall,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        Text(
            text = count,
            style = type.labelMedium,
            color = colors.onSurfaceVariant
        )
    }
}

/** One line of an answer: a hit, or the heading Home puts over the terms that only start with it. */
private sealed interface AnswerLine {
    data class Hit(val hit: SearchHit) : AnswerLine

    data object PrefixHeading : AnswerLine
}

/**
 * Home's own order for one answer: every entry the query reached — as it is spelled, folded to kana,
 * deinflected, or through a collapsed reading — and then, under one heading, the terms that merely
 * start with it. The heading is a line of its own rather than something every suggestion repeats.
 */
private fun answerLines(hits: List<SearchHit>): List<AnswerLine> {
    val lines = ArrayList<AnswerLine>(hits.size + 1)
    hits.filter { it.match != SearchMatch.PREFIX }.forEach { lines.add(AnswerLine.Hit(it)) }
    val prefix = hits.filter { it.match == SearchMatch.PREFIX }
    if (prefix.isNotEmpty()) {
        lines.add(AnswerLine.PrefixHeading)
        prefix.forEach { lines.add(AnswerLine.Hit(it)) }
    }
    return lines
}

/**
 * The rows of one answer, at Home's own rhythm: a card per entry with the list's 12 dp between them,
 * and the prefix heading a step closer to the rows it names than to the entries above it.
 *
 * [firstRowModifier] is the compact layout's own handle on the first card, so an IME arrival can
 * bring one result into view under the field that asked for it.
 */
@Composable
private fun AnswerRows(
    hits: List<SearchHit>,
    startsWith: String,
    onPick: (DictEntry) -> Unit,
    modifier: Modifier = Modifier,
    firstRowModifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val lines = remember(hits) { answerLines(hits) }
    val firstHit = lines.indexOfFirst { it is AnswerLine.Hit }
    Column(modifier = modifier.fillMaxWidth()) {
        lines.forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(Spacing.m))
            when (line) {
                is AnswerLine.Hit -> ResultCard(
                    hit = line.hit,
                    onPick = onPick,
                    modifier = if (index == firstHit) firstRowModifier else Modifier
                )

                AnswerLine.PrefixHeading -> Text(
                    text = startsWith,
                    style = type.titleSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.s)
                )
            }
        }
    }
}

/**
 * One dictionary entry, as Home draws it: one `surfaceVariant` card holding the headword and its
 * common mark, the readings, the first three glosses, the part of speech, and — only when the query
 * reached the entry through a fold, a deinflection or a collapsed reading — the form that did reach
 * it, in the accent. It grows to whatever those lines need: no fixed height, no thumbnail, no second
 * column, and nothing on it that is not a fact about the entry.
 *
 * The whole card is one button, so a screen reader hears the entry and then the one thing a card can
 * do — open the dictionary entry — as a single node. Nothing inside it is separately tappable.
 */
@Composable
private fun ResultCard(hit: SearchHit, onPick: (DictEntry) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val entry = hit.entry
    val interactions = remember { MutableInteractionSource() }
    val reading = entry.kana.joinToString("・")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(colors.surfaceVariant, ControlShape)
            .clickable(
                interactionSource = interactions,
                indication = null,
                onClickLabel = stringResource(R.string.lookup_cd_open_entry),
                role = Role.Button,
                onClick = { onPick(entry) }
            )
            .pressOpacity(interactions)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.primary,
                    style = type.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (entry.common) {
                    Spacer(Modifier.width(Spacing.s))
                    // Home's own mark for a common word: the accent's container, 12/4 dp of air and
                    // the control corner, with the word itself in the ink that reads on it.
                    Box(
                        modifier = Modifier
                            .background(colors.primaryContainer, ControlShape)
                            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
                    ) {
                        Text(
                            text = stringResource(R.string.lookup_search_common),
                            style = type.labelSmall,
                            color = colors.onPrimaryContainer,
                            maxLines = 1
                        )
                    }
                }
            }
            if (reading.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = reading,
                    style = type.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (entry.glosses.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = entry.glosses.take(3).joinToString("; "),
                    style = type.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            entry.pos.firstOrNull()?.let { pos ->
                Spacer(Modifier.height(Spacing.xs))
                // Home's own pill for the part of speech: the card's paper, 8/4 dp of air and the
                // brand tile's 10 dp corner.
                Box(
                    modifier = Modifier
                        .background(colors.surface, PillShape)
                        .padding(horizontal = Spacing.s, vertical = Spacing.xs)
                ) {
                    Text(
                        text = pos,
                        style = type.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (hit.match == SearchMatch.NORMALIZED || hit.match == SearchMatch.LOOSE) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.lookup_search_from, hit.form),
                    style = type.bodySmall,
                    color = colors.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(CardGlyphSize)
        )
    }
}

/**
 * The empty prompt's own words to tap: Home's examples until the reader has searched at all, and
 * their own searched words under the line that names them after that. A chip is a choice rather than
 * typing, so it is asked for at once, and it carries the action it performs as its own name — the
 * word alone does not say what tapping it will do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PromptChips(recents: List<String>, onChip: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val firstSearch = recents.isEmpty()
    val words = if (firstSearch) QueryExamples else recents
    Column(modifier = modifier.fillMaxWidth()) {
        if (!firstSearch) {
            Text(
                text = stringResource(R.string.lookup_search_recent),
                style = type.labelLarge,
                color = colors.onSurfaceVariant
            )
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = if (firstSearch) Spacing.l else Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            words.forEach { word ->
                val interactions = remember(word) { MutableInteractionSource() }
                val chipLabel = stringResource(R.string.lookup_search_example, word)
                Box(
                    modifier = Modifier
                        .heightIn(min = ChipMinHeight)
                        .clip(ControlShape)
                        .background(colors.surfaceVariant, ControlShape)
                        .clickable(
                            interactionSource = interactions,
                            indication = null,
                            role = Role.Button,
                            onClick = { onChip(word) }
                        )
                        .pressOpacity(interactions)
                        .padding(horizontal = Spacing.l, vertical = Spacing.m)
                        .semantics {
                            contentDescription = chipLabel
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = word,
                        style = type.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * A state with nothing to list: the group centres in the viewport it is given and scrolls when it
 * cannot fit there, so a large text scale can never put the top of the group out of reach. It is
 * the sheet's empty-state shape without its two recovery actions — inside search the field itself
 * and Back are the recovery, and repeating Search dictionary here would be a second button for the
 * mode the reader is already in.
 *
 * The artwork is the sheet's own no-matches mark, at the sheet's own size rule and tinted by the
 * same role, drawn directly rather than through `LookupEmptyState` so no recovery button comes with
 * it. It is decorative: the caption carries the fact.
 *
 * [minHeight] is the viewport the group centres inside, with its own scroller for the overflow.
 * Null means the group already sits in the cover's one scroller, where a second scroller would be a
 * scroller inside a scroller; it then keeps the same 24 dp of air and lays out at its own height.
 *
 * [prompt] is the empty prompt's own words to tap, drawn above the artwork at Home's own 24 dp; the
 * states that offer nothing to tap leave it null.
 */
@Composable
private fun CentredState(
    viewportWidth: Dp,
    viewportHeight: Dp,
    minHeight: Dp?,
    artwork: Int?,
    message: String,
    supporting: String?,
    announcement: String?,
    prompt: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val artworkSize = if (viewportWidth < ArtworkNarrowWidth || viewportHeight < ArtworkShortHeight) {
        ArtworkCompact
    } else {
        ArtworkSize
    }
    val captionWidth = minOf(CaptionMaxWidth, viewportWidth)
    // At least the viewport tall, so the group centres when it fits; taller than the viewport when
    // it does not, which is what hands the overflow to this one scroller.
    val layout = if (minHeight == null) {
        Modifier
    } else {
        Modifier
            .heightIn(min = minHeight)
            .verticalScroll(rememberScrollState())
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(layout)
            .padding(vertical = Spacing.xxl)
            .announce(announcement),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (prompt != null) {
            prompt()
            Spacer(Modifier.height(Spacing.xl))
        }
        if (artwork != null) {
            Icon(
                painter = painterResource(artwork),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(minOf(artworkSize, viewportWidth))
            )
            Spacer(Modifier.height(Spacing.l))
        }
        Text(
            text = message,
            style = type.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = captionWidth)
        )
        if (supporting != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = supporting,
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = captionWidth)
            )
        }
    }
}

/**
 * The dictionary failure: the rail, its real cause, the field and Back/Close still available, and —
 * when the failure was a query's rather than the repository's — the one action that can help, asking
 * the same query again.
 */
@Composable
private fun FailureRail(
    failure: String?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    InlineStatusRail(
        text = stringResource(R.string.lookup_dict_error),
        glyph = RailGlyph.Error,
        tone = RailTone.Error,
        supporting = failure?.let { stringResource(R.string.lookup_dict_error_cause, it) },
        action = onRetry?.let { retry ->
            RailAction(
                label = stringResource(R.string.lookup_search_retry),
                onClick = retry
            )
        },
        modifier = modifier
    )
}

/**
 * A polite live region for the line that changes with the query: the count, the empty prompt, the
 * unsupported pair or the over-limit helper.
 *
 * The painted line is immediate — results never wait for an announcement — while the region's own
 * announced value settles for one [Motion.SHORT] step, so a burst of queries inside that window is
 * announced once instead of once per keystroke. Nothing is delayed but the announcement.
 *
 * It is marked on the wrapper rather than on the text: a live region on a container is what makes a
 * screen reader announce a change to the text inside it, and a container with children is not a
 * traversal stop of its own, so this adds no node the reader has to swipe past.
 */
@Composable
private fun Modifier.announce(text: String?): Modifier {
    var settled by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (settled != text) {
            delay(Motion.SHORT.toLong())
            settled = text
        }
    }
    if (text == null) return this
    return this.semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = settled ?: text
    }
}
