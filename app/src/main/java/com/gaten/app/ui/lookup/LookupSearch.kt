/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.DictionarySearch
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.dict.SearchHit
import com.gaten.app.dict.SearchMatch
import com.gaten.app.ui.components.pressOpacity
import com.gaten.app.ui.theme.Motion
import com.gaten.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/** The top bar's own height, and the floor every control in it keeps. */
private val TopBarHeight = 48.dp
private val MinTarget = 48.dp
private val GlyphSize = 20.dp

/** The house content column, insets included, and the width below which that inset narrows. */
private val ContentColumnMaxWidth = 560.dp
private val NarrowWidth = 360.dp

/**
 * The shared field recipe, unchanged from every other field in the app: a 14 dp radius, a 1 dp
 * neutral outline, 16/12 dp of padding, and the accent's 2 dp focus outline 2 dp outside the
 * field's own edge. The 2 dp is always reserved so the field does not jump when focus lands on it.
 */
private val FieldShape = RoundedCornerShape(14.dp)
private val FocusRingShape = RoundedCornerShape(16.dp)
private val FieldMinHeight = 49.dp
private val FieldRingOffset = 2.dp
private val FieldOutline = 1.dp
private val FocusRing = 2.dp
private val FieldPaddingH = 16.dp
private val FieldPaddingV = 12.dp

