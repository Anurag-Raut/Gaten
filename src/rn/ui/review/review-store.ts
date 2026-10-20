/**
 * The review session's state, held in a module singleton so the state survives tab switches —
 * the RN port of the shell-owned AnkiReviewViewModel. A session left open keeps its card, its
 * timer and its open WaniKani details; coming back to the Review tab lands on the card the
 * reader left, and everything before a card is re-read on entry (see useAnkiReview).
 *
 * Every provider call goes through the GatenAnki bridge; failures come back as rejections whose
 * `code` is the Kotlin failure's own name (`ANKI_NOT_INSTALLED`, `ANKI_PROVIDER`, …) with the
 * wrapped detail as the message. Nothing here is left in a component: the shell reads
 * `sessionActive()` through `onSessionActive` to hide its bottom bar while a card is up.
 */
import * as Anki from "../../native/GatenAnki";
import type {
  AnkiCardResult,
  AnkiDeckResult,
} from "../../native/GatenAnki";

/** Why review cannot go on — the Kotlin AnkiReviewFailure's own names. */
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
  ord: number;
  deckName: string;
  question: string;
  answer: string;
  css: string;
  fieldNames: string[];
  fields: string[];
  typedAnswer: boolean;
}

/** What the review screen is doing — the Kotlin Step, verbatim. */
export type ReviewStep =
  | { kind: "starting" }
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
    }
  | { kind: "done"; deckName: string }
  | {
      kind: "failed";
      failure: ReviewFailure;
      detail?: string;
      afterGrade: boolean;
    };

/** The whole observable state the screen renders from. */
export interface ReviewState {
  step: ReviewStep;
  deck: AnkiDeckResult | null;
  busy: boolean;
  expandedKanji: string | null;
  expandedRadical: number | null;
}

/**
 * How deep a step sits, so a move to a deeper one slides forward and a move back slides back.
 * The two failure-shaped steps stand where the reader met them.
 */
export function stepDepth(step: ReviewStep): number {
  switch (step.kind) {
    case "starting":
    case "blocked":
      return 0;
    case "decks":
      return 1;
    case "session":
    case "failed":
      return 2;
    case "done":
      return 3;
  }
}

/**
 * Whether the step is a card session, and so whether the shell's bottom bar has to stay out of
 * the way: a card on screen, the wait for one, or a card whose read or answer just failed inside
 * the deck the reader chose. `deck` separates a failure inside a deck from a failure that never
 * got that far; the finished deck is not a session.
 */
export function holdsSession(
  step: ReviewStep,
  deck: AnkiDeckResult | null
): boolean {
  switch (step.kind) {
    case "session":
      return true;
    case "failed":
      return deck != null;
    case "decks":
      return deck != null;
    default:
      return false;
  }
}

/** The failure a bridge rejection carried, without any of the provider's internals. */
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

type StepListener = (state: ReviewState) => void;
type SessionListener = (active: boolean) => void;

class ReviewStore {
  private state: ReviewState = {
    step: { kind: "starting" },
    deck: null,
    busy: false,
    expandedKanji: null,
    expandedRadical: null,
  };

  private stepListeners = new Set<StepListener>();
  private sessionListeners = new Set<SessionListener>();
  private lastSessionActive = false;

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

  /** Whether a card session is open — the shell reads this to hide its bottom bar. */
  sessionActive(): boolean {
    return holdsSession(this.state.step, this.state.deck);
  }

  /** Reported whenever that fact changes — and only then. */
  onSessionActive(listener: SessionListener): { remove: () => void } {
    this.sessionListeners.add(listener);
    return { remove: () => this.sessionListeners.delete(listener) };
  }

  private set(patch: Partial<ReviewState>) {
    this.state = { ...this.state, ...patch };
    for (const listener of this.stepListeners) listener(this.state);
    const active = this.sessionActive();
    if (active !== this.lastSessionActive) {
      this.lastSessionActive = active;
      for (const listener of this.sessionListeners) listener(active);
    }
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

  /** Reads AnkiDroid's status, then its deck list. */
  private async start(): Promise<void> {
    try {
      const status = await Anki.status();
      if (status === "notInstalled") {
        this.set({ step: { kind: "blocked", failure: "NOT_INSTALLED" } });
      } else if (status === "needsPermission") {
        this.set({ step: { kind: "blocked", failure: "PERMISSION" } });
      } else {
        this.openDecks();
      }
    } catch (error) {
      const { failure } = failureOf(error);
      this.set({ step: { kind: "blocked", failure } });
    }
  }

  /** The permission dialog came back refused, so review cannot run yet. */
  permissionDenied() {
    this.set({ step: { kind: "blocked", failure: "PERMISSION" } });
  }

  /**
   * Reads what review can do from scratch, from outside a deck: AnkiDroid's status, then its
   * deck list, with no deck chosen. This is what entering the screen asks for whenever the
   * reader is not already inside a session — everything it re-reads can have changed while the
   * screen was away.
   */
  refresh() {
    this.ifIdle(() => {
      this.set({ deck: null });
      void this.start();
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

  /** Starts reviewing `deck`. */
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
        this.nextCard();
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
    this.nextCard({
      ...step,
      startedAt: step.startedAt + (away ?? 0),
    });
  }

  /**
   * Asks AnkiDroid for the next due card in the chosen deck, fresh, and reads it. `keep` is the
   * card the reader is already on: when the scheduler hands out the same card again it keeps
   * its revealed side and its timer, so coming back from another app does not reset the
   * reader's place. A different card always starts from the front.
   */
  private nextCard(keep?: Extract<ReviewStep, { kind: "session" }>) {
    const deck = this.state.deck;
    if (deck == null) return;
    this.setBusy(true);
    this.collapseDetails();
    void (async () => {
      try {
        const queue = await Anki.nextCard(deck.id);
        if (queue.kind === "empty") {
          this.set({ step: { kind: "done", deckName: deck.name } });
        } else {
          const card = await Anki.card(queue.noteId, queue.ord);
          const same =
            keep != null &&
            keep.card.noteId === card.noteId &&
            keep.card.ord === card.ord
              ? keep
              : null;
          this.set({
            step: {
              kind: "session",
              card: toReviewCard(card),
              token: queue.token,
              buttonLabels: queue.buttonLabels,
              mediaFiles: queue.mediaFiles,
              startedAt: same?.startedAt ?? Date.now(),
              revealed: same?.revealed === true,
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

function toReviewCard(card: AnkiCardResult): ReviewCard {
  return {
    noteId: card.noteId,
    ord: card.ord,
    deckName: card.deckName,
    question: card.question,
    answer: card.answer,
    css: card.css,
    fieldNames: card.fieldNames,
    fields: card.fields,
    typedAnswer: card.typedAnswer,
  };
}

/** The app's one review session state, outside any component. */
export const reviewStore = new ReviewStore();