/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Analytics: one page over the two services the reader studies with, in the order the questions come
 * — how consistent am I (Study activity), is the collection maturing (Anki), where is the curriculum
 * (WaniKani), what am I weak at (Recall), what is coming (Ahead). There is no source switcher: the
 * sections are the page, and each one names its own source and scope in its meta line.
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
 * cached at module scope, and nothing here writes to either service. Three rules hold everywhere: a
 * figure a source did not answer is an em dash, never a zero; a day the source would not answer is an
 * outlined cell, never an empty one; and one source's failure never blanks another source's sections.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {BOTTOM_BAR_HEIGHT, MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {
  AnalyticsSection,
  BarRow,
  ColumnBars,
  CountLegend,
  Heatmap,
  InlineUnavailable,
  Metric,
  MetricPair,
  Ring,
  SkeletonLines,
  StackedBar,
} from "./ink";
import {
  activeStages,
  aheadBuckets,
  aheadTotal,
  collectionTotal,
  formatCount,
  freshnessLabel,
  heatmap,
  notStartedCount,
  stageShares,
  type HeatmapCell,
} from "./model";
import {
  ANKI_ACTIVITY_DAYS,
  useAnkiStats,
  type AnkiStatsFailure,
  type AnkiStatsSnapshot,
  type AnkiStatsState,
} from "./useAnkiStats";
import {useWaniKaniStats, type WaniKaniStatsState} from "./useWaniKaniStats";
import type {WaniKaniRecall, WaniKaniStatsSnapshot} from "../../native/GatenWaniKani";
import type {SettingsPageName} from "../navigation";

/** The ring both summary sections lead with, at the size the design fixes. */
const RING_SIZE = 84;
const RING_STROKE = 9;

/** The Ahead bars are short by design: eight bars beside a number, not a chart that owns the row. */
const AHEAD_BAR_HEIGHT = 56;

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
 * What each reason AnkiDroid's numbers are missing says, in as few words as the fact allows, and
 * which of the two actions clears it: the Anki settings page (where the grant is asked for) or a
 * plain re-read. Installing AnkiDroid happens outside Gaten, so that one is not told to anyone whose
 * failure is anything else.
 */
const ANKI_NOTICES: Record<AnkiStatsFailure, {body: string; settings?: boolean}> = {
  NOT_INSTALLED: {body: "AnkiDroid is not installed."},
  PERMISSION: {body: "AnkiDroid has not granted access to its collection.", settings: true},
  NOT_SET_UP: {body: "AnkiDroid has no collection yet."},
  NOT_FOUND: {body: "The deck Gaten reads is no longer in the collection.", settings: true},
  MALFORMED: {body: "AnkiDroid answered something Gaten could not read."},
  PROVIDER: {body: "AnkiDroid's collection refused the read."},
};

/**
 * One source, as a section can draw it: its numbers, a read still on its way, or the sentence that
 * says why there are none. The three are separate states rather than a nullable value because they
 * mean three different things, and only the third is allowed to talk about a missing service.
 */
type SourceRead<T> =
  | {state: "loading"}
  | {state: "ready"; data: T}
  | {state: "gone"; body: string; action?: {label: string; onPress: () => void}};

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
  const notice = ANKI_NOTICES[anki.failure ?? "PROVIDER"];
  return {
    state: "gone",
    body: notice.body,
    action: notice.settings
      ? {label: "Anki settings", onPress: () => onOpenSettings("anki")}
      : {label: "Try again", onPress: anki.refresh},
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
      body: rejected ? "WaniKani refused the stored token." : "WaniKani is not connected.",
      action: {
        label: rejected ? "Connect again" : "Connect WaniKani",
        onPress: () => onOpenSettings("wanikani"),
      },
    };
  }
  // `failed`: a read that settled with nothing to show. When WaniKani asked for a wait, that wait is
  // the reason the reader is not told to try again straight away.
  return {
    state: "gone",
    body:
      wanikani.message ??
      (wanikani.retryAfterSeconds != null
        ? `WaniKani asked for a ${wanikani.retryAfterSeconds}s wait.`
        : "WaniKani could not be read."),
    action: wanikani.retryAfterSeconds == null ? {label: "Try again", onPress: refresh} : undefined,
  };
}

