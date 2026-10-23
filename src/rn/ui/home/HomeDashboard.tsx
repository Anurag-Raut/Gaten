/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React, {useState} from "react";
import {ActivityIndicator, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useTheme} from "../theme";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {shape, spacing, typeScale} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import type {HomeDashboardData} from "./useHomeDashboard";
import {
  AnkiServiceMark,
  EmptyRecentArtwork,
  StreakDayMark,
  StreakFlameMark,
  ToriiServiceMark,
} from "./HomeArtwork";

type HomeDashboardProps = {
  home: HomeDashboardData;
  /** The review flow on AnkiDroid: its deck list, or the state that stops it. */
  onOpenAnki: () => void;
  /** The review flow on WaniKani: its connection state, or the queue behind it. */
  onOpenWaniKani: () => void;
  onOpenWord: (entryId: number) => void;
};

/** The home dashboard follows the reader's actual Anki activity and service snapshots. */
export function HomeDashboard({
  home,
  onOpenAnki,
  onOpenWaniKani,
  onOpenWord,
}: HomeDashboardProps) {
  return (
    <View style={styles.dashboard}>
      <StreakBanner home={home} />
      <View style={styles.serviceRow}>
        <AnkiCard
          home={home}
          onOpen={onOpenAnki}
          onRefresh={home.refreshAnki}
        />
        <WaniKaniCard
          home={home}
          onOpen={onOpenWaniKani}
          onRefresh={home.refreshWaniKani}
        />
      </View>
      <RecentLookups home={home} onOpenWord={onOpenWord} onRefresh={home.refreshRecentLookups} />
    </View>
  );
}

/** Shows the current Anki streak and seven weekday states without inferring lifetime activity. */
function StreakBanner({
  home,
}: {
  home: HomeDashboardData;
}) {
  const {streak} = home;
  const completedToday = (streak.completedToday ?? 0) > 0;
  const {colors} = useTheme();
  const ink = colors.onSurface;
  const muted = colors.onSurfaceVariant;
  // Keep the calendar visible before Anki activity is available.
  const today = new Date();
  const daysSinceMonday = (today.getDay() + 6) % 7;
  const weekdays = streak.weekdays.length > 0 ? streak.weekdays : Array.from({length: 7}, (_, index) => {
    const date = new Date(today);
    date.setDate(today.getDate() - daysSinceMonday + index);
    return {
      key: `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`,
      label: ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][index],
      daysAgo: daysSinceMonday - index,
      active: null,
      completed: null,
      today: index === daysSinceMonday,
    };
  });
  const hasPriorActivity = (day: (typeof weekdays)[number]) =>
    weekdays.some((olderDay) => olderDay.daysAgo > day.daysAgo && olderDay.active === true);
  const streakDaysLabel = String(streak.days ?? 0);
  return (
    <View style={[styles.streakCard, {borderBottomColor: colors.outlineVariant}]}>
      <View style={styles.streakTop}>
        <StreakFlameMark />
        <View style={styles.streakCopy}>
          <Text
            accessibilityLabel="Daily streak. Anki study days follow your collection's rollover."
            style={[typeScale.titleMedium, styles.streakTitle, {color: ink}]}
          >
            Daily streak
          </Text>
        </View>
        <View style={styles.streakTotal}>
          <Text style={[styles.streakCount, {color: colors.accent}]}>{streakDaysLabel}</Text>
          <Text numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.8} style={[typeScale.bodySmall, styles.streakTotalLabel, {color: muted}]}>days</Text>
        </View>
      </View>
      <View style={styles.weekRow}>
        {weekdays.map((day) => {
          let state: "complete" | "missed" | "today" | "empty" | "unknown" | "upcoming" = "empty";
          if (day.today) {
            if (completedToday || day.active === true) state = "complete";
            else if (day.active == null) state = "unknown";
            else state = "today";
          } else if (day.active === true) {
            state = "complete";
          } else if (day.active === false && day.daysAgo > 0 && hasPriorActivity(day)) {
            state = "missed";
          } else if (day.daysAgo < 0) {
            state = "upcoming";
          } else if (day.active == null && day.daysAgo >= 0) {
            state = "unknown";
          }
          const status = state === "complete"
            ? "studied"
            : state === "missed"
              ? "missed"
              : state === "today"
                ? "not studied yet"
                : state === "unknown"
                  ? "study activity unknown"
                  : state === "upcoming"
                    ? "upcoming"
                    : "no study recorded";
          return (
          <View
            key={day.key}
            accessible
            accessibilityLabel={`Anki ${day.label}: ${status}${day.today ? ", today" : ""}`}
            style={styles.weekDay}
          >
            <StreakDayMark state={state === "unknown" ? "empty" : state} size={29} />
            <Text numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.85} style={[typeScale.labelMedium, {color: day.today ? colors.accent : muted}]}>
              {day.label}
            </Text>
          </View>
          );
        })}
      </View>
    </View>
  );
}

