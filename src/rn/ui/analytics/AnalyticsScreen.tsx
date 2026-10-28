/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Analytics: one page over the two services the reader studies with, in the order the questions come
 * — how consistent am I (Study activity), is the collection maturing (Anki), where is the curriculum
 * (WaniKani), how are my answers going (Accuracy), how long is a level taking (Level progress). There
 * is no source switcher: the sections are the page, and each one names its own source and scope in
 * its meta line.
 *
 * The page is deliberately near-monochrome. Every analytical hue is the reader's own accent at one of
 * a few strengths, so the whole page repaints when the accent changes and no chart carries a colour
 * that means something only here. There is no success green, no brand pink and no brand blue in the
 * data: a green accent is the reader's choice and flows through like any other, but nothing on this
 * page invents a second colour language of its own.
 *
 * There are no cards. Hierarchy is whitespace, 1 dp rules and type size; a chart sits on the page
 * with no frame around it, and the numbers that belong to a chart sit beside it rather than in a tile.
 *
 * Home already answers "what is due now", so this page never repeats it: no due counts, no streak, no
 * service rows. Every figure here is a summary the reader cannot get from the review screens.
 *
 * The screen is presentational. The numbers come from `useAnkiStats` and `useWaniKaniStats`, both
 * cached at module scope, and nothing here writes to either service. Four rules hold everywhere: a
 * figure a source did not answer is an em dash, never a zero; a day the source would not answer is an
 * outlined cell, never an empty one; one source's failure never blanks another source's sections; and a
 * source that cannot be read is named once — in its own section, with the one action that fixes it —
 * while the sections that read it draw a motif and a line of their own instead of repeating the action.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {Pressable, ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useIsFocused} from "@react-navigation/native";
import {BOTTOM_BAR_HEIGHT, CONTENT_COLUMN, contentInsetFor, shape, spacing, typeScale} from "../tokens";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {
  AnalyticsEmpty,
  AnalyticsSection,
  AnimatedValueText,
  BarRow,
  CountLegend,
  Heatmap,
  InlineUnavailable,
  Metric,
  MetricPair,
  Ring,
  SourceUnavailable,
  StackedBar,
  useAnalyticsEntrance,
  type DistributionPart,
  type SourceTone,
} from "./ink";
import {
  AccuracyArt,
  AnkiConnectionArt,
  LevelProgressArt,
  StudyActivityArt,
  WaniKaniConnectionArt,
  type Artwork,
} from "./illustrations";
import {
  accuracyOf,
  activeStages,
  averageLevelDays,
  collectionTotal,
  daysOnLevel,
  daysOnLevelLabel,
  formatCount,
  freshnessLabel,
  heatmap,
  levelDurations,
  overallAccuracy,
  subjectAccuracies,
  type HeatmapCell,
} from "./model";
import {
  useAnkiStats,
  type AnkiStatsFailure,
  type AnkiStatsSnapshot,
  type AnkiStatsState,
} from "./useAnkiStats";
import {useWaniKaniStats, type WaniKaniStatsState} from "./useWaniKaniStats";
import * as Anki from "../../native/GatenAnki";
import {downloadAnkiDroid} from "../anki/anki-actions";
import * as GatenPlatform from "../../native/GatenPlatform";
import type {WaniKaniStatsSnapshot} from "../../native/GatenWaniKani";
import type {SettingsPageName} from "../navigation";

/** The ring both summary sections lead with, at the size the design fixes. */
const RING_SIZE = 84;
const RING_STROKE = 9;

/**
 * How often the freshness line is recomputed. It reads "Updated 4m ago", which is only true while
 * something keeps counting: no data is read here, this only re-renders the sentence, and a page left
 * open for an hour stops claiming to be a minute old.
 */
const FRESHNESS_TICK_MS = 30_000;

/** The accent strengths a distribution bar is drawn from — one hue, four (or five) weights. */
const ANKI_INTENSITY = [0.2, 0.42, 0.67, 1] as const;
const SRS_INTENSITY = [0.2, 0.4, 0.6, 0.8, 1] as const;

/**
 * How a dependent section reads its source's failure. `empty` means the source simply has nothing yet
 * and the section's own sentence stands; the rest are the design's wording for a source that failed,
 * is waiting for a permission, or is offline.
 */
type Downstream = "empty" | "error" | "permission" | "offline";

const DOWNSTREAM_MESSAGES: Record<Exclude<Downstream, "empty">, string> = {
  error: "Unavailable until the read succeeds.",
  permission: "Waiting for permission.",
  offline: "Unavailable while offline.",
};

