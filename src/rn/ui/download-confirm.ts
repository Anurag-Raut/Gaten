/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The copy behind the one download confirmation both entry points raise. Kept as plain data so the
 * onboarding page and the settings row cannot drift into two different descriptions of the same
 * transfer, and so the wording can be checked without a device.
 *
 * COPY STATUS: the dialog wording is the approved brief's draft and stays here for one edit point;
 * no copy in this branch is committed or treated as final until the user signs off.
 */
import {formatMegabytes} from "./onboarding/onboarding-steps";

/** What the optional reader does, in one line: the benefit first, the offline promise second. */
export const DOWNLOAD_CONFIRM_REASON =
  "Reads screen and novel text better, then works offline.";

/** The connection note, short enough to stay one quiet line at the dialog's width. */
export const DOWNLOAD_CONFIRM_NOTE = "Wi-Fi recommended. Mobile data may incur charges.";

export interface DownloadConfirmCopy {
  title: string;
  /** The decimal-MB size the row would download, or null when the read did not report one. */
  size: string | null;
  reason: string;
  note: string;
}

/** The dialog's copy for [name] and [bytes], using the same size format as every download row. */
export function downloadConfirmCopy(name: string, bytes: number): DownloadConfirmCopy {
  return {
    title: `Download ${name}?`,
    size: bytes > 0 ? formatMegabytes(bytes) : null,
    reason: DOWNLOAD_CONFIRM_REASON,
    note: DOWNLOAD_CONFIRM_NOTE,
  };
}
