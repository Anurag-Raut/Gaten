/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Welcome screen: ten seconds that say what Gaten does — see a word, open Gaten, circle it,
 * read it, add it to Anki — before the reader meets onboarding page 1.
 *
 * It is the state *before* page 1, never a step of its own: shown while the stored step is still 0
 * (see `shouldShowWelcome`), and gone once the flow has advanced. There is no Skip and no persisted
 * flag.
 *
 * The animation is one Reanimated shared value holding 0..10 and nothing else: every pose is
 * `welcomeFrame` of that value (`welcome-timeline.ts`, the handoff prototype's numbers), so a frame
 * is reproducible without a device, and the clock is stopped outright when the app is backgrounded.
 * Only transform and opacity are animated.
 *
 * The stage is decorative — hidden from screen readers, never a touch target — and the screen
 * exposes exactly one label for the whole illustration; the captions are not announced. The
 * lettering is the bundled IBM Plex Sans JP at fixed dp inside the stage, so the artwork scales as
 * one picture instead of reflowing with the system font size, and the whole stage is scaled down to
 * whatever box it is given rather than ever clipping.
 */
import React, {useCallback, useEffect, useState} from "react";
import {
  AppState,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type LayoutChangeEvent,
} from "react-native";
import Animated, {
  Easing,
  cancelAnimation,
  useAnimatedProps,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
  type SharedValue,
} from "react-native-reanimated";
import {SafeAreaView, useSafeAreaInsets} from "react-native-safe-area-context";
import Svg, {Ellipse, Path} from "react-native-svg";
import {useTheme} from "../theme";
import {GatenWordmark} from "../home/GatenWordmark";
import {Button} from "../components";
import {APP_FONT, CONTENT_COLUMN, contentInsetFor, spacing} from "../tokens";
import {G_MARK_PATH, G_MARK_VIEWBOX} from "./welcome-g-mark";
import {
  ADDED_LABEL,
  ADD_LABEL,
  FALLBACK_WORD,
  LOOP_MS,
  MEANING_LINE,
  PLUS_LABEL,
  READING_LINE,
  SENTENCE_AFTER,
  SENTENCE_BEFORE,
  SENTENCE_WORD,
  STAGE_HEIGHT,
  STAGE_WIDTH,
  WELCOME_CAPTIONS,
  WELCOME_LABEL,
  welcomeFrameFor,
  wordGeometry,
  type WordGeometry,
} from "./welcome-timeline";

/** The stage's own layout, in its 316 x 372 dp box. */
const SHEET_TOP = 6;
const SHEET_HEIGHT = 276;
const SHEET_RADIUS = 18;
const SENTENCE_LEFT = 20;
const SENTENCE_TOP = 66;
const SENTENCE_SIZE = 24;
const BUBBLE = {right: 6, top: 104, size: 50};
const CURSOR_SIZE = 26;
const ANKI = {right: 2, top: -6, size: 44};
const FLY = {width: 70, height: 46};

/** The smallest the stage is drawn at: below it the picture is illegible and is dropped instead. */
const MIN_STAGE_SCALE = 0.55;

/**
 * The mark's own top rhythm, the one the Home header gives it: Home's header column pads by
 * [spacing.m] and its brand row adds an 11 dp margin above the mark's line box, so the mark's ink
 * sits the same distance below the status bar on both screens. The wordmark's own box carries
 * whitespace above the G — its viewBox's 46 units plus the letterboxing from holding the artwork's
 * aspect ratio in a 44 dp line box, ~6.3 dp at font scale 1 — and scales it with the mark, so this
 * offset stays in dp, the same choice Home's fixed margins make, and the two ink tops agree at
 * every font scale.
 */
const WORDMARK_TOP_OFFSET = spacing.m + 11;

/** A full-bleed absolute box, for a layer that carries other properties too. */
const FILL = {position: "absolute" as const, left: 0, right: 0, top: 0, bottom: 0};

/** The Anki mark's own tile and inks: the app's blue stacked cards, on their light ground. */
const ANKI_TILE = "#EAF4FF";
const ANKI_BLUE = "#2E7CF6";
const ANKI_SPARKLE = "#7FB4F7";

/** The mark the animation draws: two stacked cards with a white gap, and a four-point sparkle. */
function AnkiMark({size}: {size: number}) {
  return (
    <Svg width={size} height={size} viewBox="0 0 30 30">
      <Path d="M15 3.6 L26.6 10.6 L15 17.6 L3.4 10.6 Z" fill={ANKI_BLUE} />
      <Path d="M3.4 14.2 L15 21.2 L26.6 14.2 L26.6 18.4 L15 25.4 L3.4 18.4 Z" fill={ANKI_BLUE} />
      <Path
        d="M23.6 1.4 L24.8 3.6 L27 4.8 L24.8 6 L23.6 8.2 L22.4 6 L20.2 4.8 L22.4 3.6 Z"
        fill={ANKI_SPARKLE}
      />
    </Svg>
  );
}

/** The floating button's mark, at its ink box, untinted: the app's own Light mark. */
function GMark({width, height}: {width: number; height: number}) {
  return (
    <Svg width={width} height={height} viewBox={G_MARK_VIEWBOX}>
      <Path d={G_MARK_PATH} fill="#000000" fillRule="evenodd" />
    </Svg>
  );
}

/** A hex ink at [alpha], so a hairline or a 14% fill needs no second token. */
function withAlpha(hex: string, alpha: number): string {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

/**
 * The artwork follows light/dark but not the reader's accent: onboarding is shown with the app's
 * default Indigo, so the stage's tones are fixed. Same values as `OnboardingArtwork`, from
 * `app/src/main/java/com/gaten/app/ui/theme/Color.kt` (IndigoLight / IndigoDark).
 */
const INDIGO_LIGHT = {accent: "#4338CA", onAccent: "#FFFFFF"};
const INDIGO_DARK = {accent: "#A5B4FC", onAccent: "#111111"};

/** One caption layer: the active scene's text, cross-fading on its own two-second window. */
function Caption({
  index,
  clock,
  reduced,
  word,
  color,
  fontSize,
  lineHeight,
}: {
  index: number;
  clock: SharedValue<number>;
  reduced: boolean;
  word: WordGeometry;
  color: string;
  fontSize: number;
  lineHeight: number;
}) {
  const style = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    const active = f.captionIndex === index;
    return {
      opacity: active ? f.captionOpacity : 0,
      transform: [{translateY: active ? f.captionY : 6}],
    };
  });
  return (
    <Animated.Text
      allowFontScaling={false}
      style={[
        {
          position: "absolute",
          left: 0,
          right: 0,
          top: 0,
          fontFamily: APP_FONT,
          fontWeight: "700",
          fontSize,
          lineHeight,
          color,
        },
        style,
      ]}
    >
      {WELCOME_CAPTIONS[index]}
    </Animated.Text>
  );
}

