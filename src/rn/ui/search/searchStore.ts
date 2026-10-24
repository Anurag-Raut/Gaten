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
 *
 * The module keeps one *default* store (Home's) and exports its functions by name, so every screen
 * that already reads it is unchanged. [createSearchStore] builds an independent store over the same
 * behaviour: the lookup sheet mounts its own ephemeral one, so opening the overlay's search never
 * writes Home's `searchActive`, query or results — history and the shared UI stay shared, the
 * transient state does not.
 */
import {createContext, useContext, useSyncExternalStore} from "react";
import {
  isSearchable as dictIsSearchable,
  recentSearches as dictRecentSearches,
  rememberSearch as dictRememberSearch,
  search as dictSearch,
  type SearchResult,
} from "../../native/GatenDict";

/** How long typing settles before the dictionary is asked. */
const SEARCH_DEBOUNCE_MS = 250;

/** The query length the dictionary answers for — DictionarySearch.QUERY_LIMIT. */
export const QUERY_LIMIT = 32;

/** How many searched words the empty prompt offers — GatenDictModule.MAX_RECENT_SEARCHES. */
export const RECENT_SEARCH_LIMIT = 6;

/** What the search screen is showing, or waiting for. */
export type SearchStatus = "idle" | "searching" | "results" | "failed" | "unsupported";

export interface SearchState {
  /** Whether Home's full-screen search surface is open. */
  searchActive: boolean;
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
  /**
   * The queries the reader has searched with, newest first. Empty until they have searched at all,
   * which is what the empty prompt shows examples for instead.
   */
  recentSearches: string[];
}

/** One search surface's state: the store the default export drives, or a scoped instance. */
export interface SearchStore {
  subscribe(listener: () => void): () => void;
  getState(): SearchState;
  /** Opens or closes this store's own full-screen search surface. */
  setSearchActive(searchActive: boolean): void;
  hitFor(entryId: number): SearchResult | null;
  primeRecentLookupHits(entries: readonly SearchResult[]): void;
  loadRecentSearches(): void;
  rememberSearchedQuery(query: string): void;
  onQueryChange(text: string): void;
  searchNow(): void;
  retrySearch(): void;
  /**
   * Where the results list was left; the screen restores from it on mount and keeps it current. The
   * offset is the whole of it: the list is restored by offset rather than by the first visible row,
   * so the row the reader was on is not remembered separately (as LazyListState had to).
   */
  readonly searchScroll: {offset: number};
}

function initialState(): SearchState {
  return {
    searchActive: false,
    query: "",
    queryLimited: false,
    status: "idle",
    hits: [],
    hitsQuery: "",
    error: null,
    generation: 0,
    recentSearches: [],
  };
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

/** [query] at the front of [list], without the copy that was already in it, cut to the limit. */
function withRecentSearch(list: readonly string[], query: string): string[] {
  const key = normalizeQuery(query);
  return [query, ...list.filter((prior) => normalizeQuery(prior) !== key)].slice(0, RECENT_SEARCH_LIMIT);
}

/**
 * Builds an independent search store. The lookup sheet uses one of these so its search is
 * ephemeral: it shares the dictionary, the persisted history and the UI, but its `searchActive`,
 * query and results can never be the Home tab's.
 */
export function createSearchStore(): SearchStore {
  let state: SearchState = initialState();

  const listeners = new Set<() => void>();

  /** Persisted entries this store can open even when they are not part of its current results. */
  const recentHits = new Map<number, SearchResult>();

  const searchScroll = {offset: 0};

  let runId = 0;
  let debounceTimer: number | null = null;

  function set(patch: Partial<SearchState>) {
    state = {...state, ...patch};
    for (const listener of listeners) {
      listener();
    }
  }

  function cancelPending() {
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer);
      debounceTimer = null;
    }
    runId += 1;
  }

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
   * Reads the searched words kept on this device. Whatever is already here — the words searched with
   * since the read was asked for — stays in front of the stored list, so a save that raced the read
   * is not lost behind it or duplicated by it.
   */
  function loadRecentSearches(): void {
    // The bridge call sits inside the chain: a JS bundle running over a build that predates the
    // native method then rejects like any other failure, instead of throwing out of Home's mount.
    void Promise.resolve()
      .then(() => dictRecentSearches())
      .then((stored) => {
        const seen = new Set(state.recentSearches.map(normalizeQuery));
        const merged = [...state.recentSearches];
        for (const query of stored) {
          const key = normalizeQuery(query);
          if (key.length > 0 && !seen.has(key)) {
            seen.add(key);
            merged.push(query);
          }
        }
        set({recentSearches: merged.slice(0, RECENT_SEARCH_LIMIT)});
      })
      .catch((error: unknown) => {
        console.warn("Could not read the recent searches", error);
      });
  }

  /**
   * Remembers [query] as a word the reader searched with: the keyboard's search action, a chip on the
   * empty prompt, or a result they opened. Typing on its own does not count — the debounce asks about
   * text the reader is still writing, and those fragments would fill the prompt.
   */
  function rememberSearchedQuery(query: string): void {
    const trimmed = query.trim();
    if (normalizeQuery(trimmed).length === 0 || state.recentSearches[0] === trimmed) {
      return;
    }
    set({recentSearches: withRecentSearch(state.recentSearches, trimmed)});
    void Promise.resolve()
      .then(() => dictRememberSearch(trimmed))
      .catch((error: unknown) => {
        console.warn("Could not save the recent search", error);
      });
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
  function onQueryChange(text: string) {
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
  function searchNow() {
    const query = state.query;
    if (normalizeQuery(query).length === 0) {
      return;
    }
    rememberSearchedQuery(query);
    cancelPending();
    void runSearch(query, runId);
  }

  /**
   * Asks the dictionary again for the query whose search failed. The rows that were on screen are not
   * an answer to anything any more, so they go and the wait shows for what it is.
   */
  function retrySearch() {
    const query = state.query;
    if (query.trim().length === 0) {
      return;
    }
    cancelPending();
    set({status: "searching", hits: [], hitsQuery: "", error: null});
    void runSearch(query, runId);
  }

  /** Opens or closes this store's own full-screen search surface. */
  function setSearchActive(searchActive: boolean): void {
    if (state.searchActive !== searchActive) {
      set({searchActive});
    }
  }

  /** The row with [entryId], or null while the results carrying it are not here. */
  function hitFor(entryId: number): SearchResult | null {
    return state.hits.find((hit) => hit.id === entryId) ?? recentHits.get(entryId) ?? null;
  }

  /** Makes locally persisted lookup rows resolvable by the existing word-detail route. */
  function primeRecentLookupHits(entries: readonly SearchResult[]): void {
    recentHits.clear();
    for (const entry of entries) {
      recentHits.set(entry.id, entry);
    }
  }

  return {
    subscribe(listener: () => void): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    getState: () => state,
    setSearchActive,
    hitFor,
    primeRecentLookupHits,
    loadRecentSearches,
    rememberSearchedQuery,
    onQueryChange,
    searchNow,
    retrySearch,
    searchScroll,
  };
}

/**
 * Home's store: the one the tab and the word-detail route already read by name. Every named export
 * below is this instance, so nothing that imported the module has to change.
 */
const defaultSearch = createSearchStore();

export function subscribeSearchState(listener: () => void): () => void {
  return defaultSearch.subscribe(listener);
}

export function getSearchState(): SearchState {
  return defaultSearch.getState();
}

/** Opens or closes Home's search surface for the app shell and Home screen. */
export function setSearchActive(searchActive: boolean): void {
  defaultSearch.setSearchActive(searchActive);
}

/** The search state, re-read whenever the store moves. */
export function useSearchState(): SearchState {
  return useSyncExternalStore(defaultSearch.subscribe, defaultSearch.getState);
}

/** The row with [entryId], or null while the results carrying it are not here. */
export function hitFor(entryId: number): SearchResult | null {
  return defaultSearch.hitFor(entryId);
}

/** Makes locally persisted lookup rows resolvable by the existing word-detail route. */
export function primeRecentLookupHits(entries: readonly SearchResult[]): void {
  defaultSearch.primeRecentLookupHits(entries);
}

/** Reads the searched words kept on this device. */
export function loadRecentSearches(): void {
  defaultSearch.loadRecentSearches();
}

/** Remembers [query] as a word the reader searched with. */
export function rememberSearchedQuery(query: string): void {
  defaultSearch.rememberSearchedQuery(query);
}

/** A new query, as the reader types it. */
export function onQueryChange(text: string): void {
  defaultSearch.onQueryChange(text);
}

/** Runs the current query now: the keyboard's search action. */
export function searchNow(): void {
  defaultSearch.searchNow();
}

/** Asks the dictionary again for the query whose search failed. */
export function retrySearch(): void {
  defaultSearch.retrySearch();
}

/** Where Home's results list was left. */
export const searchScroll = defaultSearch.searchScroll;

/**
 * The store a search surface should read. Home provides nothing, so it gets the default; the lookup
 * sheet provides its scoped instance so its shared components follow the overlay, not Home.
 */
export const SearchStoreContext = createContext<SearchStore>(defaultSearch);

/** The store in scope: Home's by default, the overlay's inside the lookup sheet. */
export function useSearchStore(): SearchStore {
  return useContext(SearchStoreContext);
}
