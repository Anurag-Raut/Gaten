/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * User-initiated feedback destinations and diagnostic formatting. Nothing is sent automatically:
 * the reader chooses whether to open a pre-filled GitHub issue or copy device info to the clipboard.
 */
import * as GatenPlatform from "../../native/GatenPlatform";
import type {DiagnosticsInfo} from "../../native/GatenAppInfo";

export const GITHUB_NEW_ISSUE_URL = "https://github.com/Anurag-Raut/Gaten/issues/new";

export const FEEDBACK_OPEN_FAILED = "Couldn't open the page. Try again.";
export const DIAGNOSTICS_COPIED = "Device info copied";
export const DIAGNOSTICS_COPY_FAILED = "Couldn't copy device info.";

export type FeedbackKind = "bug" | "feature";

export interface FeedbackBridge {
  openUrl: (url: string) => Promise<boolean>;
  copyToClipboard: (text: string) => Promise<boolean>;
  toast: (message: string) => Promise<boolean>;
}

const defaultBridge: FeedbackBridge = {
  openUrl: (url) => GatenPlatform.openUrl(url),
  copyToClipboard: (text) => GatenPlatform.copyToClipboard(text),
  toast: (message) => GatenPlatform.toast(message),
};

/** Formats non-PII build and device facts into a compact, readable block. */
export function formatDiagnostics(info: DiagnosticsInfo | null | undefined): string {
  if (info == null) return "App: Gaten";
  const lines: string[] = [];
  const versionParts = [
    info.appVersion ? info.appVersion : "",
    info.versionCode > 0 ? `(${info.versionCode})` : "",
  ].filter(Boolean);
  lines.push(`App: Gaten${versionParts.length > 0 ? ` ${versionParts.join(" ")}` : ""}`);
  if (info.androidRelease || info.androidSdk > 0) {
    const release = info.androidRelease || "unknown";
    const sdk = info.androidSdk > 0 ? ` (API ${info.androidSdk})` : "";
    lines.push(`Android: ${release}${sdk}`);
  }
  const device = [info.manufacturer, info.model]
    .map((part) => part.trim())
    .filter(Boolean)
    .join(" ");
  if (device) {
    lines.push(`Device: ${device}`);
  }
  return lines.join("\n");
}

/** One-line summary shown inside the feedback sheet so the reader sees what gets included. */
export function diagnosticsSummaryLine(info: DiagnosticsInfo | null | undefined): string {
  if (info == null) return "Gaten";
  const parts: string[] = [];
  parts.push(info.appVersion ? `Gaten ${info.appVersion}` : "Gaten");
  if (info.androidRelease) {
    parts.push(`Android ${info.androidRelease}`);
  } else if (info.androidSdk > 0) {
    parts.push(`API ${info.androidSdk}`);
  }
  const model = info.model.trim();
  if (model) {
    parts.push(model);
  }
  return parts.join(" · ");
}

/** Builds a pre-filled GitHub new-issue URL for a bug report or feature request. */
export function buildFeedbackUrl(
  kind: FeedbackKind,
  diagnostics?: DiagnosticsInfo | null,
): string {
  const params: string[] = [];
  if (kind === "bug") {
    params.push("labels=bug");
    params.push(`title=${encodeURIComponent("[Bug] ")}`);
    const body = [
      "### What happened?",
      "",
      "",
      "### Steps to reproduce",
      "1. ",
      "",
      "### Device info",
      "```",
      formatDiagnostics(diagnostics),
      "```",
    ].join("\n");
    params.push(`body=${encodeURIComponent(body)}`);
  } else {
    params.push("labels=enhancement");
    params.push(`title=${encodeURIComponent("[Feature] ")}`);
    const body = [
      "### Idea or improvement",
      "",
      "",
      "### Why it would help",
      "",
    ].join("\n");
    params.push(`body=${encodeURIComponent(body)}`);
  }
  return `${GITHUB_NEW_ISSUE_URL}?${params.join("&")}`;
}

/** Opens the pre-filled GitHub issue page; toasts only when no browser can take the intent. */
export async function openFeedbackDestination(
  kind: FeedbackKind,
  diagnostics?: DiagnosticsInfo | null,
  bridge: FeedbackBridge = defaultBridge,
): Promise<boolean> {
  const url = buildFeedbackUrl(kind, diagnostics);
  const opened = await bridge.openUrl(url).catch(() => false);
  if (!opened) {
    await bridge.toast(FEEDBACK_OPEN_FAILED).catch(() => false);
  }
  return opened;
}

/** Copies the non-PII diagnostics summary to the clipboard and confirms with a toast. */
export async function copyDiagnostics(
  diagnostics?: DiagnosticsInfo | null,
  bridge: FeedbackBridge = defaultBridge,
): Promise<boolean> {
  const copied = await bridge.copyToClipboard(formatDiagnostics(diagnostics)).catch(() => false);
  await bridge.toast(copied ? DIAGNOSTICS_COPIED : DIAGNOSTICS_COPY_FAILED).catch(() => false);
  return copied;
}
