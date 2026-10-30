/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The review flow's state, held in a module singleton so it survives leaving the page — the RN port
 * of the shell-owned review view model. A session left open keeps its card, its timer and its open
 * WaniKani details; pushing the page again mid-session lands on the card the reader left.
 *
 * The flow is one pushed page, not a tab: Home's two service cards ask this store for a step and then
 * push the page, which draws whatever step it finds. The steps are the Anki deck list and card
 * session, and the WaniKani connection and review; none of them is a route of its own, because a step
 * is not a page the reader navigates to — Back is the page's own pop.
 *
 * Every provider call goes through a bridge; failures come back as rejections whose `code` is the
 * Kotlin failure's own name (`ANKI_NOT_INSTALLED`, `WK_OFFLINE`, …) with the wrapped detail as the
 * message. AnkiDroid keeps Anki's schedule and WaniKani keeps its own: this file decides what is on
 * screen and when a review is finished, and never what a card or a subject is scheduled for.
 *
 * Nothing here is left in a component, and nothing here hides anything in the shell: a pushed page is
 * full-screen with no bottom bar by the shell's own rule, so the flow does not have to ask for it.
 */
import {romajiToHiragana} from "./wanikani-romaji";
import * as Anki from "../../native/GatenAnki";
import * as WaniKani from "../../native/GatenWaniKani";
import {getAnkiDecks, loadAnkiDecks} from "../anki/useAnkiDecks";
import {
  getWaniKaniStats,
  invalidateWaniKaniStats,
  loadWaniKaniStats,
  resetWaniKaniStats,
} from "../analytics/useWaniKaniStats";
import type {
  AnkiCardResult,
  AnkiDeckResult,
  AnkiPreferencesResult,
} from "../../native/GatenAnki";
import type {
  WaniKaniFailure,
  WaniKaniLessonItem,
  WaniKaniLearningPreferences,
  WaniKaniReviewItem,
} from "../../native/GatenWaniKani";

/** Why an Anki review cannot go on — the Kotlin AnkiReviewFailure's own names. */
export type ReviewFailure =
  | "NOT_INSTALLED"
  | "PERMISSION"
  | "NOT_SET_UP"
  | "NOT_FOUND"
  | "MALFORMED"
  | "PROVIDER";

/** One card, before (`revealed` false) or after the answer is shown. */
export interface ReviewCard {
  noteId: number;
  modelId: number;
  ord: number;
  deckName: string;
  question: string;
  answer: string;
  css: string;
  fieldNames: string[];
  fields: string[];
  answerFields: {expression: string; reading: string; meaning: string} | null;
  /** The sentence the note itself carries, readable offline; null when it has none. */
  exampleSentence: string | null;
  typedAnswer: boolean;
}

/**
 * One subject of a WaniKani review in progress, and how far the reader is through it. The counts are
 * the ones WaniKani's own `POST /reviews` asks for: how many times this subject's meaning and reading
 * were answered wrongly, which is why a wrong attempt is counted where it happened rather than
 * reported at the end.
 */
export interface WaniKaniSession {
  items: WaniKaniReviewItem[];
  /** The parts the whole queue asks for: a meaning always, and a reading when the subject has one. */
  totalParts: number;
  /** The parts finished before the one on screen, across the whole queue. */
  doneParts: number;
  index: number;
  part: "meaning" | "reading";
  /** What is in the field right now. */
  entry: string;
  incorrectMeaning: number;
  incorrectReading: number;
  feedback: {tone: "right" | "wrong"; text: string} | null;
  /**
   * True once a part has been answered wrongly and not yet answered correctly: WaniKani asks for the
   * right answer to be typed before the subject moves on, so the next correct check finishes the part
   * the wrong one opened.
   */
  retrying: boolean;
  /**
   * `reviewed` is the wrong answer's own stop: the right answer is on screen and the reader must
   * continue (then type it) before the part can finish. `submitting` and `submitFailed` are the
   * review being sent; a right answer goes straight there, as WaniKani's own flow does.
   */
  phase: "answering" | "reviewed" | "submitting" | "submitFailed";
  /** Why the last submission failed, or null when it has not failed — or the token was refused. */
  failure: WaniKaniFailure | null;
  /** True when the submission was refused for the token rather than for the network. */
  authRejected: boolean;
}

/**
 * One lesson batch in progress: the subjects being taught and then quizzed, and how far the reader is
 * through them. The counts of wrong answers are the ones the lesson quiz tracks so a wrong answer can
 * be remembered, but a lesson is finished with `PUT /assignments/<id>/start`, not with a review, so
 * nothing is sent back until the subject's quiz is fully correct.
 *
 * Each subject is started the moment its quiz is passed, so leaving mid-batch keeps the ones already
 * finished and returns the rest to the lessons queue.
 */
export interface WaniKaniLessonSession {
  /** The subjects being taught and quizzed in this batch, in WaniKani's lesson order. */
  batch: WaniKaniLessonItem[];
  /** How many lessons are still queued after this batch, for the closing line. */
  remaining: number;
  /** `teaching` one card at a time, then `quizzing` them; `starting` sends a finished one. */
  phase: "teaching" | "quizzing" | "starting" | "startFailed";
  teachIndex: number;
  quizIndex: number;
  part: "meaning" | "reading";
  entry: string;
  incorrectMeaning: number;
  incorrectReading: number;
  feedback: {tone: "right" | "wrong"; text: string} | null;
  retrying: boolean;
  /** Why the last start failed, or null when it has not failed - or the token was refused. */
  failure: WaniKaniFailure | null;
  /** True when the start was refused for the token rather than for the network. */
  authRejected: boolean;
  /** Parts finished across the whole batch: the teach cards, then the quiz answers. */
  doneParts: number;
  /** Teach cards plus quiz parts, the denominator the progress line moves against. */
  totalParts: number;
}

/** How fresh the hub's numbers are, so the page can say what it is showing. */
export type WaniKaniSync = "fresh" | "offline" | "failed";

/** The queues the hub shows, from one `GET /summary` snapshot. */
export interface WaniKaniSummary {
  dueNow: number;
  lessonsNow: number;
  /** When the next reviews come up, or null when WaniKani has none scheduled. */
  nextReviewAt: number | null;
  /** Reviews becoming due in the next hour, from the forecast; null when it is not derivable. */
  nextHourCount: number | null;
  /** Epoch milliseconds this snapshot was read, for cache freshness. */
  fetchedAt: number;
  /** Fresh numbers, cached because the device is offline, or a refresh that failed. */
  sync: WaniKaniSync;
  /** Seconds WaniKani asked us to wait, when a rate limit caused the failure. */
  retryAfterSeconds: number | null;
}

export interface WaniKaniHubIssue {
  failure: WaniKaniFailure;
  retryAfterSeconds: number | null;
}

/** Independent queue outcomes keep one failed read from hiding the other study destination. */
export interface WaniKaniHubState {
  kind: "wkHome";
  summary: WaniKaniSummary | null;
  failed: boolean;
  failure: WaniKaniHubIssue | null;
  connection: "connected" | "disconnected" | "expired";
  refreshing: boolean;
  lessonsLocked: boolean;
  pausedLesson: WaniKaniLessonSession | null;
  pausedReview: WaniKaniSession | null;
  reviewFailure: WaniKaniHubIssue | null;
  lessonFailure: WaniKaniHubIssue | null;
  learningPreferences?: WaniKaniLearningPreferences | null;
  learningPreferencesFailed?: boolean;
  sessionFinished?: boolean;
  /**
   * The one row action the reader is waiting on — the queue or the sync they just tapped — or null
   * when nothing of theirs is in flight. A background refresh leaves it null, so a row the reader
   * did not touch never spins on another read's behalf, and the page can draw the spinner on the
   * row that was tapped rather than over the whole hub.
   */
  pendingAction?: WaniKaniHubPendingAction | null;
}

/** The hub row actions a reader can have in flight, named as the rows are. */
export type WaniKaniHubPendingAction = "reviews" | "lessons" | "sync";

