/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Analytics: one dashboard for the two services the reader studies with — AnkiDroid and WaniKani —
 * with no source switch anywhere. Every card names the source it comes from, so a number is never
 * ambiguous, and nothing is ever combined into a score that neither service reports.
 *
 * The screen is presentational: the numbers come from `useAnkiStats` and `useWaniKaniStats` (each
 * read through the bridge when the dashboard becomes the screen being looked at), and nothing here
 * writes to either service. A service that is not set up shows one compact card in its own place —
 * the connect action and the reason, in context — rather than a wall over the page, and the other
 * service's cards carry on underneath it.
 *
 * Drawing: plain views for the bars (an absolutely positioned, childless bar may animate its width
 * without re-running layout for its siblings) and `react-native-svg` for the donut, which is already
 * installed. No charting dependency, no gradients, and colour is never the only key: every bar, arc
 * angle and stage carries its own text label and value.
 *
 * Two chart conventions hold everywhere here. The axis is coarse enough to be read on a phone — the
 * WaniKani forecast is summed into equal five-hour blocks rather than squeezed into 25 hourly labels —
 * and a bucket the source would not answer is never drawn as a zero: it is a dashed bar with a dash in
 * place of the figure, and the note under the chart counts the buckets that are missing.
 */
import React, {useEffect, useMemo} from "react";
import {
  ScrollView,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import Animated, {
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {useIsFocused} from "@react-navigation/native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import Svg, {Circle} from "react-native-svg";
import {shape, spacing, typeScale} from "../tokens";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme, type Theme} from "../theme";
import {
  useAnkiStats,
  type AnkiDailyPoint,
  type AnkiStatsFailure,
} from "./useAnkiStats";
import {useWaniKaniStats} from "./useWaniKaniStats";
import type {SettingsPageName} from "../navigation";

/** How long a bar takes to reach a new value. Under the platform's own transition, over the reader's. */
const CHART_MS = 200;

/** The donut's own geometry: a 132dp ring, 14dp thick, with the figure inside it. */
const DONUT_SIZE = 132;
const DONUT_THICKNESS = 14;
const DONUT_RADIUS = (DONUT_SIZE - DONUT_THICKNESS) / 2;
const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

/**
 * The quiet spectrum the SRS arcs and their legend dots are drawn from — the theme's own roles, in
 * the order a card climbs them: the accent first, then its tonal neighbours, then the quietest
 * outline for a burned card. Derived, never hardcoded: the accent changing changes the whole ring,
 * and every stage is named and counted beside its colour.
 */
function stageColors(theme: Theme): string[] {
  const {colors} = theme;
  return [
    colors.primary,
    colors.tertiary,
    colors.secondary,
    colors.primaryContainer,
    colors.outlineVariant,
  ];
}

/**
 * When the numbers were read, as the clock on the device reads it: "Updated 14:03". An absolute time
 * cannot claim to be fresher than it is, so no timer has to sit behind the label; a read from another
 * day is named, and an afternoon is not mistaken for this morning.
 */
function updatedLabel(at: number | null): string | null {
  if (at == null) {
    return null;
  }
  const when = new Date(at);
  const now = new Date();
  const time = `${String(when.getHours()).padStart(2, "0")}:${String(when.getMinutes()).padStart(2, "0")}`;
  if (when.toDateString() === now.toDateString()) {
    return `Updated ${time}`;
  }
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (when.toDateString() === yesterday.toDateString()) {
    return `Updated ${time} yesterday`;
  }
  const month = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][
    when.getMonth()
  ];
  return `Updated ${time} on ${when.getDate()} ${month}`;
}

