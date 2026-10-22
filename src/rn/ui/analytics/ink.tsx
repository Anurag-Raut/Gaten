/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The borderless building blocks of the Analytics dashboard: the section, the hairline, the numbers,
 * the twelve-week heatmap, the ring, and the bars the screen composes from.
 *
 * The approved direction is Ink without cards. Nothing in this file draws a surface — there is no
 * corner radius on a container, no background behind content, no shadow and no enclosing outline.
 * A number is set apart from its neighbour by whitespace and a single 1 dp hairline, and hierarchy
 * is carried by type size alone: the eyebrow is a whisper, the figure is the loudest type on the
 * page. The only filled shapes permitted are the ones whose fill *is* a value — a heatmap cell, a
 * ring's arc, a chart column, a progress fill, a stacked segment — and what surrounds each one is a
 * hairline-coloured track, a hairline-coloured outline, or nothing at all, so the empty remainder
 * stays visible without a surface being drawn behind it.
 *
 * The geometry follows the approved package (docs/DESIGN_SPEC.md, "borderless editorial"): sections
 * are 22 dp of top padding beneath a hairline with 24 dp above them, the first section starts 10 dp
 * under the view switcher with no rule of its own, metrics are 34 sp with -1.5 tracking, a metric
 * pair is split by one vertical hairline and nothing else, the SRS bar is 5 dp, the column chart is
 * 86 dp tall with lightly rounded column tops, the heatmap is twelve columns of lightly rounded
 * cells with only M/W/F labelled, and the ring is an 84 dp circle with a 9 dp stroke. Colours come
 * from `useTheme` — never a hardcoded hex, never a fixed violet — so the reader's accent repaints
 * every fill and the hairlines follow the light and dark schemes.
 */
import React from "react";
import {
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type DimensionValue,
  type ViewStyle,
} from "react-native";
import Svg, {Circle} from "react-native-svg";
import {PressableScale} from "../PressableScale";
import {useTheme, type ThemeColors} from "../theme";
import {spacing, typeScale} from "../tokens";
import type {HeatmapCell} from "./model";

/** The column chart's default height, in dp: the approved number, and the axis a column is scaled on. */
const DEFAULT_COLUMN_HEIGHT = 86;

/**
 * A bucket the source counted as zero still gets this much column, so an honest zero reads as a zero
 * and never collapses into the same nothing as a bucket the source would not answer.
 */
const MIN_BAR_HEIGHT = 2;

/**
 * The height of one heatmap cell, in dp. The width is deliberately not fixed: the twelve week
 * columns divide whatever the section gives them — about 23 dp on a 392 dp screen and 18 dp on a
 * 320 dp one — so the grid always spans its content width and never scrolls sideways. The height
 * stays put at every width, which is what keeps a cell a wide capsule and a week a row of days
 * rather than a square texture.
 */
const HEATMAP_CELL_HEIGHT = 17;

/**
 * The gutter between two week columns, in dp: the approved 4–5 dp, taken at the wide end because a
 * week boundary is the coarser division of the grid and has to stay visible at a glance.
 */
const HEATMAP_COLUMN_GAP = 5;

/**
 * The gutter between two weekday rows, in dp: tighter than the column gutter, because the seven days
 * inside one week are a closer family than the twelve weeks are to each other.
 */
const HEATMAP_ROW_GAP = 4;

/** The corner of an activity cell, in dp: the approved lightly rounded square. */
const HEATMAP_CELL_RADIUS = 4;

/**
 * The weekday label column, in dp: an `M` at the section-meta size, then the 5 dp gutter that the
 * cells start after. Only `M`, `W` and `F` are painted, but every row keeps this width so the seven
 * rows stay in one column and the unlabelled rows never shift the cells sideways.
 */
const HEATMAP_LABEL_WIDTH = 13;

/** The legend swatch, in dp: the cell's corner at a smaller size, so a day and its key read as one mark. */
const HEATMAP_SWATCH_SIZE = 11;

/**
 * The accent opacities for activity levels 1 to 4, in order. The ramp is carried by opacity on the
 * reader's own accent rather than by four tints mixed here in JS, for the reason the stacked bar's
 * segments give: a tint built by hand drifts from the tone the theme resolved, and a fixed hue would
 * spend the one colour this page is not allowed to spend. Level 0 is deliberately absent — a
 * confirmed zero is `surfaceVariant`, so it can never be mistaken for the lightest step of activity.
 */
const HEATMAP_LEVEL_OPACITY = [0.2, 0.42, 0.67, 1] as const;

