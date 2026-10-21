/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The app's bottom bar: three tabs, drawn by hand rather than by the navigator, because a page pushed
 * over a tab has to be full-screen with no bar.
 *
 * A rounded surface holds a filled selected pill with the selected glyph and label inline; every
 * other tab is a muted glyph on its own. Light and dark modes share the same geometry.
 *
 * The bar never moves (it is a sibling of the pager), and everything inside it is one continuous
 * function of the pager's own `position`: the capsule travels between item centres, the three glyphs
 * part to make room for it, and the capsule's own glyph-and-label pair cross-fades and drifts as it
 * goes. So a swipe drags all of it frame by frame, a tap on a non-adjacent tab walks it across the
 * tabs between, and a tap that lands mid-flight simply retargets the same values. The capsule takes
 * each tab's own measured label width, so a short label stays compact instead of inheriting the
 * width of the longest one.
 *
 * Every one of those is a `transform`, `width` or an `opacity` interpolated from `position`, which the library
 * feeds through the native driver — so the bar stays in step with the pager rather than a frame
 * behind. Each label is measured at the reader's font scale, so its selected capsule is only as
 * wide as it needs to be, and it shrinks to fit a narrow screen.
 *
 * The travel belongs to the pager and must not be timed here, but the *end* of it — on a tap — is the
 * bar's own: the pill is given the speed that travel was carrying, runs a few dp past the tab it is
 * aiming at and springs back onto it. A drag injects nothing, because the finger has already decided
 * where that travel stops.
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
  cancelAnimation,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSequence,
  withSpring,
  withTiming,
} from "react-native-reanimated";
import type {MaterialTopTabBarProps} from "@react-navigation/material-top-tabs";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {PressableScale} from "./PressableScale";
import {useTheme} from "./theme";
import * as ThemeBridge from "../native/GatenTheme";
import * as Haptics from "../native/GatenHaptics";
import {PRESS_EASING_REANIMATED, shape, typeScale} from "./tokens";
import {Icon} from "./components";
import type {IconName} from "./icons";

/**
 * The bar's own geometry, in dp: a 46 dp capsule inside a 63 dp content box, above whatever the
 * gesture inset needs. `SLOT_WIDTH` is one *inactive* tab — its glyph and its whole touch area — and
 * is also what the bar is sized from: it is as wide as the widest capsule plus one slot per other
 * tab plus its inner padding, capped by the display. `MIN_SLOT` is how far a slot may close up on a
 * screen too narrow for that, before the capsule starts giving up width instead. 48 dp is still an
 * accessible target.
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
  Settings: "settings",
};

/**
 * How long the glyph waits before its arrival gesture: the pager's own settle is ~250 ms, so
 * starting a little before that keeps the gesture clear of the capsule's travel without feeling
 * detached from the tap.
 */
const ICON_MOTION_DELAY = 160;

// A small horizontal overshoot layered over the pager's travel, only after a tab tap.
const SLIDE_SPRING = {mass: 1, stiffness: 500, damping: 20};
const SLIDE_VELOCITY = 270;
const SLIDE_SETTLE_DELAY = 160;

/** Draws the tab's own Material Symbols glyph: the bar's shared size, in the ink it is given. */
function TabIcon({name, color}: {name: IconName; color: string}) {
  return <Icon name={name} size={ICON_SIZE} color={color} />;
}