/** One hairline progress segment, filled left to right by a scaled accent bar. */
function Segment({
  index,
  clock,
  reduced,
  word,
  track,
  fill,
}: {
  index: number;
  clock: SharedValue<number>;
  reduced: boolean;
  word: WordGeometry;
  track: string;
  fill: string;
}) {
  const style = useAnimatedStyle(() => ({
    transform: [{scaleX: welcomeFrameFor(clock.value, reduced, word).segments[index]}],
  }));
  return (
    <View style={{flex: 1, height: 3, borderRadius: 2, backgroundColor: track, overflow: "hidden"}}>
      <Animated.View
        style={[
          {...FILL, backgroundColor: fill, transformOrigin: "left"},
          style,
        ]}
      />
    </View>
  );
}

/**
 * The Welcome screen. [out] is the flow's handoff clock: 0 while the screen owns the frame, 1 once
 * "Set up Gaten" has handed over to onboarding page 1 — the screen drifts out on it while the page
 * beneath drifts in, on the flow's own page-transition curve. [onStart] is the press.
 */
export function WelcomeScreen({out, onStart}: {out: SharedValue<number>; onStart: () => void}) {
  const {colors, dark} = useTheme();
  const insets = useSafeAreaInsets();
  const {width, fontScale} = useWindowDimensions();
  const reduced = useReducedMotion();
  const indigo = dark ? INDIGO_DARK : INDIGO_LIGHT;

  const clock = useSharedValue(0);
  const [running, setRunning] = useState(true);
  const [region, setRegion] = useState({width: 0, height: 0});
  const [word, setWord] = useState<WordGeometry>(FALLBACK_WORD);

  // The loop: one shared value 0..10, linear, forever — stopped outright whenever the app is not in
  // the foreground, so nothing animates off-screen.
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (state) => setRunning(state === "active"));
    return () => subscription.remove();
  }, []);
  useEffect(() => {
    if (reduced || !running) {
      cancelAnimation(clock);
      return;
    }
    clock.value = withRepeat(withTiming(10, {duration: LOOP_MS, easing: Easing.linear}), -1, false);
    return () => cancelAnimation(clock);
  }, [reduced, running, clock]);

  const measureStage = useCallback((event: LayoutChangeEvent) => {
    const {width: w, height: h} = event.nativeEvent.layout;
    setRegion((current) => (current.width === w && current.height === h ? current : {width: w, height: h}));
  }, []);

  const measureWord = useCallback((event: LayoutChangeEvent) => {
    const {x, y, width: w, height: h} = event.nativeEvent.layout;
    setWord((current) => {
      const next = wordGeometry(SENTENCE_LEFT + x, SHEET_TOP + SENTENCE_TOP + y, w, h);
      return current.rx === next.rx && current.ry === next.ry && current.cx === next.cx
        ? current
        : next;
    });
  }, []);

  // The stage is drawn at its own size and scaled down to the box it is actually given, so it never
  // clips. The box is the region between the copy above and the caption below, measured on layout;
  // until that first measurement there is no box to scale to, so nothing is drawn (the stage is
  // decorative, and one frame without it is invisible). Below [MIN_STAGE_SCALE] the picture is
  // illegible and is dropped rather than drawn as a smudge, exactly as the flow drops its own
  // artwork; the caption, the progress and the button stand either way.
  const scale =
    region.width > 0 && region.height > 0
      ? Math.min(1, region.width / STAGE_WIDTH, region.height / STAGE_HEIGHT)
      : 0;
  const showStage = scale >= MIN_STAGE_SCALE;

  const rootStyle = useAnimatedStyle(() => ({
    opacity: 1 - out.value,
    transform: [{translateX: -out.value * width * 0.25}],
  }));
  const sheetStyle = useAnimatedStyle(() => ({opacity: welcomeFrameFor(clock.value, reduced, word).sheetOpacity}));
  const dimStyle = useAnimatedStyle(() => ({opacity: welcomeFrameFor(clock.value, reduced, word).dim}));
  const highlightStyle = useAnimatedStyle(() => ({
    opacity: welcomeFrameFor(clock.value, reduced, word).highlightOpacity,
  }));
  const bubbleStyle = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {opacity: f.bubbleOpacity, transform: [{translateX: f.bubbleX}, {scale: f.bubbleScale}]};
  });
  const rippleStyle = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {opacity: f.rippleOpacity, transform: [{scale: f.rippleScale}]};
  });
  const cursorStyle = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {
      opacity: f.cursorOpacity,
      transform: [
        {translateX: f.cursorX - CURSOR_SIZE / 2},
        {translateY: f.cursorY - CURSOR_SIZE / 2 - 2},
      ],
    };
  });
  const ellipseProps = useAnimatedProps(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {
      strokeDashoffset: word.circumference * (1 - f.ellipseDraw),
      opacity: f.ellipseOpacity,
    };
  });
  const resultStyle = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {opacity: f.resultOpacity, transform: [{translateY: f.resultY}]};
  });
  const pillStyle = useAnimatedStyle(() => ({
    transform: [{scale: welcomeFrameFor(clock.value, reduced, word).addScale}],
  }));
  // The two pill states are exclusive by opacity, never superimposed: the green fill, the resting
  // label and the ✓ Added label cross over at the same moment, so exactly one is ever painted.
  const addedStyle = useAnimatedStyle(() => ({
    opacity: welcomeFrameFor(clock.value, reduced, word).added ? 1 : 0,
  }));
  const restingStyle = useAnimatedStyle(() => ({
    opacity: welcomeFrameFor(clock.value, reduced, word).added ? 0 : 1,
  }));
  const ankiStyle = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {opacity: f.ankiOpacity, transform: [{scale: f.ankiScale}]};
  });
  const plusStyle = useAnimatedStyle(() => ({
    opacity: welcomeFrameFor(clock.value, reduced, word).plusOpacity,
  }));
  const flyStyle = useAnimatedStyle(() => {
    const f = welcomeFrameFor(clock.value, reduced, word);
    return {
      opacity: f.flyOpacity,
      transform: [{translateX: f.flyX}, {translateY: f.flyY}, {scale: f.flyScale}],
    };
  });

  const inset = contentInsetFor(width);
  const captionFontSize = 22 * fontScale;
  const captionLineHeight = 28 * fontScale;
  const sentenceText = {
    fontFamily: APP_FONT,
    fontSize: SENTENCE_SIZE,
    lineHeight: 34,
    color: colors.onSurface,
  };

  return (
    <Animated.View style={[StyleSheet.absoluteFill, {backgroundColor: colors.background}, rootStyle]}>
      <SafeAreaView style={{flex: 1}} edges={["top", "left", "right"]}>
        {/* The copy and the stage scroll when they cannot fit — a large text size on a short screen
            squeezes the stage out first, and whatever is left scrolls — while the footer stays
            pinned below the scroller, so "Set up Gaten" is never pushed off the screen. */}
        <ScrollView
          style={{flex: 1}}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={[CONTENT_COLUMN, {flexGrow: 1, paddingHorizontal: inset}]}
        >
          <View accessible accessibilityRole="text" accessibilityLabel={WELCOME_LABEL} style={{flexGrow: 1}}>
            {/* The mark carries Home's own top rhythm ([WORDMARK_TOP_OFFSET]) so its ink clears the
                status bar by the same distance there and here. */}
            <View
              style={{
                height: 44 * fontScale,
                justifyContent: "center",
                marginTop: WORDMARK_TOP_OFFSET,
              }}
            >
              <GatenWordmark />
            </View>
            <Text
              allowFontScaling={false}
              style={{
                marginTop: spacing.s,
                fontSize: 14 * fontScale,
                lineHeight: 20 * fontScale,
                fontFamily: APP_FONT,
                color: colors.onSurfaceVariant,
              }}
            >
              Look up Japanese in any app, then study it.
            </Text>

            <View
              style={{flex: 1, alignItems: "center", justifyContent: "center", marginTop: spacing.l}}
              onLayout={measureStage}
            >
              {showStage && (
                <View
                  accessible={false}
                  accessibilityElementsHidden
                  importantForAccessibility="no"
                  pointerEvents="none"
                  style={{width: STAGE_WIDTH * scale, height: STAGE_HEIGHT * scale}}
                >
                  <View style={{width: STAGE_WIDTH, height: STAGE_HEIGHT, transform: [{scale}]}}>
                    {/* The reading sheet. */}
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          left: 0,
                          top: SHEET_TOP,
                          width: STAGE_WIDTH,
                          height: SHEET_HEIGHT,
                          borderWidth: 1.5,
                          borderColor: colors.outline,
                          borderRadius: SHEET_RADIUS,
                          backgroundColor: colors.surface,
                          overflow: "hidden",
                        },
                        sheetStyle,
                      ]}
                    >
                      <View
                        style={{
                          position: "absolute",
                          left: 20,
                          top: 20,
                          width: 9,
                          height: 9,
                          borderRadius: 4.5,
                          backgroundColor: colors.outline,
                        }}
                      />
                      <View
                        style={{
                          position: "absolute",
                          left: 38,
                          top: 22,
                          width: 70,
                          height: 5,
                          borderRadius: 3,
                          backgroundColor: colors.outline,
                        }}
                      />
                      {/* The sentence, as three texts in a row rather than one nested text: the
                          word's own box is then a View's layout, reported relative to this row, so
                          the ellipse and the highlight are placed from a measurement whose frame of
                          reference is unambiguous. All three carry the same 24 dp line, so the
                          baseline is the same as one run of text. */}
                      <View
                        style={{
                          position: "absolute",
                          left: SENTENCE_LEFT,
                          top: SENTENCE_TOP,
                          flexDirection: "row",
                          alignItems: "flex-end",
                        }}
                      >
                        <Text allowFontScaling={false} style={sentenceText}>
                          {SENTENCE_BEFORE}
                        </Text>
                        <View onLayout={measureWord}>
                          <Text allowFontScaling={false} style={sentenceText}>
                            {SENTENCE_WORD}
                          </Text>
                        </View>
                        <Text allowFontScaling={false} style={sentenceText}>
                          {SENTENCE_AFTER}
                        </Text>
                      </View>
                      {[134, 154, 174, 194].map((top, i) => (
                        <View
                          key={top}
                          style={{
                            position: "absolute",
                            left: 20,
                            top,
                            width: [250, 210, 236, 120][i],
                            height: 5,
                            borderRadius: 3,
                            backgroundColor: colors.outline,
                          }}
                        />
                      ))}
                      <Animated.View
                        style={[
                          {
                            ...FILL,
                            borderRadius: SHEET_RADIUS,
                            backgroundColor: colors.onSurface,
                          },
                          dimStyle,
                        ]}
                      />
                      <Animated.View
                        style={[
                          {
                            position: "absolute",
                            left: word.cx - word.rx + 6,
                            top: word.cy - word.ry + 3,
                            width: 2 * word.rx - 12,
                            height: 2 * word.ry - 6,
                            borderRadius: 14,
                            backgroundColor: indigo.accent,
                            transform: [{rotate: "-4deg"}],
                          },
                          highlightStyle,
                        ]}
                      />
                    </Animated.View>

                    {/* The accent ellipse drawing around 合点, and the ring following its tip. */}
                    <Svg
                      width={STAGE_WIDTH}
                      height={STAGE_HEIGHT}
                      style={StyleSheet.absoluteFill}
                      pointerEvents="none"
                    >
                      <AnimatedEllipse
                        cx={word.cx}
                        cy={word.cy}
                        rx={word.rx}
                        ry={word.ry}
                        fill="none"
                        stroke={indigo.accent}
                        strokeWidth={3.5}
                        strokeLinecap="round"
                        strokeDasharray={word.circumference}
                        transform={`rotate(-4 ${word.cx} ${word.cy})`}
                        animatedProps={ellipseProps}
                      />
                    </Svg>
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          left: 0,
                          top: 0,
                          width: CURSOR_SIZE,
                          height: CURSOR_SIZE,
                          borderRadius: CURSOR_SIZE / 2,
                          borderWidth: 2.5,
                          borderColor: indigo.accent,
                          backgroundColor: withAlpha(indigo.accent, 0.25),
                        },
                        cursorStyle,
                      ]}
                    />

                    {/* The floating button and its tap ripple. */}
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          right: BUBBLE.right,
                          top: BUBBLE.top,
                          width: BUBBLE.size,
                          height: BUBBLE.size,
                          borderRadius: BUBBLE.size / 2,
                          backgroundColor: "#FFFFFF",
                          borderWidth: 1,
                          borderColor: "rgba(0, 0, 0, 0.14)",
                          alignItems: "center",
                          justifyContent: "center",
                          elevation: 6,
                          shadowColor: "#1E1E3C",
                          shadowOpacity: 0.22,
                          shadowRadius: 9,
                          shadowOffset: {width: 0, height: 6},
                        },
                        bubbleStyle,
                      ]}
                    >
                      <GMark width={27} height={24} />
                    </Animated.View>
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          right: BUBBLE.right,
                          top: BUBBLE.top,
                          width: BUBBLE.size,
                          height: BUBBLE.size,
                          borderRadius: BUBBLE.size / 2,
                          borderWidth: 2.5,
                          borderColor: indigo.accent,
                        },
                        rippleStyle,
                      ]}
                    />

                    {/* The Anki mark and its +1 badge. */}
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          right: ANKI.right,
                          top: ANKI.top,
                          width: ANKI.size,
                          height: ANKI.size,
                          borderRadius: 12,
                          backgroundColor: ANKI_TILE,
                          alignItems: "center",
                          justifyContent: "center",
                        },
                        ankiStyle,
                      ]}
                    >
                      <AnkiMark size={30} />
                      <Animated.View
                        style={[
                          {
                            position: "absolute",
                            right: -6,
                            top: -6,
                            minWidth: 20,
                            height: 20,
                            borderRadius: 10,
                            paddingHorizontal: 4,
                            backgroundColor: indigo.accent,
                            alignItems: "center",
                            justifyContent: "center",
                          },
                          plusStyle,
                        ]}
                      >
                        <Text
                          allowFontScaling={false}
                          style={{
                            fontFamily: APP_FONT,
                            fontSize: 11,
                            fontWeight: "700",
                            color: indigo.onAccent,
                          }}
                        >
                          {PLUS_LABEL}
                        </Text>
                      </Animated.View>
                    </Animated.View>

                    {/* The card arcing into the Anki mark. */}
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          left: 0,
                          top: 0,
                          width: FLY.width,
                          height: FLY.height,
                          borderRadius: 10,
                          borderWidth: 1.5,
                          borderColor: colors.outline,
                          backgroundColor: colors.surface,
                          alignItems: "center",
                          justifyContent: "center",
                        },
                        flyStyle,
                      ]}
                    >
                      <Text
                        allowFontScaling={false}
                        style={{
                          fontFamily: APP_FONT,
                          fontSize: 20,
                          fontWeight: "700",
                          color: colors.onSurface,
                        }}
                      >
                        {SENTENCE_WORD}
                      </Text>
                    </Animated.View>

                    {/* The result card. */}
                    <Animated.View
                      style={[
                        {
                          position: "absolute",
                          left: 4,
                          right: 4,
                          bottom: 0,
                          height: 124,
                          borderWidth: 1.5,
                          borderColor: withAlpha(indigo.accent, 0.75),
                          borderRadius: 20,
                          backgroundColor: colors.surface,
                          paddingVertical: 16,
                          paddingHorizontal: 18,
                          elevation: 6,
                          shadowColor: "#1E1E3C",
                          shadowOpacity: 0.14,
                          shadowRadius: 14,
                          shadowOffset: {width: 0, height: 10},
                        },
                        resultStyle,
                      ]}
                    >
                      <View style={{flexDirection: "row", alignItems: "baseline", gap: 10}}>
                        <Text
                          allowFontScaling={false}
                          style={{
                            fontFamily: APP_FONT,
                            fontSize: 28,
                            fontWeight: "700",
                            color: colors.onSurface,
                          }}
                        >
                          {SENTENCE_WORD}
                        </Text>
                        <Text
                          allowFontScaling={false}
                          style={{
                            fontFamily: APP_FONT,
                            fontSize: 15,
                            fontWeight: "600",
                            color: indigo.accent,
                          }}
                        >
                          {READING_LINE}
                        </Text>
                      </View>
                      <Text
                        allowFontScaling={false}
                        style={{
                          marginTop: 2,
                          fontFamily: APP_FONT,
                          fontSize: 13,
                          lineHeight: 19,
                          color: colors.onSurfaceVariant,
                        }}
                      >
                        {MEANING_LINE}
                      </Text>
                      <Animated.View
                        style={[
                          {
                            position: "absolute",
                            right: 14,
                            bottom: 14,
                            height: 38,
                            paddingHorizontal: 16,
                            borderRadius: 12,
                            backgroundColor: indigo.accent,
                            alignItems: "center",
                            justifyContent: "center",
                          },
                          pillStyle,
                        ]}
                      >
                        {/* The ✓ Added state: one opaque fill, and the two labels cross over by
                            opacity, so exactly one of them is ever painted (never both at once, as
                            reduced motion showed when they were stacked). The resting label is what
                            sizes the pill; ✓ Added is narrower and centred on it. */}
                        <Animated.View
                          style={[
                            {...FILL, backgroundColor: colors.success, borderRadius: 12},
                            addedStyle,
                          ]}
                        />
                        <Animated.Text
                          allowFontScaling={false}
                          style={[
                            {
                              fontFamily: APP_FONT,
                              fontSize: 15,
                              fontWeight: "600",
                              color: indigo.onAccent,
                            },
                            restingStyle,
                          ]}
                        >
                          {ADD_LABEL}
                        </Animated.Text>
                        <Animated.View
                          style={[FILL, {alignItems: "center", justifyContent: "center"}, addedStyle]}
                        >
                          <Text
                            allowFontScaling={false}
                            style={{
                              fontFamily: APP_FONT,
                              fontSize: 15,
                              fontWeight: "600",
                              color: indigo.onAccent,
                            }}
                          >
                            {ADDED_LABEL}
                          </Text>
                        </Animated.View>
                      </Animated.View>
                    </Animated.View>
                  </View>
                </View>
              )}
            </View>

            <View style={{paddingBottom: 10}}>
              <View>
                {/* The longest caption, in flow and invisible, sizes the slot at this width and font
                    scale — so the five animated copies can be laid over it without the slot ever
                    reflowing as the loop changes which one is showing, and a caption that wraps at a
                    large text size has the room it needs instead of running into the progress. */}
                <Text
                  allowFontScaling={false}
                  accessibilityElementsHidden
                  importantForAccessibility="no"
                  style={{
                    fontFamily: APP_FONT,
                    fontWeight: "700",
                    fontSize: captionFontSize,
                    lineHeight: captionLineHeight,
                    color: colors.onSurface,
                    opacity: 0,
                  }}
                >
                  {WELCOME_CAPTIONS[0]}
                </Text>
                {WELCOME_CAPTIONS.map((_, index) => (
                  <Caption
                    key={index}
                    index={index}
                    clock={clock}
                    reduced={reduced}
                    word={word}
                    color={colors.onSurface}
                    fontSize={captionFontSize}
                    lineHeight={captionLineHeight}
                  />
                ))}
              </View>
              <View style={{flexDirection: "row", gap: 6, marginTop: 14}}>
                {WELCOME_CAPTIONS.map((_, index) => (
                  <Segment
                    key={index}
                    index={index}
                    clock={clock}
                    reduced={reduced}
                    word={word}
                    track={colors.outline}
                    fill={colors.accent}
                  />
                ))}
              </View>
            </View>
          </View>
        </ScrollView>

        <View
          style={[
            CONTENT_COLUMN,
            {paddingHorizontal: inset, paddingBottom: insets.bottom + spacing.xl, paddingTop: spacing.l},
          ]}
        >
          <Button label="Set up Gaten" fullWidth onPress={onStart} />
        </View>
      </SafeAreaView>
    </Animated.View>
  );
}

const AnimatedEllipse = Animated.createAnimatedComponent(Ellipse);
