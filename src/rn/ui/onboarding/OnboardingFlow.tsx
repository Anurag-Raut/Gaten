/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run setup flow: three pages, one per thing a reader sets up before the app is theirs —
 * lookup mode, AnkiDroid, WaniKani.
 *
 * The shell shows it in place of the tabs while the stored onboarding step is below 3, and it is
 * shown on the first launch only. Each page does, or hands off to, the real setup for its item and
 * reports the real status; each has its own Skip, and there is deliberately no "skip all" — the
 * reader skips one page at a time, and the last page's Skip finishes the flow. Nothing here creates,
 * deletes or changes any Anki or WaniKani data: a page reads what is already there and, at most,
 * opens the app that owns it.
 *
 * The shell is one persistent frame — the back row, the progress row, the artwork scene and the
 * scroller are mounted once and never swapped — and the three pages are controllers that fill it.
 * A page owns the setup state for its item (the lookup grant, the AnkiDroid read, the WaniKani
 * token) and hands the shell a plain view of what to show; the shell draws the frame, the artwork
 * and the content, so a step change moves ONE scene rather than replacing one page with another.
 *
 * The shell owns the step, exactly as before: a page advances by writing the next step first, and
 * the shell shows the next page once that write lands. A write that cannot be saved keeps the page
 * and offers a retry rather than silently skipping the reader forward.
 */
