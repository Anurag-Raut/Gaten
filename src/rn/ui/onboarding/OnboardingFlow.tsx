/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run setup flow: three pages, one per thing a reader sets up before the app is theirs —
 * lookup mode, AnkiDroid, WaniKani — and two steps inside each page.
 *
 * The shell shows it in place of the tabs while the stored onboarding step is below 3, and it is
 * shown on the first launch only. Each page does, or hands off to, the real setup for its item and
 * reports the real status; each has its own Skip, and there is deliberately no "skip all" — the
 * reader skips one page at a time, and the last page's Skip finishes the flow. Nothing here creates,
 * deletes or changes any Anki or WaniKani data: a page reads what is already there and, at most,
 * opens the app that owns it.
 *
 * The shell is one persistent frame — the back row, the progress row, the artwork scene and the
 * scroller are mounted once and never swapped — and the three pages are controllers that fill it
 * with their step rows. A page owns the setup state for its item (the lookup grant, the reader, the
 * AnkiDroid read, the WaniKani token) and hands the shell the one action it offers; the shell draws
 * the frame and the artwork, so a step change moves ONE scene rather than replacing one page with
 * another.
 *
 * A page's own steps are its render: the rows grow in place as the device verifies them, and the
 * flow's auto-advance is the same verified gate the rows are built from (see `onboarding-steps`), so
 * a page cannot draw itself complete and advance while saying otherwise. The shell owns the step,
 * exactly as before: a page advances by writing the next step first, and the shell shows the next
 * page once that write lands. A write that cannot be saved keeps the page and offers a retry rather
 * than silently skipping the reader forward.
 */
import React, {memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState} from "react";
import {
  AccessibilityInfo,
  Alert,
  AppState,
  BackHandler,
  findNodeHandle,
  I18nManager,
  ScrollView,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from "react-native";
import {KeyboardAvoidingView, useReanimatedKeyboardAnimation} from "react-native-keyboard-controller";
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
import * as GatenPlatform from "../../native/GatenPlatform";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {WaniKaniFailure} from "../../native/GatenWaniKani";
import {downloadAnkiDroid} from "../anki/anki-actions";
import {Button, Icon, IconButton} from "../components";
import {PressableScale} from "../PressableScale";
import type {WaniKaniConnectOutcome} from "../review/review-store";
import {FAILURE_LABELS} from "../settings/WaniKaniSettingsScreen";
import {TextField, useLookupModeRequest} from "../settings/settings-components";
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
import {STEP_SCENE_MS, travelDirection} from "./onboarding-motion";
import {StepList, StepRow} from "./StepList";
import {WelcomeScreen} from "./WelcomeScreen";
import {shouldShowWelcome} from "./welcome-timeline";
import {
  actionAreaKey,
  ankiSteps,
  connectReadout,
  downloadPercent,
  downloadProgressText,
  formatMegabytes,
  lookupSteps,
  nextStep,
  shouldAutoAdvance,
  tokenToCheck,
  wanikaniPrimary,
  wanikaniSteps,
  type AdvanceGate,
} from "./onboarding-steps";
import {
  cancelDownload,
  classifyDownloadError,
  canCancelDownload,
  downloadMedium,
  getRecognitionSettings,
  setRecognitionMode,
  subscribeDownloadProgress,
  type DownloadFailure,
  type DownloadProgress,
} from "./recognition-port";

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

/** The artwork's accent fade when a WaniKani account is really connected. */
const ACCENT_FADE_MS = 300;

/**
 * The auto-advance beat: a page's own done state stays readable this long — the dot and its word —
 * before the flow writes the next step through the same guarded write the Continue button uses.
 */
const AUTO_ADVANCE_BEAT_MS = 600;

/**
 * The fire-time retry: a beat whose state gate holds but whose scene is mid-gesture or still
 * settling re-checks this often instead of firing, until the scene is idle or the gate drops.
 */
const AUTO_ADVANCE_RETRY_MS = 200;

/** The one header row: the bare back target's own 48 dp, and the gap it leaves before the artwork. */
const HEADER_ROW_HEIGHT = 48;
const HEADER_GAP = 10;
/** The back target's own appearance once, when a step with nowhere to go gains or loses it. */
const HEADER_BACK_MS = 170;

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

/**
 * A handoff's phasing on a 0→1 clock, shared by the step text and the step rows: the outgoing is
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

/** One action the footer can offer: the page's filled action, or its Skip. */
interface Action {
  label: string;
  onPress: () => void;
  disabled?: boolean;
}

/**
 * What a page hands the shell to draw: the one filled action it offers and its Skip. The heading and
 * explanation are the shell's fixed copies, so a page cannot restyle or swap them; the step rows and
 * every control inside them are the page's own render. Plain data, so the shell can keep the
 * previous page's view as a purely visual outgoing layer — rendering it again runs no page logic and
 * makes no second native call.
 */
interface StepVisual {
  primary: Action;
  /**
   * True when every step on this page was verified from the device: the flow's auto-advance gate.
   * The page keeps it false while anything of its own — a dialog, a read, a write — still owns the
   * scene.
   */
  done: boolean;
  skipLabel: string;
  skipAccessibilityLabel: string;
  onSkip: () => void;
  saveError: boolean;
  onRetry: () => void;
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
    </Animated.View>
  );
}

/**
 * The flow's one action area, drawn by the shell outside every page: it is the same element for the
 * whole flow, so a step change — or a swipe — only ever swaps a label, a disabled state and what the
 * press does. The filled action and the Skip are the two slots, with the save-error line above them.
 * The primary keeps its fill while a page is checking, and the buttons sit at the end of the page's
 * own scroller, so large text and short screens still reach them.
 */
const StepActions = memo(function StepActions({
  visual,
  busy,
}: {
  visual: StepVisual | null;
  busy: boolean;
}) {
  const {colors} = useTheme();
  if (visual == null) {
    return null;
  }
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
        <Button
          key="primary"
          label={visual.primary.label}
          onPress={visual.primary.onPress}
          disabled={visual.primary.disabled || busy}
          fullWidth
          surfaceWhileDisabled
        />
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
});

/**
 * The three-step progress track: three fixed quiet segments with one accent bar that sweeps to the
 * live step on the scene clock — the same travelling indicator, at a header's size.
 */
