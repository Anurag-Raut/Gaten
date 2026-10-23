/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani half of the analytics dashboard: one account's own numbers, read from the Kotlin side
 * and held outside any component, in the same shape the app's other stores use — the state is one
 * object, the screen subscribes to it, and nothing about it lives in a render.
 *
 * What it does, and what it deliberately leaves to the Kotlin side:
 *  - **One read per visit, coalesced.** The native side already serves the cached snapshot while it is
 *    younger than five minutes, so calling in when the dashboard becomes visible costs a request only
 *    when the numbers have aged out; a read already in flight is joined rather than repeated, so a
 *    mount and a pull-to-refresh together cannot spend two runs of eight requests.
 *  - **Content first, errors beside it.** A refresh that fails keeps the last complete numbers on
 *    screen ([stale] plus [failure]) rather than replacing them with an error; only a first read with
 *    nothing cached is an error state. A rejected token or a missing one is its own state, because
 *    the dashboard shows a connect card for it instead of a failure.
 *  - **No token here.** Nothing in this module ever sees the credential: the bridge reads it on the
 *    Kotlin side, and every answer is numbers or a status.
 *
 * The four states the dashboard renders from: [WaniKaniStatsStatus] "loading" (first read, nothing to
 * show yet), "ready" (numbers, possibly with [failure] as a note), "needsToken"/"authRejected" (the
 * connect card), and "failed" (nothing to show and a reason).
 */
import {useCallback, useEffect, useSyncExternalStore} from "react";
import {AppState} from "react-native";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {WaniKaniFailure, WaniKaniStatsSnapshot} from "../../native/GatenWaniKani";

/** What the WaniKani cards of the dashboard can say. */
export type WaniKaniStatsStatus =
  | "idle"
  | "loading"
  | "ready"
  | "refreshing"
  | "needsToken"
  | "authRejected"
  | "failed";

/** The WaniKani side of the dashboard, as one object. */
export interface WaniKaniStatsState {
  /** The last complete numbers, or null when none have ever been read. */
  snapshot: WaniKaniStatsSnapshot | null;
  status: WaniKaniStatsStatus;
  /**
   * Why the last read or refresh failed, when one did — a note beside numbers that are still shown,
   * or the reason there are none.
   */
  failure: WaniKaniFailure | null;
  /** True while the numbers on screen could not be brought up to date. */
  stale: boolean;
  /** Seconds WaniKani asked us to wait before asking again, for a rate limit. */
  retryAfterSeconds: number | null;
  /**
   * Epoch milliseconds when WaniKani may be asked again, after a rate limit that came with a wait.
   * The hook makes no call before it (the Kotlin side holds the waiting to the same deadline), so a
   * pull-to-refresh during the wait costs nothing. Null whenever there is no wait.
   */
  retryAt: number | null;
  /** Set when the numbers could not be kept on the device: they are for this session only. */
  cacheFailure: WaniKaniFailure | null;
  /** A message for a failure the bridge could not name — a programming error, not a WaniKani one. */
  message: string | null;
}

/**
 * How long a snapshot is shown without a question — mirrors the Kotlin client's `STATS_TTL_MS`.
 *
 * It is used here only to *avoid* a call: coming back to a tab or to the app with numbers this young
 * asks nothing at all, and the Kotlin side remains the authority on what a read costs.
 */
export const WANIKANI_STATS_TTL_MS = 5 * 60 * 1000;

let state: WaniKaniStatsState = {
  snapshot: null,
  status: "idle",
  failure: null,
  stale: false,
  retryAfterSeconds: null,
  retryAt: null,
  cacheFailure: null,
  message: null,
};

const listeners = new Set<() => void>();

/** The read in flight, so a mount and a refresh action together spend one run of requests. */
let inFlight: Promise<void> | null = null;

/**
 * Moves every time the state is reset (a disconnect, or a token that connected a different account).
 * A read captures it before it starts and refuses to write if it has moved since, so an answer for an
 * account that is no longer connected cannot put that account's numbers back on the screen.
 */
let generation = 0;