/** The legend's swatches, in the order the cells use them: the confirmed zero first, then the four steps. */
const HEATMAP_LEGEND_LEVELS = [0, 1, 2, 3, 4] as const;

/**
 * The grid's seven rows, Monday first, one entry per row: `M`, `W` and `F` are labelled because three
 * words are enough to read the axis as a calendar, where seven would crowd the cells' left edge for
 * no information the reader needs twice. The array's length is the grid's row count.
 */
const HEATMAP_ROW_LABELS: (string | null)[] = ["M", null, "W", null, "F", null, null];

/** The ring's default diameter, in dp: the approved 84 dp, the size at which the figure inside stays legible. */
const RING_SIZE = 84;

/** The ring's default stroke, in dp: the approved 9 dp, heavy enough that a short arc still reads as a gauge. */
const RING_STROKE = 9;

/**
 * The figure inside the ring as a fraction of the ring's diameter: 0.24 sets a 20 sp number in an
 * 84 dp ring with its caption beneath, and keeps that proportion if a caller asks for a larger ring.
 */
const RING_CENTRE_RATIO = 0.24;

/**
 * One titled group of numbers. The hairline over the group and the whitespace around it are what
 * separate it from its neighbour — the card that used to do that job is gone, so the rule and the
 * rhythm have to carry the grouping alone.
 *
 * `first` lifts the rule and the top padding for the section that opens the page: the view switcher's
 * own 1 dp underline already sits directly above it, and a second line there would read as a doubled
 * edge. The first section starts 10 dp under that switcher; every later one is 24 dp below the
 * content before it and begins its own rule 22 dp above its content.
 */
export function AnalyticsSection({
  title,
  meta,
  first = false,
  children,
}: {
  title: string;
  meta?: string | null;
  first?: boolean;
  children: React.ReactNode;
}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View
      style={[
        styles.section,
        first
          ? {marginTop: 10}
          : {
              borderTopWidth: 1,
              borderTopColor: colors.outlineVariant,
              paddingTop: spacing.contentInset,
              marginTop: spacing.xl,
            },
      ]}
    >
      <View style={styles.sectionHeader}>
        <Text
          accessibilityRole="header"
          style={[typeScale.titleMedium, styles.sectionTitle, {color: colors.onSurface}]}
        >
          {title}
        </Text>
        {meta != null && meta !== "" ? (
          // The scope note is capped at 44% of the header so a long deck path cannot crowd the title
          // off the line; two lines are allowed rather than truncating a deck that needs both.
          <Text
            numberOfLines={2}
            style={[typeScale.labelSmall, styles.sectionMeta, {color: colors.onSurfaceVariant}]}
          >
            {meta}
          </Text>
        ) : null}
      </View>
      <View style={styles.sectionBody}>{children}</View>
    </View>
  );
}

/**
 * A full-width 1 dp hairline with 17 dp of air on each side. It is the divider for everything that is
 * not a range of content — the seam between the freshness line and the figures, or between a chart
 * and the note that explains it — where a whole section would be too much structure.
 */
export function ThinRule(): React.JSX.Element {
  const {colors} = useTheme();
  return <View style={[styles.thinRule, {backgroundColor: colors.outlineVariant}]} />;
}

/** One number the dashboard states: an optional eyebrow, the figure, and what it counts. */
export interface MetricBlock {
  /** The source or scope the figure belongs to, set as an uppercase label above it. */
  eyebrow?: string | null;
  /** The figure itself, already formatted by the screen. */
  value: string;
  /** What the figure counts, directly under it. */
  caption: string;
  /** A second line of context, a step below the caption. */
  note?: string | null;
  /** Draw the figure in the quiet ink: a number that is real but not the point of the section. */
  muted?: boolean;
  /** Use the display size. Off drops to the metric scale for a figure that must not shout. */
  hero?: boolean;
}

/**
 * A single metric: the eyebrow, the figure and its caption, nothing enclosing them.
 *
 * Type size is the whole hierarchy here — the figure is 34 sp with -1.5 tracking so it reads as a
 * display number, and everything around it is body-small. The block is one accessibility node, so a
 * screen reader hears "Anki, 31 cards waiting, 5 new · 3 learning · 23 review" as a single statement
 * instead of four loose fragments.
 */
