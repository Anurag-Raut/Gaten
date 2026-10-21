/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The global design tokens the Compose screens shared, as plain numbers: the spacing scale, the type
 * scale shared with the native overlay, the motion constants every transition is built from,
 * and the few layout constants the spec fixes. Colours are not tokens — they come from the theme
 * bridge (see `theme.tsx`).
 */
import {Easing as ReanimatedEasing} from "react-native-reanimated";
import type {TextStyle} from "react-native";

/** The spacing scale, in dp. */
export const spacing = {
  xs: 4,
  s: 8,
  m: 12,
  l: 16,
  compactSection: 20,
  contentInset: 22,
  xl: 24,
  xxl: 32,
} as const;

/** Nothing in the app is wider than this, however wide the screen is. */
export const MAX_CONTENT_WIDTH = 560;

/** Two cards are put beside each other from here on; below it a phone has width for one. */
export const TWO_COLUMN_WIDTH = 640;

/** Content surfaces stay flat; dividers and spacing establish grouping. */
export const CARD_ELEVATION = 0;

/** The shared corner vocabulary: brand artwork, controls and contained surfaces. */
export const shape = {
  small: 14,
  medium: 20,
  large: 20,
  brandTile: 10,
  settingsIcon: 11,
  empty: 12,
  button: 13,
  settings: 17,
  navActive: 15,
} as const;

/** The custom bottom bar track height, before the bottom safe-area inset is added. */
export const BOTTOM_BAR_HEIGHT = 63;

/** The press-scale curve — Material's fast-out-slow-in, the Kotlin app's own `Motion.pressEasing`. */
export const PRESS_EASING: [number, number, number, number] = [0.4, 0, 0.2, 1];

/**
 * The page slide: 260 ms on Material's emphasized curve (0.2, 0, 0, 1). It belongs to the review
 * step's swap (`StepTransition`) — the one slide this app times by hand, since the native stack's
 * push runs on the platform animator and has no Android duration knob (`animationDuration` is
 * documented iOS-only, and react-native-screens' Android anim resources are not configurable from
 * JS). 380 ms was the Compose design's number and read a beat slow in the hand; 260 ms is still a
 * slide an eye follows — the outgoing page is removed only once this and the wrap-height tween land —
 * and it keeps the app's hand-timed motion under the same 300 ms ceiling as everything else.
 */
export const PAGE_SLIDE_DURATION_MS = 260;

/**
 * The curve again as a Reanimated easing, which is a different shape: `withTiming` wants the
 * factory object this returns, not a plain function.
 */
export const PAGE_EASING_REANIMATED = ReanimatedEasing.bezier(0.2, 0, 0, 1);

/** The press-scale curve as a Reanimated easing, for the press feedback's timing. */
export const PRESS_EASING_REANIMATED = ReanimatedEasing.bezier(...PRESS_EASING);

/** Inter is bundled for Latin text; Japanese glyphs use the platform font fallback. */
const INTER = "Inter";

/** Shared text roles from docs/Gaten_Ink_Design_Language_v1.md. Display roles retain Japanese reading sizes. */
export const typeScale = {
  displayMedium: {fontSize: 40, lineHeight: 48, fontWeight: "500"},
  displaySmall: {fontSize: 34, lineHeight: 44, fontWeight: "500"},
  brand: {fontSize: 35, lineHeight: 38, letterSpacing: -1.85, fontWeight: "700", fontFamily: INTER},
  headlineSmall: {fontSize: 35, lineHeight: 38, letterSpacing: -1.85, fontWeight: "700", fontFamily: INTER},
  titleLarge: {fontSize: 17, lineHeight: 22, letterSpacing: -0.57, fontWeight: "700", fontFamily: INTER},
  titleMedium: {fontSize: 15, lineHeight: 20, letterSpacing: -0.45, fontWeight: "700", fontFamily: INTER},
  titleSmall: {fontSize: 15, lineHeight: 20, letterSpacing: -0.45, fontWeight: "700", fontFamily: INTER},
  bodyLarge: {fontSize: 14, lineHeight: 20, fontWeight: "400", fontFamily: INTER},
  bodyMedium: {fontSize: 14, lineHeight: 20, fontWeight: "400", fontFamily: INTER},
  description: {fontSize: 13, lineHeight: 19, fontWeight: "400", fontFamily: INTER},
  bodySmall: {fontSize: 12, lineHeight: 17, fontWeight: "400", fontFamily: INTER},
  labelLarge: {fontSize: 15, lineHeight: 20, letterSpacing: -0.2, fontWeight: "600", fontFamily: INTER},
  labelMedium: {fontSize: 12, lineHeight: 18, fontWeight: "600", fontFamily: INTER},
  labelSmall: {fontSize: 12, lineHeight: 16, fontWeight: "500", fontFamily: INTER},
  navigation: {fontSize: 11, lineHeight: 14, fontWeight: "600", fontFamily: INTER},
  metric: {fontSize: 25, lineHeight: 28, letterSpacing: -1.2, fontWeight: "700", fontFamily: INTER},
} satisfies Record<string, TextStyle>;
