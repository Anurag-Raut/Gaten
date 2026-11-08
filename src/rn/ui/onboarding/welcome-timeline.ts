/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Welcome screen's ten-second loop, as plain numbers: one pure function of time that returns
 * every pose the screen draws, so the RN component and the tests read the same arithmetic and no
 * element can drift from another.
 *
 * The numbers are the handoff prototype's (`reference/landing-timeline.reference.js`), ported
 * exactly — the geometry, the scene boundaries, the easing and the fade windows — while the DOM
 * prototype itself is not. Time [t] runs 0..10, five two-second scenes:
 *
 *  1. 0-2  the reading sheet, なるほど、合点がいった。
 *  2. 2-4  the G button slides in and ripples at 2.8
 *  3. 4-6  the accent ellipse draws around 合点 (4.1-5.0), the word fills 14% at 5.0
 *  4. 6-8  the result card rises (6.0-6.5)
 *  5. 8-10 the pill becomes ✓ Added at 8.4, 合点 arcs into the Anki mark (8.7-9.3), the +1
 *          badge shows at 9.3, and everything fades out from 9.4
 *
 * The loop is the screen's only driver: one Reanimated shared value holding 0..10. Every number
 * here is a pure function of it, so a frame is reproducible without a device.
 */

/** The stage the whole animation is drawn in, in dp, before the screen's own fit-down scale. */
export const STAGE_WIDTH = 316;
export const STAGE_HEIGHT = 372;

/** Five two-second scenes: a ten-second loop. */
export const SCENE_COUNT = 5;
export const SCENE_MS = 2000;
export const LOOP_MS = SCENE_MS * SCENE_COUNT;

/** The one caption per scene, cross-fading at the scene boundaries. */
export const WELCOME_CAPTIONS: readonly string[] = [
  "See a word you don't know",
  "Open Gaten",
  "Circle it",
  "See what it means",
  "Add it to Anki",
];

/** The reading sheet's sentence, split so the word 合点 can be measured on its own. */
export const SENTENCE_BEFORE = "なるほど、";
export const SENTENCE_WORD = "合点";
export const SENTENCE_AFTER = "がいった。";

/** The one label the screen exposes; the stage and the captions are never announced. */
export const WELCOME_LABEL =
  "Gaten looks up words you circle on screen and saves them to Anki.";

/** The result card's own copy, and the pill's two states. */
export const MEANING_LINE = "understanding, consent";
export const READING_LINE = "がてん";
export const ADD_LABEL = "Add to Anki";
export const ADDED_LABEL = "✓ Added";
export const PLUS_LABEL = "+1";

/** Whether the Welcome screen is the state before onboarding page 1. */
export function shouldShowWelcome(step: number): boolean {
  return step === 0;
}

/** Progress of [t] through [a]..[b], clamped to 0..1. */
function seg(t: number, a: number, b: number): number {
  "worklet";
  const x = (t - a) / (b - a);
  return x < 0 ? 0 : x > 1 ? 1 : x;
}

/** Ease-out cubic — the sheet's arrival and the card's rise. */
function easedOut(x: number): number {
  "worklet";
  return 1 - Math.pow(1 - x, 3);
}

/** Ease-in-out cubic — the ellipse's draw and the arcing card. */
function easedInOut(x: number): number {
  "worklet";
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
}

/**
 * The word's measured box, in stage coordinates: its centre and the ellipse's two radii. The
 * ellipse is 14 dp wider than the word and 7 dp taller, and rotated -4 degrees, exactly as the
 * prototype draws it around the DOM span's own bounding box.
 */
export interface WordGeometry {
  cx: number;
  cy: number;
  rx: number;
  ry: number;
  /** The ellipse's own perimeter, for the stroke-dash draw-on. */
  circumference: number;
}

/** A word box [x],[y],[width],[height] in stage coordinates, as the screen measures it. */
export function wordGeometry(x: number, y: number, width: number, height: number): WordGeometry {
  const rx = width / 2 + 14;
  const ry = height / 2 + 7;
  return {cx: x + width / 2, cy: y + height / 2, rx, ry, circumference: ellipseLength(rx, ry)};
}

/** The ellipse's perimeter, by the same Ramanujan approximation the prototype used. */
export function ellipseLength(rx: number, ry: number): number {
  return Math.PI * 2 * Math.sqrt((rx * rx + ry * ry) / 2);
}

