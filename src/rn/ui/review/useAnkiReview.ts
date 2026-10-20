/**
 * The review screen's view of the session store: the same shape AnkiReviewScreen collected from
 * the shell-owned ViewModel, plus the store's actions and the lifecycle the Activity used to
 * own — the first-entry refresh, the paused/resumed queue re-read (AppState), and the leave
 * catch-up when the screen uncomposes without its owner ever pausing.
 *
 * The state itself lives in review-store.ts, so a tab switch keeps the card, its timer and its
 * open WaniKani details; this hook only subscribes and drives the lifecycle edges.
 */
import { useEffect, useRef, useState } from "react";
import { AppState } from "react-native";
import { useIsFocused } from "@react-navigation/native";
import * as Haptics from "../../native/GatenHaptics";
import {
  holdsSession,
  reviewStore,
  type ReviewState,
  type ReviewStep,
} from "./review-store";

/** The store's actions, exactly the ViewModel's public calls. */
export const reviewActions = {
  refresh: () => reviewStore.refresh(),
  openDecks: () => reviewStore.openDecks(),
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
  onPaused: () => reviewStore.onPaused(),
  onResumed: () => reviewStore.onResumed(),
};

/**
 * One look at the whole session: state, actions, and whether the screen's step keeps a session.
 * The Done cue (Confirm haptic + Done sound) fires here, exactly on the move *into* the finished
 * state — the step first seen is remembered per composition, so remounting onto a session left
 * finished stays silent, matching the Compose screen's seenStep guard.
 */
export function useAnkiReview(): ReviewState & {
  sessionActive: boolean;
  actions: typeof reviewActions;
} {
  const [state, setState] = useState<ReviewState>(reviewStore.getState());

  useEffect(() => {
    const subscription = reviewStore.subscribe(setState);
    return () => subscription.remove();
  }, []);

  // The screen's own start, once per entry: AnkiDroid's status, then its deck list. A session
  // already running is left alone — the store outlives this screen, so coming back to one has
  // to land on the card the reader left. Everything else is read again, because every state
  // before a card is stale by the time the reader comes back. RN keeps tab screens mounted
  // where Compose tore the composition down, so the tab's focus is what re-runs this.
  const isFocused = useIsFocused();
  useEffect(() => {
    if (!isFocused) return;
    const current = reviewStore.getState();
    if (!holdsSession(current.step, current.deck)) reviewActions.refresh();
  }, [isFocused]);

  // Back from another app, the card on screen may no longer be the one AnkiDroid would hand
  // out, so the queue is re-read on resume; the time spent away is not answer time. The
  // Activity owned those edges in Compose; AppState owns them here. Unmounting without the app
  // ever pausing — a tab switch away — takes the pause moment now, exactly like the Compose
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

  // The finished deck's one cue.
  const seenStep = useRef<ReviewStep>(state.step);
  useEffect(() => {
    if (state.step.kind === "done" && seenStep.current.kind !== "done") {
      void Haptics.play("confirm");
      void Haptics.play("done");
    }
    seenStep.current = state.step;
  }, [state.step]);

  return {
    ...state,
    sessionActive: holdsSession(state.step, state.deck),
    actions: reviewActions,
  };
}