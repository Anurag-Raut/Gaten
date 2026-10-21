/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The review screen's own primitives: the buttons, notes, badges and chips the review surface is
 * built from, drawn locally because they are this screen's visual language rather than the app's
 * shared kit.
 *
 * Controls use rounded rectangles, flat white surfaces, a small badge behind a state's glyph, one
 * dark primary action per surface and quiet, legible metadata. Every control is at least a 48 dp
 * touch target (Android's own minimum for interactive elements, developer.android.com
 * `guide/topics/ui/accessibility/apps`), and shapes come from the app's shared tokens.
 */
import React from "react";
import {ActivityIndicator, StyleSheet, Text, View} from "react-native";
import {Icon} from "../components";
import type {IconName} from "../icons";
import {PressableScale} from "../PressableScale";
import {shape, spacing, typeScale} from "../tokens";
import type {Theme} from "../theme";

/** The screen's dominant action: a dark fill with a high-contrast label. */
export function FilledAction({
  theme,
  label,
  onPress,
  disabled,
  minHeight = 52,
}: {
  theme: Theme;
  label: string;
  onPress: () => void;
  disabled?: boolean;
  minHeight?: number;
}) {
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled: disabled ?? false}}
      onPress={onPress}
      disabled={disabled}
      style={[
        styles.pill,
        {
          minHeight,
          width: "100%",
          backgroundColor: disabled ? theme.colors.disabled : theme.colors.actionPrimary,
        },
      ]}
    >
      <Text
        style={[
          typeScale.labelLarge,
          {
            color: disabled ? theme.colors.onDisabled : theme.colors.onActionPrimary,
          },
        ]}
      >
        {label}
      </Text>
    </PressableScale>
  );
}

/** One neutral secondary action beside the dark primary action. */
export function OutlinedAction({
  theme,
  label,
  onPress,
  disabled,
  minHeight = 52,
}: {
  theme: Theme;
  label: string;
  onPress: () => void;
  disabled?: boolean;
  minHeight?: number;
}) {
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled: disabled ?? false}}
      onPress={onPress}
      disabled={disabled}
      style={[
        styles.pill,
        {
          minHeight,
          width: "100%",
          backgroundColor: disabled ? theme.colors.disabled : theme.colors.actionSecondary,
        },
      ]}
    >
      <Text
        style={[
          typeScale.labelLarge,
          {
            color: disabled
              ? theme.colors.onDisabled
              : theme.colors.onActionSecondary,
          },
        ]}
      >
        {label}
      </Text>
    </PressableScale>
  );
}

/** One tonal action: the rating bar's category color, or a neutral disabled state. */
export function TonalAction({
  theme,
  content,
  onPress,
  disabled,
  container,
  minHeight = 64,
}: {
  theme: Theme;
  content: React.ReactNode;
  onPress: () => void;
  disabled?: boolean;
  /** Overrides the default tonal pair — the rating bar's "Again" takes the error tones. */
  container?: string;
  minHeight?: number;
}) {
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityState={{disabled: disabled ?? false}}
      onPress={onPress}
      disabled={disabled}
      style={[
        styles.pill,
        {
          minHeight,
          backgroundColor: disabled
            ? theme.colors.disabled
            : container ?? theme.colors.actionSecondary,
          // The bar's buttons are as wide as their share of the row, so their content padding is
          // the library's own 4dp rather than the roomier pill the full-width actions take.
          paddingHorizontal: spacing.xs,
        },
      ]}
    >
      {content}
    </PressableScale>
  );
}

/** One text-only action, for the moves beside a surface's main one. */
export function TextAction({
  theme,
  label,
  onPress,
  disabled,
}: {
  theme: Theme;
  label: string;
  onPress: () => void;
  disabled?: boolean;
}) {
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled: disabled ?? false}}
      onPress={onPress}
      disabled={disabled}
      style={[styles.pill, {minHeight: 48, paddingHorizontal: spacing.m}]}
    >
      <Text
        style={[
          typeScale.labelLarge,
          {color: disabled ? theme.colors.onDisabled : theme.colors.accent},
        ]}
      >
        {label}
      </Text>
    </PressableScale>
  );
}

/**
 * The badge a glyph sits in: a rounded square on a subtle neutral surface, so a state reads at a
 * glance before its text is read.
 */
