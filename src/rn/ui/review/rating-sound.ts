/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The sound one Anki rating button plays. The ease is positional, exactly as `ratingsFor` reads the
 * deck's button count: four buttons are Again/Hard/Good/Easy, three are Again/Good/Easy, and two are
 * Again/Good. Anything outside the clamped row plays nothing.
 */
import type {FeedbackKind} from "../../native/GatenHaptics";

export function ratingSoundFor(ease: number, buttonCount: number): FeedbackKind | null {
  const count = Math.min(4, Math.max(2, buttonCount));
  if (!Number.isInteger(ease) || ease < 1 || ease > count) return null;
  switch (count) {
    case 2:
      return ease === 1 ? "rateAgain" : "rateGood";
    case 3:
      return ease === 1 ? "rateAgain" : ease === 2 ? "rateGood" : "rateEasy";
    default:
      return ease === 1
        ? "rateAgain"
        : ease === 2
          ? "rateHard"
          : ease === 3
            ? "rateGood"
            : "rateEasy";
  }
}
