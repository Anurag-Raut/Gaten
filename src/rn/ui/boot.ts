/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run step read, started once and shared.
 *
 * The shell owns the step and its failure UI, but the read is started from the app root's own
 * effect so it overlaps the theme bootstrap instead of following it. The shell mounts below the
 * theme provider, which renders nothing until the theme resolves, so a read started there would be
 * a third sequential bridge hop. Starting it here makes it concurrent with the theme's own reads
 * while keeping a single in-flight request and the shell's fresh retry intact.
 */
import * as GatenAppInfo from "../native/GatenAppInfo";

let primed: Promise<number> | null = null;

/**
 * The first-run step, read once. Every caller gets the same promise, so the app root's early start
 * and the shell's mount cannot ask the bridge twice. A rejection is swallowed for the early-start
 * window — the shell attaches its own handler and owns the retry — and the slot is cleared so the
 * retry reads again.
 */
export function primeOnboardingStep(): Promise<number> {
  if (primed == null) {
    const started = GatenAppInfo.getOnboardingStep();
    // The early start must not surface as an unhandled rejection before the shell consumes it.
    started.catch(() => {
      if (primed === started) {
        primed = null;
      }
    });
    primed = started;
  }
  return primed;
}

/** Forgets the primed read so the next call is a fresh one: the setup retry's own read. */
export function resetOnboardingStepPrime(): void {
  primed = null;
}
