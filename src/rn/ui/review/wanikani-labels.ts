/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The words the WaniKani screens share: what each subject kind is called, and why a write failed.
 * Kept here rather than in either screen so the review and the lesson cannot drift apart on wording.
 */
import type {WaniKaniFailure, WaniKaniLessonItem} from "../../native/GatenWaniKani";

/** The subject kind in WaniKani's own words. */
export const WANIKANI_TYPE_LABELS: Record<WaniKaniLessonItem["type"], string> = {
  radical: "Radical",
  kanji: "Kanji",
  vocabulary: "Vocabulary",
  kana_vocabulary: "Kana vocabulary",
};

/** Why a submission or a start failed, in the same words the rest of the app uses for WaniKani. */
export const WANIKANI_FAILURE_LABELS: Record<WaniKaniFailure, string> = {
  OFFLINE: "no connection",
  TIMEOUT: "the request timed out",
  RATE_LIMITED: "WaniKani is limiting requests just now",
  SERVER: "WaniKani reported a server error",
  TOO_LARGE: "the answer was too large to keep",
  MALFORMED: "WaniKani sent something Gaten could not read",
  STORAGE: "this device could not save the answer",
};