/** One number the dashboard states: the source's name, the figure, and what it counts. */
function MetricCard({
  source,
  figure,
  caption,
  muted = false,
}: {
  source: string;
  figure: string;
  caption: string;
  muted?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.metricCard, {backgroundColor: colors.surface}]}>
      <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>{source}</Text>
      <Text
        style={[
          typeScale.displaySmall,
          styles.metricFigure,
          {color: muted ? colors.onSurfaceVariant : colors.onSurface},
        ]}
      >
        {figure}
      </Text>
      <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>{caption}</Text>
    </View>
  );
}

/** A card with the source's name on it, and whatever it has to say. */
function SourceCard({
  source,
  title,
  children,
}: {
  source: string;
  title: string;
  children: React.ReactNode;
}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.card, {backgroundColor: colors.surface}]}>
      <View style={styles.cardHead}>
        <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>{source}</Text>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
      </View>
      {children}
    </View>
  );
}

/** One bar of a chart: its axis label, its value, and the whole fact in words for a screen reader. */
export interface ChartPoint {
  /** What the axis calls this bar. */
  label: string;
  /** What it counts, or null when the source would not answer this bucket. */
  value: number | null;
  /** The bar said in full: its span, and its value — or that there is no reading for it. */
  spoken: string;
  /** The bar the eye should start from. */
  emphasis?: boolean;
}

/**
 * One bar of a bar chart: an absolutely positioned, childless bar inside its own track, which is the
 * one shape whose width may be animated without a layout pass for anything else. Three states, drawn
 * three ways, because they are three different facts: a value, a counted zero (an empty track), and a
 * bucket the source would not answer (a dashed track and a dash where the figure would be).
 *
 * The column is one accessibility node: a screen reader hears the span and the value, not a number, a
 * bar and a label read out separately.
 */
function Bar({point, max}: {point: ChartPoint; max: number}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const track = useSharedValue(0);
  const value = point.value;
  const unknown = value == null;
  const share = value == null || max <= 0 ? 0 : Math.min(1, value / max);

  useEffect(() => {
    track.value = reducedMotion
      ? share
      : withTiming(share, {duration: CHART_MS});
  }, [share, reducedMotion, track]);

  const barStyle = useAnimatedStyle(() => ({width: `${track.value * 100}%`}));
  const fill = point.emphasis ? colors.primary : colors.primaryContainer;

  return (
    <View accessible accessibilityLabel={point.spoken} style={styles.barColumn}>
      <Text
        accessible={false}
        numberOfLines={1}
        style={[typeScale.labelSmall, styles.barValue, {color: colors.onSurfaceVariant}]}
      >
        {value == null ? "–" : String(value)}
      </Text>
      <View
        style={[
          styles.barTrack,
          {backgroundColor: colors.surfaceContainerHighest},
          unknown && [styles.barUnknown, {borderColor: colors.outlineVariant}],
        ]}
      >
        <Animated.View
          style={[styles.barFill, {backgroundColor: fill}, barStyle]}
          accessible={false}
        />
      </View>
      <Text
        accessible={false}
        numberOfLines={1}
        style={[typeScale.labelSmall, styles.barLabel, {color: colors.onSurfaceVariant}]}
      >
        {point.label}
      </Text>
    </View>
  );
}

/**
 * A row of bars under its axis labels, with the one note that belongs beneath them. The note is the
 * caller's, because only the caller knows what a missing bar means: a zero the source counted, or a
 * bucket it would not answer — and those must never read the same on screen.
 */
function BarChart({points, note}: {points: ChartPoint[]; note: string | null}) {
  const {colors} = useTheme();
  const max = points.reduce((peak, point) => Math.max(peak, point.value ?? 0), 0);
  return (
    <View>
      <View style={styles.barRow}>
        {points.map((point, index) => (
          <Bar key={`${point.label}-${index}`} point={point} max={max} />
        ))}
      </View>
      {note != null && (
        <Text style={[typeScale.bodySmall, styles.chartNote, {color: colors.onSurfaceVariant}]}>
          {note}
        </Text>
      )}
    </View>
  );
}