export function Metric({
  eyebrow,
  value,
  caption,
  note,
  muted = false,
  hero = true,
}: MetricBlock): React.JSX.Element {
  const {colors} = useTheme();
  const figureColor = muted ? colors.onSurfaceVariant : colors.onSurface;
  // The eyebrow is named first, then the figure with its caption, then the note — the order the eye
  // reads them, so the spoken label and the drawn block agree.
  const label = [eyebrow, `${value} ${caption}`, note]
    .filter((part): part is string => part != null && part !== "")
    .join(", ");
  return (
    <View accessible accessibilityLabel={label} style={styles.metric}>
      {eyebrow != null && eyebrow !== "" ? (
        <Text style={[styles.metricEyebrow, {color: colors.onSurfaceVariant}]}>{eyebrow}</Text>
      ) : null}
      <Text style={[hero ? styles.metricHero : typeScale.metric, {color: figureColor}]}>
        {value}
      </Text>
      <Text style={[typeScale.bodySmall, styles.metricCaption, {color: colors.onSurfaceVariant}]}>
        {caption}
      </Text>
      {note != null && note !== "" ? (
        <Text style={[typeScale.bodySmall, styles.metricNote, {color: colors.onSurfaceVariant}]}>
          {note}
        </Text>
      ) : null}
    </View>
  );
}

/**
 * Two metrics side by side, split by exactly one vertical hairline. There is no outer border and no
 * background: the single line between the figures is the whole boundary, and 18 dp of inset on the
 * right figure keeps its text off that line while 12 dp on the left keeps it off the page edge.
 */
export function MetricPair({
  left,
  right,
}: {
  left: MetricBlock;
  right: MetricBlock;
}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View style={styles.metricPair}>
      <View style={[styles.metricPairSide, styles.metricPairLeft]}>
        <Metric {...left} />
      </View>
      <View
        style={[
          styles.metricPairSide,
          styles.metricPairRight,
          {borderLeftWidth: 1, borderLeftColor: colors.outlineVariant},
        ]}
      >
        <Metric {...right} />
      </View>
    </View>
  );
}

/**
 * A plain label-and-value line, sized like a list row rather than a card. It draws its own 1 dp
 * bottom hairline on every row, including the last one: the alternative — a `last` prop threaded
 * through every caller — would put a layout decision in the screen's hands for no gain, and a
 * trailing rule under the final row of a section is the quieter cost. The screen composes these so
 * that a trailing rule is acceptable where it lands.
 */
export function MetricRow({label, value}: {label: string; value: string}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View style={[styles.metricRow, {borderBottomColor: colors.outlineVariant}]}>
      <Text style={[typeScale.bodySmall, styles.metricRowLabel, {color: colors.onSurfaceVariant}]}>
        {label}
      </Text>
      <Text style={[typeScale.bodyMedium, styles.metricRowValue, {color: colors.onSurface}]}>
        {value}
      </Text>
    </View>
  );
}

/**
 * A bare progress track: a hairline-coloured channel with a coloured fill and nothing around it.
 * The track is `colors.outlineVariant` — the surface colour family is deliberately off limits in this
 * layout, so the empty remainder is drawn as a rule rather than as a filled panel. The share is
 * clamped so a ratio taken against a zero total cannot draw a bar wider than its track or a negative
 * one.
 */
export function InlineBar({
  share,
  color,
  height = 7,
}: {
  share: number;
  color: string;
  height?: number;
}): React.JSX.Element {
  const {colors} = useTheme();
  const clamped = Number.isFinite(share) ? Math.min(1, Math.max(0, share)) : 0;
  return (
    <View style={[styles.inlineBar, {height, backgroundColor: colors.outlineVariant}]}>
      <View
        style={[styles.inlineBarFill, {width: `${clamped * 100}%` as DimensionValue, backgroundColor: color}]}
      />
    </View>
  );
}

/**
 * One row of an SRS list: a fixed label column, a thin bar, and a fixed count column. The columns are
 * pinned at 72 dp and 32 dp with a 10 dp gap so every bar in the list starts and ends on the same two
 * lines, which is what lets the eye compare rows; the bar itself is the 5 dp hairline height. The row
 * is one accessibility node reading "Apprentice: 183", never the label and the number separately.
 */
export function BarRow({
  label,
  count,
  share,
  color,
}: {
  label: string;
  count: string;
  share: number;
  color: string;
}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View accessible accessibilityLabel={`${label}: ${count}`} style={styles.barRow}>
      <Text numberOfLines={1} style={[typeScale.labelSmall, styles.barRowLabel, {color: colors.onSurfaceVariant}]}>
        {label}
      </Text>
      <View style={styles.barRowTrack}>
        <InlineBar share={share} color={color} height={5} />
      </View>
      <Text style={[typeScale.labelSmall, styles.barRowCount, {color: colors.onSurface}]}>{count}</Text>
    </View>
  );
}