export function IconBadge({
  theme,
  name,
  size = 44,
  onContainer,
}: {
  theme: Theme;
  name: IconName;
  size?: number;
  /** Whether the badge sits on a variant-filled card, which takes the page's own colour instead. */
  onContainer?: boolean;
}) {
  const glyph = Math.round(size * 0.5);
  return (
    <View
      style={[
        styles.badge,
        {
          width: size,
          height: size,
          backgroundColor: onContainer
            ? theme.colors.surface
            : theme.colors.surfaceVariant,
        },
      ]}
    >
      <Icon name={name} size={glyph} color={theme.colors.onSurfaceVariant} />
    </View>
  );
}

/**
 * A state with nothing to act on yet — loading, empty, failed — as one coherent surface: a badge,
 * a short hierarchy of title over body, and the surface's own recovery actions.
 */
export function StateCard({
  theme,
  icon,
  title,
  body,
  note,
  tone = "neutral",
  spin = false,
  children,
}: {
  theme: Theme;
  icon: IconName;
  title: string;
  body?: string;
  /** A second, smaller paragraph for what the state means rather than what it is. */
  note?: string;
  /** The only state that takes colour: something failed. */
  tone?: "neutral" | "error";
  /** A loading badge rather than a glyph one. */
  spin?: boolean;
  children?: React.ReactNode;
}) {
  const {colors} = theme;
  const failed = tone === "error";
  return (
    <View style={[styles.stateCard, {backgroundColor: colors.surface}]}>
      <View
        style={[
          styles.stateBadge,
          {
            backgroundColor: failed
              ? colors.errorContainer
              : colors.surfaceVariant,
          },
        ]}
      >
        {spin ? (
          <ActivityIndicator size="small" color={colors.accent} />
        ) : (
          <Icon
            name={icon}
            size={24}
            color={failed ? colors.onErrorContainer : colors.onSurfaceVariant}
          />
        )}
      </View>
      <Text style={[typeScale.titleSmall, styles.stateTitle, {color: colors.onSurface}]}>
        {title}
      </Text>
      {body != null && (
        <Text style={[typeScale.bodyMedium, styles.stateBody, {color: colors.onSurfaceVariant}]}>
          {body}
        </Text>
      )}
      {note != null && (
        <Text style={[typeScale.bodySmall, styles.stateNote, {color: colors.onSurfaceVariant}]}>
          {note}
        </Text>
      )}
      {children != null && <View style={styles.stateActions}>{children}</View>}
    </View>
  );
}

/** "The next card is on its way": spinner and message on a quiet card. */
export function BusyRow({theme, message}: {theme: Theme; message: string}) {
  return (
    <View
      style={[
        styles.rowCard,
        {backgroundColor: theme.colors.surfaceVariant, padding: spacing.m},
      ]}
    >
      <ActivityIndicator size="small" color={theme.colors.accent} />
      <Text
        style={[
          typeScale.bodyMedium,
          {
            color: theme.colors.onSurfaceVariant,
            marginStart: spacing.m,
            flexShrink: 1,
          },
        ]}
      >
        {message}
      </Text>
    </View>
  );
}

/** One of the screen's honest notes, with the action that answers it when there is one. */
export function NoteCard({
  theme,
  text,
  actionLabel,
  onAction,
}: {
  theme: Theme;
  text: string;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <View
      style={[
        styles.rowCard,
        {backgroundColor: theme.colors.surfaceVariant, padding: spacing.m},
      ]}
    >
      <IconBadge theme={theme} name="info" size={36} onContainer />
      <Text
        style={[
          typeScale.bodyMedium,
          {color: theme.colors.onSurfaceVariant, marginStart: spacing.m, flex: 1},
        ]}
      >
        {text}
      </Text>
      {actionLabel != null && onAction != null && (
        <View style={{marginStart: spacing.xs}}>
          <TextAction theme={theme} label={actionLabel} onPress={onAction} />
        </View>
      )}
    </View>
  );
}

/** The side of the card on screen: the accent for the answer, a quiet neutral for the question. */
export function CardSideTag({
  theme,
  revealed,
}: {
  theme: Theme;
  revealed: boolean;
}) {
  return (
    <View
      style={[
        styles.chip,
        {
          backgroundColor: revealed
            ? theme.colors.accentSubtle
            : theme.colors.surfaceVariant,
          alignSelf: "flex-start",
        },
      ]}
    >
      <Text
        style={[
          typeScale.labelMedium,
          {
            color: revealed
              ? theme.colors.accent
              : theme.colors.onSurfaceVariant,
          },
        ]}
      >
        {revealed ? "Answer" : "Question"}
      </Text>
    </View>
  );
}

