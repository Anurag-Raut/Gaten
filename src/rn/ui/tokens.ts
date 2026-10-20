/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The global design tokens the Compose screens shared, as plain numbers: the spacing scale, the type
 * scale out of the Kotlin app's own `Type.kt`, the motion constants every transition is built from,
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
  xl: 24,
  xxl: 32,
} as const;

/** Nothing in the app is wider than this, however wide the screen is. */
export const MAX_CONTENT_WIDTH = 840;

/** Two cards are put beside each other from here on; below it a phone has width for one. */
export const TWO_COLUMN_WIDTH = 640;

/** Card elevation everywhere is exactly 1dp, and no card draws a border. */
export const CARD_ELEVATION = 1;

/** The corner radii the Compose theme shipped (GatenShapes: 12/20/28). */
export const shape = {
  small: 12,
  medium: 20,
  large: 28,
} as const;

/** The custom bottom bar's own height, before the bottom safe-area inset is added. */
export const BOTTOM_BAR_HEIGHT = 80;

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

/**
 * The face the Kotlin scale draws every Latin glyph in: `Type.kt`'s `Latin` FontFamily, which is
 * Manrope — bundled for Compose as `res/font/manrope_variable.ttf`.
 *
 * React Native resolves a text's weight against the *family* it names (see `ReactFontManager`), not
 * against a variable font's axis, so the family is published by `res/font/manrope.xml`: one entry
 * per weight the scale asks for (400/500/600), each pinned to the axis value it names. A Japanese
 * glyph is absent from Manrope and falls through to the system face, exactly as it does in Compose.
 */
const MANROPE = "Manrope";

/**
 * The type scale, step for step out of the Kotlin app's own `Type.kt`: one size, weight, line height,
 * letter spacing and face per Material role, exactly as the Compose screens set them.
 *
 * The two display steps name no face because `Type.kt` draws them in the system one: they are always
 * a Japanese word, and a Japanese glyph falls through to the system face from Manrope anyway.
 */
export const typeScale = {
  displayMedium: {fontSize: 40, lineHeight: 48, fontWeight: "500"},
  displaySmall: {fontSize: 34, lineHeight: 44, fontWeight: "500"},
  headlineSmall: {fontSize: 26, lineHeight: 34, fontWeight: "600", fontFamily: MANROPE},
  titleLarge: {fontSize: 24, lineHeight: 32, fontWeight: "600", fontFamily: MANROPE},
  titleMedium: {fontSize: 19, lineHeight: 26, fontWeight: "500", fontFamily: MANROPE},
  titleSmall: {fontSize: 16, lineHeight: 24, fontWeight: "600", fontFamily: MANROPE},
  bodyLarge: {fontSize: 17, lineHeight: 26, fontWeight: "400", fontFamily: MANROPE},
  bodyMedium: {fontSize: 15, lineHeight: 22, fontWeight: "400", fontFamily: MANROPE},
  bodySmall: {fontSize: 13, lineHeight: 18, fontWeight: "400", fontFamily: MANROPE},
  labelLarge: {fontSize: 15, lineHeight: 20, fontWeight: "500", letterSpacing: 0.1, fontFamily: MANROPE},
  labelMedium: {fontSize: 13, lineHeight: 18, fontWeight: "500", letterSpacing: 0.3, fontFamily: MANROPE},
  labelSmall: {fontSize: 12, lineHeight: 16, fontWeight: "500", letterSpacing: 0.4, fontFamily: MANROPE},
} satisfies Record<string, TextStyle>;
