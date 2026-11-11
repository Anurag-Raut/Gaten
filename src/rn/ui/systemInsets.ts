/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The bottom inset every edge-to-edge screen lays out from, as a plain function so the rule can be
 * tested without a window.
 *
 * `react-native-safe-area-context` reads Android's navigation-bar inset. On Android 15's
 * edge-to-edge gesture navigation that inset is zero — the system draws no bar, only the gesture
 * handle — so the floating bottom bar and bottom-padded content would otherwise sit flush against
 * the screen edge. The system-gesture inset is the strip the platform reserves for that handle, and
 * it is read from the native shell (`GatenShell.getSystemGestureInsets`) and merged in here. A
 * device with neither a navigation bar nor a gesture strip still keeps [MIN_BOTTOM_INSET] so nothing
 * ever touches the bottom edge.
 */

/**
 * The smallest bottom gap, in dp, kept even when the platform reports no bottom inset at all. It is
 * the spacing scale's `s` step: enough that the surface reads as floating rather than docked, small
 * enough that it never crowds content on a device that genuinely has no system bottom chrome.
 */
export const MIN_BOTTOM_INSET = 8;

/**
 * The bottom inset to lay out from: the larger of the safe-area inset the provider already computed
 * and the system-gesture inset, floored at [MIN_BOTTOM_INSET]. Only the bottom is merged — the
 * left/right gesture strips are not unsafe content area, so padding for them would wrongly inset
 * every screen.
 */
export function mergeBottomInset(safeBottom: number, gestureBottom: number): number {
  return Math.max(safeBottom, gestureBottom, MIN_BOTTOM_INSET);
}
