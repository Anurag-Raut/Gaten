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
 * How many level rows the Level progress chart draws: the latest five levels the account has a
 * measurable run at, which is a screenful of comparison rather than a scroll of history.
 */
const LEVEL_ROWS = 5;

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

/** One side of WaniKani's answer record: how many answers were right, out of how many were asked. */
export interface AnswerCounts {
  correct: number;
  total: number;
}

/** An accuracy as the page draws it: the share a bar fills to, and the percentage stated beside it. */
export interface Accuracy {
  /** 0 through 1, or null when there is no accuracy to draw. */
  share: number | null;
  /** The percentage, or a dash when there is none. */
  label: string;
}

/**
 * One side's accuracy — the readings, or the meanings. A side the account was never asked, or one the
 * source reported with no answers at all, is a dash rather than a zero percent: nothing was answered,
 * so nothing was got wrong either, and "0%" would be a claim about answers that do not exist.
 */
export function accuracyOf(side: AnswerCounts | null): Accuracy {
  if (side == null || side.total <= 0) {
    return {share: null, label: BLANK};
  }
  return {share: side.correct / side.total, label: `${Math.round((side.correct / side.total) * 100)}%`};
}

/** Every answer behind WaniKani's accuracy record, and the accuracy the two sides add up to. */
export interface OverallAccuracy extends Accuracy {
  correct: number;
  total: number;
  /** The answers that were not right, which is what the page states under the headline. */
  wrong: number;
}

/**
 * The readings and the meanings added together, weighted by the answers each side was taken over:
 * the accuracy the section leads with. Summing the counts rather than averaging the two percentages
 * is the point — a side with ten answers must not weigh as much as a side with ten thousand — and it
 * is why this is one accuracy and not two. `null` when the record itself could not be read at all,
 * which the section states instead of a figure; a record whose sides were both unasked is a real
 * zero answers, and its percentage is a dash.
 */
export function overallAccuracy(
  recall: {reading: AnswerCounts | null; meaning: AnswerCounts | null} | null
): OverallAccuracy | null {
  if (recall == null) {
    return null;
  }
  const sides = [recall.reading, recall.meaning].filter((side): side is AnswerCounts => side != null);
  const correct = sides.reduce((sum, side) => sum + side.correct, 0);
  const total = sides.reduce((sum, side) => sum + side.total, 0);
  const {share, label} = accuracyOf({correct, total});
  return {correct, total, wrong: total - correct, share, label};
}

/** One subject kind's cumulative answers: radicals, kanji or vocabulary, including kana vocabulary. */
export interface AccuracySubject {
  key: string;
  label: string;
  correct: number;
  total: number;
}

/** One subject kind's accuracy, as the compact "By subject" line states it. */
export interface SubjectAccuracy {
  key: string;
  /** The subject kind's own name ("Kanji"). */
  name: string;
  /** Its accuracy as a percentage, or a dash when it has never been asked. */
  value: string;
}

/**
 * The per-subject accuracy line, in the order the read gave its kinds. A read that did not answer the
 * breakdown at all — an older cache — returns nothing, and the line is left out rather than drawn
 * empty; a kind the account was never asked keeps its place with a dash, because "no answers" and
 * "not measured" are different facts and only one of them is about that kind.
 */
export function subjectAccuracies(subjects: AccuracySubject[] | null): SubjectAccuracy[] {
  if (subjects == null) {
    return [];
  }
  return subjects.map((subject) => ({
    key: subject.key,
    name: subject.label,
    value: accuracyOf({correct: subject.correct, total: subject.total}).label,
  }));
}

/** One run at one level, as WaniKani's level progressions report it, in epoch milliseconds. */
export interface LevelAttempt {
  id: number;
  level: number;
  startedAt: number | null;
  passedAt: number | null;
  abandonedAt: number | null;
}

/** One level's run as the chart draws it: how long it took, and whether it is still running. */
export interface LevelDuration {
  level: number;
  /** Fractional days, which the row states to one decimal. */
  days: number;
  /** True for the level the snapshot says the account is on and has not passed yet. */
  current: boolean;
}

/**
 * The attempt at each level the page measures: the latest one that was not abandoned. A reset abandons
 * the run it interrupts and starts the same level again, so one level can hold several attempts, and
 * only the newest live one is the run the reader actually spent their days on — counting the
 * abandoned ones beside it would count those days twice and report a level that took twice as long.
 */
