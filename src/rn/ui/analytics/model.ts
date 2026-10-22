/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The words and the arithmetic the Analytics page puts around its numbers, as pure functions: no
 * React, no React Native, so what the page claims about a read can be reasoned about — and tested —
 * without a phone, a theme or a bridge.
 *
 * The rule the whole file follows is honesty about what was read. A figure the source did not answer
 * stays missing — a dash, a null, a sentence that says so — and is never quietly turned into a zero,
 * because "none" and "no answer" are different facts and the reader is owed the difference. Each
 * function takes the read shape it needs and hands back the string, number or grid the page draws,
 * in the same voice the page's own copy already uses.
 *
 * Two calendars live side by side here and are never blended: AnkiDroid counts its activity in its
 * own study days (the collection's rollover hour, not local midnight), while the heatmap arranges
 * those days into the reader's local calendar weeks so the grid can be read as a calendar at all. A
 * cell therefore sits on the local date it is labelled with, which need not be the boundary its
 * count was taken over — the copy beside the grid says so rather than letting the reader assume
 * midnight.
 */
import type {AnkiActivityDay, AnkiCollectionCounts} from "./useAnkiStats";

/**
 * The activity day the heatmap is built from, re-exported here so the grid's inputs and outputs can
 * be imported from one place. The read owns the shape, so it is declared beside [useAnkiStats].
 */
export type {AnkiActivityDay};

/** The em dash a figure the source did not answer is shown as, so a gap is never read as a zero. */
const BLANK = "—";

/**
 * One day in milliseconds: the heatmap steps whole days between a cell and the Monday it belongs to,
 * and the "in n days" copy is measured in these.
 */
const DAY_MS = 86_400_000;

/**
 * The three-letter English weekday names, indexed by `Date#getDay()`. Fixed rather than taken from
 * `Intl`: Hermes' internationalisation is not dependable on device, and a chart axis — or the
 * sentence a screen reader reads for a cell — is not the place to find out.
 */
const WEEKDAY_LABELS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

/** The month names the dated copy uses. Also fixed, for the same reason. */
const MONTH_LABELS = [
  "Jan",
  "Feb",
  "Mar",
  "Apr",
  "May",
  "Jun",
  "Jul",
  "Aug",
  "Sep",
  "Oct",
  "Nov",
  "Dec",
];

/** WaniKani's own bucket for subjects nothing has studied yet; excluded from the active spread. */
const NOT_STARTED_KEY = "stage0";

/** How many weeks of activity the grid draws, and so how many columns it has. */
const HEATMAP_WEEKS = 12;

/** Days in one column, Monday first: a column is one local calendar week. */
const HEATMAP_ROWS = 7;

/**
 * The band boundaries used when the window holds too few counted days for quartiles to mean
 * anything: a light day, a real day, a heavy day, and anything past that. Fewer than four counted
 * days cannot describe a spread, so these fixed ones stand in rather than bands that only ever hold
 * one day each.
 */
const FALLBACK_BANDS = [4, 19, 49];

/**
 * The eight bars the "Ahead" section draws, and the hours of WaniKani's summary each one covers.
 * The ranges tile hours 0–24 with no gap and no overlap, so a review lands in exactly one bar and
 * the bars add up to the total the header states.
 */
const AHEAD_BUCKETS: {label: string; firstHour: number; lastHour: number}[] = [
  {label: "Now", firstHour: 0, lastHour: 0},
  {label: "2h", firstHour: 1, lastHour: 2},
  {label: "4h", firstHour: 3, lastHour: 4},
  {label: "8h", firstHour: 5, lastHour: 8},
  {label: "12h", firstHour: 9, lastHour: 12},
  {label: "16h", firstHour: 13, lastHour: 16},
  {label: "20h", firstHour: 17, lastHour: 20},
  {label: "24h", firstHour: 21, lastHour: 24},
];

/**
 * A count as the reader sees it: grouped by thousands, or a dash when the source did not answer.
 * The grouping is done with a string pattern rather than `toLocaleString`, whose output on Hermes is
 * not something a dashboard should depend on.
 */
export function formatCount(value: number | null): string {
  if (value == null) {
    return BLANK;
  }
  return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/**
 * Every card in the collection, from Anki's own Card Counts categories. The four are mutually
 * exclusive — a card is new, learning, a young review or a mature review, never two — so their sum is
 * the collection total. A single category the source would not answer makes the whole total unknown:
 * a partial sum would understate the collection and read as a real figure.
 */
export function collectionTotal(collection: AnkiCollectionCounts): number | null {
  if (
    collection.new == null ||
    collection.learning == null ||
    collection.young == null ||
    collection.mature == null
  ) {
    return null;
  }
  return collection.new + collection.learning + collection.young + collection.mature;
}

/**
 * The stages worth drawing, with the order the payload gave them. WaniKani's "Not started" bucket is
 * left out: it is a large, uninformative slice of the ring, and the count beside the chart states it
 * separately — see [notStartedCount].
 */
export function activeStages(
  stages: {key: string; label: string; count: number}[]
): {key: string; label: string; count: number}[] {
  return stages.filter((stage) => stage.key !== NOT_STARTED_KEY);
}

/** The size of WaniKani's "Not started" bucket, or null when the payload did not name it. */
export function notStartedCount(stages: {key: string; count: number}[]): number | null {
  const notStarted = stages.find((stage) => stage.key === NOT_STARTED_KEY);
  return notStarted == null ? null : notStarted.count;
}

/**
 * Each count as a share of the largest, so the biggest ring slice is a full turn. A list whose largest
 * count is zero — or an empty list — is all zeros rather than a division into NaN or Infinity, so a
 * collection with nothing due cannot produce a broken ring.
 */
export function stageShares(counts: number[]): number[] {
  if (counts.length === 0) {
    return [];
  }
  const largest = Math.max(...counts);
  if (largest <= 0) {
    return counts.map(() => 0);
  }
  return counts.map((count) => count / largest);
}

/**
 * The three upper bounds of the four intensity bands, read off the days that actually counted.
 * Quartiles over the non-zero counts — nearest rank, floored at one — keep one enormous day from
 * flattening every other active day into the same colour: a day is banded against its neighbours in
 * this window, not against a fixed idea of a big day. The bounds are a pure function of the counts
 * passed in, so the same window always paints the same way.
 */
function levelBands(counts: number[]): [number, number, number] {
  if (counts.length < 4) {
    return [FALLBACK_BANDS[0], FALLBACK_BANDS[1], FALLBACK_BANDS[2]];
  }
  const sorted = counts.slice().sort((left, right) => left - right);
  const at = (share: number): number =>
    Math.max(1, sorted[Math.min(sorted.length - 1, Math.ceil(share * sorted.length) - 1)]);
  return [at(0.25), at(0.5), at(0.75)];
}

/** Which of the four bands a counted day falls in. Zero is never banded: it has a level of its own. */
function bandOf(cards: number, bands: [number, number, number]): 1 | 2 | 3 | 4 {
  if (cards <= bands[0]) {
    return 1;
  }
  if (cards <= bands[1]) {
    return 2;
  }
  if (cards <= bands[2]) {
    return 3;
  }
  return 4;
}

/**
 * Midnight on the Monday of the week the instant [at] falls in, as a local timestamp: the boundary a
 * cell's column is measured against. Monday first, because that is the order the grid reads in.
 */
function mondayOf(at: number): number {
  const monday = new Date(at);
  monday.setDate(monday.getDate() - ((monday.getDay() + 6) % 7));
  monday.setHours(0, 0, 0, 0);
  return monday.getTime();
}

/**
 * The local calendar date [daysAgo] days before [now]. Stepping the date rather than subtracting
 * whole days of milliseconds keeps the label on the day the reader's calendar shows it, even across
 * a daylight-saving change.
 */
function dayBefore(now: number, daysAgo: number): Date {
  const day = new Date(now);
  day.setDate(day.getDate() - daysAgo);
  return day;
}

/**
 * The sentence a screen reader reads for one cell: the local date the cell sits on, then what the
 * day held. An answered zero and a day the source would not answer are different facts, so they get
 * different sentences — and the unknown one uses the exact words the design asks for.
 */
function spokenFor(day: Date, cards: number | null): string {
  const date = `${WEEKDAY_LABELS[day.getDay()]} ${day.getDate()} ${MONTH_LABELS[day.getMonth()]}`;
  if (cards == null) {
    return `${date}, Activity unknown`;
  }
  return cards === 0 ? `${date}, No cards` : `${date}, ${cards} cards`;
}

/** One day of the grid: the count that was read, the band it earned, and its own spoken label. */
export interface HeatmapCell {
  /** 0 is AnkiDroid's current study day; the rest are the days before it. */
  daysAgo: number;
  /** Distinct cards answered that day, or null when the provider would not answer it. */
  cards: number | null;
  /** 0 for a confirmed zero, 1–4 for a counted day's band, null for a day that could not be read. */
  level: 0 | 1 | 2 | 3 | 4 | null;
  /** What a screen reader reads for this cell. */
  spoken: string;
}

/** The twelve-week grid, and the summary the page states beside it. */
export interface HeatmapGrid {
  /** Twelve columns, oldest week first; each column holds seven cells, Monday first. Empty slots are null. */
  columns: (HeatmapCell | null)[][];
  /** Days in the window with a confirmed positive count. Null when no day could be read at all. */
  activeDays: number | null;
  /** Distinct cards summed over the days that answered. Null when no day could be read at all. */
  cards: number | null;
  /** True when at least one day in the window could not be read. */
  partial: boolean;
}

/** One day with somewhere to sit in the grid, before it is painted into a cell. */
interface PlacedDay {
  daysAgo: number;
  cards: number | null;
  at: Date;
  column: number;
  row: number;
}

/**
 * Twelve weeks of AnkiDroid's study days as the calendar grid the page draws.
 *
 * The columns are local calendar weeks, Monday first, and the last column is the week `now` falls in,
 * so today sits at its own weekday in the right-hand column and the days after it in that week are
 * empty slots rather than cells. The window's own edge works the same way: a day older than the first
 * column's Monday has no slot to sit in and is dropped, and because the figures and the chart have to
 * be the same set of days — a reader adding up the cells must reach the number printed above them —
 * `activeDays`, `cards` and `partial` are all counted over the days that were placed, never over the
 * whole read window. A few days of read beyond the grid cost nothing and keep the arithmetic simple.
 *
 * Intensity is banded over the placed days that counted, so one huge day cannot make every other
 * active day look empty; a day the source would not answer is neither banded nor counted as a zero,
 * it is simply unknown, and `partial` says that some of the drawn days are missing. Nothing here
 * reads a clock other than the one passed in, so the same window always draws the same way.
 */
export function heatmap(activity: AnkiActivityDay[], now: number): HeatmapGrid {
  const thisMonday = mondayOf(now);
  const placed: PlacedDay[] = [];

  for (const day of activity) {
    const at = dayBefore(now, day.daysAgo);
    // Whole weeks between the cell's Monday and today's, rounded because a daylight-saving change
    // makes the two midnights a few minutes short of seven days apart.
    const weeksBack = Math.round(
      (thisMonday - mondayOf(at.getTime())) / (HEATMAP_ROWS * DAY_MS)
    );
    const column = HEATMAP_WEEKS - 1 - weeksBack;
    if (column < 0 || column >= HEATMAP_WEEKS) {
      continue;
    }
    placed.push({daysAgo: day.daysAgo, cards: day.cards, at, column, row: (at.getDay() + 6) % 7});
  }

  const answered = placed.filter(
    (day): day is PlacedDay & {cards: number} => day.cards != null
  );
  const bands = levelBands(answered.filter((day) => day.cards > 0).map((day) => day.cards));
  const columns: (HeatmapCell | null)[][] = Array.from({length: HEATMAP_WEEKS}, () =>
    Array.from({length: HEATMAP_ROWS}, () => null)
  );

  for (const day of placed) {
    columns[day.column][day.row] = {
      daysAgo: day.daysAgo,
      cards: day.cards,
      level: day.cards == null ? null : day.cards === 0 ? 0 : bandOf(day.cards, bands),
      spoken: spokenFor(day.at, day.cards),
    };
  }

  return {
    columns,
    activeDays: answered.length === 0 ? null : answered.filter((day) => day.cards > 0).length,
    cards:
      answered.length === 0 ? null : answered.reduce((total, day) => total + day.cards, 0),
    partial: placed.some((day) => day.cards == null),
  };
}

/**
 * How fresh the numbers are, as a sentence that keeps itself honest: "Updated now" for a read that
 * has just landed, whole minutes and hours for one still inside the day, and the date once a day has
 * passed. `now` is passed in because the page re-renders this on a timer — the label is a pure
 * function of the two times and never a claim about a moment the read did not happen in.
 */
export function freshnessLabel(at: number | null, now: number): string | null {
  if (at == null) {
    return null;
  }
  const elapsed = now - at;
  if (elapsed < 45_000) {
    return "Updated now";
  }
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 60) {
    // Never "0m": a read that is not "now" is at least a minute old, and rounding down to nothing
    // would claim it was fresher than it is.
    return `Updated ${Math.max(1, minutes)}m ago`;
  }
  const hours = Math.floor(elapsed / 3_600_000);
  if (hours < 24) {
    return `Updated ${hours}h ago`;
  }
  const when = new Date(at);
  return `Updated on ${when.getDate()} ${MONTH_LABELS[when.getMonth()]}`;
}

/**
 * WaniKani's hourly review counts as the eight bars the "Ahead" section draws, in the design's own
 * order. Membership is by hour offset and the ranges tile the day without a gap, so the eight values
 * add up to the same total the section's own figure states. An empty schedule returns the eight
 * labels with null values rather than zeros: a schedule nobody answered is not a quiet one.
 */
export function aheadBuckets(buckets: {count: number}[]): {label: string; value: number | null}[] {
  if (buckets.length === 0) {
    return AHEAD_BUCKETS.map((bucket) => ({label: bucket.label, value: null}));
  }
  return AHEAD_BUCKETS.map((bucket) => ({
    label: bucket.label,
    value: buckets.reduce(
      (total, hour, index) =>
        index >= bucket.firstHour && index <= bucket.lastHour ? total + hour.count : total,
      0
    ),
  }));
}

/**
 * Every review the schedule named, or null when it named none at all. A schedule that answered with
 * zeroes is a real zero and is stated as one; a schedule that did not answer shows a dash instead.
 */
export function aheadTotal(buckets: {count: number}[]): number | null {
  if (buckets.length === 0) {
    return null;
  }
  return buckets.reduce((total, hour) => total + hour.count, 0);
}