/**
 * AnkiDroid's own study days as chart points, and the sentence for whatever is missing from them.
 * AnkiDroid can refuse a day's search, and a refused day is not a day without answers: the figure is
 * a dash, the bar is dashed, and the note counts the days that could not be read — so "no cards" is
 * only ever said about days that really did answer zero.
 */
function ankiDailyChart(
  daily: AnkiDailyPoint[]
): {points: ChartPoint[]; note: string | null} {
  const points: ChartPoint[] = daily
    .slice()
    .sort((left, right) => right.daysAgo - left.daysAgo)
    .map((day) => {
      const counted = day.cards == null ? null : `${day.cards} card${day.cards === 1 ? "" : "s"}`;
      const span = day.daysAgo === 0 ? "today" : `${day.daysAgo} day${day.daysAgo === 1 ? "" : "s"} ago`;
      return {
        label: day.daysAgo === 0 ? "Today" : `${day.daysAgo}d`,
        value: day.cards,
        spoken: counted == null ? `${span}: no reading` : `${span}: ${counted} studied`,
      };
    });
  const unknown = points.filter((point) => point.value == null).length;
  const total = points.reduce((sum, point) => sum + (point.value ?? 0), 0);
  const note =
    points.length === 0 || unknown === points.length
      ? "AnkiDroid did not answer the daily counts, so the last seven days are unavailable."
      : unknown > 0
        ? `${unknown === 1 ? "One day" : `${unknown} days`} of these seven could not be read, so this chart is incomplete.`
        : total === 0
          ? "No cards answered yet in the last seven days."
          : null;
  return {points, note};
}

/** The hours one forecast bar covers: WaniKani answers 25 hourly buckets, and five per bar fit a phone. */
const FORECAST_BLOCK_HOURS = 5;

/**
 * WaniKani's 25 hourly buckets as five equal blocks, and the sentence that says so. Every bucket lands
 * in exactly one block, so no future count is dropped: each bar is its block's own sum, over the same
 * span as every other bar, and the first bar — the hour the reader is in — is the one the eye starts
 * from. The bars are five hours wide rather than one because 25 labels cannot be read on a phone.
 */
function forecastChart(buckets: {count: number}[]): {points: ChartPoint[]; note: string | null} {
  const points: ChartPoint[] = [];
  for (let start = 0; start < buckets.length; start += FORECAST_BLOCK_HOURS) {
    const block = buckets.slice(start, start + FORECAST_BLOCK_HOURS);
    const count = block.reduce((sum, bucket) => sum + bucket.count, 0);
    const last = start + block.length - 1;
    const span =
      start === 0
        ? last === 0
          ? "the hour you are in"
          : `now to ${last} hours from now`
        : `${start} to ${last} hours from now`;
    points.push({
      label: start === 0 ? "Now" : `+${start}h`,
      value: count,
      spoken: `${span}: ${count} review${count === 1 ? "" : "s"}`,
      emphasis: start === 0,
    });
  }
  const total = buckets.reduce((sum, bucket) => sum + bucket.count, 0);
  const note =
    buckets.length === 0
      ? "WaniKani did not answer the hourly forecast."
      : total === 0
        ? "Nothing is scheduled in the next 24 hours."
        : `Each bar is ${FORECAST_BLOCK_HOURS} hours, added up from WaniKani's own 25 hourly buckets.`;
  return {points, note};
}

/**
 * A donut of counts with its own legend: each arc is one stage, and every stage is named and counted
 * in the list under it, so the ring never has to be read by colour. With nothing counted, the ring is
 * an empty track and the list is empty: a legitimate zero rather than a drawn placeholder.
 */