/** What the review screen is doing. */
export type ReviewStep =
  | {
      /**
       * The read that precedes a step: AnkiDroid's status and deck list, or WaniKani's token and
       * queue. The page is pushed before this answers, so it has something honest to draw rather
       * than the step the reader left last time.
       */
      kind: "opening";
      source: "anki" | "waniKani";
    }
  | { kind: "blocked"; failure: ReviewFailure }
  | { kind: "decks"; decks: AnkiDeckResult[]; configuredDeckName: string; filteredEmpty?: boolean }
  | {
      kind: "session";
      card: ReviewCard;
      token: number;
      buttonLabels: string[];
      mediaFiles: string[];
      startedAt: number;
      revealed: boolean;
      /** Cards finished in this deck, and how many were due when the session started. */
      answered: number;
      total: number;
    }
  | { kind: "done"; deckName: string }
  | {
      kind: "failed";
      failure: ReviewFailure;
      detail?: string;
      afterGrade: boolean;
    }
  | { kind: "wkConnect"; rejected: boolean }
  | WaniKaniHubState
  | { kind: "wkReview"; session: WaniKaniSession }
  | { kind: "wkLesson"; session: WaniKaniLessonSession }
  | {
      kind: "wkFailed";
      failure: WaniKaniFailure;
      retryAfterSeconds: number | null;
      /** Which read to make again when the reader retries. */
      retry: "home" | "reviews" | "lessons";
    };

/** The whole observable state the screen renders from. */
export interface ReviewState {
  step: ReviewStep;
  deck: AnkiDeckResult | null;
  busy: boolean;
  expandedKanji: string | null;
  expandedRadical: number | null;
}

/** What connecting from the connection screen answered, for that screen to say. */
export type WaniKaniConnectOutcome =
  | { status: "connected" }
  | { status: "invalidToken" }
  | { status: "failed"; failure: WaniKaniFailure }
  | { status: "bridge"; message: string };

/**
 * How deep a step sits, so the screen that arrives slides in from the side it was opened from: a move
 * to a deeper step enters forward, a move back enters from the leading edge. The failure-shaped steps
 * stand where the reader met them.
 */
export function stepDepth(step: ReviewStep): number {
  switch (step.kind) {
    case "opening":
    case "blocked":
    case "wkFailed":
      return 0;
    case "decks":
    case "wkConnect":
    case "wkHome":
      return 1;
    case "session":
    case "failed":
    case "wkReview":
    case "wkLesson":
      return 2;
    case "done":
      return 3;
  }
}

/** The failure an Anki bridge rejection carried, without any of the provider's internals. */
function failureOf(error: unknown): { failure: ReviewFailure; detail?: string } {
  if (typeof error !== "object" || error == null) {
    return { failure: "PROVIDER" };
  }
  const code = "code" in error ? error.code : undefined;
  const message = "message" in error ? error.message : undefined;
  const detail = typeof message === "string" && message ? message : undefined;
  const match =
    typeof code === "string" ? /^ANKI_([A-Z_]+)$/.exec(code) : null;
  if (match) {
    const failure = match[1] as ReviewFailure;
    return { failure, detail };
  }
  return { failure: "PROVIDER", detail };
}

/** The WaniKani failure a bridge rejection carried; a bridge-level error is the network's own. */
function waniKaniFailureOf(error: unknown): WaniKaniFailure {
  if (typeof error === "object" && error != null && "code" in error) {
    const code = error.code;
    const match = typeof code === "string" ? /^WK_([A-Z_]+)$/.exec(code) : null;
    if (match) return match[1] as WaniKaniFailure;
  }
  return "SERVER";
}

/**
 * Whether a shared-stats status is an answer rather than a read still on its way. `idle`,
 * `loading` and `refreshing` are all transient: a caller that has awaited a read and still sees one
 * of them got no answer for its account, so it must read again instead of leaving a skeleton up.
 */
function isTerminalWaniKaniStatus(status: string): boolean {
  return status === "ready" || status === "failed" || status === "needsToken" || status === "authRejected";
}

/** How a meaning is compared: WaniKani answers are case-insensitive and their spacing does not count. */
function normalizeMeaning(text: string): string {
  return text.trim().toLowerCase().replace(/\s+/g, " ");
}

/** The parts one subject asks for: its meaning, and its reading when WaniKani quizzes one. */
function partsOf(item: WaniKaniReviewItem): number {
  return item.readings.length > 0 ? 2 : 1;
}

/**
 * How many lessons one batch teaches before quizzing them: WaniKani's own default lesson batch size.
 * The API lets a client choose, and the account's stored preference is not read here - that would be
 * another request on every queue read, kept as a follow-up rather than paid per batch.
 */
export const LESSON_BATCH_SIZE = 5;

/** A fresh session over [items], at the first part of the first subject. */
function startWaniKaniSession(items: WaniKaniReviewItem[]): WaniKaniSession {
  return {
    items,
    totalParts: items.reduce((total, item) => total + partsOf(item), 0),
    doneParts: 0,
    index: 0,
    part: "meaning",
    entry: "",
    incorrectMeaning: 0,
    incorrectReading: 0,
    feedback: null,
    retrying: false,
    phase: "answering",
    failure: null,
    authRejected: false,
  };
}

/** A fresh lesson batch over [batch], at the first teaching card. */
function startWaniKaniLessonSession(
  batch: WaniKaniLessonItem[],
  remaining: number
): WaniKaniLessonSession {
  return {
    batch,
    remaining,
    phase: "teaching",
    teachIndex: 0,
    quizIndex: 0,
    part: "meaning",
    entry: "",
    incorrectMeaning: 0,
    incorrectReading: 0,
    feedback: null,
    retrying: false,
    failure: null,
    authRejected: false,
    doneParts: 0,
    totalParts: batch.length + batch.reduce((total, item) => total + partsOf(item), 0),
  };
}

type StepListener = (state: ReviewState) => void;

class ReviewStore {
  private state: ReviewState = {
    step: { kind: "opening", source: "anki" },
    deck: null,
    busy: false,
    expandedKanji: null,
    expandedRadical: null,
  };

  private stepListeners = new Set<StepListener>();

  /**
   * A lesson batch left part-way. Held here rather than in the step so the hub can offer to resume it
   * after the reader has looked at reviews in between; cleared when the batch is finished.
   */
  private pausedLesson: WaniKaniLessonSession | null = null;
  private pausedReview: WaniKaniSession | null = null;
  private hubRequest = 0;
  private lastHub: WaniKaniHubState | null = null;
  /**
   * The hub row action in flight, and the token that owns it. The token is what keeps a superseded
   * action's completion from clearing a newer action's spinner or a reset hub's clean state.
   */
  private pendingAction: WaniKaniHubPendingAction | null = null;
  private pendingToken = 0;
  /** Absolute queue-only rate limit; the shared stats store only knows waits from summary reads. */
  private queueRetryAt: number | null = null;
  /** When the app went to the background with a card on screen, so the time spent away can be
   *  taken back out of AnkiDroid's answer time. */
  private pausedAt: number | null = null;

  getState(): ReviewState {
    return this.state;
  }

  subscribe(listener: StepListener): { remove: () => void } {
    this.stepListeners.add(listener);
    return { remove: () => this.stepListeners.delete(listener) };
  }

  private set(patch: Partial<ReviewState>) {
    this.state = { ...this.state, ...patch };
    if (patch.step?.kind === "wkHome") this.lastHub = patch.step;
    if (patch.step?.kind === "wkReview") this.pausedReview = patch.step.session;
    if (patch.step?.kind === "wkLesson") this.pausedLesson = patch.step.session;
    for (const listener of this.stepListeners) listener(this.state);
  }

  private setBusy(busy: boolean) {
    this.set({ busy });
  }

  /** Runs `action` only when no provider call is in flight. */
  private ifIdle(action: () => void) {
    if (this.state.busy) return;
    action();
  }

  /**
   * Marks `action` as the row the reader is waiting on and hands back the token that ends it. The
   * token is bumped by a reset or a newer action, so a late completion can only ever clear the
   * spinner it started.
   */
  private beginPending(action: WaniKaniHubPendingAction): number {
    this.pendingAction = action;
    const token = ++this.pendingToken;
    if (this.state.step.kind === "wkHome") this.set({step: this.hubStep()});
    return token;
  }

