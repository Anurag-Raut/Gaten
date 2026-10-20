/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React, {useState} from "react";
import {ActivityIndicator, StyleSheet, Text, View} from "react-native";
import Svg, {
  Circle,
  Path,
  Defs,
  LinearGradient as SvgLinearGradient,
  Rect,
  Stop,
} from "react-native-svg";
import {useTheme} from "../theme";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {shape, spacing, typeScale} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import type {SettingsPageName} from "../navigation";
import type {HomeDashboardData} from "./useHomeDashboard";
import {
  AnkiCardsArtwork,
  StreakDayMark,
  StreakFlameMark,
  ToriiArtwork,
} from "./HomeArtwork";

type HomeDashboardProps = {
  home: HomeDashboardData;
  onOpenReview: () => void;
  onOpenSettingsPage: (page: SettingsPageName) => void;
  onOpenWord: (entryId: number) => void;
};

/** The home dashboard follows the reader's actual Anki activity and service snapshots. */
export function HomeDashboard({
  home,
  onOpenReview,
  onOpenSettingsPage,
  onOpenWord,
}: HomeDashboardProps) {
  const {colors, accent} = useTheme();
  const dark = isDarkScheme(colors.background);
  const canReview = home.canReview;
  const usesIndigoCta = accent === "Indigo";
  const keepIndigoDisabledColor = usesIndigoCta && !dark && !canReview;
  const cta = !canReview && !keepIndigoDisabledColor
    ? dark
      ? {from: "#383246", to: "#302A3C", text: "#BDB2D5"}
      : {from: "#F0EBFF", to: "#E8E0FF", text: "#8573B5"}
    : dark && usesIndigoCta
      ? {from: "#C8B6FF", to: "#B9A7FF", text: "#261849"}
      : usesIndigoCta
        ? {from: "#5A3EF0", to: "#4B31E3", text: "#FFFFFF"}
        : {from: colors.primary, to: colors.primary, text: colors.onPrimary};

  return (
    <View style={styles.dashboard}>
      <StreakBanner home={home} dark={dark} onRefresh={home.refreshAnki} />
      <View style={styles.serviceRow}>
        <AnkiCard
          home={home}
          dark={dark}
          onReview={onOpenReview}
          onSettings={() => onOpenSettingsPage("anki")}
          onRefresh={home.refreshAnki}
        />
        <WaniKaniCard
          home={home}
          dark={dark}
          onSettings={() => onOpenSettingsPage("wanikani")}
          onRefresh={home.refreshWaniKani}
        />
      </View>
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel={canReview ? "Start studying" : "Connect Anki to start studying"}
        accessibilityState={{disabled: !canReview}}
        disabled={!canReview}
        onPress={onOpenReview}
        style={[styles.studyButton, keepIndigoDisabledColor ? styles.studyButtonDisabled : null]}
      >
        <GradientSurface id="home-cta" colors={[cta.from, cta.to]} radius={26} vertical />
        <Text style={[typeScale.labelLarge, styles.studyButtonLabel, {color: cta.text}]}>
          Start studying
        </Text>
        <View pointerEvents="none" style={styles.studyButtonArrow}>
          <Svg width={20} height={20} viewBox="0 0 20 20"><Path d="m7 4 6 6-6 6" fill="none" stroke={cta.text} strokeWidth={3} strokeLinecap="round" strokeLinejoin="round" /></Svg>
        </View>
      </PressableScale>
      <RecentLookups home={home} onOpenWord={onOpenWord} onRefresh={home.refreshRecentLookups} />
    </View>
  );
}

