/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/** Shared Ink press feedback: a quiet opacity change, with a visible keyboard focus ring. */
import React, {useState} from "react";
import {
  Pressable,
  type PressableProps,
  type StyleProp,
  type ViewStyle,
} from "react-native";
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  useReducedMotion,
  withTiming,
} from "react-native-reanimated";
import {PRESS_EASING_REANIMATED} from "./tokens";
import {useTheme} from "./theme";

const PRESSED_OPACITY = 0.72;
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
  onFocus,
  onBlur,
  ...rest
}: PressableScaleProps) {
  const {colors} = useTheme();
  const [focused, setFocused] = useState(false);
  const reducedMotion = useReducedMotion();
  const opacity = useSharedValue(1);

  const settle = (target: number) => {
    opacity.value = reducedMotion ? target : withTiming(target, {
      duration: PRESS_MS,
      easing: PRESS_EASING_REANIMATED,
    });
  };
  const pressStyle = useAnimatedStyle(() => ({opacity: opacity.value}));

  return (
    <AnimatedPressable
      {...rest}
      onPressIn={(event) => {
        settle(PRESSED_OPACITY);
        onPressIn?.(event);
      }}
      onPressOut={(event) => {
        settle(1);
        onPressOut?.(event);
      }}
      onFocus={(event) => {
        setFocused(true);
        onFocus?.(event);
      }}
      onBlur={(event) => {
        setFocused(false);
        onBlur?.(event);
      }}
      style={[style, pressStyle, focused && {outlineWidth: 2, outlineColor: colors.accent, outlineOffset: 2}]}
    >
      {children}
    </AnimatedPressable>
  );
}