/** Shows Anki's live due count while keeping connection, permission, and retry states explicit. */
function AnkiCard({
  home,
  onOpen,
  onRefresh,
}: {
  home: HomeDashboardData;
  /** Opens the review flow on AnkiDroid, whatever state AnkiDroid is in. */
  onOpen: () => void;
  onRefresh: () => void;
}) {
  const {anki} = home;
  const due = anki.dueCount;
  const completed = anki.completedToday;
  let status = "Checking…";
  let statusTone: ServiceStatusTone = "neutral";
  let count: number | null = null;
  let countLabel = "reviews due";
  let detail: string | undefined;
  let loading = false;
  let action: (() => void) | undefined;
  let actionLabel: string | undefined;
  let hint: string | undefined;

  switch (anki.status) {
    case "connected":
      status = anki.refreshing ? "Refreshing…" : "Connected";
      statusTone = anki.refreshing ? "neutral" : "connected";
      action = onOpen;
      hint = "Opens AnkiDroid's decks";
      if (due != null) {
        count = due;
        countLabel = completed != null && completed > 0 ? "remaining" : "reviews due";
      } else {
        detail = "Review count unavailable";
      }
      break;
    case "missing":
      status = "Not connected";
      statusTone = "disconnected";
      action = onOpen;
      actionLabel = "Open Anki";
      hint = "Opens the Anki screen";
      break;
    case "permissionNeeded":
      status = "Permission needed";
      statusTone = "error";
      action = onOpen;
      actionLabel = "Grant access";
      hint = "Opens the Anki screen to grant AnkiDroid access";
      break;
    case "notSetUp":
      status = "Not set up";
      statusTone = "disconnected";
      action = onOpen;
      actionLabel = "Open Anki";
      hint = "Opens the Anki screen";
      break;
    case "error":
      status = "Refresh failed";
      statusTone = "error";
      action = onRefresh;
      actionLabel = "Retry reading Anki";
      hint = "Retries reading Anki";
      if (due != null) {
        count = due;
        countLabel = "due · tap to retry";
      } else {
        detail = "Could not load";
        actionLabel = "Try again";
      }
      break;
    case "loading":
      status = "Checking…";
      detail = "Loading reviews…";
      loading = true;
      break;
  }

  return (
    <ServiceCard
      title="Anki"
      status={status}
      statusTone={statusTone}
      count={count}
      countLabel={countLabel}
      detail={detail}
      loading={loading}
      artwork={<AnkiServiceMark size={28} />}
      action={action}
      actionLabel={actionLabel}
      hint={hint}
    />
  );
}

