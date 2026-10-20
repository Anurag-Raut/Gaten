/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The search screen's state, held outside any component the way SearchViewModel held it outside
 * the composition: the tab is torn down on every switch, so the query, the results and the
 * reading position live here, keyed to nothing — there is one search.
 *
 * The behaviours are SearchViewModel's: the query bounded to QUERY_LIMIT characters (never
 * between the halves of a surrogate pair), the status a query shows before the dictionary is
 * asked, the 250ms trailing debounce with `searchNow` and `retry` skipping it, stale answers
 * dropped when the query has moved on, and the scroll position kept for the next time the screen
 * is composed.
 *
 * One thing is the RN port's own, because the port has to keep the keyboard's keystrokes cheap:
 * a typed query is *coalesced* rather than cleared. Each keystroke updates the text and the
 * generation at once, cancels the timer and the in-flight answer, and leaves the results that are
 * on screen exactly where they are, labelled by [SearchState.hitsQuery] — the text that found them.
 * The dictionary is therefore asked once per settled word, not once per key, and the list never
 * blanks or flashes a spinner through the middle of a word. Only the first query of a session (no
 * results to keep) shows the waiting state while its debounce runs.
 *
 * "Asked" includes the searchability classification: `GatenDict.isSearchable` is a native bridge
 * call (`GatenDictModule.isSearchable`), so it is not run per keystroke either. A settled query
 * costs one classification and, when it classifies as searchable, one lookup — and the notice for a
 * query the dictionary cannot answer arrives with that same classification, a debounce after the
 * reader stopped typing rather than on every key.
 */
import {useSyncExternalStore} from "react";
import {
  isSearchable as dictIsSearchable,
  search as dictSearch,
  type SearchResult,
} from "../../native/GatenDict";

/** How long typing settles before the dictionary is asked. */
const SEARCH_DEBOUNCE_MS = 250;

/** The query length the dictionary answers for — DictionarySearch.QUERY_LIMIT. */
export const QUERY_LIMIT = 32;

/** What the search screen is showing, or waiting for. */
export type SearchStatus = "idle" | "searching" | "results" | "failed" | "unsupported";

export interface SearchState {
  query: string;
  /** True when the query had to be cut to QUERY_LIMIT characters. */
  queryLimited: boolean;
  status: SearchStatus;
  hits: SearchResult[];
  /**
   * The query [hits] answer. The results stay on screen while the reader types the next one, so
   * the rows are always labelled by the text that actually found them — never by the newer text
   * still waiting for its own answer.
   */
  hitsQuery: string;
  error: string | null;
  /** Moves with every query the reader types; the list starts at the top when it moves. */
  generation: number;
}

let state: SearchState = {
  query: "",
  queryLimited: false,
  status: "idle",
  hits: [],
  hitsQuery: "",
  error: null,
  generation: 0,
};

const listeners = new Set<() => void>();

/** Persisted entries Home can open even when they are not part of the current search results. */
const recentHits = new Map<number, SearchResult>();

/**
 * Where the results list was left; the screen restores from it on mount and keeps it current. The
 * offset is the whole of it: the list is restored by offset rather than by the first visible row,
 * so the row the reader was on is not remembered separately (as LazyListState had to).
 */
export const searchScroll = {offset: 0};

function set(patch: Partial<SearchState>) {
  state = {...state, ...patch};
  for (const listener of listeners) {
    listener();
  }
}

/** The query as the search acts on it: NFKC-composed, whitespace removed, bounded to code points. */
export function normalizeQuery(query: string): string {
  const composed = query.normalize("NFKC");
  const out: string[] = [];
  let codePoints = 0;
  for (const character of composed) {
    if (codePoints >= QUERY_LIMIT) {
      break;
    }
    if (/\s/u.test(character)) {
      continue;
    }
    out.push(character);
    codePoints += 1;
  }
  return out.join("");
}

/** [text] cut to the query limit, never between the halves of a surrogate pair. */
export function boundQuery(text: string): string {
  if (text.length <= QUERY_LIMIT) {
    return text;
  }
  const cut = text.slice(0, QUERY_LIMIT);
  const last = cut.charCodeAt(cut.length - 1);
  const highSurrogate = last >= 0xd800 && last <= 0xdbff;
  return highSurrogate ? cut.slice(0, -1) : cut;
}

function failureMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return String(error);
}

