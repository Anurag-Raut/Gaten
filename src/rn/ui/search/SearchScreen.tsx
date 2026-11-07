/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The state notice the dictionary screens share: what happened and — when something can be done —
 * the one action. The search field, the result rows and the example chips live in `searchParts`;
 * this file is only the notice, because Home, the word detail and the reference pages all raise the
 * same one and a second copy of it would drift.
 */
import React from "react";
import {ActivityIndicator, StyleSheet, Text, View} from "react-native";
import {Button, Icon} from "../components";
import type {IconName} from "../icons";
import {useTheme} from "../theme";
import {shape, spacing, typeScale} from "../tokens";

/**
 * A state with no results to show: what happened, and — when something can be done — the action.
 * It is one flat row on the list's own fill and rounding, so it occupies exactly the box a result
 * card does: a glyph column, then the title and body, then the action under them. It draws no inset
 * and does not scroll — the list around it owns both — so it can never box a card inside a card.
 */
export function SearchNotice({
  title,
  body,
  action,
  icon = "info",
  spin = false,
}: {
  title: string;
  body?: string;
  action?: {label: string; onPress: () => void};
  icon?: IconName;
  /** A working spinner rather than a glyph. */
  spin?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.notice, {backgroundColor: colors.surfaceVariant}]}>
      <View style={styles.noticeRow}>
        <View style={styles.noticeGlyph}>
          {spin ? (
            <ActivityIndicator size="small" color={colors.accent} />
          ) : (
            <Icon name={icon} size={24} color={colors.onSurfaceVariant} />
          )}
        </View>
        <View style={styles.noticeText}>
          <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>{title}</Text>
          {body != null && (
            <Text
              style={[typeScale.bodyMedium, styles.noticeBody, {color: colors.onSurfaceVariant}]}
            >
              {body}
            </Text>
          )}
        </View>
      </View>
      {action != null && (
        <Button
          fullWidth
          label={action.label}
          onPress={action.onPress}
          style={styles.noticeAction}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  notice: {
    borderRadius: shape.small,
    paddingVertical: spacing.m,
    paddingHorizontal: spacing.l,
    width: "100%",
  },
  noticeRow: {flexDirection: "row", alignItems: "flex-start", gap: spacing.m},
  // The glyph column is the same 24 dp whether it holds a glyph or the spinner, so the text beside
  // it does not shift when a search starts.
  noticeGlyph: {width: 24, alignItems: "center"},
  noticeText: {flex: 1, minWidth: 0},
  noticeBody: {marginTop: spacing.s},
  noticeAction: {marginTop: spacing.m},
});
