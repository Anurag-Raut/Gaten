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
import type {TextStyle, ViewStyle} from "react-native";

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

/**
 * The house horizontal content inset in dp, from the screen's own width: the usual
 * [spacing.contentInset] (22), tightened to [spacing.l] (16) on phones narrower than 360 dp.
 */
export function contentInsetFor(width: number): number {
  return width < 360 ? spacing.l : spacing.contentInset;
}

/** Nothing in the app is wider than this, however wide the screen is. */
export const MAX_CONTENT_WIDTH = 560;

/**
 * The shared content column: as wide as its parent up to the [MAX_CONTENT_WIDTH] cap, centred. It
 * carries no padding of its own, so a consumer adds the horizontal inset (see [contentInsetFor])
 * and any surrounding rhythm it needs.
 */
export const CONTENT_COLUMN: ViewStyle = {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"};

/** Two cards are put beside each other from here on; below it a phone has width for one. */
export const TWO_COLUMN_WIDTH = 640;

/** Content surfaces stay flat; dividers and spacing establish grouping. */
export const CARD_ELEVATION = 0;

/** The floating bottom bar is the one surface that lifts off the page, so it reads as hovering
 * over the content it covers. */
export const FLOATING_BAR_ELEVATION = 8;

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

/**
 * The leaving curve: Material's "accelerate" (0.4, 0, 1, 1). Chrome that shares a frame with a page
 * cannot pop out of it, so what leaves — the bottom bar as a page covers it, a sheet being dismissed —
 * takes this one, and what arrives takes [PAGE_EASING_REANIMATED]'s decelerating curve instead.
 */
export const EXIT_EASING_REANIMATED = ReanimatedEasing.bezier(0.4, 0, 1, 1);

/**
 * The bundled face the whole app is drawn in, Latin and Japanese alike. IBM Plex Sans JP carries
 * IBM Plex's Latin design alongside the Japanese glyphs, so a mixed string stays in one face with no
 * per-script split — the same arrangement the Compose type scale uses
 * (`app/src/main/java/com/gaten/app/ui/theme/Type.kt`). A glyph the face does not carry still falls
 * back to the platform's own, as any font does.
 * The name is registered in `MainApplication`; it is not a system family.
 */
export const APP_FONT = "IBM Plex Sans JP";

/**
 * Lettering for a kanji the app draws as a page's own anchor — the WaniKani review's hero
 * character, the reference page's hero character and its component chips. One weight for all of
 * them, so a character never changes weight as the reader moves between the surfaces that show it;
 * the size stays each surface's own. The kanji chips and the Anki answer's headword already wear
 * the same weight through `typeScale.displaySmall`/`displayMedium`.
 */
export const KANJI_LETTERING = {fontWeight: "500", fontFamily: APP_FONT} as const;

/** Shared text roles from docs/Gaten_Ink_Design_Language_v1.md. Display roles retain Japanese reading sizes. */
export const typeScale = {
  displayMedium: {fontSize: 40, lineHeight: 48, fontWeight: "500", fontFamily: APP_FONT},
  displaySmall: {fontSize: 34, lineHeight: 44, fontWeight: "500", fontFamily: APP_FONT},
  brand: {fontSize: 35, lineHeight: 38, letterSpacing: -1.85, fontWeight: "700", fontFamily: APP_FONT},
  headlineSmall: {fontSize: 35, lineHeight: 44, letterSpacing: -1.85, fontWeight: "700", fontFamily: APP_FONT},
  titleLarge: {fontSize: 17, lineHeight: 22, letterSpacing: -0.57, fontWeight: "700", fontFamily: APP_FONT},
  titleMedium: {fontSize: 15, lineHeight: 20, letterSpacing: -0.45, fontWeight: "700", fontFamily: APP_FONT},
  titleSmall: {fontSize: 15, lineHeight: 20, letterSpacing: -0.45, fontWeight: "700", fontFamily: APP_FONT},
  bodyLarge: {fontSize: 14, lineHeight: 20, fontWeight: "400", fontFamily: APP_FONT},
  bodyMedium: {fontSize: 14, lineHeight: 20, fontWeight: "400", fontFamily: APP_FONT},
  description: {fontSize: 13, lineHeight: 19, fontWeight: "400", fontFamily: APP_FONT},
  bodySmall: {fontSize: 12, lineHeight: 17, fontWeight: "400", fontFamily: APP_FONT},
  labelLarge: {fontSize: 15, lineHeight: 20, letterSpacing: -0.2, fontWeight: "600", fontFamily: APP_FONT},
  labelMedium: {fontSize: 12, lineHeight: 18, fontWeight: "600", fontFamily: APP_FONT},
  labelSmall: {fontSize: 12, lineHeight: 16, fontWeight: "500", fontFamily: APP_FONT},
  navigation: {fontSize: 11, lineHeight: 14, fontWeight: "600", fontFamily: APP_FONT},
  metric: {fontSize: 25, lineHeight: 28, letterSpacing: -1.2, fontWeight: "700", fontFamily: APP_FONT},
  /** A learner-facing value — the meaning or the reading — drawn in the reader's accent colour wherever it is shown. */
  valueLarge: {fontSize: 20, lineHeight: 28, fontWeight: "600", fontFamily: APP_FONT},
  /** The kana line under a headword. */
  readingLine: {fontSize: 17, lineHeight: 24, fontWeight: "400", fontFamily: APP_FONT},
  /** Small print: notes, provenance, the foot of a panel. */
  micro: {fontSize: 11, lineHeight: 15, fontWeight: "400", fontFamily: APP_FONT},
  /** Uppercase tracked labels, as analytics draws them; the caller adds `textTransform`. */
  eyebrow: {fontSize: 11, lineHeight: 14, letterSpacing: 1.2, fontWeight: "700", fontFamily: APP_FONT},
  /** State and narrative titles — the screens' 21/22/23 sp headings. */
  headline: {fontSize: 22, lineHeight: 28, letterSpacing: -0.7, fontWeight: "700", fontFamily: APP_FONT},
  /** The single-kanji hero of a lesson or review. */
  glyphHero: {fontSize: 64, lineHeight: 80, fontWeight: "500", fontFamily: APP_FONT},
  /** Sentence-size Japanese. */
  glyphMedium: {fontSize: 28, lineHeight: 36, fontWeight: "500", fontFamily: APP_FONT},
  /** The analytics figure that anchors a screen. */
  metricHero: {fontSize: 34, lineHeight: 38, letterSpacing: -1.5, fontWeight: "700", fontFamily: APP_FONT},
  /** Home's due count — the documented Home override. */
  homeCount: {fontSize: 24, lineHeight: 24, letterSpacing: -0.9, fontWeight: "700", fontFamily: APP_FONT},
  /** Home's streak count — the documented Home override. */
  homeStreak: {fontSize: 20, lineHeight: 22, fontWeight: "500", fontFamily: APP_FONT},
  /** Home's empty-history caption — the documented Home override. */
  homeCaption: {fontSize: 13, lineHeight: 18, fontWeight: "700", fontFamily: APP_FONT},
} satisfies Record<string, TextStyle>;
