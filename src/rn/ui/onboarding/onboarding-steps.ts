/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The onboarding flow's steps as pure data: what the device reports turns into the two rows a page
 * draws, and into the single question the flow's auto-advance asks -- is every step on this page
 * verified done?
 *
 * Nothing here touches React or a native module, so the derivation the screens depend on is one
 * function of plain facts, pinnable by tests without a device: a step is done only when the fact
 * behind it was observed, never because a page moved on. Each page's advance gate is the same
 * [verified] its rows are built from, so a page cannot draw itself complete and advance while
 * saying otherwise; the primary button is the one deliberate way past a step the device cannot
 * confirm, and it never changes what [verified] means.
 */

/**
 * One row's state, as the page draws it: done, the reader's current step, an openable row that is
 * not the reader's step but is offered to them (WaniKani's "Create a token"), or not started.
 */
export type StepStatus = "done" | "current" | "available" | "todo";

/** The word a row's own state reads with, for its one grouped accessibility label. */
export function stepStatusWord(status: StepStatus): string {
  return status === "done"
    ? "done"
    : status === "current"
      ? "current"
      : status === "available"
        ? "available"
        : "not started";
}

/** How the rail segment below a row is drawn: solid once that step is done, the pale rail otherwise. */
export type ConnectorState = "filled" | "pending";

/**
 * The rail segment a row's own step leaves below its disc. The stepper reads every segment this way,
 * so a segment is only ever solid under a step that is really done — the one fact the page verified.
 */
export function connectorStateAfter(status: StepStatus): ConnectorState {
  return status === "done" ? "filled" : "pending";
}

/**
 * True while a step's content has to be laid out at its natural height so it can be measured: it is
 * open, and nothing has measured it yet. A step that arrives open is laid out this way on its first
 * frame, so it opens at once — measuring it inside a zero-height clip reports zero on device, which
 * is exactly how a step used to stay shut forever.
 */
export function shouldMeasureStepContent(shown: boolean, measured: number | null): boolean {
  return shown && measured == null;
}

/**
 * The height a content measurement is allowed to set: the measured height when it is a real one, or
 * null for a zero, negative or non-finite one. A closed row clips its content to nothing, and that
 * clipped zero must never be taken as the height the row opens to.
 */
export function acceptStepContentHeight(height: number): number | null {
  return Number.isFinite(height) && height > 0 ? height : null;
}

/** What a step's content knows: the natural height of what it holds, once it has been measured. */
export interface StepContentState {
  measured: number | null;
}

/** What a step's content hears: a height it laid itself out at, and whether the step was open. */
export interface StepContentEvent {
  height: number;
  /** True while the content is open, so the clip is not holding it shut. */
  open: boolean;
}

/**
 * A step's content, as a state machine.
 *
 * The content's height is a property of the content, not of the clip in front of it, and a reading
 * taken while the clip is shut belongs to the clip. The first version of this code took every
 * reading, so the shrink of a close — its intermediate frames included — overwrote the real height
 * with a sliver, and from then on the step could only ever open to that sliver: open, close, and
 * nothing again. So a reading while the step is shut is ignored outright, a non-positive reading is
 * ignored, and a real reading only ever raises the height — which is kept across open and close, so
 * the reader can open the step as many times as they like.
 */
export function stepContentReducer(
  state: StepContentState,
  event: StepContentEvent,
): StepContentState {
  if (!event.open && state.measured != null) {
    // The clip is shut: this reading is the clip's height, never the content's.
    return state;
  }
  const next = acceptStepContentHeight(event.height);
  // The same state object for the same height, so a re-laid-out content that has not changed its
  // height never re-renders (a reading per render is how this loops).
  if (next == null || next === state.measured) return state;
  return {measured: next};
}

/** The value a stepper's clocked property lands on, and whether it lands at once. */
export interface StepTarget {
  /** 1 when the property is on (the content open, the rail filled, the disc done), else 0. */
  value: number;
  /** True when the value must be written straight in, never ridden over the row's clock. */
  instant: boolean;
}