import React, {useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from "react";
import {
  AccessibilityInfo,
  AppState,
  BackHandler,
  findNodeHandle,
  I18nManager,
  ScrollView,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {SafeAreaView, useSafeAreaInsets} from "react-native-safe-area-context";
import Animated, {
  cancelAnimation,
  Extrapolation,
  interpolate,
  runOnJS,
  type SharedValue,
  useAnimatedReaction,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withSpring,
  withTiming,
} from "react-native-reanimated";
import {Gesture, GestureDetector} from "react-native-gesture-handler";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {WaniKaniFailure} from "../../native/GatenWaniKani";
import {downloadAnkiDroid} from "../anki/anki-actions";
import {Button, IconButton} from "../components";
import {WaniKaniConnect} from "../review/WaniKaniConnect";
import type {WaniKaniConnectOutcome} from "../review/review-store";
import {useLookupModeRequest} from "../settings/settings-components";
import {useTheme} from "../theme";
import {
  CONTENT_COLUMN,
  MAX_CONTENT_WIDTH,
  PAGE_EASING_REANIMATED,
  contentInsetFor,
  spacing,
  typeScale,
} from "../tokens";
import {OnboardingScene, SCENE_WIDTH} from "./OnboardingArtwork";

/**
 * Each page's own copy, shared by its live controller and by the swipe's pure previews, so a peer's
 * heading and explanation are never duplicated and a preview can never say something the real page
 * does not. [name] is the step's short name, for announcements.
 */
const PAGES = [
  {
    name: "Look up mode",
    heading: "Look up Japanese anywhere",
    explanation:
      "Look up Japanese words and kanji while you read in other apps. Enable lookup mode to get started.",
  },
  {
    name: "Anki",
    heading: "Study with Anki",
    explanation:
      "Connect AnkiDroid to review your decks and save words from your lookups as flashcards.",
  },
  {
    name: "WaniKani",
    heading: "Connect WaniKani",
    explanation:
      "Connect your WaniKani account to review kanji and vocabulary alongside your other study.",
  },
] as const;

/** The scene clock's three stops, one per page. */
const STOPS: number[] = [0, 1, 2];

/**
 * The step transition, in ms. Longer than the review screen's 260 ms page slide: this one carries a
 * whole scene — the card's pose, the deck, the torii, the progress bar and the content — so the eye
 * needs a beat more to read it as one action. Inside the brief's 300-380 ms window.
 */
const STEP_SCENE_MS = 340;

/** The WaniKani token form's own enter and exit. */
const FORM_MS = 300;

/** The one header row: the bare back target's own 48 dp, and the gap it leaves before the artwork. */
const HEADER_ROW_HEIGHT = 48;
const HEADER_GAP = 10;

/**
 * The step swipe. A pan has to travel [SWIPE_ACTIVATE_X] dp sideways to take the gesture, and gives
 * it up as soon as it moves [SWIPE_FAIL_Y] dp vertically, so the page's own scrolling keeps working.
 * A release commits at [SWIPE_COMMIT_FRACTION] of the window (capped at [SWIPE_COMMIT_MAX] dp) or on
 * a [SWIPE_FLICK_VELOCITY] dp/s flick that still travelled [SWIPE_FLICK_TRAVEL] dp; the artwork and
 * the copy preview [SWIPE_PREVIEW_REACH] of the adjacent pose under the finger.
 *
 * The settle is a real spring on the same scene clock, so the distance left and the finger's release
 * speed both decide it and a tiny tail never pays a fresh 340 ms. Velocity is in scene units per
 * second; ζ = 45 / (2√(500·1)) ≈ 1.006 — just overdamped, with overshoot clamping on, so it settles
 * without leaving the 0..2 band. Buttons keep their withTiming curve unchanged.
 */
const SWIPE_ACTIVATE_X = 18;
const SWIPE_FAIL_Y = 12;
const SWIPE_COMMIT_FRACTION = 0.22;
const SWIPE_COMMIT_MAX = 96;
const SWIPE_PREVIEW_REACH = 0.85;
const SWIPE_FLICK_VELOCITY = 650;
const SWIPE_FLICK_TRAVEL = 20;
const SWIPE_SPRING = {mass: 1, stiffness: 500, damping: 45, overshootClamping: true} as const;

/** The swipe's session roles. */
const SWIPE_IDLE = 0;
/** The finger owns the scene; the copy's fraction comes from the scene map. */
const SWIPE_DRAG = 1;
/** The release's dimensionless progress owns the scene *and* the copy; this page is leaving. */
const SWIPE_SETTLE = 2;
/** The same settle progress, but this page is the committed target: its copy is arriving. */
const SWIPE_ARRIVE = 3;

/**
 * A swipe's pose: one physical driver and linear maps of it.
 *
 * While the finger is down the driver is `scene` itself and the copy's fraction is
 * `clamp(slope·scene + intercept, −1, 1)` — slope 1 / intercept −sceneAtTouch, so a gesture that
 * begins mid-transition starts at the pose actually on screen. At release the driver becomes `q`
 * (0→1, one spring), the scene is a lerp of it (`sceneFrom`→`sceneTo`) and the same slope/intercept
 * now map `q`; so the art and the copy can never finish at different times, and a settle whose art
 * span is zero still completes the copy.
 *
 * `role` is the session state (idle / drag / settle / arrive) and `base*` is the leaving page's pose
 * captured at takeover, eased to rest by `q` — so taking over an arriving page never pops its
 * heading and a cancelled swipe still ends at rest.
 */
interface SwipePose {
  scene: SharedValue<number>;
  q: SharedValue<number>;
  role: SharedValue<number>;
  slope: SharedValue<number>;
  intercept: SharedValue<number>;
  sceneFrom: SharedValue<number>;
  sceneTo: SharedValue<number>;
  baseOpacity: SharedValue<number>;
  baseX: SharedValue<number>;
  baseOwn: SharedValue<number>;
}

/** The copy's signed fraction of a page, read from whichever clock currently owns the session. */
function swipeFraction(pose: SwipePose): number {
  "worklet";
  const role = pose.role.value;
  if (role === SWIPE_IDLE) {
    return 0;
  }
  const clock = role === SWIPE_DRAG ? pose.scene.value : pose.q.value;
  return Math.max(-1, Math.min(1, pose.slope.value * clock + pose.intercept.value));
}

/** The compact progress track: three 32 dp segments 6 dp apart, 3 dp thick — a bar, not a banner. */
const TRACK_SEGMENT = 32;
const TRACK_GAP = 6;
const TRACK_THICKNESS = 3;
const TRACK_WIDTH = 3 * TRACK_SEGMENT + 2 * TRACK_GAP;

/**
 * The text handoff, on the content clock: the outgoing heading/explanation/status leaves over the
 * first [TEXT_EXIT_BY] of the clock and is gone by then, the incoming starts at [TEXT_ENTER_AT], and
 * both travel [TEXT_TRAVEL] dp. The two windows never overlap, so two headings are never readable at
 * once during a step change.
 */
const TEXT_TRAVEL = 28;
/** The WaniKani form's own rise: the page leaves and the form arrives over the same travel. */
const FORM_TRAVEL = 24;

/**
 * A handoff's phasing on a 0→1 clock, shared by the step text and the WaniKani form: the outgoing is
 * gone inside the first [PHASE_EXIT_BY], the incoming does not appear until [PHASE_ENTER_AT], and the
 * same two formulas run backwards when the clock runs back, so nothing is ever readable twice.
 */
const PHASE_EXIT_BY = 0.28;
const PHASE_ENTER_AT = 0.3;

/** The house easing for a handoff's own phase: smoothstep on a 0..1 progress. */
function easedPhase(t: number): number {
  "worklet";
  return t * t * (3 - 2 * t);
}

/**
 * Where live body text stands at content-clock [q]: silent until [PHASE_ENTER_AT], then eased from
 * [TEXT_TRAVEL] dp out to rest by 1. Pure and shared, so a captured outgoing pose and a live
 * incoming pose are the same function of the same clock.
 */
function textArrival(q: number, direction: number): {opacity: number; x: number} {
  "worklet";
  if (q <= PHASE_ENTER_AT) {
    return {opacity: 0, x: direction * TEXT_TRAVEL};
  }
  const eased = easedPhase(Math.min(1, (q - PHASE_ENTER_AT) / (1 - PHASE_ENTER_AT)));
  return {opacity: eased, x: direction * TEXT_TRAVEL * (1 - eased)};
}

/**
 * Where outgoing body text stands at clock [p]: from the pose its own arrival had reached
 * ([fromProgress], [fromDirection]) to gone, inside [TEXT_EXIT_BY] of the clock.
 */
function textExit(
  fromProgress: number,
  fromDirection: number,
  direction: number,
  p: number,
): {opacity: number; x: number} {
  "worklet";
  const start = textArrival(fromProgress, fromDirection);
  const t = Math.min(1, p / PHASE_EXIT_BY);
  const endX = -direction * TEXT_TRAVEL;
  return {opacity: start.opacity * (1 - t), x: start.x + (endX - start.x) * t};
}

/**
 * Where one page's narrative copy stands. Three fixed copies are drawn for the whole flow — one per
 * [PAGES] entry — so a step change or a swipe only re-poses them: no copy is mounted, unmounted or
 * re-identified.
 *
 * A swipe session poses every copy on the artwork's own page coordinate, at full opacity, so the
 * text under the finger is always whole and a takeover is just the scene it finds. The entry/exit
 * curtain below is the button path's alone: its copy enters on the content clock as the body used
 * to, and the copy being left exits on it.
 *
 * Declared after the constants and helpers it captures: a worklet's closure is built when its
 * factory runs, and a helper declared later is still unassigned at that moment.
 */
function narrativePose(
  index: number,
  step: number,
  from: number,
  fromProgress: number,
  fromDirection: number,
  direction: number,
  contentProgress: SharedValue<number>,
  swipePose: SwipePose,
  entering: boolean,
  outgoing: boolean,
  swipeDistance: number,
): {opacity: number; x: number} {
  "worklet";
  const role = swipePose.role.value;
  if (role !== SWIPE_IDLE) {
    // A session: every copy rides the artwork's own page coordinate — one global scene, one
    // full-width step — at full opacity, so the text under the finger is always whole. No curtain,
    // no exit/arrival and no source/target identity: a takeover is just the scene it finds.
    return {opacity: 1, x: (index - swipePose.scene.value) * swipeDistance};
  }
  if (index === step) {
    return entering ? textArrival(0, direction) : textArrival(contentProgress.value, direction);
  }
  if (outgoing && index === from) {
    return textExit(fromProgress, fromDirection, direction, contentProgress.value);
  }
  return {opacity: 0, x: direction * TEXT_TRAVEL};
}

/** One action the footer can offer: a filled primary, or a quiet outlined alternative. */
interface Action {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}

/**
 * What a page hands the shell to draw: its own status and its actions. The heading and explanation
 * are the shell's fixed copies, so a page cannot restyle or swap them. Plain data, so the shell can
 * keep the previous page's view as a purely visual outgoing layer — rendering it again runs no page
 * logic and makes no second native call.
 */
interface StepVisual {
  /** The page's live status, drawn by the shell's fixed copy under its explanation. */
  status: React.ReactNode;
  /**
   * A semantic key for [status]: the controller state the node reads, not the node itself. The shell
   * caches one status per page and replaces it only when this key changes, so a re-report never
   * re-renders and a leaving copy keeps the status it had while its own text hands over.
   */
  statusKey: string;
  primary: Action | null;
  outlined: Action | null;
  skipLabel: string;
  skipAccessibilityLabel: string;
  onSkip: () => void;
  saveError: boolean;
  onRetry: () => void;
}

/**
 * The two actions a view draws: the filled one is its primary, or — when the page has no primary —
 * the outlined action it does have, so the filled slot is never empty and no placeholder is invented.
 * The quiet alternative is the outlined one only when it sits beside a real primary.
 */
function actionsFor(visual: StepVisual): {primary: Action | null; alternative: Action | null} {
  return visual.primary != null
    ? {primary: visual.primary, alternative: visual.outlined}
    : {primary: visual.outlined, alternative: null};
}

/**
 * A key for the action area alone — the committed step plus everything the shell's buttons draw. The
 * shell re-renders on this and never on a page's whole view, so the buttons are not remounted, and do
 * not lose their surface, just because a page re-reported its status. The step is in the key so two
 * pages that happen to share a label and a disabled state still hand over a fresh handler.
 */
function actionAreaKey(step: number, visual: StepVisual): string {
  const {primary, alternative} = actionsFor(visual);
  return [
    `step:${step}`,
    primary == null ? "" : `${primary.label}:${primary.disabled ? 1 : 0}`,
    alternative == null ? "" : `${alternative.label}:${alternative.disabled ? 1 : 0}`,
    visual.saveError ? "error" : "",
    visual.skipLabel,
    visual.skipAccessibilityLabel,
  ].join("|");
}

/** What every page is handed by the flow: where it is, and how to fill the frame. */
interface PageProps {
  step: number;
  /** Asks the shell for the next step; it writes first and shows the page once that lands. */
  onAdvance: (next: number) => void;
  /** True while a write is still failing, so the page offers its retry instead of advancing. */
  saveError: boolean;
  onRetry: () => void;
  /** Hands the shell this page's view; the shell keeps the last one for the outgoing layer. */
  report: (visual: StepVisual) => void;
}

/** The artwork's full width, and the width the chrome caps it at in the middle mode. */
const ARTWORK_WIDTH = SCENE_WIDTH;
const ARTWORK_CAPPED_WIDTH = 248;

/** How the page's hero is drawn for the current window: full size, capped, or left out entirely. */
type ArtworkMode = "hidden" | "capped" | "normal";

/**
 * The artwork mode for a window, in the brief's order: a large text size or a short viewport drops
 * the artwork entirely; the next step down caps its width. [regionHeight] is the height left for the
 * scene and the scroller together, which is what the artwork is actually competing for.
 */
function artworkModeFor(fontScale: number, regionHeight: number): ArtworkMode {
  if (fontScale >= 1.6 || regionHeight < 400) return "hidden";
  if (fontScale >= 1.3 || regionHeight < 620) return "capped";
  return "normal";
}

/**
 * The onboarding narrative: a 22sp/28 heading that keeps titleLarge's face, weight and tracking, and
 * a 14sp/20 centred explanation capped at [NARRATIVE_WIDTH] dp so it reads as two or three lines on a
 * normal phone. Everything here is onboarding-only — the app's other text and the artwork titles are
 * untouched — and it stays in the scroller's normal flow, so large text wraps instead of clipping.
 */
const NARRATIVE_WIDTH = 320;
const NARRATIVE_HEADING = {fontSize: 22, lineHeight: 28, textAlign: "center" as const};
const NARRATIVE_LINE = {textAlign: "center" as const};
const NARRATIVE_BODY = {
  marginTop: spacing.s,
  maxWidth: NARRATIVE_WIDTH,
  alignSelf: "center" as const,
  textAlign: "center" as const,
};
const NARRATIVE_STATUS = {
  marginTop: spacing.l,
  maxWidth: NARRATIVE_WIDTH,
  alignSelf: "center" as const,
};
/** The narrative slot: the live narrative's own top offset and centring, in the scroller's flow. */
const NARRATIVE_SLOT = {
  alignItems: "center" as const,
  marginTop: spacing.l,
};
/**
 * One copy's box: the full column width and centred, so the copy in flow and the copies laid over
 * it measure and wrap identically — switching a copy between the two never reflows its text.
 */
const NARRATIVE_BOX = {
  width: "100%" as const,
  alignItems: "center" as const,
};
/**
 * A copy that is not on the committed step: laid over the slot's own copy at the same box, so the
 * slot keeps the height of real, in-flow content and never jumps when the copy changes hands.
 */
const NARRATIVE_OVERLAY = {
  ...NARRATIVE_BOX,
  position: "absolute" as const,
  top: 0,
  left: 0,
  right: 0,
};

/**
 * One page's heading and explanation, drawn by the shell for the whole flow: one fixed copy per
 * [PAGES] entry, so the copy's identity and text nodes survive every step change, swipe and commit.
 * The committed page's copy is the one in flow — it sets the slot's height, so large text and the
 * scroller still lay out from real content — and the others are laid over it in the same slot.
 */
function NarrativeCopy({
  index,
  step,
  from,
  fromProgress,
  fromDirection,
  outgoing,
  direction,
  entering,
  contentProgress,
  swipePose,
  headingRef,
  status,
  swipeDistance,
}: {
  index: number;
  step: number;
  from: number;
  fromProgress: number;
  fromDirection: number;
  outgoing: boolean;
  direction: number;
  entering: boolean;
  contentProgress: SharedValue<number>;
  swipePose: SwipePose;
  headingRef: React.RefObject<Text | null>;
  /** This page's own cached status, drawn under its explanation and posed with it. */
  status: React.ReactNode;
  /** One full page step in dp: the copy's horizontal page coordinate during a swipe. */
  swipeDistance: number;
}) {
  const {colors} = useTheme();
  const live = index === step;
  const copyStyle = useAnimatedStyle(() => {
    const pose = narrativePose(
      index,
      step,
      from,
      fromProgress,
      fromDirection,
      direction,
      contentProgress,
      swipePose,
      entering,
      outgoing,
      swipeDistance,
    );
    return {opacity: pose.opacity, transform: [{translateX: pose.x}]};
  });
  return (
    <Animated.View
      style={[live ? NARRATIVE_BOX : NARRATIVE_OVERLAY, copyStyle]}
      pointerEvents="none"
      accessibilityElementsHidden={!live}
      importantForAccessibility={live ? "auto" : "no-hide-descendants"}
    >
      <Text
        ref={live ? headingRef : undefined}
        accessibilityRole="header"
        accessibilityLabel={`${PAGES[index].heading}. Step ${index + 1} of 3.`}
        style={[typeScale.titleLarge, NARRATIVE_HEADING, {color: colors.onSurface}]}
      >
        {PAGES[index].heading}
      </Text>
      <Text style={[typeScale.bodyMedium, NARRATIVE_BODY, {color: colors.onSurfaceVariant}]}>
        {PAGES[index].explanation}
      </Text>
      {status != null && <View style={NARRATIVE_STATUS}>{status}</View>}
    </Animated.View>
  );
}

/**
 * The flow's one action area, drawn by the shell outside every page: it is the same element for the
 * whole flow, so a step change — or a swipe — only ever swaps a label, a disabled state and what the
 * press does. The filled slot is the page's primary, or its outlined action when that is the only one
 * it has, so the surface is never empty; the filled action and the Skip are the last two slots, and a
 * caption or a quiet alternative sits above them. The primary keeps its fill while a page is checking,
 * and the buttons sit at the end of the page's own scroller, so large text and short screens still
 * reach them.
 */
function StepActions({visual, busy}: {visual: StepVisual | null; busy: boolean}) {
  const {colors} = useTheme();
  if (visual == null) {
    return null;
  }
  const {primary, alternative} = actionsFor(visual);
  return (
    <View style={{gap: spacing.s, marginTop: spacing.xxl}}>
      {/* Every child is keyed so the buttons keep their identity — and their surface — when a
          neighbour appears or goes, rather than being remounted in a new slot. */}
      {visual.saveError && (
        <Text
          key="save-error"
          accessibilityLiveRegion="polite"
          style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.error}]}
        >
          Couldn't save setup progress. Try again.
        </Text>
      )}
      {alternative != null && (
        <Button
          key="alternative"
          variant="outlined"
          label={alternative.label}
          onPress={alternative.onPress}
          disabled={alternative.disabled || busy}
          fullWidth
        />
      )}
      {visual.saveError ? (
        <Button
          key="primary"
          label="Try again"
          onPress={visual.onRetry}
          disabled={busy}
          fullWidth
          surfaceWhileDisabled
        />
      ) : (
        primary != null && (
          <Button
            key="primary"
            label={primary.label}
            onPress={primary.onPress}
            disabled={primary.disabled || busy}
            fullWidth
            surfaceWhileDisabled
          />
        )
      )}
      <Button
        key="skip"
        variant="text"
        label={visual.skipLabel}
        accessibilityLabel={visual.skipAccessibilityLabel}
        onPress={visual.onSkip}
        disabled={busy}
        fullWidth
      />
    </View>
  );
}

