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
 * A rounded surface holds a filled selected pill with the selected glyph and label inline; every
 * other tab is a muted glyph on its own. Light and dark modes share the same geometry.
 *
 * The bar never moves (it is a sibling of the pager), and everything inside it is one continuous
 * function of the pager's own `position`: the capsule travels between item centres, the four glyphs
 * part to make room for it, and the capsule's own glyph-and-label pair cross-fades and drifts as it
 * goes. So a swipe drags all of it frame by frame, a tap on a non-adjacent tab walks it across the
 * tabs between, and a tap that lands mid-flight simply retargets the same values. The capsule takes
 * each tab's own measured label width, so a short label stays compact instead of inheriting the
 * width of the longest one.
 *
 * Every one of those is a `transform`, `width` or an `opacity` interpolated from `position`, which the library
 * feeds through the native driver — so the bar stays in step with the pager rather than a frame
 * behind it. Each label is measured at the reader's font scale, so its selected capsule is only as
 * wide as it needs to be, and it shrinks to fit a narrow screen.
 */
import React, {useEffect, useRef, useState} from "react";
import {
  Animated,
  BackHandler,
  I18nManager,
  Pressable,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from "react-native";
import Reanimated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSequence,
  withTiming,
} from "react-native-reanimated";
import type {MaterialTopTabBarProps} from "@react-navigation/material-top-tabs";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {PressableScale} from "./PressableScale";
import {useTheme} from "./theme";
import {useSessionActive} from "./session";
import * as ThemeBridge from "../native/GatenTheme";
import * as Haptics from "../native/GatenHaptics";
import {PRESS_EASING_REANIMATED, shape, typeScale} from "./tokens";
import {Icon} from "./components";
import type {IconName} from "./icons";

/**
 * The bar's own geometry, in dp: a 46 dp capsule inside a 63 dp content box, above whatever the
 * gesture inset needs. `SLOT_WIDTH` is one *inactive* tab — its glyph and its whole touch area — and
 * `MIN_SLOT` is how far it may close up on a narrow screen before the capsule starts giving up width
 * instead. 48 dp is still an accessible target.
 */
const BAR_HEIGHT = 63;
const CAPSULE_HEIGHT = 46;
const BAR_SIDE_MARGIN = 22;
const BAR_INNER_PADDING = 6;
const SLOT_WIDTH = 66;
const MIN_SLOT = 48;
const ICON_SIZE = 20;
const CAPSULE_PADDING = 16;
const CONTENT_GAP = 8;

/** The bar's corners, and how far a capsule label drifts while the capsule crosses to another. */
const BAR_RADIUS = shape.medium;
const CONTENT_DRIFT = 18;

/** Each tab route's glyph, so the bar and its arrival motion never disagree about one. */
const TAB_ICONS: Record<string, IconName> = {
  Home: "home",
  Analytics: "bar_chart",
  Review: "school",
  Settings: "settings",
};

/**
 * How long the glyph waits before its arrival gesture: the pager's own settle is ~250 ms, so
 * starting a little before that keeps the gesture clear of the capsule's travel without feeling
 * detached from the tap.
 */
const ICON_MOTION_DELAY = 160;

/** Draws the tab's own Material Symbols glyph: the bar's shared size, in the ink it is given. */
function TabIcon({name, color}: {name: IconName; color: string}) {
  return <Icon name={name} size={ICON_SIZE} color={color} />;
}

/**
 * Each tab's own arrival gesture. The glyphs are filled Material Symbols paths, so the motion is
 * transform-only: Home bounces, Analytics grows from its baseline like a chart drawn upward,
 * Review tosses the mortarboard up and back, and Settings spins once. The gesture starts
 * [ICON_MOTION_DELAY] ms after selection — about when the capsule has landed — so it is seen
 * rather than smeared across the pager transition. Home, Analytics and Review return to rest;
 * the gear finishes at 360°, which is visually identical to 0°, so resetting it is invisible.
 * Reduced motion leaves every glyph still.
 */
