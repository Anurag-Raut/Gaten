/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The one place the support destinations live. Nothing else in the feature hardcodes a URL: the
 * sheet and the proactive popup both read what is here, so a changed destination changes in both.
 */
import * as GatenPlatform from "../../native/GatenPlatform";
import type {IconName} from "../icons";

/** The Play listing the review action opens. */
export const REVIEW_URL = "https://play.google.com/store/apps/details?id=com.gaten";

/** Said when a hand-off fails, and when a destination has not been filled in yet. */
const OPEN_FAILED = "Couldn't open the page. Try again.";

// TODO(owner): fill in the three donor urls when the pages exist. Until then every empty one
// reports the failed hand-off rather than opening anything.
export const SUPPORT_LINKS: ReadonlyArray<{label: string; url: string; icon: IconName}> = [
  {label: "Buy Me a Coffee", url: "", icon: "open_in_new"},
  {label: "Ko-fi", url: "", icon: "open_in_new"},
  {label: "GitHub Sponsors", url: "", icon: "open_in_new"},
];

/**
 * Opens one support destination. An empty url is not a broken page to fall into but the owner's own
 * "not filled in yet", and an opener that resolves `false` means no browser could take it: both are
 * reported with the app's existing failed-hand-off toast, and neither pretends the move landed.
 */
export function openSupportLink(url: string): void {
  if (url.length === 0) {
    void GatenPlatform.toast(OPEN_FAILED);
    return;
  }
  void GatenPlatform.openUrl(url).then((opened) => {
    if (!opened) {
      void GatenPlatform.toast(OPEN_FAILED);
    }
  });
}