/**
 * The column chart: one column per bucket of a series — a day of study, an hour of the coming day's
 * schedule — standing on a shared baseline. A bucket the source answered is a column whose height is
 * its share of the series' busiest bucket; a bucket it answered zero for is a 2 dp stub, so an honest
 * zero is visibly not the same as an empty bucket; a `null` bucket draws no column at all. That last
 * distinction is the point of the chart — a missing reading must never be painted as a zero, so the
 * column exists only when a number does.
 *
 * `height` is both the axis and the drawing height: the study-activity week is the approved 86 dp,
 * and a section that wants a shorter chart — the Ahead schedule — passes its own number rather than
 * letting a tall chart be scaled down, which would blur its 2 dp stub into the baseline. The
 * component is deliberately unit-agnostic, so the spoken label is the bucket and its count and never
 * names a unit the caller did not: the same chart carries Anki's cards and WaniKani's scheduled
 * reviews, and those are two different things.
 *
 * There is no animation and no enclosing surface: the design's motion budget belongs to the screen,
 * and a chart that springs on every re-read would compete with it. Each column is one accessibility
 * node, spoken as its bucket and count, or as "no reading" when the number is missing.
 */
export function ColumnBars({
  points,
  height = DEFAULT_COLUMN_HEIGHT,
}: {
  points: {label: string; value: number | null}[];
  height?: number;
}): React.JSX.Element {
  const {colors} = useTheme();
  // The busiest bucket sets the axis. A series with nothing positive leaves `max` at 0, and then
  // every counted bucket falls back to the minimum stub rather than dividing by zero. The same 2 dp
  // floor applies to a positive bucket too small to round to a pixel: a bucket the source answered
  // with a one must still be a visible mark, and never indistinguishable from one it could not
  // answer at all.
  const max = points.reduce(
    (top, point) => (point.value != null && point.value > top ? point.value : top),
    0,
  );
  const columnHeight = (value: number): number =>
    max <= 0 ? MIN_BAR_HEIGHT : Math.max(MIN_BAR_HEIGHT, Math.round((value / max) * height));
  return (
    <View>
      <View style={[styles.columnChart, {height, borderBottomColor: colors.outlineVariant}]}>
        {points.map((point, index) => (
          <View
            key={index}
            accessible
            accessibilityLabel={
              point.value == null ? `${point.label}: no reading` : `${point.label}: ${point.value}`
            }
            style={styles.columnChartSlot}
          >
            {point.value == null ? null : (
              <View
                style={[
                  styles.columnChartBar,
                  {height: columnHeight(point.value), backgroundColor: colors.accent},
                ]}
              />
            )}
          </View>
        ))}
      </View>
      <View style={styles.columnChartLabels}>
        {points.map((point, index) => (
          <Text key={index} style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>
            {point.label}
          </Text>
        ))}
      </View>
    </View>
  );
}

/**
 * A share of a whole as a ring: a closed hairline-coloured track with an accent arc laid over it,
 * starting at twelve o'clock and running clockwise. The track is drawn in full whatever the share, so
 * the part that is not filled stays a visible remainder rather than a gap — and a ring with nothing to
 * report is still a ring, because `null` draws the track alone. That is the unknown state: the shape
 * is there, the value is not, and nothing was invented to fill it.
 *
 * The geometry is two `Circle`s: the track, and an arc whose dash length is the share of the
 * circumference with the rest as its gap, rotated a quarter turn back so the arc begins at the top.
 * The ring is the one place in this file where `react-native-svg` is needed — a share that starts at
 * an arbitrary angle is a path, not a stack of rectangles — and it is the same dependency the app
 * already ships for its artwork.
 */
