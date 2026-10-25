/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import React, {useEffect, useState} from "react";
import {View} from "react-native";
import Animated, {useAnimatedStyle, useReducedMotion, useSharedValue, withTiming} from "react-native-reanimated";
import {InkIcon} from "./InkIcon";
import {PAGE_EASING_REANIMATED} from "./tokens";

const TOGGLE_MS = 220;

/** Measures the full body while clipped, animates height changes, and hides closed controls from accessibility. */
export function Collapsible({expanded, children}: {expanded: boolean; children: React.ReactNode}) {
  const reducedMotion = useReducedMotion();
  const [measuredHeight, setMeasuredHeight] = useState(0);
  const height = useSharedValue(0);
  const opacity = useSharedValue(expanded ? 1 : 0);

  useEffect(() => {
    const config = {duration: reducedMotion ? 0 : TOGGLE_MS, easing: PAGE_EASING_REANIMATED};
    height.value = withTiming(expanded ? measuredHeight : 0, config);
    opacity.value = withTiming(expanded ? 1 : 0, config);
  }, [expanded, measuredHeight, reducedMotion, height, opacity]);

  const style = useAnimatedStyle(() => ({height: height.value, opacity: opacity.value}));
  return (
    <Animated.View
      style={[{overflow: "hidden"}, style]}
      pointerEvents={expanded ? "auto" : "none"}
      accessibilityElementsHidden={!expanded}
      importantForAccessibility={expanded ? "auto" : "no-hide-descendants"}
    >
      <View
        style={{position: "absolute", top: 0, left: 0, right: 0}}
        onLayout={(event) => setMeasuredHeight(event.nativeEvent.layout.height)}
      >
        {children}
      </View>
    </Animated.View>
  );
}

/** Rotates the disclosure arrow from right (closed) to down (open), following the body's timing. */
export function DisclosureChevron({expanded, color, size = 16}: {expanded: boolean; color: string; size?: number}) {
  const reducedMotion = useReducedMotion();
  const progress = useSharedValue(expanded ? 1 : 0);
  useEffect(() => {
    progress.value = withTiming(expanded ? 1 : 0, {
      duration: reducedMotion ? 0 : TOGGLE_MS,
      easing: PAGE_EASING_REANIMATED,
    });
  }, [expanded, reducedMotion, progress]);
  const style = useAnimatedStyle(() => ({transform: [{rotate: `${progress.value * 90}deg`}]}));
  return (
    <Animated.View style={style} accessible={false} importantForAccessibility="no-hide-descendants">
      <InkIcon name="chevron" size={size} color={color} />
    </Animated.View>
  );
}