/** One result: a flat row, its own hairline under it, and nothing else. */
private val RowMinHeight = 80.dp
private val RuleHeight = 1.dp

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
 * with the repository the overlay already has open. It is synchronous and bounded, so the list and
 * its count are published together and nothing stands between the query and its answer: no spinner,
 * no skeleton, no debounce, no minimum wait. A failure is the rail, never an invented zero.
 *
 * @param repo the repository already open for this overlay, or null when it could not be opened.
 * @param failure the sanitized readable cause, already resolved by the parent.
 * @param query the draft, owned by the parent so it survives closing search. The cursor and the
 *   IME's composing range belong to this field and start at the end of a restored draft.
 * @param onQueryChange the accepted text, as typed — normalization belongs to the helper.
 * @param onBack leave search and keep the draft; the saved sheet is already laid out beneath.
 * @param onClose end the lookup, IME or not.
 * @param onPick the chosen entry and the committed query it came from, for the parent to reveal in
 *   the sheet and to label the provenance with.
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
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current

    val title = stringResource(R.string.lookup_search)

    // The field is the draft plus what only a field has: the caret and the IME's composing range.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    // The text the results belong to — the field's, unless a Japanese IME is mid-word.
    var committed by remember { mutableStateOf(query) }
    var tooLong by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val listState = rememberScrollState()
    val fieldIntoView = remember { BringIntoViewRequester() }
    val firstRowIntoView = remember { BringIntoViewRequester() }

    // A restored draft arrives as a new [query] while this surface still holds the old field.
    LaunchedEffect(query) {
        if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    }

    // The field takes the keyboard's focus as soon as the cover is composed. Whether that also
    // raises the IME is the field's own decision, because it depends on who is reading.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The window is not laid out to insets — it keeps fitInsetsTypes at 0 and the capture never
    // moves — so the cover composes against the IME itself and tracks the platform's own progress,
    // with no tween of ours. The navigation bar is the floor, not an addition: a docked keyboard
    // already covers it, and a floating one that reports no docked inset leaves the viewport alone
    // rather than inventing a keyboard height for it.
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = maxOf(imeBottom, navBottom)

    val failed = repo == null || failure != null
    val normalized = remember(committed) { DictionarySearch.normalize(committed) }
    val searchable = normalized.isNotEmpty() && DictionarySearch.isSearchable(normalized)
    // The one query path: the helper's own bounded order, deduplication and 24-row contract. Never
    // WordScanner (that finds words inside a line of OCR output) and never a second normalizer.
    val hits = remember(committed, repo, failure) {
        if (repo == null || failure != null || !searchable) {
            emptyList<SearchHit>()
        } else {
            DictionarySearch.search(
                committed,
                repo::lookup,
                repo::lookupPrefix,
                repo::lookupLoose,
                limit = DictionarySearch.DEFAULT_LIMIT
            )
        }
    }

    val phase = when {
        failed -> SearchPhase.Failed
        normalized.isEmpty() -> SearchPhase.Prompt
        !searchable -> SearchPhase.Unsupported
        hits.isEmpty() -> SearchPhase.NoMatches
        else -> SearchPhase.Results
    }

    // A new committed query starts the list at its top: the answer to what was just typed is the
    // first row, not where the reader had scrolled to for the previous one.
    LaunchedEffect(committed) { listState.scrollTo(0) }

    val prompt = stringResource(R.string.lookup_search_prompt)
    val unsupported = stringResource(R.string.lookup_search_unsupported)
    val noMatches = stringResource(R.string.lookup_no_words)
    val tooLongMessage = stringResource(R.string.lookup_search_too_long)
    val count = when {
        hits.size >= DictionarySearch.DEFAULT_LIMIT -> stringResource(R.string.lookup_search_capped)
        hits.size == 1 -> stringResource(R.string.lookup_search_one_result)
        else -> stringResource(R.string.lookup_search_results, hits.size)
    }
    // Why "neko" can be an exact ねこ match: the kana the helper read the romaji as.
    val kana = remember(normalized) { DictionarySearch.kanaFromRomaji(normalized) }
    // What the polite live region says for the state the reader is in now. The failure rail
    // announces itself, and the over-limit helper speaks for the surface while it is up.
    val announcement = when {
        tooLong -> tooLongMessage
        phase == SearchPhase.Prompt -> prompt
        phase == SearchPhase.Unsupported -> "$prompt. $unsupported"
        phase == SearchPhase.NoMatches -> noMatches
        phase == SearchPhase.Results -> count
        else -> null
    }

    val onEdit: (TextFieldValue) -> Unit = { next ->
        val text = next.text
        if (text.codePointCount(0, text.length) > DictionarySearch.QUERY_LIMIT) {
            // The whole edit is refused — field, results and helper alike — rather than searching a
            // truncated paste as if the reader had typed it.
            tooLong = true
        } else {
            tooLong = false
            field = next
            onQueryChange(text)
            // A composing fragment is not a query yet: the last committed results stay, and nothing
            // calls the fragment unsupported, until the IME finishes the word.
            if (next.composition == TextRange.Zero) committed = text
        }
    }
    // IME Search commits what is in the field, runs the same search and takes the keyboard away
    // without picking a row or moving focus off the field.
    val onSearchAction: () -> Unit = {
        committed = field.text
        keyboard?.hide()
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
                val labelHeight = with(density) { type.labelMedium.lineHeight.toDp() }
                val fieldMinHeight = maxOf(
                    FieldMinHeight,
                    with(density) { type.bodyMedium.lineHeight.toDp() } + FieldPaddingV * 2
                )
                // What the query block, its helper and the count cost before a row can start.
                val chrome = Spacing.m + labelHeight + Spacing.s + fieldMinHeight + Spacing.m +
                    labelHeight
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
                                intoView = fieldIntoView
                            )
                            when (phase) {
                                // The rail carries its own 16 dp of air above and below.
                                SearchPhase.Failed -> FailureRail(failure)
                                SearchPhase.Results -> {
                                    Spacer(Modifier.height(Spacing.m))
                                    CountLine(kana = kana, count = count, announcement = announcement)
                                    Spacer(Modifier.height(Spacing.s))
                                    hits.forEachIndexed { index, hit ->
                                        ResultRow(
                                            hit = hit,
                                            onPick = { chosen -> onPick(chosen, committed) },
                                            modifier = if (index == 0) {
                                                Modifier.bringIntoViewRequester(firstRowIntoView)
                                            } else {
                                                Modifier
                                            }
                                        )
                                    }
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
                                if (imeBottom > 0.dp && viewportHeight >= chrome + RowMinHeight) {
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
                            intoView = null
                        )
                        when (phase) {
                            SearchPhase.Failed -> Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                FailureRail(failure)
                            }

                            SearchPhase.Results -> {
                                Spacer(Modifier.height(Spacing.m))
                                CountLine(kana = kana, count = count, announcement = announcement)
                                Spacer(Modifier.height(Spacing.s))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .verticalScroll(listState)
                                        // The last row can come 16 dp clear of the keyboard.
                                        .padding(bottom = Spacing.l)
                                ) {
                                    hits.forEach { hit -> ResultRow(hit = hit, onPick = { chosen -> onPick(chosen, committed) }) }
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
 * The labelled query field.
 *
 * The label is painted, always — a placeholder is a hint, not a label. The hint doubles as the
 * field's accessible hint by being inside the field's own merged node, and it disappears the moment
 * there is text. There is no leading search icon: the bar and the label already state the task.
 *
 * The field is one logical line that scrolls its own text horizontally; it accepts Japanese, kana,
 * kanji and romaji through whatever IME the reader has, with no autocorrect, no automatic
 * capitalisation and no keyboard type forced on it. Its IME action is Search, and focus raises the
 * IME only for a reader who is not exploring by touch: with touch exploration on, the field takes
 * the focus and waits for the reader's own edit activation rather than opening a keyboard over
 * their reading.
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
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    val context = LocalContext.current
    val hint = stringResource(R.string.lookup_search_query_hint)
    var focused by remember { mutableStateOf(false) }
    // Who is reading decides whether gaining focus may raise a keyboard. A touch-exploration reader
    // is moved by the IME, not helped by it: the field takes focus silently and their own double
    // tap on it opens the keyboard.
    val touchExploration = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    }
    // The field grows with the reader's own line height rather than clipping it: 12 dp of air above
    // and below the text, and never under the house 49 dp.
    val fieldMinHeight = maxOf(
        FieldMinHeight,
        with(density) { type.bodyMedium.lineHeight.toDp() } + FieldPaddingV * 2
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.lookup_search_query_label),
            style = type.labelMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.s))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // The ring's offset is always reserved, so focus does not move the field and the
                // ring can sit outside the field's own 1 dp outline.
                .padding(FieldRingOffset)
                .then(
                    if (focused) {
                        Modifier.border(FocusRing, colors.primary, FocusRingShape)
                    } else {
                        Modifier
                    }
                )
        ) {
            BasicTextField(
                value = field,
                onValueChange = onEdit,
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
                    },
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
                            .border(FieldOutline, colors.outlineVariant, FieldShape)
                            .background(colors.surface, FieldShape)
                            .padding(horizontal = FieldPaddingH, vertical = FieldPaddingV),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (field.text.isEmpty()) {
                                Text(
                                    text = hint,
                                    style = type.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            innerTextField()
                        }
                        // The Clear target's 48 dp is reserved whether or not it is there, so the
                        // reader's own text never runs under a control that appears as they type.
                        if (field.text.isEmpty()) {
                            Spacer(Modifier.size(MinTarget))
                        } else {
                            ClearTarget(
                                onClear = {
                                    onEdit(TextFieldValue(""))
                                    // Clearing keeps the reader in the field they are editing.
                                    focusRequester.requestFocus()
                                }
                            )
                        }
                    }
                }
            )
        }
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