/** Shows WaniKani's due-now count without inventing completed or remaining-review history. */
function WaniKaniCard({
  home,
  onOpen,
  onRefresh,
}: {
  home: HomeDashboardData;
  /** Opens the review flow on WaniKani — its connection state, or its queue. */
  onOpen: () => void;
  onRefresh: () => void;
}) {
  const {waniKani} = home;
  let status = "Checking…";
  let statusTone: ServiceStatusTone = "neutral";
  let count: number | null = null;
  let detail: string | undefined;
  let loading = false;
  let action: (() => void) | undefined;
  let actionLabel: string | undefined;
  let hint: string | undefined;

  switch (waniKani.status) {
    case "connected":
      if (waniKani.failure === "OFFLINE") {
        // The numbers on screen are a cached read. With no connection the row says so instead of
        // showing a stale count as if it were live.
        status = "Internet required";
        statusTone = "error";
        count = null;
        detail = "Connect to load reviews";
        action = onOpen;
        hint = "Opens WaniKani reviews";
        break;
      }
      status = waniKani.error ? "Refresh failed" : waniKani.refreshing ? "Refreshing…" : "Connected";
      statusTone = waniKani.error ? "error" : waniKani.refreshing ? "neutral" : "connected";
      count = waniKani.dueNow;
      detail = count == null ? "Review count unavailable" : undefined;
      if (waniKani.error) {
        action = onRefresh;
        actionLabel = "Retry reading WaniKani";
        hint = "Retries reading WaniKani";
      } else {
        action = onOpen;
        hint = "Opens WaniKani reviews";
      }
      break;
    case "disconnected":
      status = "Not connected";
      statusTone = "disconnected";
      action = onOpen;
      actionLabel = "Connect";
      hint = "Opens the WaniKani screen";
      break;
    case "authRejected":
      status = "Reconnect required";
      statusTone = "error";
      action = onOpen;
      actionLabel = "Reconnect";
      hint = "Opens the WaniKani screen";
      break;
    case "error":
      if (waniKani.failure === "OFFLINE") {
        status = "Internet required";
        statusTone = "error";
        detail = "Connect to load reviews";
        action = onRefresh;
        actionLabel = "Try again";
        hint = "Retries reading WaniKani";
        break;
      }
      status = "Unavailable";
      statusTone = "error";
      action = onRefresh;
      actionLabel = "Try again";
      hint = "Retries reading WaniKani";
      break;
    case "loading":
      status = "Checking…";
      detail = "Loading reviews…";
      loading = true;
      break;
  }

  return (
    <ServiceCard
      title="WaniKani"
      status={status}
      statusTone={statusTone}
      count={count}
      countLabel={waniKani.error ? "due · tap to retry" : "reviews due"}
      detail={detail}
      loading={loading}
      artwork={<ToriiServiceMark size={28} />}
      action={action}
      actionLabel={actionLabel}
      hint={hint}
      offline={waniKani.failure === "OFFLINE"}
    />
  );
}

type ServiceStatusTone = "connected" | "disconnected" | "neutral" | "error";

/**
 * Renders a compact service row with its mark, connection state and one honest count or action.
 */
