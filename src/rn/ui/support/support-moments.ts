/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The good moments to offer support: a point where the reader has just got something done, so a
 * thank-you lands as a thank-you and not as an interruption. Study code reports one here; the
 * popup, next time Home is on show, spends it. Held in memory only — a moment belongs to the launch
 * it happened in.
 */
let pendingMoment = false;

/** Reports a good moment, such as a finished review or lesson session. */
export function noteSupportMoment(): void {
  pendingMoment = true;
}

/** Whether a moment is waiting. */
export function hasSupportMoment(): boolean {
  return pendingMoment;
}

/** Spends the waiting moment, so one finished session buys one offer at most. */
export function consumeSupportMoment(): void {
  pendingMoment = false;
}
