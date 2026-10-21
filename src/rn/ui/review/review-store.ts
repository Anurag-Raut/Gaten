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
import * as Anki from "../../native/GatenAnki";
import * as WaniKani from "../../native/GatenWaniKani";
import type {
  AnkiCardResult,
  AnkiDeckResult,
  AnkiPreferencesResult,
} from "../../native/GatenAnki";
import type {
  WaniKaniFailure,
  WaniKaniLessonItem,
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
  phase: "answering" | "submitting" | "submitFailed";
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
  /** Epoch milliseconds this snapshot was read, for the page's synced label. */
  fetchedAt: number;
  /** Fresh numbers, cached because the device is offline, or a refresh that failed. */
  sync: WaniKaniSync;
  /** Seconds WaniKani asked us to wait, when a rate limit caused the failure. */
  retryAfterSeconds: number | null;
}

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
  | { kind: "decks"; decks: AnkiDeckResult[]; configuredDeckName: string }
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
  | {
      kind: "wkHome";
      /** The last known queues; null before the first read has answered. */
      summary: WaniKaniSummary | null;
      /** True when the last read failed with no snapshot at all: the cards say so. */
      failed: boolean;
      /** True when WaniKani said the account's grant does not cover the lessons it has. */
      lessonsLocked: boolean;
      /** A lesson batch left part-way, for the card's in-progress state. */
      pausedLesson: WaniKaniLessonSession | null;
    }
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

  private collapseDetails() {
    this.set({ expandedKanji: null, expandedRadical: null });
  }

  /**
   * What entering the flow from Home's Anki card asks for: AnkiDroid's own status first, then its
   * deck list. A service that cannot be used at all — not installed, no permission, no collection —
   * is stated where the reader tapped, rather than as an empty deck list.
   *
   * A card session is the reader's own place and is left exactly as it was: the page they pushed is
   * the card they were on, so re-entering resumes it rather than reading the collection again.
   */
  openAnki() {
    if (this.state.step.kind === "session") return;
    this.ifIdle(() => {
      // Back to the picker, whatever step was left behind: the deck that was being studied is not
      // the one this entry chose.
      this.set({ deck: null, step: { kind: "opening", source: "anki" } });
      this.setBusy(true);
      void (async () => {
        let blocked: ReviewStep | null = null;
        try {
          const status = await Anki.status();
          if (status === "notInstalled") {
            blocked = { kind: "blocked", failure: "NOT_INSTALLED" };
          } else if (status === "needsPermission") {
            blocked = { kind: "blocked", failure: "PERMISSION" };
          }
        } catch (error) {
          blocked = { kind: "blocked", failure: failureOf(error).failure };
        }
        this.setBusy(false);
        // The read of the deck list is its own busy step: it takes over from here, so a status that
        // can be used goes straight into the picker with no second tap.
        if (blocked == null) this.openDecks();
        else this.set({ step: blocked });
      })();
    });
  }

  /** Reads the deck list, fresh. */
  openDecks() {
    if (this.state.busy) return;
    // Busy is set before the work starts, so two taps in one frame cannot start two reads.
    this.setBusy(true);
    this.collapseDetails();
    void (async () => {
      try {
        // The deck Gaten exports to — the picker's default selection — is read with the list, not
        // after it: the picker's first frame is then already the right one, and a changed export
        // deck still preselects because this read happens on every entry.
        const [decks, configuredDeckName] = await Promise.all([
          Anki.decks(),
          Anki.getConfiguredDeckName().catch(() => ""),
        ]);
        this.set({ step: { kind: "decks", decks, configuredDeckName } });
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

  /** The permission dialog came back refused, so review cannot run yet. */
  permissionDenied() {
    this.set({ step: { kind: "blocked", failure: "PERMISSION" } });
  }

  /**
   * What entering the flow from Home's WaniKani card asks for: the stored token first, then the real
   * queue. A reader with no token gets the connection screen; a connected one goes straight into the
   * review, with no second start step, and a queue with nothing due is the caught-up state rather
   * than an empty review.
   *
   * A review already in progress is the reader's own place and is left exactly as it was.
   */
  openWaniKani() {
    // Entry always lands on the hub, so a batch left part-way is offered again rather than resumed
    // unseen: the reader asked for the page, not for the quiz they walked away from.
    if (this.state.step.kind === "wkLesson") this.pausedLesson = this.state.step.session;
    this.ifIdle(() => {
      this.set({ deck: null, step: { kind: "opening", source: "waniKani" } });
      this.setBusy(true);
      void (async () => {
        try {
          const configured = await WaniKani.isConfigured();
          if (!configured) {
            this.set({ step: { kind: "wkConnect", rejected: false } });
            return;
          }
          await this.enterWaniKaniHome();
        } catch (error) {
          this.set({
            step: {
              kind: "wkFailed",
              failure: waniKaniFailureOf(error),
              retryAfterSeconds: null,
              retry: "home",
            },
          });
        } finally {
          this.setBusy(false);
        }
      })();
    });
  }

  /** The hub's step, with [patch] applied: a read updates one thing without dropping the rest. */
  private hubStep(patch: Partial<Extract<ReviewStep, {kind: "wkHome"}>> = {}) {
    const step = this.state.step.kind === "wkHome" ? this.state.step : null;
    return {
      kind: "wkHome" as const,
      summary: step?.summary ?? null,
      failed: step?.failed ?? false,
      lessonsLocked: step?.lessonsLocked ?? false,
      pausedLesson: this.pausedLesson,
      ...patch,
    };
  }

  /**
   * Reads WaniKani's own numbers and lands on the hub, asking past the stats cache every time. One
   * `GET /summary`, already part of the stats read, carries both counts, the next review time and the
   * next hour's arrivals; the read is always forced because the page has to be honest about when its
   * numbers were last verified - a snapshot answered from the disk without a request could not say
   * whether the device is even online, and the "Cached" state would never appear.
   *
   * A cached snapshot that could not be refreshed is kept and labelled: offline it says so and offers
   * no session, and a failed refresh keeps the last known counts with a retry. Only a read with
   * nothing at all behind it marks the hub failed.
   */
  private async enterWaniKaniHome(): Promise<void> {
    this.set({ step: this.hubStep({ summary: null, failed: false }) });
    const result = await WaniKani.refreshStats();
    // The step may have been left while the read was in flight.
    if (this.state.step.kind !== "wkHome") return;
    switch (result.status) {
      case "available": {
        const forecast = result.snapshot.forecast;
        const failure = result.failure ?? null;
        this.set({
          step: this.hubStep({
            summary: {
              dueNow: result.snapshot.dueNow,
              lessonsNow: result.snapshot.lessonsNow,
              nextReviewAt: result.snapshot.nextReviewAt,
              nextHourCount: forecast.length > 1 && forecast[1].count > 0 ? forecast[1].count : null,
              fetchedAt: result.snapshot.fetchedAt,
              sync:
                failure == null
                  ? "fresh"
                  : failure === "OFFLINE" || failure === "TIMEOUT"
                    ? "offline"
                    : "failed",
              retryAfterSeconds: result.retryAfterSeconds ?? null,
            },
            failed: false,
          }),
        });
        return;
      }
      case "needsToken":
        this.set({ step: { kind: "wkConnect", rejected: false } });
        return;
      case "authRejected":
        this.set({ step: { kind: "wkConnect", rejected: true } });
        return;
      case "failed":
        this.set({ step: this.hubStep({ failed: true }) });
        return;
    }
  }

  /** Asks WaniKani for the queues again, past the cache - the hub's Retry sync action. */
  retryWaniKaniSync() {
    this.ifIdle(() => {
      this.setBusy(true);
      void (async () => {
        try {
          await this.enterWaniKaniHome();
        } catch (error) {
          this.set({ step: this.hubStep({ failed: true }) });
        } finally {
          this.setBusy(false);
        }
      })();
    });
  }

  /** From the hub: reads the review queue and enters it. */
  startReviews() {
    this.ifIdle(() => {
      this.setBusy(true);
      void (async () => {
        try {
          await this.enterWaniKaniReview();
        } catch (error) {
          this.set({
            step: {
              kind: "wkFailed",
              failure: waniKaniFailureOf(error),
              retryAfterSeconds: null,
              retry: "reviews",
            },
          });
        } finally {
          this.setBusy(false);
        }
      })();
    });
  }

  /** Reads WaniKani's review queue and enters it. */
  private async enterWaniKaniReview(): Promise<void> {
    const queue = await WaniKani.reviewQueue();
    switch (queue.status) {
      case "available":
        if (queue.items.length === 0) {
          await this.finishWaniKani();
          return;
        }
        this.set({ step: { kind: "wkReview", session: startWaniKaniSession(queue.items) } });
        return;
      case "empty":
        await this.finishWaniKani();
        return;
      case "needsToken":
        this.set({ step: { kind: "wkConnect", rejected: false } });
        return;
      case "authRejected":
        this.set({ step: { kind: "wkConnect", rejected: true } });
        return;
      case "failed":
        this.set({
          step: {
            kind: "wkFailed",
            failure: queue.failure,
            retryAfterSeconds: queue.retryAfterSeconds ?? null,
            retry: "reviews",
          },
        });
    }
  }

  /** The review queue is finished: back to the hub, with the counts asked of WaniKani again. */
  private async finishWaniKani(): Promise<void> {
    await this.enterWaniKaniHome();
  }

  /** From the hub: resumes a batch left part-way, or reads the lesson queue and enters a new one. */
  startLessons() {
    this.ifIdle(() => {
      if (this.pausedLesson != null) {
        this.set({ step: { kind: "wkLesson", session: this.pausedLesson } });
        return;
      }
      this.setBusy(true);
      void (async () => {
        try {
          await this.enterWaniKaniLessons();
        } catch (error) {
          this.set({
            step: {
              kind: "wkFailed",
              failure: waniKaniFailureOf(error),
              retryAfterSeconds: null,
              retry: "lessons",
            },
          });
        } finally {
          this.setBusy(false);
        }
      })();
    });
  }

  /**
   * Reads the lesson queue and enters a batch of it. The batch is [LESSON_BATCH_SIZE] of what is
   * waiting - WaniKani's own default lesson batch - and the rest stay queued for the next one.
   */
  private async enterWaniKaniLessons(): Promise<void> {
    const queue = await WaniKani.lessonsQueue();
    switch (queue.status) {
      case "available": {
        if (queue.items.length === 0) {
          await this.enterWaniKaniHome();
          return;
        }
        const batch = queue.items.slice(0, LESSON_BATCH_SIZE);
        const session = startWaniKaniLessonSession(batch, queue.items.length - batch.length);
        // Held from the first moment, so leaving the page mid-batch keeps the reader's place.
        this.pausedLesson = session;
        this.set({ step: { kind: "wkLesson", session } });
        return;
      }
      case "empty":
        this.pausedLesson = null;
        await this.enterWaniKaniHome();
        return;
      case "locked": {
        // The summary counted lessons WaniKani has but this account cannot start; say so rather than
        // showing a zero the reader would read as "nothing to learn".
        const hub = this.hubStep({ lessonsLocked: true });
        this.set({
          step: {
            ...hub,
            summary: hub.summary == null ? null : {...hub.summary, lessonsNow: 0},
          },
        });
        return;
      }
      case "needsToken":
        this.set({ step: { kind: "wkConnect", rejected: false } });
        return;
      case "authRejected":
        this.set({ step: { kind: "wkConnect", rejected: true } });
        return;
      case "failed":
        this.set({
          step: {
            kind: "wkFailed",
            failure: queue.failure,
            retryAfterSeconds: queue.retryAfterSeconds ?? null,
            retry: "lessons",
          },
        });
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
      await this.enterWaniKaniHome();
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
    if (step.kind !== "wkReview") return;
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
          const kana = await WaniKani.readingAnswer(typed);
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
          const answer =
            session.part === "reading"
              ? item.primaryReading ?? ""
              : item.primaryMeaning;
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
                entry: "",
                retrying: true,
                feedback: { tone: "wrong", text: `Incorrect — the answer is ${answer}` },
              },
            },
          });
          return;
        }
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
   * Sends the subject's completed review. The counters are the ones WaniKani asks for, taken from the
   * session as they were counted; a subject that was answered wrongly is still submitted, because that
   * is how WaniKani learns the answer was wrong.
   */
  private submitWaniKani() {
    const step = this.state.step;
    if (step.kind !== "wkReview") return;
    const session = step.session;
    if (session.phase === "submitting") return;
    const item = session.items[session.index];
    if (item == null) return;
    this.set({
      step: { kind: "wkReview", session: { ...session, phase: "submitting", failure: null, authRejected: false } },
    });
    void (async () => {
      try {
        const result = await WaniKani.submitReview(
          item.subjectId,
          session.incorrectMeaning,
          session.incorrectReading
        );
        const current = this.state.step;
        if (current.kind !== "wkReview") return;
        if (result.status === "accepted") {
          this.advanceWaniKani(current.session);
          return;
        }
        const rejected =
          result.status === "authRejected" || result.status === "needsToken";
        this.set({
          step: {
            kind: "wkReview",
            session: {
              ...current.session,
              phase: "submitFailed",
              failure: rejected ? null : result.status === "failed" ? result.failure : null,
              authRejected: rejected,
            },
          },
        });
      } catch (error) {
        const current = this.state.step;
        if (current.kind !== "wkReview") return;
        this.set({
          step: {
            kind: "wkReview",
            session: {
              ...current.session,
              phase: "submitFailed",
              failure: waniKaniFailureOf(error),
              authRejected: false,
            },
          },
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

  /** The subject is saved: the next one, or the caught-up state when the queue is done. */
  private advanceWaniKani(session: WaniKaniSession) {
    const item = session.items[session.index];
    // One part was just finished. For a subject with a reading, its meaning was already counted when
    // the screen moved to the reading, so this adds the part that was on screen, not the whole
    // subject - which is what keeps the progress line from passing its end.
    const done = session.doneParts + (item == null ? 0 : 1);
    const next = session.index + 1;
    if (next >= session.items.length) {
      void this.finishWaniKani();
      return;
    }
    this.set({
      step: {
        kind: "wkReview",
        session: {
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
        },
      },
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
      step: { kind: "wkLesson", session: { ...session, teachIndex: next, doneParts: next } },
    });
  }

  /** One keystroke in the lesson quiz's answer field. */
  lessonEntry(text: string) {
    const step = this.state.step;
    if (step.kind !== "wkLesson") return;
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
          const kana = await WaniKani.readingAnswer(typed);
          correct = item.readings.includes(kana);
        } else {
          const answer = normalizeMeaning(typed);
          correct = item.meanings.some((meaning) => normalizeMeaning(meaning) === answer);
        }
        const current = this.state.step;
        if (current.kind !== "wkLesson") return;
        const live = current.session;
        if (!correct) {
          const answer =
            session.part === "reading" ? item.primaryReading ?? "" : item.primaryMeaning;
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
                feedback: { tone: "wrong", text: `Incorrect — the answer is ${answer}` },
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

  /** Starts the subject whose quiz just finished; on success, the next subject or the hub. */
  private startWaniKaniLesson() {
    const step = this.state.step;
    if (step.kind !== "wkLesson") return;
    const session = step.session;
    if (session.phase === "starting") return;
    const item = session.batch[session.quizIndex];
    if (item == null) return;
    this.set({
      step: {
        kind: "wkLesson",
        session: { ...session, phase: "starting", failure: null, authRejected: false },
      },
    });
    void (async () => {
      try {
        const result = await WaniKani.startLesson(item.assignmentId);
        const current = this.state.step;
        if (current.kind !== "wkLesson") return;
        if (result.status === "started") {
          this.advanceLesson(current.session);
          return;
        }
        const rejected = result.status === "authRejected" || result.status === "needsToken";
        this.set({
          step: {
            kind: "wkLesson",
            session: {
              ...current.session,
              phase: "startFailed",
              failure: rejected ? null : result.status === "failed" ? result.failure : null,
              authRejected: rejected,
            },
          },
        });
      } catch (error) {
        const current = this.state.step;
        if (current.kind !== "wkLesson") return;
        this.set({
          step: {
            kind: "wkLesson",
            session: {
              ...current.session,
              phase: "startFailed",
              failure: waniKaniFailureOf(error),
              authRejected: false,
            },
          },
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

  /** The subject is started: the next one, or the hub once the batch is done. */
  private advanceLesson(session: WaniKaniLessonSession) {
    const item = session.batch[session.quizIndex];
    // As in the review: the part on screen was just finished, and for a subject with a reading its
    // meaning was counted when the screen moved to it.
    const done = session.doneParts + (item == null ? 0 : 1);
    const next = session.quizIndex + 1;
    if (next >= session.batch.length) {
      this.pausedLesson = null;
      void this.enterWaniKaniHome();
      return;
    }
    this.set({
      step: {
        kind: "wkLesson",
        session: {
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
        },
      },
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
          else await this.enterWaniKaniHome();
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
