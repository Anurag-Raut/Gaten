/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Anki half of the Analytics page: one read-only, collection-wide snapshot of what AnkiDroid says
 * today — the collection's four card states, and twelve weeks of distinct-card activity.
 *
 * The page no longer shows remaining cards, deck counts or a seven-day chart: Home owns the due
 * counts, and this file supplies the two things only Analytics draws. Every figure is
 * collection-wide, because the deck selector is gone — nothing here is scoped to one deck, and the
 * deck list is not read at all any more.
 *
 * Everything here is a read. Each number is one browser-search count answered from the provider's
 * card search with an `_id`-only projection, so no card is ever fetched, rendered or written. Nothing
 * in this file can change the collection or its preferences, and no query is sent without a search
 * term.
 *
 * What the numbers mean, because the labels have to be honest:
 *
 *  - **Cards, not reviews.** `prop:rated` matches *cards* that have a review inside the window, so
 *    the same card graded five times today counts once.
 *  - **AnkiDroid's study day, not local midnight.** Those windows are the collection's own day,
 *    computed from its rollover hour ("Next day starts at", 4 AM by default), so "today" here is the
 *    day AnkiDroid is on.
 *  - **The state counts are the whole collection.** New / learning / young review / mature review are
 *    Anki's own Card Counts categories, mutually exclusive, so their sum is the whole collection.
 *  - **A day the provider would not answer stays missing.** A refused search reads as null and is
 *    never turned into a zero, because "no cards that day" and "no answer that day" are different
 *    facts and the heatmap draws them differently.
 *
 * One read is 88 searches — four card states and [ANKI_ACTIVITY_DAYS] days — and the bridge refuses
 * more than [ANKI_QUERIES_PER_CALL] in a single call, so the days go out in chunks of that size. That
 * cost is also why the snapshot is cached for [ANKI_STATS_TTL_MS]: a page entered and left
 * repeatedly costs nothing, and five minutes of staleness is cheap next to 88 searches a minute. A
 * refresh that fails keeps the last snapshot and reports the failure beside it, so the page never
 * blanks out what it already had.
 */
import {useCallback, useEffect, useRef, useState} from "react";
import {AppState} from "react-native";
import * as Anki from "../../native/GatenAnki";

/**
 * How long a snapshot stays fresh. Five minutes: a read is 88 searches, which is not a cost to pay
 * again every minute, and the collection's own numbers move slowly inside five of them.
 */
export const ANKI_STATS_TTL_MS = 5 * 60 * 1000;

/** How many days of activity the heatmap reads, today included: twelve weeks. */
export const ANKI_ACTIVITY_DAYS = 84;

/** How many searches the bridge accepts in one call. The read has to be chunked on this number. */
export const ANKI_QUERIES_PER_CALL = 12;

/** Why the numbers are missing, in the same names the review screen uses. */
export type AnkiStatsFailure =
  | "NOT_INSTALLED"
  | "PERMISSION"
  | "NOT_SET_UP"
  | "NOT_FOUND"
  | "MALFORMED"
  | "PROVIDER";

/**
 * The collection's card states, Anki's own Card Counts categories. New, learning, young review and
 * mature review are mutually exclusive — every card is in exactly one — so their sum is the whole
 * collection. Null when the provider would not answer that search: an unread category stays missing
 * instead of silently counting as zero.
 */
export interface AnkiCollectionCounts {
  new: number | null;
  learning: number | null;
  young: number | null;
  mature: number | null;
}

/** One day of activity: distinct cards answered on one of AnkiDroid's own study days. */
export interface AnkiActivityDay {
  /** 0 is AnkiDroid's current study day; 1..[ANKI_ACTIVITY_DAYS] - 1 are the days before it. */
  daysAgo: number;
  /** Null when AnkiDroid would not answer that day's search. */
  cards: number | null;
}

/** Everything the Anki half of the page draws from, in one immutable read. */
export interface AnkiStatsSnapshot {
  /** The collection's four card states, from AnkiDroid's Card Counts searches. */
  collection: AnkiCollectionCounts;
  /** [ANKI_ACTIVITY_DAYS] entries, daysAgo 0 (AnkiDroid's current study day) first. */
  activity: AnkiActivityDay[];
  /** When this snapshot was read, epoch milliseconds. */
  updatedAt: number;
}

/** What the page renders from, and the one thing it can ask for. */
export interface AnkiStatsState {
  /** "loading" only until the first snapshot lands; a later refresh keeps the last one. */
  status: "loading" | "ready" | "unavailable";
  /** Why the data is missing or stale. Coexists with `data` when only a refresh failed. */
  failure: AnkiStatsFailure | null;
  /** True while a read is in flight. */
  refreshing: boolean;
  data: AnkiStatsSnapshot | null;
  refresh: () => void;
}

/**
 * The four collection-wide searches one snapshot needs, in the order the bridge answers them. They
 * are Anki's own Card Counts categories — new / learning / young review / mature review — and the
 * four are mutually exclusive, so their sum is the whole collection.
 */
export function ankiStatsQueries(): string[] {
  return ["is:new", "is:learn", "is:review prop:ivl<21", "is:review prop:ivl>=21"];
}

