/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.gaten.app.anki.AddResult
import com.gaten.app.anki.AnkiCard
import com.gaten.app.anki.AnkiExporter
import com.gaten.app.anki.AnkiPreferences
import com.gaten.app.anki.AnkiSettings
import com.gaten.app.anki.AnkiStatus
import com.gaten.app.dict.DictEntry
import com.gaten.app.dict.DictionarySearch
import com.gaten.app.dict.JmdictRepository
import com.gaten.app.dict.SearchHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "GatenSearch"

/** How long typing settles before the dictionary is asked, so a word costs one query, not one per key. */
private const val SEARCH_DEBOUNCE_MS = 250L

/** What the search screen is showing, or waiting for. */
enum class SearchStatus { Idle, Searching, Results, Failed, Unsupported }

/** How an export to AnkiDroid ended, for the button's final label. */
enum class ExportOutcome { ADDED, DUPLICATE }

/** Where the "Add to Anki" button of one entry is. */
sealed interface ExportState {
    /** Nothing has happened to this entry yet, or the last attempt failed and it can be tried again. */
    data object Idle : ExportState

    /** Whether the entry is already in the configured deck is being read. */
    data object Checking : ExportState

    /** A note is being written. */
    data object Busy : ExportState

    /** The note was added or found in Anki; checked again when the detail is resumed. */
    data class Done(val outcome: ExportOutcome) : ExportState

    /** The attempt failed; [message] is what AnkiDroid's failure means for the reader. */
    data class Failed(val message: String) : ExportState
}

/**
 * Where one entry's export is, and the configuration the answer belongs to.
 *
 * The deck, note type and templates are the reader's to change at any time, and a note written under
 * one configuration says nothing about another — "already in Anki" is about the note type the add
 * would have used. The settings and the AnkiDroid status are therefore kept beside the state, and an
 * answer whose configuration no longer holds reads as [ExportState.Idle] again.
 */
data class ExportProgress(
    val state: ExportState,
    val settings: AnkiSettings,
    val status: AnkiStatus
)

/**
 * Everything the search screen draws. One object, so the screen reads one value and every part of it
 * moves together.
 *
 * [hits] are [DictionarySearch]'s own rows, most likely first, and [hitFor] is how a detail resolves
 * the entry id its route carries: the id and not the entry, so a saved page can be restored from a
 * fresh search of the same query — and, while that search is still running, resolves to null rather
 * than dropping the page. [exports] is where each entry's Add-to-Anki button is, keyed by entry id
 * because an add under way must survive the reader navigating away from it. [generation] moves with
 * every query the reader types, which is what tells the screen to start its list at the top again
 * without moving it when a screen is merely restored.
 */
data class SearchState(
    val query: String = "",
    /** True when [query] had to be cut to [DictionarySearch.QUERY_LIMIT] characters. */
    val queryLimited: Boolean = false,
    val status: SearchStatus = SearchStatus.Idle,
    val hits: List<SearchHit> = emptyList(),
    val error: String? = null,
    val generation: Int = 0,
    val ankiStatus: AnkiStatus = AnkiStatus.NotInstalled,
    val ankiSettings: AnkiSettings = AnkiSettings(),
    val exports: Map<Int, ExportProgress> = emptyMap()
) {
    /**
     * The row with [entryId], or null while the results carrying it are not here: a restored page
     * resolves as soon as the search it was left on answers, and never before.
     */
    fun hitFor(entryId: Int): SearchHit? = hits.firstOrNull { it.entry.id == entryId }

    /** Where the Add-to-Anki button of [entryId] is, under the configuration in force right now. */
    fun export(entryId: Int): ExportState {
        val progress = exports[entryId] ?: return ExportState.Idle
        if (progress.state is ExportState.Busy) return ExportState.Busy
        return if (progress.settings == ankiSettings && progress.status == ankiStatus) {
            progress.state
        } else {
            ExportState.Idle
        }
    }
}

/**
 * The search screen's state, held outside the composition.
 *
 * The screen is one tab of a few, and it is thrown away whenever the reader looks at another one, so
 * nothing it shows may live in the composition: the query, the results, the reading position and
 * every export are here, and the query and the reading position are in [savedState] as well, so they
 * outlive the process too. Which page is open is the shell's navigation state, not this one's.
 *
 * The dictionary is opened once and kept warm for the life of the view model, searches run on
 * [Dispatchers.IO] and each new one cancels the last, and an export runs in [viewModelScope] so a
 * note that is being written is still written when the reader has left the screen.
 */
class SearchViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {

    private val app = application

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Where the result list was left; the screen reads it once and writes it back on the way out. */
    val scrollIndex: Int get() = savedState[KEY_SCROLL_INDEX] ?: 0
    val scrollOffset: Int get() = savedState[KEY_SCROLL_OFFSET] ?: 0