/** Shows the current Anki streak and seven weekday states without inferring lifetime activity. */
function StreakBanner({
  home,
  dark,
  onRefresh,
}: {
  home: HomeDashboardData;
  dark: boolean;
  onRefresh: () => void;
}) {
  const {streak} = home;
  const days = streak.days ?? 0;
  const completedToday = (streak.completedToday ?? 0) > 0;
  const ink = dark ? "#F6F2FF" : "#1B1740";
  const muted = dark ? "#C9C3E0" : "#5A5780";
  const {colors} = useTheme();
  const weekdays = home.streak.weekdays;
  const hasObservedActivity = weekdays.some((day) => day.active === true) || (streak.totalStudyDays ?? 0) > 0;
  const hasPriorActivity = (day: (typeof weekdays)[number]) =>
    weekdays.some((olderDay) => olderDay.daysAgo > day.daysAgo && olderDay.active === true);
  const streakWasBroken = weekdays.some(
    (day) => day.daysAgo > 0 && day.active === false && hasPriorActivity(day),
  );
  const currentRun = Math.max(days, 1);
  const message = streak.status === "loading"
    ? "Checking study history…"
    : streak.status === "unavailable"
      ? "Study history unavailable."
      : completedToday
        ? streak.atLeastSevenDays
          ? "7+ day streak. Great work!"
          : `${currentRun}${streak.capped ? "+" : ""} ${currentRun === 1 ? "day" : "days"} in a row. Keep going!`
        : days > 0
          ? `${days}${streak.capped ? "+" : ""} day streak. Study today!`
          : streakWasBroken
            ? "Streak reset. Start again."
            : "Start your streak today.";
  const totalLabel = streak.totalStudyDays == null
    ? "—"
    : `${streak.totalStudyDays} ${streak.totalStudyDays === 1 ? "day" : "days"}`;
  const cardColor = dark ? "#27243B" : "#F1EEFF";

  return (
    <View style={[styles.streakCard, {backgroundColor: cardColor}]}>
      <View style={styles.streakTop}>
        <StreakFlameMark active={days > 0 || completedToday} size={30} dark={dark} />
        <View style={styles.streakCopy}>
          <Text
            accessibilityLabel="Daily streak. Anki study days follow your collection's rollover."
            style={[styles.heavyTitle, styles.streakTitle, {color: ink}]}
          >
            Daily streak
          </Text>
          <Text style={[typeScale.bodySmall, styles.streakMessage, {color: muted}]}>
            {message}
          </Text>
          {streak.error ? (
            <PressableScale
              accessibilityRole="button"
              accessibilityLabel="Refresh Anki study streak"
              onPress={onRefresh}
              style={styles.retryLink}
            >
              <Text style={[typeScale.labelMedium, {color: colors.primary}]}>Try again</Text>
            </PressableScale>
          ) : null}
        </View>
        <View style={styles.streakTotal}>
          <Text style={[styles.streakTotalValue, {color: ink}]}>{totalLabel}</Text>
          <Text style={[styles.streakTotalCaption, {color: muted}]}>total</Text>
        </View>
      </View>
      <View style={styles.weekRow}>
        {weekdays.map((day) => {
          let state: "complete" | "missed" | "today" | "empty" | "unknown" = "empty";
          if (day.today) {
            if (completedToday || day.active === true) state = "complete";
            else if (day.active == null) state = "unknown";
            else if (hasObservedActivity) state = "today";
          } else if (day.active === true) {
            state = "complete";
          } else if (day.active === false && day.daysAgo > 0 && hasPriorActivity(day)) {
            state = "missed";
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
                  : "no study recorded";
          return (
          <View
            key={day.key}
            accessible
            accessibilityLabel={`Anki ${day.label}: ${status}${day.today ? ", today" : ""}`}
            style={styles.weekDay}
          >
            <StreakDayMark state={state} size={28} dark={dark} />
            <Text numberOfLines={1} style={[styles.dayLabel, {color: muted}, state === "today" ? [styles.todayLabel, {color: ink}] : null]}>
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
  dark,
  onReview,
  onSettings,
  onRefresh,
}: {
  home: HomeDashboardData;
  dark: boolean;
  onReview: () => void;
  onSettings: () => void;
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
  let action: (() => void) | undefined;
  let actionLabel: string | undefined;

  switch (anki.status) {
    case "connected":
      status = anki.refreshing ? "Refreshing…" : "Connected";
      statusTone = anki.refreshing ? "neutral" : "connected";
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
      action = onSettings;
      actionLabel = "Connect to start";
      break;
    case "permissionNeeded":
      status = "Permission needed";
      statusTone = "error";
      action = onReview;
      actionLabel = "Grant access";
      break;
    case "notSetUp":
      status = "Not set up";
      statusTone = "disconnected";
      action = onSettings;
      actionLabel = "Set up AnkiDroid";
      break;
    case "error":
      status = "Refresh failed";
      statusTone = "error";
      action = onRefresh;
      actionLabel = "Retry reading Anki";
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
      dark={dark}
      tint="blue"
      artwork={<AnkiCardsArtwork width={100} height={100} dark={dark} />}
      action={action}
      actionLabel={actionLabel}
    />
  );
}

/** Shows WaniKani's due-now count without inventing completed or remaining-review history. */
function WaniKaniCard({
  home,
  dark,
  onSettings,
  onRefresh,
}: {
  home: HomeDashboardData;
  dark: boolean;
  onSettings: () => void;
  onRefresh: () => void;
}) {
  const {waniKani} = home;
  let status = "Checking…";
  let statusTone: ServiceStatusTone = "neutral";
  let count: number | null = null;
  let detail: string | undefined;
  let action: (() => void) | undefined;
  let actionLabel: string | undefined;

  switch (waniKani.status) {
    case "connected":
      status = waniKani.error ? "Refresh failed" : waniKani.refreshing ? "Refreshing…" : "Connected";
      statusTone = waniKani.error ? "error" : waniKani.refreshing ? "neutral" : "connected";
      count = waniKani.dueNow;
      detail = count == null ? "Review count unavailable" : undefined;
      if (waniKani.error) {
        action = onRefresh;
        actionLabel = "Retry reading WaniKani";
      }
      break;
    case "disconnected":
      status = "Not connected";
      statusTone = "disconnected";
      action = onSettings;
      actionLabel = "Connect to start";
      break;
    case "authRejected":
      status = "Reconnect required";
      statusTone = "error";
      action = onSettings;
      actionLabel = "Reconnect";
      break;
    case "error":
      status = "Unavailable";
      statusTone = "error";
      action = onRefresh;
      actionLabel = "Try again";
      break;
    case "loading":
      status = "Checking…";
      detail = "Loading reviews…";
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
      dark={dark}
      tint="pink"
      artwork={<ToriiArtwork width={100} height={100} dark={dark} />}
      action={action}
      actionLabel={actionLabel}
    />
  );
}

type ServiceStatusTone = "connected" | "disconnected" | "neutral" | "error";

/** Renders the shared title, connection status, one honest count or action, and faded service art. */
function ServiceCard({
  title,
  status,
  statusTone,
  count,
  countLabel,
  detail,
  dark,
  tint,
  artwork,
  action,
  actionLabel,
}: {
  title: string;
  status: string;
  statusTone: ServiceStatusTone;
  count: number | null;
  countLabel: string;
  detail?: string;
  dark: boolean;
  tint: "blue" | "pink";
  artwork: React.ReactNode;
  action?: () => void;
  actionLabel?: string;
}) {
  const palette = tint === "blue"
    ? dark
      ? {from: "#1B2A3E", to: "#263B55", border: "#344A67", text: "#F7FAFF", muted: "#CAD5E7", accent: "#AFC2FF"}
      : {from: "#EEF4FF", to: "#D9E8FF", border: "#CFE0FA", text: "#14142B", muted: "#4B4F6B", accent: "#3B4FE0"}
    : dark
      ? {from: "#2D2330", to: "#3B2A3A", border: "#563D52", text: "#FFF8FC", muted: "#E0CDD9", accent: "#FF9AB9"}
      : {from: "#FFF3FA", to: "#FBE3F1", border: "#F4D3E6", text: "#14142B", muted: "#4B4F6B", accent: "#C42B52"};
  const statusColor = statusTone === "connected"
    ? dark ? "#86EFAC" : "#166534"
    : statusTone === "disconnected" || statusTone === "error"
      ? dark ? "#FDA4AF" : "#991B1B"
      : dark ? "#C3C9D7" : "#4B4F6B";
  const statusDotColor = statusTone === "connected"
    ? "#16A34A"
    : statusTone === "disconnected" || statusTone === "error"
      ? "#DC2626"
      : dark ? "#9AA5B9" : "#8B90A3";
  const visibleDescription = count != null
    ? `${count} ${countLabel}`
    : actionLabel
      ? `${actionLabel} →`
      : detail ?? "";
  const accessibilityLabel = `${title}, ${status}${count != null ? `, ${count} ${countLabel}` : detail ? `, ${detail}` : actionLabel ? `, ${actionLabel}` : ""}`;
  const content = (
    <View style={[styles.serviceCard, {borderColor: palette.border}]}>
      <GradientSurface id={`service-${tint}`} colors={[palette.from, palette.to]} radius={19} />
      <View pointerEvents="none" accessible={false} accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={[styles.serviceArtwork, {opacity: dark ? 0.2 : 0.35}]}>{artwork}</View>
      <View style={styles.serviceContent}>
        <View style={styles.serviceHeader}>
          <Text style={[styles.heavyTitle, styles.serviceTitle, {color: palette.text}]}>{title}</Text>
          <View style={styles.serviceStatusRow}>
            <View style={[styles.serviceStatusDot, {backgroundColor: statusDotColor}]} />
            <Text style={[styles.serviceStatus, {color: statusColor}]}>{status}</Text>
          </View>
        </View>
        <View style={[styles.serviceDetailRow]}>
          {count != null ? (
            <View style={styles.serviceCountLine}>
              <Text numberOfLines={1} adjustsFontSizeToFit minimumFontScale={0.75} style={[styles.serviceCount, {color: palette.accent}]}>{count}</Text>
              <Text style={[styles.serviceCountLabel, {color: palette.muted}]}>{countLabel}</Text>
            </View>
          ) : actionLabel ? (
            <Text style={[styles.serviceActionLabel, {color: palette.accent}]}>
              {actionLabel} →
            </Text>
          ) : (
            <Text style={[styles.serviceDetail, {color: palette.muted}]}>{visibleDescription}</Text>
          )}
        </View>
      </View>
    </View>
  );

  if (!action) return <View accessible accessibilityLabel={accessibilityLabel} style={styles.serviceColumn}>{content}</View>;
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityHint={actionLabel?.startsWith("Retry") || actionLabel === "Try again"
        ? "Retries the service status check"
        : "Opens the related connection or permission settings"}
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
  const {colors, palette} = useTheme();
  const referenceLight = palette === "Gaten" && !isDarkScheme(colors.background);
  const [expanded, setExpanded] = useState(false);
  const {recentLookups, recentLookupsError, recentLookupsStatus} = home;
  const visibleLookups = expanded ? recentLookups : recentLookups.slice(0, 3);

  return (
    <View style={styles.recentSection}>
      <View style={styles.recentHeading}>
        <Text style={[styles.heavyTitle, styles.recentTitle, {color: referenceLight ? "#14142B" : colors.onSurface}]}>Recently looked up</Text>
        {recentLookups.length > 3 ? (
          <PressableScale
            accessibilityRole="button"
            accessibilityLabel={expanded ? "Show fewer recent lookups" : "See all recent lookups"}
            accessibilityState={{expanded}}
            onPress={() => setExpanded((value) => !value)}
            style={styles.seeAllButton}
          >
            <Text style={[typeScale.labelMedium, {color: colors.primary}]}>
              {expanded ? "Show less" : "See all"}
            </Text>
          </PressableScale>
        ) : null}
      </View>
      {recentLookups.length > 0 ? (
        <View style={[styles.recentList, {backgroundColor: colors.surface}]}>
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
          icon={<ActivityIndicator size="small" color={colors.primary} />}
          title="Loading recent lookups…"
          message=""
        />
      ) : recentLookupsStatus === "error" ? (
        <EmptyRecent
          icon={<Icon name="error" color={colors.onSurfaceVariant} />}
          title="Recent lookups could not be loaded"
          message={recentLookupsError ?? "Try loading them again."}
          action={{label: "Try again", onPress: onRefresh}}
        />
      ) : (
        <EmptyRecent
          icon={<Svg width={18} height={18} viewBox="0 0 24 24"><Circle cx={10.5} cy={10.5} r={6.5} stroke={isDarkScheme(colors.background) ? colors.onSurfaceVariant : "#3A3A3F"} strokeWidth={2.6} fill="none" /><Path d="m16 16 4.5 4.5" stroke={isDarkScheme(colors.background) ? colors.onSurfaceVariant : "#3A3A3F"} strokeWidth={2.6} strokeLinecap="round" /></Svg>}
          title="No recent lookups yet"
          message="Words you search will appear here."
        />
      )}
      {recentLookups.length > 0 && recentLookupsError ? (
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel="Refresh recent lookups"
          onPress={onRefresh}
          style={styles.recentError}
        >
          <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>Could not refresh recent lookups. Try again.</Text>
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
  const reading = hit.kana[0] ?? "";
  const gloss = hit.glosses[0] ?? "";
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={`Open ${hit.primary}${reading ? `, ${reading}` : ""}${gloss ? `, ${gloss}` : ""}`}
      onPress={onPress}
      style={[
        styles.lookupRow,
        !last && {borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.outlineVariant},
      ]}
    >
      <View style={[styles.lookupGlyph, {backgroundColor: colors.surfaceVariant}]}>
        <Text numberOfLines={1} style={[styles.heavyTitle, styles.kanjiGlyph, {color: colors.onSurface}]}>
          {hit.primary.slice(0, 2)}
        </Text>
      </View>
      <View style={styles.lookupWord}>
        <Text numberOfLines={1} style={[styles.heavyTitle, styles.lookupTitle, {color: colors.onSurface}]}>
          {hit.primary}
        </Text>
        {reading ? (
          <Text numberOfLines={1} style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
            {reading}
          </Text>
        ) : null}
      </View>
      {gloss ? (
        <Text numberOfLines={2} style={[typeScale.bodySmall, styles.lookupGloss, {color: colors.onSurfaceVariant}]}>
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
}: {
  icon: React.ReactNode;
  title: string;
  message: string;
  action?: {label: string; onPress: () => void};
}) {
  const {colors, palette} = useTheme();
  const referenceLight = palette === "Gaten" && !isDarkScheme(colors.background);
  return (
    <View style={styles.emptyRecent}>
      <View style={[styles.emptyIcon, {backgroundColor: referenceLight ? "#EDEDEF" : colors.surfaceVariant}]}>{icon}</View>
      <Text style={[styles.heavyTitle, styles.emptyTitle, {color: referenceLight ? "#14142B" : colors.onSurface}]}>{title}</Text>
      {message ? <Text style={[typeScale.bodySmall, styles.emptyMessage, {color: referenceLight ? "#6B6B72" : colors.onSurfaceVariant}]}>{message}</Text> : null}
      {action ? (
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel={action.label}
          onPress={action.onPress}
          style={styles.emptyAction}
        >
          <Text style={[typeScale.labelLarge, {color: colors.primary}]}>{action.label}</Text>
        </PressableScale>
      ) : null}
    </View>
  );
}

function GradientSurface({
  id,
  colors,
  radius,
  vertical = false,
}: {
  id: string;
  colors: string[];
  radius: number;
  vertical?: boolean;
}) {
  return (
    <Svg pointerEvents="none" style={StyleSheet.absoluteFill}>
      <Defs>
        <SvgLinearGradient id={id} x1="0" y1="0" x2={vertical ? "0" : "1"} y2="1">
          <Stop offset="0" stopColor={colors[0]} />
          <Stop offset="1" stopColor={colors[1]} />
        </SvgLinearGradient>
      </Defs>
      <Rect width="100%" height="100%" rx={radius} fill={`url(#${id})`} />
    </Svg>
  );
}

function isDarkScheme(background: string): boolean {
  return Number.parseInt(background.slice(1, 3), 16) < 128;
}

const styles = StyleSheet.create({
  dashboard: {width: "100%", gap: spacing.m},
  heavyTitle: {fontFamily: "Inter", fontWeight: "700"},
  streakCard: {
    width: "100%",
    maxWidth: 400,
    minHeight: 120,
    alignSelf: "center",
    paddingHorizontal: 12,
    paddingVertical: 12,
    borderRadius: 20,
    overflow: "hidden",
    justifyContent: "space-between",
  },
  streakTop: {
    minHeight: 38,
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  streakCopy: {flex: 1, minWidth: 0},
  streakTitle: {fontFamily: "Inter", fontSize: 16, lineHeight: 20, fontWeight: "700", flexShrink: 1},
  streakMessage: {fontFamily: "Inter", fontSize: 12, lineHeight: 16, fontWeight: "400", marginTop: 2},
  retryLink: {alignSelf: "flex-start", minHeight: 36, justifyContent: "center"},
  streakTotal: {minWidth: 42, alignItems: "flex-end", justifyContent: "center"},
  streakTotalValue: {fontFamily: "Inter", fontSize: 15, lineHeight: 18, fontWeight: "700"},
  streakTotalCaption: {fontFamily: "Inter", fontSize: 10, lineHeight: 12, fontWeight: "400"},
  weekRow: {flexDirection: "row", justifyContent: "space-between", marginTop: 10},
  weekDay: {flex: 1, minWidth: 0, alignItems: "center", gap: 4},
  dayLabel: {fontFamily: "Inter", fontSize: 11, lineHeight: 14, fontWeight: "500"},
  todayLabel: {fontWeight: "700"},
  serviceRow: {flexDirection: "row", gap: 12, alignItems: "stretch"},
  serviceColumn: {flex: 1, minWidth: 0},
  serviceCard: {
    flex: 1,
    minHeight: 112,
    borderWidth: 1,
    borderRadius: 20,
    overflow: "hidden",
    paddingHorizontal: 12,
    paddingVertical: 11,
  },
  serviceContent: {zIndex: 3},
  serviceHeader: {minHeight: 37},
  serviceTitle: {fontFamily: "Inter", fontSize: 16, lineHeight: 22, fontWeight: "700"},
  serviceStatusRow: {minHeight: 15, flexDirection: "row", alignItems: "center", gap: 6},
  serviceStatusDot: {width: 6, height: 6, borderRadius: 3},
  serviceStatus: {fontFamily: "Inter", fontSize: 11, lineHeight: 15, fontWeight: "600", flexShrink: 1},
  serviceDetailRow: {minHeight: 44, marginTop: 6, justifyContent: "center"},
  serviceCountLine: {flexDirection: "row", alignItems: "baseline", gap: 5},
  serviceCount: {flexShrink: 1, fontFamily: "Inter", fontSize: 24, lineHeight: 28, fontWeight: "700"},
  serviceCountLabel: {flexShrink: 1, fontFamily: "Inter", fontSize: 11, lineHeight: 14, fontWeight: "400"},
  serviceActionLabel: {fontFamily: "Inter", fontSize: 13, lineHeight: 17, fontWeight: "600"},
  serviceDetail: {fontFamily: "Inter", fontSize: 12, lineHeight: 16, fontWeight: "500"},
  serviceArtwork: {position: "absolute", end: 0, bottom: 0, width: 100, height: 100, zIndex: 0},
  studyButton: {
    minHeight: 52,
    borderRadius: 26,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: spacing.l,
    overflow: "hidden",
  },
  studyButtonDisabled: {opacity: 0.9},
  studyButtonLabel: {flex: 1, textAlign: "center", fontFamily: "Inter", fontSize: 18, lineHeight: 24, fontWeight: "600", letterSpacing: 0},
  studyButtonArrow: {position: "absolute", end: 20},
  recentSection: {marginTop: 8},
  recentHeading: {
    minHeight: 28,
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: spacing.s,
  },
  recentTitle: {fontSize: 20, lineHeight: 28, flex: 1, flexShrink: 1},
  seeAllButton: {minHeight: 40, minWidth: 48, alignItems: "center", justifyContent: "center", paddingHorizontal: spacing.xs},
  recentList: {borderRadius: 14, overflow: "hidden"},
  lookupRow: {
    minHeight: 58,
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.xs,
  },
  lookupGlyph: {width: 38, minHeight: 38, borderRadius: shape.small, alignItems: "center", justifyContent: "center", paddingHorizontal: 2},
  kanjiGlyph: {fontSize: 15, lineHeight: 20},
  lookupWord: {flex: 1, minWidth: 0},
  lookupTitle: {fontSize: 14, lineHeight: 19},
  lookupGloss: {maxWidth: "34%", textAlign: "right"},
  emptyRecent: {alignItems: "center", paddingTop: 4, paddingBottom: 16},
  emptyIcon: {width: 32, height: 32, borderRadius: 16, marginBottom: 10, alignItems: "center", justifyContent: "center"},
  emptyTitle: {fontSize: 16, lineHeight: 22, fontWeight: "600", textAlign: "center"},
  emptyMessage: {fontFamily: "Inter", fontSize: 14, lineHeight: 20, marginTop: 2, textAlign: "center"},
  emptyAction: {minHeight: 40, justifyContent: "center", paddingHorizontal: spacing.m},
  recentError: {minHeight: 44, justifyContent: "center", alignItems: "center"},
});
