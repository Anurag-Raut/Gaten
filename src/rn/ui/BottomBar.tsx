/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The app's bottom bar: four tabs, drawn by hand rather than by the navigator, because a page pushed
 * over a tab has to be full-screen with no bar and the bar has to disappear entirely while a review
 * session is on screen.
 *
 * The look is the design the reader asked for: a near-black bar with generous top corners, the
 * selected tab held in a white capsule with its glyph *and* its label inline, and every other tab a
 * muted glyph on its own — no text under the unselected ones.
 *
 * The bar never moves (it is a sibling of the pager), and everything inside it is one continuous
 * function of the pager's own `position`: the capsule travels between item centres, the four glyphs
 * part to make room for it, and the capsule's own glyph-and-label pair cross-fades and drifts as it
 * goes. So a swipe drags all of it frame by frame, a tap on a non-adjacent tab walks it across the
 * tabs between, and a tap that lands mid-flight simply retargets the same values.
 *
 * Every one of those is a `transform` or an `opacity` interpolated from `position`, which the library
 * feeds through the native driver — so the bar stays in step with the pager rather than a frame
 * behind it. The one measurement is the widest label: that is what sizes the capsule, so it can never
 * crop the selected label at any font scale, and it shrinks to fit a narrow screen.
 */
import React, {useEffect, useState} from "react";
import {
  Animated,
  BackHandler,
  I18nManager,
  Pressable,
  StyleSheet,
  Text,
  View,
} from "react-native";
import type {MaterialTopTabBarProps} from "@react-navigation/material-top-tabs";
import Svg, {Path} from "react-native-svg";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {PressableScale} from "./PressableScale";
import {typeScale} from "./tokens";
import {useTheme} from "./theme";
import {useSessionActive} from "./session";
import * as Haptics from "../native/GatenHaptics";

/**
 * The bar's own geometry, in dp: a 52 dp capsule inside a 68 dp content box, above whatever the
 * gesture inset needs. `SLOT_WIDTH` is one *inactive* tab — its glyph and its whole touch area — and
 * `MIN_SLOT` is how far it may close up on a narrow screen before the capsule starts giving up width
 * instead. 48 dp is still an accessible target, and the widest label still fits a 320 dp phone at the
 * default font scale.
 */
const BAR_HEIGHT = 68;
const CAPSULE_HEIGHT = 52;
const SLOT_WIDTH = 64;
const MIN_SLOT = 48;
const ICON_SIZE = 24;
const CAPSULE_PADDING = 16;
const CONTENT_GAP = 8;

/** The bar's top corners, and how far a capsule label drifts while the capsule crosses to another. */
const BAR_RADIUS = 28;
const CONTENT_DRIFT = 18;

/**
 * The bar's colours all come from the reader's theme, so it is part of whatever palette, mode and
 * accent they chose and updates with them as they change: the surface container step for the bar
 * itself (a tonal step off the page, not a block of its own), the accent's container for the capsule
 * that holds the selected tab, the ink that reads on it, and the muted role for the tabs that are
 * not selected. The Kotlin theme maps `secondaryContainer` — the role a Material navigation bar
 * indicator uses — onto the accent's own container, so the capsule follows an accent change.
 */