const ProgressTrack = memo(function ProgressTrack({scene}: {scene: SharedValue<number>}) {
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
});

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
  // The action area the shell draws, kept here so it survives every page swap, with the step it was
  // reported for: the auto-advance must read the committed page's done state, never a predecessor's
  // last report from the frame before the new page first reports.
  const [actions, setActions] = useState<{step: number; visual: StepVisual} | null>(null);
  // The key of the view [actions] currently holds, so a report that would draw the same buttons is
  // dropped — and the committed step stays in the comparison instead of cancelling itself out.
  const reportedActionKey = useRef<string | null>(null);
  // Held true for exactly the commit a step change lands on, so the live text paints its entering
  // pose while the clock's reset is still on its way to the UI thread.
  const [entering, setEntering] = useState(false);
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

  // The auto-advance session: one timer, owned by the flow, booked for one page visit. Every manual
  // navigation — Continue, Skip, Back, hardware Back, all through [write] — invalidates it
  // synchronously, as do the swipe's activation and a backgrounding. The fire
  // re-checks the live scene before it writes, and each visit fires at most once, so a still-mounted
  // last page cannot schedule a second write while the handoff to the tabs is in flight.
  const autoTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // The visit the beat is booked for and the visit it already fired for: a fired visit never
  // re-arms, even once its write settles, and a timer is only ever live for its own visit.
  const autoArmedVisit = useRef<number>(-1);
  const autoFiredVisit = useRef<number>(-1);
  // True from any invalidation — a manual press, a swipe activation, a backgrounding —
  // until the scene is idle again. The arming effect reads it, so the beat re-arms when it lifts.
  const [beatSuspended, setBeatSuspended] = useState(false);
  // True when this visit arrived by going back: the page may already read done, and Back must stay
  // put — the beat only arms for a visit that moved forward.
  const [arrivedBackwards, setArrivedBackwards] = useState(false);
  // The inputs the fire-time re-check reads; assigned during render, so the callback never reasons
  // from a stale closure.
  const autoInputs = useRef<{
    navRun: number;
    step: number;
    actions: {step: number; visual: StepVisual} | null;
    writePending: boolean;
    saveError: boolean;
    arrivedBackwards: boolean;
    screenReader: boolean;
    suspended: boolean;
  } | null>(null);

  const clearAutoAdvance = useCallback(() => {
    clearTimeout(autoTimer.current);
    autoTimer.current = null;
    // The suspension is state, not a ref: the arming effect re-runs on it, so the beat re-arms the
    // moment the scene is idle again (a cancelled swipe, a foreground return).
    setBeatSuspended(true);
  }, []);

  const write = useCallback(
    (next: number, onResult?: (saved: boolean) => void) => {
      // Every manual navigation runs through here: drop any booked beat before it can fire an
      // obsolete target behind the reader's back.
      clearAutoAdvance();
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
  const {width} = useWindowDimensions();
  // The Welcome screen: the state before page 1, never a step of its own and never persisted. It is
  // shown while the stored step is still 0, and once "Set up Gaten" is pressed it hands the frame to
  // the flow's own first page on the flow's page-transition clock — the same duration and curve a
  // step change uses, so the reader arrives on page 1 exactly as they would from a page change.
  const [welcome, setWelcome] = useState(() => shouldShowWelcome(step));
  const welcomeOut = useSharedValue(welcome ? 0 : 1);
  const finishWelcome = useCallback(() => setWelcome(false), []);
  const startWelcome = useCallback(() => {
    if (reducedMotion) {
      welcomeOut.value = 1;
      finishWelcome();
      return;
    }
    welcomeOut.value = withTiming(
      1,
      {duration: STEP_SCENE_MS, easing: PAGE_EASING_REANIMATED},
      (finished) => {
        "worklet";
        if (finished) runOnJS(finishWelcome)();
      },
    );
  }, [reducedMotion, welcomeOut, finishWelcome]);
  // The page beneath the Welcome screen waits behind it: it neither takes touches nor enters the
  // accessibility tree, and it drifts in as the Welcome screen drifts out.
  const shellStyle = useAnimatedStyle(() => ({
    opacity: welcomeOut.value,
    transform: [{translateX: (1 - welcomeOut.value) * width * 0.25}],
  }));
  // One clock for the whole scene. It holds the page the artwork is currently drawn at, in page
  // units, and is the only thing the artwork reads.
  const scene = useSharedValue(step);
  const entrance = useSharedValue(reducedMotion ? 1 : 0);
  // The artwork's recede into the WaniKani token form's anchor: onboarding draws its token field
  // inside the page's own step, so the scene is never receded and this stays a fixed zero the scene
  // still reads.
  const formProgress = useSharedValue(0);
  // The content's own clock: 0 at a step change, 1 when the new page has settled. Kept separate
  // from the scene so the content can trail the artwork by a beat.
  const contentProgress = useSharedValue(1);
  // The artwork's real-status accent: 1 only while a WaniKani account is really connected, as the
  // page's own native read reports it. Nothing else draws it.
  const success = useSharedValue(0);
  const [wanikaniConnected, setWanikaniConnected] = useState(false);
  // TalkBack: a fixed beat can cut the heading announcement off mid-speech, so with a screen reader
  // on the flow keeps the manual Continue and never times a page away.
  const [screenReader, setScreenReader] = useState(false);

  useEffect(() => {
    let live = true;
    void AccessibilityInfo.isScreenReaderEnabled()
      .then((enabled) => {
        if (live) setScreenReader(enabled);
      })
      .catch(() => {
        // Unknown: keep the default (auto-advance) rather than assume a screen reader is present.
      });
    const subscription = AccessibilityInfo.addEventListener("change", (enabled) => {
      if (live) setScreenReader(enabled);
    });
    return () => {
      live = false;
      subscription.remove();
    };
  }, []);

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
    // The session is over — cancelled or handed off — so the scene is idle and the beat may re-arm.
    setBeatSuspended(false);
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
    // The finger takes the scene over: the beat must not fire under a drag or a settle.
    clearAutoAdvance();
    swipeVisualSettled.current = false;
    swipeStepSeen.current = false;
    swipeOwnsClock.current = false;
  }, [clearAutoAdvance]);

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
    const outgoingDirection = travelDirection(nav.from, nav.step);
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
    // A Back visit never auto-forwards, however done its page already reads.
    setArrivedBackwards(step < nav.step);
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
  const direction = travelDirection(nav.from, nav.step);

  const report = useCallback(
    (visual: StepVisual) => {
      reported.current = visual;
      // The action area is the shell's own, drawn once for the whole flow: it takes the freshest view
      // whose buttons actually differ, so a step change swaps a label and nothing else. Everything
      // the shell reads is in the key — including the page's own verified [done], which can flip
      // while the buttons look identical — so nothing a page changes is ever dropped.
      const key = actionAreaKey({
        step,
        label: visual.primary.label,
        disabled: visual.primary.disabled === true,
        done: visual.done,
        saveError: visual.saveError,
        skipLabel: visual.skipLabel,
        skipAccessibilityLabel: visual.skipAccessibilityLabel,
      });
      if (reportedActionKey.current === key) {
        return;
      }
      reportedActionKey.current = key;
      setActions({step, visual});
    },
    [step],
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

  // The accent follows the real connection state and only the WaniKani page carries it, so it is
  // never lit on a page that has nothing to say about the account. Reduced motion sets it directly,
  // keeping the state without the sweep.
  const accentOn = wanikaniConnected && step === 2;
  useEffect(() => {
    success.value = reducedMotion
      ? accentOn
        ? 1
        : 0
      : withTiming(accentOn ? 1 : 0, {duration: ACCENT_FADE_MS, easing: PAGE_EASING_REANIMATED});
  }, [accentOn, reducedMotion, success]);

  // A new page starts fresh, and the incoming heading takes the reader's focus. The heading carries
  // the step in its own label, so one focus announcement says where the reader is and what the page
  // is — a separate step announcement would read it twice.
  useEffect(() => {
    // The page beneath the Welcome screen is out of the accessibility tree, so it must not take the
    // reader's focus either; the first page's heading is announced when the Welcome screen is gone.
    if (welcome) return;
    const node = findNodeHandle(headingRef.current);
    if (node != null) AccessibilityInfo.setAccessibilityFocus(node);
  }, [step, welcome]);

  // Android Back: while a step is being saved it does nothing at all — a second write must not race
  // the one in flight. Otherwise it goes back a page on pages 2 and 3, and on the first page is left
  // to the platform — which leaves the app without finishing the flow.
  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      if (persisting.current) {
        return true;
      }
      if (step === 0) {
        return false;
      }
      write(step - 1);
      return true;
    });
    return () => subscription.remove();
  }, [step, write]);

  // Auto-advance: when the committed page's own completion read says done, show the success beat —
  // long enough for the dot and its word — and then write the next step through the same guarded
  // write the Continue button and a swipe use. The booking carries the visit ([nav.run]) and the
  // step; a manual navigation, a swipe activation, a backgrounding or an unmount
  // all invalidate it synchronously. The fire re-checks the live scene — the swipe session, the
  // content clock, the write, the completion state — so nothing the render cannot see advances the
  // page, and a failed write stays on the page's own visible "Try again" with no auto-retry.
  autoInputs.current = {
    navRun: nav.run,
    step,
    actions,
    writePending,
    saveError,
    arrivedBackwards,
    screenReader,
    suspended: beatSuspended,
  };
  // A page reports [done] only when every step on it was verified from the device, so this holds
  // exactly when the committed page is complete and nothing of the flow's own is in flight.
  const doneReady =
    actions != null &&
    actions.step === step &&
    actions.visual.done &&
    !writePending &&
    !saveError &&
    !screenReader &&
    !beatSuspended &&
    !arrivedBackwards &&
    !welcome &&
    autoFiredVisit.current !== nav.run;

  const fireAutoAdvance = useCallback(() => {
    autoTimer.current = null;
    const inputs = autoInputs.current;
    if (inputs == null) return;
    // A swipe owns the scene from activation until its visual has settled AND its step has been
    // observed; writePending alone does not mean idle. A fire during a session would take the
    // button path and cancel the gesture, so it refuses here instead.
    const swipeIdle =
      swipePose.role.value === SWIPE_IDLE &&
      swipeTarget.current == null &&
      !swipeOwnsClock.current;
    // The one-shot booking is what makes a fire this visit's own: a stale timer from another visit,
    // or one whose beat already fired, refuses outright.
    if (autoArmedVisit.current !== inputs.navRun || autoFiredVisit.current === inputs.navRun) {
      return;
    }
    const gate: AdvanceGate = {
      verified:
        inputs.actions != null &&
        inputs.actions.step === inputs.step &&
        inputs.actions.visual.done,
      arrivedBackwards: inputs.arrivedBackwards,
      screenReader: inputs.screenReader,
      suspended: inputs.suspended,
      writePending: inputs.writePending || persisting.current,
      saveError: inputs.saveError,
      sceneIdle: swipeIdle,
      contentSettled: contentProgress.value >= 1,
    };
    if (!shouldAutoAdvance(gate)) {
      // The gate cannot be made true by waiting (a page whose steps are not verified never will be),
      // so it refuses; but one that is only waiting on the scene's own settle re-checks shortly
      // instead. A retry is still this visit's own timer — the arming effect clears whatever is
      // booked the moment a dep changes, and the booking check above refuses strays.
      if (!shouldAutoAdvance({...gate, sceneIdle: true, contentSettled: true})) return;
      autoTimer.current = setTimeout(fireAutoAdvance, AUTO_ADVANCE_RETRY_MS);
      return;
    }
    // The one-shot booking is spent only on the write that actually happens: a transient refusal
    // leaves the visit re-armable.
    autoFiredVisit.current = inputs.navRun;
    write(nextStep(inputs.step));
  }, [write, swipePose, contentProgress]);

  useEffect(() => {
    if (!doneReady) return;
    autoArmedVisit.current = nav.run;
    const timer = setTimeout(fireAutoAdvance, AUTO_ADVANCE_BEAT_MS);
    autoTimer.current = timer;
    return () => {
      // Unconditional: covers the armed timer and any fire-time retry, so nothing survives a visit
      // change or a gate drop.
      clearTimeout(autoTimer.current);
      autoTimer.current = null;
      autoArmedVisit.current = -1;
    };
  }, [doneReady, nav.run, beatSuspended, fireAutoAdvance]);

  // A fresh visit starts unsuspended, however the previous one was left.
  useEffect(() => {
    setBeatSuspended(false);
  }, [nav.run]);

  // A backgrounded app never advances a beat that was armed before it left; coming back lifts the
  // suspension, and the pages' own revalidations hold the gate until their reads land.
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") setBeatSuspended(false);
      else clearAutoAdvance();
    });
    return () => subscription.remove();
  }, [clearAutoAdvance]);

  const shared: PageProps = {
    step,
    onAdvance: write,
    saveError,
    onRetry: retry,
    report,
  };

  return (
    <View style={{flex: 1}}>
      <Animated.View
        style={[{flex: 1}, shellStyle]}
        pointerEvents={welcome ? "none" : "auto"}
        accessibilityElementsHidden={welcome}
        importantForAccessibility={welcome ? "no-hide-descendants" : "auto"}
      >
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
          swipePose={swipePose}
          headingRef={headingRef}
          actions={actions?.visual ?? null}
          onSwipeStep={swipeToStep}
          onSwipeStart={swipeStarted}
          onSwipeSettled={swipeSettled}
          onBack={
            step > 0
              ? () => {
                  if (persisting.current) return;
                  write(step - 1);
                }
              : null
          }
        >
          {step === 0 && <LookupPage {...shared} />}
          {step === 1 && <AnkiPage {...shared} />}
          {step === 2 && <WaniKaniPage {...shared} onConnectedChange={setWanikaniConnected} />}
        </OnboardingShell>
      </Animated.View>
      {welcome && <WelcomeScreen out={welcomeOut} onStart={startWelcome} />}
    </View>
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
  swipePose,
  headingRef,
  actions,
  onSwipeStep,
  onSwipeStart,
  onSwipeSettled,
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
  /** The swipe's pose: the scene clock plus the copy's fraction map. */
  swipePose: SwipePose;
  /** The heading the flow focuses when a page arrives: the committed copy's own heading. */
  headingRef: React.RefObject<Text | null>;
  /** The live action area, held by the flow so the buttons survive every page swap. */
  actions: StepVisual | null;
  /** Commits a swiped step through the flow's persisted write; `onResult` answers whether it saved. */
  onSwipeStep: (next: number, onResult: (saved: boolean) => void) => void;
  /** A new touch takes the session over; the previous settle can no longer report. */
  onSwipeStart: () => void;
  /** The swipe's settle landed; `accepted` says whether the step was a committed target. */
  onSwipeSettled: (accepted: boolean) => void;
  onBack: (() => void) | null;
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width, height, fontScale} = useWindowDimensions();
  const inset = contentInsetFor(width);
  // Gestures still change the step with reduced motion on; they just never draw the drag or settle.
  const reducedMotion = useReducedMotion();
  // The back target is one element for the whole flow and is always mounted: only its opacity
  // changes, briefly and once, when the first step gains the target or the reader returns to it.
  // Nothing about a save's progress ever touches it — the write's own synchronous guard, not a dim,
  // is what keeps the arrow from racing a save.
  const hasBack = onBack != null;
  const backOpacity = useSharedValue(hasBack ? 1 : 0);
  useEffect(() => {
    backOpacity.value = reducedMotion
      ? hasBack
        ? 1
        : 0
      : withTiming(hasBack ? 1 : 0, {duration: HEADER_BACK_MS, easing: PAGE_EASING_REANIMATED});
  }, [hasBack, reducedMotion, backOpacity]);
  const backStyle = useAnimatedStyle(() => ({opacity: backOpacity.value}));
  // The height left for the scene and the scroller together; the window stands in until the first
  // layout, so a page decides its artwork mode from its first frame.
  const [region, setRegion] = useState<number | null>(null);
  const regionHeight =
    region ?? Math.max(0, height - insets.top - insets.bottom - HEADER_ROW_HEIGHT - HEADER_GAP);
  const decidedMode = artworkModeFor(fontScale, regionHeight);
  const [mode, setMode] = useState<ArtworkMode>(decidedMode);
  if (mode !== decidedMode) {
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

  // The page's own steps ride the same pose as the copy above them: during a swipe they travel on the
  // artwork's page coordinate at full opacity, so the list under the finger is whole and slides with
  // the heading; on the button path they arrive on the content clock. Only the live page's steps
  // exist, so before the commit this is the leaving page's list sliding out, and after it the
  // arriving page's list lands exactly where the settle left it — one continuous movement.
  const stepsStyle = useAnimatedStyle(() => {
    if (swipePose.role.value !== SWIPE_IDLE) {
      return {
        opacity: 1,
        transform: [{translateX: (step - swipePose.scene.value) * swipeDistance}],
      };
    }
    const pose = entering ? textArrival(0, direction) : textArrival(contentProgress.value, direction);
    return {opacity: pose.opacity, transform: [{translateX: pose.x}]};
  });

  // The step swipe: one horizontal pan that previews the adjacent page on the same scene clock the
  // artwork and the track already draw with, then commits through the flow's persisted write. It
  // takes only clearly horizontal intent and gives up early on vertical movement, so the page still
  // scrolls and the buttons still take their taps. It is off while a save is in flight, and it never
  // changes the step itself — a swipe is exactly the Continue write.
  const dragBase = useSharedValue(0);
  const commitDistance = Math.min(width * SWIPE_COMMIT_FRACTION, SWIPE_COMMIT_MAX);
  const rtl = I18nManager.isRTL ? -1 : 1;
  const swipeEnabled = !writePending;

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
          // Superseded before the finger lifted (a step change, an ended session): this
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
  // button's own timing can never be written over.
  useAnimatedReaction(
    () => swipePose.q.value,
    (progress) => {
      if (swipePose.role.value < SWIPE_SETTLE) return;
      scene.value =
        swipePose.sceneFrom.value + (swipePose.sceneTo.value - swipePose.sceneFrom.value) * progress;
    },
  );

  // A new page starts at its own top: the scroller is one instance for the whole flow, so a reader
  // who scrolled to reach the actions does not arrive on the next page already scrolled.
  const scrollRef = useRef<ScrollView>(null);
  // The keyboard takes the bottom of the page, and the artwork above the scroller would take most of
  // what is left. So the artwork recedes on the keyboard's own animation (the same recede the form
  // anchor uses) and the scroller's keyboard-avoiding view hands the room to the copy and the steps.
  // Reduced motion lands on the end state at once instead of following the keyboard's travel.
  const {progress: keyboardProgress} = useReanimatedKeyboardAnimation();
  useAnimatedReaction(
    () => (reducedMotion ? Math.round(keyboardProgress.value) : keyboardProgress.value),
    (value) => {
      formProgress.value = value;
    },
  );
  useEffect(() => {
    scrollRef.current?.scrollTo({y: 0, animated: false});
  }, [step]);

  return (
    <SafeAreaView style={{flex: 1, backgroundColor: colors.background}} edges={["top", "left", "right"]}>
      {/* One header row: the bare back target, the compact step track centred on the row, and the
          step in words. Every step reserves the back target's slot, so neither the row's height nor
          the track's centre moves as the reader goes back and forward. */}
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
          {/* Always mounted, so no step change — and no save — remounts or restyles the arrow. On the
              first step it is the same element at zero opacity, inert and out of the accessibility
              tree; while a save is in flight its press is the existing no-op, whose protection is the
              flow's synchronous write guard rather than any change of appearance. */}
          <Animated.View
            style={backStyle}
            pointerEvents={hasBack ? "auto" : "none"}
            accessibilityElementsHidden={!hasBack}
            importantForAccessibility={hasBack ? "auto" : "no-hide-descendants"}
          >
            <IconButton
              name="arrow_back"
              accessibilityLabel="Back"
              onPress={writePending ? () => {} : onBack ?? (() => {})}
              color={colors.onSurface}
            />
          </Animated.View>
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
            <KeyboardAvoidingView style={{flex: 1}} behavior="padding">
              <ScrollView
                ref={scrollRef}
                // A swipe's text rides the artwork's page coordinate, so copies travel outside the
                // slot: clip at this viewport, and keep the three fixed copies attached on Android —
                // a detached peer would not draw as its UI-thread transform brings it back in.
                style={{overflow: "hidden"}}
                removeClippedSubviews={false}
                showsVerticalScrollIndicator={false}
                showsHorizontalScrollIndicator={false}
                keyboardShouldPersistTaps="handled"
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
                      swipeDistance={swipeDistance}
                    />
                  ))}
                </View>
                {/* The committed page's own steps, arriving on the same clock as the copy above. */}
                <Animated.View key="steps" style={[{flexGrow: 1}, stepsStyle]}>
                  {children}
                </Animated.View>
                {/* The one action area, outside every page: the same element for the whole flow, so a
                    step change or a swipe only swaps a label, a disabled state and what the press
                    does. It stays in the scroller's own flow, so large text and short screens reach it. */}
                <StepActions key="actions" visual={actions} busy={writePending} />
              </ScrollView>
            </KeyboardAvoidingView>
          </View>
        </View>
      </GestureDetector>
    </SafeAreaView>
  );
}


