/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The shared text language of a reference page: a titled section, the accent-coloured value it states, the
 * prose that explains it, an example sentence, and the two shapes a related item takes — a tile for
 * a component and a word for the vocabulary an ideograph appears in.
 *
 * The lesson page's teaching card is the approved reference this copies, so the WaniKani and RTK
 * panels, the lesson and the Anki answer all state a meaning the same way. The roles come from
 * `typeScale`, never from numbers here.
 */
import React from "react";
import { StyleSheet, Text, View, type StyleProp, type TextStyle } from "react-native";
import { PressableScale } from "../PressableScale";
import { useTheme } from "../theme";
import { parseMnemonic, type MnemonicKind } from "./mnemonic-markup";
import { KANJI_LETTERING, shape, spacing, typeScale } from "../tokens";

/** A titled block: the caption and whatever it introduces, spaced from what is above it. */
export function ReferenceSection({
  title,
  children,
  topGap = spacing.xl,
}: {
  title: string;
  children: React.ReactNode;
  topGap?: number;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ marginTop: topGap }}>
      <Text
        accessibilityRole="header"
        style={[
          typeScale.labelMedium,
          { color: colors.onSurfaceVariant, marginBottom: spacing.s },
        ]}
      >
        {title}
      </Text>
      {children}
    </View>
  );
}

/** The value a section states — a meaning or a reading — in the reader's accent colour, as every surface does. */
export function ReferenceValue({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: StyleProp<TextStyle>;
}) {
  const { colors } = useTheme();
  return (
    <Text style={[typeScale.valueLarge, { color: colors.accent }, style]}>
      {children}
    </Text>
  );
}

/**
 * WaniKani's highlight colours, as its own site shows them: radicals blue, kanji pink, vocabulary
 * purple, readings a quiet grey chip. Light mode uses the deeper tone so the word stays legible.
 */
const HIGHLIGHT: Record<MnemonicKind, { dark: string; light: string } | null> = {
  radical: { dark: "#4CC3FF", light: "#0077B8" },
  kanji: { dark: "#FF5CC8", light: "#C2007F" },
  vocabulary: { dark: "#C77DFF", light: "#8A1FD6" },
  reading: { dark: "#E6E6EA", light: "#3A3A42" },
  ja: null,
};

/**
 * Mnemonic prose with the words WaniKani highlights set in a small rounded chip of the same hue on a
 * soft tint. A nested Text span cannot take padding or corners on Android, so each highlight is an
 * inline View carrying its own Text in the paragraph's lettering. Plain text with no markers renders
 * as ordinary text.
 */
export function MnemonicText({
  children,
  style,
  selectable,
}: {
  children: string;
  style?: StyleProp<TextStyle>;
  selectable?: boolean;
}) {
  const { dark, colors } = useTheme();
  // The chip's Text does not inherit from the paragraph (it sits inside a View), so it is handed the
  // paragraph's lettering explicitly, without the paragraph's own margins.
  const {
    margin, marginTop, marginBottom, marginVertical, marginHorizontal, marginLeft, marginRight,
    ...lettering
  } = StyleSheet.flatten(style) ?? {};
  return (
    <Text
      selectable={selectable}
      selectionColor={selectable ? colors.selection : undefined}
      style={style}
    >
      {parseMnemonic(children).map((piece, index) => {
        const tone = piece.kind == null ? null : HIGHLIGHT[piece.kind];
        if (tone == null) return piece.text;
        const color = dark ? tone.dark : tone.light;
        return (
          <View
            key={index}
            style={{
              backgroundColor: color + "24",
              borderRadius: MNEMONIC_CHIP_RADIUS,
              paddingHorizontal: 5,
              transform: [{ translateY: MNEMONIC_CHIP_NUDGE }],
            }}
          >
            <Text style={[lettering, { color }]}>{piece.text}</Text>
          </View>
        );
      })}
    </Text>
  );
}

/** Android sets an inline view's bottom on the text baseline; this drops it back onto the line box. */
const MNEMONIC_CHIP_NUDGE = 5;

/** A highlight chip's corners: rounder than a hard box, well short of a pill. */
const MNEMONIC_CHIP_RADIUS = 6;

/** The prose under a value: a mnemonic, a story, WaniKani's explanation. */
export function ReferenceBody({
  children,
  selectable,
}: {
  children: React.ReactNode;
  selectable?: boolean;
}) {
  const { colors } = useTheme();
  const style = [
    typeScale.bodyMedium,
    { color: colors.onSurfaceVariant, marginTop: spacing.m },
  ];
  if (typeof children === "string") {
    return (
      <MnemonicText selectable={selectable} style={style}>
        {children}
      </MnemonicText>
    );
  }
  return (
    <Text
      selectable={selectable}
      selectionColor={selectable ? colors.selection : undefined}
      style={style}
    >
      {children}
    </Text>
  );
}

/** One example sentence: the Japanese, then its English under it. */
export function ReferenceExample({
  japanese,
  english,
}: {
  japanese: string;
  english: string;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ marginTop: spacing.m }}>
      <Text style={[typeScale.bodyMedium, { color: colors.onSurface }]}>
        {japanese}
      </Text>
      <Text
        style={[
          typeScale.bodySmall,
          { color: colors.onSurfaceVariant, marginTop: 2 },
        ]}
      >
        {english}
      </Text>
    </View>
  );
}

/**
 * A related item in a vertical list: its glyph and its name on one row, a control when there is
 * somewhere to go. The glyph is the kanji lettering every other glyph in the app wears.
 */
export function ReferenceTile({
  glyph,
  name,
  onPress,
  accessibilityLabel,
}: {
  glyph?: string | null;
  name?: string | null;
  onPress?: () => void;
  accessibilityLabel?: string;
}) {
  const { colors } = useTheme();
  const content = (
    <>
      {glyph != null && (
        <Text
          style={[
            typeScale.displaySmall,
            KANJI_LETTERING,
            { color: colors.onSurface },
          ]}
        >
          {glyph}
        </Text>
      )}
      {name != null && name !== "" && (
        <Text
          style={[
            typeScale.bodyMedium,
            { color: colors.onSurface, flexShrink: 1 },
          ]}
        >
          {name}
        </Text>
      )}
    </>
  );
  const style = {
    minHeight: 48,
    flexDirection: "row" as const,
    alignItems: "center" as const,
    gap: spacing.m,
    paddingHorizontal: spacing.m,
    borderRadius: shape.small,
    backgroundColor: colors.surfaceVariant,
    alignSelf: "stretch" as const,
  };
  return onPress == null ? (
    <View style={style}>{content}</View>
  ) : (
    <PressableScale
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      style={style}
    >
      {content}
    </PressableScale>
  );
}

/** A word a kanji is used in: the characters, their reading, and the meaning under them. */
export function ReferenceWord({
  characters,
  reading,
  meaning,
}: {
  characters: string;
  reading?: string | null;
  meaning: string;
}) {
  const { colors } = useTheme();
  return (
    <View style={{ marginTop: spacing.m }}>
      <Text
        style={[
          typeScale.valueLarge,
          KANJI_LETTERING,
          { color: colors.onSurface },
        ]}
      >
        {characters}
        {reading != null && reading !== "" && (
          <Text
            style={[typeScale.bodyMedium, { color: colors.onSurfaceVariant }]}
          >
            {"  " + reading}
          </Text>
        )}
      </Text>
      <Text style={[typeScale.bodyMedium, { color: colors.onSurfaceVariant }]}>
        {meaning}
      </Text>
    </View>
  );
}
