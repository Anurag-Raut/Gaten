/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import {openUrl, toast} from "../../native/GatenPlatform";

/** Opens AnkiDroid's store listing in the store or browser, reporting a failed hand-off. */
export async function downloadAnkiDroid(): Promise<void> {
  try {
    if (await openUrl("https://play.google.com/store/apps/details?id=com.ichi2.anki")) {
      return;
    }
  } catch {
    // Rejected intents and an unavailable URL handler get the same visible failure below.
  }
  void toast("Could not open the AnkiDroid download page.");
}
