/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The shared building blocks the Compose screens had in `ui/components`: the card and the
 * press-scale primitive that drives it, the notice card, the chips, the buttons, the icon glyph
 * and the kanji strip. Screens read their colours from `useTheme` and their sizes from `tokens`.
 */
import React, {useEffect} from "react";
import {
  ScrollView,
  Text,
  View,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import Svg, {Path} from "react-native-svg";
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  withTiming,
} from "react-native-reanimated";
import {PressableScale} from "./PressableScale";
import {ICON_PATHS, type IconName} from "./icons";
import * as GatenHaptics from "../native/GatenHaptics";
import {useTheme} from "./theme";
import {
  CARD_ELEVATION,
  PRESS_EASING_REANIMATED,
  shape,
  spacing,
  typeScale,
} from "./tokens";

/** One Material Symbols glyph, drawn at `size` dp in the current colour. */
export function Icon({
  name,
  size = 24,
  color,
}: {
  name: IconName;
  size?: number;
  color: string;
}) {
  const paths = ICON_PATHS[name].d;
  return (
    <Svg width={size} height={size} viewBox="0 -960 960 960">
      {paths.map((d, index) => (
        <Path key={index} d={d} fill={color} />
      ))}
    </Svg>
  );
}

/** Flat content surface; screens use spacing and separators to establish grouping. */
export function Card({
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
          backgroundColor: colors.surface,
          borderRadius: shape.medium,
          elevation: CARD_ELEVATION,
        },
        style,
      ]}
    >
      {children}
    </View>
  );
}

