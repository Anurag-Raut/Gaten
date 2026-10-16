/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.search

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gaten.app.R
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.SearchHit
import com.gaten.app.dict.SearchMatch
import com.gaten.app.ui.FeedbackHaptic
import com.gaten.app.ui.FeedbackSound
import com.gaten.app.ui.components.KanjiStrip
import com.gaten.app.ui.components.kanjiIn
import com.gaten.app.ui.lookup.sheetType
import com.gaten.app.ui.rememberFeedback
import com.gaten.app.ui.theme.Spacing
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.delay

/** How long the button says a note was just written before settling back to its steady label. */
private const val CELEBRATION_MS = 1600L

/** The gutter a gloss's number hangs in, wide enough for the two digits a long entry can reach. */
private val GlossNumberWidth = 28.dp

/**
 * One dictionary entry, by id: what the word is, what it means, the way into AnkiDroid, and the strip
 * of kanji it is written with.
 *
 * The route carries the id and not the entry, so the page can outlive the process: the row is
 * resolved out of the results the shared view model holds, and while a restored search is still
 * answering this screen keeps its place — the search's own loading state until it is done, and a
 * notice only once it is done and the row is really gone. Nothing here opens or closes a WaniKani
 * page: a kanji tap hands its character over, and the shell decides what that opens.
 *
 * This is the search screen's own detail rather than the overlay's result sheet — the two show the
 * same entry and share the kanji strip and the sheet's type scale, but this one has a screen to
 * itself, so it is a page that scrolls rather than a sheet that is capped.
 *
 * @param onBack where the top bar's arrow goes, back to the results.
 * @param onKanji where a tapped kanji of the word goes, with its character.
 * @param onAnkiSettings where the Anki link goes, so deck and note type can be set before an export.
 * @param onWaniKaniSettings where the WaniKani link goes, for the token the kanji pages are read with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchWordScreen(
    entryId: Int,
    onBack: () -> Unit,
    onKanji: (String) -> Unit,
    onAnkiSettings: () -> Unit,
    onWaniKaniSettings: () -> Unit,
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val open = state.hitFor(entryId)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The bottom inset belongs to the action pinned below, which knows how far up the gesture
        // band it has to stay; taking it here as well would pad the page twice.
        contentWindowInsets = WindowInsets.systemBars.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = open?.entry?.primary ?: stringResource(R.string.search_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.search_cd_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (open == null) {
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                if (state.status == SearchStatus.Searching) {
                    Loading()
                } else {
                    Notice(
                        title = stringResource(R.string.search_detail_unavailable),
                        body = stringResource(R.string.search_detail_unavailable_help)
                    )
                }
            }
        } else {
            WordBody(
                hit = open,
                deckName = state.ankiSettings.deckName,
                export = state.export(open.entry.id),
                ankiStatus = state.ankiStatus,
                onPrepareExport = { viewModel.prepareExport(open.entry) },
                onAdd = { viewModel.addToAnki(open.entry) },
                onShare = { viewModel.shareToAnkiDroid(open.entry) },
                onAnkiStatusChanged = viewModel::refreshAnkiStatus,
                onKanji = onKanji,
                onAnkiSettings = onAnkiSettings,
                onWaniKaniSettings = onWaniKaniSettings,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/**
 * The entry itself, with the export and the kanji strip it is made of. Its state arrives as
 * parameters — [SearchWordScreen] reads the view model once and hands each piece down — so nothing
 * here touches navigation or the shell.
 *
 * [export] is the entry's own Add-to-Anki state, which the view model keeps keyed by the entry: an
 * add that is under way is still under way after the reader has gone somewhere else and come back.
 * The entry scrolls; the export does not, and stays pinned above the gesture band.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordBody(
    hit: SearchHit,
    deckName: String,
    export: ExportState,
    ankiStatus: AnkiStatus,
    onPrepareExport: () -> Unit,
    onAdd: () -> Unit,
    onShare: () -> Boolean,
    onAnkiStatusChanged: () -> Unit,
    onKanji: (String) -> Unit,
    onAnkiSettings: () -> Unit,
    onWaniKaniSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = hit.entry
    val context = LocalContext.current
    val type = sheetType(compact = false)

    // The reader's answer to the permission request. Nothing is exported here: granting only lets
    // the button add, and the tap that adds is the reader's own.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        onAnkiStatusChanged()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val prepareExport = rememberUpdatedState(onPrepareExport)
    DisposableEffect(lifecycleOwner, entry.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) prepareExport.value()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A cue belongs to a note that has just been written, so it fires once on the state the button
    // puts the entry through: writing, then added. A restored or revisited screen starts already
    // settled and gives nothing back, and a duplicate gives nothing back either.
    val feedback = rememberFeedback()
    var writing by remember(entry.id) { mutableStateOf(false) }
    var celebrated by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(export) {
        when (export) {
            ExportState.Checking, ExportState.Busy -> writing = true

            is ExportState.Done -> {
                if (writing && export.outcome == ExportOutcome.ADDED) {
                    feedback.haptic(FeedbackHaptic.Confirm)
                    feedback.sound(FeedbackSound.Added)
                    celebrated = true
                }
                writing = false
            }

            ExportState.Idle, is ExportState.Failed -> writing = false
        }
    }
    LaunchedEffect(celebrated) {
        if (!celebrated) return@LaunchedEffect
        delay(CELEBRATION_MS)
        celebrated = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        // The entry scrolls; the action below does not. The reader who has scrolled the glosses away
        // still has the one thing the page is for in front of them, clear of the gesture band.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l)
                .padding(top = Spacing.l, bottom = Spacing.l)
        ) {
            Headword(entry, hit)

            Spacer(Modifier.height(Spacing.l))
            Meanings(entry)

            // The kanji the word is written with; each one opens its own WaniKani page, with a tick
            // for the selection and nothing more.
            Spacer(Modifier.height(Spacing.m))
            KanjiStrip(
                characters = remember(entry.primary) { kanjiIn(entry.primary) },
                onKanji = { character ->
                    feedback.haptic(FeedbackHaptic.Tick)
                    onKanji(character)
                },
                type = type
            )

            // The two settings the detail leans on: the deck and note type a word goes to, and the
            // WaniKani token its kanji pages are read with.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                TextButton(onClick = onAnkiSettings) {
                    Text(stringResource(R.string.search_detail_anki_settings))
                }
                TextButton(onClick = onWaniKaniSettings) {
                    Text(stringResource(R.string.wk_settings_open))
                }
            }
        }

        AddToAnki(
            deckName = deckName,
            export = export,
            status = ankiStatus,
            celebrated = celebrated,
            onAdd = onAdd,
            onRequestPermission = { permission.launch(AddContentApi.READ_WRITE_PERMISSION) },
            onShare = {
                if (!onShare()) {
                    Toast.makeText(context, R.string.anki_error_share, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

/**
 * The word itself: its display form, its readings, any other spelling of it the dictionary carries,
 * and how the search reached it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Headword(entry: DictEntry, hit: SearchHit) {
    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = entry.primary,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (entry.common) {
                    LabelChip(stringResource(R.string.search_common))
                }
            }
            if (entry.kana.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
                // One line, however many readings there are: a word with a dozen of them must not
                // push the meanings off the page. The full list is still what a reader with a
                // screen reader is told.
                Text(
                    text = entry.kana.joinToString("・"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // The dictionary's other kanji spellings of this entry — ネコ for 猫, 喰べる for 食べる.
            val otherSpellings = entry.kanji.drop(1)
            if (otherSpellings.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = stringResource(
                        R.string.search_detail_also_written,
                        otherSpellings.joinToString("・")
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (hit.match == SearchMatch.NORMALIZED || hit.match == SearchMatch.LOOSE) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = stringResource(R.string.search_matched_form, hit.form),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * The way into AnkiDroid, pinned below the entry: which deck the note goes to, and one full-width
 * button that says where it stands.
 *
 * With the database permission ungranted the button asks for it — an explicit tap, and the only
 * thing that tap does; the add is a second, deliberate one. AnkiDroid's own add screen is offered
 * beside it, because a reader who does not want to grant anything still wants the word there. With
 * AnkiDroid not installed there is no button at all, only where to configure cards until it is.
 *
 * [celebrated] is the button's one transient state: the moment a note was written, named for
 * [CELEBRATION_MS] and then left to the steady "Added" the export's own settled state already shows.
 * The button is disabled in both, so the naming never becomes a second tap.
 */