function MotionIcon({
  name,
  color,
  active,
}: {
  name: IconName;
  color: string;
  active: boolean;
}) {
  const reducedMotion = useReducedMotion();
  const progress = useSharedValue(0);
  const firstRender = useRef(true);

  useEffect(() => {
    if (firstRender.current) {
      firstRender.current = false;
      return;
    }
    if (!active || reducedMotion) {
      progress.value = 0;
      return;
    }
    const out = {
      duration: name === "settings" ? 400 : 200,
      easing: PRESS_EASING_REANIMATED,
    };
    progress.value = withDelay(
      ICON_MOTION_DELAY,
      name === "settings"
        ? withTiming(1, out, (finished) => {
            // A whole turn ends where it began; reset without another frame of movement.
            if (finished) {
              progress.value = 0;
            }
          })
        : withSequence(withTiming(1, out), withTiming(0, out)),
    );
  }, [active, reducedMotion, name, progress]);

  const motion = useAnimatedStyle(() => {
    const p = progress.value;
    switch (name) {
      case "home":
        return {transform: [{translateY: -2.5 * p}, {scale: 1 + 0.16 * p}]};
      case "bar_chart":
        return {transform: [{scaleY: 1 + 0.25 * p}]};
      case "school":
        return {
          transform: [
            {translateY: -3 * p},
            {rotate: `${-10 * p}deg`},
          ],
        };
      default:
        return {transform: [{rotate: `${360 * p}deg`}]};
    }
  });

  return (
    <Reanimated.View
      style={[
        {transformOrigin: name === "bar_chart" ? "bottom" : "center"},
        motion,
      ]}
    >
      <TabIcon name={name} color={color} />
    </Reanimated.View>
  );
}

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
 * The selected capsule and remaining slots fill the row between 6 dp side insets. Glyph shifts
 * are measured from their fixed resting slots so the same layout can animate between tabs.
 */
function layoutAt(
  selected: number,
  count: number,
  width: number,
  slot: number,
  capsuleWidths: number[],
) {
  const row = count * slot;
  const slotsLeft = (width - row) / 2;
  const capsuleWidth = capsuleWidths[selected];
  const idealLeft = count > 1 ? BAR_INNER_PADDING : (width - capsuleWidth) / 2;
  const inactiveSlot = count > 1
    ? Math.max(0, (width - BAR_INNER_PADDING * 2 - capsuleWidth) / (count - 1))
    : 0;
  const start = (index: number) =>
    index <= selected
      ? idealLeft + index * inactiveSlot
      : idealLeft + capsuleWidth + (index - 1) * inactiveSlot;
  const box = (index: number) => (index === selected ? capsuleWidth : inactiveSlot);

  return {
    slotsLeft,
    capsuleX: start(selected),
    capsuleWidth,
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
  icon,
  index,
  selected,
  iconColor,
  slotLeft,
  slot,
  shift,
  position,
  inputRange,
  onPress,
}: {
  label: string;
  icon: IconName;
  index: number;
  selected: boolean;
  iconColor: string;
  slotLeft: number;
  slot: number;
  shift: number[];
  position: MaterialTopTabBarProps["position"];
  inputRange: number[];
  onPress: () => void;
}) {
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
        showFocusRing={false}
        accessibilityRole="tab"
        accessibilityState={{selected}}
        accessibilityLabel={label}
        onPress={onPress}
      >
        <Animated.View style={{opacity: plain}}>
          <TabIcon name={icon} color={iconColor} />
        </Animated.View>
      </PressableScale>
    </Animated.View>
  );
}