/**
 * The three-step progress track: three fixed quiet segments with one accent bar that sweeps to the
 * live step on the scene clock — the same travelling indicator, at a header's size.
 */
function ProgressTrack({scene}: {scene: SharedValue<number>}) {
  const {colors} = useTheme();
  const barStyle = useAnimatedStyle(() => ({
    transform: [
      {
        translateX: interpolate(
          scene.value,
          STOPS,
          [0, TRACK_SEGMENT + TRACK_GAP, 2 * (TRACK_SEGMENT + TRACK_GAP)],
          Extrapolation.CLAMP,
        ),
      },
    ],
  }));
  return (
    <View style={{width: TRACK_WIDTH, height: TRACK_THICKNESS}}>
      <View style={{flexDirection: "row", gap: TRACK_GAP}}>
        {[0, 1, 2].map((index) => (
          <View
            key={index}
            style={{
              width: TRACK_SEGMENT,
              height: TRACK_THICKNESS,
              borderRadius: TRACK_THICKNESS / 2,
              backgroundColor: colors.outlineVariant,
            }}
          />
        ))}
      </View>
      <Animated.View
        style={[
          {
            position: "absolute",
            left: 0,
            top: 0,
            width: TRACK_SEGMENT,
            height: TRACK_THICKNESS,
            borderRadius: TRACK_THICKNESS / 2,
            backgroundColor: colors.accent,
          },
          barStyle,
        ]}
      />
    </View>
  );
}

/**
 * The first-run setup flow. [step] is the page to show (0, 1 or 2); the shell reads it before the
 * tabs mount and writes it back through [onAdvance], which resolves `false` when the step could not
 * be saved.
 */
