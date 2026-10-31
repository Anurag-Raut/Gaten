/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Shared Ink press feedback: a quiet opacity change, a tick from the phone, with a visible keyboard
 * focus ring — and, for the few controls the design moves instead of just dimming, a drop.
 */
import React, {useState} from "react";
import {
  Pressable,
  type PressableProps,
  type StyleProp,
  type ViewStyle,
} from "react-native";
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  useReducedMotion,
  withTiming,
} from "react-native-reanimated";
import * as GatenHaptics from "../native/GatenHaptics";
import {PRESS_EASING_REANIMATED} from "./tokens";
import {useTheme} from "./theme";

const PRESSED_OPACITY = 0.72;
const PRESS_MS = 100;
/** How far a `pressDrop` control travels down while it is held. */
const PRESS_DROP_DP = 2;

const AnimatedPressable = Animated.createAnimatedComponent(Pressable);

/** A control that travels: it sinks [distance] dp and eases its fill from [from] to [to] while held. */
export interface PressDrop {
  distance?: number;
  /** The resting fill, so the colour eases back to it on release. */
  from: string;
  /** The fill while held. */
  to: string;
}

/** How much of a fill's own ink a held control lays back over it: Material's pressed state layer. */
const HELD_LAYER = 0.14;

/**
 * The tone a fill takes while it is held: [fill] with [ink] laid over it at the state layer's own
 * weight. The theme hands out opaque `#RRGGBB`, and reanimated eases colours, not `rgba` blends, so
 * the pressed fill is resolved here rather than animated from a translucent overlay.
 */
export function heldTone(fill: string, ink: string): string {
  const from = hexChannels(fill);
  const over = hexChannels(ink);
  if (from === null || over === null) {
    return fill;
  }
  const blend = (a: number, b: number) => Math.round(a + (b - a) * HELD_LAYER);
  return `#${[
    blend(from[0], over[0]),
    blend(from[1], over[1]),
    blend(from[2], over[2]),
  ]
    .map((channel) => channel.toString(16).padStart(2, "0"))
    .join("")}`;
}

/** [color] as its three 8-bit channels, or null when it is not the theme's opaque hex. */
function hexChannels(color: string): [number, number, number] | null {
  const match = /^#([0-9a-f]{6})$/i.exec(color.trim());
  if (match === null) {
    return null;
  }
  const value = parseInt(match[1], 16);
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff];
}

interface PressableScaleProps extends Omit<PressableProps, "style"> {
  style?: StyleProp<ViewStyle>;
  /** Draw the accent focus ring; off for controls whose platform focus highlight is unwanted. */
  showFocusRing?: boolean;
  /** The pressed-state travel, for the controls the design sinks rather than dims. */
  pressDrop?: PressDrop;
}

export function PressableScale({
  style,
  children,
  showFocusRing = true,
  pressDrop,
  disabled,
  onPress,
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
  const drop = useSharedValue(0);

  const settle = (pressed: boolean) => {
    const to = pressed ? PRESSED_OPACITY : 1;
    opacity.value = reducedMotion ? to : withTiming(to, {
      duration: PRESS_MS,
      easing: PRESS_EASING_REANIMATED,
    });
    if (pressDrop) {
      const next = pressed ? 1 : 0;
      drop.value = reducedMotion ? next : withTiming(next, {
        duration: PRESS_MS,
        easing: PRESS_EASING_REANIMATED,
      });
    }
  };
  const pressStyle = useAnimatedStyle(() => ({
    opacity: opacity.value,
    ...(pressDrop == null
      ? null
      : {
          transform: [{translateY: drop.value * (pressDrop.distance ?? PRESS_DROP_DP)}],
          backgroundColor: interpolateColor(
            drop.value,
            [0, 1],
            [pressDrop.from, pressDrop.to],
          ),
        }),
  }));

  return (
    <AnimatedPressable
      {...rest}
      disabled={disabled}
      onPressIn={(event) => {
        settle(true);
        onPressIn?.(event);
      }}
      onPress={(event) => {
        // Tick on the click, not on touch-down: a press that starts on a card and turns into a
        // scroll is never a click, and must not tick. Every enabled control ticks; a disabled one
        // reports no click at all.
        if (!disabled) {
          void GatenHaptics.play("tick").catch(() => {});
        }
        onPress?.(event);
      }}
      onPressOut={(event) => {
        settle(false);
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
      style={[
        style,
        pressStyle,
        showFocusRing && focused && {
          outlineWidth: 2,
          outlineColor: colors.accent,
          outlineOffset: 2,
        },
      ]}
    >
      {children}
    </AnimatedPressable>
  );
}