/** The heading, and the one control on the page. */
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
          {updated ?? "Updating…"}
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

/** What a section shows instead of a source's numbers: a skeleton while the read is open, or the line
 *  that says why there are none. A read that answered draws its numbers in the section itself. */
function SourceFallback({read, withAction = true}: {read: SourceRead<unknown>; withAction?: boolean}) {
  if (read.state === "loading") {
    return <SkeletonLines />;
  }
  if (read.state === "gone") {
    return (
      <InlineUnavailable action={withAction ? read.action : undefined}>{read.body}</InlineUnavailable>
    );
  }
  return null;
}

/** The quiet sentence under a section's numbers, in the page's own muted ink. */
function Note({children}: {children: React.ReactNode}) {
  const {colors} = useTheme();
  return <Text style={[styles.note, {color: colors.onSurfaceVariant}]}>{children}</Text>;
}

/**
 * The grid drawn while the first read is on its way: twelve weeks of days the source has not answered
 * yet. It is built by the same function that builds the real grid, from a window in which every day is
 * unknown, so the skeleton is the chart's own "unknown" treatment rather than a second drawing of it —
 * and it claims nothing: the summary reads no active days, no cards.
 */
function unknownGrid(now: number): (HeatmapCell | null)[][] {
  const unknown = Array.from({length: ANKI_ACTIVITY_DAYS}, (_, daysAgo) => ({daysAgo, cards: null}));
  return heatmap(unknown, now).columns;
}

/** A share of a whole, or null when the two halves are not both known. */
function share(part: number | null, whole: number | null): number | null {
  if (part == null || whole == null || whole <= 0) {
    return null;
  }
  return Math.min(1, part / whole);
}