    private var repository: JmdictRepository? = null
    private val repositoryLock = Mutex()
    private var searchJob: Job? = null

    init {
        // Restored from a saved screen: the query comes back and the search runs again, which is also
        // what brings the open entry's row back for the detail to be resolved from.
        val query = savedState.get<String>(KEY_QUERY).orEmpty()
        _state.value = SearchState(query = query, status = statusOf(query))
        if (_state.value.status == SearchStatus.Searching) search(query, debounce = false)
    }

    /**
     * A new query. The text is bounded to [DictionarySearch.QUERY_LIMIT] characters — the screen says
     * so when it had to cut, rather than searching a word the reader did not type — the results are
     * dropped rather than left tappable under a query they do not belong to, and the search waits
     * [SEARCH_DEBOUNCE_MS] so typing a word costs one query rather than one per keystroke.
     */
    fun onQueryChange(text: String) {
        val query = bounded(text)
        val status = statusOf(query)
        searchJob?.cancel()
        savedState[KEY_QUERY] = query
        savedState[KEY_SCROLL_INDEX] = 0
        savedState[KEY_SCROLL_OFFSET] = 0
        _state.update {
            it.copy(
                query = query,
                queryLimited = text.length > query.length,
                status = status,
                hits = emptyList(),
                error = null,
                generation = it.generation + 1
            )
        }
        if (status == SearchStatus.Searching) search(query, debounce = true)
    }

    /** Runs the current query now: the keyboard's search action, and an example query tapped. */
    fun searchNow() {
        val query = _state.value.query
        if (statusOf(query) != SearchStatus.Searching) return
        searchJob?.cancel()
        search(query, debounce = false)
    }

    /** Asks the dictionary again for the query whose search failed. */
    fun retry() {
        val query = _state.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        _state.update { it.copy(status = SearchStatus.Searching, error = null) }
        search(query, debounce = false)
    }

    /** Keeps the reading position for the next time this screen is composed. */
    fun saveScroll(index: Int, offset: Int) {
        savedState[KEY_SCROLL_INDEX] = index
        savedState[KEY_SCROLL_OFFSET] = offset
    }

    /**
     * Reads whether AnkiDroid can be written to, and — when it can — whether [entry] is already in
     * the configured deck, so the button opens saying so instead of offering an add that would be
     * refused as a duplicate.
     *
     * Called when the detail resumes. Settings, access and duplicate status are read again because
     * the reader may have changed the note type or deleted the note in AnkiDroid while away.
     * An export in flight is left alone.
     */
    fun prepareExport(entry: DictEntry) {
        val status = AnkiExporter.status(app)
        val settings = AnkiPreferences.load(app)
        _state.update { it.copy(ankiStatus = status, ankiSettings = settings) }
        if (status != AnkiStatus.Ready) return
        // Read through the configuration in force: a check made under a deck or note type the reader
        // has since changed is not an answer about this one, so it is asked again.
        when (_state.value.export(entry.id)) {
            ExportState.Idle, is ExportState.Failed, is ExportState.Done -> Unit
            else -> return
        }
        // An attempt that is under way is left alone, whatever configuration it belongs to.
        if (rawExport(entry.id) is ExportState.Busy) return

        val attempt = ExportProgress(ExportState.Checking, settings, status)
        setExport(entry.id, attempt)
        viewModelScope.launch {
            val card = AnkiCard.from(entry)
            val duplicate = AnkiExporter.isDuplicate(app, card, settings)
            // Only if this attempt is still the one on record: a newer attempt, or a configuration
            // changed since, owns the entry now and this answer must not overwrite it.
            if (_state.value.exports[entry.id] != attempt) return@launch
            setExport(
                entry.id,
                ExportProgress(
                    if (duplicate) ExportState.Done(ExportOutcome.DUPLICATE) else ExportState.Idle,
                    settings,
                    status
                )
            )
        }
    }

