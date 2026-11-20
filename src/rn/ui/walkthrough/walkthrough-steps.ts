/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Pure data and state transitions for the interactive Home walkthrough.
 *
 * Kept free of React and native imports so every step definition, boundary clamp, and visibility
 * gate is directly unit-testable off-device.
 */
import type {IconName} from "../icons";

export type WalkthroughStepId = "lookup" | "search" | "study" | "tabs";

/** Where the walkthrough card anchors vertically on Home so its pointer aims at the real control. */
export type WalkthroughAnchor = "top" | "middle" | "bottom";

/** Which edge and alignment carries the directional pointer cue on the walkthrough card. */
export type WalkthroughPointer = "top-right" | "top-center" | "bottom-center";

export interface WalkthroughStep {
  id: WalkthroughStepId;
  icon: IconName;
  title: string;
  body: string;
  tip: string;
  anchor: WalkthroughAnchor;
  pointer: WalkthroughPointer;
  /** Optional interactive action label rendered inside the step card. */
  actionLabel?: string;
}

/** Sample word used by the interactive "Try a lookup" action on step 2. */
export const SAMPLE_LOOKUP_QUERY = "猫";

export const WALKTHROUGH_STEPS: ReadonlyArray<WalkthroughStep> = [
  {
    id: "lookup",
    icon: "touch_app",
    title: "Look up Japanese on any screen",
    body: "Use the top-right status icon to check or enable Screen lookup. When it is on, circle Japanese text in manga, games, or any app for instant offline definitions.",
    tip: "Everything runs on-device — screenshots never leave your phone.",
    anchor: "top",
    pointer: "top-right",
    actionLabel: "Open Screen lookup",
  },
  {
    id: "search",
    icon: "search",
    title: "Search offline — even in romaji",
    body: "Tap the search bar above to look up words in kanji, kana, or plain romaji like neko or taberu. Tap any result to inspect pitch, readings, and kanji breakdowns.",
    tip: "Try searching: 猫 · ねこ · taberu",
    anchor: "top",
    pointer: "top-center",
    actionLabel: "Try searching 猫",
  },
  {
    id: "study",
    icon: "library_books",
    title: "Study with AnkiDroid & WaniKani",
    body: "Your daily streak, Anki decks, and WaniKani reviews live right on Home. You can also send any word you look up straight to an AnkiDroid deck in one tap.",
    tip: "Connect AnkiDroid or WaniKani anytime from Home or Settings.",
    anchor: "middle",
    pointer: "top-center",
  },
  {
    id: "tabs",
    icon: "bar_chart",
    title: "Track progress & customize Gaten",
    body: "Switch between Home, Analytics, and Settings using the bottom bar. You can replay this walkthrough anytime under Settings.",
    tip: "Swipe left or right between tabs, or tap any tab below.",
    anchor: "bottom",
    pointer: "bottom-center",
  },
];

/** Clamps a step index into the valid range for [steps]. */
export function clampStepIndex(index: number, total = WALKTHROUGH_STEPS.length): number {
  if (total <= 0) return 0;
  return Math.max(0, Math.min(total - 1, Math.trunc(index)));
}

/** Returns the next step index, or `null` when advancing past the final step finishes the tour. */
export function nextWalkthroughStep(index: number, total = WALKTHROUGH_STEPS.length): number | null {
  const clamped = clampStepIndex(index, total);
  return clamped + 1 >= total ? null : clamped + 1;
}

/** Returns the previous step index, clamped at 0. */
export function prevWalkthroughStep(index: number, total = WALKTHROUGH_STEPS.length): number {
  const clamped = clampStepIndex(index, total);
  return Math.max(0, clamped - 1);
}

/** Human-readable step counter used visually and in TalkBack labels. */
export function walkthroughProgressLabel(index: number, total = WALKTHROUGH_STEPS.length): string {
  const safeTotal = Math.max(1, total);
  const current = clampStepIndex(index, safeTotal) + 1;
  return `${current} of ${safeTotal}`;
}