/**
 * What a stepper's clocked property does, from whether it is on and whether motion is reduced.
 *
 * Reduced motion is not a zero-length animation: a `withTiming` of duration 0 never lands on the
 * device, which is how a step could read as expanded — chevron up, container sized — while its
 * opacity and height never arrived, and the how-to stayed invisible. With motion reduced the value
 * is written straight in instead, so the end state is always exactly reached.
 */
export function stepTarget(on: boolean, reducedMotion: boolean): StepTarget {
  return {value: on ? 1 : 0, instant: reducedMotion};
}

/**
 * How far a page must move up so a field's bottom clears the keyboard: zero when the field already
 * sits above the keyboard's top edge, the overlap plus a breathing [gap] otherwise.
 */
export function keyboardShift(fieldBottom: number, visibleBottom: number, gap: number): number {
  return Math.max(0, fieldBottom + gap - visibleBottom);
}

/** A page's two rows and its verified-completion gate. */
export interface StepSet {
  step1: StepStatus;
  step2: StepStatus;
  /** True only when every step on this page was verified from the device. */
  verified: boolean;
}

/**
 * One page's rows from its first readiness fact and its second one: [step1Done] is the fact the
 * device observes for the first row (lookup mode on, AnkiDroid installed, connected), [step2Done]
 * the second (the better reader installed and selected, the collection read, connected). The second
 * row is the reader's current step only once the first is really done.
 */
function stepSet(step1Done: boolean, step2Done: boolean): StepSet {
  return {
    step1: step1Done ? "done" : "current",
    step2: step2Done ? "done" : step1Done ? "current" : "todo",
    verified: step1Done && step2Done,
  };
}

/** Page 1's facts: the accessibility grant, and Enhanced's install and selection. */
export type LookupFact = "checking" | "on" | "off" | "failed";
export type MediumFact = "checking" | "absent" | "downloading" | "installed";

export interface LookupFacts {
  lookup: LookupFact;
  medium: MediumFact;
  /** True when the selected recognition mode is the downloaded reader. */
  mediumSelected: boolean;
}

/**
 * Page 1: lookup mode is the step that advances it, and the second row is verified only when the
 * better reader is installed *and* selected. The bundled Standard default alone is never enough --
 * otherwise choosing or downloading Enhanced would be unreachable -- so the reader resolves
 * this page's second row either by installing and selecting the better reader, or by the page's own
 * "Done".
 */
export function lookupSteps(facts: LookupFacts): StepSet {
  return stepSet(facts.lookup === "on", facts.medium === "installed" && facts.mediumSelected);
}

/** Page 2's facts: the AnkiDroid read, one terminal state per attempt. */
export type AnkiFact =
  | "checking"
  | "notInstalled"
  | "needsPermission"
  | "notSetUp"
  | "denied"
  | "failure"
  | "connected";

export interface AnkiFacts {
  status: AnkiFact;
  /** Whether the collection the read returned holds no decks yet; a complete read all the same. */
  collectionEmpty: boolean;
}

/** True once the read says AnkiDroid is on the device at all, whatever the permission state. */
export function ankiInstalled(status: AnkiFact): boolean {
  return status !== "checking" && status !== "notInstalled";
}

/**
 * Page 2: the first row is AnkiDroid being installed, the second the collection actually being read.
 * A denied permission, a collection that is not set up or a failed read is installed but not read
 * -- the row says what to do next, and the page never verifies.
 */
export function ankiSteps(facts: AnkiFacts): StepSet {
  return stepSet(ankiInstalled(facts.status), facts.status === "connected");
}

export interface WaniKaniFacts {
  /** Null while the stored token is being read. */
  connected: boolean | null;
}

/**
 * Page 3: the token is checked against WaniKani, not against anything Gaten can see, so both rows
 * are done only once the account is really connected. There is no partial state: a rejected token
 * and a network failure leave the page exactly where it was.
 *
 * Unlike the other pages the reader pastes first, so the row carrying the token field is the current
 * one; "Create a token" is an available row they can open for the how-to, never the step that holds
 * the page back and never drawn as done until the account really is.
 */