/** The field's trailing action: empties the query, keeps the field and its focus. */
@Composable
private fun ClearTarget(onClear: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClear,
        interactionSource = interactions,
        modifier = Modifier
            .size(MinTarget)
            .pressOpacity(interactions)
    ) {
        Icon(
            Icons.Rounded.Cancel,
            contentDescription = stringResource(R.string.lookup_cd_clear_query),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(GlyphSize)
        )
    }
}

/**
 * The romaji explanation and the honest count, in one block so a polite live region can cover both.
 *
 * The count is what came back, never a total: at the helper's own limit it says so rather than
 * implying there is a next page, and it is the same string the reader hears, so no announcement
 * ever claims a number the list does not show.
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
    Column(modifier = modifier.fillMaxWidth().announce(announcement)) {
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

/**
 * One result: the entry's own headword, its distinct readings, its first gloss and how it was
 * reached — a flat full-width row with a hairline under it, and nothing that is not a fact about
 * the entry. No card, thumbnail, kana tile, index, checkmark, star or leading glyph, no fixed
 * height and no second column: at 160% every line wraps and the row grows.
 *
 * The whole row is one button, so a screen reader hears the entry and then the one thing a row can
 * do — open the dictionary entry — as a single node. Nothing inside it is separately tappable.
 */