function ServiceCard({
  title,
  status,
  statusTone,
  count,
  countLabel,
  detail,
  loading = false,
  artwork,
  action,
  actionLabel,
  hint,
  offline = false,
}: {
  title: string;
  status: string;
  statusTone: ServiceStatusTone;
  count: number | null;
  countLabel: string;
  detail?: string;
  /** A read is on its way: the spinner takes the status dot's place until it lands. */
  loading?: boolean;
  artwork: React.ReactNode;
  action?: () => void;
  actionLabel?: string;
  hint?: string;
  /** No connection: the trailing edge shows the offline glyph instead of a stale count or line. */
  offline?: boolean;
}) {
  const {colors} = useTheme();
  const {width, fontScale} = useWindowDimensions();
  // The single row keeps the count on the trailing edge. It only stacks when the scaled type no
  // longer leaves the outcome enough room beside the header — a narrow screen or very large type.
  const stackOutcome = width / fontScale < 270;
  const dark = isDarkScheme(colors.background);
  const tileColor = title === "Anki"
    ? dark ? "#182D40" : "#EAF4FF"
    : dark ? "#38212A" : "#FFF0F2";
  const ink = colors.onSurface;
  const muted = colors.onSurfaceVariant;
  const statusColor = statusTone === "error" ? colors.error : muted;
  const statusDotColor = statusTone === "connected"
    ? colors.success
    : statusTone === "error"
      ? colors.warning
      : colors.iconSecondary;
  const visibleDescription = count != null
    ? `${count} ${countLabel}`
    : actionLabel
      ? actionLabel
      : detail ?? "";
  const accessibilityLabel = `${title}, ${status}${count != null ? `, ${count} ${countLabel}` : detail ? `, ${detail}` : actionLabel ? `, ${actionLabel}` : ""}`;
  const content = (
    <View style={[styles.serviceRowItem, stackOutcome && styles.serviceRowItemScaled, {borderBottomColor: colors.outlineVariant}]}>
      <View style={[styles.serviceHeader, stackOutcome && styles.serviceHeaderScaled]}>
        <View
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
          style={[styles.serviceIconTile, {backgroundColor: tileColor}]}
        >
          {artwork}
        </View>
        <View style={styles.serviceTitles}>
          <Text numberOfLines={1} style={[typeScale.titleSmall, {color: ink}]}>{title}</Text>
          <View style={styles.serviceStatusRow}>
            {loading ? (
              <ActivityIndicator size={12} color={colors.accent} />
            ) : (
              <View style={[styles.serviceStatusDot, {backgroundColor: statusDotColor}]} />
            )}
            <Text numberOfLines={2} style={[typeScale.bodySmall, styles.serviceStatus, {color: statusColor}]}>{status}</Text>
          </View>
        </View>
      </View>
      <View style={[styles.serviceFooter, stackOutcome && styles.serviceFooterScaled]}>
        {offline ? (
          <Icon name="cloud_off" size={18} color={statusColor} />
        ) : count != null ? (
          <View style={[styles.serviceCountLine, stackOutcome && styles.serviceCountLineScaled]}>
            {/* The count wears the accent, the same tone the study hubs' rows give their due count. */}
            <Text numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.6} style={[styles.serviceCount, {color: colors.accent}]}>{count}</Text>
            <Text numberOfLines={2} style={[typeScale.bodySmall, styles.serviceCountLabel, {color: muted}]}>{countLabel}</Text>
          </View>
        ) : actionLabel ? (
          <Text
            numberOfLines={1}
            adjustsFontSizeToFit
            minimumFontScale={0.75}
            style={[styles.serviceAction, {color: ink}]}
          >
            {actionLabel}
          </Text>
        ) : (
          <Text numberOfLines={2} style={[typeScale.bodySmall, styles.serviceDetail, {color: muted}]}>{visibleDescription}</Text>
        )}
        {action ? <Icon name="keyboard_arrow_right" size={18} color={muted} /> : null}
      </View>
    </View>
  );

  if (!action) return <View accessible accessibilityLabel={accessibilityLabel} style={styles.serviceColumn}>{content}</View>;
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityHint={hint}
      onPress={action}
      style={styles.serviceColumn}
    >
      {content}
    </PressableScale>
  );
}

/** Recent words stay compact and open the same word route as a search result. */
function RecentLookups({
  home,
  onOpenWord,
  onRefresh,
}: {
  home: HomeDashboardData;
  onOpenWord: (entryId: number) => void;
  onRefresh: () => void;
}) {
  const {colors} = useTheme();
  const [expanded, setExpanded] = useState(false);
  const {recentLookups, recentLookupsError, recentLookupsStatus} = home;
  const visibleLookups = expanded ? recentLookups : recentLookups.slice(0, 3);

  return (
    <View style={styles.recentSection}>
      <View style={styles.recentHeading}>
        <Text style={[typeScale.titleLarge, styles.recentTitle, {color: colors.onSurface}]}>Recently looked up</Text>
        {recentLookups.length > 3 ? (
          <PressableScale
            accessibilityRole="button"
            accessibilityLabel={expanded ? "Show fewer recent lookups" : "See all recent lookups"}
            accessibilityState={{expanded}}
            onPress={() => setExpanded((value) => !value)}
            style={styles.seeAllButton}
          >
            <Text style={[typeScale.labelMedium, {color: colors.accent}]}>
              {expanded ? "Show less" : "See all"}
            </Text>
          </PressableScale>
        ) : null}
      </View>
      {recentLookups.length > 0 ? (
        <View style={styles.recentList}>
          {visibleLookups.map((hit, index) => (
            <RecentLookupRow
              key={hit.id}
              hit={hit}
              last={index === visibleLookups.length - 1}
              onPress={() => onOpenWord(hit.id)}
            />
          ))}
        </View>
      ) : recentLookupsStatus === "loading" ? (
        <EmptyRecent
          icon={<ActivityIndicator size="small" color={colors.iconSecondary} />}
          title="Loading recent lookups…"
          message=""
        />
      ) : recentLookupsStatus === "error" ? (
        <EmptyRecent
          icon={<Icon name="error" color={colors.warning} />}
          title="Recent lookups could not be loaded"
          message={recentLookupsError ?? "Try loading them again."}
          error
          action={{label: "Try again", onPress: onRefresh}}
        />
      ) : (
        <View accessible accessibilityLabel="No recent searches found" style={styles.noRecentLookups}>
          <View style={styles.emptyArtwork} pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <EmptyRecentArtwork />
          </View>
          <Text style={[styles.emptyTitle, {color: colors.onSurface}]}>No recent searches found</Text>
        </View>
      )}
      {recentLookups.length > 0 && recentLookupsError ? (
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel="Refresh recent lookups"
          onPress={onRefresh}
          style={styles.recentError}
        >
          <Text style={[typeScale.labelMedium, {color: colors.error}]}>Could not refresh recent lookups. Try again.</Text>
        </PressableScale>
      ) : null}
    </View>
  );
}