/**
 * The day searches the activity window needs, in the order the bridge answers them: daysAgo 0
 * (AnkiDroid's current study day) first, the oldest day last.
 *
 * `prop:rated=0` is AnkiDroid's current study day and `-1`, `-2` … the days before it — the manual's
 * own exact-day construct, since `rated:n -rated:(n-1)` is documented as unreliable. Each one counts
 * distinct cards with a review inside that day, so the window is a set of cards per day rather than
 * a number of answer events.
 */
export function ankiActivityQueries(): string[] {
  const queries: string[] = [];
  for (let daysAgo = 0; daysAgo < ANKI_ACTIVITY_DAYS; daysAgo += 1) {
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

/**
 * One read of everything, from the bridge alone: the four card states in one call, then the day
 * searches in whole chunks of [ANKI_QUERIES_PER_CALL] — the bridge rejects a longer list outright.
 * The answers are positional, so a day the provider would not answer keeps its null in place instead
 * of sliding the rest of the window along.
 */
async function readSnapshot(): Promise<AnkiStatsSnapshot> {
  const status = await Anki.status();
  if (status !== "ready") {
    throw {code: status === "notInstalled" ? "ANKI_NOT_INSTALLED" : "ANKI_PERMISSION"};
  }
  const counts = await Anki.counts(ankiStatsQueries());
  const at = (index: number): number | null => counts[index] ?? null;

  const searches = ankiActivityQueries();
  const activity: AnkiActivityDay[] = [];
  for (let start = 0; start < searches.length; start += ANKI_QUERIES_PER_CALL) {
    const answers = await Anki.counts(searches.slice(start, start + ANKI_QUERIES_PER_CALL));
    answers.forEach((cards, offset) => {
      activity.push({daysAgo: start + offset, cards: cards ?? null});
    });
  }

  return {
    collection: {new: at(0), learning: at(1), young: at(2), mature: at(3)},
    activity,
    updatedAt: Date.now(),
  };
}

/** The last good snapshot, shared by every mount of the page, and the read in flight for it. */
let cache: {
  snapshot: AnkiStatsSnapshot;
  at: number;
} | null = null;
let inFlight: Promise<AnkiStatsSnapshot> | null = null;

/**
 * The Anki snapshot, read once when the page mounts — every page mounts up front — and again on a
 * foreground return, cached for [ANKI_STATS_TTL_MS]. `refresh` forces a read. Every figure is
 * collection-wide: there is no deck choice to make here any more.
 */
export function useAnkiStats(): AnkiStatsState {
  const [data, setData] = useState<AnkiStatsSnapshot | null>(cache?.snapshot ?? null);
  const [failure, setFailure] = useState<AnkiStatsFailure | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  // Whether a read has ever settled. Until one has, "no numbers yet" is a read still on its way, not
  // an AnkiDroid that has nothing: the first render happens before the effect that starts the read.
  const [attempted, setAttempted] = useState(false);
  // Only the newest read may write state: a page re-entry supersedes an older one.
  const generation = useRef(0);
  const live = useRef(true);
  useEffect(
    () => () => {
      live.current = false;
    },
    []
  );

  const load = useCallback((force: boolean) => {
    if (!force && cache != null && Date.now() - cache.at < ANKI_STATS_TTL_MS) {
      setData(cache.snapshot);
      setFailure(null);
      return;
    }
    const mine = generation.current + 1;
    generation.current = mine;
    setRefreshing(true);
    // One read at a time: a second caller waits on the same promise instead of asking AnkiDroid the
    // same 88 searches again.
    const started = inFlight ?? (inFlight = readSnapshot());
    started
      .then((snapshot) => {
        if (!live.current || mine !== generation.current) return;
        cache = {snapshot, at: Date.now()};
        setData(snapshot);
        setFailure(null);
      })
      .catch((error) => {
        if (!live.current || mine !== generation.current) return;
        const why = failureOf(error);
        // Two failures mean the numbers are not merely stale but no longer ours to show — AnkiDroid
        // is gone, or the database permission was taken back — so the last snapshot goes with them.
        // Everything else (a provider that would not answer, a malformed body) keeps the last
        // snapshot on screen with the failure reported beside it.
        if (why === "NOT_INSTALLED" || why === "PERMISSION") {
          cache = null;
          setData(null);
        }
        setFailure(why);
      })
      .finally(() => {
        if (inFlight === started) inFlight = null;
        if (!live.current) return;
        setAttempted(true);
        if (mine === generation.current) setRefreshing(false);
      });
  }, []);

  useEffect(() => {
    load(false);
  }, [load]);

  // A foreground return cannot be seen by the effect above, so it is listened for here. The read it
  // asks for is TTL-aware: it answers from the cache it already has, and only asks AnkiDroid again
  // once that snapshot has aged out.
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (status) => {
      if (status === "active") load(false);
    });
    return () => subscription.remove();
  }, [load]);

  const refresh = useCallback(() => load(true), [load]);

  return {
    // "loading" until the first read settles: a page that has not been answered yet is not a page
    // with nothing on it, and an older refresh keeps the snapshot it already had.
    status: data != null ? "ready" : refreshing || !attempted ? "loading" : "unavailable",
    failure,
    refreshing,
    data,
    refresh,
  };
}
