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

/** One row's state, as the page draws it: done, the reader's current step, or not started. */
export type StepStatus = "done" | "current" | "todo";

/** The word a row's own state reads with, for its one grouped accessibility label. */
export function stepStatusWord(status: StepStatus): string {
  return status === "done" ? "done" : status === "current" ? "current" : "not started";
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

/** Page 1's facts: the accessibility grant, and PP-OCRv6 Medium's install and selection. */
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
 * better reader is installed *and* selected. The bundled ML Kit default alone is never enough --
 * otherwise choosing or downloading PP-OCRv6 Medium would be unreachable -- so the reader resolves
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
 */
export function wanikaniSteps(facts: WaniKaniFacts): StepSet {
  return stepSet(facts.connected === true, facts.connected === true);
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

/** The whole percent of a download, clamped to 0..100 for the bar and the spoken value. */
export function downloadPercent(progress: DownloadProgressLike): number {
  if (progress.bytesTotal <= 0) return 0;
  const percent = Math.floor((progress.bytesDone / progress.bytesTotal) * 100);
  return Math.max(0, Math.min(100, percent));
}

/** The one line a reader's download row shows: its phase, its percent, and its megabytes so far. */
export function downloadProgressText(progress: DownloadProgressLike): string {
  if (progress.phase === "verifying") return "Verifying\u2026";
  const percent = downloadPercent(progress);
  const done = formatMegabytes(progress.bytesDone);
  const total = formatMegabytes(progress.bytesTotal);
  return `Downloading\u2026 \u00b7 ${percent}% \u00b7 ${done} of ${total}`;
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
