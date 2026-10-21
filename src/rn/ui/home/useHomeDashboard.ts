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
import type {SearchResult} from "../../native/GatenDict";
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
  refreshing: false,
};

interface AnkiRead {
  anki: HomeAnkiState;
  streak: HomeStreakState;
}

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

/** Reads collection-wide due counts and study days, excluding nested decks from the due sum. */
async function readAnki(): Promise<AnkiRead> {
  const status = await Anki.status();
  if (status === "notInstalled") {
    return {
      anki: {...EMPTY_ANKI, status: "missing"},
      streak: {...EMPTY_STREAK, status: "unavailable", error: "AnkiDroid is not installed"},
    };
  }
  if (status === "needsPermission") {
    return {
      anki: {...EMPTY_ANKI, status: "permissionNeeded"},
      streak: {...EMPTY_STREAK, status: "unavailable", error: "AnkiDroid database permission is needed"},
    };
  }

  try {
    const decks = await Anki.decks();
    // Parent deck counts include their children; adding nested rows would count cards twice.
    const totals = decks.filter((deck) => !deck.name.includes("::")).reduce(
      (sum, deck) => ({
        newCount: sum.newCount + deck.newCount,
        learnCount: sum.learnCount + deck.learnCount,
        reviewCount: sum.reviewCount + deck.reviewCount,
      }),
      {newCount: 0, learnCount: 0, reviewCount: 0},
    );
    const counts = await Anki.counts(
      Array.from({length: 7}, (_, daysAgo) => `prop:rated=${daysAgo === 0 ? 0 : -daysAgo}`),
    );
    const daily = Array.from({length: 7}, (_, index) => counts[index] ?? null);
    const completedToday = daily[0];
    const streak = makeStreak(daily);
    const dueCount = totals.newCount + totals.learnCount + totals.reviewCount;
    return {
      anki: {
        status: "connected",
        deckName: null,
        ...totals,
        dueCount,
        completedToday,
        error: null,
        refreshing: false,
      },
      streak: {...streak, completedToday: daily[0] ?? null},
    };
  } catch (error) {
    const code = errorCode(error);
    const status: HomeAnkiStatus =
      code === "NOT_INSTALLED" ? "missing" :
      code === "PERMISSION" ? "permissionNeeded" :
      code === "NOT_SET_UP" ? "notSetUp" : "error";
    return {
      anki: {...EMPTY_ANKI, status, error: errorMessage(error)},
      streak: {...EMPTY_STREAK, status: "unavailable", error: errorMessage(error)},
    };
  }
}

/**
 * Loads home data only while Home is focused, then refreshes again when the app returns to the
 * foreground. Independent reads keep a history failure from masking either study service.
 */
export function useHomeDashboard(active: boolean): HomeDashboardData {
  const waniKaniStats = useWaniKaniStats(active);
  const [anki, setAnki] = useState(EMPTY_ANKI);
  const [streak, setStreak] = useState(EMPTY_STREAK);
  const [recentLookups, setRecentLookups] = useState<SearchResult[]>([]);
  const [recentLookupsStatus, setRecentLookupsStatus] = useState<RecentLookupsStatus>("loading");
  const [recentLookupsError, setRecentLookupsError] = useState<string | null>(null);
  const ankiGeneration = useRef(0);
  const historyGeneration = useRef(0);

  const loadAnki = useCallback(() => {
    const mine = ankiGeneration.current + 1;
    ankiGeneration.current = mine;
    setAnki((current) => ({...current, refreshing: true}));
    setStreak((current) => ({...current, error: null}));

    void readAnki()
      .then((result) => {
        if (mine !== ankiGeneration.current) return;
        setAnki((current) => {
          if (result.anki.status !== "error") return {...result.anki, refreshing: false};
          return {
            ...result.anki,
            deckName: current.deckName,
            newCount: current.newCount,
            learnCount: current.learnCount,
            reviewCount: current.reviewCount,
            dueCount: current.dueCount,
            completedToday: current.completedToday,
            refreshing: false,
          };
        });
        setStreak((current) => {
          const accessLost = ["missing", "permissionNeeded", "notSetUp"].includes(result.anki.status);
          if (accessLost) return result.streak;
          return result.streak.status === "unavailable" && current.status === "ready"
            ? {...current, error: result.streak.error}
            : result.streak;
        });
      })
      .catch((error: unknown) => {
        if (mine !== ankiGeneration.current) return;
        const message = errorMessage(error);
        setAnki((current) => ({...current, status: "error", error: message, refreshing: false}));
        setStreak((current) =>
          current.status === "ready"
            ? {...current, error: message}
            : {...current, status: "unavailable", error: message}
        );
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
    loadAnki();
    loadRecentLookups();
  }, [loadAnki, loadRecentLookups]);

  useEffect(() => {
    if (active) load();
  }, [active, load]);

  useEffect(() => {
    if (!active) return;
    const subscription = AppState.addEventListener("change", (status) => {
      if (status === "active") load();
    });
    return () => subscription.remove();
  }, [active, load]);

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
    refreshAnki: loadAnki,
    refreshWaniKani: waniKaniStats.refresh,
    refreshRecentLookups: loadRecentLookups,
  };
}