/**
 * Each tab's own arrival gesture. The glyphs are filled Material Symbols paths, so the motion is
 * transform-only: Home bounces, Analytics grows from its baseline like a chart drawn upward, and
 * Settings spins once. The gesture starts [ICON_MOTION_DELAY] ms after selection — about when the
 * capsule has landed — so it is seen rather than smeared across the pager transition. Home and
 * Analytics return to rest; the gear finishes at 360°, which is visually identical to 0°, so
 * resetting it is invisible. Reduced motion leaves every glyph still.
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
  descriptors,
  position,
  layout,
  searchActive = false,
}: MaterialTopTabBarProps & {searchActive?: boolean}) {
  const {colors, mode} = useTheme();
  const systemMode = useColorScheme();
  const dark = mode === "Dark" || (mode === "System" && systemMode === "dark");
  // The selected pill wears the reader's accent, not the neutral action fill: the bar's active
  // state is the one place the accent is always visible, and `onPrimary` is already the ink that
  // reads on that tone in either mode.
  const capsuleColor = colors.accent;
  const capsuleInk = colors.onPrimary;
  const barColor = colors.bottom;
  const iconColor = colors.fine;
  const background = colors.background;
  const insets = useSafeAreaInsets();
  // The one measured thing in the bar: how wide each label is, at the reader's own font scale.
  const [labelWidths, setLabelWidths] = useState<number[] | null>(null);

  const depth = stackDepth(state, state.routes[state.index]?.name ?? "");
  const visible = depth === 0 && !searchActive;
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

  // The capsule's settle: its travel is the pager's, so the one part of the pill's motion the bar
  // owns is the end of that travel. `capsule` is the extra distance the pill is carrying past its
  // tab, in dp — zero at rest, and only ever nonzero while a tap's spring is settling.
  const reducedMotion = useReducedMotion();
  const capsule = useSharedValue(0);

  const swipeNavigation = descriptors[state.routes[0].key].navigation;
  useEffect(() => {
    const unsubscribe = swipeNavigation.addListener("swipeStart", () => {
      cancelAnimation(capsule);
      capsule.value = 0;
    });
    return () => {
      unsubscribe();
      cancelAnimation(capsule);
    };
  }, [swipeNavigation, capsule]);

  const capsuleSettle = useAnimatedStyle(() => ({
    transform: [{translateX: capsule.value}],
  }));

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
  // The room the bar may take: the display, less its own side margins.
  const available = Math.max(0, layout.width - BAR_SIDE_MARGIN * 2);

  // Until the labels have been measured — one layout pass — how wide the bar needs to be is not
  // known, so only the invisible measuring row is mounted. The bar is absolutely positioned, so it
  // appears at its final width on the next pass rather than at a width that would jump, and nothing
  // reflows either way.
  if (labelWidths == null || count === 0 || available === 0) {
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
        {measureRow}
      </View>
    );
  }

  // Reserve enough room for the widest capsule and its neighbours first. Each selected capsule then
  // uses its own label width, while the inactive slots preserve their full touch target where space
  // allows.
  const capsuleIdeals = labelWidths.map(
    (labelWidth, index) => Math.max(state.routes[index].name === "Home" ? 113 : 0, CAPSULE_PADDING * 2 + ICON_SIZE + CONTENT_GAP + labelWidth),
  );
  const widestCapsule = Math.max(...capsuleIdeals);
  // The bar is only as wide as the tabs it holds — the widest capsule, the others at their own touch
  // width, and the inner padding — so a bar of three does not strand its glyphs in empty space. It is
  // a function of the measured labels alone, never of the selection, so the surface stays put while
  // the capsule travels inside it; a display too narrow for that gets the room it has.
  const width = Math.min(
    available,
    BAR_INNER_PADDING * 2 + widestCapsule + (count - 1) * SLOT_WIDTH,
  );
  const spare =
    count > 1 ? (width - BAR_INNER_PADDING * 2 - widestCapsule) / (count - 1) : SLOT_WIDTH;
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
              cancelAnimation(capsule);
              capsule.value = 0;
              if (reducedMotion) {
                return;
              }
              // Only taps add horizontal overshoot; manual swipes use the pager position alone.
              capsule.value = withDelay(
                SLIDE_SETTLE_DELAY,
                withSpring(0, {
                  ...SLIDE_SPRING,
                  velocity: (index > state.index ? 1 : -1) * rtl * SLIDE_VELOCITY,
                }),
              );
            }}
          />
        ))}
        {/*
          The capsule: one accent pill carrying its own glyph and label, cross-faded and drifted per
          tab, so it reads as the selected item opening rather than as two pills swapping. The fill
          and the content are two nodes over one box — the fill paints below the plain glyphs, the
          content above them — so the tap's settle wraps both: same box, same offset, and the pill
          moves as one object.
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
          <Reanimated.View style={[styles.capsuleInner, capsuleSettle]}>
            <Animated.View
              style={[
                styles.capsuleFill,
                {backgroundColor: capsuleColor, transform: [{scaleX: capsuleScaleX}]},
              ]}
            />
          </Reanimated.View>
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
          <Reanimated.View style={[styles.capsuleContentSettle, capsuleSettle]}>
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
          </Reanimated.View>
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
  capsuleInner: {flex: 1},
  capsuleContentFrame: {
    position: "absolute",
    top: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    bottom: (BAR_HEIGHT - CAPSULE_HEIGHT) / 2,
    start: 0,
  },
  capsuleContentSettle: {position: "absolute", top: 0, bottom: 0, start: 0, end: 0},
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