type LookupState = "checking" | "on" | "off" | "failed";

/** The Enhanced install, as the reader rows show it. */
type OcrState = "checking" | "absent" | "downloading" | "installed";

/** Where a token is created, opened in the browser from the WaniKani page. */
const WANIKANI_TOKEN_URL = "https://www.wanikani.com/settings/personal_access_tokens";

/** The "Recommended" marker beside the reader it names. */
function RecommendedPill({label}: {label: string}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        backgroundColor: colors.accentSubtle,
        borderRadius: 6,
        paddingHorizontal: spacing.s,
        paddingVertical: 1,
      }}
    >
      <Text style={[typeScale.labelSmall, {color: colors.onPrimaryContainer}]}>{label}</Text>
    </View>
  );
}

/** A radio: an accent ring and dot when chosen, a quiet outline when not. */
function RadioMark({selected}: {selected: boolean}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        width: 20,
        height: 20,
        borderRadius: 10,
        borderWidth: 2,
        borderColor: selected ? colors.accent : colors.outlineVariant,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      {selected && (
        <View style={{width: 10, height: 10, borderRadius: 5, backgroundColor: colors.accent}} />
      )}
    </View>
  );
}

/** One selectable reader: its name, its mark, and the one line that stands for its state. */
function ReaderRow({
  title,
  badge,
  summary,
  selected,
  disabled,
  hint,
  onPress,
}: {
  title: string;
  badge?: string;
  summary: string | null;
  selected: boolean;
  disabled?: boolean;
  hint?: string;
  onPress: () => void;
}) {
  const {colors} = useTheme();
  return (
    <PressableScale
      accessibilityRole="radio"
      accessibilityState={{selected, disabled: disabled === true}}
      accessibilityLabel={badge != null ? `${title}, ${badge}` : title}
      accessibilityHint={hint}
      disabled={disabled}
      onPress={onPress}
      style={{
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.m,
        minHeight: 48,
        paddingVertical: spacing.s,
        opacity: disabled === true ? 0.5 : 1,
      }}
    >
      <View style={{flex: 1, minWidth: 0}}>
        <View style={{flexDirection: "row", alignItems: "center", gap: spacing.s}}>
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
          {badge != null && <RecommendedPill label={badge} />}
        </View>
        {summary != null && (
          <Text
            style={[typeScale.description, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}
          >
            {summary}
          </Text>
        )}
      </View>
      <RadioMark selected={selected} />
    </PressableScale>
  );
}

/** The 4 dp download bar under its own percentage line; the bar itself is decorative. */
function DownloadBar({percent}: {percent: number}) {
  const {colors} = useTheme();
  return (
    <View
      accessible
      accessibilityRole="progressbar"
      accessibilityValue={{min: 0, max: 100, now: percent, text: `${percent}%`}}
      style={{height: 4, borderRadius: 2, backgroundColor: colors.outlineVariant, overflow: "hidden"}}
    >
      <View style={{height: 4, width: `${percent}%`, backgroundColor: colors.accent}} />
    </View>
  );
}

/** The one line a failed download leaves: what went wrong, in the reader's own words. */
function downloadFailureText(failure: DownloadFailure): string {
  switch (failure) {
    case "offline":
      return "Couldn't download Enhanced: no connection.";
    case "storage":
      return "Couldn't download Enhanced: not enough storage on this device";
    case "verify":
      return "Couldn't download Enhanced: the file did not pass its check";
    case "cancelled":
      return "Cancelled.";
    default:
      return "Couldn't download Enhanced.";
  }
}

/**
 * Page 1: the accessibility grant that lookup mode is, read afresh on every arrival, and the reader
 * the lookups will be read with.
 *
 * The grant is the step that advances the page; the better reader is the second row's own
 * verification, and the bundled Standard default never verifies it, so the reader either installs and
 * selects Enhanced or resolves the page with its own "Done". Nothing here is inferred from a
 * return to the app: the grant is re-read, and an "off" stays off, however the reader came back.
 */
function LookupPage({step, onAdvance, saveError, onRetry, report}: PageProps) {
  const {colors} = useTheme();
  const {request, dialog, open: dialogOpen} = useLookupModeRequest();
  const [lookup, setLookup] = useState<LookupState>("checking");
  // Whether the app has come back to the foreground while this page was up: only then does an "off"
  // read mean "you returned without enabling it", rather than the first look at the page.
  const [returned, setReturned] = useState(false);
  const [medium, setMedium] = useState<OcrState>("checking");
  const [mediumBytes, setMediumBytes] = useState(0);
  const [mediumSelected, setMediumSelected] = useState(false);
  const [progress, setProgress] = useState<DownloadProgress | null>(null);
  const [failure, setFailure] = useState<DownloadFailure | null>(null);
  // True while a foreground re-read is on its way: the shown success stays, but the beat waits.
  const [revalidating, setRevalidating] = useState(false);
  // True while the installed reader's own mode selection is being written: the advance waits for it.
  const [modeSettling, setModeSettling] = useState(false);
  const readSeq = useRef(0);
  const ocrSeq = useRef(0);
  // The mode switch fires once per observation of an installed reader, never once per re-read.
  const mediumApplied = useRef(false);
  const unsubscribe = useRef<(() => void) | null>(null);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      unsubscribe.current?.();
      unsubscribe.current = null;
    };
  }, []);

  const read = useCallback((showChecking: boolean) => {
    readSeq.current += 1;
    const seq = readSeq.current;
    if (showChecking) setLookup("checking");
    else setRevalidating(true);
    void GatenAppInfo.isLookupModeActive()
      .then((active) => {
        if (!mounted.current || seq !== readSeq.current) return;
        setLookup(active ? "on" : "off");
        setRevalidating(false);
      })
      .catch(() => {
        if (!mounted.current || seq !== readSeq.current) return;
        setLookup("failed");
        setRevalidating(false);
      });
  }, []);

  /** Reads the recognition settings and, once the reader is installed, selects it exactly once. */
  const readOcr = useCallback((showChecking: boolean) => {
    ocrSeq.current += 1;
    const seq = ocrSeq.current;
    if (showChecking) setMedium("checking");
    void getRecognitionSettings()
      .then((settings) => {
        if (!mounted.current || seq !== ocrSeq.current) return;
        const engine = settings.engines.find((candidate) => candidate.id === "medium");
        setMediumBytes(engine?.downloadBytes ?? 0);
        setMediumSelected(settings.mode === "medium");
        setMedium(
          settings.downloading.includes("medium")
            ? "downloading"
            : engine?.installed
              ? "installed"
              : "absent",
        );
        // An install is only used when it is the selected mode, so one that finishes here — or was
        // already on the device when the reader arrived — selects itself. Skipped, Standard stays.
        // The advance waits for this selection write to settle, whatever it answers.
        if (engine?.installed && settings.mode !== "medium" && !mediumApplied.current) {
          mediumApplied.current = true;
          setModeSettling(true);
          void setRecognitionMode("medium")
            .catch(() => {})
            .finally(() => {
              if (mounted.current) setModeSettling(false);
            });
        }
      })
      .catch(() => {
        // No error state of its own: the row simply reflects not installed.
        if (!mounted.current || seq !== ocrSeq.current) return;
        setMedium("absent");
      });
  }, []);

  // The grant is given in system Settings, so it is read on arrival and again every time the app
  // comes back to the foreground; a return is never treated as success. The recognition settings
  // ride the same reads, so a download that finishes elsewhere resolves while the page is up.
  useEffect(() => {
    read(true);
    readOcr(true);
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        setReturned(true);
        read(false);
        readOcr(false);
      }
    });
    return () => subscription.remove();
  }, [read, readOcr]);

  // A download is native module state that outlives this page, so while one runs it is observed at a
  // low rate until it lands or the page goes — the settings screen's own pattern.
  useEffect(() => {
    if (medium !== "downloading") return;
    const timer = setInterval(() => readOcr(false), 2000);
    return () => clearInterval(timer);
  }, [medium, readOcr]);

  /** The install itself: progress rides the module's own events, and cancel is the row's own tap. */
  const startDownload = useCallback(() => {
    if (!mounted.current) return;
    setFailure(null);
    setProgress({engine: "medium", phase: "downloading", bytesDone: 0, bytesTotal: mediumBytes});
    setMedium("downloading");
    unsubscribe.current?.();
    unsubscribe.current = subscribeDownloadProgress((next) => {
      if (mounted.current) setProgress(next);
    });
    void downloadMedium().then(
      () => {
        unsubscribe.current?.();
        unsubscribe.current = null;
        if (mounted.current) {
          setProgress(null);
          readOcr(false);
        }
      },
      (error: unknown) => {
        unsubscribe.current?.();
        unsubscribe.current = null;
        if (!mounted.current) return;
        setProgress(null);
        setFailure(classifyDownloadError(error));
        setMedium("absent");
      },
    );
  }, [mediumBytes, readOcr]);

  /** A Download tap: nothing is fetched until the reader confirms, as the settings screen does. */
  const confirmDownload = useCallback(() => {
    if (medium === "downloading") return;
    Alert.alert(
      "Download Enhanced?",
      mediumBytes > 0
        ? `Enhanced is ${formatMegabytes(mediumBytes)}. Wi-Fi is recommended. Downloading on mobile data may incur charges.`
        : "Wi-Fi is recommended. Downloading on mobile data may incur charges.",
      [
        {text: "Cancel", style: "cancel"},
        {
          text: "Download",
          onPress: () => {
            if (mounted.current) startDownload();
          },
        },
      ],
      {cancelable: true},
    );
  }, [medium, mediumBytes, startDownload]);

  const selectMedium = useCallback(() => {
    if (medium !== "installed" || mediumSelected) return;
    void setRecognitionMode("medium")
      .then(() => {
        if (mounted.current) setMediumSelected(true);
      })
      .catch(() => {});
  }, [medium, mediumSelected]);

  const selectMlKit = useCallback(() => {
    if (!mediumSelected) return;
    void setRecognitionMode("ml_kit")
      .then(() => {
        if (mounted.current) setMediumSelected(false);
      })
      .catch(() => {});
  }, [mediumSelected]);

  const steps = lookupSteps({lookup, medium, mediumSelected});
  const active = steps.step1 === "current" ? 1 : 2;

  const primary: Action =
    active === 2
      ? {label: "Done", onPress: () => onAdvance(nextStep(step))}
      : lookup === "checking"
        ? {label: "Checking\u2026", onPress: () => {}, disabled: true}
        : lookup === "failed"
          ? {label: "Try again", onPress: () => read(true)}
          : {label: "Turn on lookup mode", onPress: () => request()};

  const visual: StepVisual = {
    primary,
    // The page verifies only when the grant is on *and* the better reader is installed and selected;
    // the disclosure dialog and every read or write in flight hold the beat.
    done:
      steps.verified &&
      !dialogOpen &&
      !revalidating &&
      !modeSettling &&
      medium !== "checking" &&
      medium !== "downloading",
    skipLabel: "Skip",
    skipAccessibilityLabel: "Skip lookup mode setup",
    onSkip: () => onAdvance(nextStep(step)),
    saveError,
    onRetry,
  };

  // A layout effect, so the shell's action area is already this page's by the time the frame that
  // shows the page paints: the buttons never show a step's predecessor for a frame.
  useLayoutEffect(() => {
    report(visual);
  });

  const progressShown =
    progress ?? {engine: "medium" as const, phase: "downloading" as const, bytesDone: 0, bytesTotal: mediumBytes};
  const mediumSummary =
    medium === "installed"
      ? "Installed \u00b7 offline"
      : medium === "downloading"
        ? null
        : mediumBytes > 0
          ? `${formatMegabytes(mediumBytes)} download`
          : "Download";

  return (
    <>
      {dialog}
      <StepList style={{marginTop: spacing.l}}>
        <StepRow
          index={1}
          total={2}
          status={steps.step1}
          title={steps.step1 === "done" ? "Lookup mode is on" : "Turn on lookup mode"}
          detail="Android needs Gaten as an accessibility service."
        >
          {returned && lookup === "off" && (
            <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
              Still off. Turn on Gaten in accessibility settings.
            </Text>
          )}
        </StepRow>
        <StepRow
          index={2}
          total={2}
          status={steps.step2}
          previousStatus={steps.step1}
          title="Choose text recognition"
          summary="Standard or Enhanced"
        >
          <View>
            <ReaderRow
              title="Standard"
              summary="Included \u00b7 offline"
              hint="Japanese text, included with Gaten"
              selected={!mediumSelected}
              disabled={medium === "downloading"}
              onPress={selectMlKit}
            />
            <ReaderRow
              title="Enhanced"
              badge="Recommended"
              summary={mediumSummary}
              hint={
                medium === "installed"
                  ? "Installed. Use it for screen and novel text"
                  : "Downloads Enhanced for screen and novel text"
              }
              selected={mediumSelected}
              disabled={medium === "downloading"}
              onPress={medium === "installed" ? selectMedium : confirmDownload}
            />
            {medium === "downloading" && (
              <View style={{gap: spacing.s, paddingBottom: spacing.s}}>
                <Text
                  accessibilityLiveRegion="polite"
                  style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}
                >
                  {downloadProgressText(progressShown)}
                </Text>
                <DownloadBar percent={downloadPercent(progressShown)} />
                {canCancelDownload() && (
                <Button
                  variant="text"
                  label="Cancel"
                  onPress={() => {
                    void cancelDownload().catch(() => {});
                  }}
                  fullWidth
                />
                )}
              </View>
            )}
            {failure != null && medium !== "downloading" && (
              <View style={{gap: spacing.s, paddingBottom: spacing.s}}>
                <Text
                  accessibilityLiveRegion="polite"
                  style={[typeScale.bodySmall, {color: colors.error, flexShrink: 1}]}
                >
                  {downloadFailureText(failure)}
                </Text>
                <Button
                  variant="text"
                  label={failure === "cancelled" ? "Download" : "Try again"}
                  onPress={confirmDownload}
                  fullWidth
                />
              </View>
            )}
          </View>
        </StepRow>
      </StepList>
    </>
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
    return typeof (error as {code: unknown}).code === "string"
      ? (error as {code: string}).code
      : "";
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