function Donut({
  slices,
  centre,
  centreCaption,
  accessibilityLabel,
}: {
  slices: {label: string; count: number}[];
  centre: string;
  centreCaption: string;
  accessibilityLabel: string;
}) {
  const theme = useTheme();
  const {colors} = theme;
  const palette = stageColors(theme);
  const total = slices.reduce((sum, slice) => sum + slice.count, 0);

  let travelled = 0;
  const arcs = slices
    .map((slice, index) => {
      const share = total === 0 ? 0 : slice.count / total;
      const arc = {
        key: slice.label,
        length: share * DONUT_CIRCUMFERENCE,
        offset: travelled,
        color: palette[index % palette.length],
      };
      travelled += share * DONUT_CIRCUMFERENCE;
      return arc;
    })
    .filter((arc) => arc.length > 0);

  return (
    <View style={styles.donutRow}>
      <View accessible accessibilityLabel={accessibilityLabel}>
        <Svg width={DONUT_SIZE} height={DONUT_SIZE}>
          <Circle
            cx={DONUT_SIZE / 2}
            cy={DONUT_SIZE / 2}
            r={DONUT_RADIUS}
            stroke={colors.surfaceContainerHighest}
            strokeWidth={DONUT_THICKNESS}
            fill="none"
          />
          {arcs.map((arc) => (
            <Circle
              key={arc.key}
              cx={DONUT_SIZE / 2}
              cy={DONUT_SIZE / 2}
              r={DONUT_RADIUS}
              stroke={arc.color}
              strokeWidth={DONUT_THICKNESS}
              strokeLinecap="round"
              fill="none"
              strokeDasharray={`${arc.length} ${DONUT_CIRCUMFERENCE - arc.length}`}
              strokeDashoffset={-arc.offset}
              // The ring starts at twelve o'clock rather than at three.
              transform={`rotate(-90 ${DONUT_SIZE / 2} ${DONUT_SIZE / 2})`}
            />
          ))}
        </Svg>
        <View style={styles.donutCentre} pointerEvents="none">
          <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>{centre}</Text>
          <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>
            {centreCaption}
          </Text>
        </View>
      </View>
      <View style={styles.legend}>
        {slices.map((slice, index) => (
          <View key={slice.label} style={styles.legendRow}>
            <View
              style={[
                styles.legendDot,
                {backgroundColor: palette[index % palette.length]},
              ]}
            />
            <Text
              numberOfLines={1}
              style={[typeScale.bodySmall, styles.legendLabel, {color: colors.onSurface}]}
            >
              {slice.label}
            </Text>
            <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>
              {slice.count}
            </Text>
          </View>
        ))}
      </View>
    </View>
  );
}

/**
 * A service that is not set up, or whose numbers could not be read: the reason and the one action,
 * in the place its own cards would have been. It never covers the other service's cards.
 */
function SourceNotice({
  source,
  title,
  body,
  action,
}: {
  source: string;
  title: string;
  body: string;
  action?: {label: string; onPress: () => void};
}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.card, {backgroundColor: colors.surface}]}>
      <View style={styles.cardHead}>
        <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>{source}</Text>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{title}</Text>
      </View>
      <Text style={[typeScale.bodySmall, styles.noticeBody, {color: colors.onSurfaceVariant}]}>
        {body}
      </Text>
      {action != null && (
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel={action.label}
          onPress={action.onPress}
          style={[styles.noticeAction, {backgroundColor: colors.primary}]}
        >
          <Text style={[typeScale.labelLarge, {color: colors.onPrimary}]}>{action.label}</Text>
        </PressableScale>
      )}
    </View>
  );
}

/**
 * What each reason AnkiDroid's numbers are missing says, in the reader's words, and which of the two
 * actions clears it: the Anki settings page (where the grant is asked for and the deck is chosen) or
 * a plain re-read. Installing AnkiDroid happens outside Gaten, so that one is not told to anyone
 * whose failure is anything else.
 */
