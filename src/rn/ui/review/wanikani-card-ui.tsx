/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani review and lesson cards' own identity chrome: the subject-type badge that keeps a
 * vocabulary, radical or kanji recognisable before and after an answer, and the task pill that says
 * whether a meaning or a reading is being asked for.
 *
 * These are WaniKani's own cue colours, held apart from the reader's accent and from the correctness
 * colours, so a subject keeps its type even when it is answered wrongly. The values are the approved
 * design's, one set per theme, and live here so the review and the lesson cannot drift apart.
 */
import React from "react";
import {StyleSheet, Text, TextInput, View} from "react-native";
import {spacing} from "../tokens";
import {useTheme} from "../theme";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {WANIKANI_TYPE_LABELS} from "./wanikani-labels";
import type {WaniKaniLessonItem} from "../../native/GatenWaniKani";
import type {WaniKaniNoteState} from "./review-store";

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
  meaning: IdentityColor;
  reading: IdentityColor;
}

const LIGHT: IdentityPalette = {
  vocabulary: {ink: "#8739D7", soft: "#F5ECFF"},
  kanji: {ink: "#DD2388", soft: "#FCEAF4"},
  radical: {ink: "#1688D0", soft: "#E9F4FD"},
  meaning: {ink: "#27777B", soft: "#F4F7F8"},
  reading: {ink: "#4338CA", soft: "#F4F3FF"},
};

const DARK: IdentityPalette = {
  vocabulary: {ink: "#CB9DF7", soft: "#342241"},
  kanji: {ink: "#F28CBC", soft: "#3A2634"},
  radical: {ink: "#84C9F4", soft: "#1E3444"},
  meaning: {ink: "#95D5D7", soft: "#233135"},
  reading: {ink: "#B8B5FF", soft: "#292842"},
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

/** The meaning or reading the card is asking for, under the glyph. */
export function TaskPill({task}: {task: "meaning" | "reading"}) {
  const {dark} = useTheme();
  const color = (dark ? DARK : LIGHT)[task];
  return (
    <View style={[styles.pill, {backgroundColor: color.soft}]}>
      <View style={[styles.dot, {backgroundColor: color.ink}]} />
      <Text style={[styles.badgeLabel, {color: color.ink}]}>
        {task === "reading" ? "Reading" : "Meaning"}
      </Text>
    </View>
  );
}

/**
 * The reader's own note for a subject: collapsed to an Add/Edit row until opened, then two optional
 * fields written back to WaniKani on Save. The same editor serves the review's answer details and the
 * lesson's teaching card; the store owns the read and the write, so neither screen talks to the API.
 */
export function NoteEditor({
  note,
  onToggle,
  onEntry,
  onSave,
}: {
  note: WaniKaniNoteState;
  onToggle: () => void;
  onEntry: (field: "meaning" | "reading", text: string) => void;
  onSave: () => void;
}) {
  const {colors} = useTheme();
  // A save may only start from a note that was successfully read; drafts are frozen while it is in
  // flight so a late keystroke cannot be lost when the server's copy replaces them.
  const saveDisabled = note.saving || note.status !== "ready";
  if (!note.open) {
    return (
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel={note.material == null ? "Add note" : "Edit note"}
        onPress={onToggle}
        style={styles.noteRow}
      >
        <Icon name="add" size={20} color={colors.onSurfaceVariant} />
        <Text style={[styles.noteRowLabel, {color: colors.onSurface}]}>
          {note.material == null ? "Add note" : "Edit note"}
        </Text>
      </PressableScale>
    );
  }
  if (note.status === "idle" || note.status === "loading") {
    return <Text style={[styles.noteStatus, {color: colors.onSurfaceVariant}]}>Loading note…</Text>;
  }
  return (
    <View style={styles.noteEditor}>
      <Text style={[styles.noteLabel, {color: colors.onSurfaceVariant}]}>Meaning note</Text>
      <TextInput
        value={note.meaningDraft}
        onChangeText={(text) => onEntry("meaning", text)}
        placeholder="A note for the meaning"
        placeholderTextColor={colors.onSurfaceVariant}
        editable={!note.saving}
        multiline
        style={[styles.noteInput, {color: colors.onSurface, borderColor: colors.outlineVariant}]}
      />
      <Text style={[styles.noteLabel, styles.noteLabelSpaced, {color: colors.onSurfaceVariant}]}>
        Reading note
      </Text>
      <TextInput
        value={note.readingDraft}
        onChangeText={(text) => onEntry("reading", text)}
        placeholder="A note for the reading"
        placeholderTextColor={colors.onSurfaceVariant}
        editable={!note.saving}
        multiline
        style={[styles.noteInput, {color: colors.onSurface, borderColor: colors.outlineVariant}]}
      />
      {note.status === "failed" && (
        <Text style={[styles.noteStatus, {color: colors.error}]}>
          The saved note could not be read.
        </Text>
      )}
      {note.saveFailed && (
        <Text style={[styles.noteStatus, {color: colors.error}]}>
          The note was not saved. Check your connection and try again.
        </Text>
      )}
      <View style={styles.noteActions}>
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel="Cancel note editing"
          onPress={onToggle}
          style={styles.noteCancel}
        >
          <Text style={[styles.noteActionLabel, {color: colors.onSurfaceVariant}]}>Cancel</Text>
        </PressableScale>
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel="Save note"
          accessibilityState={{disabled: saveDisabled}}
          disabled={saveDisabled}
          onPress={onSave}
          style={[styles.noteSave, {backgroundColor: colors.actionPrimary, opacity: saveDisabled ? 0.6 : 1}]}
        >
          <Text style={[styles.noteActionLabel, {color: colors.onActionPrimary}]}>
            {note.saving ? "Saving…" : "Save note"}
          </Text>
        </PressableScale>
      </View>
    </View>
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
  pill: {
    flexDirection: "row",
    alignItems: "center",
    alignSelf: "center",
    borderRadius: 999,
    paddingVertical: 8,
    paddingHorizontal: spacing.m,
  },
  dot: {width: 7, height: 7, borderRadius: 4, marginEnd: spacing.s},
  badgeLabel: {fontSize: 12, lineHeight: 16, fontWeight: "700"},
  level: {fontSize: 12, lineHeight: 16},
  noteRow: {flexDirection: "row", alignItems: "center", minHeight: 49, gap: spacing.s},
  noteRowLabel: {fontSize: 14, lineHeight: 20},
  noteEditor: {paddingTop: spacing.l, paddingBottom: spacing.l},
  noteLabel: {fontSize: 12, lineHeight: 16, fontWeight: "700"},
  noteLabelSpaced: {marginTop: spacing.l},
  noteInput: {
    fontSize: 14,
    lineHeight: 20,
    minHeight: 48,
    marginTop: spacing.xs,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.s,
    borderWidth: 1,
    borderRadius: 12,
    textAlignVertical: "top",
  },
  noteStatus: {fontSize: 12, lineHeight: 18, marginTop: spacing.s},
  noteActions: {flexDirection: "row", justifyContent: "flex-end", gap: spacing.m, marginTop: spacing.m},
  noteCancel: {minHeight: 44, justifyContent: "center", paddingHorizontal: spacing.m},
  noteSave: {
    minHeight: 44,
    justifyContent: "center",
    paddingHorizontal: spacing.l,
    borderRadius: 12,
  },
  noteActionLabel: {fontSize: 14, lineHeight: 20, fontWeight: "700"},
});