/**
 * Page 2: AnkiDroid installed, allowed and with a collection to read.
 *
 * The first row is the app being on the device at all; the second is the collection really being
 * read. A denied permission, a collection that is not set up or a read that failed is installed but
 * not read, so the row says what to do next and the page never verifies. The permission prompt
 * fires only on the reader's own tap.
 */
function AnkiPage({step, onAdvance, saveError, onRetry, report}: PageProps) {
  const {colors} = useTheme();
  const [state, setState] = useState<AnkiPageState>({kind: "checking"});
  // True while a foreground re-read is on its way: the shown success stays, but the beat waits.
  const [revalidating, setRevalidating] = useState(false);
  // Whether the app has come back to the foreground while this page was up: only then does a
  // still-missing AnkiDroid read as "you returned without installing it".
  const [returned, setReturned] = useState(false);
  const readSeq = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const read = useCallback((showChecking: boolean) => {
    readSeq.current += 1;
    const seq = readSeq.current;
    if (showChecking) setState({kind: "checking"});
    else setRevalidating(true);
    void (async () => {
      // One terminal state per read, applied once at the end: an early return mid-read leaves the
      // revalidation flag to the read that actually lands.
      let next: AnkiPageState;
      try {
        const status = await GatenAnki.status();
        if (!mounted.current || seq !== readSeq.current) return;
        if (status === "notInstalled") {
          next = {kind: "notInstalled"};
        } else if (status === "needsPermission") {
          next = {kind: "needsPermission"};
        } else {
          const collection = await GatenAnki.collection();
          if (!mounted.current || seq !== readSeq.current) return;
          next = {kind: "connected", empty: collection.decks.length === 0};
        }
      } catch (error) {
        if (!mounted.current || seq !== readSeq.current) return;
        switch (ankiFailureOf(error)) {
          case "NOT_INSTALLED":
            next = {kind: "notInstalled"};
            break;
          case "PERMISSION":
            next = {kind: "needsPermission"};
            break;
          case "NOT_SET_UP":
            next = {kind: "notSetUp"};
            break;
          default:
            next = {kind: "failure"};
        }
      }
      if (!mounted.current) return;
      setState(next);
      setRevalidating(false);
    })();
  }, []);

  // The store, the permission dialog and AnkiDroid's own collection all change outside this page, so
  // the status is read on arrival and again on every foreground return.
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

  const grant = () => {
    void GatenAnki.requestPermission()
      .then((granted) => {
        if (granted) read(false);
        else if (mounted.current) setState({kind: "denied"});
      })
      .catch(() => {
        if (mounted.current) setState({kind: "denied"});
      });
  };

  const steps = ankiSteps({
    status: state.kind,
    collectionEmpty: state.kind === "connected" ? state.empty : false,
  });
  const notInstalled = state.kind === "notInstalled";

  const primary: Action =
    state.kind === "checking"
      ? {label: "Checking\u2026", onPress: () => {}, disabled: true}
      : steps.step1 === "current"
        ? notInstalled && returned
          ? {label: "Check again", onPress: () => read(true)}
          : {label: "Install AnkiDroid", onPress: () => void downloadAnkiDroid()}
        : state.kind === "needsPermission"
          ? {label: "Allow access", onPress: grant}
          : state.kind === "notSetUp"
            ? {label: "Open AnkiDroid", onPress: openAnkiDroid}
            : state.kind === "denied" || state.kind === "failure"
              ? {label: "Try again", onPress: () => read(true)}
              : {label: "Done", onPress: () => onAdvance(nextStep(step))};

  let step1Note: React.ReactNode = null;
  if (notInstalled && returned) {
    step1Note = (
      <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>Not installed yet.</Text>
    );
  }
  let step2Note: React.ReactNode = null;
  if (state.kind === "needsPermission") {
    step2Note = (
      <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
        Allow Gaten to read your AnkiDroid collection.
      </Text>
    );
  } else if (state.kind === "notSetUp") {
    step2Note = (
      <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
        Open AnkiDroid once to finish setup.
      </Text>
    );
  } else if (state.kind === "denied") {
    step2Note = (
      <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
        Access not allowed.
      </Text>
    );
  } else if (state.kind === "failure") {
    step2Note = (
      <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
        Couldn't read AnkiDroid.
      </Text>
    );
  } else if (state.kind === "connected" && state.empty) {
    step2Note = (
      <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>
        Create or import a deck when you're ready.
      </Text>
    );
  }

  const visual: StepVisual = {
    primary,
    done: steps.verified && !revalidating,
    skipLabel: "Skip",
    skipAccessibilityLabel: "Skip Anki setup",
    onSkip: () => onAdvance(nextStep(step)),
    saveError,
    onRetry,
  };

  // A layout effect, so the shell's action area is already this page's by the time the frame that
  // shows the page paints: the buttons never show a step's predecessor for a frame.
  useLayoutEffect(() => {
    report(visual);
  });

  return (
    <StepList style={{marginTop: spacing.l}}>
      <StepRow
        index={1}
        total={2}
        status={steps.step1}
        title={steps.step1 === "done" ? "AnkiDroid is installed" : "Install AnkiDroid"}
        detail="A separate app from Google Play."
      >
        {step1Note}
      </StepRow>
      <StepRow
        index={2}
        total={2}
        status={steps.step2}
        previousStatus={steps.step1}
        title="Allow access"
        detail="Read decks, record answers, add words."
        summary="Read decks, record answers, add words."
      >
        {step2Note}
      </StepRow>
    </StepList>
  );
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

/** The token check the WaniKani page hands the bridge. */
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

/** The one line a press on the empty primary leaves under the field. */
const PENDING_TOKEN_HINT = "Paste your token to connect.";

/** One numbered line of the WaniKani how-to; a key term takes the ink colour and the weight. */
function HowToLine({number, children}: {number: number; children: React.ReactNode}) {
  const {colors} = useTheme();
  return (
    <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
      <Text style={{fontWeight: "600", color: colors.onSurface}}>{`${number}. `}</Text>
      {children}
    </Text>
  );
}

/**
 * Page 3: connect a WaniKani account with a personal access token.
 *
 * The token is checked with WaniKani before it is stored, so the page's own rows are done only once
 * the account is really connected — there is no partial state, and a rejected token or a network
 * failure leaves the page exactly where it was. The typed token lives here, so the browser trip to
 * create it and back does not throw it away.
 *
 * The reader pastes first: "Paste it here" is the current step, and "Create a token" is an available
 * row they open for the how-to. The primary is enabled even with an empty field, where it points the
 * reader at the field instead of doing nothing.
 */
function WaniKaniPage({
  onAdvance,
  saveError,
  onRetry,
  report,
  onConnectedChange,
}: PageProps & {
  onConnectedChange: (connected: boolean) => void;
}) {
  const {colors} = useTheme();
  // The emphasis the how-to's key words wear, shared by its numbered lines.
  const strong = {fontWeight: "600" as const, color: colors.onSurface};
  // Null while the stored token is being read; true or false once it is known.
  const [connected, setConnected] = useState<boolean | null>(null);
  const [revalidating, setRevalidating] = useState(false);
  const [token, setToken] = useState("");
  const [showToken, setShowToken] = useState(false);
  // The line under the field, and whether it is a failure or the "paste your token" prompt.
  const [message, setMessage] = useState<{text: string; tone: "error" | "hint"} | null>(null);
  const [retry, setRetry] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [showHowTo, setShowHowTo] = useState(false);
  const [foregroundRev, setForegroundRev] = useState(0);
  const tokenInputRef = useRef<TextInput>(null);
  const readSeq = useRef(0);
  // The token and the in-flight guard the connect handler reads. The shell stores this page's primary
  // once and keeps it, so a handler that closed over a keystroke's snapshot would keep checking the
  // first character the reader typed; the handler reads the live values through these refs instead.
  const tokenRef = useRef("");
  tokenRef.current = token;
  const connectingRef = useRef(false);
  connectingRef.current = connecting;
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    readSeq.current += 1;
    const seq = readSeq.current;
    let live = true;
    void GatenWaniKani.isConfigured()
      .then((configured) => {
        if (live && seq === readSeq.current) {
          setConnected(configured);
          setRevalidating(false);
        }
      })
      .catch(() => {
        // A read that fails is treated as "not connected": asking for a token is the recoverable
        // direction, and a stored token that works is accepted without asking again.
        if (live && seq === readSeq.current) {
          setConnected(false);
          setRevalidating(false);
        }
      });
    return () => {
      live = false;
    };
  }, [foregroundRev]);

  useEffect(() => {
    const subscription = AppState.addEventListener("change", (next) => {
      if (next === "active") {
        setRevalidating(true);
        setForegroundRev((current) => current + 1);
      }
    });
    return () => subscription.remove();
  }, []);

  // The flow's accent follows the same real read the page shows, never a guess.
  useEffect(() => {
    onConnectedChange(connected === true);
  }, [connected, onConnectedChange]);

  // Stable on purpose: the shell keeps this handler as the page's primary, so it must read the live
  // token and the live in-flight guard rather than the values captured when it was made.
  const connect = useCallback(() => {
    const value = tokenToCheck(() => ({token: tokenRef.current, busy: connectingRef.current}));
    if (value == null) return;
    setConnecting(true);
    setMessage(null);
    void connectWaniKani(value)
      .then((outcome) => {
        const readout = connectReadout(outcome, FAILURE_LABELS);
        if (!mounted.current) return;
        setMessage(readout.message == null ? null : {text: readout.message, tone: "error"});
        setRetry(readout.retry);
        if (outcome.status === "connected") {
          setToken("");
          setConnected(true);
        }
      })
      .finally(() => {
        if (mounted.current) setConnecting(false);
      });
  }, []);

  // Typing anything into the field satisfies the "paste your token" prompt, so it goes away.
  const onTokenChange = useCallback((next: string) => {
    setToken(next);
    setMessage((current) => (current?.tone === "hint" ? null : current));
  }, []);

  const steps = wanikaniSteps({connected});

  // The shell keeps this page's primary once (its report key does not change as the reader types), so
  // the press reads the live facts through a ref: what the field holds now decides connect-or-prompt.
  const intent = wanikaniPrimary({connected, connecting, retry, token});
  const intentRef = useRef(intent);
  intentRef.current = intent;
  const onAdvanceRef = useRef(onAdvance);
  onAdvanceRef.current = onAdvance;
  const onPrimary = useCallback(() => {
    switch (intentRef.current.intent) {
      case "connect":
        connect();
        return;
      case "advance":
        onAdvanceRef.current(3);
        return;
      case "prompt":
        setMessage({text: PENDING_TOKEN_HINT, tone: "hint"});
        tokenInputRef.current?.focus();
        return;
      default:
        return;
    }
  }, [connect]);

  const primary: Action = {
    label: intent.label,
    disabled: intent.disabled,
    onPress: onPrimary,
  };

  const visual: StepVisual = {
    primary,
    done: steps.verified && !revalidating && !connecting,
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

  return (
    <StepList style={{marginTop: spacing.l}}>
        <StepRow
          index={1}
          total={2}
          status={steps.step1}
          title="Create a token"
          hint="Need a token? Show me how"
          onPress={() => setShowHowTo((current) => !current)}
          expanded={showHowTo}
          accessibilityHint="Shows how to create a WaniKani token"
        >
        <View style={{gap: spacing.s}}>
          <HowToLine number={1}>
            Open wanikani.com <Text style={strong}>settings</Text>, then{" "}
            <Text style={strong}>Personal Access Tokens</Text>.
          </HowToLine>
          <HowToLine number={2}>
            Tap <Text style={strong}>Generate a new token</Text>.
          </HowToLine>
          <HowToLine number={3}>
            Select <Text style={strong}>every permission</Text>.
          </HowToLine>
          <HowToLine number={4}>
            Copy the token, come back here and <Text style={strong}>paste it</Text>.
          </HowToLine>
          <PressableScale
            accessibilityRole="link"
            accessibilityLabel="Open wanikani.com"
            accessibilityHint="Opens your WaniKani personal access tokens in the browser"
            onPress={() => {
              void GatenPlatform.openUrl(WANIKANI_TOKEN_URL).catch(() => {});
            }}
            style={{
              flexDirection: "row",
              alignItems: "center",
              gap: spacing.s,
              minHeight: 48,
              justifyContent: "flex-start",
            }}
          >
            <Text style={[typeScale.bodyMedium, {color: colors.accent}]}>Open wanikani.com</Text>
            <Icon name="open_in_new" size={16} color={colors.accent} />
          </PressableScale>
        </View>
      </StepRow>
      <StepRow
        index={2}
        total={2}
        status={steps.step2}
        previousStatus={steps.step1}
        title="Paste it here"
      >
        <View style={{position: "relative", justifyContent: "center"}}>
          <TextField
            inputRef={tokenInputRef}
            value={token}
            onChangeText={onTokenChange}
            placeholder="Paste your token"
            secureTextEntry={!showToken}
            autoCapitalize="none"
            autoCorrect={false}
            editable={!connecting}
            returnKeyType="go"
            accessibilityLabel="WaniKani personal access token"
            onSubmitEditing={connect}
            style={{paddingRight: 72}}
          />
          <View
            style={{position: "absolute", right: 0, top: 0, bottom: 0, justifyContent: "center"}}
          >
            <Button
              variant="text"
              label={showToken ? "Hide" : "Show"}
              accessibilityLabel={showToken ? "Hide token" : "Show token"}
              onPress={() => setShowToken((current) => !current)}
            />
          </View>
        </View>
        {message != null && (
          <Text
            accessibilityLiveRegion="polite"
            style={[
              typeScale.bodySmall,
              {
                color: message.tone === "error" ? colors.error : colors.onSurfaceVariant,
                marginTop: spacing.m,
                flexShrink: 1,
              },
            ]}
          >
            {message.text}
          </Text>
        )}
      </StepRow>
      </StepList>
  );
}
