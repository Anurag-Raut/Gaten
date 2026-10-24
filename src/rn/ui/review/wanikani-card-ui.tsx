/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani review and lesson cards' own identity chrome: the subject-type badge that keeps a
 * vocabulary, radical or kanji recognisable before and after an answer, and the task label that says
 * whether a meaning or a reading is being asked for.
 *
 * These are WaniKani's own cue colours, held apart from the reader's accent and from the correctness
 * colours, so a subject keeps its type even when it is answered wrongly. The values are the approved
 * design's, one set per theme, and live here so the review and the lesson cannot drift apart.
 */
import React from "react";
import {StyleSheet, Text, View} from "react-native";
import {spacing} from "../tokens";
import {useTheme} from "../theme";
import {WANIKANI_TYPE_LABELS} from "./wanikani-labels";
import type {WaniKaniLessonItem} from "../../native/GatenWaniKani";

type SubjectType = WaniKaniLessonItem["type"];

interface IdentityColor {
  /** The dot and label ink on the badge or pill. */
  ink: string;
  /** The badge or pill's own soft fill. */
  soft: string;
}

interface IdentityPalette {
  vocabulary: IdentityColor;
  kanji: IdentityColor;
  radical: IdentityColor;
}

const LIGHT: IdentityPalette = {
  vocabulary: {ink: "#8739D7", soft: "#F5ECFF"},
  kanji: {ink: "#DD2388", soft: "#FCEAF4"},
  radical: {ink: "#1688D0", soft: "#E9F4FD"},
};

const DARK: IdentityPalette = {
  vocabulary: {ink: "#CB9DF7", soft: "#342241"},
  kanji: {ink: "#F28CBC", soft: "#3A2634"},
  radical: {ink: "#84C9F4", soft: "#1E3444"},
};

/** A kana vocabulary wears the vocabulary colour; every other kind maps to its own. */
function subjectColor(palette: IdentityPalette, type: SubjectType): IdentityColor {
  return type === "kanji"
    ? palette.kanji
    : type === "radical"
      ? palette.radical
      : palette.vocabulary;
}

/**
 * The subject's type and its level: the coloured badge at the leading edge, the level at the trailing
 * one. The type is never replaced by a correctness colour, so it stays readable after an answer.
 */
export function SubjectBadge({type, level}: {type: SubjectType; level: number}) {
  const {colors, dark} = useTheme();
  const color = subjectColor(dark ? DARK : LIGHT, type);
  return (
    <View style={styles.badgeRow}>
      <View style={[styles.badge, {backgroundColor: color.soft}]}>
        <View style={[styles.dot, {backgroundColor: color.ink}]} />
        <Text style={[styles.badgeLabel, {color: color.ink}]}>{WANIKANI_TYPE_LABELS[type]}</Text>
      </View>
      {level > 0 && <Text style={[styles.level, {color: colors.onSurfaceVariant}]}>Level {level}</Text>}
    </View>
  );
}

/** The meaning or reading the card is asking for, under the glyph, as plain text. */
export function TaskLabel({task}: {task: "meaning" | "reading"}) {
  const {colors} = useTheme();
  return (
    <Text style={[styles.taskLabel, {color: colors.onSurface}]}>
      {task === "reading" ? "Reading" : "Meaning"}
    </Text>
  );
}

const styles = StyleSheet.create({
  badgeRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    width: "100%",
  },
  badge: {
    flexDirection: "row",
    alignItems: "center",
    borderRadius: 999,
    paddingVertical: 8,
    paddingHorizontal: spacing.m,
  },
  dot: {width: 7, height: 7, borderRadius: 4, marginEnd: spacing.s},
  badgeLabel: {fontSize: 12, lineHeight: 16, fontWeight: "700"},
  level: {fontSize: 12, lineHeight: 16},
  taskLabel: {fontSize: 14, lineHeight: 20},
});