export function OnboardingFlow({
  step,
  onAdvance,
}: {
  step: number;
  onAdvance: (next: number) => Promise<boolean>;
}) {
  const headingRef = useRef<Text>(null);
  const [saveError, setSaveError] = useState(false);
  // The action area the shell draws, kept here so it survives every page swap.
  const [actions, setActions] = useState<StepVisual | null>(null);
  // One live status per page, keyed by the controller state it reads, so the fixed copies can draw
  // their own status and a handover poses it exactly like the heading.
  const [statuses, setStatuses] = useState<Record<number, {key: string; node: React.ReactNode}>>({});
  // The key of the view [actions] currently holds, so a report that would draw the same buttons is
  // dropped — and the committed step stays in the comparison instead of cancelling itself out.
  const reportedActionKey = useRef<string | null>(null);
  // Held true for exactly the commit a step change lands on, so the live text paints its entering
  // pose while the clock's reset is still on its way to the UI thread.
  const [entering, setEntering] = useState(false);
  // The WaniKani token form rises over the WaniKani page; the flow owns the flag so Back can close
  // it before it means "the previous page". The form is mounted on its first open and then kept —
  // its token, message and scroll position are the reader's work, and closing the form must not
  // throw them away.
  const [formOpen, setFormOpen] = useState(false);
  const [formMounted, setFormMounted] = useState(false);
  // True when the form's own rise or fall has finished. The page's actions are handed back only once
  // the page is really on screen again, never while it is still invisible under the form.
  const [formSettled, setFormSettled] = useState(true);
  const formRun = useRef(0);
  const pending = useRef(step);

  /**
   * The one persistence write the flow may have in flight. Every way forward and back goes through
   * [startWrite]: a step is saved, and only when that save has actually landed does another one
   * start. That is what keeps rapid Next/Back from saving two steps out of order — the native write
   * and the step the shell shows are the same write, so there is no second one to race it. The ref
   * is the synchronous gate (a second tap in the same frame must not get through); the state is what
   * the frame uses to hold its actions while the save is pending.
   */
  const persisting = useRef(false);
  const [writePending, setWritePending] = useState(false);

  const write = useCallback(
    (next: number, onResult?: (saved: boolean) => void) => {
      if (persisting.current) {
        onResult?.(false);
        return;
      }
      persisting.current = true;
      setWritePending(true);
      pending.current = next;
      setSaveError(false);
      void onAdvance(next)
        .then((saved) => {
          if (!saved) setSaveError(true);
          onResult?.(saved);
        })
        // `onAdvance` answers `false` for a failed save rather than throwing; a rejection that still
        // gets here is the same failure and must not leave the page silently unable to move.
        .catch(() => {
          setSaveError(true);
          onResult?.(false);
        })
        .finally(() => {
          persisting.current = false;
          setWritePending(false);
        });
    },
    [onAdvance],
  );

  const retry = useCallback(() => write(pending.current), [write]);

  const reducedMotion = useReducedMotion();
  // One clock for the whole scene. It holds the page the artwork is currently drawn at, in page
  // units, and is the only thing the artwork reads.
  const scene = useSharedValue(step);
  const entrance = useSharedValue(reducedMotion ? 1 : 0);
  const formProgress = useSharedValue(formOpen ? 1 : 0);
  // The content's own clock: 0 at a step change, 1 when the new page has settled. Kept separate
  // from the scene so the content can trail the artwork by a beat.
  const contentProgress = useSharedValue(1);
  // The artwork's real-status accent: 1 only while a WaniKani account is really connected, as the
  // page's own native read reports it. Nothing else draws it.
  const success = useSharedValue(0);
  const [wanikaniConnected, setWanikaniConnected] = useState(false);
  // Bumped when the embedded form stores a working token, so the WaniKani page re-reads the real
  // store rather than trusting the form's own outcome.
  const [connectRevision, setConnectRevision] = useState(0);

  // The swipe's session. The gesture owns [scene] from activation, and every narrative pose is the
  // swipe pose's fraction of that same scene, so the artwork, the track and the copy share one
  // clock. A session ends only when the visual has settled AND, for an accepted swipe, the saved
  // step has actually been observed as the `step` prop — a resolved promise alone is not proof.
  const swipeActive = useSharedValue(0);
  const swipeSlope = useSharedValue(1);
  const swipeIntercept = useSharedValue(0);
  const swipeQ = useSharedValue(0);
  const swipeSceneFrom = useSharedValue(step);
  const swipeSceneTo = useSharedValue(step);
  const swipeBaseOpacity = useSharedValue(1);
  const swipeBaseX = useSharedValue(0);
  const swipeBaseOwn = useSharedValue(0);
  const swipePose = useMemo<SwipePose>(
    () => ({
      scene,
      q: swipeQ,
      role: swipeActive,
      slope: swipeSlope,
      intercept: swipeIntercept,
      sceneFrom: swipeSceneFrom,
      sceneTo: swipeSceneTo,
      baseOpacity: swipeBaseOpacity,
      baseX: swipeBaseX,
      baseOwn: swipeBaseOwn,
    }),
    [
      scene,
      swipeQ,
      swipeActive,
      swipeSlope,
      swipeIntercept,
      swipeSceneFrom,
      swipeSceneTo,
      swipeBaseOpacity,
      swipeBaseX,
      swipeBaseOwn,
    ],
  );
  const swipeTarget = useRef<number | null>(null);
  const swipeVisualSettled = useRef(false);
  const swipeStepSeen = useRef(false);
  const swipeOwnsClock = useRef(false);

  const finishSwipe = useCallback(() => {
    swipeTarget.current = null;
    swipeVisualSettled.current = false;
    swipeStepSeen.current = false;
    swipeOwnsClock.current = false;
    // Ending the session is a no-op on the copy's pose — it reads its settled end either way — and it
    // is a single UI-thread write, so no React flip can race it into a blank frame.
    swipeActive.value = SWIPE_IDLE;
    swipeBaseOwn.value = 0;
  }, [swipeActive, swipeBaseOwn]);

  // The gesture's settle reported. An accepted swipe waits for its step to be observed; a cancelled
  // one is finished the moment it lands.
  const swipeSettled = useCallback(
    (accepted: boolean) => {
      swipeVisualSettled.current = true;
      if (!accepted || swipeStepSeen.current) finishSwipe();
    },
    [finishSwipe],
  );

  // A new touch takes the session over: the spring it cancelled can never report, so only the
  // flow's own bookkeeping is reset here — never the pose's `active`, which onStart has just armed.
  // A commit that is still pending keeps its target, so its handover still lands correctly.
  const swipeStarted = useCallback(() => {
    swipeVisualSettled.current = false;
    swipeStepSeen.current = false;
    swipeOwnsClock.current = false;
  }, []);

  // A swipe commits through exactly the same persisted path as Continue and Skip — one guarded write,
  // the page changes only once the save lands, and a refused or failed save answers the caller so the
  // artwork can settle back on the step the reader is still on.
  const swipeToStep = useCallback(
    (next: number, onResult: (saved: boolean) => void) => {
      swipeTarget.current = next;
      write(next, (saved) => {
        // A refused or failed save leaves no pending target, so a later step change can only be a
        // button's own.
        if (!saved && swipeTarget.current === next) swipeTarget.current = null;
        onResult(saved);
      });
    },
    [write],
  );

  // Which step the scene is travelling from, and the previous page's view kept for the outgoing
  // layer. Both are set during render, when the step changes, so the new page's first frame already
  // animates from the right place.
  const reported = useRef<StepVisual | null>(null);
  const [nav, setNav] = useState(() => ({
    step,
    from: step - 1,
    outgoing: null as StepVisual | null,
    // Where the outgoing page stood in its own arrival, and the direction it was travelling: a step
    // change that lands mid-arrival hands the exit over from that pose rather than from rest.
    outgoingProgress: 1,
    outgoingDirection: 1,
    run: 0,
  }));
  if (nav.step !== step) {
    // Read before the clock is reset, so the page being left exits from where it actually was.
    const outgoingProgress = contentProgress.value;
    const outgoingDirection = nav.step > nav.from ? 1 : -1;
    // A step change that lands on the swipe's target is the gesture's own commit: the scene and the
    // copy are already at that page's pose, so nothing may be reset or replayed. The session ends
    // only once the spring has landed too (or immediately if it already had).
    const fromSwipe = swipeTarget.current === step;
    if (fromSwipe) {
      swipeTarget.current = null;
      swipeStepSeen.current = true;
      swipeOwnsClock.current = true;
      if (swipeVisualSettled.current) {
        finishSwipe();
      } else {
        // The committed page now owns the copy's arrival, on the same UI-thread pose the preview was
        // reading, so the two layers trade over inside one frame.
        swipePose.role.value = SWIPE_ARRIVE;
      }
    } else {
      // A button (or the hardware key) moved the step: its own clean handoff runs, and any swipe
      // session — a pending commit, a live drag or an unfinished settle — is superseded. Stopping
      // the clock and turning the pose off makes the old gesture's release inert, so it can neither
      // commit nor settle over the new page.
      if (
        swipeOwnsClock.current ||
        swipeTarget.current != null ||
        swipePose.role.value !== SWIPE_IDLE
      ) {
        cancelAnimation(scene);
        cancelAnimation(swipePose.q);
        finishSwipe();
      }
      swipeOwnsClock.current = false;
    }
    setNav({
      step,
      from: nav.step,
      outgoing: fromSwipe ? null : reported.current,
      outgoingProgress,
      outgoingDirection,
      run: nav.run + 1,
    });
    if (!fromSwipe) {
      // The live text must paint the entering pose on this very commit: the clock's reset below is
      // written from JS and may not have reached the UI thread by the frame this renders. Reduced
      // motion never resets the clock, so there is no such frame to cover and the text simply arrives.
      setEntering(!reducedMotion);
      if (!reducedMotion) {
        contentProgress.value = 0;
      }
    }
  }
  const direction = nav.step > nav.from ? 1 : -1;

  const report = useCallback(
    (visual: StepVisual) => {
      reported.current = visual;
      // The page's status is cached here and replaced only when the controller's own semantic key
      // changes — never by comparing the node, so a re-report cannot loop, and the leaving copy keeps
      // the status it had. It is drawn by the fixed copies, not by the page.
      setStatuses((current) => {
        const cached = current[step];
        if (cached != null && cached.key === visual.statusKey) {
          return current;
        }
        return {...current, [step]: {key: visual.statusKey, node: visual.status}};
      });
      // The action area is the shell's own, drawn once for the whole flow: it takes the freshest view
      // whose buttons actually differ, so a step change swaps a label and nothing else. The committed
      // step is part of the key, so two pages that share a label and a disabled state still hand over
      // a fresh handler.
      const key = actionAreaKey(step, visual);
      if (reportedActionKey.current === key) {
        return;
      }
      reportedActionKey.current = key;
      setActions(visual);
    },
    [step],
  );
  const statusNodes = useMemo(
    () => PAGES.map((_, index) => statuses[index]?.node ?? null),
    [statuses],
  );

  // The outgoing layer goes once the scene has landed. The run id comes back from the UI thread so a
  // step change that lands mid-flight never drops the page the new one is still leaving.
  const clearOutgoing = useCallback((run: number) => {
    setNav((current) => (current.run === run && current.outgoing != null ? {...current, outgoing: null} : current));
  }, []);

  useEffect(() => {
    if (reducedMotion) {
      // Nothing times, so nothing reports back: the leaving page goes straight away.
      scene.value = step;
      clearOutgoing(nav.run);
      return;
    }
    if (swipeOwnsClock.current) {
      // The gesture's spring already owns this clock and is heading for this very step, so the
      // accepted 340 ms timing must not retime it or start it again.
      return;
    }
    scene.value = withTiming(step, {duration: STEP_SCENE_MS, easing: PAGE_EASING_REANIMATED}, (finished) => {
      "worklet";
      if (finished) runOnJS(clearOutgoing)(nav.run);
    });
  }, [nav.run, step, reducedMotion, scene, clearOutgoing]);

  // The content clock runs alongside the scene's; reduced motion pins it settled. This runs after
  // the step-change commit has painted, so by the next frame the reset has long reached the UI
  // thread and the bodies can follow the clock: the entering pose and the clock's own start are the
  // same pose, so handing over is invisible.
  useEffect(() => {
    contentProgress.value = reducedMotion
      ? 1
      : withTiming(1, {duration: STEP_SCENE_MS, easing: PAGE_EASING_REANIMATED});
    setEntering(false);
  }, [nav.run, reducedMotion, contentProgress]);

  // The first-run settle: the scene comes up once, on the flow's own mount. Nothing else replays it.
  useEffect(() => {
    if (reducedMotion) {
      entrance.value = 1;
      return;
    }
    entrance.value = withTiming(1, {duration: STEP_SCENE_MS, easing: PAGE_EASING_REANIMATED});
  }, [reducedMotion, entrance]);

  // The form's own transition. Its target is read during render, so the very commit that opens or
  // closes the form already treats the page as covered — the actions' interactivity and the form's
  // own a11y hand-off must never read the previous transition's settled value.
  const formTarget = formOpen ? 1 : 0;
  const [formTargetSeen, setFormTargetSeen] = useState(formTarget);
  if (formTargetSeen !== formTarget) {
    setFormTargetSeen(formTarget);
    setFormSettled(false);
  }

  // A finish from an older run must not report the current transition settled.
  const settleForm = useCallback((run: number) => {
    if (run === formRun.current) setFormSettled(true);
  }, []);

  // The WaniKani form's own rise and fall, and the recede of the scene behind it. The form stays
  // mounted after its first open, so closing it only animates it away; [formSettled] marks the moment
  // the rise or the fall has actually finished, and a run id keeps a superseded finish from clearing
  // it early.
  useEffect(() => {
    const target = formOpen ? 1 : 0;
    if (formProgress.value === target) {
      setFormSettled(true);
      return;
    }
    const run = ++formRun.current;
    if (reducedMotion) {
      formProgress.value = target;
      setFormSettled(true);
      return;
    }
    setFormSettled(false);
    formProgress.value = withTiming(target, {duration: FORM_MS, easing: PAGE_EASING_REANIMATED}, (finished) => {
      "worklet";
      if (finished) runOnJS(settleForm)(run);
    });
  }, [formOpen, reducedMotion, formProgress, settleForm]);

  if (formOpen && !formMounted) {
    setFormMounted(true);
  }

  // The accent follows the real connection state and only the WaniKani page carries it, so it is
  // never lit on a page that has nothing to say about the account. Reduced motion sets it directly,
  // keeping the state without the sweep.
  const accentOn = wanikaniConnected && step === 2;
  useEffect(() => {
    success.value = reducedMotion
      ? accentOn
        ? 1
        : 0
      : withTiming(accentOn ? 1 : 0, {duration: FORM_MS, easing: PAGE_EASING_REANIMATED});
  }, [accentOn, reducedMotion, success]);

  // The embedded form stored a working token: the form goes, and the page re-reads the real store.
  const onFormConnected = useCallback(() => {
    setFormOpen(false);
    setConnectRevision((current) => current + 1);
  }, []);

  // A new page starts fresh: the WaniKani form closes, and the incoming heading takes the reader's
  // focus. The heading carries the step in its own label, so one focus announcement says where the
  // reader is and what the page is — a separate step announcement would read it twice.
  useEffect(() => {
    setFormOpen(false);
    const node = findNodeHandle(headingRef.current);
    if (node != null) AccessibilityInfo.setAccessibilityFocus(node);
  }, [step]);

  // Closing the form puts the reader back on the page it was covering — once the page is really back
  // on screen ([formSettled]), so focus never lands on content that is still invisible under the
  // form's exit.
  const formWasOpen = useRef(formOpen);
  useEffect(() => {
    if (formOpen) {
      formWasOpen.current = true;
      return;
    }
    if (formWasOpen.current && formSettled) {
      formWasOpen.current = false;
      const node = findNodeHandle(headingRef.current);
      if (node != null) AccessibilityInfo.setAccessibilityFocus(node);
    }
  }, [formOpen, formSettled]);

  // Android Back: while a step is being saved it does nothing at all — a second write must not race
  // the one in flight. Otherwise it closes the WaniKani form first, goes back a page on pages 2 and
  // 3, and on the first page is left to the platform — which leaves the app without finishing the
  // flow.
  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      if (persisting.current) {
        return true;
      }
      if (formOpen) {
        setFormOpen(false);
        return true;
      }
      if (step === 0) {
        return false;
      }
      write(step - 1);
      return true;
    });
    return () => subscription.remove();
  }, [step, formOpen, write]);

  const shared: PageProps = {
    step,
    onAdvance: write,
    saveError,
    onRetry: retry,
    report,
  };

  return (
    <OnboardingShell
      step={step}
      scene={scene}
      entrance={entrance}
      formProgress={formProgress}
      contentProgress={contentProgress}
      success={success}
      direction={direction}
      outgoing={nav.outgoing}
      outgoingStep={nav.from}
      outgoingProgress={nav.outgoingProgress}
      outgoingDirection={nav.outgoingDirection}
      entering={entering}
      writePending={writePending}
      formOpen={formOpen}
      formMounted={formMounted}
      formSettled={formSettled}
      swipePose={swipePose}
      headingRef={headingRef}
      statusNodes={statusNodes}
      actions={actions}
      onSwipeStep={swipeToStep}
      onSwipeStart={swipeStarted}
      onSwipeSettled={swipeSettled}
      onCloseForm={() => setFormOpen(false)}
      onFormConnected={onFormConnected}
      onBack={
        formOpen
          ? () => setFormOpen(false)
          : step > 0
            ? () => {
                if (persisting.current) return;
                write(step - 1);
              }
            : null
      }
    >
      {step === 0 && <LookupPage {...shared} />}
      {step === 1 && <AnkiPage {...shared} />}
      {step === 2 && (
        <WaniKaniPage
          {...shared}
          connectRevision={connectRevision}
          onConnectedChange={setWanikaniConnected}
          onOpenForm={() => setFormOpen(true)}
        />
      )}
    </OnboardingShell>
  );
}

