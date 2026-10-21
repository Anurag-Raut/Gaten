/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The state card the dictionary screens share: what happened, and — when something can be done — the
 * one action. The search field, the result rows and the example chips live in `searchParts`; this
 * file is only the notice, because Home, the word detail and the reference pages all raise the same
 * one and a second copy of it would drift.
 */
import React from "react";
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {Button, Icon} from "../components";
import type {IconName} from "../icons";
import {useTheme} from "../theme";
import {shape, spacing, typeScale} from "../tokens";

/**
 * A state with no results to show: what happened, and — when something can be done — the one
 * action. One raised surface with a badge behind its glyph and a short hierarchy over it, the same
 * shape the review's states draw, shared between the search surfaces and the word detail.
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
  /** A working badge rather than a glyph one. */
  spin?: boolean;
}) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  return (
    <ScrollView
      style={styles.noticeScroll}
      contentContainerStyle={[
        styles.notice,
        {padding: width < 360 ? spacing.l : spacing.contentInset},
      ]}
    >
      <View style={[styles.noticeCard, {backgroundColor: colors.surface}]}>
        <View style={[styles.noticeBadge, {backgroundColor: colors.surfaceVariant}]}>
          {spin ? (
            <ActivityIndicator size="small" color={colors.accent} />
          ) : (
            <Icon name={icon} size={24} color={colors.onSurfaceVariant} />
          )}
        </View>
        <Text style={[typeScale.titleLarge, styles.noticeText, {color: colors.onSurface}]}>
          {title}
        </Text>
        {body != null && (
          <Text
            style={[
              typeScale.bodyMedium,
              styles.noticeText,
              styles.noticeBody,
              {color: colors.onSurfaceVariant},
            ]}
          >
            {body}
          </Text>
        )}
        {action != null && (
          <View style={styles.noticeAction}>
            <Button fullWidth label={action.label} onPress={action.onPress} />
          </View>
        )}
      </View>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  noticeScroll: {flexGrow: 1},
  notice: {
    flexGrow: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: spacing.contentInset,
  },
  noticeCard: {
    borderRadius: shape.medium,
    padding: spacing.xl,
    width: "100%",
    maxWidth: 420,
    alignItems: "center",
  },
  noticeBadge: {
    width: 56,
    height: 56,
    borderRadius: shape.medium,
    alignItems: "center",
    justifyContent: "center",
  },
  noticeText: {marginTop: spacing.l, textAlign: "center"},
  noticeBody: {marginTop: spacing.s},
  noticeAction: {marginTop: spacing.xl, width: "100%"},
});