const ANKI_NOTICES: Record<AnkiStatsFailure, {title: string; body: string; settings?: boolean}> = {
  NOT_INSTALLED: {
    title: "AnkiDroid is not installed",
    body: "Install AnkiDroid from your app store, then let Gaten read the deck you study from. Nothing is read until then.",
  },
  PERMISSION: {
    title: "Gaten cannot read AnkiDroid",
    body: "AnkiDroid has not granted access to its collection. Grant it there — Anki settings has the switch — and these numbers appear.",
    settings: true,
  },
  NOT_SET_UP: {
    title: "AnkiDroid has no collection yet",
    body: "Open AnkiDroid once so it creates its collection folder, then read the numbers again.",
  },
  NOT_FOUND: {
    title: "The deck Gaten reads is not in AnkiDroid",
    body: "The deck Gaten is set to study is no longer in the collection. Choose another one in Anki settings.",
    settings: true,
  },
  MALFORMED: {
    title: "AnkiDroid answered something Gaten could not read",
    body: "Its collection answered in a shape Gaten does not know. Try again — if it keeps happening, update AnkiDroid.",
  },
  PROVIDER: {
    title: "AnkiDroid would not open its collection",
    body: "Its collection refused the read. Try again in a moment.",
  },
};

/**
 * The card AnkiDroid's half of the dashboard shows while its numbers are missing: the one reason, and
 * the one action. A read that has not answered yet says so — it never borrows the words of a failure,
 * and a failure never says the wrong thing about it.
 */
function AnkiNotice({
  failure,
  onOpenSettings,
  onRetry,
}: {
  failure: AnkiStatsFailure | null;
  onOpenSettings: (page: SettingsPageName) => void;
  onRetry: () => void;
}) {
  if (failure == null) {
    return (
      <SourceNotice
        source="Anki"
        title="Reading AnkiDroid…"
        body="The counts and the last seven days come from AnkiDroid's own collection."
      />
    );
  }
  const notice = ANKI_NOTICES[failure];
  return (
    <SourceNotice
      source="Anki"
      title={notice.title}
      body={notice.body}
      action={
        notice.settings
          ? {label: "Anki settings", onPress: () => onOpenSettings("anki")}
          : {label: "Try again", onPress: onRetry}
      }
    />
  );
}

/** The quiet line under the heading: how fresh the numbers are, and the one refresh. */
function Header({
  updated,
  busy,
  onRefresh,
}: {
  updated: string | null;
  busy: boolean;
  onRefresh: () => void;
}) {
  const {colors} = useTheme();
  return (
    <View style={styles.headerRow}>
      <View style={styles.headerText}>
        <Text style={[typeScale.displaySmall, {color: colors.onSurface}]}>Analytics</Text>
        <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
          {updated ?? "Reading the numbers…"}
        </Text>
      </View>
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel="Refresh the numbers"
        onPress={onRefresh}
        style={[styles.refresh, {backgroundColor: colors.surfaceContainerHigh}]}
      >
        <Icon name="refresh" size={22} color={busy ? colors.onSurfaceVariant : colors.primary} />
      </PressableScale>
    </View>
  );
}

/** One deck the reader can look at: the chip that selects it, and the counts it brings. */
function DeckPicker({
  decks,
  selectedId,
  onSelect,
}: {
  decks: {id: number; name: string}[];
  selectedId: number | null;
  onSelect: (deckId: number) => void;
}) {
  const {colors} = useTheme();
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={styles.deckRow}
    >
      {decks.map((deck) => {
        const selected = deck.id === selectedId;
        return (
          <PressableScale
            key={deck.id}
            accessibilityRole="button"
            accessibilityState={{selected}}
            accessibilityLabel={`Show ${deck.name}`}
            onPress={() => onSelect(deck.id)}
            style={[
              styles.deckChip,
              {
                backgroundColor: selected ? colors.primaryContainer : colors.surfaceContainerHigh,
              },
            ]}
          >
            <Text
              numberOfLines={1}
              style={[
                typeScale.labelLarge,
                {color: selected ? colors.onPrimaryContainer : colors.onSurfaceVariant},
              ]}
            >
              {deck.name}
            </Text>
          </PressableScale>
        );
      })}
    </ScrollView>
  );
}

