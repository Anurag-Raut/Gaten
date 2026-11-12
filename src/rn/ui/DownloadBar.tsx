/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The one download bar, shared by the onboarding reader row and the settings screen.
 *
 * It draws the module's real bytes: a determinate fill from `bytesDone / bytesTotal`, a full bar once
 * the phase turns to "verifying", and a sweeping bar while nothing has been reported yet — Play's
 * pack transfer, or the first moments of any download. An empty grey track there would read as a
 * stalled download, which is exactly the state this bar must never claim.
 */
import React, {useEffect, useState} from "react";
import {View} from "react-native";
import Animated, {
  cancelAnimation,
  Easing,
  interpolate,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from "react-native-reanimated";
import {downloadBarState, type DownloadProgressLike} from "./onboarding/onboarding-steps";
import {useTheme} from "./theme";

const TRACK_HEIGHT = 4;
/** The sweeping segment's share of the track. */
const SWEEP_WIDTH = 0.3;
const SWEEP_MS = 1200;

export function DownloadBar({progress}: {progress: DownloadProgressLike}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const {percent, indeterminate} = downloadBarState(progress);
  const [trackWidth, setTrackWidth] = useState(0);
  const sweep = useSharedValue(0);

  useEffect(() => {
    if (!indeterminate) {
      cancelAnimation(sweep);
      return;
    }
    if (reducedMotion) {
      cancelAnimation(sweep);
      // A still segment, held mid-track, so reduced motion still says "working" without moving.
      sweep.value = 0.5;
      return;
    }
    if (trackWidth <= 0) return;
    sweep.value = 0;
    sweep.value = withRepeat(
      withTiming(1, {duration: SWEEP_MS, easing: Easing.inOut(Easing.quad)}),
      -1,
      false,
    );
    return () => cancelAnimation(sweep);
  }, [indeterminate, reducedMotion, sweep, trackWidth]);

  const sweepStyle = useAnimatedStyle(() => {
    const segment = trackWidth * SWEEP_WIDTH;
    return {
      width: segment,
      transform: [{translateX: interpolate(sweep.value, [0, 1], [-segment, trackWidth])}],
    };
  });

  return (
    <View
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel="Download progress"
      accessibilityState={{busy: indeterminate}}
      accessibilityValue={
        indeterminate ? undefined : {min: 0, max: 100, now: percent, text: `${percent}%`}
      }
      onLayout={(event) => {
        const next = event.nativeEvent.layout.width;
        setTrackWidth((current) => (current === next ? current : next));
      }}
      style={{
        height: TRACK_HEIGHT,
        borderRadius: TRACK_HEIGHT / 2,
        backgroundColor: colors.outlineVariant,
        overflow: "hidden",
      }}
    >
      {indeterminate ? (
        <Animated.View
          style={[
            {
              height: TRACK_HEIGHT,
              borderRadius: TRACK_HEIGHT / 2,
              backgroundColor: colors.accent,
            },
            sweepStyle,
          ]}
        />
      ) : (
        <View style={{height: TRACK_HEIGHT, width: `${percent}%`, backgroundColor: colors.accent}} />
      )}
    </View>
  );
}