export function Ring({
  share,
  centre,
  caption = null,
  size = RING_SIZE,
  stroke = RING_STROKE,
}: {
  share: number | null;
  centre: string;
  caption?: string | null;
  size?: number;
  stroke?: number;
}): React.JSX.Element {
  const {colors} = useTheme();
  // A stroke straddles the path it draws, so the radius gives up half of it to keep the whole ring
  // inside the square the caller lays out; the track and the arc share that one radius so the arc
  // sits exactly on the track and never inside or outside it.
  const radius = Math.max(0, (size - stroke) / 2);
  const circumference = 2 * Math.PI * radius;
  // A share that is not a finite number at all is treated as no share: a ring built from an empty
  // division draws its track and stops, rather than guessing at zero or at a full turn.
  const clamped = share == null || !Number.isFinite(share) ? null : Math.min(1, Math.max(0, share));
  const arc = clamped == null ? 0 : circumference * clamped;
  // The figure is set as a fraction of the diameter so the same component reads correctly at any
  // size the caller asks for, and the caption stays at the body size: it is a unit, not a second
  // figure.
  const centreSize = Math.round(size * RING_CENTRE_RATIO);
  // The figure and its caption are one statement — "68% mature" — so the ring is a single
  // accessibility node instead of two loose fragments inside a circle.
  const label = caption != null && caption !== "" ? `${centre} ${caption}` : centre;
  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel={label}
      style={[styles.ring, {width: size, height: size}]}
    >
      <Svg width={size} height={size}>
        <Circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={colors.outlineVariant}
          strokeWidth={stroke}
        />
        {clamped == null ? null : (
          <Circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke={colors.accent}
            strokeWidth={stroke}
            strokeDasharray={`${arc} ${circumference - arc}`}
            strokeDashoffset={0}
            // SVG starts a dash at three o'clock; a quarter turn back puts this arc's head at the
            // top, and a positive dash then sweeps clockwise from there.
            transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
        )}
      </Svg>
      {/* The figure sits over the arc's opening rather than under it, and the overlay never takes a
          touch: the ring reports itself, and nothing inside it is a control. */}
      <View style={styles.ringCentre} pointerEvents="none">
        <Text
          numberOfLines={1}
          style={[
            styles.ringValue,
            {fontSize: centreSize, lineHeight: Math.round(centreSize * 1.2), color: colors.onSurface},
          ]}
        >
          {centre}
        </Text>
        {caption != null && caption !== "" ? (
          <Text
            numberOfLines={1}
            style={[typeScale.bodySmall, styles.ringCaption, {color: colors.onSurfaceVariant}]}
          >
            {caption}
          </Text>
        ) : null}
      </View>
    </View>
  );
}

/**
 * The fill of one activity cell at one level, shared by the grid and its legend so the key can never
 * drift from the marks it explains.
 *
 * Unknown is an outline and no fill at all: a day the source would not answer must not be painted as
 * a zero, and the empty outlined square is what says "there is a day here that nobody read". A
 * confirmed zero is the quiet neutral — real, present, and unmistakably not activity. Levels 1 to 4
 * are the reader's own accent at four opacities, so the ramp is a tint of one hue and never a second
 * colour on the page.
 */
function heatmapCellFill(level: HeatmapCell["level"], colors: ThemeColors): ViewStyle {
  // Unknown is the absence of a reading, so it is drawn as an empty outline; a confirmed zero is a
  // reading, so it is filled. The fill is the hairline tone rather than the subtler surface tone,
  // because on the page's own paper a zero has to be visible as a day that happened and counted
  // nothing — otherwise a quiet month and an unread month look the same.
  if (level == null) return {borderWidth: 1, borderColor: colors.outlineVariant};
  if (level === 0) return {backgroundColor: colors.outlineVariant};
  return {backgroundColor: colors.accent, opacity: HEATMAP_LEVEL_OPACITY[level - 1]};
}

/**
 * What the whole grid says to a screen reader, built here from the cells because the caller has no
 * way to hand the chart a label of its own. It states the three things the grid cannot be asked for
 * one cell at a time: how many days were studied, how many distinct cards those days came to — only
 * from the days that answered, so a figure the source could not supply is never silently added as a
 * zero — and how many days could not be read at all, which is said out loud because a partial window
 * makes a smaller claim than a complete one.
 *
 * Empty slots are not days: a `null` entry pads the start and the end of the twelve weeks and is left
 * out of every count. A cell that exists with no count is an unknown day and is counted as one.
 */
function heatmapSummary(columns: (HeatmapCell | null)[][]): string {
  let studiedDays = 0;
  let cards = 0;
  let unknownDays = 0;
  for (const column of columns) {
    for (const cell of column) {
      if (cell == null) continue;
      if (cell.cards == null) {
        unknownDays += 1;
        continue;
      }
      if (cell.cards > 0) studiedDays += 1;
      cards += cell.cards;
    }
  }
  const parts = [
    `${studiedDays} ${studiedDays === 1 ? "day" : "days"} studied`,
    `${cards} ${cards === 1 ? "card" : "cards"}`,
  ];
  if (unknownDays > 0) parts.push(`${unknownDays} ${unknownDays === 1 ? "day" : "days"} not read`);
  return `Study activity, last twelve weeks: ${parts.join(", ")}`;
}

