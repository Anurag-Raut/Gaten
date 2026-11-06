/**
 * The fixture's only synthetic edge: the WaniKani submission bridge.
 *
 * The review store, its `submitting` phase, `advanceWaniKani`, `submitFailed`, the retry guard and
 * the whole screen are production code. This module sets the temporary evidence-build hook in
 * `GatenWaniKani.submitReview`, so the production wrapper returns an outcome this fixture can time
 * and choose. Nothing here touches a token, a network or a real account.
 *
 * The 1.5 s delay is a fixture constant, not app behaviour. The fixture bar says so on screen.
 */
import type {WaniKaniReviewSubmissionResult} from "../../../../src/rn/native/GatenWaniKani";

export type SubmitOutcome = "accepted" | "failed";

type SubmitFn = (
  subjectId: number,
  incorrectMeaningAnswers: number,
  incorrectReadingAnswers: number
) => Promise<WaniKaniReviewSubmissionResult>;

/** The temporary hook in `submitReview`; absent from the committed bridge. */
type FixtureSubmitGlobal = {__GATEN_FIXTURE_SUBMIT__?: SubmitFn};

/** Fixture-only pacing, so the pending row is visible long enough to photograph. */
export const SYNTHETIC_SUBMIT_DELAY_MS = 1500;

let outcomes: SubmitOutcome[] = ["accepted", "failed"];
let calls = 0;
let bridgeTarget = "not installed";

/** Returns after the production synchronous boundary: the outcome is fixed when the store calls. */
const syntheticSubmit: SubmitFn = async () => {
  const outcome = outcomes[calls] ?? "failed";
  calls += 1;
  await new Promise<void>((resolve) => setTimeout(resolve, SYNTHETIC_SUBMIT_DELAY_MS));
  return outcome === "accepted"
    ? {status: "accepted"}
    : {status: "failed", failure: "OFFLINE"};
};

export function installSubmitStub(): string {
  const fixtureGlobal = globalThis as unknown as FixtureSubmitGlobal;
  try {
    fixtureGlobal.__GATEN_FIXTURE_SUBMIT__ = syntheticSubmit;
  } catch {
    bridgeTarget = "FAILED";
    return bridgeTarget;
  }
  bridgeTarget =
    fixtureGlobal.__GATEN_FIXTURE_SUBMIT__ === syntheticSubmit ? "fixture hook" : "FAILED";
  return bridgeTarget;
}

/** Sets the outcome schedule and starts the next call at its first entry. */
export function configureOutcomes(next: SubmitOutcome[]): void {
  outcomes = next;
  calls = 0;
}

export function submitStubState(): {
  target: string;
  nextCall: number;
  nextOutcome: SubmitOutcome;
  delayMs: number;
} {
  return {
    target: bridgeTarget,
    nextCall: calls + 1,
    nextOutcome: outcomes[calls] ?? "failed",
    delayMs: SYNTHETIC_SUBMIT_DELAY_MS,
  };
}
