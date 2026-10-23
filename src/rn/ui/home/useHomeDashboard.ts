/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/** The home screen's read-only view of Anki, WaniKani and locally opened dictionary entries. */
import {useCallback, useEffect, useRef, useState} from "react";
import {AppState} from "react-native";
import * as Anki from "../../native/GatenAnki";
import * as GatenDict from "../../native/GatenDict";
import type {AnkiDeckResult} from "../../native/GatenAnki";
import type {SearchResult} from "../../native/GatenDict";
import type {WaniKaniFailure} from "../../native/GatenWaniKani";
import {loadAnkiDecks, useAnkiDecks, type AnkiDecksState} from "../anki/useAnkiDecks";
import {useWaniKaniStats} from "../analytics/useWaniKaniStats";
import {primeRecentLookupHits} from "../search/searchStore";

export type HomeAnkiStatus =
  | "loading"
  | "connected"
  | "missing"
  | "permissionNeeded"
  | "notSetUp"
  | "error";

export interface HomeAnkiState {
  status: HomeAnkiStatus;
  deckName: string | null;
  newCount: number | null;
  learnCount: number | null;
  reviewCount: number | null;
  dueCount: number | null;
  /** Distinct cards reviewed today across the whole Anki collection. */
  completedToday: number | null;
  error: string | null;
  refreshing: boolean;
}

export type HomeWaniKaniStatus =
  | "loading"
  | "connected"
  | "disconnected"
  | "authRejected"
  | "error";

export interface HomeWaniKaniState {
  status: HomeWaniKaniStatus;
  /** Reviews WaniKani says are available in the current hour. */
  dueNow: number | null;
  /** Sum of WaniKani's actual hourly forecast buckets covering the next 24 hours. */
  dueNext24Hours: number | null;
  /** WaniKani's existing stats response has no completed-review history. */
  completedToday: null;
  error: string | null;
  /** The named reason the last read failed, so the card can tell "no internet" from a retry. */
  failure: WaniKaniFailure | null;
  refreshing: boolean;
}

export interface HomeWeekday {
  key: string;
  /** Local calendar weekday for this position in Anki's study-day sequence. */
  label: string;
  daysAgo: number;
  active: boolean | null;
  completed: number | null;
  today: boolean;
}

export interface HomeStreakState {
  status: "loading" | "ready" | "unavailable";
  /** Current consecutive Anki study days, capped to the observed seven-day window. */
  days: number | null;
  /** True when all seven observed days are active, so the actual streak may be longer. */
  atLeastSevenDays: boolean;
  /** True when the streak reaches the oldest observed day, so older activity could extend it. */
  capped: boolean;
  /** Monday through Sunday for the local week; future days are null, past activity uses Anki study days. */
  weekdays: HomeWeekday[];
  /** Distinct cards reviewed today across the whole Anki collection. */
  completedToday: number | null;
  error: string | null;
}

export type RecentLookupsStatus = "loading" | "ready" | "error";

export interface HomeDashboardData {
  anki: HomeAnkiState;
  waniKani: HomeWaniKaniState;
  streak: HomeStreakState;
  recentLookups: SearchResult[];
  recentLookupsStatus: RecentLookupsStatus;
  recentLookupsError: string | null;
  refreshAnki: () => void;
  refreshWaniKani: () => void;
  refreshRecentLookups: () => void;
}

/** How many study days Home's week and its streak are drawn from, today first. */
const STUDY_DAYS = 7;

const EMPTY_ANKI: HomeAnkiState = {
  status: "loading",
  deckName: null,
  newCount: null,
  learnCount: null,
  reviewCount: null,
  dueCount: null,
  completedToday: null,
  error: null,
  refreshing: false,
};

const EMPTY_STREAK: HomeStreakState = {
  status: "loading",
  days: null,
  atLeastSevenDays: false,
  capped: false,
  weekdays: [],
  completedToday: null,
  error: null,
};

const EMPTY_WANIKANI: HomeWaniKaniState = {
  status: "loading",
  dueNow: null,
  dueNext24Hours: null,
  completedToday: null,
  error: null,
  failure: null,
  refreshing: false,
};

function errorCode(error: unknown): string {
  if (typeof error === "object" && error != null && "code" in error && typeof error.code === "string") {
    return error.code.replace(/^ANKI_/, "");
  }
  return "PROVIDER";
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) return error.message;
  const code = errorCode(error);
  return code === "PROVIDER" ? String(error) : code;
}

function weekday(date: Date): string {
  return ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"][date.getDay()];
}