/**
 * Twelve weeks of study as a heatmap: one cell per Anki study day, twelve columns read left to right
 * (oldest week first) and seven rows read top to bottom (Monday first). A week is a column because
 * the eye compares weeks down the grid and days across it, and because a column of seven is what lets
 * twelve weeks fit a phone at a glance.
 *
 * Only `M`, `W` and `F` are labelled, and every row keeps that label column's width whether it has a
 * word in it or not: the axis has to read as a calendar without seven labels crowding the edge, and
 * the unlabelled rows must not shift the cells sideways. The cells take their width from the space
 * left over — twelve of them plus their gutters fill the section at any width from 320 dp up, so the
 * grid never scrolls — while their height stays fixed, which is what keeps a cell a wide capsule.
 *
 * The whole grid is one accessibility node. Eighty-four focusable squares would be a wall of
 * fragments to swipe through, and the summary the grid builds for itself — days studied, cards,
 * days that could not be read — is the claim the chart is actually making. The legend repeats the
 * cell fills rather than restating them, so the key cannot drift from the marks.
 */
export function Heatmap({columns}: {columns: (HeatmapCell | null)[][]}): React.JSX.Element {
  const {colors} = useTheme();
  // The weekday column has to grow with the reader's text size: at 160 % a fixed 13 dp column cuts the
  // M/W/F labels in half, and the twelve columns of cells simply give up the few dp it needs (they are
  // `flex: 1`, so the grid never overflows).
  const {fontScale} = useWindowDimensions();
  const labelWidth = Math.ceil(HEATMAP_LABEL_WIDTH * fontScale);
  return (
    <View accessible accessibilityRole="image" accessibilityLabel={heatmapSummary(columns)}>
      <View style={styles.heatmapGrid}>
        {HEATMAP_ROW_LABELS.map((label, rowIndex) => (
          <View key={rowIndex} style={styles.heatmapRow}>
            {label == null ? (
              // An unlabelled row still reserves the label column, so all seven rows of cells start
              // on the same vertical line.
              <View style={[styles.heatmapRowLabel, {width: labelWidth}]} />
            ) : (
              <Text
                style={[
                  typeScale.labelSmall,
                  styles.heatmapRowLabel,
                  styles.heatmapRowLabelText,
                  {width: labelWidth, color: colors.onSurfaceVariant},
                ]}
              >
                {label}
              </Text>
            )}
            {columns.map((column, columnIndex) => {
              const cell = column[rowIndex] ?? null;
              return (
                <View
                  key={columnIndex}
                  style={[
                    styles.heatmapCell,
                    // A `null` entry is a slot outside the twelve weeks, not an unread day: it stays
                    // empty so the grid's edge is ragged in exactly the way the calendar is.
                    cell == null ? null : heatmapCellFill(cell.level, colors),
                  ]}
                />
              );
            })}
          </View>
        ))}
      </View>
      <View style={styles.heatmapLegend}>
        <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>Less</Text>
        <View style={styles.heatmapSwatches}>
          {HEATMAP_LEGEND_LEVELS.map((level) => (
            <View key={level} style={[styles.heatmapSwatch, heatmapCellFill(level, colors)]} />
          ))}
        </View>
        <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>More</Text>
      </View>
    </View>
  );
}

/**
 * The counts under a distribution bar: each item is its figure over its label, and the row wraps when
 * the type is scaled up rather than letting one column crush another. The row spreads across the
 * width the bar above it spans, so a label sits under the segment it names instead of in a list of
 * its own; when the items wrap, the last line keeps the same spread and reads as a continuation.
 *
 * A count is a figure and not prose: it is set at the value size in the strongest ink, with its label
 * beneath it at the section-meta size in the quiet ink — the same two-step the metrics use, at one
 * scale down. Each item is one accessibility node reading "312 New", so a screen reader never has to
 * pair a floating number with the word under it.
 */
export function CountLegend({
  items,
}: {
  items: {key: string; label: string; value: string}[];
}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View style={styles.countLegend}>
      {items.map((item) => (
        <View
          key={item.key}
          accessible
          accessibilityLabel={`${item.value} ${item.label}`}
          style={styles.countLegendItem}
        >
          <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{item.value}</Text>
          <Text
            numberOfLines={1}
            style={[typeScale.labelSmall, styles.countLegendLabel, {color: colors.onSurfaceVariant}]}
          >
            {item.label}
          </Text>
        </View>
      ))}
    </View>
  );
}