/**
 * The persistent frame: the back row, the progress row, the artwork scene, and the scroller whose
 * content the pages fill. It is mounted once for the whole flow, so the scene is never rebuilt as
 * the reader moves between pages.
 *
 * The artwork sits above the scroller rather than inside it, which is what lets one card survive a
 * step change; a large text size or a short viewport still drops it entirely, and the actions stay
 * at the end of the page's own scroller — never a pinned footer that has hidden them.
 */
function OnboardingShell({
  step,
  scene,
  entrance,
  formProgress,
  contentProgress,
  success,
  direction,
  outgoing,
  outgoingStep,
  outgoingProgress,
  outgoingDirection,
  entering,
  writePending,
  formOpen,
  formMounted,
  formSettled,
  swipePose,
  headingRef,
  statusNodes,
  actions,
  onSwipeStep,
  onSwipeStart,
  onSwipeSettled,
  onCloseForm,
  onFormConnected,
  onBack,
  children,
}: {
  step: number;
  scene: SharedValue<number>;
  entrance: SharedValue<number>;
  formProgress: SharedValue<number>;
  contentProgress: SharedValue<number>;
  success: SharedValue<number>;
  direction: number;
  outgoing: StepVisual | null;
  /** The page the outgoing picture belongs to, for its heading's step announcement. */
  outgoingStep: number;
  /** Where the outgoing page stood in its own arrival, and the direction it was travelling. */
  outgoingProgress: number;
  outgoingDirection: number;
  /** True for the one commit after a step change, while the content clock's reset is in flight. */
  entering: boolean;
  writePending: boolean;
  formOpen: boolean;
  formMounted: boolean;
  /** True when the form's rise or fall has finished, so the page is really on screen again. */
  formSettled: boolean;
  /** The swipe's pose: the scene clock plus the copy's fraction map. */
  swipePose: SwipePose;
  /** The heading the flow focuses when a page arrives: the committed copy's own heading. */
  headingRef: React.RefObject<Text | null>;
  /** One cached status per page, by [PAGES] index, drawn by that page's fixed copy. */
  statusNodes: React.ReactNode[];
  /** The live action area, held by the flow so the buttons survive every page swap. */
  actions: StepVisual | null;
  /** Commits a swiped step through the flow's persisted write; `onResult` answers whether it saved. */
  onSwipeStep: (next: number, onResult: (saved: boolean) => void) => void;
  /** A new touch takes the session over; the previous settle can no longer report. */
  onSwipeStart: () => void;
  /** The swipe's settle landed; `accepted` says whether the step was a committed target. */
  onSwipeSettled: (accepted: boolean) => void;
  onCloseForm: () => void;
  onFormConnected: () => void;
  onBack: (() => void) | null;
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width, height, fontScale} = useWindowDimensions();
  const inset = contentInsetFor(width);
  // Gestures still change the step with reduced motion on; they just never draw the drag or settle.
  const reducedMotion = useReducedMotion();
  // The height left for the scene and the scroller together; the window stands in until the first
  // layout, so a page decides its artwork mode from its first frame.
  const [region, setRegion] = useState<number | null>(null);
  const regionHeight =
    region ?? Math.max(0, height - insets.top - insets.bottom - HEADER_ROW_HEIGHT - HEADER_GAP);
  // The mode is latched while the WaniKani form is up: the soft keyboard resizes the window, and
  // the artwork must not collapse under a form the reader is already filling in. It re-decides the
  // moment the form closes.
  const decidedMode = artworkModeFor(fontScale, regionHeight);
  const [mode, setMode] = useState<ArtworkMode>(decidedMode);
  if (!formOpen && mode !== decidedMode) {
    setMode(decidedMode);
  }
  const innerColumnWidth = Math.min(width, MAX_CONTENT_WIDTH) - 2 * inset;
  // One page step in dp for the narrative copies: the full viewport the scroller clips at, not the
  // narrower content column, so a copy is wholly out of sight exactly when the next one is in place
  // and no neighbour sliver can sit in the side padding while its own page already owns the screen.
  const swipeDistance = width - insets.left - insets.right;
  const artworkWidth = Math.min(
    innerColumnWidth,
    mode === "capped" ? ARTWORK_CAPPED_WIDTH : ARTWORK_WIDTH,
  );

  // The form's handoff, on its own clock and the same two phases as the step text: the page's body
  // and actions are gone inside the first [PHASE_EXIT_BY], the form's do not appear until
  // [PHASE_ENTER_AT], and the same formulas run backwards as the form closes — so the form always
  // leaves before the page returns and the two are never readable at once. The scene's recede stays
  // on the whole 0→1 clock, so it is continuous in both directions.
  const contentAreaStyle = useAnimatedStyle(() => {
    const t = Math.min(1, formProgress.value / PHASE_EXIT_BY);
    return {opacity: 1 - t, transform: [{translateY: -FORM_TRAVEL * t}]};
  });
  const formStyle = useAnimatedStyle(() => {
    const t = easedPhase(Math.max(0, Math.min(1, (formProgress.value - PHASE_ENTER_AT) / (1 - PHASE_ENTER_AT))));
    return {opacity: t, transform: [{translateY: FORM_TRAVEL * (1 - t)}]};
  });

  // The step swipe: one horizontal pan that previews the adjacent page on the same scene clock the
  // artwork and the track already draw with, then commits through the flow's persisted write. It
  // takes only clearly horizontal intent and gives up early on vertical movement, so the page still
  // scrolls and the buttons still take their taps. It is off while the form is up or a save is in
  // flight, and it never changes the step itself — a swipe is exactly the Continue write.
  const dragBase = useSharedValue(0);
  const commitDistance = Math.min(width * SWIPE_COMMIT_FRACTION, SWIPE_COMMIT_MAX);
  const rtl = I18nManager.isRTL ? -1 : 1;
  const swipeEnabled = !formOpen && formSettled && !writePending;

  // A settle reports only while the session is still live: the flow ends the role the moment a
  // session is superseded, so a spring that was cancelled or replaced can never report for a newer
  // one, and the gesture's own release is inert.
  const swipeLanded = useCallback(
    (accepted: boolean) => {
      if (swipePose.role.value === SWIPE_IDLE) return;
      onSwipeSettled(accepted);
    },
    [onSwipeSettled, swipePose],
  );

  const commitSwipe = useCallback(
    (next: number) => {
      if (swipePose.role.value === SWIPE_IDLE) return;
      onSwipeStep(next, (saved) => {
        if (saved || swipePose.role.value === SWIPE_IDLE) return;
        // Refused or failed: roll the visual back to the step the reader is still on, from wherever
        // the settle actually is, restarting the same progress with no inherited velocity.
        if (reducedMotion) {
          onSwipeSettled(false);
          return;
        }
        const pNow = swipeFraction(swipePose);
        swipePose.sceneFrom.value = scene.value;
        swipePose.sceneTo.value = step;
        swipePose.slope.value = -pNow;
        swipePose.intercept.value = pNow;
        swipePose.q.value = 0;
        swipePose.q.value = withSpring(1, SWIPE_SPRING, (finished) => {
          "worklet";
          if (finished) runOnJS(swipeLanded)(false);
        });
      });
    },
    [onSwipeStep, onSwipeSettled, scene, step, reducedMotion, swipeLanded, swipePose],
  );

  const swipe = useMemo(
    () =>
      Gesture.Pan()
        .enabled(swipeEnabled)
        .activeOffsetX([-SWIPE_ACTIVATE_X, SWIPE_ACTIVATE_X])
        .failOffsetY([-SWIPE_FAIL_Y, SWIPE_FAIL_Y])
        .onStart(() => {
          "worklet";
          // A touch that lands while the committed page is still unreadable — the arrival has not
          // reached [PHASE_ENTER_AT] — must not take the session over: taking it would pin the page
          // the finger is leaving and restart the fraction from 0, so all three copies would be
          // unreadable at once. The old clock and composition keep running, and this touch is inert
          // because onUpdate and onEnd ignore a session that is not DRAG. Nothing is written first.
          if (
            swipePose.role.value === SWIPE_ARRIVE &&
            Math.abs(swipeFraction(swipePose)) <= PHASE_ENTER_AT
          ) {
            return;
          }
          // A button's own 340 ms handoff may still be running: taking it over would cut the copy
          // from its arrival pose to a full-width page pose. Leave it alone; onUpdate and onEnd
          // ignore a session that is not DRAG, so this touch is inert.
          if (
            swipePose.role.value === SWIPE_IDLE &&
            (Math.abs(scene.value - step) > 0.001 || contentProgress.value < 0.999)
          ) {
            return;
          }
          // Only once the pan actually owns the gesture: a tap, a vertical scroll or a native cancel
          // that never activates never reaches onEnd (BEGAN → FAILED/CANCELLED reports only through
          // onFinalize), so touching the scene clock there could cancel a settle that nothing would
          // ever restore. A settle may still be running: take over from the pose actually on screen.
          // The copy's map starts at slope 1 / intercept −sceneAtTouch, so its fraction is 0 here and
          // then follows the scene exactly — a pan begun mid-transition never blanks the heading.
          cancelAnimation(scene);
          cancelAnimation(swipePose.q);
          // Capture the committed copy's pose EXACTLY as it stands before the role changes — its own
          // arrival, the exit it is under the finger or the settle, and any base already taken over —
          // and make that the new base. One pure call, so a retouch (of a settled copy as much as an
          // arriving one) continues from what is on screen instead of popping.
          const role = swipePose.role.value;
          if (role === SWIPE_IDLE) {
            swipePose.baseOwn.value = 0;
          } else {
            const pose = narrativePose(
              step,
              step,
              outgoingStep,
              outgoingProgress,
              outgoingDirection,
              direction,
              contentProgress,
              swipePose,
              entering,
              outgoing != null,
              swipeDistance,
            );
            swipePose.baseOpacity.value = pose.opacity;
            swipePose.baseX.value = pose.x;
            swipePose.baseOwn.value = 1;
          }
          const from = scene.value;
          dragBase.value = from;
          swipePose.slope.value = 1;
          swipePose.intercept.value = -from;
          swipePose.q.value = 0;
          swipePose.role.value = SWIPE_DRAG;
          runOnJS(onSwipeStart)();
        })
        .onUpdate((event) => {
          "worklet";
          if (reducedMotion || swipePose.role.value !== SWIPE_DRAG) return;
          // The pan only takes the gesture after [SWIPE_ACTIVATE_X] dp of travel, so the preview
          // subtracts that slop: the art starts moving from the pose it is actually in instead of
          // jumping that slop's worth on the activation frame. The commit and flick thresholds stay
          // on the raw translation, so the navigation intent is unchanged.
          const raw = event.translationX;
          const travel = Math.sign(raw) * Math.max(0, Math.abs(raw) - SWIPE_ACTIVATE_X);
          const reach = Math.min(1, Math.abs(travel) / commitDistance);
          const dir = (travel < 0 ? 1 : -1) * rtl;
          const low = Math.max(0, step - 1);
          const high = Math.min(2, step + 1);
          scene.value = Math.max(low, Math.min(high, dragBase.value + dir * reach * SWIPE_PREVIEW_REACH));
        })
        .onEnd((event, success) => {
          "worklet";
          // Superseded before the finger lifted (a step change, the form, an ended session): this
          // release owns nothing and must not commit or settle over the new page.
          if (swipePose.role.value !== SWIPE_DRAG) return;
          const travel = Math.abs(event.translationX);
          const sign =
            event.translationX !== 0 ? Math.sign(event.translationX) : Math.sign(event.velocityX);
          const dir = (sign < 0 ? 1 : -1) * rtl;
          // One step per swipe, never a wrap: at either end the adjacent target is out of range and
          // the preview has already been clamped, so nothing is committed.
          const target = step + dir;
          const accepted =
            success &&
            target >= 0 &&
            target <= 2 &&
            (travel >= commitDistance ||
              (Math.abs(event.velocityX) >= SWIPE_FLICK_VELOCITY && travel >= SWIPE_FLICK_TRAVEL));
          // From here one dimensionless settle progress owns both outputs: the scene lerps from the
          // pose on screen to the finish, and the copy's fraction from where it was to ±1 (accepted,
          // in the direction of travel) or 0 (a swipe that returns to its step). Both are reachable
          // from any release pose, and a settle with no art span still completes the copy.
          const s0 = scene.value;
          const finishScene = accepted ? target : step;
          const p0 = swipeFraction(swipePose);
          const pEnd = accepted ? dir : 0;
          swipePose.slope.value = pEnd - p0;
          swipePose.intercept.value = p0;
          swipePose.sceneFrom.value = s0;
          swipePose.sceneTo.value = finishScene;
          swipePose.q.value = 0;
          swipePose.role.value = SWIPE_SETTLE;
          if (reducedMotion) {
            // Nothing is drawn: snap the progress and tell the session the visual has settled.
            swipePose.q.value = 1;
            if (accepted) runOnJS(commitSwipe)(target);
            runOnJS(swipeLanded)(accepted);
            return;
          }
          // The spring's velocity is the finger's own, in settle-progress units per second, and only
          // when the finish lies the way the finger was already going. It is bounded so a nearly
          // finished swipe cannot turn its tiny remaining span into an instantaneous snap.
          const sceneVelocity = (-rtl * event.velocityX / commitDistance) * SWIPE_PREVIEW_REACH;
          const span = finishScene - s0;
          const velocity =
            span !== 0 && sceneVelocity * span > 0 ? Math.min(12, Math.abs(sceneVelocity / span)) : 0;
          cancelAnimation(swipePose.q);
          swipePose.q.value = withSpring(1, {...SWIPE_SPRING, velocity}, (finished) => {
            "worklet";
            if (finished) runOnJS(swipeLanded)(accepted);
          });
          if (accepted) runOnJS(commitSwipe)(target);
        }),
    [
      swipeEnabled,
      scene,
      swipePose,
      dragBase,
      commitDistance,
      step,
      outgoingStep,
      outgoingProgress,
      outgoingDirection,
      direction,
      contentProgress,
      entering,
      outgoing,
      swipeDistance,
      reducedMotion,
      rtl,
      swipeLanded,
      commitSwipe,
      onSwipeStart,
    ],
  );

  // The one physical driver: while the copy settles, the scene is a lerp of the settle progress, so
  // the art and the text are the same clock with mapped outputs. Nothing runs outside a settle, so a
  // button's or the form's own timing can never be written over.
  useAnimatedReaction(
    () => swipePose.q.value,
    (progress) => {
      if (swipePose.role.value < SWIPE_SETTLE) return;
      scene.value =
        swipePose.sceneFrom.value + (swipePose.sceneTo.value - swipePose.sceneFrom.value) * progress;
    },
  );

  // The embedded form's own connect: the token is checked natively, and only a real connection
  // closes the form and asks the WaniKani page to re-read the store. A refused or failed token
  // leaves the form up with its own message.
  const connect = useCallback(
    async (token: string) => {
      const outcome = await connectWaniKani(token);
      if (outcome.status === "connected") onFormConnected();
      return outcome;
    },
    [onFormConnected],
  );

  // A new page starts at its own top: the scroller is one instance for the whole flow, so a reader
  // who scrolled to reach the actions does not arrive on the next page already scrolled.
  const scrollRef = useRef<ScrollView>(null);
  useEffect(() => {
    scrollRef.current?.scrollTo({y: 0, animated: false});
  }, [step]);

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: colors.background}} edges={["top", "left", "right"]}>
      {/* One header row: the bare back target, the compact step track centred on the row, and the
          step in words. Every step reserves the back target's slot, so neither the row's height nor
          the track's centre moves as the reader goes back and forward; while the WaniKani form is up
          the target closes the form rather than leaving the page. */}
      <View
        style={[
          CONTENT_COLUMN,
          {
            flexDirection: "row",
            alignItems: "center",
            minHeight: HEADER_ROW_HEIGHT,
            paddingHorizontal: inset,
            marginBottom: HEADER_GAP,
          },
        ]}
      >
        <View style={{width: HEADER_ROW_HEIGHT, height: HEADER_ROW_HEIGHT, justifyContent: "center"}}>
          {onBack != null && (
            // While a step is being saved the chevron is dimmed and inert: the frame's actions
            // already hold, and a second write must not race the one in flight.
            <View style={{opacity: writePending ? 0.4 : 1}}>
              <IconButton
                name="arrow_back"
                accessibilityLabel="Back"
                onPress={writePending ? () => {} : onBack}
                color={colors.onSurface}
              />
            </View>
          )}
        </View>

        {/* The step in words carries the row's accessible name as the full "Step N of 3": the row
            itself cannot be one accessible element without swallowing the back target, and the track
            is decorative. Its 12 dp right padding matches the bare arrow's own visible inset — the
            glyph starts 12 dp inside its 48 dp target — while the cell keeps its 48 dp minimum and
            grows with the text. */}
        <View style={{flex: 1, alignItems: "flex-end", minWidth: HEADER_ROW_HEIGHT, paddingRight: 12}}>
          <Text
            accessibilityLabel={`Step ${step + 1} of 3`}
            style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}
          >
            {`${step + 1} / 3`}
          </Text>
        </View>

        {/* Centred on the row itself, so the right cell growing at a large text size cannot pull the
            track off centre. */}
        <View
          pointerEvents="none"
          style={{
            position: "absolute",
            left: 0,
            right: 0,
            top: 0,
            bottom: 0,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <ProgressTrack scene={scene} />
          </View>
        </View>
      </View>

      <GestureDetector gesture={swipe}>
        <View
          style={{flex: 1}}
          onLayout={(event) => {
            const next = event.nativeEvent.layout.height;
            setRegion((current) => (current === next ? current : next));
          }}
        >
          {mode !== "hidden" && (
            <View
              accessible={false}
              accessibilityElementsHidden
              importantForAccessibility="no-hide-descendants"
              pointerEvents="none"
              style={[CONTENT_COLUMN, {alignItems: "center", paddingHorizontal: inset}]}
            >
              <OnboardingScene
                width={artworkWidth}
                scene={scene}
                entrance={entrance}
                formProgress={formProgress}
                success={success}
              />
            </View>
          )}

          <View style={{flex: 1}}>
            <Animated.View
              style={[{flex: 1}, contentAreaStyle]}
              pointerEvents={formOpen || !formSettled ? "none" : "auto"}
              accessibilityElementsHidden={formOpen || !formSettled}
              importantForAccessibility={formOpen || !formSettled ? "no-hide-descendants" : "auto"}
            >
              <ScrollView
                ref={scrollRef}
                // A swipe's text rides the artwork's page coordinate, so copies travel outside the
                // slot: clip at this viewport, and keep the three fixed copies attached on Android —
                // a detached peer would not draw as its UI-thread transform brings it back in.
                style={{overflow: "hidden"}}
                removeClippedSubviews={false}
                showsVerticalScrollIndicator={false}
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={[
                  CONTENT_COLUMN,
                  {
                    flexGrow: 1,
                    paddingHorizontal: inset,
                    paddingBottom: insets.bottom + spacing.xl,
                  },
                ]}
              >
                {/* The narrative slot: one fixed copy per page, drawn here for the whole flow, so
                    a step change or a swipe re-poses the copy and never swaps its identity. The
                    committed page's copy is in flow — it sets the slot's height, so large text and the
                    scroller still lay out from real content — and the others are laid over it. */}
                <View style={NARRATIVE_SLOT}>
                  {PAGES.map((_, index) => (
                    <NarrativeCopy
                      key={`narrative-${index}`}
                      index={index}
                      step={step}
                      from={outgoingStep}
                      fromProgress={outgoingProgress}
                      fromDirection={outgoingDirection}
                      outgoing={outgoing != null}
                      direction={direction}
                      entering={entering}
                      contentProgress={contentProgress}
                      swipePose={swipePose}
                      headingRef={headingRef}
                      status={statusNodes[index] ?? null}
                      swipeDistance={swipeDistance}
                    />
                  ))}
                </View>
                <Animated.View key="incoming" style={{flexGrow: 1}}>
                  {children}
                </Animated.View>
                {/* The one action area, outside every page: the same element for the whole flow, so a
                    step change or a swipe only swaps a label, a disabled state and what the press
                    does. It stays in the scroller's own flow, so large text and short screens reach it. */}
                <StepActions key="actions" visual={actions} busy={writePending} />
              </ScrollView>
            </Animated.View>

            {formMounted && (
              <Animated.View
                style={[{position: "absolute", top: 0, left: 0, right: 0, bottom: 0}, formStyle]}
                pointerEvents={formOpen ? "auto" : "none"}
                accessibilityElementsHidden={!formOpen}
                importantForAccessibility={formOpen ? "auto" : "no-hide-descendants"}
              >
                <WaniKaniConnect
                  embedded
                  active={formOpen && formSettled}
                  rejected={false}
                  busy={false}
                  onBack={onCloseForm}
                  onConnect={connect}
                />
              </Animated.View>
            )}
          </View>
        </View>
      </GestureDetector>
    </SafeAreaView>
  );
}