function set(patch: Partial<WaniKaniStatsState>) {
  state = {...state, ...patch};
  for (const listener of listeners) {
    listener();
  }
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The WaniKani numbers as they stand, for anything that is not a component. */
export function getWaniKaniStats(): WaniKaniStatsState {
  return state;
}

/**
 * Reads the numbers once: the cached snapshot while it is young enough, a fresh read otherwise.
 * [force] is the reader's own refresh, which ignores the cache (and still coalesces on a run that has
 * just finished). A read already in flight is joined, never doubled.
 */
export function loadWaniKaniStats(force = false): Promise<void> {
  if (state.retryAt != null && Date.now() < state.retryAt) {
    // WaniKani asked for a wait and it is not up: no call is made here, for any caller — not the
    // automatic read, not a refresh the reader pulls for. The numbers already on screen stand, with
    // the wait still reported beside them.
    return Promise.resolve();
  }
  if (inFlight) {
    return inFlight;
  }
  set({
    status: state.snapshot == null ? "loading" : force ? "refreshing" : state.status,
    message: null,
  });
  const mine = generation;
  const tracked = (force ? GatenWaniKani.refreshStats() : GatenWaniKani.stats())
    .then((result) => {
      // A reset while this was in flight (a disconnect, or another account's token) owns the state
      // now: this answer describes an account that is no longer connected, so it is dropped.
      if (mine !== generation) {
        return;
      }
      switch (result.status) {
        case "available":
          set({
            snapshot: result.snapshot,
            status: "ready",
            failure: result.failure ?? null,
            stale: result.stale,
            retryAfterSeconds: result.retryAfterSeconds ?? null,
            // A rate limit that only came as a note beside the numbers still holds the wait.
            retryAt: result.retryAfterSeconds == null ? null : Date.now() + result.retryAfterSeconds * 1000,
            cacheFailure: result.cacheFailure ?? null,
            message: null,
          });
          return;
        case "needsToken":
          // No token: nothing to show and nothing wrong, so the snapshot goes with it.
          set({
            snapshot: null,
            status: "needsToken",
            failure: null,
            stale: false,
            retryAfterSeconds: null,
            retryAt: null,
            cacheFailure: null,
            message: null,
          });
          return;
        case "authRejected":
          // WaniKani refused the stored token and the Kotlin side dropped the snapshot with it.
          set({
            snapshot: null,
            status: "authRejected",
            failure: null,
            stale: false,
            retryAfterSeconds: null,
            retryAt: null,
            cacheFailure: null,
            message: null,
          });
          return;
        case "failed":
          set({
            status: state.snapshot == null ? "failed" : "ready",
            failure: result.failure,
            stale: state.snapshot != null,
            retryAfterSeconds: result.retryAfterSeconds ?? null,
            retryAt:
              result.retryAfterSeconds == null
                ? null
                : Date.now() + result.retryAfterSeconds * 1000,
            message: null,
          });
          return;
      }
    })
    .catch((error: unknown) => {
      if (mine !== generation) {
        return;
      }
      // Only a bridge-level failure reaches here — WaniKani's own failures are answers.
      set({
        status: state.snapshot == null ? "failed" : "ready",
        failure: null,
        stale: state.snapshot != null,
        retryAt: null,
        message: error instanceof Error ? error.message : String(error),
      });
    })
    .finally(() => {
      // Only when this is still the read on record: a reset clears it, and a read started after one
      // must not be cleared by the answer of the read it replaced.
      if (inFlight === tracked) {
        inFlight = null;
      }
    });
  inFlight = tracked;
  return tracked;
}

/**
 * Forgets everything read, and everything waiting: the reader disconnected WaniKani, or connected a
 * token that belongs to a different account. A read already in flight is dropped rather than joined —
 * its answer belongs to the account that is no longer connected — and the next read starts fresh.
 */
export function resetWaniKaniStats(): void {
  generation += 1;
  inFlight = null;
  set({
    snapshot: null,
    status: "idle",
    failure: null,
    stale: false,
    retryAfterSeconds: null,
    retryAt: null,
    cacheFailure: null,
    message: null,
  });
}

/**
 * The dashboard's WaniKani numbers. The read happens once when the screen mounts — every tab mounts
 * up front, so the numbers are there before the reader arrives — and again when the app returns to
 * the foreground (the Kotlin side decides whether that costs a request at all), so no render or tab
 * switch ever asks WaniKani anything.
 */
export function useWaniKaniStats(): WaniKaniStatsState & {refresh: () => void} {
  const current = useSyncExternalStore(subscribe, getWaniKaniStats);
  const refresh = useCallback(() => {
    void loadWaniKaniStats(true);
  }, []);
  useEffect(() => {
    void loadWaniKaniStats(false);
  }, []);
  // A disconnect — or a token that belongs to another account — forgets everything the store held,
  // and no caller should have to remember to read again: the first subscriber to see the forgotten
  // state asks for the numbers. That is what keeps Home's card from sitting on "Checking…" after the
  // reader connects or disconnects in Settings.
  useEffect(() => {
    if (current.status !== "idle") {
      return;
    }
    void loadWaniKaniStats(false);
  }, [current.status]);
  // Coming back to the app is a fresh look, and the effect above cannot see it. Numbers older than
  // the TTL are brought up to date — the Kotlin side still decides what that costs, and a read in
  // flight is joined — and numbers this young cost nothing at all, so a quick foreground asks
  // WaniKani nothing.
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (status) => {
      if (status !== "active") {
        return;
      }
      const {snapshot} = getWaniKaniStats();
      if (snapshot == null || Date.now() - snapshot.fetchedAt >= WANIKANI_STATS_TTL_MS) {
        void loadWaniKaniStats(false);
      }
    });
    return () => subscription.remove();
  }, []);
  return {...current, refresh};
}
