/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.search

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gaten.app.R
import com.gaten.app.dict.DictionarySearch
import com.gaten.app.dict.SearchHit
import com.gaten.app.dict.SearchMatch
import com.gaten.app.ui.components.pressScale
import com.gaten.app.ui.theme.Spacing

/** Example queries offered while the box is empty: a kanji word, a kana word, and an inflection. */
private val EXAMPLE_QUERIES = listOf("猫", "たべる", "食べた", "ありがとう", "学校", "面白い")

/**
 * The search tab's results: one Japanese query, the entries it found, and the way into one of them.
 *
 * Everything the screen shows lives in [SearchViewModel], which the shell owns and hands to every
 * search destination: switching tabs, opening a settings page, rotating the device or having the
 * process killed and restored all come back to the same query, the same results and the same reading
 * position. Which page is open is the shell's back stack, not this screen's: a tapped result only
 * hands over the id of the entry to open, and this screen neither knows nor decides what happens
 * next.
 *
 * @param onOpenWord where a tapped result goes, with the id of the dictionary entry to open.
 * @param focusRequested true, once, when the shell says the reader opened this screen to type; the
 *   field then takes the cursor and the request is handed back through [onFocusConsumed], so a
 *   restore, a return from a word and a rotation never raise the keyboard again.
 * @param onFocusConsumed called once that cursor request has been taken.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenWord: (Int) -> Unit,
    focusRequested: Boolean,
    onFocusConsumed: () -> Unit,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldFocus = remember { FocusRequester() }

    // The cursor is asked for exactly once, on the state the shell raises when the reader opens this
    // screen to type — a tap on the tab over the results, or Home's search card. A restored screen,
    // a return from a word, a rotation and every ordinary recomposition say nothing, so the keyboard
    // stays down for them.
    LaunchedEffect(focusRequested) {
        if (!focusRequested) return@LaunchedEffect
        fieldFocus.requestFocus()
        keyboard?.show()
        onFocusConsumed()
    }

    // The reading position belongs to the view model: this composition is thrown away whenever the
    // reader looks at another tab or opens a result, and the list has to come back where it was left.
    val listState = remember { LazyListState(viewModel.scrollIndex, viewModel.scrollOffset) }
    DisposableEffect(listState) {
        onDispose {
            viewModel.saveScroll(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
    }
    // A query the reader typed starts its own results at the top. A restored screen does not: its
    // position belongs to the query it was left on.
    var seenGeneration by remember { mutableIntStateOf(state.generation) }
    LaunchedEffect(state.generation) {
        if (state.generation == seenGeneration) return@LaunchedEffect
        seenGeneration = state.generation
        listState.scrollToItem(0)
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.search_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                // The keyboard would otherwise cover the results it is searching, and the field's
                // own inset is not the content's.
                .imePadding()
                .fillMaxSize()
        ) {
            SearchField(
                query = state.query,
                queryLimited = state.queryLimited,
                focusRequester = fieldFocus,
                onQueryChange = viewModel::onQueryChange,
                onSubmit = {
                    focus.clearFocus()
                    viewModel.searchNow()
                }
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.status == SearchStatus.Idle -> EmptyPrompt(onExample = viewModel::onQueryChange)

                    state.status == SearchStatus.Unsupported -> Notice(
                        title = stringResource(R.string.search_not_japanese),
                        body = stringResource(R.string.search_not_japanese_help)
                    )

                    state.status == SearchStatus.Failed -> Notice(
                        title = stringResource(R.string.search_error),
                        body = state.error?.let { stringResource(R.string.search_error_dictionary, it) },
                        actionLabel = stringResource(R.string.search_retry),
                        onAction = viewModel::retry
                    )

                    state.status == SearchStatus.Searching -> Loading()

                    state.hits.isEmpty() -> Notice(
                        title = stringResource(R.string.search_empty, state.query),
                        body = stringResource(R.string.search_empty_help)
                    )

                    else -> Results(
                        state = state,
                        listState = listState,
                        onSelect = { entryId ->
                            // The reader is going into a word: the keyboard has nothing left to do
                            // here, and must not come back for the results it returns to.
                            keyboard?.hide()
                            focus.clearFocus()
                            onOpenWord(entryId)
                        }
                    )
                }
            }
        }
    }
}

/**
 * The query box: the only control above the results, with the way to empty it and the note that
 * explains a query cut at [DictionarySearch.QUERY_LIMIT].
 */