function liveAttempts(history: LevelAttempt[]): Map<number, LevelAttempt> {
  const chosen = new Map<number, LevelAttempt>();
  for (const attempt of history) {
    if (attempt.abandonedAt != null) {
      continue;
    }
    const held = chosen.get(attempt.level);
    // Latest by start time, and by id when two attempts carry the same one, so the order is total.
    const mine = attempt.startedAt ?? -Infinity;
    const theirs = held?.startedAt ?? -Infinity;
    if (held == null || (mine === theirs ? attempt.id > held.id : mine > theirs)) {
      chosen.set(attempt.level, attempt);
    }
  }
  return chosen;
}

/**
 * How long one attempt took, in fractional days, or null when the record cannot state it. An attempt
 * that never started, a run that ended before it began, and a run dated in the future — or not dated
 * with a number at all — are records the page leaves out rather than measures wrongly. A run with no
 * pass time has not finished, so only the level the snapshot names is measured against `now`: an older
 * unfinished run is history the account moved past, and there is no end to measure it to.
 */
function attemptDays(attempt: LevelAttempt, now: number, currentLevel: number): number | null {
  const {startedAt, passedAt} = attempt;
  if (startedAt == null || !Number.isFinite(startedAt) || startedAt > now) {
    return null;
  }
  if (passedAt == null) {
    return attempt.level === currentLevel ? (now - startedAt) / DAY_MS : null;
  }
  if (!Number.isFinite(passedAt) || passedAt > now || passedAt < startedAt) {
    return null;
  }
  return (passedAt - startedAt) / DAY_MS;
}

/**
 * The duration rows the Level progress chart draws: the latest levels the account has a measurable run
 * at, oldest first, so the chart reads down the way the levels were lived. The level the snapshot says
 * the account is on is `current` while it is unpassed — the row is then a run so far rather than a
 * finished one — and levels above it are left out, because a snapshot's level is the frontier.
 *
 * A level whose only runs were abandoned has no row: those days were unspent, and a bar drawn from
 * them would say a level took time it did not.
 */
export function levelDurations(
  history: LevelAttempt[],
  now: number,
  currentLevel: number
): LevelDuration[] {
  const rows: LevelDuration[] = [];
  for (const attempt of liveAttempts(history).values()) {
    if (attempt.level > currentLevel) {
      continue;
    }
    const days = attemptDays(attempt, now, currentLevel);
    if (days == null) {
      continue;
    }
    rows.push({
      level: attempt.level,
      days,
      current: attempt.level === currentLevel && attempt.passedAt == null,
    });
  }
  rows.sort((left, right) => left.level - right.level);
  return rows.slice(-LEVEL_ROWS);
}

/**
 * How long the account has been on [currentLevel] so far, in fractional days, or null when that
 * cannot be stated — no run recorded at that level, a start date the read did not carry, or a run
 * whose start is dated in the future. Only the level the snapshot names is answered for, and only
 * while it is unpassed: a finished level's days are its duration, not time spent waiting.
 */
export function daysOnLevel(
  history: LevelAttempt[],
  now: number,
  currentLevel: number
): number | null {
  const attempt = liveAttempts(history).get(currentLevel);
  if (attempt == null || attempt.passedAt != null) {
    return null;
  }
  return attemptDays(attempt, now, currentLevel);
}

/**
 * The mean of the finished levels' durations, in fractional days, or null when no level has a
 * measurable run. The level the snapshot names is left out even when its run has a pass time — the
 * average is what a level costs, and the level the account is standing on is the one run whose cost
 * is not settled yet — and so is anything above it, which a snapshot's level makes impossible and a
 * stale history could still carry.
 */
export function averageLevelDays(
  history: LevelAttempt[],
  now: number,
  currentLevel: number
): number | null {
  const days: number[] = [];
  for (const attempt of liveAttempts(history).values()) {
    if (attempt.level >= currentLevel || attempt.passedAt == null) {
      continue;
    }
    const measured = attemptDays(attempt, now, currentLevel);
    if (measured != null) {
      days.push(measured);
    }
  }
  if (days.length === 0) {
    return null;
  }
  return days.reduce((sum, day) => sum + day, 0) / days.length;
}

/**
 * Days on a level as the headline says them: whole days, so "5 days on this level" reads as time
 * elapsed rather than as a measurement — the chart and the average are where the tenths belong. Null
 * when the start is unknown, because a sentence with a hole in it is worse than no sentence at all.
 */
export function daysOnLevelLabel(days: number | null): string | null {
  if (days == null) {
    return null;
  }
  const whole = Math.floor(days);
  if (whole <= 0) {
    return "Less than a day on this level";
  }
  return `${whole} ${whole === 1 ? "day" : "days"} on this level`;
}