/**
 * One proportional bar for parts of a whole: the segments take width by their counts and the
 * hairline-coloured remainder shows what is left, all in a single 9 dp capsule. The bar carries the
 * whole statement for a screen reader — every segment named and counted — and the visible legend
 * belongs to the caller, because the two charts that use this bar label their parts differently
 * (Anki's four card states against WaniKani's five SRS stages). Zero-count segments are dropped from
 * the bar: they would be invisible anyway, and the legend beside it still says "Guru 0".
 */
export function StackedBar({
  segments,
}: {
  segments: {key: string; label: string; count: number; color: string; opacity?: number}[];
}): React.JSX.Element {
  const {colors} = useTheme();
  const drawn = segments.filter((segment) => segment.count > 0);
  const summary = segments.map((segment) => `${segment.label} ${segment.count}`).join(", ");
  return (
    <View>
      <View
        accessibilityRole="image"
        accessibilityLabel={summary}
        style={[styles.stackedBar, {backgroundColor: colors.outlineVariant}]}
      >
        {drawn.map((segment) => (
          <View
            key={segment.key}
            style={[
              styles.stackedSegment,
              {flexGrow: segment.count, backgroundColor: segment.color},
              // The ramp between the parts of one bar is carried by opacity rather than by three
              // different hues: the reader's accent is the only hue the page is allowed to spend,
              // and a tint built here in JS would drift from the tones the theme resolved.
              segment.opacity != null && {opacity: segment.opacity},
            ]}
          />
        ))}
      </View>
    </View>
  );
}

/**
 * The honest "we cannot read this" block: a line of explanation fenced above and below by the same
 * hairline the sections use, and an optional text action under it. It is not a banner and not a card —
 * there is no fill and no radius — because unavailability is not a thing to look at, it is a thing to
 * read and move past. The action is a plain accent-coloured text with a full-height 48 dp target,
 * never a filled button.
 */
export function InlineUnavailable({
  children,
  action,
}: {
  children: React.ReactNode;
  action?: {label: string; onPress: () => void};
}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View
      style={[
        styles.unavailable,
        {borderTopColor: colors.outlineVariant, borderBottomColor: colors.outlineVariant},
      ]}
    >
      <Text style={[typeScale.bodySmall, styles.unavailableText, {color: colors.onSurfaceVariant}]}>
        {children}
      </Text>
      {action != null ? (
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel={action.label}
          onPress={action.onPress}
          style={styles.unavailableAction}
        >
          <Text style={[typeScale.labelLarge, {color: colors.accent}]}>{action.label}</Text>
        </PressableScale>
      ) : null}
    </View>
  );
}

/**
 * Flat placeholder bars for type that has not loaded. Three widths cycle so a stack reads as unloaded
 * text rather than as one solid block, and the radius is explicitly zero so a placeholder can never
 * drift into looking like a card. There is no shimmer and no animation: motion belongs to the screen,
 * and a placeholder should sit still until the real numbers arrive.
 */