    /**
     * Adds [entry] to the deck the Anki settings name.
     *
     * The whole attempt runs in [viewModelScope] against the application context, so it finishes —
     * and its result is kept, keyed by the entry — however the reader moves on: the detail closing,
     * another search, another tab. The guard is here, not only on the button: a tap that lands while
     * a note is being written, or after one was, must not write a second note.
     *
     * The attempt is made under the settings read now, and its result is stored with them. If the
     * reader changes the deck or the note type while it runs, the stored answer no longer describes
     * the configuration in force and the button reads as untouched, ready for the new one.
     */
    fun addToAnki(entry: DictEntry) {
        // A note being written is never started again, whatever configuration it was started under.
        if (rawExport(entry.id) is ExportState.Busy) return

        val settings = AnkiPreferences.load(app)
        val status = AnkiExporter.status(app)
        _state.update { it.copy(ankiStatus = status, ankiSettings = settings) }
        // A note already in the deck is not written twice — under the configuration in force, which
        // is what makes a deck or a note type changed in the meantime add again, as it should.
        if (_state.value.export(entry.id) is ExportState.Done) return

        val card = AnkiCard.from(entry)
        val attempt = ExportProgress(ExportState.Busy, settings, status)
        setExport(entry.id, attempt)

        viewModelScope.launch {
            val next = when (val result = AnkiExporter.add(app, card, settings)) {
                AddResult.Added -> ExportState.Done(ExportOutcome.ADDED)
                AddResult.Duplicate -> ExportState.Done(ExportOutcome.DUPLICATE)
                is AddResult.Failed -> ExportState.Failed(result.message)
            }
            // Re-read, so that a configuration changed mid-flight is what the button is judged
            // against from here on.
            _state.update {
                it.copy(
                    ankiStatus = AnkiExporter.status(app),
                    ankiSettings = AnkiPreferences.load(app)
                )
            }
            if (_state.value.exports[entry.id] != attempt) return@launch
            setExport(entry.id, ExportProgress(next, settings, status))
        }
    }

    /**
     * Hands [entry] to AnkiDroid's own add screen, the way on when the database permission is not
     * granted: the reader picks the deck and the note type there. False when nothing can take it.
     */
    fun shareToAnkiDroid(entry: DictEntry): Boolean =
        AnkiExporter.shareFallback(app, AnkiCard.from(entry))

    /**
     * The reader has answered the permission request. Nothing is exported here: granting only makes
     * the button able to add, and the reader taps it when they mean to.
     */
    fun refreshAnkiStatus() {
        _state.update {
            it.copy(ankiStatus = AnkiExporter.status(app), ankiSettings = AnkiPreferences.load(app))
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        repository?.close()
        repository = null
    }

    /**
     * Runs [query] on the dictionary, replacing whatever the last search was doing.
     *
     * The result is dropped when the query has moved on, so a search that finished — or failed —
     * after a newer one started cannot answer for text it was not run against.
     */
    private fun search(query: String, debounce: Boolean) {
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MS)
            val hits = try {
                withContext(Dispatchers.IO) {
                    val repo = openRepository()
                    DictionarySearch.search(
                        query,
                        repo::lookup,
                        repo::lookupPrefix,
                        repo::lookupLoose
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Searching the dictionary failed", e)
                if (_state.value.query == query) {
                    _state.update {
                        it.copy(
                            status = SearchStatus.Failed,
                            error = e.message ?: e.javaClass.simpleName
                        )
                    }
                }
                return@launch
            }
            if (_state.value.query != query) return@launch
            _state.update { it.copy(status = SearchStatus.Results, hits = hits, error = null) }
        }
    }

    /**
     * The dictionary, opened once and kept warm for the life of this view model. A failed open is
     * not remembered: [retry] and the next query both try again.
     */
    private suspend fun openRepository(): JmdictRepository {
        repository?.let { return it }
        return repositoryLock.withLock {
            repository ?: JmdictRepository.open(app).also { repository = it }
        }
    }

    /** [text] cut to the query limit, never between the halves of a surrogate pair. */
    private fun bounded(text: String): String {
        if (text.length <= DictionarySearch.QUERY_LIMIT) return text
        val cut = text.take(DictionarySearch.QUERY_LIMIT)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /**
     * Where an entry's export really is, whatever configuration it was made under. The guards read
     * this: work in flight, and work already done, are not started again just because a settings
     * change has hidden their answer from the screen.
     */
    private fun rawExport(entryId: Int): ExportState =
        _state.value.exports[entryId]?.state ?: ExportState.Idle

    private fun setExport(entryId: Int, progress: ExportProgress) {
        _state.update { it.copy(exports = it.exports + (entryId to progress)) }
    }

    private companion object {
        const val KEY_QUERY = "search.query"
        const val KEY_SCROLL_INDEX = "search.scroll.index"
        const val KEY_SCROLL_OFFSET = "search.scroll.offset"

        /**
         * What the screen shows for a query before the dictionary is asked: nothing at all when there
         * is nothing to ask, and the "Japanese only" note when there is nothing of Japanese in it —
         * romaji included, since that is transliterated and looked up like the kana it stands for.
         */
        fun statusOf(query: String): SearchStatus {
            val normalized = DictionarySearch.normalize(query)
            return when {
                normalized.isEmpty() -> SearchStatus.Idle
                !DictionarySearch.isSearchable(normalized) -> SearchStatus.Unsupported
                else -> SearchStatus.Searching
            }
        }
    }
}