export function wanikaniSteps(facts: WaniKaniFacts): StepSet {
  const connected = facts.connected === true;
  return {
    step1: connected ? "done" : "available",
    step2: connected ? "done" : "current",
    verified: connected,
  };
}

/** What the WaniKani page's one filled action is, and what a press of it does, from live facts. */
export interface WaniKaniPrimaryFacts {
  /** Null while the stored token is being read. */
  connected: boolean | null;
  /** True while a connect attempt is in flight. */
  connecting: boolean;
  /** True when the last attempt failed in a way that is worth retrying. */
  retry: boolean;
  /** The token in the field right now. */
  token: string;
}

/** The label that action wears, whether it can be pressed, and what a press of it means. */
export interface WaniKaniPrimary {
  label: string;
  disabled: boolean;
  /** "advance" finishes the page, "connect" checks the token, "prompt" asks the reader for one. */
  intent: "advance" | "connect" | "prompt" | "none";
}

/**
 * The WaniKani page's primary, from its live facts. The button is never a dead tap: with no token
 * typed the press is still enabled and becomes a prompt for one — the field is focused and the inline
 * hint shown — so a reader who taps "Connect WaniKani" before pasting is told what to do instead of
 * getting silence.
 */
export function wanikaniPrimary(facts: WaniKaniPrimaryFacts): WaniKaniPrimary {
  if (facts.connected == null) return {label: "Checking\u2026", disabled: true, intent: "none"};
  if (facts.connecting) return {label: "Checking token\u2026", disabled: true, intent: "none"};
  if (facts.connected) return {label: "Done", disabled: false, intent: "advance"};
  return {
    label: facts.retry ? "Try again" : "Connect WaniKani",
    disabled: false,
    intent: facts.token.trim() === "" ? "prompt" : "connect",
  };
}

/** The page [step] moves on to: the last page finishes onboarding (3). */
export function nextStep(step: number): number {
  return step >= 2 ? 3 : step + 1;
}

/**
 * The auto-advance gate, re-checked live at fire time. It is the single answer to "may the flow move
 * on by itself now?": every step on the page verified, the visit not a Back visit, no screen reader,
 * the scene idle and settled, and no write or failure of its own still in flight. A false anywhere
 * holds the page.
 */
export interface AdvanceGate {
  verified: boolean;
  arrivedBackwards: boolean;
  screenReader: boolean;
  suspended: boolean;
  writePending: boolean;
  saveError: boolean;
  sceneIdle: boolean;
  contentSettled: boolean;
}

export function shouldAutoAdvance(gate: AdvanceGate): boolean {
  return (
    gate.verified &&
    !gate.arrivedBackwards &&
    !gate.screenReader &&
    !gate.suspended &&
    !gate.writePending &&
    !gate.saveError &&
    gate.sceneIdle &&
    gate.contentSettled
  );
}