/** The four glyphs, outlined, at 24 dp: home, analytics, style and settings. */
const ICON_PATHS: Record<string, string> = {
  Home: "M12 3 2 12h3v8h6v-6h2v6h6v-8h3L12 3Z",
  Analytics:
    "M5 9.2h3V19H5V9.2ZM10.6 5h2.8v14h-2.8V5ZM16.2 13H19v6h-2.8v-6Z",
  Review:
    "M12 3a9 9 0 0 0-9 9c0 4.97 4.03 9 9 9s9-4.03 9-9-4.03-9-9-9Zm0 4a2 2 0 1 1-2 2 2 2 0 0 1 2-2Zm0 11.2a6.4 6.4 0 0 1-4.8-2.24c.02-1.6 3.2-2.46 4.8-2.46s4.78.86 4.8 2.46A6.4 6.4 0 0 1 12 18.2Z",
  Settings:
    "M19.14 12.94a7.6 7.6 0 0 0 .05-.94 7.6 7.6 0 0 0-.06-.94l2.03-1.58a.5.5 0 0 0 .12-.62l-1.92-3.32a.5.5 0 0 0-.6-.22l-2.39.96a7.3 7.3 0 0 0-1.62-.94l-.36-2.54a.5.5 0 0 0-.5-.42h-3.84a.5.5 0 0 0-.5.42l-.36 2.54c-.59.25-1.13.57-1.62.94l-2.39-.96a.5.5 0 0 0-.6.22L2.72 8.86a.5.5 0 0 0 .12.62l2.03 1.58a7.6 7.6 0 0 0 0 1.88l-2.03 1.58a.5.5 0 0 0-.12.62l1.92 3.32c.12.22.38.3.6.22l2.39-.96c.5.37 1.03.69 1.62.94l.36 2.54a.5.5 0 0 0 .5.42h3.84a.5.5 0 0 0 .5-.42l.36-2.54c.59-.25 1.13-.57 1.62-.94l2.39.96c.22.08.48 0 .6-.22l1.92-3.32a.5.5 0 0 0-.12-.62l-2.03-1.58Zm-7.14 2.7a3.6 3.6 0 1 1 3.6-3.6 3.6 3.6 0 0 1-3.6 3.6Z",
};

/** How deep the named tab's own stack is: a pushed page hides the bar. */
function stackDepth(state: MaterialTopTabBarProps["state"], name: string): number {
  const route = state.routes.find((candidate) => candidate.name === name);
  const nested = route?.state;
  return nested && typeof nested.index === "number" ? nested.index : 0;
}

/**
 * Where one settled selection puts the bar, in the bar's own frame (x = 0 at its left edge): the
 * capsule's own left edge, and how far each glyph has moved from where it sits with no selection at
 * all. Every state the bar can settle in is one call of this, and `position` interpolates between
 * them, so the whole layout — capsule and glyphs alike — is one continuous function of the pager.
 *
 * The row is centred twice: once with four equal slots (what the glyphs rest on), and once with the
 * selected tab's slot as wide as the capsule (what the selection looks like when it settles). The
 * difference between the two is exactly the shift each glyph needs.
 */
function layoutAt(
  selected: number,
  count: number,
  width: number,
  capsuleWidth: number,
  slot: number,
) {
  const row = count * slot;
  const slotsLeft = (width - row) / 2;
  const idealLeft = (width - (row - slot + capsuleWidth)) / 2;
  const start = (index: number) =>
    idealLeft + (index <= selected ? index * slot : capsuleWidth + (index - 1) * slot);
  const box = (index: number) => (index === selected ? capsuleWidth : slot);

  return {
    slotsLeft,
    capsuleX: start(selected),
    iconShift: (index: number) =>
      start(index) + box(index) / 2 - (slotsLeft + index * slot + slot / 2),
  };
}

/**
 * One unselected tab: its muted glyph, on a slot that is exactly as wide as the glyph's touch area.
 * The whole slot — touch included — is translated to where the selection's layout puts it, so the
 * glyphs part as the capsule arrives; the glyph then fades out under the capsule, which carries its
 * own copy of it.
 */
function BarItem({
  label,
  index,
  selected,
  slotLeft,
  slot,
  shift,
  position,
  inputRange,
  onPress,
}: {
  label: string;
  index: number;
  selected: boolean;
  slotLeft: number;
  slot: number;
  shift: number[];
  position: MaterialTopTabBarProps["position"];
  inputRange: number[];
  onPress: () => void;
}) {
  const {colors} = useTheme();
  const translateX = position.interpolate({
    inputRange,
    outputRange: shift,
    extrapolate: "clamp",
  });
  // 1 while this glyph is on its own, 0 once the capsule covers it.
  const plain = position.interpolate({
    inputRange,
    outputRange: inputRange.map((candidate) => (candidate === index ? 0 : 1)),
    extrapolate: "clamp",
  });

  return (
    <Animated.View
      style={[
        styles.slot,
        {start: slotLeft + index * slot, width: slot, transform: [{translateX}]},
      ]}
    >
      <PressableScale
        style={styles.slotPress}
        accessibilityRole="tab"
        accessibilityState={{selected}}
        accessibilityLabel={label}
        onPress={onPress}
      >
        <Animated.View style={{opacity: plain}}>
          <Svg width={ICON_SIZE} height={ICON_SIZE} viewBox="0 0 24 24">
            <Path
              d={ICON_PATHS[label] ?? ICON_PATHS.Home}
              fill={colors.onSurfaceVariant}
            />
          </Svg>
        </Animated.View>
      </PressableScale>
    </Animated.View>
  );
}