type LookupState = "checking" | "on" | "off" | "failed";

/** Page 1: the accessibility grant that lookup mode is, read afresh on every arrival. */
function LookupPage({step, onAdvance, saveError, onRetry, report}: PageProps) {
  const {colors} = useTheme();
  const {request, dialog} = useLookupModeRequest();
  const [state, setState] = useState<LookupState>("checking");
  // Whether the app has come back to the foreground while this page was up: only then does an "off"
  // read mean "you returned without enabling it", rather than the first look at the page.
  const [returned, setReturned] = useState(false);
  const readSeq = useRef(0);

  const read = useCallback((showChecking: boolean) => {
    readSeq.current += 1;
    const seq = readSeq.current;
    if (showChecking) setState("checking");
    void GatenAppInfo.isLookupModeActive()
      .then((active) => {
        if (seq === readSeq.current) setState(active ? "on" : "off");
      })
      .catch(() => {
        if (seq === readSeq.current) setState("failed");
      });
  }, []);

  // The grant is given in system Settings, so it is read on arrival and again every time the app
  // comes back to the foreground; a return is never treated as success.
  useEffect(() => {
    read(true);
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        setReturned(true);
        read(false);
      }
    });
    return () => subscription.remove();
  }, [read]);

  const primary: Action =
    state === "checking"
      ? {label: "Checking…", onPress: () => {}, disabled: true}
      : state === "on"
        ? {label: "Continue", onPress: () => onAdvance(step + 1)}
        : state === "failed"
          ? {label: "Try again", onPress: () => read(true)}
          : {
              label: "Turn on lookup mode",
              onPress: () => request(),
            };

  const visual: StepVisual = {
    statusKey: `${state}:${returned ? 1 : 0}:${colors.onSurfaceVariant}`,
    status:
      state === "checking" ? null : (
        <View style={{gap: spacing.s}}>
          {state === "on" && (
            <>
              <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
                Lookup mode is on.
              </Text>
              <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
                To start a lookup, turn on Floating button or add the Quick Settings tile in Screen
                lookup settings.
              </Text>
            </>
          )}
          {state === "off" && returned && (
            <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
              Lookup mode is still off.
            </Text>
          )}
          {state === "failed" && (
            <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
              Couldn't check lookup mode.
            </Text>
          )}
        </View>
      ),
    primary,
    outlined: null,
    skipLabel: "Skip",
    skipAccessibilityLabel: "Skip lookup mode setup",
    onSkip: () => onAdvance(step + 1),
    saveError,
    onRetry,
  };

  // A layout effect, so the shell's action area is already this page's by the time the frame that
  // shows the page paints: the buttons never show a step's predecessor for a frame.
  useLayoutEffect(() => {
    report(visual);
  });

  return (
    <>{dialog}</>
  );
}

