/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani hub: the two things the account can do, on one page. Reviews is what is due now; New
 * cards is what WaniKani has waiting to be learned. Each section shows its own count and the one
 * action that starts it, or the state that says there is nothing behind it.
 *
 * The counts come from `GET /summary` - read by the store before this screen is drawn - and are only
 * an indicator: tapping either action reads the real queue, which is where a stale count becomes a
 * specific answer rather than a wrong one.
 */
import React from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {BusyRow, FilledAction, SubHeader, nextReviewLabel} from "./review-ui";
import type {WaniKaniSummary} from "./review-store";

/** One half of the page: a caption, either its count and action or a line saying there is nothing. */
function HubSection({
  title,
  count,
  countLabel,
  emptyText,
  actionLabel,
  busy,
  onAction,
  first = false,
}: {
  title: string;
  count: number;
  /** What the count counts, under it: `reviews are due`, `new cards are waiting`. */
  countLabel: string;
  /** What to say instead when the count is zero. */
  emptyText: string;
  actionLabel: string;
  busy: boolean;
  onAction: () => void;
  first?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={[
        styles.section,
        first ? null : {borderTopWidth: 1, borderTopColor: colors.outlineVariant},
      ]}
    >
      <Text
        accessibilityRole="header"
        style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}
      >
        {title}
      </Text>
      {count > 0 ? (
        <>
          <View style={styles.countBlock}>
            <Text style={[typeScale.displayMedium, {color: colors.onSurface}]}>{String(count)}</Text>
            <Text style={[typeScale.description, styles.countLabel, {color: colors.onSurfaceVariant}]}>
              {countLabel}
            </Text>
          </View>
          <View style={styles.action}>
            <FilledAction label={actionLabel} disabled={busy} onPress={onAction} />
          </View>
        </>
      ) : (
        <Text style={[typeScale.bodyMedium, styles.empty, {color: colors.onSurfaceVariant}]}>
          {emptyText}
        </Text>
      )}
    </View>
  );
}

export function WaniKaniHome({
  summary,
  busy,
  onBack,
  onStartReviews,
  onStartLessons,
}: {
  summary: WaniKaniSummary | null;
  busy: boolean;
  onBack: () => void;
  onStartReviews: () => void;
  onStartLessons: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title="WaniKani" onBack={onBack} />
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.screen}
        contentContainerStyle={[
          styles.body,
          {paddingHorizontal: contentInset, paddingBottom: insets.bottom + spacing.xl},
        ]}
      >
        <View style={styles.content}>
          {summary == null ? (
            <BusyRow message="Reading WaniKani…" />
          ) : (
            <>
              <HubSection
                first
                title="Reviews"
                count={summary.dueNow}
                countLabel={summary.dueNow === 1 ? "review is due" : "reviews are due"}
                emptyText={
                  summary.nextReviewAt == null
                    ? "You're caught up. Nothing is due right now."
                    : `You're caught up. The next reviews come up at ${nextReviewLabel(summary.nextReviewAt)}.`
                }
                actionLabel="Start reviews"
                busy={busy}
                onAction={onStartReviews}
              />
              <HubSection
                title="New cards"
                count={summary.lessonsNow}
                countLabel={summary.lessonsNow === 1 ? "new card is waiting" : "new cards are waiting"}
                emptyText="No new cards right now. New cards appear as your current ones are passed."
                actionLabel="Start lessons"
                busy={busy}
                onAction={onStartLessons}
              />
            </>
          )}
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {flexGrow: 1, paddingTop: spacing.xl},
  content: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  section: {paddingVertical: spacing.xl},
  countBlock: {marginTop: spacing.s},
  countLabel: {marginTop: 2},
  action: {marginTop: spacing.l},
  empty: {marginTop: spacing.s},
});
