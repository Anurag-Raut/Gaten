/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The engine descriptions the user approved, in the words both screens use. They live here so
 * onboarding and Settings cannot drift, and they say only what each engine can do — downloads,
 * sizes, offline availability and installation state belong to the button, the dialog and the
 * status rows, never to these lines.
 *
 * docs/ocr-engines.md is the source of truth for any claim: the bundled reader is the Japanese text
 * recognizer, and the optional one is the recommended reader for ordinary screen and novel text.
 */
import type {RecognitionEngine} from "../native/GatenRecognition";

export const STANDARD_ENGINE_SUMMARY = "Clear, regular text in standard fonts";

export const ENHANCED_ENGINE_SUMMARY = "Better for stylised fonts, novels and harder-to-read text";

/** The one short state line an installed Enhanced row adds; Standard has no state of its own. */
export const ENHANCED_READY = "Ready to use.";

/** What reads meanwhile when Enhanced is selected but its pack is not on the device. */
export const ENHANCED_FALLBACK_NOTE = "Until it's ready, Standard reads text.";

/** The benefit line for an engine id, shared by onboarding and Settings. */
export function engineSummary(engine: RecognitionEngine): string {
  return engine === "medium" ? ENHANCED_ENGINE_SUMMARY : STANDARD_ENGINE_SUMMARY;
}

/** The state line an engine row shows under its benefit line, or null when there is none to show. */
export function engineReadyLine(
  engine: RecognitionEngine,
  installed: boolean,
  downloading: boolean,
): string | null {
  if (engine !== "medium" || !installed || downloading) return null;
  return ENHANCED_READY;
}