@Composable
private fun AddToAnki(
    deckName: String,
    export: ExportState,
    status: AnkiStatus,
    celebrated: Boolean,
    onAdd: () -> Unit,
    onRequestPermission: () -> Unit,
    onShare: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The page's own bottom inset, taken once here, plus the 16 dp that keeps the
                // button out of the gesture band.
                .navigationBarsPadding()
                .padding(horizontal = Spacing.l, vertical = Spacing.l)
        ) {
            Text(
                text = stringResource(R.string.search_detail_anki_deck, deckName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (status != AnkiStatus.NotInstalled) {
                Spacer(Modifier.height(Spacing.s))
                Button(
                    onClick = when (status) {
                        AnkiStatus.Ready -> onAdd
                        AnkiStatus.NeedsPermission -> onRequestPermission
                        AnkiStatus.NotInstalled -> onAdd
                    },
                    // Nothing to tap while a note is being written, and nothing to tap once it is
                    // there: a second tap must not write a second note.
                    enabled = export is ExportState.Idle || export is ExportState.Failed,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when {
                        celebrated -> Text(stringResource(R.string.search_detail_anki_added))

                        export is ExportState.Checking || export is ExportState.Busy ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = LocalContentColor.current,
                                strokeWidth = 2.dp
                            )

                        export is ExportState.Done -> Text(
                            text = stringResource(
                                when (export.outcome) {
                                    ExportOutcome.ADDED -> R.string.anki_added
                                    ExportOutcome.DUPLICATE -> R.string.anki_duplicate
                                }
                            )
                        )

                        else -> {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.anki_add))
                        }
                    }
                }
            }

            if (status == AnkiStatus.NeedsPermission) {
                TextButton(onClick = onShare) {
                    Text(stringResource(R.string.search_detail_send_to_anki))
                }
            }

            if (status == AnkiStatus.NotInstalled) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = stringResource(R.string.anki_settings_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (export is ExportState.Failed) {
                Spacer(Modifier.height(Spacing.s))
                Text(
                    text = export.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** What the dictionary says the word means, and what it calls it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Meanings(entry: DictEntry) {
    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.l)) {
            Caption(stringResource(R.string.search_detail_meaning))
            if (entry.glosses.isEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.search_detail_no_meaning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Numbered, with the number hanging outside the text: a gloss that wraps keeps its
            // place under itself rather than under its number.
            entry.glosses.forEachIndexed { index, gloss ->
                Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(GlossNumberWidth)
                    )
                    Text(
                        text = gloss,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (entry.pos.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.m))
                Caption(stringResource(R.string.search_detail_pos))
                Spacer(Modifier.height(Spacing.xs))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    entry.pos.forEach { pos -> PosChip(pos) }
                }
            }
        }
    }
}

/** One part of speech, outlined: named, but not competing with the word's own colour. */
@Composable
private fun PosChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.s, vertical = 2.dp)
        )
    }
}

/** The small muted label above one block of the detail. */
@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