function RecentLookupRow({
  hit,
  last,
  onPress,
}: {
  hit: SearchResult;
  last: boolean;
  onPress: () => void;
}) {
  const {colors} = useTheme();
  const {fontScale} = useWindowDimensions();
  // A narrow gloss column truncates the definition once the type grows, so larger text moves the
  // gloss under the word and lets it wrap in full instead of clipping it.
  const stacked = fontScale > 1.2;
  const reading = hit.kana[0] ?? "";
  const gloss = hit.glosses[0] ?? "";
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={`Open ${hit.primary}${reading ? `, ${reading}` : ""}${gloss ? `, ${gloss}` : ""}`}
      onPress={onPress}
      style={[
        styles.lookupRow,
        stacked && styles.lookupRowStacked,
        !last && {borderBottomWidth: 1, borderBottomColor: colors.outlineVariant},
      ]}
    >
      <View style={styles.lookupWord}>
        <Text numberOfLines={1} style={[typeScale.titleSmall, {color: colors.onSurface}]}>
          {hit.primary}
        </Text>
        {reading ? (
          <Text numberOfLines={1} style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
            {reading}
          </Text>
        ) : null}
      </View>
      {gloss ? (
        <Text
          numberOfLines={stacked ? undefined : 2}
          style={[
            typeScale.bodySmall,
            styles.lookupGloss,
            stacked && styles.lookupGlossStacked,
            {color: colors.onSurfaceVariant},
          ]}
        >
          {gloss}
        </Text>
      ) : null}
    </PressableScale>
  );
}

function EmptyRecent({
  icon,
  title,
  message,
  action,
  error = false,
}: {
  icon: React.ReactNode;
  title: string;
  message: string;
  action?: {label: string; onPress: () => void};
  error?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <View style={styles.emptyRecent}>
        <View style={[styles.emptyIcon, {backgroundColor: colors.surfaceVariant}]}>{icon}</View>
      <View style={styles.emptyCopy}>
        <Text style={[typeScale.titleSmall, {color: error ? colors.error : colors.onSurface}]}>{title}</Text>
        {message ? <Text style={[typeScale.bodySmall, styles.emptyMessage, {color: colors.onSurfaceVariant}]}>{message}</Text> : null}
        {action ? (
          <PressableScale
            accessibilityRole="button"
            accessibilityLabel={action.label}
            onPress={action.onPress}
            style={styles.emptyAction}
          >
            <Text style={[typeScale.labelLarge, {color: colors.accent}]}>{action.label}</Text>
          </PressableScale>
        ) : null}
      </View>
    </View>
  );
}

function isDarkScheme(background: string): boolean {
  return Number.parseInt(background.slice(1, 3), 16) < 128;
}