export function subscribeSearchState(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getSearchState(): SearchState {
  return state;
}

/** The search state, re-read whenever the store moves. */
export function useSearchState(): SearchState {
  return useSyncExternalStore(subscribeSearchState, getSearchState);
}

/** The row with [entryId], or null while the results carrying it are not here. */
export function hitFor(entryId: number): SearchResult | null {
  return state.hits.find((hit) => hit.id === entryId) ?? recentHits.get(entryId) ?? null;
}

/** Makes locally persisted lookup rows resolvable by the existing word-detail route. */
export function primeRecentLookupHits(entries: readonly SearchResult[]): void {
  recentHits.clear();
  for (const entry of entries) {
    recentHits.set(entry.id, entry);
  }
}

function cancelPending() {
  if (debounceTimer !== null) {
    clearTimeout(debounceTimer);
    debounceTimer = null;
  }
  runId += 1;
}

let runId = 0;
let debounceTimer: number | null = null;

/**
 * Runs [query] against the dictionary, replacing whatever the last search was doing. The result
 * is dropped when the query has moved on, so a search that finished after a newer one started
 * cannot answer for text it was not run against.
 */
async function runSearch(query: string, id: number) {
  const normalized = normalizeQuery(query);
  if (normalized.length === 0) {
    set({status: "idle", hits: [], hitsQuery: "", error: null});
    return;
  }
  try {
    const searchable = await dictIsSearchable(normalized);
    if (id !== runId || state.query !== query) {
      return;
    }
    if (!searchable) {
      set({status: "unsupported", hits: [], hitsQuery: "", error: null});
      return;
    }
    const hits = await dictSearch(query);
    if (id !== runId || state.query !== query) {
      return;
    }
    // These rows answer this query; the screen labels them by it even after the reader has typed on.
    set({status: "results", hits, hitsQuery: query, error: null});
  } catch (error) {
    if (id !== runId || state.query !== query) {
      return;
    }
    set({status: "failed", hits: [], hitsQuery: "", error: failureMessage(error)});
  }
}

/**
 * A new query, as the reader types it: the text is on screen in the same tick, the lookup waits
 * [SEARCH_DEBOUNCE_MS] after the last keystroke, and the previous results stay where they are until
 * the new ones land.
 *
 * That last part is the whole point of the shape here: a keystroke moves `query` and `generation`
 * and *nothing else* about the results, so typing neither blanks the list nor puts a spinner over
 * it — the rows on screen keep answering [hitsQuery] they were found for. Anything that is not
 * typing (an example tapped, the keyboard's search action, `retry`) goes through `searchNow`/
 * `retrySearch`, which cancel the wait and ask at once.
 *
 * The answer to an older query can never land under a newer one: `cancelPending` moves the run id
 * before the state changes, so a lookup already in flight is dropped when it returns.
 */
export function onQueryChange(text: string) {
  const bounded = boundQuery(text);
  const empty = normalizeQuery(bounded).length === 0;
  if (bounded === state.query) {
    // The text is already this text — a repeated call rather than a keystroke. There is nothing to
    // cancel (whatever is pending is already for this text), nothing to schedule, and no reason to
    // move the generation and send the list back to the top.
    return;
  }
  cancelPending();
  // What is already on screen stays, unless the reader emptied the field — then there is nothing
  // for those rows to answer and they go.
  const keep = !empty && state.hits.length > 0;
  set({
    query: bounded,
    queryLimited: text.length > bounded.length,
    status: empty ? "idle" : keep ? "results" : "searching",
    hits: empty ? [] : state.hits,
    hitsQuery: empty ? "" : state.hitsQuery,
    error: null,
    generation: state.generation + 1,
  });
  if (empty) {
    return;
  }
  // Nothing is asked of the dictionary here — not even the searchability check, which is a native
  // bridge call (`GatenDict.isSearchable` → `GatenDictModule.isSearchable`). One query costs one
  // check and one lookup, both when the typing settles (see `runSearch`), so a keystroke costs the
  // reader nothing but a state update.
  debounceTimer = setTimeout(() => {
    debounceTimer = null;
    void runSearch(bounded, runId);
  }, SEARCH_DEBOUNCE_MS);
}

/** Runs the current query now: the keyboard's search action. */
export function searchNow() {
  const query = state.query;
  if (normalizeQuery(query).length === 0) {
    return;
  }
  cancelPending();
  void runSearch(query, runId);
}

/**
 * Asks the dictionary again for the query whose search failed. The rows that were on screen are not
 * an answer to anything any more, so they go and the wait shows for what it is.
 */
export function retrySearch() {
  const query = state.query;
  if (query.trim().length === 0) {
    return;
  }
  cancelPending();
  set({status: "searching", hits: [], hitsQuery: "", error: null});
  void runSearch(query, runId);
}
