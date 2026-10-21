/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The review page's view of its own store: the whole state the screen renders from, the actions that
 * move it, and the lifecycle the page owns — the paused/resumed queue re-read (AppState), and the
 * leave catch-up when the screen unmounts without its owner ever pausing.
 *
 * The state itself lives in review-store.ts, so leaving the page mid-session keeps the hub's counts,
 * the card on screen and a WaniKani review in progress; this hook only subscribes and drives the
 * lifecycle edges. Which step the page opens on is not decided here: Home asks the store for one
 * before it pushes the page.
 */
import { useEffect, useRef, useState } from "react";
import { AppState } from "react-native";
import * as Haptics from "../../native/GatenHaptics";
import {
  reviewStore,
  type ReviewState,
  type ReviewStep,
} from "./review-store";

/** The store's actions, exactly the ones the screen calls. */
export const reviewActions = {
  openAnki: () => reviewStore.openAnki(),
  openDecks: () => reviewStore.openDecks(),
  openWaniKani: () => reviewStore.openWaniKani(),
  connectWaniKani: (token: string) => reviewStore.connectWaniKani(token),
  permissionDenied: () => reviewStore.permissionDenied(),
  startDeck: (deck: Parameters<typeof reviewStore.startDeck>[0]) =>
    reviewStore.startDeck(deck),
  changeDeck: () => reviewStore.changeDeck(),
  retry: () => reviewStore.retry(),
  reveal: () => reviewStore.reveal(),
  grade: (
    session: Extract<ReviewStep, { kind: "session" }>,
    ease: number
  ) => reviewStore.grade(session, ease),
  toggleKanji: (character: string) => reviewStore.toggleKanji(character),
  toggleRadical: (id: number) => reviewStore.toggleRadical(id),
  closeDetails: () => reviewStore.closeDetails(),
  wkEntry: (text: string) => reviewStore.wkEntry(text),
  wkCheck: () => reviewStore.wkCheck(),
  wkRetrySubmit: () => reviewStore.wkRetrySubmit(),
  retryWaniKani: () => reviewStore.retryWaniKani(),
  onPaused: () => reviewStore.onPaused(),
  onResumed: () => reviewStore.onResumed(),
};

/**
 * One look at the whole page: the state and the actions. The finished-queue cue (Confirm haptic +
 * Done sound) fires here, exactly on the move *into* a finished state — the step first seen is
 * remembered per composition, so remounting onto a session left finished stays silent, matching the
 * Compose screen's seenStep guard.
 */
export function useReview(): ReviewState & {
  actions: typeof reviewActions;
} {
  const [state, setState] = useState<ReviewState>(reviewStore.getState());

  useEffect(() => {
    const subscription = reviewStore.subscribe(setState);
    return () => subscription.remove();
  }, []);

  // Back from another app, the card on screen may no longer be the one AnkiDroid would hand
  // out, so the queue is re-read on resume; the time spent away is not answer time. The
  // Activity owned those edges in Compose; AppState owns them here. Unmounting without the app
  // ever pausing — leaving the page — takes the pause moment now, exactly like the Compose
  // screen's onDispose did.
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (status) => {
      if (status === "active") reviewActions.onResumed();
      else reviewActions.onPaused();
    });
    return () => {
      subscription.remove();
      reviewActions.onPaused();
    };
  }, []);

  // The finished queue's one cue, for either service: the same two sounds the app already plays for
  // a deck that is done.
  const seenStep = useRef<ReviewStep>(state.step);
  useEffect(() => {
    const finished = state.step.kind === "done" || state.step.kind === "wkDone";
    const wasFinished =
      seenStep.current.kind === "done" || seenStep.current.kind === "wkDone";
    if (finished && !wasFinished) {
      void Haptics.play("confirm");
      void Haptics.play("done");
    }
    seenStep.current = state.step;
  }, [state.step]);

  return {
    ...state,
    actions: reviewActions,
  };
}