/** What a source still being read says in the sections that depend on it. */
const DOWNSTREAM_LOADING = "Loading source data…";

/** Which of AnkiDroid's own fixes a failure needs. */
type AnkiFix = "install" | "grant" | "open" | "settings" | "retry";

/**
 * What each reason AnkiDroid's numbers are missing says, how it is marked, which action clears it, and
 * what the sections that read AnkiDroid say meanwhile. `Install` opens the store page and `Grant
 * access` asks for the collection permission: both happen outside a read, so neither is cleared by
 * reading again.
 */
const ANKI_FAILURES: Record<
  AnkiStatsFailure,
  {tone: SourceTone; title: string; detail: string; downstream: Downstream; fix: AnkiFix}
> = {
  NOT_INSTALLED: {
    tone: "neutral",
    title: "AnkiDroid is not installed.",
    detail: "Open the AnkiDroid installation flow.",
    downstream: "empty",
    fix: "install",
  },
  PERMISSION: {
    tone: "neutral",
    title: "AnkiDroid access needed.",
    detail: "Allow Gaten to read collection statistics.",
    downstream: "permission",
    fix: "grant",
  },
  NOT_SET_UP: {
    tone: "neutral",
    title: "AnkiDroid has no collection yet.",
    detail: "Open AnkiDroid once to create it.",
    downstream: "empty",
    fix: "open",
  },
  NOT_FOUND: {
    tone: "error",
    title: "The deck Gaten reads is no longer in the collection.",
    detail: "Choose the deck again in Anki settings.",
    downstream: "error",
    fix: "settings",
  },
  MALFORMED: {
    tone: "error",
    title: "Could not read collection.",
    detail: "The last attempt failed.",
    downstream: "error",
    fix: "retry",
  },
  PROVIDER: {
    tone: "error",
    title: "Could not read collection.",
    detail: "The last attempt failed.",
    downstream: "error",
    fix: "retry",
  },
};

/** Runs one of the page's own openers, and says so when the device took nothing. */
function openOrSay(opened: Promise<boolean>, failure: string) {
  void opened
    .then((done) => {
      if (!done) {
        void GatenPlatform.toast(failure);
      }
    })
    .catch(() => {
      void GatenPlatform.toast(failure);
    });
}

/**
 * Asks for AnkiDroid's own collection permission — the request the review screen makes — and reads the
 * numbers again once it is granted.
 */
function grantAnkiAccess(refresh: () => void) {
  void Anki.requestPermission()
    .then((granted) => {
      if (granted) {
        refresh();
        return;
      }
      void GatenPlatform.toast("AnkiDroid access was not granted");
    })
    .catch(() => {
      void GatenPlatform.toast("Could not ask for AnkiDroid access");
    });
}

/** The action each of AnkiDroid's fixes offers, built from the page's own callbacks. */
function ankiAction(
  fix: AnkiFix,
  anki: AnkiStatsState,
  onOpenSettings: (page: SettingsPageName) => void
): {label: string; onPress: () => void} {
  switch (fix) {
    case "install":
      return {
        label: "Install",
        onPress: downloadAnkiDroid,
      };
    case "grant":
      return {label: "Grant access", onPress: () => grantAnkiAccess(anki.refresh)};
    case "open":
      return {
        label: "Open AnkiDroid",
        onPress: () => openOrSay(Anki.openAnkiDroid(), "Could not open AnkiDroid"),
      };
    case "settings":
      return {label: "Anki settings", onPress: () => onOpenSettings("anki")};
    case "retry":
      return {label: "Try again", onPress: anki.refresh};
  }
}

/**
 * How a source that cannot be read is shown: the tone of its mark, its message and supporting line, the
 * one action that clears it, and what the sections that read it say meanwhile.
 */
type SourceFailure = {
  tone: SourceTone;
  title: string;
  detail: string;
  downstream: Downstream;
  action?: {label: string; onPress: () => void};
};

/**
 * One source, as a section can draw it: its numbers, a read still on its way, or the presentation of why
 * there are none. The three are separate states rather than a nullable value because they mean three
 * different things, and only the third is allowed to talk about a missing service.
 */
type SourceRead<T> =
  | {state: "loading"}
  | {state: "ready"; data: T}
  | {state: "gone"; failure: SourceFailure};

/**
 * AnkiDroid's read as the sections need it. A read that has not answered yet is `loading`, and only a
 * read that has settled with nothing to show is `gone` — so "not connected" is never said on the
 * strength of a question that is simply still open.
 */