/**
 * The dashboard. `onOpenSettingsPage` is the shell's own way to a settings page: the connect actions
 * on the cards hand the reader to the page that owns the token or the grant, and coming back reads
 * the numbers again.
 */
export function AnalyticsScreen({
  onOpenSettingsPage,
}: {
  onOpenSettingsPage?: (page: SettingsPageName) => void;
}) {
  const theme = useTheme();
  const {colors} = theme;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const active = useIsFocused();
  const anki = useAnkiStats(active);
  const wanikani = useWaniKaniStats(active);

  const ankiData = anki.data;
  const wkData = wanikani.snapshot;

  const rows = width >= 640 ? 2 : 1;

  // The freshest of the two reads, as an absolute clock time. No timer sits behind the label: it says
  // when the numbers on screen were read, and a resumed app re-reads them through the hooks.
  const updatedAt = useMemo(() => {
    const times = [ankiData?.updatedAt ?? null, wkData?.fetchedAt ?? null].filter(
      (at): at is number => at != null
    );
    return times.length === 0 ? null : Math.max(...times);
  }, [ankiData?.updatedAt, wkData?.fetchedAt]);

  const ankiRemaining = ankiData?.globalRemaining ?? null;
  const deck = ankiData?.selectedDeck ?? null;
  const selectedDeckRow =
    ankiData != null && deck != null
      ? ankiData.decks.find((candidate) => candidate.id === deck.id) ?? null
      : null;
  const stages = wkData?.stages ?? [];
  const stageTotal = stages.reduce((sum, stage) => sum + stage.count, 0);

  const openSettings = (page: SettingsPageName) => {
    onOpenSettingsPage?.(page);
  };

  const refresh = () => {
    anki.refresh();
    wanikani.refresh();
  };

  const busy = anki.refreshing || wanikani.status === "refreshing" || wanikani.status === "loading";

  return (
    <View style={[styles.root, {backgroundColor: colors.background}]}>
      <ScrollView
        style={{marginTop: insets.top}}
        contentContainerStyle={[styles.content, {paddingBottom: spacing.xxl + insets.bottom}]}
        keyboardShouldPersistTaps="handled"
      >
        <Header updated={updatedLabel(updatedAt)} busy={busy} onRefresh={refresh} />

        {/* The day's shape, in four numbers: two sources, each labelled, nothing blended. */}
        <View style={[styles.metrics, {flexDirection: rows === 2 ? "row" : "column"}]}>
          <MetricCard
            source="Anki"
            figure={ankiData?.studiedToday != null ? String(ankiData.studiedToday) : "—"}
            caption="cards studied today"
            muted={ankiData?.studiedToday == null}
          />
          <MetricCard
            source="Anki"
            figure={ankiRemaining != null ? String(ankiRemaining) : "—"}
            caption="cards remaining"
            muted={ankiRemaining == null}
          />
          <MetricCard
            source="WaniKani"
            figure={wkData != null ? String(wkData.level) : "—"}
            caption={
              wkData != null && wkData.maxLevelGranted > 0
                ? `of ${wkData.maxLevelGranted} reachable levels`
                : "current level"
            }
            muted={wkData == null}
          />
          <MetricCard
            source="WaniKani"
            figure={wkData != null ? String(wkData.dueNow) : "—"}
            caption="reviews available now"
            muted={wkData == null}
          />
        </View>

        {/* Anki: the last seven of AnkiDroid's own study days, then the deck the reader picked.
            Until a read answers there is no card to draw, only the reason — and "not connected" is
            never said on the strength of a read that has simply not come back yet. */}
        {ankiData == null ? (
          <AnkiNotice
            failure={anki.failure}
            onOpenSettings={openSettings}
            onRetry={anki.refresh}
          />
        ) : (
          <>
            <SourceCard source="Anki" title="Last 7 days">
              <BarChart {...ankiDailyChart(ankiData.daily)} />
            </SourceCard>

            {ankiData.decks.length > 0 ? (
              <SourceCard source="Anki" title="Decks">
                <DeckPicker
                  decks={ankiData.decks}
                  selectedId={deck?.id ?? null}
                  onSelect={anki.selectDeck}
                />
                <View style={styles.deckCounts}>
                  <DeckCount label="new" value={selectedDeckRow?.newCount ?? null} />
                  <DeckCount label="learning" value={selectedDeckRow?.learnCount ?? null} />
                  <DeckCount label="review" value={selectedDeckRow?.reviewCount ?? null} />
                  <DeckCount label="remaining" value={selectedDeckRow?.remaining ?? null} />
                </View>
                <Text
                  style={[typeScale.bodySmall, styles.chartNote, {color: colors.onSurfaceVariant}]}
                >
                  {ankiData.totalCards != null && ankiData.matureCards != null
                    ? `${ankiData.totalCards} cards in ${deck?.name ?? "this deck"} — ${ankiData.matureCards} with an interval of 21 days or more.`
                    : `Counts are for ${deck?.name ?? "the selected deck"}.`}
                </Text>
              </SourceCard>
            ) : (
              // A collection with no decks in it is a real state of AnkiDroid and not an error: it
              // says what to do about it rather than drawing an empty deck list with no explanation.
              <SourceNotice
                source="Anki"
                title="No decks yet"
                body="AnkiDroid's collection has no decks in it yet. Add one there, then read the numbers again."
                action={{label: "Try again", onPress: anki.refresh}}
              />
            )}
          </>
        )}

        {anki.failure != null && ankiData != null && (
          <Text style={[typeScale.bodySmall, styles.staleNote, {color: colors.onSurfaceVariant}]}>
            The last read of AnkiDroid did not finish — these are the numbers from before it.
          </Text>
        )}

        {/* WaniKani: what is waiting, what is coming, and where the items are sitting. Nothing here
            draws a zero before a read has finished: an account that has not answered says it is
            being read, and only real, complete numbers may say "nothing is scheduled". */}
        {wanikani.status === "needsToken" || wanikani.status === "authRejected" ? (
          <SourceNotice
            source="WaniKani"
            title="WaniKani is not connected"
            body={
              wanikani.status === "authRejected"
                ? "WaniKani refused the stored token. Connect again with a current one to see your numbers here."
                : "Connect your WaniKani token to see the queue, the next 24 hours and your SRS stages."
            }
            action={{
              label: "Connect WaniKani",
              onPress: () => openSettings("wanikani"),
            }}
          />
        ) : wkData == null ? (
          wanikani.status === "failed" ? (
            <SourceNotice
              source="WaniKani"
              title="WaniKani could not be read"
              body={
                wanikani.message ??
                (wanikani.retryAfterSeconds != null
                  ? `WaniKani asked Gaten to wait ${wanikani.retryAfterSeconds}s before asking again.`
                  : "The numbers could not be fetched. Try again in a moment.")
              }
              action={
                wanikani.retryAfterSeconds == null
                  ? {label: "Try again", onPress: wanikani.refresh}
                  : undefined
              }
            />
          ) : (
            <SourceNotice
              source="WaniKani"
              title="Reading WaniKani…"
              body="The queue, the next 24 hours and the SRS stages come from your own WaniKani account."
            />
          )
        ) : (
          <>
            <SourceCard source="WaniKani" title="Next 24 hours">
              <BarChart {...forecastChart(wkData.forecast)} />
            </SourceCard>

            <SourceCard source="WaniKani" title="SRS stages">
              <Donut
                slices={stages.map((stage) => ({label: stage.label, count: stage.count}))}
                centre={String(stageTotal)}
                // Not "cards": these are WaniKani's own items, and the total includes the stages
                // nothing has reached yet and the ones already burned.
                centreCaption="total items"
                accessibilityLabel={
                  stages.length > 0
                    ? `SRS stages, ${stageTotal} items in total — ${stages
                        .map((stage) => `${stage.label}: ${stage.count}`)
                        .join(", ")}`
                    : "No SRS stages were read."
                }
              />
            </SourceCard>
          </>
        )}

        {wanikani.stale && wkData != null && (
          <Text style={[typeScale.bodySmall, styles.staleNote, {color: colors.onSurfaceVariant}]}>
            {wanikani.retryAfterSeconds != null
              ? `WaniKani asked Gaten to wait ${wanikani.retryAfterSeconds}s — these are the last complete numbers.`
              : "The last WaniKani read did not finish — these are the last complete numbers."}
          </Text>
        )}
      </ScrollView>
    </View>
  );
}