@Composable
private fun SearchField(
    query: String,
    queryLimited: Boolean,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val limitNote: (@Composable () -> Unit)? = if (queryLimited) {
        { Text(stringResource(R.string.search_query_limit, DictionarySearch.QUERY_LIMIT)) }
    } else {
        null
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.search_cd_clear)
                    )
                }
            }
        },
        supportingText = limitNote,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() })
    )
}

/** The results, exact and folded matches first and the terms that start with the query behind them. */
@Composable
private fun Results(
    state: SearchState,
    listState: LazyListState,
    onSelect: (Int) -> Unit
) {
    val matches = remember(state.hits) { state.hits.filter { it.match != SearchMatch.PREFIX } }
    val suggestions = remember(state.hits) { state.hits.filter { it.match == SearchMatch.PREFIX } }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.l, vertical = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s)
    ) {
        item(key = "count") {
            Text(
                text = stringResource(R.string.search_results_count, state.hits.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(matches, key = { it.entry.id }) { hit -> ResultCard(hit, onSelect) }
        if (suggestions.isNotEmpty()) {
            item(key = "suggestions") {
                Text(
                    text = stringResource(
                        R.string.search_prefix_header,
                        DictionarySearch.normalize(state.query)
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.s)
                )
            }
            items(suggestions, key = { it.entry.id }) { hit -> ResultCard(hit, onSelect) }
        }
    }
}

/**
 * One result: the headword, its readings, the first few meanings and what the dictionary says it is —
 * a card rather than a line, because this is the whole of what the reader has to choose between
 * before opening one.
 */
@Composable
private fun ResultCard(hit: SearchHit, onSelect: (Int) -> Unit) {
    val entry = hit.entry
    // The scale shares the click's own interactions, so the card shrinks for exactly as long as the
    // press is the card's.
    val interactions = remember { MutableInteractionSource() }
    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactions)
            .clickable(
                interactionSource = interactions,
                indication = LocalIndication.current,
                onClickLabel = stringResource(R.string.search_cd_open, entry.primary),
                role = Role.Button,
                onClick = { onSelect(entry.id) }
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.primary,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.common) {
                        Spacer(Modifier.size(Spacing.s))
                        LabelChip(stringResource(R.string.search_common))
                    }
                }
                if (entry.kana.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = entry.kana.joinToString("・"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (entry.glosses.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = entry.glosses.take(3).joinToString("; "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                entry.pos.firstOrNull()?.let { pos ->
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = pos,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // The spelling that reached the entry, for a hit the reader did not type as such: the
                // kana fold or deinflection of a normalised one, and the collapsed reading of a loose
                // one (tokyo -> ときょ -> 東京).
                if (hit.match == SearchMatch.NORMALIZED || hit.match == SearchMatch.LOOSE) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = stringResource(R.string.search_matched_form, hit.form),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The box while the query is empty: what the search takes, and a few words to try. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyPrompt(onExample: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
    ) {
        Text(
            text = stringResource(R.string.search_examples_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = stringResource(R.string.search_examples_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.l))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.s)
        ) {
            EXAMPLE_QUERIES.forEach { example ->
                val interactions = remember { MutableInteractionSource() }
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .pressScale(interactions)
                        .clickable(
                            interactionSource = interactions,
                            indication = LocalIndication.current,
                            role = Role.Button
                        ) { onExample(example) }
                ) {
                    Text(
                        text = example,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m)
                    )
                }
            }
        }
    }
}

/** The search's own "working" state, for a query whose results are not on screen yet. */
@Composable
internal fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = stringResource(R.string.search_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A state with no results to show: what happened, and — when something can be done — the one action. */
@Composable
internal fun Notice(
    title: String,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Spacer(Modifier.height(Spacing.s))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.l))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** One small label on a result card: "common", so a word the dictionary calls frequent reads as one. */
@Composable
internal fun LabelChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp)
        )
    }
}