@Composable
private fun ResultRow(
    hit: SearchHit,
    onPick: (DictEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val entry = hit.entry
    val reading = entry.kana.distinct().joinToString("・")
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = stringResource(R.string.lookup_cd_open_entry),
                    role = Role.Button,
                    onClick = { onPick(entry) }
                )
                .heightIn(min = RowMinHeight)
                .padding(vertical = Spacing.l)
        ) {
            Text(
                text = entry.primary,
                style = type.titleMedium,
                color = colors.onSurface
            )
            if (reading.isNotEmpty() && reading != entry.primary) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = reading,
                    style = type.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = entry.glosses.firstOrNull() ?: stringResource(R.string.lookup_no_meaning),
                style = type.bodyMedium,
                color = colors.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = provenance(hit),
                style = type.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(RuleHeight)
                .background(colors.outlineVariant)
        )
    }
}

/**
 * How this row was reached, in the helper's own vocabulary. [SearchMatch.NORMALIZED] covers both
 * the kana fold and deinflection and does not say which it was, so the returned form is what makes
 * the correspondence visible (ネコ → ねこ, 食べた → 食べる) instead of an invented badge.
 */
@Composable
private fun provenance(hit: SearchHit): String = when (hit.match) {
    SearchMatch.EXACT -> stringResource(R.string.lookup_match_exact)
    SearchMatch.NORMALIZED -> stringResource(R.string.lookup_match_form, hit.form)
    SearchMatch.LOOSE -> stringResource(R.string.lookup_match_loose, hit.form)
    SearchMatch.PREFIX -> stringResource(R.string.lookup_match_prefix, hit.form)
}

/**
 * A state with nothing to list: the group centres in the viewport it is given and scrolls when it
 * cannot fit there, so a large text scale can never put the top of the group out of reach. It is
 * the sheet's empty-state shape without its two recovery actions — inside search the field, Clear
 * query and Back are the recovery, and repeating Search dictionary here would be a second button
 * for the mode the reader is already in.
 *
 * The artwork is the sheet's own no-matches mark, at the sheet's own size rule and tinted by the
 * same role, drawn directly rather than through `LookupEmptyState` so no recovery button comes with
 * it. It is decorative: the caption carries the fact.
 *
 * [minHeight] is the viewport the group centres inside, with its own scroller for the overflow.
 * Null means the group already sits in the cover's one scroller, where a second scroller would be a
 * scroller inside a scroller; it then keeps the same 24 dp of air and lays out at its own height.
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

/** The dictionary failure: the rail, its real cause, and the field and Back/Close still available. */
@Composable
private fun FailureRail(failure: String?, modifier: Modifier = Modifier) {
    InlineStatusRail(
        text = stringResource(R.string.lookup_dict_error),
        glyph = RailGlyph.Error,
        tone = RailTone.Error,
        supporting = failure?.let { stringResource(R.string.lookup_dict_error_cause, it) },
        action = null,
        modifier = modifier
    )
}

/**
 * A polite live region for the line that changes with the query: the count, the empty prompt, the
 * unsupported pair or the over-limit helper.
 *
 * The painted line is immediate — results never wait for an announcement — while the region's own
 * announced value settles for one [Motion.SHORT] step, so a burst of committed queries inside that
 * window is announced once instead of once per keystroke. Nothing is delayed but the announcement.
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
