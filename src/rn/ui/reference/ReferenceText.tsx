/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The shared text language of a reference page: a titled section, the violet value it states, the
 * prose that explains it, an example sentence, and the two shapes a related item takes — a tile for
 * a component and a word for the vocabulary an ideograph appears in.
 *
 * The lesson page's teaching card is the approved reference this copies, so the WaniKani and RTK
 * panels, the lesson and the Anki answer all state a meaning the same way. The roles come from
 * `typeScale`, never from numbers here.
 */
import React from "react";
import {Text, View, type StyleProp, type TextStyle} from "react-native";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {KANJI_LETTERING, shape, spacing, typeScale} from "../tokens";

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
  const {colors} = useTheme();
  return (
    <View style={{marginTop: topGap}}>
      <Text
        accessibilityRole="header"
        style={[
          typeScale.labelMedium,
          {color: colors.onSurfaceVariant, marginBottom: spacing.s},
        ]}
      >
        {title}
      </Text>
      {children}
    </View>
  );
}

/** The value a section states — a meaning or a reading — in the one violet every surface uses. */
export function ReferenceValue({
  children,
  style,
}: {
  children: React.ReactNode;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Text style={[typeScale.valueLarge, {color: colors.reviewReading}, style]}>{children}</Text>
  );
}

/** The prose under a value: a mnemonic, a story, WaniKani's explanation. */
export function ReferenceBody({
  children,
  selectable,
}: {
  children: React.ReactNode;
  selectable?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <Text
      selectable={selectable}
      style={[
        typeScale.bodyMedium,
        {color: colors.onSurfaceVariant, marginTop: spacing.m},
      ]}
    >
      {children}
    </Text>
  );
}

/** One example sentence: the Japanese, then its English under it. */
export function ReferenceExample({japanese, english}: {japanese: string; english: string}) {
  const {colors} = useTheme();
  return (
    <View style={{marginTop: spacing.m}}>
      <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{japanese}</Text>
      <Text
        style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: 2}]}
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
  const {colors} = useTheme();
  const content = (
    <>
      {glyph != null && (
        <Text style={[typeScale.displaySmall, KANJI_LETTERING, {color: colors.onSurface}]}>
          {glyph}
        </Text>
      )}
      {name != null && name !== "" && (
        <Text style={[typeScale.bodyMedium, {color: colors.onSurface, flexShrink: 1}]}>{name}</Text>
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
  const {colors} = useTheme();
  return (
    <View style={{marginTop: spacing.m}}>
      <Text style={[typeScale.valueLarge, KANJI_LETTERING, {color: colors.onSurface}]}>
        {characters}
        {reading != null && reading !== "" && (
          <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
            {"  " + reading}
          </Text>
        )}
      </Text>
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>{meaning}</Text>
    </View>
  );
}