export function BottomBar({state, navigation, position, layout}: MaterialTopTabBarProps) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const sessionActive = useSessionActive();
  // The one measured thing in the bar: how wide each label is, at the reader's own font scale.
  const [labelWidths, setLabelWidths] = useState<number[] | null>(null);

  const depth = stackDepth(state, state.routes[state.index]?.name ?? "");
  const visible = !sessionActive && depth === 0;
  const atNonHomeRoot = visible && state.index !== 0;

  // Back from a tab root leaves for Home; a pushed page is handled by its own stack, and at
  // Home's root Back belongs to the system. (The tab navigator's own `backBehavior` is
  // `firstRoute`, the same rule — this is the port's longer-standing one and stays authoritative.)
  useEffect(() => {
    if (!atNonHomeRoot) {
      return;
    }
    const subscription = BackHandler.addEventListener(
      "hardwareBackPress",
      () => {
        navigation.navigate("Home");
        return true;
      },
    );
    return () => subscription.remove();
  }, [atNonHomeRoot, navigation]);

  if (!visible) {
    return null;
  }

  const measureRow = (
    <View
      style={styles.measureRow}
      pointerEvents="none"
      importantForAccessibility="no-hide-descendants"
    >
      {state.routes.map((route, index) => (
        <Text
          key={route.key}
          style={styles.capsuleLabel}
          onLayout={(event) => {
            const width = event.nativeEvent.layout.width;
            setLabelWidths((current) => {
              if (current?.[index] === width) {
                return current;
              }
              const next = [...(current ?? state.routes.map(() => 0))];
              next[index] = width;
              return next;
            });
          }}
        >
          {route.name}
        </Text>
      ))}
    </View>
  );

  const count = state.routes.length;
  const width = layout.width;

  // Until the labels have been measured — one layout pass — there is nothing to size the capsule
  // with, so the bar is drawn empty rather than at a width that would jump.
  if (labelWidths == null || width === 0 || count === 0) {
    return (
      <View
        style={{
          height: BAR_HEIGHT + insets.bottom,
          backgroundColor: colors.background,
        }}
      >
        <View
          style={[
            styles.bar,
            {
              flex: 1,
              paddingBottom: insets.bottom,
              backgroundColor: colors.surfaceContainer,
            },
          ]}
        >
          {measureRow}
        </View>
      </View>
    );
  }

  // The capsule is sized for the widest label, so the selected one can never be cropped. The
  // inactive slots keep their full width unless the screen cannot hold that capsule beside them: then
  // they close up — never below a 48 dp target — and the capsule gives up the rest of the width.
  const capsuleIdeal =
    CAPSULE_PADDING * 2 + ICON_SIZE + CONTENT_GAP + Math.max(...labelWidths);
  const spare = count > 1 ? (width - capsuleIdeal) / (count - 1) : SLOT_WIDTH;
  const slot = Math.max(MIN_SLOT, Math.min(SLOT_WIDTH, spare));
  const capsuleWidth = Math.min(capsuleIdeal, width - (count - 1) * slot);
  const inputRange = state.routes.map((_, index) => index);
  const settled = inputRange.map((selected) =>
    layoutAt(selected, count, width, capsuleWidth, slot),
  );
  const slotsLeft = settled[0].slotsLeft;
  // RTL lays the items out from the right, so the pager's position runs the other way.
  const rtl = I18nManager.isRTL ? -1 : 1;
  const capsuleX = position.interpolate({
    inputRange,
    outputRange: settled.map((frame) => rtl * frame.capsuleX),
    extrapolate: "clamp",
  });
  const labelLimit = Math.max(
    0,
    capsuleWidth - CAPSULE_PADDING * 2 - ICON_SIZE - CONTENT_GAP,
  );

  // The bar's box is painted with the page's own colour behind the rounded surface: the two top
  // corners are cut out of the bar, and the notch beside each one used to show whatever lies behind
  // the whole navigator — the window — instead of the surface the bar sits under. Painting the box
  // underneath fixes both corners, in either theme, without touching the bar's own colour, its
  // radius, its height or anything inside it.
  return (
    <View
      style={{
        height: BAR_HEIGHT + insets.bottom,
        backgroundColor: colors.background,
      }}
    >
      <View
        style={[
          styles.bar,
          {
            flex: 1,
            paddingBottom: insets.bottom,
            backgroundColor: colors.surfaceContainer,
          },
        ]}
      >
        {measureRow}
        <View style={styles.stage}>
        {state.routes.map((route, index) => (
          <BarItem
            key={route.key}
            label={route.name}
            index={index}
            selected={index === state.index}
            slotLeft={slotsLeft}
            slot={slot}
            shift={settled.map((frame) => rtl * frame.iconShift(index))}
            position={position}
            inputRange={inputRange}
            onPress={() => {
              if (index === state.index) {
                // An already-selected tab is a no-op: no tick, no navigation.
                return;
              }
              Haptics.play("tick");
              navigation.navigate(route.name);
            }}
          />
        ))}
        {/*
          The capsule: one white pill carrying its own glyph and label, cross-faded and drifted per
          tab, so it reads as the selected item opening rather than as two pills swapping.
        */}
        <Animated.View
          pointerEvents="none"
          accessible={false}
          importantForAccessibility="no-hide-descendants"
          style={[
            styles.capsule,
            {
              width: capsuleWidth,
              backgroundColor: colors.primaryContainer,
              transform: [{translateX: capsuleX}],
            },
          ]}
        >
          {state.routes.map((route, index) => (
            <Animated.View
              key={route.key}
              style={[
                styles.capsuleContent,
                {
                  opacity: position.interpolate({
                    inputRange,
                    outputRange: inputRange.map((candidate) =>
                      candidate === index ? 1 : 0,
                    ),
                    extrapolate: "clamp",
                  }),
                  transform: [
                    {
                      translateX: position.interpolate({
                        inputRange,
                        outputRange: inputRange.map((candidate) =>
                          rtl * (candidate - index) * CONTENT_DRIFT,
                        ),
                        extrapolate: "clamp",
                      }),
                    },
                  ],
                },
              ]}
            >
              <Svg width={ICON_SIZE} height={ICON_SIZE} viewBox="0 0 24 24">
                <Path
                  d={ICON_PATHS[route.name] ?? ICON_PATHS.Home}
                  fill={colors.onPrimaryContainer}
                />
              </Svg>
              <Text
                numberOfLines={1}
                style={[
                  styles.capsuleLabel,
                  {color: colors.onPrimaryContainer, maxWidth: labelLimit},
                ]}
              >
                {route.name}
              </Text>
            </Animated.View>
          ))}
        </Animated.View>
        {/*
          The capsule's own touch shield: it swallows taps that land on the selected pill (which, as
          before, do nothing) instead of letting them reach the glyph slot underneath, whose shifted
          box can sit under the label end of the capsule.
        */}
        <Animated.View
          style={[
            styles.shield,
            {width: capsuleWidth, transform: [{translateX: capsuleX}]},
          ]}
        >
          <Pressable
            style={styles.shieldPress}
            onPress={() => {}}
            accessible={false}
            importantForAccessibility="no"
          />
        </Animated.View>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  bar: {
    borderTopLeftRadius: BAR_RADIUS,
    borderTopRightRadius: BAR_RADIUS,
  },
  stage: {flex: 1},
  measureRow: {position: "absolute", flexDirection: "row", opacity: 0},
  slot: {position: "absolute", top: 0, bottom: 0},
  slotPress: {flex: 1, alignItems: "center", justifyContent: "center"},
  capsule: {
    position: "absolute",
    top: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    start: 0,
    height: CAPSULE_HEIGHT,
    borderRadius: CAPSULE_HEIGHT / 2,
    overflow: "hidden",
  },
  capsuleContent: {
    position: "absolute",
    top: 0,
    bottom: 0,
    start: 0,
    end: 0,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: CONTENT_GAP,
  },
  capsuleLabel: {...typeScale.labelLarge},
  shield: {
    position: "absolute",
    top: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    start: 0,
    height: CAPSULE_HEIGHT,
  },
  shieldPress: {flex: 1},
});