export function BottomBar({
  state,
  navigation,
  position,
  layout,
  searchActive = false,
}: MaterialTopTabBarProps & {searchActive?: boolean}) {
  const {colors, mode} = useTheme();
  const systemMode = useColorScheme();
  const dark = mode === "Dark" || (mode === "System" && systemMode === "dark");
  const capsuleColor = colors.actionPrimary;
  const capsuleInk = colors.onActionPrimary;
  const barColor = colors.bottom;
  const iconColor = colors.fine;
  const background = colors.background;
  const insets = useSafeAreaInsets();
  const sessionActive = useSessionActive();
  // The one measured thing in the bar: how wide each label is, at the reader's own font scale.
  const [labelWidths, setLabelWidths] = useState<number[] | null>(null);

  const depth = stackDepth(state, state.routes[state.index]?.name ?? "");
  const visible = !sessionActive && depth === 0 && !searchActive;
  const atNonHomeRoot = visible && state.index !== 0;

  useEffect(() => {
    ThemeBridge.setNavigationBarAppearance(background, dark).catch(
      (error: unknown) => {
        console.warn("Could not update Android navigation bar appearance.", error);
      },
    );
  }, [background, dark]);

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
            // Leave a pixel-rounding allowance so measured labels do not ellipsize.
            const width = Math.ceil(event.nativeEvent.layout.width) + 1;
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
  const width = Math.min(380, Math.max(0, layout.width - BAR_SIDE_MARGIN * 2));

  // Until the labels have been measured — one layout pass — there is nothing to size the capsules
  // with, so the bar is drawn empty rather than at a width that would jump.
  if (labelWidths == null || width === 0 || count === 0) {
    return (
      <View
        style={{
          position: "absolute",
          left: 0,
          right: 0,
          bottom: 0,
          height: BAR_HEIGHT + insets.bottom,
        }}
      >
        <View
          style={[
            styles.surface,
            {
              height: BAR_HEIGHT,
              width,
              backgroundColor: barColor,
            },
          ]}
        >
          <View style={{height: BAR_HEIGHT, width, alignSelf: "center"}}>
            {measureRow}
          </View>
        </View>
      </View>
    );
  }

  // Reserve enough room for the widest capsule and its neighbours first. Each selected capsule then
  // uses its own label width, while the inactive slots preserve their full touch target where space
  // allows.
  const capsuleIdeals = labelWidths.map(
    (labelWidth, index) => Math.max(state.routes[index].name === "Home" ? 113 : 0, CAPSULE_PADDING * 2 + ICON_SIZE + CONTENT_GAP + labelWidth),
  );
  const spare =
    count > 1 ? (width - BAR_INNER_PADDING * 2 - Math.max(...capsuleIdeals)) / (count - 1) : SLOT_WIDTH;
  const slot = Math.max(MIN_SLOT, Math.min(SLOT_WIDTH, spare));
  const capsuleWidths = capsuleIdeals.map((ideal) =>
    Math.max(0, Math.min(ideal, width - BAR_INNER_PADDING * 2 - (count - 1) * slot)),
  );
  const widestCapsuleWidth = Math.max(1, ...capsuleWidths);
  const inputRange = state.routes.map((_, index) => index);
  const settled = inputRange.map((selected) =>
    layoutAt(selected, count, width, slot, capsuleWidths),
  );
  const slotsLeft = settled[0].slotsLeft;
  // RTL lays the items out from the right, so the pager's position runs the other way.
  const rtl = I18nManager.isRTL ? -1 : 1;
  // Width itself is a layout property and cannot follow the pager's native animation. Scale only
  // the capsule background and touch shield, while the label stays at its natural size.
  const capsuleScaleX = position.interpolate({
    inputRange,
    outputRange: settled.map((frame) => frame.capsuleWidth / widestCapsuleWidth),
    extrapolate: "clamp",
  });
  const capsuleOffsetX = position.interpolate({
    inputRange,
    outputRange: settled.map(
      (frame) =>
        rtl * (frame.capsuleX + frame.capsuleWidth / 2 - widestCapsuleWidth / 2),
    ),
    extrapolate: "clamp",
  });

  // Only the floating track paints a surface; the pager continues beneath its margins and gesture inset.
  return (
    <View
      style={{
        position: "absolute",
        left: 0,
        right: 0,
        bottom: 0,
        height: BAR_HEIGHT + insets.bottom,
      }}
    >
      <View
        style={[
          styles.surface,
          {
            height: BAR_HEIGHT,
            width,
            backgroundColor: barColor,
          },
        ]}
      >
      <View style={{height: BAR_HEIGHT, width, alignSelf: "center"}}>
        {measureRow}
        <View style={styles.stage}>
        {state.routes.map((route, index) => (
          <BarItem
            key={route.key}
            label={route.name}
            icon={TAB_ICONS[route.name] ?? "settings"}
            index={index}
            selected={index === state.index}
            iconColor={iconColor}
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
          The capsule: one accent pill carrying its own glyph and label, cross-faded and drifted per
          tab, so it reads as the selected item opening rather than as two pills swapping.
        */}
        <Animated.View
          pointerEvents="none"
          accessible={false}
          importantForAccessibility="no-hide-descendants"
          style={[
            styles.capsulePosition,
            {
              width: widestCapsuleWidth,
              transform: [{translateX: capsuleOffsetX}],
            },
          ]}
        >
          <Animated.View
            style={[
              styles.capsuleFill,
              {backgroundColor: capsuleColor, transform: [{scaleX: capsuleScaleX}]},
            ]}
          />
        </Animated.View>
        <Animated.View
          pointerEvents="none"
          accessible={false}
          importantForAccessibility="no-hide-descendants"
          style={[
            styles.capsuleContentFrame,
            {width: widestCapsuleWidth, transform: [{translateX: capsuleOffsetX}]},
          ]}
        >
          {state.routes.map((route, index) => {
            const icon = TAB_ICONS[route.name] ?? "settings";
            return (
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
              <MotionIcon
                name={icon}
                color={capsuleInk}
                active={index === state.index}
              />
              <Text
                numberOfLines={1}
                adjustsFontSizeToFit
                minimumFontScale={0.8}
                style={[
                  styles.capsuleLabel,
                  {
                    color: capsuleInk,
                    maxWidth: Math.max(
                      0,
                      capsuleWidths[index] -
                        CAPSULE_PADDING * 2 -
                        ICON_SIZE -
                        CONTENT_GAP,
                    ),
                  },
                ]}
              >
                {route.name}
              </Text>
            </Animated.View>
            );
          })}
        </Animated.View>
        {/*
          The capsule's own touch shield: it swallows taps that land on the selected pill (which, as
          before, do nothing) instead of letting them reach the glyph slot underneath, whose shifted
          box can sit under the label end of the capsule.
        */}
        <Animated.View
          style={[
            styles.shieldPosition,
            {width: widestCapsuleWidth, transform: [{translateX: capsuleOffsetX}]},
          ]}
        >
          <Animated.View style={[styles.shieldScaled, {transform: [{scaleX: capsuleScaleX}]}]}>
            <Pressable
              style={styles.shieldPress}
              onPress={() => {}}
              accessible={false}
              importantForAccessibility="no"
            />
          </Animated.View>
        </Animated.View>
        </View>
      </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  surface: {
    alignSelf: "center",
    borderRadius: BAR_RADIUS,
  },
  stage: {flex: 1},
  measureRow: {position: "absolute", flexDirection: "row", opacity: 0},
  slot: {position: "absolute", top: 0, bottom: 0},
  slotPress: {flex: 1, alignItems: "center", justifyContent: "center"},
  capsulePosition: {
    position: "absolute",
    top: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    start: 0,
    height: CAPSULE_HEIGHT,
  },
  capsuleFill: {
    flex: 1,
    borderRadius: shape.navActive,
    overflow: "hidden",
  },
  capsuleContentFrame: {
    position: "absolute",
    top: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    bottom: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    start: 0,
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
  capsuleLabel: typeScale.navigation,
  shieldPosition: {
    position: "absolute",
    top: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    start: 0,
    height: 48,
  },
  shieldScaled: {flex: 1},
  shieldPress: {flex: 1},
});
