/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * One page slide between the review screen's steps — the RN port of Compose's AnimatedContent
 * with `pageSlide` (Motion.kt): horizontal slide, 380 ms, emphasized easing, no fade. Forward,
 * the incoming page enters over its whole width while the outgoing one stops a quarter of the
 * way out; back, the pair is mirrored and the incoming page draws underneath. RTL mirrors.
 *
 * There is no size animation, exactly like the Compose screen's `using null`: the step area has
 * a fixed frame (the screen's own body), so the pinned actions below it never move.
 *
 * The slide is one Reanimated shared value driving both pages' styles on the UI thread. Entry and
 * exit animations (`entering`/`exiting`) are deliberately not used here: the design needs the
 * incoming page drawn *over* the outgoing one going forward and *under* it coming back, which
 * layout animations cannot express, and they offer no way to retarget a swap that lands mid-flight.
 */
import React, {useCallback, useEffect, useRef, useState} from "react";
import {I18nManager, StyleSheet, View} from "react-native";
import Animated, {
  cancelAnimation,
  Extrapolation,
  interpolate,
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {PAGE_EASING_REANIMATED, PAGE_SLIDE_DURATION_MS} from "../tokens";

/** How much of a page stays on screen while the next one covers it, or uncovers it going back. */
const PAGE_PARALLAX = 0.25;

interface Page {
  key: string;
  node: React.ReactNode;
  forward: boolean;
}

/**
 * One page slide between two contents. `fill` lays the pages out to a fixed frame (the screen's
 * step area); `wrap` animates the frame's height between the outgoing and incoming pages' own
 * heights — Compose's `pageResize` SizeTransform — so a surface above that is sized by its
 * content (the inline WaniKani panel under the review strip) never jumps as a page opens.
 */
export function StepTransition({
  pageKey,
  forward,
  sizing = "fill",
  children,
}: {
  pageKey: string;
  forward: boolean;
  sizing?: "fill" | "wrap";
  children: React.ReactNode;
}) {
  const [current, setCurrent] = useState<Page>(() => ({
    key: pageKey,
    node: children,
    forward,
  }));
  const [outgoing, setOutgoing] = useState<Page | null>(null);
  const progress = useSharedValue(1);
  const height = useSharedValue(0);
  const runId = useRef(0);
  // Which of the swap's two animations have landed: the slide drives `progress` and, in `wrap`
  // sizing, the frame's height. The outgoing page goes when both have. The two share a duration
  // and so land together, but a swap between two pages of the same height finishes its height
  // animation on the spot — dropping the outgoing then would take the page away while it is still
  // sliding.
  const landed = useRef({slide: false, size: true});
  const [width, setWidth] = useState(0);

  // The swap is detected during render: the page currently on screen — frozen with the state
  // it was last rendered with, exactly like Compose's outgoing content — becomes the outgoing
  // page, and the new one takes its place. React re-runs this render before committing. The
  // driver is reset here rather than in the effect because an animated view computes its first
  // style from the shared value as it mounts: resetting later would paint one frame of the
  // incoming page already parked at its resting position.
  if (current.key !== pageKey) {
    cancelAnimation(progress);
    progress.value = 0;
    landed.current = {slide: false, size: sizing !== "wrap"};
    setOutgoing(current);
    setCurrent({key: pageKey, node: children, forward});
    runId.current += 1;
  }

  /**
   * One of the swap's two animations has landed. The run id comes back from the UI thread because
   * a worklet cannot read the ref, and a swap that landed mid-flight must not drop the page the
   * new one is still leaving.
   */
  const finish = useCallback((id: number, part: "slide" | "size") => {
    if (id !== runId.current) return;
    landed.current[part] = true;
    if (landed.current.slide && landed.current.size) setOutgoing(null);
  }, []);

  useEffect(() => {
    if (outgoing == null) return;
    const id = runId.current;
    progress.value = withTiming(
      1,
      {duration: PAGE_SLIDE_DURATION_MS, easing: PAGE_EASING_REANIMATED},
      (finished) => {
        "worklet";
        if (finished) runOnJS(finish)(id, "slide");
      },
    );
  }, [outgoing, progress, finish]);

  const animateToIncomingHeight = useCallback(
    (measured: number) => {
      if (sizing !== "wrap" || outgoing == null) return;
      const id = runId.current;
      height.value = withTiming(
        measured,
        {duration: PAGE_SLIDE_DURATION_MS, easing: PAGE_EASING_REANIMATED},
        (finished) => {
          "worklet";
          if (finished) runOnJS(finish)(id, "size");
        },
      );
    },
    [sizing, outgoing, height, finish],
  );

  const measureWidth = useCallback((measured: number) => {
    setWidth((current) => (current === measured ? current : measured));
  }, []);

  const rtl = I18nManager.isRTL ? -1 : 1;

  // Forward: the incoming page slides in from the far edge and comes to rest at 0, and the
  // outgoing one eases a quarter of the way out behind it. Back is mirrored: the incoming page
  // is uncovered from a quarter out, while the outgoing one leaves entirely on top.
  const incomingFrom = current.forward ? rtl * width : -rtl * PAGE_PARALLAX * width;
  const outgoingTo = current.forward ? -rtl * PAGE_PARALLAX * width : rtl * width;

  const incomingStyle = useAnimatedStyle(() => ({
    transform: [
      {
        translateX: interpolate(
          progress.value,
          [0, 1],
          [incomingFrom, 0],
          Extrapolation.CLAMP,
        ),
      },
    ],
  }));

  const outgoingSlideStyle = useAnimatedStyle(() => ({
    transform: [
      {
        translateX: interpolate(
          progress.value,
          [0, 1],
          [0, outgoingTo],
          Extrapolation.CLAMP,
        ),
      },
    ],
  }));

  const frameStyle = useAnimatedStyle(() => ({height: height.value}));

  const drawIncomingFirst = current.forward;

  if (sizing === "wrap") {
    if (outgoing == null) {
      // At rest the page is in flow, so the frame is exactly the page's own height — and the
      // driver is kept at that height, which is where the next swap's resize starts from.
      return (
        <View
          style={styles.wrapFrame}
          onLayout={(e) => {
            height.value = e.nativeEvent.layout.height;
            measureWidth(e.nativeEvent.layout.width);
          }}
        >
          {current.node}
        </View>
      );
    }
    // The entering page stays in flow, so `onLayout` reports *its* height — the height the frame
    // animates to. The leaving page fills the frame instead of taking part in the layout, so the
    // two overlap without the frame ever holding both, and it slides its own way out over the same
    // move (a quarter out going forward, the whole width coming back).
    const incomingEl = (
      <Animated.View
        key={current.key}
        style={[styles.wrapPage, incomingStyle]}
        onLayout={(e) => animateToIncomingHeight(e.nativeEvent.layout.height)}
        pointerEvents="box-none"
      >
        {current.node}
      </Animated.View>
    );
    const outgoingEl = (
      <Animated.View
        key={outgoing.key}
        style={[styles.page, outgoingSlideStyle]}
        pointerEvents="box-none"
      >
        {outgoing.node}
      </Animated.View>
    );
    return (
      <Animated.View
        style={[styles.wrapFrame, styles.wrapAnimating, frameStyle]}
        onLayout={(e) => measureWidth(e.nativeEvent.layout.width)}
      >
        {drawIncomingFirst ? [outgoingEl, incomingEl] : [incomingEl, outgoingEl]}
      </Animated.View>
    );
  }

  const layers =
    outgoing == null
      ? [
          <View key={current.key} style={styles.page} pointerEvents="box-none">
            {current.node}
          </View>,
        ]
      : drawIncomingFirst
        ? [
            <Animated.View
              key={outgoing.key}
              style={[styles.page, outgoingSlideStyle]}
              pointerEvents="box-none"
            >
              {outgoing.node}
            </Animated.View>,
            <Animated.View
              key={current.key}
              style={[styles.page, incomingStyle]}
              pointerEvents="box-none"
            >
              {current.node}
            </Animated.View>,
          ]
        : [
            <Animated.View
              key={current.key}
              style={[styles.page, incomingStyle]}
              pointerEvents="box-none"
            >
              {current.node}
            </Animated.View>,
            <Animated.View
              key={outgoing.key}
              style={[styles.page, outgoingSlideStyle]}
              pointerEvents="box-none"
            >
              {outgoing.node}
            </Animated.View>,
          ];

  return (
    <View style={styles.container} onLayout={(e) => measureWidth(e.nativeEvent.layout.width)}>
      {layers}
    </View>
  );
}

/** The key a step animates by: the card itself for a session, the kind for everything else. */
export function stepContentKey(step: {kind: string; token?: number; card?: {noteId: number; ord: number}; revealed?: boolean}): string {
  if (step.kind === "session" && step.card != null) {
    return `${step.card.noteId}:${step.card.ord}:${step.token}:${step.revealed}`;
  }
  return step.kind;
}

const styles = StyleSheet.create({
  container: {flex: 1, overflow: "hidden"},
  page: {position: "absolute", top: 0, left: 0, right: 0, bottom: 0},
  wrapFrame: {width: "100%"},
  // A `wrap` page in flow: its own height is what the frame animates to, so nothing here may be
  // absolutely positioned or stretched to the frame.
  wrapPage: {width: "100%"},
  wrapAnimating: {overflow: "visible"},
});