  /** Clears the pending row, unless a reset or a newer action has taken its place. */
  private endPending(token: number): void {
    if (token !== this.pendingToken) return;
    this.pendingAction = null;
    if (this.state.step.kind === "wkHome") this.set({step: this.hubStep()});
  }

  /** Forgets the pending row outright: an account change, or a fresh entry that supersedes it. */
  private clearPending(): void {
    this.pendingToken += 1;
    this.pendingAction = null;
  }

  /**
   * Seconds a rate limit still asks the reader to wait, or null when no wait is in force. Queue
   * reads can be rate-limited independently of the shared summary, so the later deadline wins.
   */
  private rateLimitWaitSeconds(): number | null {
    const now = Date.now();
    if (this.queueRetryAt != null && this.queueRetryAt <= now) this.queueRetryAt = null;
    const retryAt = Math.max(getWaniKaniStats().retryAt ?? 0, this.queueRetryAt ?? 0);
    const seconds = Math.ceil((retryAt - now) / 1000);
    return seconds > 0 ? seconds : null;
  }

  /** Retains a queue's explicit wait without shortening an overlapping shared-summary wait. */
  private rememberQueueRateLimit(retryAfterSeconds: number | null | undefined): void {
    if (retryAfterSeconds == null) return;
    this.queueRetryAt = Math.max(this.queueRetryAt ?? 0, Date.now() + retryAfterSeconds * 1000);
  }

  /** States a blocked explicit sync without spending a summary request during the queue's wait. */
  private showRateLimitWait(wait: number): void {
    const summary = this.state.step.kind === "wkHome" ? this.state.step.summary : this.lastHub?.summary;
    this.set({step: this.hubStep({
      refreshing: false,
      failed: summary == null,
      summary: summary == null ? null : {...summary, sync: "failed", retryAfterSeconds: wait},
      failure: {failure: "RATE_LIMITED", retryAfterSeconds: wait},
    })});
  }

  private collapseDetails() {
    this.set({ expandedKanji: null, expandedRadical: null });
  }

  /**
   * What entering the flow from Home's Anki card asks for: AnkiDroid's own status and its deck list,
   * read through the store Home's card draws from, so the two can never show different decks or
   * different counts. A service that cannot be used at all — not installed, no permission, no
   * collection — is stated where the reader tapped, rather than as an empty deck list.
   */
  openAnki() {
    this.ifIdle(() => {
      // Queue reads no longer hold global busy. Retire their ownership before the Anki picker becomes
      // visible so a late WaniKani answer cannot replace it.
      this.hubRequest += 1;
      this.clearPending();
      // Back to the picker, whatever step was left behind: the deck that was being studied is not
      // the one this entry chose.
      this.set({ deck: null, step: { kind: "opening", source: "anki" } });
      void this.readDecks();
    });
  }

  /** Reads the deck list, fresh, and lands on it. */
  openDecks() {
    if (this.state.busy) return;
    void this.readDecks();
  }

  /**
   * One read of the shared deck list, then the picker it feeds — or the state that stops it. Busy is
   * held across the whole read, so two taps in one frame cannot start two.
   */
  private async readDecks(): Promise<void> {
    this.setBusy(true);
    this.collapseDetails();
    try {
      await loadAnkiDecks();
      const {failure, decks, studyDecks, configuredDeckName} = getAnkiDecks();
      if (failure === "NOT_INSTALLED" || failure === "PERMISSION" || failure === "NOT_SET_UP") {
        // Stated where the reader tapped: an empty deck list would read as "nothing to study", and
        // none of these is retried away by asking again.
        this.set({step: {kind: "blocked", failure}});
        return;
      }
      if (failure != null || decks == null || studyDecks == null) {
        this.set({step: {kind: "failed", failure: failure ?? "PROVIDER", afterGrade: false}});
        return;
      }
      this.set({step: {kind: "decks", decks: studyDecks, configuredDeckName,
        filteredEmpty: studyDecks.length === 0 && decks.length > 0}});
    } finally {
      this.setBusy(false);
    }
  }

  /** The permission dialog came back refused, so review cannot run yet. */
  permissionDenied() {
    this.set({ step: { kind: "blocked", failure: "PERMISSION" } });
  }

  /** A deck removed in Settings must not resume through an old session retained by the store. */
  studyDeckSelectionChanged(ids: number[] | null) {
    if (ids != null && this.state.deck != null && !ids.includes(this.state.deck.id)) {
      this.set({deck: null, step: {kind: "opening", source: "anki"}});
    }
  }

  /**
   * Opens WaniKani, preserving an unfinished session until the reader chooses to resume it. The read
   * decides where to land, so nothing is drawn until it answers: a token that is missing, or one
   * WaniKani refuses, opens the connection page, which is the only screen that can fix it.
   */
  openWaniKani() {
    this.ifIdle(() => {
      if (this.state.step.kind === "wkLesson") this.pausedLesson = this.state.step.session;
      if (this.state.step.kind === "wkReview") this.pausedReview = this.state.step.session;
      // The configuration read is part of this visit too. Bump first, before it awaits, so an
      // earlier entry cannot reopen WaniKani after a provider switch or account reset.
      this.clearPending();
      const request = ++this.hubRequest;
      this.set({ deck: null, step: { kind: "opening", source: "waniKani" } });
      this.setBusy(true);
      void (async () => {
        try {
          const configured = await WaniKani.isConfigured();
          if (request !== this.hubRequest) return;
          // Only the configuration gate is globally busy. The hub's refresh shows its own loading
          // state so cached rows remain available while the summary is read.
          this.setBusy(false);
          if (!configured) {
            this.accountUnavailable("disconnected");
            return;
          }
          void this.enterWaniKaniHome();
        } catch (error) {
          if (request !== this.hubRequest) return;
          this.set({step: this.hubStep({failed: true, refreshing: false,
            failure: {failure: waniKaniFailureOf(error), retryAfterSeconds: null}})});
        } finally {
          if (request === this.hubRequest) this.setBusy(false);
        }
      })();
    });
  }

  /** Forget account-specific queues and sessions whenever credentials change. */
  resetWaniKaniAccount() {
    this.hubRequest += 1;
    this.clearPending();
    this.queueRetryAt = null;
    this.lastHub = null;
    this.pausedLesson = null;
    this.pausedReview = null;
    // Reset deliberately retires the configuration gate too; its stale finally is still forbidden
    // from clearing any later action.
    this.setBusy(false);
    if (this.state.step.kind.startsWith("wk")) {
      this.set({step: {kind: "opening", source: "waniKani"}});
    }
  }

  /**
   * The token is gone, or WaniKani refuses it. Both open the connection page: the hub can only say
   * the queues are unreachable, and this is the one screen where the reader can do something about
   * it. A refused token is carried over so the page explains why it is being asked for again.
   */
  private accountUnavailable(connection: "disconnected" | "expired") {
    this.resetWaniKaniAccount();
    this.set({step: {kind: "wkConnect", rejected: connection === "expired"}});
  }

  /**
   * The same loss, met from a queue the reader asked for inside the hub: the failure belongs to that
   * queue, so the hub stays up and its own row explains it rather than dropping the reader out.
   */
  private accountUnavailableInSession(connection: "disconnected" | "expired") {
    this.resetWaniKaniAccount();
    this.set({step: this.hubStep({connection})});
  }

  showWaniKaniConnect() {
    this.ifIdle(() => {
      const rejected = this.state.step.kind === "wkHome" && this.state.step.connection === "expired";
      this.set({step: {kind: "wkConnect", rejected}});
    });
  }