function ankiRead(
  anki: AnkiStatsState,
  onOpenSettings: (page: SettingsPageName) => void
): SourceRead<AnkiStatsSnapshot> {
  if (anki.data != null) {
    return {state: "ready", data: anki.data};
  }
  if (anki.status === "loading") {
    return {state: "loading"};
  }
  const failure = ANKI_FAILURES[anki.failure ?? "PROVIDER"];
  return {
    state: "gone",
    failure: {...failure, action: ankiAction(failure.fix, anki, onOpenSettings)},
  };
}

/** WaniKani's read as the sections need it, with the token states spoken in the reader's terms. */
function waniKaniRead(
  wanikani: WaniKaniStatsState,
  refresh: () => void,
  onOpenSettings: (page: SettingsPageName) => void
): SourceRead<WaniKaniStatsSnapshot> {
  if (wanikani.snapshot != null) {
    return {state: "ready", data: wanikani.snapshot};
  }
  if (wanikani.status === "loading" || wanikani.status === "idle") {
    return {state: "loading"};
  }
  if (wanikani.status === "needsToken" || wanikani.status === "authRejected") {
    const rejected = wanikani.status === "authRejected";
    return {
      state: "gone",
      failure: {
        tone: "neutral",
        title: rejected ? "WaniKani refused the stored token." : "Not connected",
        detail: rejected ? "Connect again with a new token." : "Connect to see WaniKani progress.",
        downstream: "empty",
        action: {
          label: rejected ? "Connect again" : "Connect",
          onPress: () => onOpenSettings("wanikani"),
        },
      },
    };
  }
  // A device that really is offline is the one failure WaniKani names as such, so it is the only one
  // allowed to say "offline"; everything else reads as a failed read. A rate limit is a wait, and the
  // wait is why the reader is not offered a retry that would do nothing.
  if (wanikani.failure === "OFFLINE") {
    return {
      state: "gone",
      failure: {
        tone: "warning",
        title: "You are offline.",
        detail: "Internet is required for WaniKani.",
        downstream: "offline",
        action: {label: "Reconnect", onPress: refresh},
      },
    };
  }
  if (wanikani.retryAfterSeconds != null) {
    return {
      state: "gone",
      failure: {
        tone: "warning",
        title: `WaniKani asked for a ${wanikani.retryAfterSeconds}s wait.`,
        detail: "Refresh after the wait to load your numbers.",
        downstream: "error",
      },
    };
  }
  return {
    state: "gone",
    failure: {
      tone: "error",
      title: wanikani.message ?? "Could not load WaniKani.",
      detail: "Check the connection and retry.",
      downstream: "error",
      action: {label: "Try again", onPress: refresh},
    },
  };
}

/**
 * The heading, and the one control on the page. The line under it is the age of the numbers on screen;
 * with nothing read yet it says the read is in flight, or that there is no data at all.
 */
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
      <View style={styles.headerHeading}>
        <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>Analytics</Text>
        <Text
          numberOfLines={1}
          style={[typeScale.description, styles.headerUpdated, {color: colors.onSurfaceVariant}]}
        >
          {updated ?? (busy ? "Updating…" : "No data yet")}
        </Text>
      </View>
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel="Refresh the numbers"
        accessibilityState={{disabled: busy}}
        onPress={onRefresh}
        style={[styles.refresh, {backgroundColor: colors.surfaceVariant}]}
      >
        <Icon name="refresh" size={22} color={busy ? colors.onSurfaceVariant : colors.accent} />
      </PressableScale>
    </View>
  );
}

/**
 * What a source's own section shows while it has no numbers: the design's motif, its state's mark, and
 * the one action that fixes it. Used by the two source sections only, so a failure is stated once — the
 * sections that read the same source draw a line of their own instead. A read that answered draws its
 * numbers in the section itself.
 */
function SourceState({
  read,
  art,
  loadingTitle,
  loadingDetail,
}: {
  read: SourceRead<unknown>;
  art: React.ComponentType<Artwork>;
  loadingTitle: string;
  loadingDetail: string;
}) {
  if (read.state === "ready") {
    return null;
  }
  if (read.state === "loading") {
    return <SourceUnavailable art={art} busy title={loadingTitle} detail={loadingDetail} />;
  }
  return (
    <SourceUnavailable
      art={art}
      tone={read.failure.tone}
      title={read.failure.title}
      detail={read.failure.detail}
      action={read.failure.action}
    />
  );
}

/**
 * The one line a section shows in place of its numbers when its source has none: the design's wording
 * for a source that is still reading, refused or offline, and the section's own sentence when the source
 * simply has nothing to show yet.
 */
