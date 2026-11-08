/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The vertical stepper inside an onboarding page: a column of discs joined by a thin rail, one row
 * per thing the reader sets up here — no hairlines, no cards.
 *
 * Each row's own header -- its disc, its title and its one line of detail -- is a single grouped
 * accessibility element ("Step 1 of 2, Turn on lookup mode, current"), so a screen reader reads the
 * step once rather than the disc, the title and the detail three times, and a status change is
 * announced politely. The step's content is not part of that element, so whatever interactive
 * control it carries keeps its own name and target, and it sits in the row's right column so the
 * rail is never drawn over.
 *
 * The disc's state cross-fades between the open mark (a haloed ring and dot for the current step, a
 * pale disc with its number for an openable one, a pale disc with a small dot for a future one) and
 * the done disc (the green check). The rail under a step fills as that step turns done, growing down
 * from the top on transform/scaleY, and it stretches with the row's own content — a how-to, a form,
 * a download's progress — because it is a flex child of the row, never a measured height.
 *
 * A step's content opens and closes on the UI thread: its height and its opacity ride one shared
 * value, from the height the content actually measured, so the row grows in place with no layout
 * jump and nothing shows before its container has finished sizing. Reduced motion opens it at once,
 * at the same end state.
 */
import React, {useEffect, useRef, useState} from "react";
import {
  Pressable,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {Icon} from "../components";
import {PAGE_EASING_REANIMATED, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {stepRowDuration} from "./onboarding-motion";
import {
  connectorStateAfter,
  shouldMeasureStepContent,
  stepContentReducer,
  stepStatusWord,
  stepTarget,
  type StepContentState,
  type StepStatus,
} from "./onboarding-steps";

/** The stepper's geometry: the disc, and the rail that joins the discs. */
export const STEP_DISC_SIZE = 28;
/** The rail's width. */
export const STEP_RAIL_WIDTH = 2;
/** How much of the outline colour the pending rail keeps: enough to read as a line, not a hairline. */
const RAIL_ALPHA = 0.32;

/** [hex] with [alpha] (0..1) appended, for a rail the palette only hands out opaque. */
function withAlpha(hex: string, alpha: number): string {
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255)
    .toString(16)
    .padStart(2, "0");
  return `${hex}${a}`;
}

/** One rail segment: the pale base line and, over it, the filled line that grows down from the top. */
function StepConnector({filled, style}: {filled: boolean; style?: StyleProp<ViewStyle>}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const duration = stepRowDuration(reducedMotion);
  const target = stepTarget(filled, reducedMotion);
  const fill = useSharedValue(target.value);
  useEffect(() => {
    fill.value = target.instant
      ? target.value
      : withTiming(target.value, {duration, easing: PAGE_EASING_REANIMATED});
  }, [target.value, target.instant, duration, fill]);
  const fillStyle = useAnimatedStyle(() => ({
    transformOrigin: "top" as const,
    transform: [{scaleY: fill.value}],
  }));
  return (
    <View
      style={[
        {width: STEP_RAIL_WIDTH, backgroundColor: withAlpha(colors.outline, RAIL_ALPHA)},
        style,
      ]}
    >
      <Animated.View
        style={[StyleSheet.absoluteFill, {backgroundColor: colors.success}, fillStyle]}
      />
    </View>
  );
}

/** The open mark inside a disc: what the step looks like before it is done. */
function OpenMark({index, status}: {index: number; status: StepStatus}) {
  const {colors} = useTheme();
  if (status === "current") {
    return (
      <View style={{width: STEP_DISC_SIZE, height: STEP_DISC_SIZE, alignItems: "center", justifyContent: "center"}}>
        {/* The halo: a soft accent field the ring sits in. */}
        <View
          style={{
            position: "absolute",
            width: STEP_DISC_SIZE,
            height: STEP_DISC_SIZE,
            borderRadius: STEP_DISC_SIZE / 2,
            backgroundColor: colors.accent,
            opacity: 0.14,
          }}
        />
        <View
          style={{
            width: 20,
            height: 20,
            borderRadius: 10,
            borderWidth: 2,
            borderColor: colors.accent,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          <View style={{width: 8, height: 8, borderRadius: 4, backgroundColor: colors.accent}} />
        </View>
      </View>
    );
  }
  const pale: ViewStyle = {
    width: STEP_DISC_SIZE,
    height: STEP_DISC_SIZE,
    borderRadius: STEP_DISC_SIZE / 2,
    backgroundColor: colors.surfaceVariant,
    alignItems: "center",
    justifyContent: "center",
  };
  if (status === "available") {
    return (
      <View style={pale}>
        <Text style={[typeScale.labelSmall, {color: colors.accent, lineHeight: 16}]}>{index}</Text>
      </View>
    );
  }
  return (
    <View style={pale}>
      <View style={{width: 8, height: 8, borderRadius: 4, backgroundColor: colors.onSurfaceVariant}} />
    </View>
  );
}

/** One row's mark: the disc, cross-fading between its open state and the done state. */
function StepMark({index, status}: {index: number; status: StepStatus}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const duration = stepRowDuration(reducedMotion);
  const target = stepTarget(status === "done", reducedMotion);
  const done = useSharedValue(target.value);
  useEffect(() => {
    done.value = target.instant
      ? target.value
      : withTiming(target.value, {duration, easing: PAGE_EASING_REANIMATED});
  }, [target.value, target.instant, duration, done]);
  const doneStyle = useAnimatedStyle(() => ({opacity: done.value}));
  const openStyle = useAnimatedStyle(() => ({opacity: 1 - done.value}));
  return (
    <View style={{width: STEP_DISC_SIZE, height: STEP_DISC_SIZE}}>
      <Animated.View
        style={[
          StyleSheet.absoluteFill,
          {alignItems: "center", justifyContent: "center"},
          openStyle,
        ]}
      >
        <OpenMark index={index} status={status} />
      </Animated.View>
      <Animated.View
        style={[
          StyleSheet.absoluteFill,
          {alignItems: "center", justifyContent: "center"},
          doneStyle,
        ]}
      >
        <View
          style={{
            width: STEP_DISC_SIZE,
            height: STEP_DISC_SIZE,
            borderRadius: STEP_DISC_SIZE / 2,
            backgroundColor: colors.success,
            alignItems: "center",
            justifyContent: "center",
          }}
        >
          {/* The check reads against the disc, not the page, so it takes the page's own background
              tone: dark ink on the light success disc of a dark theme, and the light page tone on the
              green disc of a light one. */}
          <Icon name="check" size={16} color={colors.background} />
        </View>
      </Animated.View>
    </View>
  );
}

/**
 * A step's content, clipped and grown to the height it measured.
 *
 * The content's height is a property of the content, not of the clip in front of it. Two things
 * follow, and both were learned on device:
 *
 * - While a height is being measured the content is laid out in flow with the clip off, so the row
 *   grows to the content's own height and the reader sees it at once. An open step that measured
 *   itself inside a zero-height clip read zero, and stayed shut.
 * - Once a height is known the content is out of the clip's flow, so the clip can never shrink what
 *   the content measures. Left in flow, the shrink of a close fed back into the measurement — every
 *   intermediate frame of the animation overwrote the real height with a sliver — and the step could
 *   never open again after being closed once. That is what [stepContentReducer] now rules out.
 *
 * [shown] drives the open fraction (one clock for the height and the opacity, so the content is never
 * readable before its container has made room) and the height follows the measured value; with motion
 * reduced both are written straight in, never left to a zero-length animation that never lands.
 */
function StepContent({shown, children}: {shown: boolean; children: React.ReactNode}) {
  const reducedMotion = useReducedMotion();
  const duration = stepRowDuration(reducedMotion);
  const target = stepTarget(shown, reducedMotion);
  const [state, setState] = useState<StepContentState>({measured: null});
  const measured = state.measured;
  const height = useSharedValue(0);
  const progress = useSharedValue(target.value);
  const placed = useRef(false);

  // The open fraction, from the step's own open state alone — never from the value it happened to be
  // holding, so an opening that follows a closing always has a real target to travel to.
  useEffect(() => {
    if (shown && measured == null) {
      progress.value = 1;
      return;
    }
    progress.value = target.instant
      ? target.value
      : withTiming(target.value, {duration, easing: PAGE_EASING_REANIMATED});
  }, [shown, measured, target.value, target.instant, duration, progress]);

  // The height the clip opens to: placed at once the first time the content's height is known, and
  // animated from there on a change inside an open row.
  useEffect(() => {
    if (measured == null) return;
    height.value = placed.current
      ? target.instant
        ? measured
        : withTiming(measured, {duration, easing: PAGE_EASING_REANIMATED})
      : measured;
    placed.current = true;
  }, [measured, target.instant, duration, height]);

  const measuring = shouldMeasureStepContent(shown, measured);
  const style = useAnimatedStyle(() => ({
    height: height.value * progress.value,
    opacity: progress.value,
  }));
  return (
    <Animated.View style={[{overflow: "hidden"}, measuring ? {opacity: 1} : style]}>
      <View
        style={
          measuring
            ? // In flow while nothing is measured: the row takes the content's own height and shows
              // it straight away.
              {flexShrink: 0}
            : // Out of the clip's flow once measured: a child in flow inside a shut clip is laid out
              // at the clip's height, and that reading must never become the row's height.
              {position: "absolute", left: 0, right: 0, top: 0}
        }
        onLayout={(event) => {
          // Read before the updater runs: the event is released once this handler returns, and an
          // updater that read it later saw a null nativeEvent and crashed the app on device.
          const height = event.nativeEvent.layout.height;
          setState((current) => stepContentReducer(current, {height, open: shown}));
        }}
      >
        {children}
      </View>
    </Animated.View>
  );
}

/** The list itself: the rows of the stepper, one under the next, joined by their own rails. */
export function StepList({
  style,
  children,
}: {
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}) {
  return <View style={style}>{children}</View>;
}

/** The header row's own copy line, in the tone the step's state gives it. */
function headerLineStyle(color: string): StyleProp<TextStyle> {
  return [typeScale.description, {color, marginTop: spacing.xs}];
}

/**
 * One step. [index] is its 1-based number and [total] the number of steps on the page. [detail] is
 * shown while the step is the reader's current one, [summary] while it has not started; a done row
 * shows its title alone. [children] is the step's own content, shown only while it is current --
 * unless [showContent] overrides that, for the one step whose control must be reachable before the
 * step is current.
 *
 * A row can instead be [onPress]-openable: its whole header becomes a button (role, expanded state,
 * a chevron and a short accent [hint]) whose content opens only while [expanded] is true. That is the
 * WaniKani "Create a token" row, offered to the reader without ever being their current step.
 *
 * [previousStatus] is the row above's, and colours the rail segment that arrives at this disc; the
 * rail below a row is coloured by the row's own [status]. The first row has no rail above it and the
 * last row none below.
 */
export function StepRow({
  index,
  total,
  title,
  status,
  previousStatus,
  detail,
  summary,
  showContent,
  hint,
  onPress,
  expanded,
  accessibilityHint,
  children,
}: {
  index: number;
  total: number;
  title: string;
  status: StepStatus;
  /** The row above's status, for the rail segment that arrives at this disc. */
  previousStatus?: StepStatus;
  detail?: string;
  summary?: string;
  showContent?: boolean;
  /** A short accent line under the title, for a row that is opened rather than being current. */
  hint?: string;
  /** Makes the row's header a button that opens its content, for an openable row. */
  onPress?: () => void;
  /** The open state that button drives; the content shows only while it is true. */
  expanded?: boolean;
  accessibilityHint?: string;
  children?: React.ReactNode;
}) {
  const {colors} = useTheme();
  const dim = status === "todo";
  const contentShown =
    children != null && (expanded !== undefined ? expanded : (showContent ?? status === "current"));
  const headerStyle: StyleProp<ViewStyle> = {
    flexDirection: "row",
    gap: spacing.m,
    paddingTop: spacing.m,
    paddingBottom: spacing.m,
    minHeight: 48,
    opacity: dim ? 0.55 : 1,
  };
  const header = (
    <>
      <View style={{flex: 1, minWidth: 0}}>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
        {!dim && status === "current" && detail != null && (
          <Text style={headerLineStyle(colors.onSurfaceVariant)}>{detail}</Text>
        )}
        {dim && summary != null && (
          <Text style={headerLineStyle(colors.onSurfaceVariant)}>{summary}</Text>
        )}
        {hint != null && status !== "done" && (
          <Text style={headerLineStyle(colors.accent)}>{hint}</Text>
        )}
      </View>
      {onPress != null && (
        <View style={{justifyContent: "center"}}>
          <Icon
            name={expanded === true ? "keyboard_arrow_up" : "keyboard_arrow_down"}
            size={22}
            color={colors.onSurfaceVariant}
          />
        </View>
      )}
    </>
  );
  return (
    <View style={{flexDirection: "row", gap: spacing.m}}>
      {/* The rail: the arriving segment, the disc, and the leaving segment. It stretches to the row's
          own height, so the rail runs the full length of an open row's content too. */}
      <View style={{width: STEP_DISC_SIZE, alignItems: "center"}}>
        {index > 1 && (
          <StepConnector
            filled={connectorStateAfter(previousStatus ?? "todo") === "filled"}
            style={{height: spacing.m}}
          />
        )}
        <StepMark index={index} status={status} />
        {index < total && (
          <StepConnector filled={connectorStateAfter(status) === "filled"} style={{flex: 1}} />
        )}
      </View>
      <View style={{flex: 1, minWidth: 0}}>
        {onPress == null ? (
          <View
            accessible
            accessibilityLabel={`Step ${index} of ${total}, ${title}, ${stepStatusWord(status)}`}
            accessibilityLiveRegion="polite"
            style={headerStyle}
          >
            {header}
          </View>
        ) : (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`Step ${index} of ${total}, ${title}, ${stepStatusWord(status)}`}
            accessibilityHint={accessibilityHint}
            accessibilityState={{expanded: expanded === true}}
            accessibilityLiveRegion="polite"
            onPress={onPress}
            style={headerStyle}
          >
            {header}
          </Pressable>
        )}
        {children != null && (
          <StepContent shown={contentShown}>
            <View style={{paddingBottom: spacing.m}}>{children}</View>
          </StepContent>
        )}
      </View>
    </View>
  );
}
