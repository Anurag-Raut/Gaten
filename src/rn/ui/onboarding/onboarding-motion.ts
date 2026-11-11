/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The onboarding flow's motion, as plain data: the page scene's duration, the step row's own
 * open/close, the curve it all rides, and the direction a move travels in.
 *
 * Pulled out of the flow so the numbers a transition is built from live in one place a test can pin
 * without a device, and so the flow and its rows can never drift apart: a page change and a step row
 * growing in place share the same curve, and back is the forward journey with its direction
 * reversed, never a second animation.
 */

/** The scene's per-page duration: the artwork, the copy, the track and the steps all ride it. */
export const STEP_SCENE_MS = 340;

/** A step row's own open or close. Shorter than a page change: a row grows in place, it does not
 * arrive as a page does, and the eye should read it as the same screen getting taller. */
export const STEP_ROW_MS = 200;

/**
 * Material's emphasized curve (0.2, 0, 0, 1) as its four bezier coordinates. The Reanimated easing
 * the flow animates with is built from exactly these numbers, and the scene clock, the copy and the
 * step rows all share it.
 */
export const PAGE_EASING_BEZIER: [number, number, number, number] = [0.2, 0, 0, 1];

/** A step row's own duration: reduced motion opens and closes it instantly, at the same end state. */
export function stepRowDuration(reducedMotion: boolean): number {
  return reducedMotion ? 0 : STEP_ROW_MS;
}

/**
 * The direction a move travels in: +1 forward (a later page or the same one), -1 back. A page's copy
 * and its steps travel this way, so going back reverses the forward journey rather than replaying it.
 */
export function travelDirection(from: number, to: number): 1 | -1 {
  return to > from ? 1 : -1;
}