/** A card that presses down with `pressScale` and opens whatever the page says. */
export function PressableCard({
  onPress,
  disabled,
  accessibilityLabel,
  accessibilityState,
  style,
  children,
}: {
  onPress: () => void;
  disabled?: boolean;
  accessibilityLabel?: string;
  accessibilityState?: {disabled?: boolean; selected?: boolean};
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  return (
    <PressableScale
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={accessibilityState}
      style={[
        {
          backgroundColor: colors.surface,
          borderRadius: shape.medium,
          elevation: CARD_ELEVATION,
        },
        style,
      ]}
    >
      {children}
    </PressableScale>
  );
}

/** A compact label for a status, count or part of speech. */
function ChipBase({
  label,
  background,
  color,
  outlined,
  textStyle = typeScale.labelSmall,
}: {
  label: string;
  background: string;
  color: string;
  outlined?: boolean;
  textStyle?: TextStyle;
}) {
  return (
    <View
      style={[
        {
          backgroundColor: background,
          borderRadius: shape.small,
          paddingHorizontal: spacing.m,
          paddingVertical: spacing.xs,
        },
        outlined ? {borderWidth: 1, borderColor: color} : null,
      ]}
    >
      <Text style={[textStyle, {color}]}>{label}</Text>
    </View>
  );
}

/**
 * A neutral icon tile for a row. Callers may supply semantic status colors for meaningful states.
 */
export function IconBadge({
  name,
  size = 44,
  background,
  color,
  iconSize,
}: {
  name: IconName;
  size?: number;
  background?: string;
  color?: string;
  iconSize?: number;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        width: size,
        height: size,
        borderRadius: shape.brandTile,
        backgroundColor: background ?? colors.surfaceVariant,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <Icon
        name={name}
        size={iconSize ?? Math.round(size * 0.5)}
        color={color ?? colors.iconSecondary}
      />
    </View>
  );
}

/**
 * A supporting label over related rows, using the same text rhythm as other metadata.
 */
export function SectionLabel({
  children,
  style,
}: {
  children: string;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Text
      style={[
        typeScale.labelSmall,
        {
          color: colors.onSurfaceVariant,
        },
        style,
      ]}
    >
      {children}
    </Text>
  );
}

/** The "common" marker beside a headword. */
export function CommonChip({label = "common"}: {label?: string}) {
  const {colors} = useTheme();
  return (
    <ChipBase
      label={label}
      background={colors.primaryContainer}
      color={colors.onPrimaryContainer}
    />
  );
}

/** A count or short figure the screens float beside a heading. */
export function CountChip({
  value,
  accessibilityLabel,
}: {
  value: string | number;
  accessibilityLabel?: string;
}) {
  const {colors} = useTheme();
  return (
    <View
      accessible
      accessibilityLabel={accessibilityLabel}
      style={{
        backgroundColor: colors.primaryContainer,
        borderRadius: shape.small,
        paddingHorizontal: spacing.s,
        paddingVertical: 2,
      }}
    >
      <Text style={[typeScale.labelSmall, {color: colors.onPrimaryContainer}]}>
        {String(value)}
      </Text>
    </View>
  );
}

/** A part-of-speech chip: a hairline outline on the surface colour, `labelMedium` text. */
export function OutlinedChip({label}: {label: string}) {
  const {colors} = useTheme();
  return (
    <ChipBase
      label={label}
      background={colors.surface}
      color={colors.onSurfaceVariant}
      textStyle={typeScale.labelMedium}
      outlined
    />
  );
}

/**
 * Shared action control: ink-filled primary, neutral secondary, outlined or text action.
 * Minimum heights and vertical padding allow labels to grow with the reader's font size.
 */
export function Button({
  label,
  onPress,
  disabled = false,
  variant = "filled",
  fullWidth = false,
  leading = null,
  accessibilityLabel,
  style,
}: {
  label?: string;
  onPress: () => void;
  disabled?: boolean;
  variant?: "filled" | "tonal" | "outlined" | "text";
  fullWidth?: boolean;
  leading?: React.ReactNode;
  accessibilityLabel?: string;
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  const bare = variant === "text";
  const container: ViewStyle = {
    minHeight: bare ? 48 : 52,
    borderRadius: shape.button,
    paddingVertical: bare ? spacing.s : spacing.m,
    paddingHorizontal: bare ? spacing.m : spacing.xl,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: spacing.s,
    alignSelf: fullWidth ? ("stretch" as const) : ("auto" as const),
    ...(variant === "filled"
      ? {backgroundColor: disabled ? colors.disabled : colors.actionPrimary}
      : variant === "tonal"
        ? {
            backgroundColor: disabled ? colors.disabled : colors.actionSecondary,
          }
        : variant === "outlined"
          ? {
              borderWidth: 1,
              borderColor: disabled ? colors.disabled : colors.outline,
            }
          : null),
  };
  const textColor = disabled
    ? colors.onDisabled
    : variant === "filled"
      ? colors.onActionPrimary
      : variant === "tonal"
        ? colors.onActionSecondary
        : variant === "outlined" ? colors.onSurface : colors.accent;
  return (
    <PressableScale
      onPress={onPress}
      disabled={disabled}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{disabled}}
      style={[container, style]}
    >
      {leading}
      {label != null && (
        <Text style={[typeScale.labelLarge, {color: textColor, flexShrink: 1, textAlign: "center"}]}>{label}</Text>
      )}
    </PressableScale>
  );
}

/**
 * A bare icon button: the given glyph at a 48dp (compact 36dp) touch target.
 *
 * [size] is the glyph's own dp, for the call sites whose Compose icon was not Material's 24: the
 * WaniKani panel's header draws its icons at 20 (18 while compact) inside the same target, as the
 * Kotlin's `WaniKaniDetail` does.
 */
export function IconButton({
  name,
  accessibilityLabel,
  onPress,
  compact = false,
  color,
  size = 24,
}: {
  name: IconName;
  accessibilityLabel: string;
  onPress: () => void;
  compact?: boolean;
  color?: string;
  size?: number;
}) {
  const {colors} = useTheme();
  const target = compact ? 36 : 48;
  return (
    <PressableScale
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      hitSlop={compact ? 6 : undefined}
      style={{
        width: target,
        height: target,
        borderRadius: target / 2,
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <Icon name={name} size={size} color={color ?? colors.iconSecondary} />
    </PressableScale>
  );
}

/** An informational card: icon, title, body, and an optional action button. */
export function Notice({
  title,
  body,
  icon = "info",
  action,
  style,
}: {
  title: string;
  body?: string;
  icon?: IconName;
  action?: {label: string; onPress: () => void};
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={[
        {
          backgroundColor: colors.surfaceVariant,
          borderRadius: shape.medium,
          padding: spacing.l,
          gap: spacing.s,
        },
        style,
      ]}
    >
      <View style={{flexDirection: "row", gap: spacing.m, alignItems: "flex-start"}}>
        <View style={{marginTop: 2}}>
          <Icon name={icon} color={colors.onSurfaceVariant} />
        </View>
        <View style={{flex: 1, gap: spacing.xs}}>
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
          {body != null && (
            <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>{body}</Text>
          )}
        </View>
      </View>
      {action != null && (
        <Button label={action.label} onPress={action.onPress} />
      )}
    </View>
  );
}

/**
 * The distinct ideographs of [word], in order: one entry per code point that is a Han character,
 * so 𠮷 survives and a kana-only word renders nothing.
 */
const HAN_CODE_POINT = /\p{Script=Han}/u;

export function distinctIdeographs(word: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const character of word) {
    if (!HAN_CODE_POINT.test(character) || seen.has(character)) {
      continue;
    }
    seen.add(character);
    out.push(character);
  }
  return out;
}

/**
 * The horizontally scrolling kanji chips under a word: one chip per ideograph of the headword.
 *
 * A tap plays the Tick haptic per [haptic]: "always" ticks on every press, "onOpen" only when the
 * tapped character is not the selected one (the review screen, where tapping the open kanji closes
 * it silently), and "none" never ticks — the mode a screen uses when the shell owns the Tick,
 * because only the shell knows whether a tap opens something new or closes it.
 *
 * The strip brings all of its own vertical spacing — 8 dp above the label and 4 dp below it — as the
 * Kotlin's does, so a caller puts nothing between it and what is above.
 */
export function KanjiStrip({
  characters,
  selected = null,
  onKanji,
  label = "WaniKani kanji",
  haptic = "always",
}: {
  characters: string[];
  selected?: string | null;
  onKanji: (character: string) => void;
  label?: string | null;
  haptic?: "always" | "onOpen" | "none";
}) {
  const {colors} = useTheme();
  if (characters.length === 0) {
    return null;
  }
  return (
    <View>
      {label != null && (
        <Text
          style={[
            typeScale.labelSmall,
            {
              color: colors.onSurfaceVariant,
              marginTop: spacing.s,
              marginBottom: spacing.xs,
            },
          ]}
        >
          {label}
        </Text>
      )}
      <ScrollView showsVerticalScrollIndicator={false} horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{gap: spacing.s}}>
        {characters.map((character) => (
          <KanjiChip
            key={character}
            character={character}
            selected={character === selected}
            onPress={() => {
              if (haptic === "always" || (haptic === "onOpen" && character !== selected)) {
                void GatenHaptics.play("tick").catch(() => {});
              }
              onKanji(character);
            }}
          />
        ))}
      </ScrollView>
    </View>
  );
}

/** How long the open chip's container colour takes to change: Kotlin's `Motion.SHORT`. */
const CHIP_MS = 150;

/** The check's own size, and the spring it lands on: Kotlin's `Motion.overshoot` (ζ 0.8, k 380). */
const CHECK_SIZE = 18;
const CHECK_SPRING = {mass: 1, stiffness: 380, damping: 31.19};

/**
 * One kanji chip: the character at the sheet's headword size in a pill that is exactly as wide as its
 * content, and the check that marks the open one. The check is drawn *only* while the chip is open
 * and takes no room of its own — it sits in front of the character, so a closed chip is narrower than
 * an open one — while the container eases between its two colours and the check lands on the
 * overshoot spring, both as the Kotlin's chip does.
 */
function KanjiChip({
  character,
  selected,
  onPress,
}: {
  character: string;
  selected: boolean;
  onPress: () => void;
}) {
  const {colors} = useTheme();
  const open = useSharedValue(selected ? 1 : 0);
  const check = useSharedValue(selected ? 1 : 0);

  useEffect(() => {
    open.value = withTiming(selected ? 1 : 0, {
      duration: CHIP_MS,
      easing: PRESS_EASING_REANIMATED,
    });
    check.value = withSpring(selected ? 1 : 0, CHECK_SPRING);
  }, [selected, open, check]);

  const containerStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      open.value,
      [0, 1],
      [colors.surfaceVariant, colors.primaryContainer],
    ),
  }));

  const checkStyle = useAnimatedStyle(() => ({
    transform: [{scale: check.value}],
  }));

  return (
    <PressableScale
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`WaniKani details for ${character}`}
      accessibilityState={{selected}}
    >
      <Animated.View
        style={[
          {
            flexDirection: "row",
            alignItems: "center",
            paddingHorizontal: spacing.m,
            paddingVertical: spacing.xs,
            borderRadius: shape.small,
            borderWidth: selected ? 1 : 0,
            borderColor: colors.primary,
          },
          containerStyle,
        ]}
      >
        {selected && (
          <Animated.View style={checkStyle}>
            <Icon name="check" size={CHECK_SIZE} color={colors.onPrimaryContainer} />
          </Animated.View>
        )}
        <Text
          style={[
            typeScale.displaySmall,
            {color: selected ? colors.onPrimaryContainer : colors.onSurface},
          ]}
        >
          {character}
        </Text>
      </Animated.View>
    </PressableScale>
  );
}