export function SkeletonLines({lines = 2}: {lines?: number}): React.JSX.Element {
  const {colors} = useTheme();
  const widths: DimensionValue[] = ["100%", "62%", "84%"];
  return (
    <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
      {Array.from({length: lines}, (_, index) => (
        <View
          key={index}
          style={[
            styles.skeletonLine,
            {width: widths[index % widths.length], backgroundColor: colors.surfaceVariant},
          ]}
        />
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  section: {width: "100%"},
  sectionHeader: {
    flexDirection: "row",
    alignItems: "flex-start",
    justifyContent: "space-between",
    gap: spacing.s,
    marginBottom: 17,
  },
  sectionTitle: {flexShrink: 1},
  sectionMeta: {maxWidth: "44%", textAlign: "right"},
  sectionBody: {gap: spacing.m},
  thinRule: {width: "100%", height: 1, marginVertical: 17},
  metric: {minWidth: 0},
  // An 11 sp label tracked 1.2 px and set uppercase: small enough to stay quiet beside a 34 sp figure,
  // open enough to read as a label rather than as squeezed body text.
  metricEyebrow: {
    fontSize: 11,
    lineHeight: 14,
    fontWeight: "700",
    letterSpacing: 1.2,
    textTransform: "uppercase",
  },
  metricHero: {fontSize: 34, lineHeight: 38, fontWeight: "700", letterSpacing: -1.5},
  metricCaption: {marginTop: 5},
  metricNote: {marginTop: 8},
  metricPair: {flexDirection: "row", alignItems: "flex-start"},
  metricPairSide: {flex: 1, minWidth: 0},
  metricPairLeft: {paddingRight: 12},
  metricPairRight: {paddingLeft: 18},
  metricRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    gap: spacing.s,
    paddingVertical: 15,
    borderBottomWidth: 1,
  },
  metricRowLabel: {flexShrink: 1},
  metricRowValue: {fontWeight: "700"},
  inlineBar: {width: "100%", borderRadius: 8, overflow: "hidden"},
  inlineBarFill: {height: "100%"},
  barRow: {flexDirection: "row", alignItems: "center", gap: 10},
  // The label column is only as wide as the words a row carries ("Reading", "Meaning"): the track is
  // the point of the row, and every dp the label does not need is a dp the bar gets. At the page's
  // half-width the three columns leave the track about 70 dp, which is what the design measures.
  barRowLabel: {width: 56},
  barRowTrack: {flex: 1, minWidth: 0},
  barRowCount: {width: 34, textAlign: "right", fontWeight: "700"},
  // The chart's own height is set inline, because `height` is the caller's axis: a fixed column lane
  // and an 18 dp bar do not change with it.
  columnChart: {
    flexDirection: "row",
    alignItems: "flex-end",
    justifyContent: "space-between",
    gap: spacing.s,
    borderBottomWidth: 1,
  },
  // Full height so a missing bucket still reserves its lane, and so every lane is an 18 dp column the
  // screen reader can reach rather than a zero-height sliver.
  columnChartSlot: {width: 18, height: "100%", justifyContent: "flex-end"},
  columnChartBar: {width: 18, borderTopLeftRadius: 5, borderTopRightRadius: 5, opacity: 0.7},
  columnChartLabels: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingTop: 7,
  },
  // Twelve columns of seven rows. The gaps are the size of the hole between two cells, and the rows
  // share the grid's width because the cells flex — that is what keeps the grid inside a 320 dp
  // section without a scroll or a second layout for narrow screens.
  heatmapGrid: {flexDirection: "column", rowGap: HEATMAP_ROW_GAP},
  heatmapRow: {
    flexDirection: "row",
    alignItems: "stretch",
    height: HEATMAP_CELL_HEIGHT,
    gap: HEATMAP_COLUMN_GAP,
  },
  heatmapRowLabel: {width: HEATMAP_LABEL_WIDTH},
  heatmapRowLabelText: {lineHeight: HEATMAP_CELL_HEIGHT},
  heatmapCell: {flex: 1, minWidth: 0, borderRadius: HEATMAP_CELL_RADIUS},
  heatmapLegend: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "flex-end",
    gap: spacing.s,
    marginTop: spacing.s,
  },
  // The swatches sit tight against each other so they read as one five-step ramp rather than as five
  // loose marks, with the two words set off by the legend's own wider gap.
  heatmapSwatches: {flexDirection: "row", alignItems: "center", gap: spacing.xs},
  heatmapSwatch: {width: HEATMAP_SWATCH_SIZE, height: HEATMAP_SWATCH_SIZE, borderRadius: HEATMAP_CELL_RADIUS},
  // The ring is a plain square that centres its own figure: the SVG draws the track and the arc, and
  // the number sits over the middle of both.
  ring: {alignItems: "center", justifyContent: "center"},
  ringCentre: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    alignItems: "center",
    justifyContent: "center",
  },
  ringValue: {fontWeight: "700"},
  ringCaption: {marginTop: 1},
  // Spread across the width the distribution bar above spans, so a count sits under the segment it
  // names; the gaps only matter once the row wraps at a larger type scale.
  countLegend: {
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "space-between",
    columnGap: spacing.m,
    rowGap: spacing.s,
  },
  countLegendItem: {minWidth: 0},
  countLegendLabel: {marginTop: 2},
  stackedBar: {height: 9, flexDirection: "row", borderRadius: 9, overflow: "hidden"},
  stackedSegment: {height: "100%"},
  unavailable: {borderTopWidth: 1, borderBottomWidth: 1, paddingVertical: 10},
  unavailableText: {lineHeight: 20},
  // A 48 dp target that hugs the label: the action gets a full-height touch area without becoming a
  // block the eye would read as a control surface.
  unavailableAction: {minHeight: 48, alignSelf: "flex-start", justifyContent: "center"},
  skeletonLine: {height: 12, borderRadius: 0, marginBottom: 10},
});