/** A cumulative accuracy as a percentage, or an em dash when there were no answers to divide by. */
function accuracy(part: {correct: number; total: number} | null): {share: number | null; label: string} {
  if (part == null || part.total <= 0) {
    return {share: null, label: "—"};
  }
  return {share: part.correct / part.total, label: `${Math.round((part.correct / part.total) * 100)}%`};
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
  const anki = useAnkiStats();
  const wanikani = useWaniKaniStats();
  const scroll = useRef<ScrollView | null>(null);
  // Only the freshness line depends on this: it is a clock, not a read.
  const [, tick] = useState(0);

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

  const ankiState = ankiRead(anki, openSettings);
  const wkState = waniKaniRead(wanikani, wanikani.refresh, openSettings);

  const refresh = () => {
    anki.refresh();
    wanikani.refresh();
  };

  const busy = anki.refreshing || wanikani.status === "refreshing" || wanikani.status === "loading";

  const ankiData = ankiState.state === "ready" ? ankiState.data : null;
  const wkData = wkState.state === "ready" ? wkState.data : null;
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

  // --- WaniKani -------------------------------------------------------------
  const stages = wkData?.stages ?? [];
  const active = activeStages(stages);
  const shares = stageShares(active.map((stage) => stage.count));
  const activeSubjects = active.length > 0 ? active.reduce((sum, stage) => sum + stage.count, 0) : null;
  const notStarted = notStartedCount(stages);
  const levelShare = share(wkData?.level ?? null, wkData?.maxLevelGranted ?? null);

  // --- Recall ---------------------------------------------------------------
  const recall: WaniKaniRecall | null = wkData?.recall ?? null;
  const reading = accuracy(recall?.reading ?? null);
  const meaning = accuracy(recall?.meaning ?? null);

  // --- Ahead ----------------------------------------------------------------
  const buckets = aheadBuckets(wkData?.forecast ?? []);
  const aheadCount = aheadTotal(wkData?.forecast ?? []);

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
            paddingHorizontal: width < 360 ? spacing.l : spacing.contentInset,
            paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
          },
        ]}
        keyboardShouldPersistTaps="handled"
      >
        <Header updated={freshnessLabel(updatedAt, now)} busy={busy} onRefresh={refresh} />

        {/* Anki — collection maturity. The ring and the bar are one hue at several strengths; the
            mature share is the only place the page draws a proportion of a whole. */}
        <AnalyticsSection title="Anki" meta="Collection" first>
          {ankiData == null ? (
            // This is the first section of its source, so it carries the action: a page with nothing
            // connected should offer the fix once, not repeat the same button down the page.
            <SourceFallback read={ankiState} />
          ) : (
            <View style={styles.ringRow}>
              <Ring
                share={matureShare}
                centre={matureShare == null ? "—" : `${Math.round(matureShare * 100)}%`}
                caption="mature"
                size={RING_SIZE}
                stroke={RING_STROKE}
              />
              <View style={styles.ringBody}>
                <Metric
                  value={formatCount(totalCards)}
                  caption="cards"
                  muted={totalCards == null}
                  hero={false}
                />
                {collectionCounts != null ? (
                  <>
                    <StackedBar
                      segments={[
                        {key: "new", label: "New", count: collectionCounts.new, color: colors.accent, opacity: ANKI_INTENSITY[0]},
                        {key: "learning", label: "Learning", count: collectionCounts.learning, color: colors.accent, opacity: ANKI_INTENSITY[1]},
                        {key: "young", label: "Young", count: collectionCounts.young, color: colors.accent, opacity: ANKI_INTENSITY[2]},
                        {key: "mature", label: "Mature", count: collectionCounts.mature, color: colors.accent, opacity: ANKI_INTENSITY[3]},
                      ]}
                    />
                    <CountLegend
                      items={[
                        {key: "new", label: "New", value: formatCount(collectionCounts.new)},
                        {key: "learning", label: "Learn.", value: formatCount(collectionCounts.learning)},
                        {key: "young", label: "Young", value: formatCount(collectionCounts.young)},
                        {key: "mature", label: "Mature", value: formatCount(collectionCounts.mature)},
                      ]}
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
            which is not the whole locked curriculum, and the note says so in five words. */}
        <AnalyticsSection title="WaniKani" meta="Progress">
          {wkData == null ? (
            <SourceFallback read={wkState} />
          ) : (
            <View style={styles.ringRow}>
              <Ring
                share={levelShare}
                centre={formatCount(wkData.level)}
                caption={wkData.maxLevelGranted > 0 ? `of ${wkData.maxLevelGranted}` : "level"}
                size={RING_SIZE}
                stroke={RING_STROKE}
              />
              <View style={styles.ringBody}>
                <Metric
                  value={formatCount(activeSubjects)}
                  caption="active subjects"
                  muted={activeSubjects == null}
                  hero={false}
                />
                {active.length > 0 ? (
                  <>
                    <StackedBar
                      segments={active.map((stage, index) => ({
                        key: stage.key,
                        label: stage.label,
                        count: stage.count,
                        color: colors.accent,
                        opacity: SRS_INTENSITY[index % SRS_INTENSITY.length],
                      }))}
                    />
                    <CountLegend
                      items={active.map((stage) => ({
                        key: stage.key,
                        // WaniKani's own bucket names carry the stage numbers ("Guru 5-6"); the legend
                        // needs the word alone to fit five of them on one line.
                        label: stage.label.split(" ")[0],
                        value: formatCount(stage.count),
                      }))}
                    />
                    <Note>
                      {notStarted != null && notStarted > 0
                        ? `Excludes ${notStarted} not-started and all locked items.`
                        : "Locked items are excluded."}
                    </Note>
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
            state, and because the two summaries are what a reader opens the page for. */}
        <AnalyticsSection title="Study activity" meta="12 weeks · Anki">
          {grid == null ? (
            <>
              <SourceFallback read={ankiState} withAction={false} />
              {ankiState.state === "loading" && <Heatmap columns={unknownGrid(now)} />}
            </>
          ) : (
            <>
              <MetricPair
                left={{
                  value: formatCount(grid.activeDays),
                  caption: "active days",
                  muted: grid.activeDays == null,
                  hero: false,
                }}
                right={{
                  value: formatCount(grid.cards),
                  caption: "cards",
                  muted: grid.cards == null,
                  hero: false,
                }}
              />
              <Heatmap columns={grid.columns} />
              {grid.partial && <Note>Some days unknown.</Note>}
            </>
          )}
        </AnalyticsSection>

        {/* Recall — cumulative accuracy over every answer this account has given, which is why it
            carries no date range: the API has no answer history to scope it to. */}
        <AnalyticsSection title="Recall" meta="WaniKani">
          {wkData == null ? (
            <SourceFallback read={wkState} withAction={false} />
          ) : recall == null ? (
            <InlineUnavailable>Recall unavailable.</InlineUnavailable>
          ) : (
            <View style={styles.recallRow}>
              <View style={styles.recallTracks}>
                <BarRow
                  label="Reading"
                  count={reading.label}
                  share={reading.share ?? 0}
                  color={colors.accent}
                />
                <BarRow
                  label="Meaning"
                  count={meaning.label}
                  share={meaning.share ?? 0}
                  color={colors.accent}
                />
              </View>
              <View style={[styles.recallDivider, {backgroundColor: colors.outlineVariant}]} />
              <View style={styles.recallNeedsWork}>
                <Text style={[styles.needsWorkLabel, {color: colors.onSurfaceVariant}]}>
                  Needs work
                </Text>
                {recall.difficult.length > 0 ? (
                  <View style={styles.needsWorkRow}>
                    {recall.difficult.map((item) => (
                      <View
                        key={item.subjectId}
                        accessible
                        accessibilityLabel={`${item.characters ?? "Subject"} ${item.percentage}% over ${item.attempts} answers`}
                        style={styles.needsWorkItem}
                      >
                        <Text style={[styles.needsWorkGlyph, {color: colors.onSurface}]}>
                          {item.characters ?? "—"}
                        </Text>
                        <Text style={[styles.needsWorkScore, {color: colors.onSurfaceVariant}]}>
                          {item.percentage}%
                        </Text>
                      </View>
                    ))}
                  </View>
                ) : (
                  <Text style={[styles.needsWorkScore, {color: colors.onSurfaceVariant}]}>
                    Not enough answers yet.
                  </Text>
                )}
              </View>
            </View>
          )}
        </AnalyticsSection>

        {/* Ahead — the schedule WaniKani already holds, not a promise: failures and new lessons will
            change it, and the label says "Next 24h", not "tomorrow". */}
        <AnalyticsSection title="Ahead" meta="Next 24h · WaniKani">
          {wkData == null ? (
            <SourceFallback read={wkState} withAction={false} />
          ) : (
            <View style={styles.aheadRow}>
              <Metric value={formatCount(aheadCount)} caption="reviews" muted={aheadCount == null} hero={false} />
              <View style={styles.aheadBars}>
                <ColumnBars points={buckets} height={AHEAD_BAR_HEIGHT} />
              </View>
            </View>
          )}
        </AnalyticsSection>

        {stale.length > 0 && (
          <Text style={[typeScale.bodySmall, styles.stale, {color: colors.onSurfaceVariant}]}>
            {stale.join(" ")}
          </Text>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  content: {
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    alignSelf: "center",
    paddingHorizontal: spacing.contentInset,
  },
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
  recallRow: {flexDirection: "row", alignItems: "flex-start", gap: spacing.l},
  recallTracks: {flex: 1, minWidth: 0, gap: spacing.s},
  recallDivider: {width: 1, alignSelf: "stretch"},
  recallNeedsWork: {flex: 1, minWidth: 0, gap: spacing.s},
  needsWorkLabel: {...typeScale.bodySmall},
  needsWorkRow: {flexDirection: "row", gap: spacing.m},
  needsWorkItem: {flex: 1, alignItems: "center", gap: 2},
  needsWorkGlyph: {fontSize: 26, lineHeight: 32},
  needsWorkScore: {...typeScale.bodySmall},
  aheadRow: {flexDirection: "row", alignItems: "flex-end", gap: spacing.l},
  aheadBars: {flex: 1, minWidth: 0},
});