type AnkiFailure = "NOT_INSTALLED" | "PERMISSION" | "NOT_SET_UP" | "PROVIDER";

type AnkiPageState =
  | {kind: "checking"}
  | {kind: "notInstalled"}
  | {kind: "needsPermission"}
  | {kind: "notSetUp"}
  | {kind: "denied"}
  | {kind: "failure"}
  | {kind: "connected"; empty: boolean};

/** The `code` a bridge rejection carried, or "" when it carried none. */
function errorCode(error: unknown): string {
  if (typeof error === "object" && error != null && "code" in error) {
    return typeof error.code === "string" ? error.code : "";
  }
  return "";
}

/** The AnkiDroid failure a bridge rejection carried, without any of the provider's internals. */
function ankiFailureOf(error: unknown): AnkiFailure {
  const named = /^ANKI_([A-Z_]+)$/.exec(errorCode(error))?.[1];
  if (named === "NOT_INSTALLED" || named === "PERMISSION" || named === "NOT_SET_UP") {
    return named;
  }
  return "PROVIDER";
}

/** Opens AnkiDroid itself, for the collection it has not set up or the failure a read hit. */
function openAnkiDroid() {
  void GatenAnki.openAnkiDroid().catch(() => {});
}

/** Page 2: AnkiDroid installed, allowed and with a collection to read — done in place. */
function AnkiPage({step, onAdvance, saveError, onRetry, report}: PageProps) {
  const {colors} = useTheme();
  const [state, setState] = useState<AnkiPageState>({kind: "checking"});
  const readSeq = useRef(0);

  const read = useCallback((showChecking: boolean) => {
    readSeq.current += 1;
    const seq = readSeq.current;
    if (showChecking) setState({kind: "checking"});
    void (async () => {
      try {
        const status = await GatenAnki.status();
        if (seq !== readSeq.current) return;
        if (status === "notInstalled") {
          setState({kind: "notInstalled"});
          return;
        }
        if (status === "needsPermission") {
          setState({kind: "needsPermission"});
          return;
        }
        const collection = await GatenAnki.collection();
        if (seq !== readSeq.current) return;
        setState({kind: "connected", empty: collection.decks.length === 0});
      } catch (error) {
        if (seq !== readSeq.current) return;
        switch (ankiFailureOf(error)) {
          case "NOT_INSTALLED":
            setState({kind: "notInstalled"});
            return;
          case "PERMISSION":
            setState({kind: "needsPermission"});
            return;
          case "NOT_SET_UP":
            setState({kind: "notSetUp"});
            return;
          default:
            setState({kind: "failure"});
        }
      }
    })();
  }, []);

  // The store, the permission dialog and AnkiDroid's own collection all change outside this page, so
  // the status is read on arrival and again on every foreground return.
  useEffect(() => {
    read(true);
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") read(false);
    });
    return () => subscription.remove();
  }, [read]);

  const grant = () => {
    void GatenAnki.requestPermission()
      .then((granted) => {
        if (granted) read(true);
        else setState({kind: "denied"});
      })
      .catch(() => setState({kind: "denied"}));
  };

  let primary: Action | null = null;
  let outlined: Action | null = null;
  let status: React.ReactNode = null;

  switch (state.kind) {
    case "checking":
      primary = {label: "Checking Anki…", onPress: () => {}, disabled: true};
      break;
    case "notInstalled":
      primary = {label: "Download Anki", onPress: () => void downloadAnkiDroid()};
      break;
    case "needsPermission":
      primary = {label: "Allow access", onPress: grant};
      break;
    case "notSetUp":
      outlined = {label: "Open Anki", onPress: openAnkiDroid};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
            Finish setup in Anki, then come back.
          </Text>
        </View>
      );
      break;
    case "denied":
      primary = {label: "Try again", onPress: () => read(true)};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
            Access wasn't allowed. Try again or skip.
          </Text>
        </View>
      );
      break;
    case "failure":
      primary = {label: "Try again", onPress: () => read(true)};
      outlined = {label: "Open Anki", onPress: openAnkiDroid};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
            Couldn't read Anki. Open it, then try again.
          </Text>
        </View>
      );
      break;
    case "connected":
      primary = {label: "Continue", onPress: () => onAdvance(step + 1)};
      status = (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
            Anki is connected.
          </Text>
          {state.empty && (
            <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
              Create or import a deck when you're ready.
            </Text>
          )}
        </View>
      );
      break;
  }

  const visual: StepVisual = {
    statusKey: `${
      state.kind === "connected" ? `connected:${state.empty ? 1 : 0}` : state.kind
    }:${colors.onSurfaceVariant}`,
    status,
    primary,
    outlined,
    skipLabel: "Skip",
    skipAccessibilityLabel: "Skip Anki setup",
    onSkip: () => onAdvance(step + 1),
    saveError,
    onRetry,
  };

  // A layout effect, so the shell's action area is already this page's by the time the frame that
  // shows the page paints: the buttons never show a step's predecessor for a frame.
  useLayoutEffect(() => {
    report(visual);
  });

  // The page draws nothing itself: its copy and status are the shell's fixed copies, and its actions
  // are the shell's action area. Only its dialogs and native reads live here.
  return null;
}