/** One of AnkiDroid's own counts, as a tag on the deck's card. */
export function CountTag({theme, text}: {theme: Theme; text: string}) {
  return (
    <View
      style={[
        styles.chip,
        {backgroundColor: theme.colors.surface, alignSelf: "auto"},
      ]}
    >
      <Text style={[typeScale.labelMedium, {color: theme.colors.onSurfaceVariant}]}>
        {text}
      </Text>
    </View>
  );
}

/** One rating button: the name to show, and the ease Anki expects for it. */
export interface Rating {
  name: string;
  ease: number;
}

/**
 * The buttons a deck actually uses, from AnkiDroid's own button count: all four when it reports
 * four, Again/Good/Easy when it reports three, Again/Good when it reports two. The ease is
 * positional — button `i` is ease `i + 1` — because that is how AnkiDroid computed the interval
 * labels in the same answer.
 */
export function ratingsFor(buttonCount: number): Rating[] {
  const again: Rating = {name: "Again", ease: 1};
  const good: Rating = {name: "Good", ease: 2};
  const easy: Rating = {name: "Easy", ease: 3};
  switch (Math.min(4, Math.max(2, buttonCount))) {
    case 2:
      return [again, good];
    case 3:
      return [again, good, easy];
    default:
      // Four buttons: the positional eases run 1..4 — Hard sits at position 2.
      return [
        again,
        {name: "Hard", ease: 2},
        {name: "Good", ease: 3},
        {name: "Easy", ease: 4},
      ];
  }
}

/**
 * The deck's ratings. The label over each button is the interval the scheduler itself reported
 * for that rating's position, so a tap sends back exactly the rating its interval was computed
 * for. "Again" takes the error tones; the rest the tonal pair the theme points at the accent's
 * container. Each button is a floor rather than a fixed height, and its two lines may wrap.
 */
export function RatingBar({
  theme,
  labels,
  enabled,
  onGrade,
}: {
  theme: Theme;
  labels: string[];
  enabled: boolean;
  onGrade: (ease: number) => void;
}) {
  const ratings = ratingsFor(labels.length);
  return (
    <View style={{flexDirection: "row", width: "100%"}}>
      {ratings.map((rating, index) => {
        const isAgain = rating.ease === 1;
        const color = !enabled
          ? theme.colors.onDisabled
          : isAgain
            ? theme.colors.onErrorContainer
            : theme.colors.onSecondaryContainer;
        return (
          <View
            key={rating.ease}
            style={{flex: 1, marginStart: index > 0 ? spacing.s : 0}}
          >
            <TonalAction
              theme={theme}
              disabled={!enabled}
              minHeight={64}
              container={isAgain ? theme.colors.errorContainer : undefined}
              content={
                <View style={{alignItems: "center", paddingVertical: spacing.s}}>
                  <Text
                    style={[typeScale.labelLarge, {color}]}
                    numberOfLines={2}
                    ellipsizeMode="tail"
                  >
                    {rating.name}
                  </Text>
                  <Text
                    style={[typeScale.labelSmall, {color, opacity: 0.8}]}
                    numberOfLines={2}
                    ellipsizeMode="tail"
                  >
                    {labels[rating.ease - 1] ?? ""}
                  </Text>
                </View>
              }
              onPress={() => onGrade(rating.ease)}
            />
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  pill: {
    borderRadius: shape.button,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: spacing.xl,
  },
  rowCard: {
    borderRadius: shape.medium,
    flexDirection: "row",
    alignItems: "center",
    width: "100%",
  },
  badge: {
    borderRadius: shape.small,
    alignItems: "center",
    justifyContent: "center",
  },
  chip: {
    borderRadius: shape.small,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.xs,
  },
  stateCard: {
    borderRadius: shape.medium,
    padding: spacing.xl,
    width: "100%",
    alignItems: "center",
  },
  stateBadge: {
    width: 56,
    height: 56,
    borderRadius: shape.medium,
    alignItems: "center",
    justifyContent: "center",
  },
  stateTitle: {marginTop: spacing.l, textAlign: "center"},
  stateBody: {marginTop: spacing.s, textAlign: "center"},
  stateNote: {marginTop: spacing.m, textAlign: "center"},
  stateActions: {marginTop: spacing.xl, width: "100%", gap: spacing.m},
});