/**
 * The word's box before the sentence has been measured: なるほど、 is five full-width glyphs at the
 * sentence's 24 dp, so the word starts at 20 + 5 * 24 and is two full-width glyphs wide. The
 * screen replaces it with the real measurement on the first layout pass; the fallback only covers
 * the frames before that, and the tests.
 */
export const FALLBACK_WORD: WordGeometry = wordGeometry(20 + 5 * 24, 6 + 66, 2 * 24, 34);

/** Every pose the Welcome screen draws at time [t], for the word geometry [word]. */
export interface WelcomeFrame {
  /** The reading sheet and its overlay fade in over 0.3 s and out over 9.7-10.0. */
  sheetOpacity: number;
  /** The sheet's 3.5% dim once the button has arrived (3.0-3.3). */
  dim: number;
  /** The G button: slides in 60 dp from the right over 2-2.4 s, dips on the 2.8 s tap. */
  bubbleOpacity: number;
  bubbleX: number;
  bubbleScale: number;
  /** The tap ripple at 2.8-3.4 s: a ring scaling 1 to 2.1 and fading. */
  rippleOpacity: number;
  rippleScale: number;
  /** The accent ellipse's draw-on (4.1-5.0), dropped once the result card arrives at 6.0. */
  ellipseDraw: number;
  ellipseOpacity: number;
  /** The ring following the stroke tip; gone after 5.3 s. */
  cursorX: number;
  cursorY: number;
  cursorOpacity: number;
  /** The word's own 14% accent fill, from 5.0 s. */
  highlightOpacity: number;
  /** The result card: rises 150 dp over 6.0-6.5 s, fades from 9.4. */
  resultOpacity: number;
  resultY: number;
  /** The Anki pill: pressed at 8.25-8.5, green ✓ Added from 8.4. */
  added: boolean;
  addScale: number;
  /** The Anki mark, in from 8.0-8.3, bumping at 9.2-9.5. */
  ankiOpacity: number;
  ankiScale: number;
  /** The 合点 card arcing into the mark, 8.7-9.3 s. */
  flyOpacity: number;
  flyX: number;
  flyY: number;
  flyScale: number;
  /** The +1 badge, 9.3-9.9 s. */
  plusOpacity: number;
  /** The caption: which of the five, its cross-fade opacity, and its 6 dp lift. */
  captionIndex: number;
  captionOpacity: number;
  captionY: number;
  /** The five progress segments' fill, left to right. */
  segments: number[];
}

/** The prototype's arcing card: from the result card's word up into the Anki mark. */
const FLY_FROM = {x: 130, y: 262};
const FLY_TO = {x: 266, y: 4};
const FLY_LIFT = 60;

/**
 * Every pose at time [t] (0..10) for word geometry [word]. Pure and worklet-safe: the component's
 * animated styles call it on the UI thread with the shared clock, and the tests call it with plain
 * numbers.
 */