/** Every WaniKani failure the bridge names, so a code from the wire is validated, not trusted. */
const WANIKANI_FAILURES: readonly WaniKaniFailure[] = [
  "OFFLINE",
  "TIMEOUT",
  "RATE_LIMITED",
  "SERVER",
  "TOO_LARGE",
  "MALFORMED",
  "STORAGE",
];

/** The WaniKani failure a bridge rejection carried; a bridge-level error is the network's own. */
function waniKaniFailureOf(error: unknown): WaniKaniFailure {
  const named = /^WK_([A-Z_]+)$/.exec(errorCode(error))?.[1];
  return WANIKANI_FAILURES.find((candidate) => candidate === named) ?? "SERVER";
}

/** The token check the onboarding flow hands the embedded connect form. */
async function connectWaniKani(token: string): Promise<WaniKaniConnectOutcome> {
  try {
    const result = await GatenWaniKani.connect(token);
    if (result.status === "invalidToken") {
      return {status: "invalidToken"};
    }
    return {status: "connected"};
  } catch (error) {
    return {status: "failed", failure: waniKaniFailureOf(error)};
  }
}

/**
 * Page 3: connect a WaniKani account with a personal access token. The connection form is the app's
 * own — the token is checked with WaniKani before it is stored — but onboarding owns the outcome and
 * stays here: a working token shows the connected state and a "Get started", never the review hub or
 * a study queue.
 *
 * The page reads the real store on arrival and again whenever [connectRevision] changes (the
 * embedded form stored a token), and reports what it found up to the flow, which is what draws the
 * connected accent on the artwork. Nothing here is inferred from a Skip or a return from the OS.
 */
function WaniKaniPage({
  step,
  onAdvance,
  saveError,
  onRetry,
  report,
  connectRevision,
  onConnectedChange,
  onOpenForm,
}: PageProps & {
  connectRevision: number;
  onConnectedChange: (connected: boolean) => void;
  onOpenForm: () => void;
}) {
  const {colors} = useTheme();
  // Null while the stored token is being read; true or false once it is known.
  const [connected, setConnected] = useState<boolean | null>(null);

  useEffect(() => {
    let live = true;
    void GatenWaniKani.isConfigured()
      .then((configured) => {
        if (live) setConnected(configured);
      })
      .catch(() => {
        // A read that fails is treated as "not connected": asking for a token is the recoverable
        // direction, and a stored token that works is accepted without asking again.
        if (live) setConnected(false);
      });
    return () => {
      live = false;
    };
  }, [connectRevision]);

  // The flow's accent follows the same real read the page shows, never a guess.
  useEffect(() => {
    onConnectedChange(connected === true);
  }, [connected, onConnectedChange]);

  const primary: Action =
    connected == null
      ? {label: "Checking…", onPress: () => {}, disabled: true}
      : connected
        ? {label: "Get started", onPress: () => onAdvance(3)}
        : {label: "Connect WaniKani", onPress: onOpenForm};

  const visual: StepVisual = {
    statusKey: `${connected == null ? "checking" : connected ? "connected" : "disconnected"}:${colors.onSurfaceVariant}`,
    status:
      connected === true ? (
        <View style={{gap: spacing.s}}>
          <Text style={[typeScale.bodySmall, NARRATIVE_LINE, {color: colors.onSurfaceVariant}]}>
            WaniKani is connected.
          </Text>
        </View>
      ) : null,
    primary,
    outlined: null,
    skipLabel: "Skip",
    skipAccessibilityLabel: "Skip WaniKani setup and finish",
    onSkip: () => onAdvance(3),
    saveError,
    onRetry,
  };

  // A layout effect, so the shell's action area is already this page's by the time the frame that
  // shows the page paints: the buttons never show a step's predecessor for a frame.
  useLayoutEffect(() => {
    report(visual);
  });

  // The page draws nothing itself: its copy and status are the shell's fixed copies, and its actions
  // are the shell's action area. Only its dialogs and native reads live here.
  return null;
}
