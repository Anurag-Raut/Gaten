/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Anki half of the Analytics tab: one read-only snapshot of what AnkiDroid says today, and the
 * deck the reader is looking at.
 *
 * Everything here is a read. The deck figures are AnkiDroid's own deck-list numbers — "to review
 * today", daily limits applied, subdecks included, learning a lower bound — and every other number
 * is one browser-search count answered from the provider's card search with an `_id`-only
 * projection, so no card is ever fetched, rendered or written. Nothing in this file can change the
 * collection or its preferences, and no query is sent without a search term.
 *
 * What the numbers mean, because the labels have to be honest:
 *
 *  - **Cards, not reviews.** `rated:` and `prop:rated` match *cards* that have a review inside the
 *    window, so the same card graded five times today counts once.
 *  - **AnkiDroid's study day, not local midnight.** Those windows are the collection's own day,
 *    computed from its rollover hour ("Next day starts at", 4 AM by default), so "today" here is
 *    the day AnkiDroid is on.
 *  - **Limits apply to the remaining counts.** A deck's new/learning/review figures are what its
 *    deck options still allow today, not how many cards the deck holds.
 *  - **Subdecks are included** in a deck's own figures, and a top-level deck's figure already
 *    contains its children, so the global sum uses the top-level decks alone.
 *
 * The snapshot is cached for [ANKI_STATS_TTL_MS] in this module and re-read only when it is stale,
 * when the reader pulls to refresh, or when they pick another deck — a tab that is entered and left
 * repeatedly costs nothing. A refresh that fails keeps the last snapshot and reports the failure
 * beside it, so the screen never blanks out what it already had.
 */
import {useCallback, useEffect, useRef, useState} from "react";
import {AppState} from "react-native";
import * as Anki from "../../native/GatenAnki";
import type {AnkiDeckResult} from "../../native/GatenAnki";

/** How long a snapshot stays fresh. The reader's own numbers move slowly within a minute. */
export const ANKI_STATS_TTL_MS = 60_000;

/** How many days the bar chart looks back, today included. */
export const ANKI_STATS_DAYS = 7;

/** Why the numbers are missing, in the same names the review screen uses. */
export type AnkiStatsFailure =
  | "NOT_INSTALLED"
  | "PERMISSION"
  | "NOT_SET_UP"
  | "NOT_FOUND"
  | "MALFORMED"
  | "PROVIDER";

/** One deck as the dashboard shows it. */
export interface AnkiDeckRow {
  id: number;
  name: string;
  /** A filtered deck is a temporary view of cards that live in a home deck. */
  filtered: boolean;
  /** Top-level: no `::` in the name. Only these are summed for the global remaining figure. */
  topLevel: boolean;
  newCount: number;
  learnCount: number;
  reviewCount: number;
  /** new + learning + review, exactly as AnkiDroid's deck list reports them for today. */
  remaining: number;
}

/** One bar: distinct cards answered on one of AnkiDroid's days. */
export interface AnkiDailyPoint {
  /** 0 is AnkiDroid's current study day; 1..[ANKI_STATS_DAYS] - 1 are the days before it. */
  daysAgo: number;
  /** Null when AnkiDroid would not answer that day's search. */
  cards: number | null;
}

/** Everything the Anki half of the dashboard draws from, in one immutable read. */
export interface AnkiStatsSnapshot {
  /** The deck the reader is looking at, or null when AnkiDroid has no decks. */
  selectedDeck: {id: number; name: string} | null;
  /** Every deck AnkiDroid reported, in AnkiDroid's own order. */
  decks: AnkiDeckRow[];
  /** Remaining today over the top-level decks, or null when the deck list could not be read. */
  globalRemaining: number | null;
  /** Cards in the selected deck, subdecks included; null when that search was unavailable. */
  totalCards: number | null;
  /** Cards in the selected deck whose interval is 21 days or more — Anki's own "mature". */
  matureCards: number | null;
  /** Distinct cards answered on AnkiDroid's current study day. */
  studiedToday: number | null;
  daily: AnkiDailyPoint[];
  /** When this snapshot was read, epoch milliseconds. */
  updatedAt: number;
}

/** What the screen renders from, and the two things it can ask for. */
export interface AnkiStatsState {
  /** "loading" only until the first snapshot lands; a later refresh keeps the last one. */
  status: "loading" | "ready" | "unavailable";
  /** Why the data is missing or stale. Coexists with `data` when only a refresh failed. */
  failure: AnkiStatsFailure | null;
  /** True while a read is in flight. */
  refreshing: boolean;
  data: AnkiStatsSnapshot | null;
  refresh: () => void;
  selectDeck: (deckId: number) => void;
}

