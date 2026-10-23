/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The AnkiDroid deck list, held outside any component because two surfaces read it: Home's Anki card
 * and the review flow's deck picker. Reading it separately in each is how the two could disagree —
 * the card kept the count a finished session had already worked off — and how one deck list could be
 * read twice within a moment.
 *
 * This holds the list, the reader's study selection and the export deck, and nothing else. The seven
 * study days behind Home's week and its streak are Home's own read: the picker has no use for them,
 * and seven searches is not a cost to pay again on every deck change.
 *
 * The shape follows the app's other stores: one state object, subscribers, a read that a second
 * caller joins rather than repeats, and the numbers already on screen kept while a refresh is in
 * flight. A read that fails keeps the last list and reports the failure beside it; only a service
 * that has gone away entirely — AnkiDroid uninstalled, its database permission taken back — takes the
 * list with it, because then those decks are no longer ours to show.
 */
import {useCallback, useEffect, useSyncExternalStore} from "react";
import * as Anki from "../../native/GatenAnki";
import type {AnkiDeckResult} from "../../native/GatenAnki";

/** Why the decks are not on screen, in the names the rest of the Anki code uses. */
export type AnkiDecksFailure = "NOT_INSTALLED" | "PERMISSION" | "NOT_SET_UP" | "PROVIDER";

/** What Home's Anki card and the deck picker both draw from. */
export interface AnkiDecksState {
  /**
   * "idle" before anything is read, "loading" while the first read is on its way, "ready" while
   * there is a list to show — possibly with a [failure] beside it — and "unavailable" when a read
   * failed and there is no list to show.
   */
  status: "idle" | "loading" | "ready" | "unavailable";
  /** Why the list is missing or stale. Null when the last read was answered. */
  failure: AnkiDecksFailure | null;
  /** Every deck AnkiDroid lists, or null before the first read settles. */
  decks: AnkiDeckResult[] | null;
  /** The decks the reader studies, in the same order, or null before the first read settles. */
  studyDecks: AnkiDeckResult[] | null;
  /** The deck Gaten exports to, so the picker opens on the deck the reader already chose. */
  configuredDeckName: string;
  /** True while a read is in flight; whatever is already on screen stays up. */
  refreshing: boolean;
}

let state: AnkiDecksState = {
  status: "idle",
  failure: null,
  decks: null,
  studyDecks: null,
  configuredDeckName: "",
  refreshing: false,
};

const listeners = new Set<() => void>();

/** The read in flight, so a card and the picker opening together spend one run of calls. */
let inFlight: Promise<void> | null = null;

function set(patch: Partial<AnkiDecksState>) {
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

function failureOf(error: unknown): AnkiDecksFailure {
  const code =
    typeof error === "object" && error != null && "code" in error && typeof error.code === "string"
      ? error.code
      : "";
  const named = /^ANKI_([A-Z_]+)$/.exec(code)?.[1];
  if (named === "NOT_INSTALLED" || named === "PERMISSION" || named === "NOT_SET_UP") {
    return named;
  }
  return "PROVIDER";
}

/** The deck list as it stands, for anything that is not a component. */
export function getAnkiDecks(): AnkiDecksState {
  return state;
}

/**
 * Reads AnkiDroid's status, its deck list, the study selection and the export deck together. A caller
 * that arrives while a read is in flight joins that read instead of asking the provider the same
 * questions twice, which is what makes a grade able to refresh the counts under a card the reader is
 * about to come back to.
 */
export function loadAnkiDecks(): Promise<void> {
  if (inFlight) {
    return inFlight;
  }
  set({
    status: state.decks == null ? "loading" : state.status,
    refreshing: true,
  });
  const tracked = (async () => {
    try {
      const status = await Anki.status();
      if (status !== "ready") {
        throw {code: status === "notInstalled" ? "ANKI_NOT_INSTALLED" : "ANKI_PERMISSION"};
      }
      const [decks, configuredDeckName, includedIds] = await Promise.all([
        Anki.decks(),
        // A reader who has not chosen an export deck yet is not a failure: the picker's own default
        // stands in for the choice, so nothing here reads as broken for them.
        Anki.getConfiguredDeckName().catch(() => ""),
        Anki.getStudyDeckIds(),
      ]);
      set({
        status: "ready",
        failure: null,
        decks,
        studyDecks: includedIds == null ? decks : decks.filter((deck) => includedIds.includes(deck.id)),
        configuredDeckName,
        refreshing: false,
      });
    } catch (error) {
      const failure = failureOf(error);
      const gone = failure === "NOT_INSTALLED" || failure === "PERMISSION";
      set({
        status: gone || state.decks == null ? "unavailable" : "ready",
        failure,
        decks: gone ? null : state.decks,
        studyDecks: gone ? null : state.studyDecks,
        refreshing: false,
      });
    }
  })().finally(() => {
    if (inFlight === tracked) {
      inFlight = null;
    }
  });
  inFlight = tracked;
  return tracked;
}

/**
 * The deck list both surfaces read. The read happens when the screen mounts — Home is mounted up
 * front, so the card has its numbers before the reader arrives — and a foreground return is left to
 * the caller that already listens for one, so no render and no tab switch asks AnkiDroid anything.
 */
export function useAnkiDecks(): AnkiDecksState & {refresh: () => void} {
  const current = useSyncExternalStore(subscribe, getAnkiDecks);
  const refresh = useCallback(() => {
    void loadAnkiDecks();
  }, []);
  useEffect(() => {
    void loadAnkiDecks();
  }, []);
  return {...current, refresh};
}