/** One of a deck's own counts, under the selector. */
function DeckCount({label, value}: {label: string; value: number | null}) {
  const {colors} = useTheme();
  return (
    <View style={styles.deckCount}>
      <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>
        {value != null ? String(value) : "—"}
      </Text>
      <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  content: {
    width: "100%",
    maxWidth: 840,
    alignSelf: "center",
    paddingHorizontal: spacing.xl,
    gap: spacing.l,
  },
  headerRow: {
    flexDirection: "row",
    alignItems: "flex-start",
    marginTop: spacing.xxl,
    gap: spacing.l,
  },
  headerText: {flex: 1},
  refresh: {
    width: 48,
    height: 48,
    borderRadius: shape.large,
    alignItems: "center",
    justifyContent: "center",
  },
  metrics: {
    gap: spacing.l,
    flexWrap: "wrap",
  },
  metricCard: {
    flex: 1,
    minWidth: 148,
    borderRadius: shape.large,
    padding: spacing.l,
    gap: spacing.xs,
  },
  metricFigure: {marginTop: spacing.xs},
  card: {
    borderRadius: shape.large,
    padding: spacing.l,
    gap: spacing.m,
  },
  cardHead: {gap: spacing.xs},
  barRow: {
    flexDirection: "row",
    alignItems: "flex-end",
    gap: spacing.s,
    height: 96,
  },
  barColumn: {flex: 1, height: "100%", justifyContent: "flex-end", gap: spacing.s},
  barTrack: {
    flex: 1,
    borderRadius: shape.small,
    justifyContent: "flex-end",
    overflow: "hidden",
  },
  /** A bucket the source would not answer: dashed, so it cannot be read as a counted zero. */
  barUnknown: {borderWidth: 1, borderStyle: "dashed"},
  barValue: {textAlign: "center"},
  barFill: {width: "0%", height: "100%"},
  barLabel: {textAlign: "center"},
  chartNote: {marginTop: spacing.s},
  staleNote: {paddingHorizontal: spacing.xs},
  deckRow: {gap: spacing.s, paddingVertical: spacing.xs},
  deckChip: {
    borderRadius: 999,
    paddingHorizontal: spacing.l,
    paddingVertical: spacing.s,
    maxWidth: 220,
  },
  deckCounts: {flexDirection: "row", gap: spacing.l},
  deckCount: {flex: 1, gap: spacing.xs},
  donutRow: {flexDirection: "row", alignItems: "center", gap: spacing.l},
  donutCentre: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: "center",
    justifyContent: "center",
  },
  legend: {flex: 1, gap: spacing.s},
  legendRow: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  legendDot: {width: 10, height: 10, borderRadius: 5},
  legendLabel: {flex: 1},
  noticeBody: {lineHeight: 18},
  noticeAction: {
    alignSelf: "flex-start",
    borderRadius: 999,
    paddingHorizontal: spacing.l,
    paddingVertical: spacing.m,
  },
});