/**
 * A deck name as an Anki search term. Spaces need the quotes; the characters Anki reads as search
 * syntax need a backslash in front of them, so a deck named `A "B" *C*` is searched for by name and
 * not as a pattern. `::` is left alone: it is the subdeck separator, which is the scope we want.
 */
export function deckScope(name: string): string {
  const escaped = name.replace(/([\\"*_()])/g, "\\$1");
  return `deck:"${escaped}"`;
}

/** The searches one snapshot needs, in the order the bridge answers them. */
export function ankiStatsQueries(deckName: string | null): string[] {
  const queries: string[] = [];
  if (deckName != null) {
    const scope = deckScope(deckName);
    queries.push(scope);
    queries.push(`${scope} prop:ivl>=21`);
  }
  queries.push("rated:1");
  for (let daysAgo = 0; daysAgo < ANKI_STATS_DAYS; daysAgo += 1) {
    // `prop:rated=0` is AnkiDroid's current study day and `-1`, `-2` … the days before it. The
    // manual's own construct: `rated:n -rated:(n-1)` is documented as unreliable.
    queries.push(`prop:rated=${daysAgo === 0 ? 0 : -daysAgo}`);
  }
  return queries;
}

/** The failure a bridge rejection carried, in this hook's own names. */
function failureOf(error: unknown): AnkiStatsFailure {
  if (typeof error !== "object" || error == null) {
    return "PROVIDER";
  }
  const code = "code" in error && typeof error.code === "string" ? error.code : "";
  const named = /^ANKI_([A-Z_]+)$/.exec(code);
  if (named) {
    return named[1] as AnkiStatsFailure;
  }
  return code === "E_ANKI_STATS" ? "MALFORMED" : "PROVIDER";
}

function toRow(deck: AnkiDeckResult): AnkiDeckRow {
  return {
    id: deck.id,
    name: deck.name,
    filtered: deck.filtered,
    topLevel: !deck.name.includes("::"),
    newCount: deck.newCount,
    learnCount: deck.learnCount,
    reviewCount: deck.reviewCount,
    remaining: deck.newCount + deck.learnCount + deck.reviewCount,
  };
}

/** One completed read: the snapshot and the deck it belongs to. */
export interface AnkiStatsRead {
  snapshot: AnkiStatsSnapshot;
  selectedId: number | null;
}

/** One read of everything, from the bridge alone. */
async function readSnapshot(keepId: number | null): Promise<AnkiStatsRead> {
  const status = await Anki.status();
  if (status !== "ready") {
    throw {code: status === "notInstalled" ? "ANKI_NOT_INSTALLED" : "ANKI_PERMISSION"};
  }
  const [decks, configuredName] = await Promise.all([
    Anki.decks(),
    // The export deck is a nicety: a collection Gaten never exported to still has an Analytics tab.
    Anki.getConfiguredDeckName().catch(() => ""),
  ]);
  const rows = decks.map(toRow);
  // The deck the reader is looking at: the one they picked if it is still there, then the deck
  // Gaten exports to, then AnkiDroid's own first deck. The export deck is only a default.
  const selected =
    rows.find((row) => row.id === keepId) ??
    rows.find((row) => row.name === configuredName) ??
    rows[0] ??
    null;
  const queries = ankiStatsQueries(selected?.name ?? null);
  const counts = await Anki.counts(queries);

  // The searches are positional: with a deck they are [total, mature, today, day 0 … day 6], and
  // without one the two deck-scoped entries are simply absent.
  const offset = selected == null ? 0 : 2;
  const at = (index: number): number | null => counts[index] ?? null;

  return {
    snapshot: {
      selectedDeck:
        selected == null ? null : {id: selected.id, name: selected.name},
      decks: rows,
      globalRemaining: rows
        .filter((row) => row.topLevel)
        .reduce((total, row) => total + row.remaining, 0),
      totalCards: selected == null ? null : at(0),
      matureCards: selected == null ? null : at(1),
      studiedToday: at(offset),
      daily: Array.from({length: ANKI_STATS_DAYS}, (_, daysAgo) => ({
        daysAgo,
        cards: at(offset + 1 + daysAgo),
      })),
      updatedAt: Date.now(),
    },
    selectedId: selected?.id ?? null,
  };
}

/** The last good snapshot, shared by every mount of the tab, and the read in flight for it. */
let cache: {
  snapshot: AnkiStatsSnapshot;
  selectedId: number | null;
  at: number;
} | null = null;
let inFlight: {selectedId: number | null; promise: Promise<AnkiStatsRead>} | null = null;

/**
 * The Anki snapshot, refreshed while [active] — the tab being on screen — and cached for
 * [ANKI_STATS_TTL_MS] beyond it. `refresh` forces a read; `selectDeck` moves the reader's choice
 * and re-reads the deck-scoped numbers for it.
 */
export function useAnkiStats(active: boolean): AnkiStatsState {
  const [data, setData] = useState<AnkiStatsSnapshot | null>(cache?.snapshot ?? null);
  const [failure, setFailure] = useState<AnkiStatsFailure | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  // Whether a read has ever settled. Until one has, "no numbers yet" is a read still on its way, not
  // an AnkiDroid that has nothing: the first render happens before the effect that starts the read.
  const [attempted, setAttempted] = useState(false);
  const [selectedId, setSelectedId] = useState<number | null>(cache?.selectedId ?? null);
  // Only the newest read may write state: a deck change or a tab re-entry supersedes an older one.
  const generation = useRef(0);
  const live = useRef(true);
  useEffect(
    () => () => {
      live.current = false;
    },
    []
  );

  const load = useCallback((force: boolean, keepId: number | null) => {
    if (
      !force &&
      cache != null &&
      cache.selectedId === keepId &&
      Date.now() - cache.at < ANKI_STATS_TTL_MS
    ) {
      setData(cache.snapshot);
      setFailure(null);
      return;
    }
    const mine = generation.current + 1;
    generation.current = mine;
    setRefreshing(true);
    // One read at a time per deck: a second caller waits on the same promise instead of asking
    // AnkiDroid the same ten questions again.
    const started =
      inFlight != null && inFlight.selectedId === keepId
        ? inFlight.promise
        : (inFlight = {selectedId: keepId, promise: readSnapshot(keepId)}).promise;
    started
      .then(({snapshot, selectedId: chosen}) => {
        if (!live.current || mine !== generation.current) return;
        cache = {snapshot, selectedId: chosen, at: Date.now()};
        setData(snapshot);
        setSelectedId(chosen);
        setFailure(null);
      })
      .catch((error) => {
        if (!live.current || mine !== generation.current) return;
        const why = failureOf(error);
        // Two failures mean the numbers are not merely stale but no longer ours to show — AnkiDroid
        // is gone, or the database permission was taken back — so the last snapshot goes with them.
        // Everything else (a provider that would not answer, a deck that moved, a malformed body)
        // keeps the last snapshot on screen with the failure reported beside it.
        if (why === "NOT_INSTALLED" || why === "PERMISSION") {
          cache = null;
          setData(null);
          setSelectedId(null);
        }
        setFailure(why);
      })
      .finally(() => {
        if (inFlight != null && inFlight.promise === started) inFlight = null;
        if (!live.current) return;
        setAttempted(true);
        if (mine === generation.current) setRefreshing(false);
      });
  }, []);

  useEffect(() => {
    if (active) load(false, selectedId);
  }, [active, load, selectedId]);

  // A resume while the tab is on screen cannot be seen by the effect above — `active` is already
  // true — so it is listened for here. The read it asks for is TTL-aware: it answers from the cache
  // it already has, and only asks AnkiDroid again once that snapshot has aged out.
  useEffect(() => {
    if (!active) return;
    const subscription = AppState.addEventListener("change", (status) => {
      if (status === "active") load(false, selectedId);
    });
    return () => subscription.remove();
  }, [active, load, selectedId]);

  const refresh = useCallback(() => load(true, selectedId), [load, selectedId]);

  const selectDeck = useCallback(
    (deckId: number) => {
      if (deckId === selectedId) return;
      setSelectedId(deckId);
      load(true, deckId);
    },
    [load, selectedId]
  );

  return {
    // "loading" until the first read settles: a screen that has not been answered yet is not a screen
    // with nothing on it, and an older refresh keeps the snapshot it already had.
    status: data != null ? "ready" : refreshing || !attempted ? "loading" : "unavailable",
    failure,
    refreshing,
    data,
    refresh,
    selectDeck,
  };
}