  /** Retain confirmed data and independent queue failures when one section changes. */
  private hubStep(patch: Partial<WaniKaniHubState> = {}): WaniKaniHubState {
    const step = this.state.step.kind === "wkHome" ? this.state.step : this.lastHub;
    return {
      kind: "wkHome",
      summary: step?.summary ?? null,
      failed: step?.failed ?? false,
      failure: step?.failure ?? null,
      connection: step?.connection ?? "connected",
      refreshing: step?.refreshing ?? false,
      lessonsLocked: step?.lessonsLocked ?? false,
      pausedLesson: this.pausedLesson,
      pausedReview: this.pausedReview,
      reviewFailure: step?.reviewFailure ?? null,
      lessonFailure: step?.lessonFailure ?? null,
      learningPreferences: step?.learningPreferences ?? null,
      learningPreferencesFailed: step?.learningPreferencesFailed ?? false,
      sessionFinished: step?.sessionFinished ?? false,
      pendingAction: this.pendingAction,
      ...patch,
    };
  }

  /**
   * Refresh both summary counts without blanking confirmed values; ignore superseded reads. The
   * counts themselves are read through the store Home's card and the dashboard draw from, so one
   * read serves all three surfaces and they cannot disagree about what is due. The lesson budget
   * stays this page's own read: nothing else in the app has a use for it.
   *
   * `force` is the reader's own sync, or the re-read a finished session or a fresh connection asks
   * for. A plain hub entry leaves it off, and the shared store then answers from its own cache when
   * the numbers are young instead of spending a run of requests to learn what it already knows.
   * `sessionFinished` marks the re-read a finished session asks for, so the rows say they are
   * updating rather than celebrating a completion they have not confirmed.
   */
  private async enterWaniKaniHome(
    options: {force?: boolean; sessionFinished?: boolean} = {},
  ): Promise<void> {
    const request = ++this.hubRequest;
    this.set({step: this.hubStep({refreshing: true, failed: false, failure: null,
      ...(options.sessionFinished ? {sessionFinished: true} : {})})});
    const learning = WaniKani.getLearningPreferences().then(
      (preferences) => ({preferences, failed: false}),
      () => ({preferences: null, failed: true}),
    );
    // A read a reset superseded answers nothing at all: the store it wrote to no longer exists, so
    // the hub would sit on its skeletons forever. Read again rather than take that for an answer.
    // The retry is bounded, so a store that is forgotten in a loop cannot keep this page spinning,
    // and it joins any read already in flight instead of starting a second run of requests.
    let stats = getWaniKaniStats();
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await loadWaniKaniStats(options.force === true);
      if (request !== this.hubRequest || this.state.step.kind !== "wkHome") return;
      // The snapshot is read back from the store, never taken from the promise's own answer: a read
      // this call joined — Home's card's, or another surface's — writes that same singleton, and
      // reading it here is what keeps a coalesced or racing read from losing its snapshot.
      stats = getWaniKaniStats();
      if (isTerminalWaniKaniStatus(stats.status)) break;
    }
    this.settleWaniKaniHub(request, await learning);
  }

  /**
   * The app came back to an open hub: the queues may have moved on while it was away, so the counts
   * and the local daily budget are both read again — the same pair the hub opens with. Nothing is
   * bumped here: a background read must not supersede a queue the reader is already starting, and
   * its answer is dropped the moment an account reset or a newer hub entry takes over.
   */
  private async refreshWaniKaniHub(): Promise<void> {
    const request = this.hubRequest;
    this.set({step: this.hubStep({refreshing: true})});
    const learning = WaniKani.getLearningPreferences().then(
      (preferences) => ({preferences, failed: false}),
      () => ({preferences: null, failed: true}),
    );
    await loadWaniKaniStats(false);
    this.settleWaniKaniHub(request, await learning);
  }

  /**
   * Applies one read of the shared store, and the local daily budget beside it, to the hub. A read
   * whose request token has been superseded answers nothing at all, so an old answer can never
   * overwrite an account that has since been replaced, or a row the reader has since started.
   */
  private settleWaniKaniHub(
    request: number,
    learning: {preferences: WaniKaniLearningPreferences | null; failed: boolean},
  ): void {
    if (request !== this.hubRequest || this.state.step.kind !== "wkHome") return;
    this.set({step: this.hubStep({
      learningPreferences: learning.preferences,
      learningPreferencesFailed: learning.failed,
    })});
    // The budget read above gave a reset another window: take the store as it stands now, not as it
    // stood when the read finished, so an account forgotten mid-flight is not shown as current.
    const stats = getWaniKaniStats();
    // A store with no answer at all (a reset landed between the reads, or one is still on its way)
    // would leave the hub on its skeletons with no way out: state the sync failed so it offers a retry.
    if (stats.status === "idle" || stats.status === "loading") {
      this.set({step: this.hubStep({
        failed: true, refreshing: false, failure: {failure: "SERVER", retryAfterSeconds: null},
      })});
      return;
    }
    switch (stats.status) {
      case "ready": {
        const snapshot = stats.snapshot;
        if (snapshot == null) return;
        const failure = stats.failure;
        // A message and no named failure means the bridge itself rejected the call rather than
        // WaniKani answering: that is the sync the hub offers a retry for.
        const rejected = stats.message != null;
        const forecast = snapshot.forecast;
        this.set({step: this.hubStep({
          summary: {
            dueNow: snapshot.dueNow,
            lessonsNow: snapshot.lessonsNow,
            nextReviewAt: snapshot.nextReviewAt,
            nextHourCount: forecast.length > 1 ? forecast[1].count : null,
            fetchedAt: snapshot.fetchedAt,
            sync: !stats.stale && failure == null ? "fresh" : failure === "OFFLINE" ? "offline" : "failed",
            retryAfterSeconds: stats.retryAfterSeconds,
          },
          connection: "connected", refreshing: false, failed: rejected,
          ...(!stats.stale && failure == null ? {sessionFinished: false} : {}),
          failure: rejected
            ? {failure: "SERVER", retryAfterSeconds: null}
            : failure == null ? null : {failure, retryAfterSeconds: stats.retryAfterSeconds},
        })});
        return;
      }
      case "refreshing":
        // A read another surface started is still on its way: what is confirmed stays up. With
        // nothing confirmed — a first connect whose read was superseded — say the sync failed
        // rather than leave the skeleton with no way out.
        if (this.lastHub?.summary == null) {
          this.set({step: this.hubStep({
            failed: true, refreshing: false, failure: {failure: "SERVER", retryAfterSeconds: null},
          })});
          return;
        }
        this.set({step: this.hubStep({refreshing: true})});
        return;
      case "needsToken":
        this.accountUnavailable("disconnected");
        return;
      case "authRejected":
        this.accountUnavailable("expired");
        return;
      case "failed":
        this.set({step: this.hubStep({
          failed: true, refreshing: false,
          summary: this.lastHub?.summary == null ? null : {...this.lastHub.summary,
            sync: stats.failure === "OFFLINE" ? "offline" : "failed"},
          failure: {failure: stats.failure ?? "SERVER", retryAfterSeconds: stats.retryAfterSeconds},
        })});
        return;
    }
  }

  /**
   * Retry a summary read; queue-specific failures remain until that queue is read successfully. The
   * reader's own tap is what this is, so it forces the read past the cache and shows as the sync
   * row's own pending action — never as the whole hub's busy state, which would lock the other row.
   */
  retryWaniKaniSync() {
    if (this.state.busy || this.pendingAction != null) return;
    const wait = this.rateLimitWaitSeconds();
    if (wait != null) {
      this.showRateLimitWait(wait);
      return;
    }
    const token = this.beginPending("sync");
    void (async () => {
      try {
        await this.enterWaniKaniHome({force: true});
      } catch (error) {
        // `enterWaniKaniHome` advances the hub generation itself. The pending token, rather than
        // that generation, owns this explicit retry and prevents a retired retry from repainting.
        if (token !== this.pendingToken || this.state.step.kind !== "wkHome") return;
        const summary = this.state.step.summary;
        this.set({step: this.hubStep({failed: true, refreshing: false,
          summary: summary == null ? null : {...summary, sync: "failed"},
          failure: {failure: waniKaniFailureOf(error), retryAfterSeconds: null}})});
      } finally {
        this.endPending(token);
      }
    })();
  }

  /** Start a fresh queue, or resume the exact saved answer and submission state. */
  startReviews() {
    if (this.state.busy || this.pendingAction != null) return;
    if (this.pausedReview != null) {
      this.set({step: {kind: "wkReview", session: this.pausedReview}});
      return;
    }
    // The queue read is the validation, but it is not made inside a rate-limit wait: WaniKani asked
    // for the pause and firing the read would spend the budget the pause protects. The row says the
    // wait instead, and the tap stays available for when it is over.
    const wait = this.rateLimitWaitSeconds();
    if (wait != null) {
      this.set({step: this.hubStep({reviewFailure: {failure: "RATE_LIMITED", retryAfterSeconds: wait}})});
      return;
    }
    const token = this.beginPending("reviews");
    const request = this.hubRequest;
    void (async () => {
      try {
        await this.enterWaniKaniReview();
      } catch (error) {
        if (request !== this.hubRequest) return;
        this.set({step: this.hubStep({reviewFailure: {
          failure: waniKaniFailureOf(error), retryAfterSeconds: null,
        }})});
      } finally {
        this.endPending(token);
      }
    })();
  }

  /** Reads WaniKani's review queue and enters it. */
  private async enterWaniKaniReview(): Promise<void> {
    const request = this.hubRequest;
    const queue = await WaniKani.reviewQueue();
    if (request !== this.hubRequest) return;
    if (queue.status === "available" || queue.status === "empty") {
      this.lastHub = this.hubStep({reviewFailure: null});
      if (this.state.step.kind === "wkHome") this.set({step: this.lastHub});
    }
    switch (queue.status) {
      case "available":
        if (queue.items.length === 0) {
          await this.finishWaniKani();
          return;
        }
        this.pausedReview = startWaniKaniSession(queue.items);
        this.set({ step: { kind: "wkReview", session: this.pausedReview } });
        return;
      case "empty":
        await this.finishWaniKani();
        return;
      case "needsToken":
        this.accountUnavailableInSession("disconnected");
        return;
      case "authRejected":
        this.accountUnavailableInSession("expired");
        return;
      case "failed":
        if (queue.failure === "RATE_LIMITED") this.rememberQueueRateLimit(queue.retryAfterSeconds);
        this.set({step: this.hubStep({reviewFailure: {
          failure: queue.failure, retryAfterSeconds: queue.retryAfterSeconds ?? null,
        }})});
    }
  }

  /**
   * The review queue is finished: back to the hub, with the counts asked of WaniKani again. The read
   * is forced because the queue just changed: a cached answer would show the count the session has
   * already worked off.
   */
  private async finishWaniKani(sessionFinished = false): Promise<void> {
    this.pausedReview = null;
    await this.enterWaniKaniHome({force: true, sessionFinished});
  }

  /**
   * Re-read the local daily budget before starting or resuming any lesson batch. Like the reviews
   * tap, the row action is the reader's own: it shows as that row's pending action, is not made
   * inside a rate-limit wait, and never takes the whole hub's busy state.
   */
  startLessons() {
    if (this.state.busy || this.pendingAction != null) return;
    const token = this.beginPending("lessons");
    const request = this.hubRequest;
    void (async () => {
      try {
        // The daily budget is local account state, not a queue read: it remains available while a
        // queue rate-limit is active and can still prevent an unnecessary queue request.
        const preferences = await WaniKani.getLearningPreferences();
        if (request !== this.hubRequest) return;
        this.set({step: this.hubStep({learningPreferences: preferences,
          learningPreferencesFailed: false, lessonFailure: null})});
        // The goal always exists; what the day already had is what the batch cannot spend.
        const budget = Math.max(0, preferences.dailyLimit - preferences.learnedToday);
        if (budget === 0) return;
        if (this.pausedLesson != null) {
          this.set({step: {kind: "wkLesson", session: this.pausedLesson}});
          return;
        }
        const wait = this.rateLimitWaitSeconds();
        if (wait != null) {
          this.set({step: this.hubStep({lessonFailure: {
            failure: "RATE_LIMITED", retryAfterSeconds: wait,
          }})});
          return;
        }
        await this.enterWaniKaniLessons(Math.min(LESSON_BATCH_SIZE, budget));
      } catch (error) {
        if (request !== this.hubRequest) return;
        this.set({step: this.hubStep({lessonFailure: {
          failure: waniKaniFailureOf(error), retryAfterSeconds: null,
        }})});
      } finally {
        this.endPending(token);
      }
    })();
  }

  /**
   * Reads the lesson queue and enters a batch of it. The batch is [LESSON_BATCH_SIZE] of what is
   * waiting - WaniKani's own default lesson batch - and the rest stay queued for the next one.
   */
  private async enterWaniKaniLessons(batchSize = LESSON_BATCH_SIZE): Promise<void> {
    const request = this.hubRequest;
    const queue = await WaniKani.lessonsQueue();
    if (request !== this.hubRequest) return;
    if (queue.status === "available" || queue.status === "empty") {
      this.lastHub = this.hubStep({lessonFailure: null, lessonsLocked: false});
      if (this.state.step.kind === "wkHome") this.set({step: this.lastHub});
    }
    switch (queue.status) {
      case "available": {
        if (queue.items.length === 0) {
          await this.enterWaniKaniHome({force: true});
          return;
        }
        const batch = queue.items.slice(0, batchSize);
        const session = startWaniKaniLessonSession(batch, queue.items.length - batch.length);
        // Held from the first moment, so leaving the page mid-batch keeps the reader's place.
        this.pausedLesson = session;
        this.set({ step: { kind: "wkLesson", session } });
        return;
      }
      case "empty":
        this.pausedLesson = null;
        await this.enterWaniKaniHome({force: true});
        return;
      case "locked": {
        // The summary counted lessons WaniKani has but this account cannot start; say so rather than
        // showing a zero the reader would read as "nothing to learn".
        this.set({step: this.hubStep({lessonsLocked: true, lessonFailure: null})});
        return;
      }
      case "needsToken":
        this.accountUnavailableInSession("disconnected");
        return;
      case "authRejected":
        this.accountUnavailableInSession("expired");
        return;
      case "failed":
        if (queue.failure === "RATE_LIMITED") this.rememberQueueRateLimit(queue.retryAfterSeconds);
        this.set({step: this.hubStep({lessonFailure: {
          failure: queue.failure, retryAfterSeconds: queue.retryAfterSeconds ?? null,
        }})});
    }
  }

  /**
   * Connects with [token] and, when it works, lands on the hub. The outcome is returned for the
   * connection screen to say — a rejected token, an unreachable WaniKani — while a working one
   * replaces the screen with the hub, where the reader chooses reviews or new cards.
   */
  async connectWaniKani(token: string): Promise<WaniKaniConnectOutcome> {
    this.setBusy(true);
    try {
      const result = await WaniKani.connect(token);
      if (result.status === "invalidToken") {
        return { status: "invalidToken" };
      }
      // The token may belong to a different account, so everything the shared store still holds —
      // numbers, a rate-limit wait, a read in flight — is dropped before the hub reads it. The
      // reset has to happen here, before the read: the connection screen used to do it after, which
      // let the hub read the account that had just been replaced.
      this.resetWaniKaniAccount();
      resetWaniKaniStats();
      await this.enterWaniKaniHome({force: true});
      return { status: "connected" };
    } catch (error) {
      return { status: "failed", failure: waniKaniFailureOf(error) };
    } finally {
      this.setBusy(false);
    }
  }

  /** One keystroke in the review's answer field. */
  wkEntry(text: string) {
    const step = this.state.step;
    if (step.kind !== "wkReview" || step.session.phase === "submitting") return;
    this.set({ step: { kind: "wkReview", session: { ...step.session, entry: text } } });
  }

  /**
   * Checks the answer on screen. A correct one finishes the part — and, when the subject asks for a
   * reading too, moves the reader to it; a wrong one is counted against the part, shows the right
   * answer, and asks for it to be typed before the subject moves on, which is WaniKani's own rule.
   *
   * Nothing is submitted here: the review is sent when every part of the subject is finished, and
   * never because a part was merely typed.
   */
  wkCheck() {
    const step = this.state.step;
    if (step.kind !== "wkReview") return;
    const session = step.session;
    if (session.phase !== "answering" || session.entry.trim() === "") return;
    const item = session.items[session.index];
    if (item == null) return;

    this.setBusy(true);
    void (async () => {
      try {
        const typed = session.entry.trim();
        let correct: boolean;
        if (session.part === "reading") {
          // A reading is compared in kana: what the reader typed is handed to the converter, so a
          // romaji answer is judged as the reading it spells rather than as its letters.
          const kana = await WaniKani.readingAnswer(romajiToHiragana(typed, true));
          correct = item.readings.includes(kana);
        } else {
          const answer = normalizeMeaning(typed);
          correct = item.meanings.some(
            (meaning) => normalizeMeaning(meaning) === answer
          );
        }
        // The step may have been left while the answer was being read.
        const current = this.state.step;
        if (current.kind !== "wkReview") return;
        const live = current.session;
        if (!correct) {
          const counted =
            session.part === "reading"
              ? { incorrectReading: live.incorrectReading + 1 }
              : { incorrectMeaning: live.incorrectMeaning + 1 };
          this.set({
            step: {
              kind: "wkReview",
              session: {
                ...live,
                ...counted,
                // The wrong attempt stays on screen, in red, behind its own `reviewed` stop. The card
                // does not change and nothing is submitted: `wkContinue` only clears the field for the
                // retry this rule requires. The feedback names no correct answer - that stays behind
                // the reader's own Show answer disclosure.
                entry: live.entry,
                retrying: true,
                phase: "reviewed",
                feedback: { tone: "wrong", text: "Incorrect" },
              },
            },
          });
          return;
        }
        // A right answer needs no stop of its own: it moves straight on, exactly as WaniKani's own
        // flow does - to the reading when one is still to come, or to the review's one submission.
        if (session.part === "meaning" && item.readings.length > 0) {
          this.set({
            step: {
              kind: "wkReview",
              session: {
                ...live,
                part: "reading",
                entry: "",
                retrying: false,
                feedback: null,
                doneParts: live.doneParts + 1,
              },
            },
          });
          return;
        }
        this.submitWaniKani();
      } finally {
        this.setBusy(false);
      }
    })();
  }

  /**
   * Continue after a wrong answer. WaniKani makes the reader type the right answer before the subject
   * moves on, so this keeps the same card and prompt: it only clears the field for the retry, leaving
   * the recorded wrong count and the answer hint in place. It never advances the queue - the next
   * check decides that.
   */
  wkContinue() {
    const step = this.state.step;
    if (step.kind !== "wkReview") return;
    const session = step.session;
    if (session.phase !== "reviewed" || !session.retrying) return;
    this.set({
      step: { kind: "wkReview", session: { ...session, phase: "answering", entry: "" } },
    });
  }

  /**
   * Installs a settled review only when its exact pending object is still retained. A hub visit may
   * change while a write is in flight; the retained session, not that read generation, owns the
   * write's result. Hidden Anki stays untouched, while an open WaniKani hub republishes its resume row.
   */
  private replacePausedReview(previous: WaniKaniSession, next: WaniKaniSession): boolean {
    if (this.pausedReview !== previous) return false;
    this.pausedReview = next;
    const current = this.state.step;
    if (current.kind === "wkReview" && current.session === previous) {
      this.set({step: {kind: "wkReview", session: next}});
    } else if (current.kind === "wkHome") {
      this.set({step: this.hubStep()});
    }
    return true;
  }

  /**
   * Sends the subject's completed review. The pending object is captured verbatim: it prevents a
   * return to the hub from discarding an accepted write, without ever replaying that write.
   */
  private submitWaniKani() {
    const step = this.state.step;
    if (step.kind !== "wkReview") return;
    const session = step.session;
    if (session.phase === "submitting") return;
    const item = session.items[session.index];
    if (item == null) return;
    const pending: WaniKaniSession = {
      ...session,
      phase: "submitting",
      feedback: {tone: "right", text: "✓ Correct"},
      failure: null,
      authRejected: false,
    };
    this.set({step: {kind: "wkReview", session: pending}});
    void (async () => {
      try {
        const result = await WaniKani.submitReview(
          item.subjectId,
          session.incorrectMeaning,
          session.incorrectReading
        );
        if (this.pausedReview !== pending) return;
        if (result.status === "accepted") {
          invalidateWaniKaniStats();
          this.advanceWaniKani(pending);
          return;
        }
        const rejected = result.status === "authRejected" || result.status === "needsToken";
        this.replacePausedReview(pending, {
          ...pending,
          phase: "submitFailed",
          failure: rejected ? null : result.status === "failed" ? result.failure : null,
          authRejected: rejected,
        });
      } catch (error) {
        if (this.pausedReview !== pending) return;
        this.replacePausedReview(pending, {
          ...pending,
          phase: "submitFailed",
          failure: waniKaniFailureOf(error),
          authRejected: false,
        });
      }
    })();
  }

  /** Sends the failed submission again, from the state the reader left it in. */
  wkRetrySubmit() {
    const step = this.state.step;
    if (step.kind !== "wkReview") return;
    if (step.session.phase !== "submitFailed" || step.session.authRejected) return;
    this.set({
      step: { kind: "wkReview", session: { ...step.session, phase: "answering" } },
    });
    this.submitWaniKani();
  }

  /** Settles an accepted review into its retained session, navigating only when it is still visible. */
  private advanceWaniKani(session: WaniKaniSession) {
    if (this.pausedReview !== session) return;
    const item = session.items[session.index];
    // One part was just finished. For a subject with a reading, its meaning was already counted when
    // the screen moved to the reading, so this adds the part that was on screen, not the whole
    // subject - which is what keeps the progress line from passing its end.
    const done = session.doneParts + (item == null ? 0 : 1);
    const next = session.index + 1;
    if (next >= session.items.length) {
      const visible =
        this.state.step.kind === "wkReview" && this.state.step.session === session;
      this.pausedReview = null;
      if (visible) {
        void this.finishWaniKani(true);
      } else if (this.state.step.kind === "wkHome") {
        this.set({step: this.hubStep()});
      }
      return;
    }
    this.replacePausedReview(session, {
      ...session,
      index: next,
      part: "meaning",
      entry: "",
      incorrectMeaning: 0,
      incorrectReading: 0,
      feedback: null,
      retrying: false,
      doneParts: done,
      phase: "answering",
      failure: null,
      authRejected: false,
    });
  }

  /** Moves from one teaching card to the next; the last card starts the quiz over the batch. */
  lessonNext() {
    const step = this.state.step;
    if (step.kind !== "wkLesson") return;
    const session = step.session;
    if (session.phase !== "teaching") return;
    const next = session.teachIndex + 1;
    if (next >= session.batch.length) {
      this.set({
        step: {
          kind: "wkLesson",
          session: {
            ...session,
            phase: "quizzing",
            quizIndex: 0,
            part: "meaning",
            entry: "",
            incorrectMeaning: 0,
            incorrectReading: 0,
            feedback: null,
            retrying: false,
            doneParts: session.batch.length,
          },
        },
      });
      return;
    }
    this.set({
      step: {
        kind: "wkLesson",
        session: { ...session, teachIndex: next, doneParts: next },
      },
    });
  }

  /** One keystroke in the lesson quiz's answer field. */
  lessonEntry(text: string) {
    const step = this.state.step;
    if (step.kind !== "wkLesson" || step.session.phase === "starting") return;
    this.set({ step: { kind: "wkLesson", session: { ...step.session, entry: text } } });
  }

  /**
   * Checks the lesson answer on screen. Judged exactly like a review - a reading through the same
   * converter, a meaning case- and spacing-insensitively - but a correct answer finishes the subject
   * with `PUT /assignments/<id>/start` rather than a review. A wrong one is counted, shows the answer,
   * and asks for it to be typed, the same rule WaniKani applies to reviews.
   */
  lessonCheck() {
    const step = this.state.step;
    if (step.kind !== "wkLesson") return;
    const session = step.session;
    if (session.phase !== "quizzing" || session.entry.trim() === "") return;
    const item = session.batch[session.quizIndex];
    if (item == null) return;

    this.setBusy(true);
    void (async () => {
      try {
        const typed = session.entry.trim();
        let correct: boolean;
        if (session.part === "reading") {
          const kana = await WaniKani.readingAnswer(romajiToHiragana(typed, true));
          correct = item.readings.includes(kana);
        } else {
          const answer = normalizeMeaning(typed);
          correct = item.meanings.some((meaning) => normalizeMeaning(meaning) === answer);
        }
        const current = this.state.step;
        if (current.kind !== "wkLesson") return;
        const live = current.session;
        if (!correct) {
          const counted =
            session.part === "reading"
              ? { incorrectReading: live.incorrectReading + 1 }
              : { incorrectMeaning: live.incorrectMeaning + 1 };
          this.set({
            step: {
              kind: "wkLesson",
              session: {
                ...live,
                ...counted,
                entry: "",
                retrying: true,
                feedback: { tone: "wrong", text: "Incorrect" },
              },
            },
          });
          return;
        }
        if (session.part === "meaning" && item.readings.length > 0) {
          this.set({
            step: {
              kind: "wkLesson",
              session: {
                ...live,
                part: "reading",
                entry: "",
                retrying: false,
                feedback: null,
                doneParts: live.doneParts + 1,
              },
            },
          });
          return;
        }
        this.startWaniKaniLesson();
      } finally {
        this.setBusy(false);
      }
    })();
  }

  /**
   * Installs a settled lesson only if its exact pending object is still retained. A hidden Anki
   * picker never receives a WaniKani write result, while the WaniKani hub immediately sees the
   * updated paused batch.
   */
  private replacePausedLesson(previous: WaniKaniLessonSession, next: WaniKaniLessonSession): boolean {
    if (this.pausedLesson !== previous) return false;
    this.pausedLesson = next;
    const current = this.state.step;
    if (current.kind === "wkLesson" && current.session === previous) {
      this.set({step: {kind: "wkLesson", session: next}});
    } else if (current.kind === "wkHome") {
      this.set({step: this.hubStep()});
    }
    return true;
  }

  /** Starts the quiz-complete subject and settles its result into the session that issued it. */
  private startWaniKaniLesson() {
    const step = this.state.step;
    if (step.kind !== "wkLesson") return;
    const session = step.session;
    if (session.phase === "starting") return;
    const item = session.batch[session.quizIndex];
    if (item == null) return;
    const pending: WaniKaniLessonSession = {
      ...session,
      phase: "starting",
      feedback: {tone: "right", text: "✓ Correct"},
      failure: null,
      authRejected: false,
    };
    this.set({step: {kind: "wkLesson", session: pending}});
    void (async () => {
      try {
        const result = await WaniKani.startLesson(item.assignmentId);
        if (this.pausedLesson !== pending) return;
        if (result.status === "started") {
          invalidateWaniKaniStats();
          this.advanceLesson(pending);
          return;
        }
        if (result.status === "dailyLimitReached") {
          const paused = {...pending, phase: "quizzing" as const};
          this.pausedLesson = paused;
          const current = this.state.step;
          if (
            (current.kind === "wkLesson" && current.session === pending) ||
            current.kind === "wkHome"
          ) {
            this.set({step: this.hubStep({learningPreferences: {
              dailyLimit: result.dailyLimit, learnedToday: result.learnedToday,
            }})});
          }
          return;
        }
        const rejected = result.status === "authRejected" || result.status === "needsToken";
        this.replacePausedLesson(pending, {
          ...pending,
          phase: "startFailed",
          failure: rejected ? null : result.status === "failed" ? result.failure : null,
          authRejected: rejected,
        });
      } catch (error) {
        if (this.pausedLesson !== pending) return;
        this.replacePausedLesson(pending, {
          ...pending,
          phase: "startFailed",
          failure: waniKaniFailureOf(error),
          authRejected: false,
        });
      }
    })();
  }

  /** Sends the failed lesson start again, from the state the reader left it in. */
  lessonRetrySubmit() {
    const step = this.state.step;
    if (step.kind !== "wkLesson") return;
    if (step.session.phase !== "startFailed" || step.session.authRejected) return;
    this.set({ step: { kind: "wkLesson", session: { ...step.session, phase: "quizzing" } } });
    this.startWaniKaniLesson();
  }

  /** Settles an accepted lesson into its retained batch, navigating only when it is still visible. */
  private advanceLesson(session: WaniKaniLessonSession) {
    if (this.pausedLesson !== session) return;
    const item = session.batch[session.quizIndex];
    // As in the review: the part on screen was just finished, and for a subject with a reading its
    // meaning was counted when the screen moved to it.
    const done = session.doneParts + (item == null ? 0 : 1);
    const next = session.quizIndex + 1;
    if (next >= session.batch.length) {
      const visible =
        this.state.step.kind === "wkLesson" && this.state.step.session === session;
      this.pausedLesson = null;
      if (visible) {
        void this.enterWaniKaniHome({force: true, sessionFinished: true});
      } else if (this.state.step.kind === "wkHome") {
        this.set({step: this.hubStep()});
      }
      return;
    }
    this.replacePausedLesson(session, {
      ...session,
      quizIndex: next,
      part: "meaning",
      entry: "",
      incorrectMeaning: 0,
      incorrectReading: 0,
      feedback: null,
      retrying: false,
      doneParts: done,
      phase: "quizzing",
      failure: null,
      authRejected: false,
    });
  }

  /** The read that failed, made again from the step the reader is on. */
  retryWaniKani() {
    const step = this.state.step;
    if (step.kind !== "wkFailed") return;
    const target = step.retry;
    this.ifIdle(() => {
      this.setBusy(true);
      void (async () => {
        try {
          if (target === "reviews") await this.enterWaniKaniReview();
          else if (target === "lessons") await this.enterWaniKaniLessons();
          else await this.enterWaniKaniHome({force: true});
        } catch (error) {
          this.set({
            step: {
              kind: "wkFailed",
              failure: waniKaniFailureOf(error),
              retryAfterSeconds: null,
              retry: target,
            },
          });
        } finally {
          this.setBusy(false);
        }
      })();
    });
  }

  /** Starts reviewing `deck`. The deck's own due count is what the session's progress is measured
   *  against, read at the moment it starts and never re-read under the reader's feet. */
  startDeck(deck: AnkiDeckResult) {
    if (deck.newCount + deck.learnCount + deck.reviewCount <= 0) return;
    this.ifIdle(() => {
      this.set({ deck });
      this.nextCard();
    });
  }

  /** Leaves this deck and shows the picker again. */
  changeDeck() {
    this.ifIdle(() => {
      this.set({ deck: null });
      this.openDecks();
    });
  }

  /** Retries what failed: the deck list when no deck is chosen, the next card otherwise. */
  retry() {
    this.ifIdle(() => {
      if (this.state.deck == null) this.openDecks();
      else this.nextCard();
    });
  }

  /** Reveals the answer of the card on screen. */
  reveal() {
    const { step } = this.state;
    if (step.kind !== "session") return;
    this.set({ step: { ...step, revealed: true } });
  }

  /** Sends one rating for `session`'s card. Guarded: a card is answered once, never twice. */
  grade(session: Extract<ReviewStep, { kind: "session" }>, ease: number) {
    if (this.state.busy) return;
    this.setBusy(true);
    void (async () => {
      const taken = Date.now() - session.startedAt;
      try {
        await Anki.grade(session.token, ease, taken);
        // Whatever the provider answered, the card is off the deck it came from, so the counts
        // Home's card and the picker share are read again — one read, joined with anything already
        // in flight — and the card behind this page is right by the time the reader is back on it.
        void loadAnkiDecks();
        // Submitted: AnkiDroid took the answer, and the next read only serves cards that are
        // actually due. NotFound/Ignored: the card is gone, or nothing was sent at all — the
        // queue is read as it is either way. All three end this card, and busy stays set
        // through the hand-off, so two taps cannot answer two.
        this.nextCard(undefined, session.answered + 1, session.total);
      } catch (error) {
        // The answer may have landed anyway, and the provider cannot undo one, so the only way
        // on is a fresh read from the scheduler.
        const { failure, detail } = failureOf(error);
        this.set({
          step: { kind: "failed", failure, detail, afterGrade: true },
        });
        this.setBusy(false);
      }
    })();
  }

  /** Toggles the inline details: the open kanji closes them, another one swaps them, and the
   *  radical under them goes back with either. */
  toggleKanji(character: string) {
    const open = this.state.expandedKanji !== character ? character : null;
    this.set({ expandedKanji: open, expandedRadical: null });
  }

  /** Toggles one radical inside the open details. */
  toggleRadical(id: number) {
    this.set({
      expandedRadical: this.state.expandedRadical !== id ? id : null,
    });
  }

  /** Closes the inline details from the panel's own control. */
  closeDetails() {
    this.set({ expandedKanji: null, expandedRadical: null });
  }

  /** The app went to the background, so the time it spends there is not answer time. */
  onPaused() {
    if (this.state.step.kind === "session") this.pausedAt = Date.now();
  }

  /**
   * The app came back: the card on screen may no longer be the one AnkiDroid would hand out —
   * it may have been answered in AnkiDroid meanwhile — so the queue is read again instead of
   * grading a stale card. Nothing is ever re-sent on its own, and the time spent away is
   * shifted out of the answered-in time.
   */
  onResumed() {
    const away = this.pausedAt != null
      ? Math.max(0, Date.now() - this.pausedAt)
      : null;
    this.pausedAt = null;
    if (this.state.busy) return;
    const { step } = this.state;
    if (step.kind === "blocked" &&
        (step.failure === "NOT_INSTALLED" || step.failure === "NOT_SET_UP")) {
      // Installing or setting up AnkiDroid happens outside Gaten; recheck when the reader returns.
      this.openAnki();
      return;
    }
    if (step.kind === "decks") {
      this.openDecks();
      return;
    }
    if (step.kind === "wkHome") {
      // A queue-specific wait is not stored by the shared summary reader. Do not use foregrounding
      // to bypass it with a new stats request; the existing row failure continues to name the wait.
      if (this.rateLimitWaitSeconds() != null) return;
      // Both the counts and the local budget, as the hub opens with: the queues move on while the
      // app is away, and the budget is the reader's own day, which may have rolled over.
      void this.refreshWaniKaniHub();
      return;
    }
    if (step.kind !== "session") return;
    this.nextCard(
      { ...step, startedAt: step.startedAt + (away ?? 0) },
      step.answered,
      step.total
    );
  }

  /**
   * Asks AnkiDroid for the next due card in the chosen deck, fresh, and reads it. `keep` is the
   * card the reader is already on: when the scheduler hands out the same card again it keeps
   * its revealed side and its timer, so coming back from another app does not reset the
   * reader's place. A different card always starts from the front.
   *
   * `answered` and `total` are the session's own progress, carried across the hand-off: the deck's
   * due count when it started, and how many cards of it are done. A deck can hand back a card that
   * was rated Again, so the total is raised to meet the count rather than being left behind by it.
   */
  private nextCard(
    keep?: Extract<ReviewStep, { kind: "session" }>,
    answered = 0,
    total = 0
  ) {
    const deck = this.state.deck;
    if (deck == null) return;
    const sessionTotal = Math.max(
      total,
      answered + 1,
      deck.newCount + deck.learnCount + deck.reviewCount
    );
    this.setBusy(true);
    this.collapseDetails();
    void (async () => {
      try {
        const queue = await Anki.nextCard(deck.id);
        if (queue.kind === "empty") {
          this.set({ step: { kind: "done", deckName: deck.name } });
        } else {
          const [card, preferences] = await Promise.all([
            Anki.card(queue.noteId, queue.ord),
            Anki.getPreferences().catch(() => null),
          ]);
          const same =
            keep != null &&
            keep.card.noteId === card.noteId &&
            keep.card.ord === card.ord
              ? keep
              : null;
          this.set({
            step: {
              kind: "session",
              card: toReviewCard(card, preferences),
              token: queue.token,
              buttonLabels: queue.buttonLabels,
              mediaFiles: queue.mediaFiles,
              startedAt: same?.startedAt ?? Date.now(),
              revealed: same?.revealed === true,
              answered,
              total: sessionTotal,
            },
          });
        }
      } catch (error) {
        const { failure, detail } = failureOf(error);
        this.set({
          step: { kind: "failed", failure, detail, afterGrade: false },
        });
      } finally {
        this.setBusy(false);
      }
    })();
  }
}