function localDateKey(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/** Turns Anki's current-to-older study-day counts into the current calendar week and a bounded streak. */
function makeStreak(daily: Array<number | null>): HomeStreakState {
  const todayCount = daily[0] ?? null;
  const today = new Date();
  const todayDate = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const daysSinceMonday = (todayDate.getDay() + 6) % 7;
  const monday = new Date(todayDate);
  monday.setDate(monday.getDate() - daysSinceMonday);
  const weekdays = Array.from({length: 7}, (_, index): HomeWeekday => {
    const date = new Date(monday);
    date.setDate(monday.getDate() + index);
    const daysAgo = Math.round((todayDate.getTime() - date.getTime()) / 86_400_000);
    const completed = daysAgo < 0 ? null : daily[daysAgo] ?? null;
    return {
      key: localDateKey(date),
      label: weekday(date),
      daysAgo,
      active: completed == null ? null : completed > 0,
      completed,
      today: daysAgo === 0,
    };
  });
  const completeWeek = daily.filter((count): count is number => count != null);
  if (daily.length !== 7 || completeWeek.length !== 7) {
    return {
      status: "unavailable",
      days: null,
      atLeastSevenDays: false,
      capped: false,
      weekdays,
      completedToday: todayCount,
      error: "Anki could not answer all seven study-day counts",
    };
  }

  // A streak remains active during today's study day until it ends, even before the first review.
  let index = completeWeek[0] > 0 ? 0 : 1;
  let days = 0;
  while (index < completeWeek.length && completeWeek[index] > 0) {
    days += 1;
    index += 1;
  }
  const capped = days > 0 && index === completeWeek.length;
  return {
    status: "ready",
    days,
    atLeastSevenDays: days >= 7,
    capped,
    weekdays,
    completedToday: todayCount,
    error: null,
  };
}

/** The seven study-day searches Home's week and its streak need, today first. */
function studyDayQueries(): string[] {
  return Array.from({length: STUDY_DAYS}, (_, daysAgo) => `prop:rated=${daysAgo === 0 ? 0 : -daysAgo}`);
}

/**
 * Reads the collection-wide study days behind Home's week. This is Home's own read rather than the
 * shared deck list's: the picker has no use for a week of history, so it does not pay for one.
 */
async function readStudyDays(): Promise<Array<number | null>> {
  const counts = await Anki.counts(studyDayQueries());
  return Array.from({length: STUDY_DAYS}, (_, index) => counts[index] ?? null);
}

/** The due counts of the decks the reader studies; a parent's counts already include its children. */
function studyTotals(decks: AnkiDeckResult[] | null): {newCount: number; learnCount: number; reviewCount: number} {
  if (decks == null) return {newCount: 0, learnCount: 0, reviewCount: 0};
  return decks
    .filter((deck) => !decks.some((parent) => deck.name.startsWith(`${parent.name}::`)))
    .reduce(
      (sum, deck) => ({
        newCount: sum.newCount + deck.newCount,
        learnCount: sum.learnCount + deck.learnCount,
        reviewCount: sum.reviewCount + deck.reviewCount,
      }),
      {newCount: 0, learnCount: 0, reviewCount: 0},
    );
}

/**
 * Home's Anki card, as the shared deck list has it. The card never invents a number the list does not
 * hold: a service that is missing or unpermitted says so, a refresh that failed keeps the last counts
 * with the failure beside them, and the list's own status carries the loading state.
 */
function ankiCard(decks: AnkiDecksState, completedToday: number | null): HomeAnkiState {
  if (decks.status === "idle" || decks.status === "loading") {
    return {...EMPTY_ANKI, refreshing: true};
  }
  if (decks.status === "unavailable") {
    const status: HomeAnkiStatus =
      decks.failure === "NOT_INSTALLED" ? "missing" :
      decks.failure === "PERMISSION" ? "permissionNeeded" :
      decks.failure === "NOT_SET_UP" ? "notSetUp" : "error";
    return {...EMPTY_ANKI, status, error: status === "error" ? "Anki could not be read" : null};
  }
  const totals = studyTotals(decks.studyDecks);
  return {
    status: decks.failure == null ? "connected" : "error",
    deckName: null,
    ...totals,
    dueCount: totals.newCount + totals.learnCount + totals.reviewCount,
    completedToday,
    error: decks.failure == null ? null : "Refresh failed",
    refreshing: decks.refreshing,
  };
}

/**
 * Loads home data once when the screen mounts — every tab is mounted up front, so the numbers are
 * already there when the reader swipes to Home — and refreshes again when the app returns to the
 * foreground. Independent reads keep a history failure from masking either study service.
 */
export function useHomeDashboard(): HomeDashboardData {
  const waniKaniStats = useWaniKaniStats();
  const decks = useAnkiDecks();
  const [daily, setDaily] = useState<Array<number | null> | null>(null);
  const [dailyError, setDailyError] = useState<string | null>(null);
  const [recentLookups, setRecentLookups] = useState<SearchResult[]>([]);
  const [recentLookupsStatus, setRecentLookupsStatus] = useState<RecentLookupsStatus>("loading");
  const [recentLookupsError, setRecentLookupsError] = useState<string | null>(null);
  const daysGeneration = useRef(0);
  const historyGeneration = useRef(0);

  const loadStudyDays = useCallback(() => {
    const mine = daysGeneration.current + 1;
    daysGeneration.current = mine;
    setDailyError(null);
    void readStudyDays()
      .then((counts) => {
        if (mine !== daysGeneration.current) return;
        setDaily(counts);
      })
      .catch((error: unknown) => {
        // A week that could not be read leaves the one already up alone, with the reason beside it.
        if (mine !== daysGeneration.current) return;
        setDailyError(errorMessage(error));
      });
  }, []);

  const loadRecentLookups = useCallback(() => {
    const mine = historyGeneration.current + 1;
    historyGeneration.current = mine;
    setRecentLookupsStatus((current) => current === "ready" ? "ready" : "loading");
    setRecentLookupsError(null);
    void GatenDict.recentLookups()
      .then((entries) => {
        if (mine !== historyGeneration.current) return;
        primeRecentLookupHits(entries);
        setRecentLookups(entries);
        setRecentLookupsStatus("ready");
        setRecentLookupsError(null);
      })
      .catch((error: unknown) => {
        if (mine !== historyGeneration.current) return;
        setRecentLookupsStatus("error");
        setRecentLookupsError(errorMessage(error));
      });
  }, []);

  const load = useCallback(() => {
    void loadAnkiDecks();
    loadStudyDays();
    loadRecentLookups();
  }, [loadStudyDays, loadRecentLookups]);

  /** The card's own retry: the shared deck list and Home's week, both read again. */
  const refreshAnki = useCallback(() => {
    void loadAnkiDecks();
    loadStudyDays();
  }, [loadStudyDays]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (status) => {
      if (status === "active") load();
    });
    return () => subscription.remove();
  }, [load]);

  const anki = ankiCard(decks, daily?.[0] ?? null);
  const accessLost =
    decks.status === "unavailable" &&
    (decks.failure === "NOT_INSTALLED" || decks.failure === "PERMISSION" || decks.failure === "NOT_SET_UP");
  let streak: HomeStreakState;
  if (accessLost) {
    // AnkiDroid is gone or unreadable: the week drawn from it is not one we can still claim.
    streak = {...EMPTY_STREAK, status: "unavailable", error: dailyError ?? "Anki could not be read"};
  } else if (daily == null) {
    streak = {...EMPTY_STREAK, status: dailyError == null ? "loading" : "unavailable", error: dailyError};
  } else {
    const week = makeStreak(daily);
    streak = dailyError == null ? week : {...week, error: dailyError};
  }

  let waniKani: HomeWaniKaniState;
  switch (waniKaniStats.status) {
    case "ready":
    case "refreshing": {
      const snapshot = waniKaniStats.snapshot;
      waniKani = {
        status: "connected",
        dueNow: snapshot?.dueNow ?? null,
        dueNext24Hours:
          snapshot?.forecast.reduce((total, bucket) => total + bucket.count, 0) ?? null,
        completedToday: null,
        error: waniKaniStats.message ?? waniKaniStats.failure,
        failure: waniKaniStats.failure,
        refreshing: waniKaniStats.status === "refreshing",
      };
      break;
    }
    case "needsToken":
      waniKani = {...EMPTY_WANIKANI, status: "disconnected"};
      break;
    case "authRejected":
      waniKani = {...EMPTY_WANIKANI, status: "authRejected"};
      break;
    case "failed":
      waniKani = {
        ...EMPTY_WANIKANI,
        status: "error",
        error: waniKaniStats.message ?? waniKaniStats.failure,
        failure: waniKaniStats.failure,
      };
      break;
    default:
      waniKani = EMPTY_WANIKANI;
  }

  return {
    anki,
    waniKani,
    streak,
    recentLookups,
    recentLookupsStatus,
    recentLookupsError,
    refreshAnki,
    refreshWaniKani: waniKaniStats.refresh,
    refreshRecentLookups: loadRecentLookups,
  };
}
