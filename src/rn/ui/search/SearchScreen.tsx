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
import {StyleSheet, Text, View} from "react-native";
import {Button, Icon} from "../components";
import type {IconName} from "../icons";
import {BusySpinner} from "../review/review-ui";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";

/**
 * What every search's loading notice says. Three ASCII periods, not U+2026: IBM Plex Sans JP sets
 * the single ellipsis glyph a third of an em above the baseline, so the dots float above the line
 * the text sits on, while three periods sit on it. It lives here beside the notice the three screens
 * raise, so Home, the word detail and the lookup sheet cannot drift apart on it.
 */
export const SEARCHING_NOTICE_TITLE = "Searching the dictionary...";

/**
 * A state with no results to show: what happened, and — when something can be done — the action.
 * It is one flat row: a glyph column, then the title and body, then the outlined action under them.
 * It draws no card, no inset and no scroll — the list or the sheet around it owns those — so it can
 * never box a card inside a card.
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
    <View style={styles.notice}>
      <View style={styles.noticeRow}>
        <View style={styles.noticeGlyph}>
          {spin ? (
            // 20 dp inside the 24 dp column: the ring is the drawing kit's own, so it stays a ring
            // when the system animator duration scale is 0, where `ActivityIndicator` does not.
            <BusySpinner size={20} />
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
          variant="outlined"
          label={action.label}
          onPress={action.onPress}
          style={styles.noticeAction}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  // One flat row of prose and its action, never a card: a box around the action would be a box
  // inside whatever the notice stands on.
  notice: {
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
