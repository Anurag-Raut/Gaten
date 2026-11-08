/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The step list inside an onboarding page: hairline rows, no cards, one row per thing the reader
 * sets up here.
 *
 * Each row's own header -- its mark, its title and its one line of detail -- is a single grouped
 * accessibility element ("Step 1 of 2, Turn on lookup mode, current"), so a screen reader reads the
 * step once rather than the circle, the title and the detail three times, and a status change is
 * announced politely. The step's content is not part of that element, so whatever interactive
 * control it carries keeps its own name and target.
 *
 * A step's content opens and closes on the UI thread: its height and its opacity ride one shared
 * value, from the height the content actually measured, so the row grows in place with no layout
 * jump and nothing shows before its container has finished sizing. Reduced motion opens it at once,
 * at the same end state.
 */
import React, {useEffect, useRef} from "react";
import {StyleSheet, Text, View, type StyleProp, type ViewStyle} from "react-native";
import Animated, {useAnimatedStyle, useReducedMotion, useSharedValue, withTiming} from "react-native-reanimated";
import {Icon} from "../components";
import {PAGE_EASING_REANIMATED, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {stepRowDuration} from "./onboarding-motion";
import {stepStatusWord, type StepStatus} from "./onboarding-steps";

/** One row's mark: a 24 dp disc holding the step's number, or a success check once it is done. */
function StepMark({index, status}: {index: number; status: StepStatus}) {
  const {colors} = useTheme();
  const size = 24;
  if (status === "done") {
    return (
      <View
        style={{
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: colors.success,
          alignItems: "center",
          justifyContent: "center",
        }}
      >
        {/* The check reads against the disc, not the page, so it takes the page's own background
            tone: dark ink on the light success disc of a dark theme, and the light page tone on the
            green disc of a light one. */}
        <Icon name="check" size={15} color={colors.background} />
      </View>
    );
  }
  const active = status === "current";
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: size / 2,
        borderWidth: active ? 2 : 1.5,
        borderColor: active ? colors.accent : colors.outlineVariant,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <Text
        style={[
          typeScale.labelSmall,
          {color: active ? colors.accent : colors.onSurfaceVariant, lineHeight: 16},
        ]}
      >
        {index}
      </Text>
    </View>
  );
}

/**
 * A step's content, clipped and grown to the height it measured. [shown] drives both the height and
 * the opacity over one clock, so the content is never readable before its container has made room;
 * a change inside an open row animates its own growth the same way, and the row's very first
 * measurement — a row that is already open on arrival — is applied at once, with no entrance.
 */
function StepContent({shown, children}: {shown: boolean; children: React.ReactNode}) {
  const reducedMotion = useReducedMotion();
  const duration = stepRowDuration(reducedMotion);
  const measured = useSharedValue(0);
  const height = useSharedValue(0);
  const progress = useSharedValue(shown ? 1 : 0);
  const initialised = useRef(false);
  useEffect(() => {
    progress.value = withTiming(shown ? 1 : 0, {
      duration: stepRowDuration(reducedMotion),
      easing: PAGE_EASING_REANIMATED,
    });
  }, [shown, reducedMotion, progress]);
  const style = useAnimatedStyle(() => ({
    height: height.value * progress.value,
    opacity: progress.value,
  }));
  return (
    <Animated.View style={[{overflow: "hidden"}, style]}>
      <View
        onLayout={(event) => {
          const next = event.nativeEvent.layout.height;
          if (next === measured.value) return;
          measured.value = next;
          if (!initialised.current) {
            initialised.current = true;
            height.value = next;
            return;
          }
          height.value =
            duration === 0
              ? next
              : withTiming(next, {duration, easing: PAGE_EASING_REANIMATED});
        }}
      >
        {children}
      </View>
    </Animated.View>
  );
}

/** The list itself: the top and bottom hairlines that bound the group, and the rows between them. */
export function StepList({
  style,
  children,
}: {
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={[
        {
          borderTopWidth: StyleSheet.hairlineWidth,
          borderBottomWidth: StyleSheet.hairlineWidth,
          borderColor: colors.outlineVariant,
        },
        style,
      ]}
    >
      {children}
    </View>
  );
}

/**
 * One step. [index] is its 1-based number and [total] the number of steps on the page. [detail] is
 * shown while the step is the reader's current one, [summary] while it has not started; a done row
 * shows its title alone. [children] is the step's own content, shown only while it is current --
 * unless [showContent] overrides that, for the one step whose control must be reachable before the
 * step is current.
 */
export function StepRow({
  index,
  total,
  title,
  status,
  detail,
  summary,
  showContent,
  children,
}: {
  index: number;
  total: number;
  title: string;
  status: StepStatus;
  detail?: string;
  summary?: string;
  showContent?: boolean;
  children?: React.ReactNode;
}) {
  const {colors} = useTheme();
  const dim = status === "todo";
  const contentShown = children != null && (showContent ?? status === "current");
  return (
    <View
      style={{
        borderTopWidth: index > 1 ? StyleSheet.hairlineWidth : 0,
        borderColor: colors.outlineVariant,
      }}
    >
      <View
        accessible
        accessibilityLabel={`Step ${index} of ${total}, ${title}, ${stepStatusWord(status)}`}
        accessibilityLiveRegion="polite"
        style={{
          flexDirection: "row",
          gap: spacing.m,
          paddingTop: spacing.m,
          paddingBottom: spacing.m,
          minHeight: 48,
          opacity: dim ? 0.55 : 1,
        }}
      >
        <StepMark index={index} status={status} />
        <View style={{flex: 1, minWidth: 0}}>
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
          {!dim && status === "current" && detail != null && (
            <Text
              style={[typeScale.description, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}
            >
              {detail}
            </Text>
          )}
          {dim && summary != null && (
            <Text
              style={[typeScale.description, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}
            >
              {summary}
            </Text>
          )}
        </View>
      </View>
      {children != null && (
        <StepContent shown={contentShown}>
          <View style={{paddingBottom: spacing.m}}>{children}</View>
        </StepContent>
      )}
    </View>
  );
}