/** The download size named in a label: decimal MB with one place of decimals, as the docs name it. */
export function formatMegabytes(bytes: number): string {
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

/** One download progress event, as the recognition module reports it. */
export interface DownloadProgressLike {
  phase: "downloading" | "verifying";
  bytesDone: number;
  bytesTotal: number;
}

/**
 * The whole percent of a download, clamped to 0..100 for the bar and the spoken value. Once the
 * bytes are all in the phase turns to "verifying" and the bar is full: the check is working on the
 * finished file, so a last event that still named a few missing bytes must not leave the bar short.
 */
export function downloadPercent(progress: DownloadProgressLike): number {
  if (progress.phase === "verifying") return 100;
  if (progress.bytesTotal <= 0) return 0;
  const percent = Math.floor((progress.bytesDone / progress.bytesTotal) * 100);
  return Math.max(0, Math.min(100, percent));
}

/** What the download bar draws: a real fill, or a sweep while there is no byte report yet. */
export interface DownloadBarState {
  /** The determinate fill, 0..100. Always 0 while [indeterminate]. */
  percent: number;
  /** True while the download is running but has not reported any bytes (e.g. a Play pack). */
  indeterminate: boolean;
}

/**
 * The bar's state from the live event. Play's pack transfer, and the first moments of any download,
 * report no bytes at all; a 0% fill there would claim a stalled download, so the bar sweeps instead.
 * As soon as a byte arrives it is a real fill, and "verifying" is the full bar.
 */
export function downloadBarState(progress: DownloadProgressLike): DownloadBarState {
  if (progress.phase === "downloading" && progress.bytesDone <= 0) {
    return {percent: 0, indeterminate: true};
  }
  return {percent: downloadPercent(progress), indeterminate: false};
}

/**
 * The one line a reader's download row shows: its phase and, once bytes arrive, the whole percent.
 * The size belongs to the button or the confirmation, not to this line — "Downloading… 42%".
 */
export function downloadProgressText(progress: DownloadProgressLike): string {
  if (progress.phase === "verifying") return "Verifying…";
  // Play's pack transfer reports no bytes: say it is working rather than pin a false 0%.
  if (progress.bytesDone <= 0) return "Downloading…";
  const percent = downloadPercent(progress);
  return `Downloading… ${percent}%`;
}

/**
 * The token a stored connect handler checks: read when the press happens, never captured when the
 * handler was made. The shell keeps one primary for the whole flow, so a handler that closed over a
 * keystroke's snapshot would keep checking the first character the reader typed; the page hands this
 * function live getters instead, and a press always reads what is in the field now.
 */
export function tokenToCheck(read: () => {token: string; busy: boolean}): string | null {
  const {token, busy} = read();
  const value = token.trim();
  return value === "" || busy ? null : value;
}

/** What the action-area key reads: the committed step, and everything the buttons draw. */
export interface ActionAreaFacts {
  step: number;
  label: string;
  disabled: boolean;
  /**
   * The page's verified-completion flag. Part of the key on purpose: [done] can flip while the
   * label, the disabled state and the save error stay the same — the Medium install landing on page
   * 1 is exactly that — and a key that ignored it would keep the shell's gate shut (and stale).
   */
  done: boolean;
  saveError: boolean;
  skipLabel: string;
  skipAccessibilityLabel: string;
}

/**
 * A key for the action area alone: the shell re-renders on this and never on a page's whole view, so
 * the buttons are not remounted, and do not lose their surface, just because a page re-reported.
 * Everything the shell reads for its gate or its press is in the key, so nothing a page changes can
 * be dropped; two pages that share a label and a disabled state still hand over a fresh handler
 * because the step is in it too.
 */
export function actionAreaKey(facts: ActionAreaFacts): string {
  return [
    `step:${facts.step}`,
    `${facts.label}:${facts.disabled ? 1 : 0}`,
    facts.done ? "done" : "",
    facts.saveError ? "error" : "",
    facts.skipLabel,
    facts.skipAccessibilityLabel,
  ].join("|");
}

/** What the WaniKani connect form checks a token against, as the page reads it back. */
export type WaniKaniOutcomeLike =
  | {status: "connected"}
  | {status: "invalidToken"}
  | {status: "failed"; failure: string}
  | {status: "bridge"; message: string};

/** One connect attempt's readout: the line under the token field, and whether it offers a retry. */
export interface ConnectReadout {
  message: string | null;
  /** True when the failure is worth another attempt (a connection or server failure). */
  retry: boolean;
}

/**
 * The line a connect attempt leaves behind. A rejected token is the reader's own to fix, so it is
 * not offered as a retry; a network or server failure is, and carries the bridge's own label for it.
 */
export function connectReadout(
  outcome: WaniKaniOutcomeLike,
  failureLabels: Record<string, string>,
): ConnectReadout {
  switch (outcome.status) {
    case "connected":
      return {message: null, retry: false};
    case "invalidToken":
      return {message: "WaniKani rejected that token. Check it was copied in full.", retry: false};
    case "failed":
      return {
        message: `Could not connect to WaniKani: ${failureLabels[outcome.failure] ?? "unknown error"}`,
        retry: true,
      };
    case "bridge":
      return {message: `Could not connect to WaniKani: ${outcome.message}`, retry: true};
  }
}