const styles = StyleSheet.create({
  dashboard: {width: "100%"},
  streakCard: {
    width: "100%",
    marginTop: spacing.xl,
    paddingBottom: 22,
    borderBottomWidth: 1,
  },
  streakTop: {
    minHeight: 38,
    flexDirection: "row",
    alignItems: "center",
    gap: 11,
  },
  streakCopy: {flex: 1, minWidth: 0},
  streakTitle: {flexShrink: 1},
  streakTotal: {minWidth: 88, alignItems: "flex-end", justifyContent: "center", gap: 3},
  streakCount: {fontSize: 20, lineHeight: 22, fontWeight: "700"},
  streakTotalLabel: {textAlign: "right"},
  weekRow: {flexDirection: "row", justifyContent: "space-between", marginTop: 20},
  weekDay: {flex: 1, minWidth: 0, alignItems: "center", gap: spacing.s},
  serviceRow: {marginTop: 8},
  serviceColumn: {width: "100%"},
  serviceRowItem: {
    minHeight: 69,
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    paddingVertical: 0,
    borderBottomWidth: 1,
  },
  serviceRowItemScaled: {flexDirection: "column", alignItems: "stretch", gap: spacing.s},
  serviceHeader: {flex: 1, minWidth: 0, flexDirection: "row", alignItems: "center", gap: spacing.m},
  serviceHeaderScaled: {flex: 0},
  serviceIconTile: {
    width: 37,
    height: 37,
    borderRadius: shape.brandTile,
    overflow: "hidden",
    alignItems: "center",
    justifyContent: "center",
  },
  serviceTitles: {flex: 1, minWidth: 0},
  serviceStatusRow: {marginTop: spacing.xs, flexDirection: "row", alignItems: "center", gap: spacing.xs},
  serviceStatusDot: {width: 6, height: 6, borderRadius: 3},
  serviceStatus: {flexShrink: 1},
  serviceFooter: {flexShrink: 1, maxWidth: 132, flexDirection: "row", alignItems: "center", gap: 7},
  serviceFooterScaled: {width: "100%", maxWidth: "100%", alignSelf: "flex-end", justifyContent: "flex-end"},
  serviceCountLine: {flex: 1, minWidth: 0, flexDirection: "row", alignItems: "center", gap: 7},
  // Once the outcome drops below the header, the count needs to travel with the chevron on the
  // trailing edge instead of stretching the full row and reading as a second title.
  serviceCountLineScaled: {flex: 0, flexShrink: 1, marginStart: "auto", justifyContent: "flex-end"},
  serviceCount: {fontSize: 24, lineHeight: 24, letterSpacing: -0.9, fontWeight: "700"},
  serviceCountLabel: {flexShrink: 1},
  serviceAction: {fontSize: 13, lineHeight: 18, fontWeight: "600"},
  serviceDetail: {flexShrink: 1},
  recentSection: {marginTop: 34},
  recentHeading: {
    minHeight: 28,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: spacing.s,
  },
  recentTitle: {flex: 1, flexShrink: 1},
  seeAllButton: {minHeight: 48, minWidth: 48, alignItems: "center", justifyContent: "center", paddingHorizontal: spacing.xs},
  recentList: {marginTop: 8},
  lookupRow: {
    minHeight: 58,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.xs,
  },
  lookupRowStacked: {flexDirection: "column", alignItems: "stretch", gap: spacing.xs},
  lookupWord: {flex: 1, minWidth: 0},
  lookupGloss: {maxWidth: "34%", textAlign: "right"},
  lookupGlossStacked: {maxWidth: "100%", textAlign: "left"},
  noRecentLookups: {alignItems: "center", marginTop: 20, paddingBottom: spacing.m},
  emptyArtwork: {width: "100%", maxWidth: 340, marginBottom: spacing.m},
  emptyRecent: {flexDirection: "row", alignItems: "center", gap: 12, paddingTop: 16, paddingBottom: 16},
  emptyIcon: {width: 39, height: 39, borderRadius: shape.empty, alignItems: "center", justifyContent: "center"},
  emptyCopy: {flex: 1, minWidth: 0},
  emptyTitle: {fontSize: 13, lineHeight: 18, fontWeight: "700", textAlign: "center"},
  emptyMessage: {marginTop: 4},
  emptyAction: {minHeight: 48, justifyContent: "center", paddingHorizontal: spacing.m},
  recentError: {minHeight: 48, justifyContent: "center", alignItems: "center"},
});
