/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import {openUrl, toast} from "../../native/GatenPlatform";

/**
 * AnkiDroid's store listing. One address for the two controls that hand the reader to it: this
 * module's `downloadAnkiDroid` and the setup help's own "Download Anki" link, which opens the page
 * itself rather than reporting a failed hand-off.
 */
export const ANKI_STORE_URL = "https://play.google.com/store/apps/details?id=com.ichi2.anki";

/** Opens AnkiDroid's store listing in the store or browser, reporting a failed hand-off. */
export async function downloadAnkiDroid(): Promise<void> {
  try {
    if (await openUrl(ANKI_STORE_URL)) {
      return;
    }
  } catch {
    // Rejected intents and an unavailable URL handler get the same visible failure below.
  }
  void toast("Could not open the Anki download page.");
}