function toReviewCard(
  card: AnkiCardResult,
  preferences: AnkiPreferencesResult | null
): ReviewCard {
  return {
    noteId: card.noteId,
    modelId: card.modelId,
    ord: card.ord,
    deckName: card.deckName,
    question: card.question,
    answer: card.answer,
    css: card.css,
    fieldNames: card.fieldNames,
    fields: card.fields,
    answerFields: answerFieldsFor(card, preferences),
    exampleSentence: exampleSentenceFor(card),
    typedAnswer: card.typedAnswer,
  };
}

/**
 * The sentence Gaten's own note type stored for the word. Card-only media and the note author's
 * `GRAYLINE_MARKER` separator are dropped; everything else is the reader's own text, read offline.
 */
function exampleSentenceFor(card: AnkiCardResult): string | null {
  const index = card.fieldNames.indexOf("Sentence");
  if (index === -1) return null;
  const raw = card.fields[index]
    ?.replace(/\[(?:sound|anki:play):[^\]]*\]/g, "")
    .replace(/GRAYLINE_MARKER/g, " ")
    .trim() ?? "";
  return raw === "" ? null : raw;
}

/** Applies a saved field map only to its exact model, or Gaten's exact current/legacy schemas. */
function answerFieldsFor(
  card: AnkiCardResult,
  preferences: AnkiPreferencesResult | null
): ReviewCard["answerFields"] {
  if (card.fieldNames.length !== card.fields.length) return null;

  let sources: string[] | null = null;
  if (
    preferences?.modelId === card.modelId &&
    preferences.validationError == null &&
    preferences.modelFields.length === card.fieldNames.length &&
    preferences.fieldSources.length === card.fieldNames.length &&
    preferences.modelFields.every((name, index) => name === card.fieldNames[index])
  ) {
    sources = preferences.fieldSources;
  } else if (
    preferences?.modelId == null &&
    (sameNames(card.fieldNames, ["Expression", "Reading", "Meaning"]) ||
      sameNames(card.fieldNames, ["Expression", "Reading", "Meaning", "Sentence"]))
  ) {
    sources = card.fieldNames.map((name) => name.toUpperCase());
  }
  if (sources == null) return null;

  const values: Record<string, string> = {
    EXPRESSION: "",
    READING: "",
    MEANING: "",
  };
  sources.forEach((source, index) => {
    if (values[source] === "" && card.fields[index]?.trim() !== "") {
      values[source] = card.fields[index] ?? "";
    }
  });
  const expression = values.EXPRESSION;
  const reading = values.READING;
  const meaning = values.MEANING;
  if (!expression.trim() || !reading.trim() || !meaning.trim()) return null;

  // Mapped user fields stay on Anki's own renderer if they carry markup or card-only media.
  const isGatenSchema = preferences?.modelId == null &&
    (card.fieldNames.length === 3 || card.fieldNames.length === 4);
  if (hasUnsupportedFieldMarkup(expression) || hasUnsupportedFieldMarkup(reading)) return null;
  if (isGatenSchema) {
    if (hasUnsupportedFieldMarkup(meaning, true)) return null;
  } else if (hasUnsupportedFieldMarkup(meaning)) {
    return null;
  }
  return {expression, reading, meaning};
}

function sameNames(actual: string[], expected: string[]): boolean {
  return actual.length === expected.length && actual.every((name, index) => name === expected[index]);
}

function hasUnsupportedFieldMarkup(value: string, allowGatenMeaning = false): boolean {
  const markup = allowGatenMeaning
    ? value.replace(/<\/?(?:br|small)\s*\/?>/gi, "")
    : value;
  return /<[^>]*>|\[(?:sound|anki:play):|\{\{c\d+::/i.test(markup);
}

/** The app's one review session state, outside any component. */
export const reviewStore = new ReviewStore();
