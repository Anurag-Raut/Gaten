/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The press feedback the Compose cards and buttons had: a scale-down tied to the press's own
 * interaction, on the `pressEasing` curve, instead of a ripple. Everything pressable that is not a
 * bar item uses this.
 *
 * The scale runs on Reanimated's UI thread, so a press during a screen transition or a list scroll
 * still settles smoothly, and a release that interrupts a press simply retargets the same value —
 * no animation handle to stop by hand.
 */
import React from "react";
import {
  Pressable,
  type PressableProps,
  type StyleProp,
  type ViewStyle,
} from "react-native";
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {PRESS_EASING_REANIMATED} from "./tokens";

/** Compose's `PressedScale`: how far a pressed surface shrinks, the same 0.98 the Kotlin app used. */
const SCALE_PRESSED = 0.98;

/**
 * How long the press answers for: 100 ms. The port started at Material's `Motion.SHORT` (150 ms);
 * press feedback is the most frequent motion in the app, so it belongs at the fast end of the
 * 100–150 ms band — the scale still reads as physical, and the release is as short as the press.
 */
const PRESS_MS = 100;

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

interface PressableScaleProps extends Omit<PressableProps, "style"> {
  style?: StyleProp<ViewStyle>;
}

export function PressableScale({
  style,
  children,
  onPressIn,
  onPressOut,
  ...rest
}: PressableScaleProps) {
  const scale = useSharedValue(1);

  const settle = (target: number) => {
    scale.value = withTiming(target, {
      duration: PRESS_MS,
      easing: PRESS_EASING_REANIMATED,
    });
  };

  const scaleStyle = useAnimatedStyle(() => ({
    transform: [{scale: scale.value}],
  }));

  return (
    <AnimatedPressable
      {...rest}
      onPressIn={(event) => {
        settle(SCALE_PRESSED);
        onPressIn?.(event);
      }}
      onPressOut={(event) => {
        settle(1);
        onPressOut?.(event);
      }}
      style={[style, scaleStyle]}
    >
      {children}
    </AnimatedPressable>
  );
}