export function welcomeFrame(t: number, word: WordGeometry): WelcomeFrame {
  "worklet";
  const sheetOpacity = t < 0.3 ? easedOut(seg(t, 0, 0.3)) : 1 - seg(t, 9.7, 10);
  const dim = 0.035 * easedInOut(seg(t, 3.0, 3.3));
  const bubbleOpacity = t < 2 ? 0 : t < 9.4 ? easedOut(seg(t, 2, 2.4)) : 1 - seg(t, 9.4, 9.7);
  const bubbleIn = easedOut(seg(t, 2, 2.4));
  const bubbleX = (1 - bubbleIn) * 60;
  const bubbleScale = 1 - 0.1 * Math.sin(Math.PI * seg(t, 2.8, 3.05));
  const rippleProgress = seg(t, 2.8, 3.4);
  const rippleOpacity =
    rippleProgress > 0 && rippleProgress < 1 ? 0.6 * (1 - rippleProgress) : 0;
  const rippleScale = 1 + rippleProgress * 1.1;

  const draw = easedInOut(seg(t, 4.1, 5.0));
  const ellipseDraw = t >= 6 ? 0 : draw;
  const ellipseOpacity = t < 4.1 || t >= 9.6 ? 0 : t >= 9.3 ? 1 - seg(t, 9.3, 9.6) : 1;
  const angle = draw * Math.PI * 2 - Math.PI / 2;
  const cursorX = word.cx + word.rx * Math.cos(angle);
  const cursorY = word.cy + word.ry * Math.sin(angle);
  const cursorOpacity = t >= 4.05 && t < 5.3 ? (t < 5.0 ? 1 : 1 - seg(t, 5, 5.3)) : 0;

  const highlightOpacity =
    (t >= 9.3 ? 1 - seg(t, 9.3, 9.6) : t >= 5 ? 1 : 0) * 0.14 * seg(t, 5, 5.3);

  const resultRise = easedOut(seg(t, 6, 6.5));
  const resultOpacity = t < 6 ? 0 : t < 9.4 ? 1 : 1 - seg(t, 9.4, 9.7);
  const resultY = (1 - resultRise) * 150;

  const added = t >= 8.4;
  const addScale = 1 - 0.08 * Math.sin(Math.PI * seg(t, 8.25, 8.5));

  const ankiOpacity = t < 8 ? 0 : t < 9.7 ? easedOut(seg(t, 8, 8.3)) : 1 - seg(t, 9.7, 10);
  const ankiScale = 1 + 0.18 * Math.sin(Math.PI * seg(t, 9.2, 9.5));

  const fly = easedInOut(seg(t, 8.7, 9.3));
  const flyOpacity = t >= 8.6 && t < 9.35 ? 1 : 0;
  const flyX = FLY_FROM.x + (FLY_TO.x - FLY_FROM.x) * fly;
  const flyY =
    FLY_FROM.y + (FLY_TO.y - FLY_FROM.y) * fly - FLY_LIFT * Math.sin(Math.PI * fly);
  const flyScale = 1 - 0.45 * fly;

  const plusOpacity = t >= 9.3 && t < 9.9 ? 1 : 0;

  const captionIndex = Math.min(4, Math.floor(t / 2));
  const phase = (t % 2) / 2;
  const captionOpacity = phase < 0.1 ? phase * 10 : phase > 0.92 ? (1 - phase) * 12.5 : 1;
  const captionY = phase < 0.1 ? (1 - phase * 10) * 6 : 0;

  const segments: number[] = [];
  for (let i = 0; i < SCENE_COUNT; i += 1) {
    segments.push(i < captionIndex ? 1 : i === captionIndex ? phase : 0);
  }

  return {
    sheetOpacity,
    dim,
    bubbleOpacity,
    bubbleX,
    bubbleScale,
    rippleOpacity,
    rippleScale,
    ellipseDraw,
    ellipseOpacity,
    cursorX,
    cursorY,
    cursorOpacity,
    highlightOpacity,
    resultOpacity,
    resultY,
    added,
    addScale,
    ankiOpacity,
    ankiScale,
    flyOpacity,
    flyX,
    flyY,
    flyScale,
    plusOpacity,
    captionIndex,
    captionOpacity,
    captionY,
    segments,
  };
}


/**
 * The frame reduced motion pins: the loop's end state, held still — the result card at rest with
 * its green ✓ Added, the word filled and ringed, the Anki mark with its +1, the last caption and a
 * full progress track. Nothing transitions.
 */
export function welcomeStaticFrame(): WelcomeFrame {
  "worklet";
  return {
    sheetOpacity: 1,
    dim: 0.035,
    bubbleOpacity: 1,
    bubbleX: 0,
    bubbleScale: 1,
    rippleOpacity: 0,
    rippleScale: 1,
    ellipseDraw: 0,
    ellipseOpacity: 0,
    cursorX: 0,
    cursorY: 0,
    cursorOpacity: 0,
    highlightOpacity: 0.14,
    resultOpacity: 1,
    resultY: 0,
    added: true,
    addScale: 1,
    ankiOpacity: 1,
    ankiScale: 1,
    flyOpacity: 0,
    flyX: FLY_TO.x,
    flyY: FLY_TO.y,
    flyScale: 0.55,
    plusOpacity: 1,
    captionIndex: SCENE_COUNT - 1,
    captionOpacity: 1,
    captionY: 0,
    segments: [1, 1, 1, 1, 1],
  };
}

/**
 * The frame [t] draws, with reduced motion pinned to the static state. It exists so every animated
 * style can call ONE function that is itself a worklet: the styles run on the UI runtime, and a
 * closure handed back from a hook is not one — calling it there throws
 * "Tried to synchronously call a Remote Function". Everything it reads is a plain argument.
 *
 * It is declared after both frame functions it calls, on purpose: the worklets Babel plugin captures
 * a closure array at the definition site, so a worklet declared before its callee would capture
 * `undefined` — which is a TypeError at runtime only when the reduced-motion branch is taken.
 */
export function welcomeFrameFor(t: number, reduced: boolean, word: WordGeometry): WelcomeFrame {
  "worklet";
  return reduced ? welcomeStaticFrame() : welcomeFrame(t, word);
}
