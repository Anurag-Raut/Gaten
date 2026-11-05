/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The answer details a WaniKani review can show, and which one the panel opens on for the prompt in
 * front of the reader. Kept beside the review screen's own module so a unit test can reach the
 * default-tab rule without a renderer.
 */

/** The answer details a review can show, in the order WaniKani itself teaches them. */
export type DetailTab = "meaning" | "reading" | "context";

export const DETAIL_TAB_LABELS: Record<DetailTab, string> = {
  meaning: "Meaning",
  reading: "Reading",
  context: "Context",
};

/** The reading the subject carries, which is what decides whether its reading detail is offered. */
export type ReadingDetails = {readings: readonly string[]};

/**
 * The tab the details open on: the part being asked when the subject actually offers it, otherwise
 * the meaning. A reading prompt on a subject with no readings falls back to the meaning so the panel
 * never selects a tab the row does not carry.
 */
export function defaultDetailTab(
  part: "meaning" | "reading",
  item: ReadingDetails | null | undefined,
): DetailTab {
  if (part === "reading" && item != null && item.readings.length > 0) return "reading";
  return "meaning";
}