function downstreamMessage(read: SourceRead<unknown>, empty: string): string {
  if (read.state === "loading") {
    return DOWNSTREAM_LOADING;
  }
  if (read.state === "gone" && read.failure.downstream !== "empty") {
    return DOWNSTREAM_MESSAGES[read.failure.downstream];
  }
  return empty;
}

/** The quiet sentence under a section's numbers, in the page's own muted ink. */
function Note({children}: {children: React.ReactNode}) {
  const {colors} = useTheme();
  return <Text style={[styles.note, {color: colors.onSurfaceVariant}]}>{children}</Text>;
}

/** A share of a whole, or null when the two halves are not both known. */
function share(part: number | null, whole: number | null): number | null {
  if (part == null || whole == null || whole <= 0) {
    return null;
  }
  return Math.min(1, part / whole);
}

/**
 * The page. `onOpenSettingsPage` is the shell's own way to a settings page: the actions on the
 * unavailable lines hand the reader to the page that owns the token or the grant, and coming back
 * reads the numbers again.
 */
export function AnalyticsScreen({
  onOpenSettingsPage,
}: {
  onOpenSettingsPage?: (page: SettingsPageName) => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const focused = useIsFocused();
  const anki = useAnkiStats();
  const wanikani = useWaniKaniStats();
  const scroll = useRef<ScrollView | null>(null);
  // Only the freshness line depends on this: it is a clock, not a read.
  const [, tick] = useState(0);
  // The highlighted category of each distribution chart, by its stable key. The two are separate
  // pieces of state, so the Anki and WaniKani bars can never highlight each other.
  const [selectedAnki, setSelectedAnki] = useState<string | null>(null);
  const [selectedSrs, setSelectedSrs] = useState<string | null>(null);
  // The heatmap's open day, by the day's own offset, so a refresh that redraws the same day keeps
  // the detail open on the fresh count and a day that disappears closes it on its own.
  const [selectedDayAgo, setSelectedDayAgo] = useState<number | null>(null);

  useEffect(() => {
    const timer = setInterval(() => tick((count) => count + 1), FRESHNESS_TICK_MS);
    return () => clearInterval(timer);
  }, []);

  const openSettings = useCallback(
    (page: SettingsPageName) => {
      onOpenSettingsPage?.(page);
    },
    [onOpenSettingsPage]
  );

  // A tap selects a category, and a second tap on the same category clears it; the bar and the key
  // both call the same toggle, so the two always agree.
  const toggleAnki = useCallback((key: string) => {
    setSelectedAnki((current) => (current === key ? null : key));
  }, []);
  const toggleSrs = useCallback((key: string) => {
    setSelectedSrs((current) => (current === key ? null : key));
  }, []);
  // A tap on a day opens its detail; a second tap on the same day closes it. The popover itself is
  // rendered by the grid from the day's current counts, so a refresh never leaves a stale figure.
  const toggleDay = useCallback((cell: HeatmapCell) => {
    setSelectedDayAgo((current) => (current === cell.daysAgo ? null : cell.daysAgo));
  }, []);
  // A tap on the page outside the charts and the grid is the other way to clear everything: both
  // chart highlights and the open day. It is a plain press on the content, so a scroll, a button or
  // a chart pressed inside it still handles its own touch first.
  const dismiss = useCallback(() => {
    setSelectedAnki(null);
    setSelectedSrs(null);
    setSelectedDayAgo(null);
  }, []);

  const ankiState = ankiRead(anki, openSettings);
  const wkState = waniKaniRead(wanikani, wanikani.refresh, openSettings);

  const refresh = () => {
    anki.refresh();
    wanikani.refresh();
  };

  const busy = anki.refreshing || wanikani.status === "refreshing" || wanikani.status === "loading";

  const ankiData = ankiState.state === "ready" ? ankiState.data : null;
  const wkData = wkState.state === "ready" ? wkState.data : null;
  // The entry sweep starts once per source, when the page is actually in view and that source has
  // answered. A refresh, a tap, the freshness clock or a text-size change never restarts it, and a
  // section that mounts after the sweep is already at its final value.
  const ankiEntrance = useAnalyticsEntrance(focused && ankiData != null);
  const wkEntrance = useAnalyticsEntrance(focused && wkData != null);
  const now = Date.now();

  // The freshest of the two reads, as an age. No timer sits behind the numbers themselves: this says
  // how old what is on screen is, and a resumed app re-reads them through the hooks.
  const updatedAt = useMemo(() => {
    const times = [anki.data?.updatedAt ?? null, wanikani.snapshot?.fetchedAt ?? null].filter(
      (at): at is number => at != null
    );
    return times.length === 0 ? null : Math.max(...times);
  }, [anki.data?.updatedAt, wanikani.snapshot?.fetchedAt]);

  // A read that could not be brought up to date still has numbers on screen. Those are marked once, at
  // the foot of the page — a cached figure never pretends to be fresh.
  const stale: string[] = [];
  if (anki.data != null && anki.failure != null) {
    stale.push("The last AnkiDroid read did not finish.");
  }
  if (wanikani.snapshot != null && wanikani.stale) {
    stale.push(
      wanikani.retryAfterSeconds != null
        ? `WaniKani asked for a ${wanikani.retryAfterSeconds}s wait.`
        : "WaniKani could not be brought up to date."
    );
  }

  // --- Study activity -------------------------------------------------------
  const grid = ankiData != null ? heatmap(ankiData.activity, now) : null;

  // --- Anki -----------------------------------------------------------------
  const collection = ankiData?.collection ?? null;
  const collectionCounts =
    collection != null &&
    collection.new != null &&
    collection.learning != null &&
    collection.young != null &&
    collection.mature != null
      ? {
          new: collection.new,
          learning: collection.learning,
          young: collection.young,
          mature: collection.mature,
        }
      : null;
  const totalCards = collectionTotal(
    collection ?? {new: null, learning: null, young: null, mature: null}
  );
  const matureShare = share(collectionCounts?.mature ?? null, totalCards);
  // One array of parts for the bar and its key: both read the same counts and the same tones, so a
  // swatch can never name a colour the bar above it did not draw.
  const ankiParts: DistributionPart[] =
    collectionCounts == null
      ? []
      : [
          {key: "new", label: "New", count: collectionCounts.new, color: colors.accent, opacity: ANKI_INTENSITY[0]},
          {key: "learning", label: "Learn.", spoken: "Learning", count: collectionCounts.learning, color: colors.accent, opacity: ANKI_INTENSITY[1]},
          {key: "young", label: "Young", count: collectionCounts.young, color: colors.accent, opacity: ANKI_INTENSITY[2]},
          {key: "mature", label: "Mature", count: collectionCounts.mature, color: colors.accent, opacity: ANKI_INTENSITY[3]},
        ].map((part) => ({...part, value: formatCount(part.count)}));

  // --- WaniKani -------------------------------------------------------------
  const stages = wkData?.stages ?? [];
  const active = activeStages(stages);
  const activeSubjects = active.length > 0 ? active.reduce((sum, stage) => sum + stage.count, 0) : null;
  const levelShare = share(wkData?.level ?? null, wkData?.maxLevelGranted ?? null);
  const srsParts: DistributionPart[] = active.map((stage, index) => ({
    key: stage.key,
    // WaniKani's own bucket names carry the stage numbers ("Guru 5-6"); the key needs the word alone
    // to fit five of them on a line, and the bar's spoken summary keeps the full name.
    label: stage.label.split(" ")[0],
    spoken: stage.label,
    count: stage.count,
    value: formatCount(stage.count),
    color: colors.accent,
    opacity: SRS_INTENSITY[index % SRS_INTENSITY.length],
  }));

  // A refresh can stop drawing a category the reader had chosen — a WaniKani stage the payload no
  // longer names. Drop that choice so no chart highlights a key that is not on screen. The joined key
  // list is the real dependency: the arrays are rebuilt every render, but a refresh that draws the
  // same categories joins to the same string and does not re-run the effect.
  const ankiKeys = ankiParts.map((part) => part.key);
  const srsKeys = srsParts.map((part) => part.key);
  useEffect(() => {
    setSelectedAnki((current) => (current != null && !ankiKeys.includes(current) ? null : current));
  }, [ankiKeys.join(",")]);
  useEffect(() => {
    setSelectedSrs((current) => (current != null && !srsKeys.includes(current) ? null : current));
  }, [srsKeys.join(",")]);

  // --- Accuracy -------------------------------------------------------------
  // `?? null` on both reads because a cache written by an older build carries neither field: the
  // sections then say the read is unavailable rather than drawing a zero.
  const recall = wkData?.recall ?? null;
  const overall = overallAccuracy(recall);
  const meaning = accuracyOf(recall?.meaning ?? null);
  const reading = accuracyOf(recall?.reading ?? null);
  const subjects = subjectAccuracies(recall?.bySubject ?? null);
  // The subject line's raw shares, kept beside the formatted labels so its percentages can count up
  // from zero without parsing the display string or changing the model's public shape.
  const subjectShares = new Map<string, number | null>();
  for (const subject of recall?.bySubject ?? []) {
    subjectShares.set(subject.key, subject.total > 0 ? subject.correct / subject.total : null);
  }

  // --- Level progress -------------------------------------------------------
  const history = wkData?.levelHistory ?? null;
  const level = wkData?.level ?? null;
  const measured = history != null && level != null;
  const levelRows = measured ? levelDurations(history, now, level) : [];
  const averageDays = measured ? averageLevelDays(history, now, level) : null;
  const levelDays = measured ? daysOnLevel(history, now, level) : null;
  // The longest row is the axis every row is drawn against; a list of nothing but zero-length runs
  // leaves the axis at zero and draws no bars rather than dividing by it.
  const levelAxis = levelRows.reduce((top, row) => (row.days > top ? row.days : top), 0);

  return (
    <View style={[styles.root, {backgroundColor: colors.background}]}>
      <ScrollView
        ref={scroll}
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={{marginTop: insets.top}}
        contentContainerStyle={[
          styles.content,
          {
            paddingHorizontal: contentInsetFor(width),
            paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
          },
        ]}
        keyboardShouldPersistTaps="handled"
      >
        {/* The whole page is one quiet press target so a tap on blank content clears the chart
            highlights and the open day. It is not a control: it adds no accessibility node and no ink,
            and a tap on a chart, a legend key, a day or the refresh button handles its own press
            first — the scroll cancels this press as soon as the finger moves. */}
        <Pressable
          accessible={false}
          importantForAccessibility="no"
          onPress={dismiss}
          style={styles.contentDismiss}
        >
        <Header updated={freshnessLabel(updatedAt, now)} busy={busy} onRefresh={refresh} />

        {/* Anki — collection maturity. The ring and the bar are one hue at several strengths; the
            mature share is the only place the page draws a proportion of a whole. */}
        <AnalyticsSection title="Anki" meta="Collection" first>
          {ankiData == null ? (
            // The source's own section is the one place its failure is named and its fix offered; the
            // sections that read it say a line of their own instead of repeating this.
            <SourceState
              read={ankiState}
              art={AnkiConnectionArt}
              loadingTitle="Checking AnkiDroid…"
              loadingDetail="Checking collection access."
            />
          ) : (
            <View style={styles.ringRow}>
              <Ring
                share={matureShare}
                centre={matureShare == null ? "—" : `${Math.round(matureShare * 100)}%`}
                centreCount={
                  matureShare == null
                    ? null
                    : {value: matureShare * 100, decimals: 0, suffix: "%"}
                }
                caption="mature"
                size={RING_SIZE}
                stroke={RING_STROKE}
                entrance={ankiEntrance}
              />
              <View style={styles.ringBody}>
                <Metric
                  value={formatCount(totalCards)}
                  count={{value: totalCards, decimals: 0}}
                  caption="cards"
                  muted={totalCards == null}
                  hero={false}
                  entrance={ankiEntrance}
                />
                {collectionCounts != null ? (
                  <>
                    <StackedBar
                      segments={ankiParts}
                      selectedKey={selectedAnki}
                      onSelect={toggleAnki}
                      entrance={ankiEntrance}
                    />
                    <CountLegend
                      items={ankiParts}
                      selectedKey={selectedAnki}
                      onSelect={toggleAnki}
                      entrance={ankiEntrance}
                    />
                  </>
                ) : (
                  <InlineUnavailable>AnkiDroid would not answer the card-state searches.</InlineUnavailable>
                )}
              </View>
            </View>
          )}
        </AnalyticsSection>

        {/* WaniKani — where the curriculum is. The population is the assignments this account has,
            which is not the whole locked curriculum: locked items carry no assignment and so never
            appear in a stage. */}
        <AnalyticsSection title="WaniKani" meta="Progress">
          {wkData == null ? (
            <SourceState
              read={wkState}
              art={WaniKaniConnectionArt}
              loadingTitle="Connecting…"
              loadingDetail="Checking your WaniKani account."
            />
          ) : (
            <View style={styles.ringRow}>
              <Ring
                share={levelShare}
                centre={formatCount(wkData.level)}
                centreCount={{value: wkData.level, decimals: 0}}
                caption={wkData.maxLevelGranted > 0 ? `of ${wkData.maxLevelGranted}` : "level"}
                size={RING_SIZE}
                stroke={RING_STROKE}
                entrance={wkEntrance}
              />
              <View style={styles.ringBody}>
                <Metric
                  value={formatCount(activeSubjects)}
                  count={{value: activeSubjects, decimals: 0}}
                  caption="active subjects"
                  muted={activeSubjects == null}
                  hero={false}
                  entrance={wkEntrance}
                />
                {active.length > 0 ? (
                  <>
                    <StackedBar
                      segments={srsParts}
                      selectedKey={selectedSrs}
                      onSelect={toggleSrs}
                      entrance={wkEntrance}
                    />
                    <CountLegend
                      items={srsParts}
                      selectedKey={selectedSrs}
                      onSelect={toggleSrs}
                      entrance={wkEntrance}
                    />
                  </>
                ) : (
                  <InlineUnavailable>WaniKani answered no SRS stages.</InlineUnavailable>
                )}
              </View>
            </View>
          )}
        </AnalyticsSection>

        {/* Study activity — the one section AnkiDroid can answer over twelve weeks, because exact-day
            distinct-card searches are what the released provider supports. It is never presented as
            review events, and a day it would not answer stays unknown. It sits after the two source
            summaries because it is the one section that reads a long history rather than today's
            state, and because the two summaries are what a reader opens the page for. While AnkiDroid
            has no numbers the section draws the design's motif and one line, and no second action. */}
        <AnalyticsSection title="Study activity" meta="12 weeks · Anki">
          {grid == null ? (
            <AnalyticsEmpty
              art={StudyActivityArt}
              message={downstreamMessage(ankiState, "Study history will appear here.")}
            />
          ) : (
            <>
              <MetricPair
                left={{
                  value: formatCount(grid.activeDays),
                  count: {value: grid.activeDays, decimals: 0},
                  caption: "active days",
                  muted: grid.activeDays == null,
                  hero: false,
                }}
                right={{
                  value: formatCount(grid.cards),
                  count: {value: grid.cards, decimals: 0},
                  caption: "cards",
                  muted: grid.cards == null,
                  hero: false,
                }}
                entrance={ankiEntrance}
              />
              <Heatmap
                columns={grid.columns}
                selectedDayAgo={selectedDayAgo}
                onSelectDay={toggleDay}
              />
              {grid.partial && <Note>Some days unknown.</Note>}
            </>
          )}
        </AnalyticsSection>

        {/* Accuracy — cumulative over every answer WaniKani still counts for this account, which is
            why it carries no date range: the API keeps a running total per subject, not a log. While
            WaniKani has no numbers the section draws the design's motif and one line, no second action. */}
        <AnalyticsSection title="Accuracy" meta="WaniKani">
          {wkData == null ? (
            <AnalyticsEmpty
              art={AccuracyArt}
              message={downstreamMessage(wkState, "Answer accuracy will appear here.")}
            />
          ) : overall == null ? (
            <InlineUnavailable>Accuracy unavailable.</InlineUnavailable>
          ) : (
            <>
              <Metric
                value={overall.label}
                count={
                  overall.share == null
                    ? null
                    : {value: overall.share * 100, decimals: 0, suffix: "%"}
                }
                caption="overall accuracy"
                muted={overall.share == null}
                hero={false}
                entrance={wkEntrance}
              />
              <Note>
                {formatCount(overall.total)} answers · {formatCount(overall.wrong)} wrong
              </Note>
              <View style={styles.accuracyBars}>
                <BarRow
                  label="Meaning"
                  count={meaning.label}
                  countValue={
                    meaning.share == null
                      ? null
                      : {value: meaning.share * 100, decimals: 0, suffix: "%"}
                  }
                  share={meaning.share ?? 0}
                  color={colors.accent}
                  entrance={wkEntrance}
                />
                <BarRow
                  label="Reading"
                  count={reading.label}
                  countValue={
                    reading.share == null
                      ? null
                      : {value: reading.share * 100, decimals: 0, suffix: "%"}
                  }
                  share={reading.share ?? 0}
                  color={colors.accent}
                  entrance={wkEntrance}
                />
              </View>
              {subjects.length > 0 && (
                <View style={styles.subjectBlock}>
                  <Text style={[styles.subjectHeading, {color: colors.onSurfaceVariant}]}>
                    By subject
                  </Text>
                  <View style={styles.subjectRow}>
                    {subjects.map((subject) => {
                      const share = subjectShares.get(subject.key) ?? null;
                      return (
                        <View
                          key={subject.key}
                          accessible
                          accessibilityLabel={`${subject.name} ${subject.value}`}
                          style={styles.subjectItem}
                        >
                          <AnimatedValueText
                            value={subject.value}
                            count={
                              share == null
                                ? null
                                : {value: share * 100, decimals: 0, suffix: "%"}
                            }
                            entrance={wkEntrance}
                            style={[typeScale.titleMedium, {color: colors.onSurface}]}
                          />
                          <Text
                            numberOfLines={2}
                            style={[
                              typeScale.labelSmall,
                              styles.subjectName,
                              {color: colors.onSurfaceVariant},
                            ]}
                          >
                            {subject.name}
                          </Text>
                        </View>
                      );
                    })}
                  </View>
                </View>
              )}
            </>
          )}
        </AnalyticsSection>

        {/* Level progress — how long a level is taking, from WaniKani's own level progressions. A
            level's duration is its start to its pass, not its completion time, which is when the last
            item on it was finished rather than when the level was left behind. While WaniKani has no
            numbers the section draws the design's motif and one line, and no second action. */}
        <AnalyticsSection title="Level progress" meta="WaniKani">
          {wkData == null ? (
            <AnalyticsEmpty
              art={LevelProgressArt}
              message={downstreamMessage(wkState, "Your level history will appear here.")}
            />
          ) : history == null ? (
            <InlineUnavailable>Level history unavailable.</InlineUnavailable>
          ) : history.length === 0 ? (
            <InlineUnavailable>WaniKani has no level history for this account.</InlineUnavailable>
          ) : (
            <>
              <MetricPair
                left={{
                  value: formatCount(level),
                  count: {value: level, decimals: 0},
                  caption: "level",
                  note: daysOnLevelLabel(levelDays),
                  hero: false,
                }}
                right={{
                  value: averageDays == null ? "—" : averageDays.toFixed(1),
                  count: {value: averageDays, decimals: 1},
                  caption: "days",
                  note: "Average / level",
                  muted: averageDays == null,
                  hero: false,
                }}
                entrance={wkEntrance}
              />
              {levelRows.length > 0 ? (
                <View style={styles.levelRows}>
                  {levelRows.map((row) => (
                    <BarRow
                      key={row.level}
                      label={`L${row.level}`}
                      count={`${row.days.toFixed(1)}d`}
                      countValue={{value: row.days, decimals: 1, suffix: "d"}}
                      share={levelAxis > 0 ? row.days / levelAxis : 0}
                      color={colors.accent}
                      muted={row.current}
                      entrance={wkEntrance}
                      // A level row's own column bases: the label is a two-character name, and the
                      // count has to hold a duration that can run past a hundred days.
                      labelWidth={34}
                      countWidth={52}
                      // Every row reserves the note column, so the current level's "so far" cannot
                      // shorten its track against the finished levels above it.
                      note={row.current ? "so far" : ""}
                      spoken={`Level ${row.level}: ${row.days.toFixed(1)} days${row.current ? ", so far" : ""}`}
                    />
                  ))}
                </View>
              ) : (
                <Note>No finished levels yet.</Note>
              )}
            </>
          )}
        </AnalyticsSection>

        {stale.length > 0 && (
          <Text style={[typeScale.bodySmall, styles.stale, {color: colors.onSurfaceVariant}]}>
            {stale.join(" ")}
          </Text>
        )}
        </Pressable>
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  content: {...CONTENT_COLUMN, paddingHorizontal: spacing.contentInset},
  // The dismiss target is exactly the content's own box: it adds no height or width of its own, so a
  // tap in the page's empty space still reaches it while every child control keeps its own touch.
  contentDismiss: {width: "100%"},
  headerRow: {
    flexDirection: "row",
    alignItems: "center",
    marginTop: spacing.xl,
    gap: spacing.l,
  },
  headerHeading: {flex: 1},
  headerUpdated: {marginTop: 10},
  /** The one control on the page: a quiet square, the accent on the glyph rather than a filled block. */
  refresh: {
    width: 43,
    height: 43,
    borderRadius: shape.small,
    alignItems: "center",
    justifyContent: "center",
  },
  note: {
    ...typeScale.bodySmall,
    marginTop: 12,
  },
  stale: {marginTop: spacing.xl},
  ringRow: {flexDirection: "row", alignItems: "flex-start", gap: spacing.l},
  ringBody: {flex: 1, minWidth: 0, gap: spacing.m},
  accuracyBars: {gap: spacing.s},
  subjectBlock: {gap: 6},
  subjectHeading: {...typeScale.bodySmall},
  // Three kinds across the width the bars above span, each a figure over its name — the same two-step
  // the legends use, so a percentage is never read as prose.
  subjectRow: {flexDirection: "row", gap: spacing.m},
  subjectItem: {flex: 1, minWidth: 0},
  subjectName: {marginTop: 2},
  // The level rows are one list of comparisons, so their tracks share a left and a right edge; the
  // gap is tighter than a section's because the rows are read against each other.
  levelRows: {gap: spacing.s},
});
