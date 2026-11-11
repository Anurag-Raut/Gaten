/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The one reveal the reference surfaces use when the content under a fixed heading changes: a short
 * fade, nothing slides. The lesson's step change and the Anki kanji details share it so a page swap
 * reads the same in both places.
 */
import {FadeIn} from "react-native-reanimated";

/** How long the new content takes to appear. */
export const REVEAL_FADE_MS = 150;

/** The entering animation for a page's content, or none when the reader asked for reduced motion. */
export function revealEntering(reduced: boolean) {
  return reduced ? undefined : FadeIn.duration(REVEAL_FADE_MS);
}
