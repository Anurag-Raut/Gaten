/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The WaniKani hub, to the approved two-card Ink direction: the page title, then exactly two bordered
 * cards - **Reviews** and **New cards** - each one an independent queue snapshot with the single
 * action that starts it. There are no tabs and no third section: the next review time belongs inside
 * the reviews card, and the next batch belongs inside the new-cards card.
 *
 * Every card is one of a small set of states rather than a count: it can be loading, ready, caught
 * up, cached because the device is offline, failed, restricted by the account's level or plan, or a
 * batch left part-way. A zero count with fresh data is a caught-up card; a zero with no data behind
 * it is not, and never looks like one.
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
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {Icon} from "../components";
import type {IconName} from "../icons";
import {PressableScale} from "../PressableScale";
import {MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {LESSON_BATCH_SIZE} from "./review-store";
import type {WaniKaniLessonSession, WaniKaniSummary} from "./review-store";

/** One row under a card's divider: a quiet key, and the value it belongs to when there is one. */
interface HubRow {
  key: string;
  value?: string;
}

/** Everything one card draws, derived from the hub's state before anything is rendered. */
interface HubCardModel {
  icon: IconName;
  title: string;
  /** The state word in the card's top-right, e.g. `Available now`, `Caught up`, `Cached`. */
  indicator: string;
  /** The count, or null when there is no verified number - drawn as an em dash, never a zero. */
  count: number | null;
  unit: string;
  rows: HubRow[];
  action?: {label: string; onPress: () => void};
  /** The quiet completed row, shown instead of an action. */
  note?: string;
  /** The stable skeleton, while the first read is still on its way. */
  loading?: boolean;
}

/** How far away a moment is, in the card's own words. */
function relativeLabel(at: number): string {
  const minutes = Math.round((at - Date.now()) / 60000);
  if (minutes <= 0) return "Due now";
  if (minutes < 60) return `In ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const rest = minutes % 60;
    return rest === 0 ? `In ${hours} hr` : `In ${hours} hr ${rest} min`;
  }
  return `At ${new Date(at).toLocaleString([], {weekday: "short", hour: "numeric", minute: "2-digit"})}`;
}

/** When the numbers were last read, for the page's synced label. */
function syncedAgo(fetchedAt: number): string {
  const minutes = Math.floor((Date.now() - fetchedAt) / 60000);
  if (minutes < 1) return "Synced just now";
  if (minutes < 60) return `Synced ${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `Synced ${hours} hr ago`;
  return `Synced ${new Date(fetchedAt).toLocaleDateString()}`;
}

/** The reviews card: due now, when the next ones come up, and how many arrive in the next hour. */
function reviewsCard(
  summary: WaniKaniSummary | null,
  failed: boolean,
  busy: boolean,
  onStart: () => void,
  onRetry: () => void
): HubCardModel {
  const base = {icon: "history" as const, title: "Reviews"};
  if (summary == null) {
    return failed
      ? {
          ...base,
          indicator: "Sync issue",
          count: null,
          unit: "Unable to load",
          rows: [{key: "Try again to refresh this queue"}],
          action: {label: "Retry sync", onPress: onRetry},
        }
      : {...base, indicator: "Loading…", count: null, unit: "", rows: [], loading: true};
  }
  const due = {count: summary.dueNow, unit: summary.dueNow === 1 ? "review due" : "reviews due"};
  if (summary.sync === "offline") {
    return {
      ...base,
      indicator: "Cached",
      ...due,
      rows: [
        {
          key: "Next review",
          value:
            summary.nextReviewAt == null
              ? "Unknown"
              : `Last known: ${relativeLabel(summary.nextReviewAt)}`,
        },
        {key: "Queue", value: "May be outdated"},
      ],
      note: "Reconnect to start a session",
    };
  }
  if (summary.sync === "failed") {
    return {
      ...base,
      indicator: "Sync issue",
      ...due,
      rows: [{key: "Try again to refresh this queue"}],
      action: {label: "Retry sync", onPress: onRetry},
    };
  }
  const rows: HubRow[] = [];
  if (summary.nextReviewAt != null) {
    rows.push({key: "Next review", value: relativeLabel(summary.nextReviewAt)});
  }
  if (summary.nextHourCount != null) {
    rows.push({key: "Next hour", value: `${summary.nextHourCount} becoming due`});
  }
  if (summary.dueNow > 0) {
    return {
      ...base,
      indicator: "Available now",
      ...due,
      rows,
      action: {label: "Start reviews", onPress: onStart},
    };
  }
  return {
    ...base,
    indicator: "Caught up",
    ...due,
    rows: [...rows, {key: "Current queue", value: "Cleared"}],
    note: "No reviews waiting",
  };
}

/** The new-cards card: lessons available, the next batch, and a batch left part-way. */
function lessonsCard(
  summary: WaniKaniSummary | null,
  failed: boolean,
  locked: boolean,
  paused: WaniKaniLessonSession | null,
  busy: boolean,
  onStart: () => void,
  onRetry: () => void
): HubCardModel {
  const base = {icon: "library_books" as const, title: "New cards"};
  if (summary == null) {
    return failed
      ? {
          ...base,
          indicator: "Sync issue",
          count: null,
          unit: "Unable to load",
          rows: [{key: "Try again to refresh this queue"}],
          action: {label: "Retry sync", onPress: onRetry},
        }
      : {...base, indicator: "Loading…", count: null, unit: "", rows: [], loading: true};
  }
  if (locked) {
    return {
      ...base,
      indicator: "Unavailable",
      count: 0,
      unit: "lessons available",
      rows: [{key: "Availability", value: "Level or plan restricted"}],
      note: "Lessons unavailable for this account",
    };
  }
  if (paused != null) {
    const total = paused.batch.length;
    const completed = paused.quizIndex;
    const remaining = Math.max(0, total - completed);
    return {
      ...base,
      indicator: "In progress",
      count: summary.lessonsNow,
      unit: summary.lessonsNow === 1 ? "lesson available" : "lessons available",
      rows: [
        {key: "Next batch", value: `${remaining} remaining`},
        {key: "Progress", value: `${completed} of ${total} completed`},
      ],
      action: {label: "Resume lessons", onPress: onStart},
    };
  }
  const available = {
    count: summary.lessonsNow,
    unit: summary.lessonsNow === 1 ? "lesson available" : "lessons available",
  };
  if (summary.sync === "offline") {
    return {
      ...base,
      indicator: "Cached",
      ...available,
      rows: [{key: "Next batch", value: `${Math.min(summary.lessonsNow, LESSON_BATCH_SIZE)} cards`}],
      note: "Reconnect to start a session",
    };
  }
  if (summary.sync === "failed") {
    return {
      ...base,
      indicator: "Sync issue",
      ...available,
      rows: [{key: "Try again to refresh this queue"}],
      action: {label: "Retry sync", onPress: onRetry},
    };
  }
  if (summary.lessonsNow > 0) {
    return {
      ...base,
      indicator: "Available now",
      ...available,
      rows: [{key: "Next batch", value: `${Math.min(summary.lessonsNow, LESSON_BATCH_SIZE)} cards`}],
      action: {label: "Start lessons", onPress: onStart},
    };
  }
  return {
    ...base,
    indicator: "Caught up",
    count: 0,
    unit: "lessons available",
    rows: [
      {key: "Next batch", value: "None available"},
      {key: "Current queue", value: "Cleared"},
    ],
    note: "All available lessons finished",
  };
}

/** One card: the icon, title and state word, then the count, the detail rows and one action. */
function HubCard({model, busy}: {model: HubCardModel; busy: boolean}) {
  const {colors} = useTheme();
  return (
    <View
      style={[styles.card, {backgroundColor: colors.background, borderColor: colors.outlineVariant}]}
    >
      <View style={styles.cardHead}>
        <View style={styles.cardTitle}>
          <Icon name={model.icon} size={19} color={colors.accent} />
          <Text style={[styles.cardTitleText, {color: colors.onSurface}]}>{model.title}</Text>
        </View>
        <Text numberOfLines={1} style={[styles.indicator, {color: colors.onSurfaceVariant}]}>
          {model.indicator}
        </Text>
      </View>
      {model.loading ? (
        <>
          <View style={styles.numRow}>
            <View style={[styles.skeleton, styles.skeletonNum, {backgroundColor: colors.surfaceVariant}]} />
            <View style={[styles.skeleton, styles.skeletonUnit, {backgroundColor: colors.surfaceVariant}]} />
          </View>
          <View style={[styles.divider, {backgroundColor: colors.outlineVariant}]} />
          <View
            style={[styles.skeleton, styles.skeletonWide, {backgroundColor: colors.surfaceVariant}]}
          />
          <View
            style={[
              styles.skeleton,
              styles.skeletonNarrow,
              {backgroundColor: colors.surfaceVariant},
            ]}
          />
        </>
      ) : (
        <>
          <View style={styles.numRow}>
            <Text style={[styles.num, {color: colors.onSurface}]}>
              {model.count == null ? "—" : String(model.count)}
            </Text>
            {model.unit !== "" && (
              <Text numberOfLines={2} style={[styles.unit, {color: colors.onSurfaceVariant}]}>
                {model.unit}
              </Text>
            )}
          </View>
          <View style={[styles.divider, {backgroundColor: colors.outlineVariant}]} />
          {model.rows.map((row, index) => (
            <View key={row.key} style={[styles.detail, index > 0 && styles.detailSpaced]}>
              <Text numberOfLines={2} style={[styles.detailKey, {color: colors.onSurfaceVariant}]}>
                {row.key}
              </Text>
              {row.value != null && (
                <Text numberOfLines={2} style={[styles.detailValue, {color: colors.onSurface}]}>
                  {row.value}
                </Text>
              )}
            </View>
          ))}
          {model.action != null ? (
            <PressableScale
              accessibilityRole="button"
              accessibilityLabel={model.action.label}
              accessibilityState={{disabled: busy}}
              disabled={busy}
              onPress={model.action.onPress}
              style={[
                styles.action,
                {backgroundColor: busy ? colors.disabled : colors.actionPrimary},
              ]}
            >
              <Text
                style={[
                  styles.actionLabel,
                  {color: busy ? colors.onDisabled : colors.onActionPrimary},
                ]}
              >
                {model.action.label}
              </Text>
              <Icon
                name="arrow_forward"
                size={18}
                color={busy ? colors.onDisabled : colors.onActionPrimary}
              />
            </PressableScale>
          ) : model.note != null ? (
            <View style={[styles.note, {backgroundColor: colors.surfaceVariant}]}>
              <Icon name="check_circle" size={16} color={colors.onSurfaceVariant} />
              <Text numberOfLines={2} style={[styles.noteText, {color: colors.onSurfaceVariant}]}>
                {model.note}
              </Text>
            </View>
          ) : null}
        </>
      )}
    </View>
  );
}

export function WaniKaniHome({
  summary,
  failed,
  lessonsLocked,
  pausedLesson,
  busy,
  onBack,
  onStartReviews,
  onStartLessons,
  onRetrySync,
}: {
  summary: WaniKaniSummary | null;
  failed: boolean;
  lessonsLocked: boolean;
  pausedLesson: WaniKaniLessonSession | null;
  busy: boolean;
  onBack: () => void;
  onStartReviews: () => void;
  onStartLessons: () => void;
  onRetrySync: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;

  const loading = summary == null && !failed;
  const reviews = reviewsCard(summary, failed, busy, onStartReviews, onRetrySync);
  const lessons = lessonsCard(
    summary,
    failed,
    lessonsLocked,
    pausedLesson,
    busy,
    onStartLessons,
    onRetrySync
  );
  const allDone =
    summary != null &&
    summary.sync === "fresh" &&
    summary.dueNow === 0 &&
    summary.lessonsNow === 0 &&
    pausedLesson == null &&
    !lessonsLocked;
  const subtitle = loading
    ? "Updating your WaniKani queues…"
    : failed && summary == null
      ? "We couldn't refresh your queues."
      : summary?.sync === "offline"
        ? "Showing your last saved queue."
        : allDone
          ? "All caught up for now."
          : "Choose what to work on next.";
  const syncText = loading
    ? "Syncing…"
    : failed && summary == null
      ? "Sync failed"
      : summary == null
        ? "Syncing…"
        : summary.sync === "offline"
          ? "Offline · cached"
          : summary.sync === "failed"
            ? "Sync failed"
            : syncedAgo(summary.fetchedAt);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
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
          <View style={styles.top}>
            <PressableScale
              accessibilityRole="button"
              accessibilityLabel="Back"
              onPress={onBack}
              style={styles.back}
            >
              <Icon name="arrow_back" size={22} color={colors.onSurface} />
            </PressableScale>
            <Text style={[styles.topTitle, {color: colors.onSurface}]}>WaniKani</Text>
            <View style={styles.sync}>
              {loading ? (
                <ActivityIndicator size="small" color={colors.accent} />
              ) : (
                <Icon name="refresh" size={15} color={colors.onSurfaceVariant} />
              )}
              <Text numberOfLines={1} style={[styles.syncText, {color: colors.onSurfaceVariant}]}>
                {syncText}
              </Text>
            </View>
          </View>
          <Text accessibilityRole="header" style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
            Your study
          </Text>
          <Text style={[typeScale.description, styles.subtitle, {color: colors.onSurfaceVariant}]}>
            {subtitle}
          </Text>
          <View style={styles.stack}>
            <HubCard model={reviews} busy={busy} />
            <HubCard model={lessons} busy={busy} />
          </View>
        </View>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {flexGrow: 1},
  content: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  top: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    minHeight: 56,
  },
  back: {width: 48, height: 48, alignItems: "flex-start", justifyContent: "center"},
  topTitle: {fontSize: 17, fontWeight: "700", letterSpacing: -0.5},
  sync: {flexDirection: "row", alignItems: "center", flexShrink: 1, marginStart: spacing.s},
  syncText: {fontSize: 11, lineHeight: 15, marginStart: 6},
  subtitle: {marginTop: 7, marginBottom: spacing.xl},
  stack: {gap: 14},
  card: {borderRadius: 21, borderWidth: 1, paddingHorizontal: 19, paddingTop: 19, paddingBottom: 17},
  cardHead: {flexDirection: "row", alignItems: "center", justifyContent: "space-between"},
  cardTitle: {flexDirection: "row", alignItems: "center", flexShrink: 1},
  cardTitleText: {fontSize: 17, lineHeight: 22, fontWeight: "700", letterSpacing: -0.4, marginStart: 9},
  indicator: {fontSize: 11, lineHeight: 15, marginStart: spacing.s},
  numRow: {flexDirection: "row", alignItems: "baseline", marginTop: 15},
  num: {fontSize: 46, lineHeight: 50, fontWeight: "700", letterSpacing: -2.8, fontVariant: ["tabular-nums"]},
  unit: {fontSize: 13, lineHeight: 18, marginStart: 9, flexShrink: 1},
  divider: {height: 1, marginTop: 16, marginBottom: 12},
  detail: {flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between"},
  detailSpaced: {marginTop: 9},
  detailKey: {fontSize: 12, lineHeight: 17, flexShrink: 1},
  detailValue: {fontSize: 12, lineHeight: 17, fontWeight: "600", textAlign: "right", marginStart: spacing.m},
  action: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    minHeight: 52,
    borderRadius: 13,
    paddingHorizontal: 15,
    marginTop: 17,
  },
  actionLabel: {fontSize: 15, fontWeight: "600", letterSpacing: -0.2, flexShrink: 1},
  note: {
    flexDirection: "row",
    alignItems: "center",
    minHeight: 42,
    borderRadius: 11,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginTop: 17,
  },
  noteText: {fontSize: 12, lineHeight: 17, marginStart: 9, flexShrink: 1},
  skeleton: {borderRadius: 8},
  skeletonNum: {width: 74, height: 44},
  skeletonUnit: {width: 125, height: 18, marginStart: 9},
  skeletonWide: {width: "92%", height: 13},
  skeletonNarrow: {width: "68%", height: 13, marginTop: 11},
});
