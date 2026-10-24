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
 * ring's arc, a progress fill, a stacked segment — and what surrounds each one is a hairline-coloured
 * track, a hairline-coloured outline, or nothing at all, so the empty remainder stays visible without
 * a surface being drawn behind it.
 *
 * The geometry follows the approved package (docs/DESIGN_SPEC.md, "borderless editorial"): sections
 * are 22 dp of top padding beneath a hairline with 24 dp above them, the first section starts 10 dp
 * under the view switcher with no rule of its own, metrics are 34 sp with -1.5 tracking, a metric
 * pair is split by one vertical hairline and nothing else, the bar rows are a 5 dp track, the heatmap
 * is twelve columns of lightly rounded cells with only M/W/F labelled, and the ring is an 84 dp
 * circle with a 9 dp stroke. Colours come from `useTheme` — never a hardcoded hex, never a fixed
 * violet — so the reader's accent repaints every fill and the hairlines follow the light and dark
 * schemes.
 */
import React, {useEffect, useRef, useState} from "react";
import {
  InteractionManager,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type DimensionValue,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import Svg, {Circle, type CircleProps} from "react-native-svg";
import {useIsFocused} from "@react-navigation/native";
import Animated, {
  Easing,
  FadeIn,
  FadeOut,
  interpolateColor,
  useAnimatedProps,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
  type SharedValue,
} from "react-native-reanimated";
import {PressableScale} from "../PressableScale";
import {useTheme, type ThemeColors} from "../theme";
import {PRESS_EASING_REANIMATED, spacing, typeScale} from "../tokens";
import {formatCount, type HeatmapCell} from "./model";

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
 * The key of a distribution segment, in dp: a small square before the category's name, drawn in the
 * segment's own tone and opacity so the bar and the key under it cannot be read two ways.
 */
const DISTRIBUTION_SWATCH_SIZE = 8;

/**
 * The usable touch height of a distribution category in the bar, in dp: a real 48 dp target with the
 * 9 dp capsule centred inside it. The bar is too thin to hit reliably, and an Android touch target
 * cannot reach outside the bounds of the view that owns it, so the wrapper itself is this tall and
 * the hit layer is a normal child filling it — not a negative inset hanging out of the parent. The
 * segments' widths, and so the drawn proportions, are untouched; only the empty space above and
 * below the capsule is new.
 */
const DISTRIBUTION_TOUCH_HEIGHT = 48;

/**
 * How strongly the categories other than the selected one are faded while a selection is active. The
 * selected segment is painted at full accent instead, so the reader's choice is the strongest mark in
 * the bar and the rest recede; the segments' widths — the counts they carry — are untouched.
 */
const DISTRIBUTION_DIM_OPACITY = 0.35;

/** The height of the whole stacked bar, in dp: the pill both layers and the outer clip share. */
const DISTRIBUTION_BAR_HEIGHT = 9;

/**
 * Every painted stage layer is a full pill at its own cumulative width, so both ends of every layer
 * are round; the layers above cover the left caps of the layers below, and the outer pill clips the
 * one right cap that should show.
 */
const DISTRIBUTION_LAYER_RADIUS = DISTRIBUTION_BAR_HEIGHT / 2;

/**
 * The analytics entrance: one deliberately slow, decelerating sweep shared by every figure and fill
 * on a screen that has just come into view with data ready. The cubic Bézier spends most of its
 * distance early and then settles for the rest of the duration; reduced motion sets the same value
 * straight to its final state.
 */
const ANALYTICS_ENTRANCE_MS = 1100;
const ANALYTICS_ENTRANCE_EASING = Easing.bezier(0.23, 1, 0.32, 1);

/** The count-up text is a normal Text, driven from JS at a bounded rate; 33 ms is about 30 Hz. */
const COUNT_UPDATE_MS = 33;

/** How long a selected/dimmed state takes to settle, in ms: short enough to feel immediate. */
const DISTRIBUTION_SELECT_MS = 160;

/** The ring's arc and value settle a touch slower than a tap, so a changed share reads as a sweep. */
const RING_SELECT_MS = 180;

/** The heatmap's selected-day ring and tooltip fade: one short beat behind the tap. */
const HEATMAP_SELECT_MS = 140;

/** The gap between the tapped day and its tooltip, and the tooltip's own width bounds, in dp. */
const TOOLTIP_GAP = 6;
const TOOLTIP_MIN_WIDTH = 120;
const TOOLTIP_MAX_WIDTH = 220;

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
 * The accent arc as an animated SVG circle: the dash that carries a share moves on the UI thread, so
 * a refreshed figure sweeps to its new length instead of jumping there.
 */
const AnimatedCircle = Animated.createAnimatedComponent(Circle);

/**
 * One entrance shared value per data source. The animation starts once, when the screen is focused
 * and that source has answered; a refresh, a legend tap, the freshness clock or a text-size change
 * never restarts it. A component that mounts after the sweep is already at 1 renders its final state.
 */
export function useAnalyticsEntrance(ready: boolean): SharedValue<number> {
  const reducedMotion = useReducedMotion();
  const entrance = useSharedValue(reducedMotion ? 1 : 0);
  const started = useRef(false);
  useEffect(() => {
    if (!ready || started.current) {
      return;
    }
    started.current = true;
    if (reducedMotion) {
      entrance.value = 1;
      return;
    }
    // The tab transition and the first layout of the screen are the expensive frames; the sweep
    // starts after those interactions finish, so it is not competing with them for the UI thread.
    let ran = false;
    const task = InteractionManager.runAfterInteractions(() => {
      ran = true;
      entrance.value = withTiming(1, {
        duration: ANALYTICS_ENTRANCE_MS,
        easing: ANALYTICS_ENTRANCE_EASING,
      });
    });
    return () => {
      task.cancel();
      // A cancelled-before-running task must not burn the one shot: a later ready transition is
      // allowed to schedule again. Once it has run, the sweep is not replayed.
      if (!ran) {
        started.current = false;
      }
    };
  }, [ready, reducedMotion, entrance]);
  return entrance;
}

/**
 * A colour that is already composited onto the page's own background, so a bar layer or a legend
 * swatch is fully opaque and no two layers can bleed through one another. Only the `#RGB` and
 * `#RRGGBB` forms the theme bridge resolves are composited; any other string is returned unchanged
 * rather than guessed at.
 */
function opaqueOnBackground(foreground: string, background: string, alpha: number): string {
  const fg = parseHex(foreground);
  const bg = parseHex(background);
  if (fg == null || bg == null || alpha >= 1) {
    return foreground;
  }
  const a = Math.min(1, Math.max(0, alpha));
  const mix = (front: number, back: number) => Math.round(front * a + back * (1 - a));
  return `rgb(${mix(fg.r, bg.r)}, ${mix(fg.g, bg.g)}, ${mix(fg.b, bg.b)})`;
}

/** The RGB of a `#RGB` or `#RRGGBB` string, or null for anything the bridge did not promise. */
function parseHex(color: string): {r: number; g: number; b: number} | null {
  const hex = color.trim();
  if (!hex.startsWith("#")) {
    return null;
  }
  const value = hex.slice(1);
  if (value.length === 3) {
    return {
      r: Number.parseInt(`${value[0]}${value[0]}`, 16),
      g: Number.parseInt(`${value[1]}${value[1]}`, 16),
      b: Number.parseInt(`${value[2]}${value[2]}`, 16),
    };
  }
  if (value.length === 6) {
    return {
      r: Number.parseInt(value.slice(0, 2), 16),
      g: Number.parseInt(value.slice(2, 4), 16),
      b: Number.parseInt(value.slice(4, 6), 16),
    };
  }
  return null;
}

/**
 * The count-up formatter, kept worklet-safe: no locale database, no display-string parsing, just the
 * same comma grouping `formatCount` draws and the same fixed decimal the screen states. The fallback
 * for a null figure is the exact string the screen already formatted, so unknown and empty values
 * never count up from zero.
 */
function countText(value: number, decimals: number, suffix: string): string {
  "worklet";
  const safe = Number.isFinite(value) ? value : 0;
  if (decimals > 0) {
    return `${safe.toFixed(decimals)}${suffix}`;
  }
  const rounded = Math.round(safe);
  const sign = rounded < 0 ? "-" : "";
  const digits = String(Math.abs(rounded));
  let grouped = "";
  for (let index = 0; index < digits.length; index += 1) {
    if (index > 0 && (digits.length - index) % 3 === 0) {
      grouped += ",";
    }
    grouped += digits[index];
  }
  return `${sign}${grouped}${suffix}`;
}

/**
 * A figure that sweeps from zero to its real value on the shared entrance. It is a normal Text: the
 * shared entrance still runs on the UI thread, and this component samples it at a bounded rate so a
 * page of figures does not mount a native TextInput per number. The final string is the exact value
 * the surrounding label already announces to a screen reader.
 */
export function AnimatedValueText({
  value,
  count,
  entrance,
  style,
}: {
  /** The finished string, the fallback when there is nothing to count up. */
  value: string;
  /** The raw figure and its formatting, or null for a dash or a word that must not count. */
  count?: {value: number | null; decimals?: number; suffix?: string} | null;
  entrance: SharedValue<number>;
  style: StyleProp<TextStyle>;
}): React.JSX.Element {
  const reducedMotion = useReducedMotion();
  const focused = useIsFocused();
  const decimals = count?.decimals ?? 0;
  const suffix = count?.suffix ?? "";
  const raw = count?.value ?? null;
  // A normal Text is far cheaper to mount than a native TextInput. The count is sampled from the
  // UI-thread entrance at a bounded rate, and only a changed string is sent through React; the ring
  // and bar still move entirely on the UI thread.
  const [display, setDisplay] = useState(() =>
    reducedMotion ? value : countText(0, decimals, suffix)
  );
  const displayRef = useRef(display);

  useEffect(() => {
    if (!focused || raw == null) {
      return;
    }
    if (reducedMotion || entrance.value >= 1) {
      if (displayRef.current !== value) {
        displayRef.current = value;
        setDisplay(value);
      }
      return;
    }
    let frame = 0;
    let lastUpdate = 0;
    let cancelled = false;
    const tick = (now: number) => {
      if (cancelled) {
        return;
      }
      if (now - lastUpdate >= COUNT_UPDATE_MS) {
        lastUpdate = now;
        const progress = entrance.value;
        const next = progress >= 1 ? value : countText(raw * progress, decimals, suffix);
        if (next !== displayRef.current) {
          displayRef.current = next;
          setDisplay(next);
        }
        if (progress >= 1) {
          return;
        }
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
    };
  }, [raw, decimals, suffix, entrance, reducedMotion, value, focused]);

  if (raw == null) {
    return <Text style={style}>{value}</Text>;
  }
  return <Text style={style}>{display}</Text>;
}

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
  /**
   * The raw figure behind [value], when the figure is a count that should sweep in from zero. The
   * screen keeps formatting; a null value means the entry shows [value] as-is, with no count-up.
   */
  count?: {value: number | null; decimals?: number; suffix?: string} | null;
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
  count,
  caption,
  note,
  muted = false,
  hero = true,
  entrance,
}: MetricBlock & {entrance: SharedValue<number>}): React.JSX.Element {
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
      <AnimatedValueText
        value={value}
        count={count}
        entrance={entrance}
        style={[hero ? styles.metricHero : typeScale.metric, {color: figureColor}]}
      />
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
  entrance,
}: {
  left: MetricBlock;
  right: MetricBlock;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  return (
    <View style={styles.metricPair}>
      <View style={[styles.metricPairSide, styles.metricPairLeft]}>
        <Metric {...left} entrance={entrance} />
      </View>
      <View
        style={[
          styles.metricPairSide,
          styles.metricPairRight,
          {borderLeftWidth: 1, borderLeftColor: colors.outlineVariant},
        ]}
      >
        <Metric {...right} entrance={entrance} />
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
  entrance,
}: {
  share: number;
  color: string;
  height?: number;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  const clamped = Number.isFinite(share) ? Math.min(1, Math.max(0, share)) : 0;
  // The fill is an absolutely sized child of the track, so a width animation is a single layer's own
  // property and the rounded ends of the track survive the sweep; its own measured width is all the
  // geometry the fill needs.
  const trackWidth = useSharedValue(0);
  const fillStyle = useAnimatedStyle(() => ({
    width: trackWidth.value * clamped * entrance.value,
  }));
  return (
    <View
      style={[styles.inlineBar, {height, backgroundColor: colors.outlineVariant}]}
      onLayout={(event) => {
        trackWidth.value = event.nativeEvent.layout.width;
      }}
    >
      <Animated.View style={[styles.inlineBarFill, {backgroundColor: color}, fillStyle]} />
    </View>
  );
}

/**
 * The columns a bar row reserves at 100 % text, in dp: the label, the count, and the note after it.
 * A row scales all three by the reader's text size, so a count never clips once the type is enlarged,
 * and a caller hands the same bases to every row of one list — a wider count for a list of durations,
 * a narrower label for a list of two-character level names — so all its tracks start and end together.
 */
const BAR_ROW_LABEL_WIDTH = 56;
const BAR_ROW_COUNT_WIDTH = 34;
const BAR_ROW_NOTE_WIDTH = 56;

/**
 * One row of a bar list: a label column, a thin bar, and a count column, with 10 dp between them. The
 * columns are the sizes a list hands to every one of its rows, so all its bars start and end on the
 * same two lines — which is what lets the eye compare them — and the bar itself is the 5 dp hairline
 * height. The row is one accessibility node reading "Apprentice: 183", never the label and the number
 * separately.
 *
 * `muted` is for the one value in a list that is not being compared — the level still being lived —
 * and `note` is the word that says why ("so far"); the row is then read in the quiet ink, so it never
 * looks like a finished measurement beside the others. `spoken` replaces the label for a screen
 * reader when the drawn one is an abbreviation ("L18" against "Level 18"). `labelWidth` and
 * `countWidth` are a list's own column bases, at 100 % text.
 */
export function BarRow({
  label,
  count,
  countValue,
  share,
  color,
  muted = false,
  note,
  spoken,
  labelWidth = BAR_ROW_LABEL_WIDTH,
  countWidth = BAR_ROW_COUNT_WIDTH,
  entrance,
}: {
  label: string;
  count: string;
  countValue?: {value: number | null; decimals?: number; suffix?: string} | null;
  share: number;
  color: string;
  muted?: boolean;
  note?: string;
  spoken?: string;
  labelWidth?: number;
  countWidth?: number;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  // The three fixed columns grow with the reader's text size: their contents are text, so a column
  // that stays put would clip the moment the type did not.
  const {fontScale} = useWindowDimensions();
  const said = note != null && note !== "" ? ` ${note}` : "";
  return (
    <View accessible accessibilityLabel={spoken ?? `${label}: ${count}${said}`} style={styles.barRow}>
      <Text
        numberOfLines={1}
        style={[
          typeScale.labelSmall,
          {width: Math.ceil(labelWidth * fontScale), color: colors.onSurfaceVariant},
        ]}
      >
        {label}
      </Text>
      <View style={styles.barRowTrack}>
        <InlineBar share={share} color={color} height={5} entrance={entrance} />
      </View>
      <AnimatedValueText
        value={count}
        count={countValue}
        entrance={entrance}
        style={[
          typeScale.labelSmall,
          styles.barRowCount,
          {
            width: Math.ceil(countWidth * fontScale),
            color: muted ? colors.onSurfaceVariant : colors.onSurface,
          },
        ]}
      />
      {note == null ? null : (
        <Text
          numberOfLines={1}
          style={[
            typeScale.labelSmall,
            {
              width: Math.ceil(BAR_ROW_NOTE_WIDTH * fontScale),
              color: colors.onSurfaceVariant,
            },
          ]}
        >
          {note}
        </Text>
      )}
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
  centreCount,
  caption = null,
  size = RING_SIZE,
  stroke = RING_STROKE,
  entrance,
}: {
  share: number | null;
  centre: string;
  centreCount?: {value: number | null; decimals?: number; suffix?: string} | null;
  caption?: string | null;
  size?: number;
  stroke?: number;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  // A stroke straddles the path it draws, so the radius gives up half of it to keep the whole ring
  // inside the square the caller lays out; the track and the arc share that one radius so the arc
  // sits exactly on the track and never inside or outside it.
  const radius = Math.max(0, (size - stroke) / 2);
  const circumference = 2 * Math.PI * radius;
  // A share that is not a finite number at all is treated as no share: a ring built from an empty
  // division draws its track and stops, rather than guessing at zero or at a full turn.
  const clamped = share == null || !Number.isFinite(share) ? null : Math.min(1, Math.max(0, share));
  // The displayed share is a shared value so a changed figure animates rather than replacing the
  // dash in one frame; reduced motion sets it directly, keeping the state change without the sweep.
  const arcProgress = useSharedValue(clamped ?? 0);
  useEffect(() => {
    const target = clamped ?? 0;
    arcProgress.value = reducedMotion
      ? target
      : withTiming(target, {duration: RING_SELECT_MS, easing: PRESS_EASING_REANIMATED});
  }, [clamped, reducedMotion, arcProgress]);
  // A round cap draws a dot on a zero-length dash, so the arc fades out as its length reaches zero
  // and the full circle stays a continuous stroke; the track underneath is always whole. The offset
  // is the animated value rather than the dash array, which keeps the animation to one numeric SVG
  // prop the renderer already treats as a path-length offset.
  const arcProps = useAnimatedProps<CircleProps>(() => {
    const length = Math.max(
      0,
      Math.min(circumference, arcProgress.value * circumference * entrance.value)
    );
    return {
      strokeDashoffset: circumference - length,
      strokeOpacity: length > 0.01 ? 1 : 0,
    };
  });
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
          <AnimatedCircle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            fill="none"
            stroke={colors.accent}
            strokeWidth={stroke}
            strokeLinecap="round"
            strokeDasharray={circumference}
            animatedProps={arcProps}
            // SVG starts a dash at three o'clock; a quarter turn back puts this arc's head at the
            // top, and a positive dash then sweeps clockwise from there.
            transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
        )}
      </Svg>
      {/* The figure sits over the arc's opening rather than under it, and the overlay never takes a
          touch: the ring reports itself, and nothing inside it is a control. */}
      <View style={styles.ringCentre} pointerEvents="none">
        <AnimatedValueText
          value={centre}
          count={centreCount}
          entrance={entrance}
          style={[
            styles.ringValue,
            {
              width: "100%",
              textAlign: "center",
              fontSize: centreSize,
              lineHeight: Math.round(centreSize * 1.2),
              color: colors.onSurface,
            },
          ]}
        />
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

/** The concise sentence one tapped day shows under its date, from the facts the read actually has. */
function dayDetailText(cell: HeatmapCell): string {
  if (cell.cards == null) {
    return "Activity unknown";
  }
  if (cell.cards === 0) {
    return "No cards studied";
  }
  return `${formatCount(cell.cards)} ${cell.cards === 1 ? "card" : "cards"} studied`;
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
 * Every drawn day is its own button: the summary the grid builds for itself is still announced once,
 * visually hidden, but a reader can land on one day and hear "Mon 5 Jan, 12 cards" before its selected
 * state opens the same date and count in a small popover beside it. A day the source would not answer
 * is still a button and still says "Activity unknown"; an empty slot outside the twelve weeks is not.
 * The legend repeats the cell fills rather than restating them, so the key cannot drift from the
 * marks.
 */
export function Heatmap({
  columns,
  selectedDayAgo,
  onSelectDay,
}: {
  columns: (HeatmapCell | null)[][];
  selectedDayAgo: number | null;
  onSelectDay: (cell: HeatmapCell) => void;
}): React.JSX.Element {
  const {colors} = useTheme();
  // The weekday column has to grow with the reader's text size: at 160 % a fixed 13 dp column cuts the
  // M/W/F labels in half, and the twelve columns of cells simply give up the few dp it needs (they are
  // `flex: 1`, so the grid never overflows).
  const {fontScale} = useWindowDimensions();
  const reducedMotion = useReducedMotion();
  const labelWidth = Math.ceil(HEATMAP_LABEL_WIDTH * fontScale);
  // One shared layout record keeps a tap from having to re-measure the grid: the row's offset and
  // the cell's own frame are enough to place the popover without reading the view tree again.
  const [gridBox, setGridBox] = useState({width: 0, height: 0});
  const cellLayouts = useRef(
    new Map<number, {x: number; y: number; width: number; height: number; row: number}>()
  );
  const rowOffsets = useRef<number[]>([]);
  // The day whose text is still on screen while its popover fades out; a new selection replaces it.
  const [displayedAgo, setDisplayedAgo] = useState<number | null>(null);
  // The selection the position was last placed for. Null means the next selection is an open, and
  // the popover is placed at the real anchor before it fades in rather than flying from the origin.
  const placedAgo = useRef<number | null>(null);
  const tooltipLeft = useSharedValue(0);
  const tooltipTop = useSharedValue(0);
  const tooltipOpacity = useSharedValue(0);
  const tooltipScale = useSharedValue(0.96);

  const cellFor = (daysAgo: number | null): HeatmapCell | null =>
    daysAgo == null
      ? null
      : columns.flat().find((cell) => cell?.daysAgo === daysAgo) ?? null;
  const shownAgo = selectedDayAgo ?? displayedAgo;
  const shownDay = cellFor(shownAgo);

  useEffect(() => {
    if (selectedDayAgo != null) {
      setDisplayedAgo(selectedDayAgo);
    }
  }, [selectedDayAgo]);

  useEffect(() => {
    if (selectedDayAgo == null) {
      placedAgo.current = null;
      tooltipOpacity.value = reducedMotion
        ? 0
        : withTiming(0, {duration: HEATMAP_SELECT_MS, easing: PRESS_EASING_REANIMATED});
      tooltipScale.value = reducedMotion
        ? 1
        : withTiming(0.96, {duration: HEATMAP_SELECT_MS, easing: PRESS_EASING_REANIMATED});
      return;
    }
    const layout = cellLayouts.current.get(selectedDayAgo);
    if (layout == null || gridBox.width === 0) {
      return;
    }
    // The popover sits under its day and is clamped inside the grid's own width, so a day at either
    // edge anchors inward rather than overflowing the section; its height is never measured because
    // it grows downward and the page can scroll under it.
    const width = Math.min(TOOLTIP_MAX_WIDTH, Math.max(TOOLTIP_MIN_WIDTH, gridBox.width));
    const anchorY = (rowOffsets.current[layout.row] ?? 0) + layout.y;
    const centreX = layout.x + layout.width / 2;
    const left = Math.max(0, Math.min(centreX - width / 2, Math.max(0, gridBox.width - width)));
    const top = anchorY + layout.height + TOOLTIP_GAP;
    const opening = placedAgo.current == null;
    placedAgo.current = selectedDayAgo;
    // An open is placed directly and then fades; only a move between two open days sweeps, so the
    // first visible frame is already at the tapped day instead of travelling there from the corner.
    if (opening) {
      tooltipLeft.value = left;
      tooltipTop.value = top;
    } else {
      tooltipLeft.value = reducedMotion
        ? left
        : withTiming(left, {duration: DISTRIBUTION_SELECT_MS, easing: PRESS_EASING_REANIMATED});
      tooltipTop.value = reducedMotion
        ? top
        : withTiming(top, {duration: DISTRIBUTION_SELECT_MS, easing: PRESS_EASING_REANIMATED});
    }
    tooltipScale.value = reducedMotion
      ? 1
      : withTiming(1, {duration: DISTRIBUTION_SELECT_MS, easing: PRESS_EASING_REANIMATED});
    tooltipOpacity.value = reducedMotion
      ? 1
      : withTiming(1, {duration: HEATMAP_SELECT_MS, easing: PRESS_EASING_REANIMATED});
  }, [selectedDayAgo, gridBox.width, reducedMotion, tooltipLeft, tooltipTop, tooltipOpacity, tooltipScale]);

  const tooltipStyle = useAnimatedStyle(() => ({
    opacity: tooltipOpacity.value,
    transform: [
      {translateX: tooltipLeft.value},
      {translateY: tooltipTop.value},
      {scale: tooltipScale.value},
    ],
  }));
  const tooltipWidth = Math.min(
    TOOLTIP_MAX_WIDTH,
    Math.max(TOOLTIP_MIN_WIDTH, gridBox.width || TOOLTIP_MAX_WIDTH)
  );
  // A new day's date and count cross-fade over the old pair, keyed by the day; reduced motion keeps
  // the swap immediate. The tooltip has no action of its own, so it never intercepts a touch meant
  // for a day underneath it.
  const contentEntering = reducedMotion ? undefined : FadeIn.duration(HEATMAP_SELECT_MS);
  const contentExiting = reducedMotion ? undefined : FadeOut.duration(HEATMAP_SELECT_MS);

  return (
    <View style={styles.heatmapBlock}>
      <Text accessible style={styles.visuallyHidden} accessibilityLabel={heatmapSummary(columns)} />
      <View
        style={styles.heatmapGrid}
        onLayout={(event) =>
          setGridBox({
            width: event.nativeEvent.layout.width,
            height: event.nativeEvent.layout.height,
          })
        }
      >
        {HEATMAP_ROW_LABELS.map((label, rowIndex) => (
          <View
            key={rowIndex}
            style={styles.heatmapRow}
            onLayout={(event) => {
              rowOffsets.current[rowIndex] = event.nativeEvent.layout.y;
            }}
          >
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
              // A `null` entry is a slot outside the twelve weeks, not an unread day: it stays empty
              // and inert so the grid's edge is ragged in exactly the way the calendar is.
              if (cell == null) {
                return <View key={columnIndex} style={styles.heatmapCell} />;
              }
              return (
                <HeatmapDay
                  key={columnIndex}
                  cell={cell}
                  selected={selectedDayAgo === cell.daysAgo}
                  onPress={onSelectDay}
                  onLayout={(x, y, width, height) => {
                    cellLayouts.current.set(cell.daysAgo, {x, y, width, height, row: rowIndex});
                  }}
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
      {shownDay == null ? null : (
        <Animated.View
          pointerEvents="none"
          accessible={selectedDayAgo != null}
          accessibilityLabel={shownDay.spoken}
          // The popover is only a visual echo of the selected day. Once nothing is selected it keeps
          // fading out, but it must not stay in the accessibility tree for that exit.
          accessibilityElementsHidden={selectedDayAgo == null}
          importantForAccessibility={selectedDayAgo == null ? "no-hide-descendants" : "auto"}
          style={[
            styles.heatmapTooltip,
            {
              maxWidth: tooltipWidth,
              backgroundColor: colors.surfaceContainerHigh,
              borderColor: colors.outlineVariant,
            },
            tooltipStyle,
          ]}
        >
          <Animated.View
            key={shownDay.daysAgo}
            entering={contentEntering}
            exiting={contentExiting}
          >
            <Text style={[typeScale.labelMedium, {color: colors.onSurface}]}>{shownDay.date}</Text>
            <Text
              style={[typeScale.bodySmall, styles.heatmapTooltipDetail, {color: colors.onSurfaceVariant}]}
            >
              {dayDetailText(shownDay)}
            </Text>
          </Animated.View>
        </Animated.View>
      )}
    </View>
  );
}

/**
 * One drawn day as a button: the fill keeps the band the grid gave it, and a selected day wears an
 * accent ring that fades in rather than a border that shifts the 17 dp cell.
 */
function HeatmapDay({
  cell,
  selected,
  onPress,
  onLayout,
}: {
  cell: HeatmapCell;
  selected: boolean;
  onPress: (cell: HeatmapCell) => void;
  onLayout: (x: number, y: number, width: number, height: number) => void;
}): React.JSX.Element {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const ring = useSharedValue(selected ? 1 : 0);
  useEffect(() => {
    const target = selected ? 1 : 0;
    ring.value = reducedMotion
      ? target
      : withTiming(target, {duration: HEATMAP_SELECT_MS, easing: PRESS_EASING_REANIMATED});
  }, [selected, reducedMotion, ring]);
  const ringStyle = useAnimatedStyle(() => ({opacity: ring.value}));
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityState={{selected}}
      accessibilityLabel={cell.spoken}
      onPress={() => onPress(cell)}
      onLayout={(event) =>
        onLayout(
          event.nativeEvent.layout.x,
          event.nativeEvent.layout.y,
          event.nativeEvent.layout.width,
          event.nativeEvent.layout.height
        )
      }
      style={styles.heatmapCell}
    >
      {/* The value fill lives on its own non-interactive layer: PressableScale owns the press
          opacity on the root, so a level's own opacity here would be flattened to full accent.
          Unknown keeps its outline, zero its neutral, and 1–4 their level; the ring stays its own
          animated layer above both. */}
      <View pointerEvents="none" style={[styles.heatmapDayFill, heatmapCellFill(cell.level, colors)]} />
      <Animated.View
        pointerEvents="none"
        style={[styles.heatmapDayRing, {borderColor: colors.accent}, ringStyle]}
      />
    </PressableScale>
  );
}

/**
 * One part of a distribution: the segment a bar sizes by its count, and the row the legend states
 * under it. The two are the same object on purpose — the bar reads its count and its tone, the legend
 * reads its figure and its name — so the key under a bar can never drift from the segment it explains.
 */
export interface DistributionPart {
  key: string;
  /** The name the legend sets under its figure ("Guru"). */
  label: string;
  /** The longer name the bar's spoken summary uses when the legend's own is an abbreviation. */
  spoken?: string;
  /** The count the bar sizes the segment by. */
  count: number;
  /** That count as the legend states it, formatted by the screen. */
  value: string;
  /** The tone of the segment: the reader's accent, never a hue of the page's own. */
  color: string;
  /** The strength of that accent, which the swatch repeats exactly. */
  opacity?: number;
}

/**
 * The counts under a distribution bar: each item is its figure over its key, and the row wraps when
 * the type is scaled up rather than letting one column crush another. The row spreads across the
 * width the bar above it spans, so a key sits under the segment it names instead of in a list of its
 * own; when the items wrap, the last line keeps the same spread and reads as a continuation.
 *
 * Every key leads with a small square in its segment's own tone, strength and backing — a category the
 * bar drew, and one it dropped for a zero count, alike — so a key never has to be matched to a bar by
 * position.
 *
 * A count is a figure and not prose: it is set at the value size in the strongest ink, with its name
 * beneath it at the section-meta size in the quiet ink — the same two-step the metrics use, at one
 * scale down. Each item is one accessibility node reading "312 New", so a screen reader never has to
 * pair a floating number with the word under it.
 *
 * Every item is a selectable button: it reports whether it is the chosen category, so a screen
 * reader can pick a segment too thin for a finger to hit in the bar, and the bar's own summary is
 * left standing beside it. The button keeps a full 48 dp of height whatever its type size, so a key
 * is as easy to hit as the bar it names.
 */
export function CountLegend({
  items,
  selectedKey,
  onSelect,
  entrance,
}: {
  items: DistributionPart[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  return (
    <View style={styles.countLegend}>
      {items.map((item) => (
        <CountLegendButton
          key={item.key}
          item={item}
          selectedKey={selectedKey}
          onSelect={onSelect}
          entrance={entrance}
        />
      ))}
    </View>
  );
}

/**
 * One key under the bar: its figure, its swatch and its name, with the choice carried by animated
 * emphasis rather than by a hard style swap. The swatch keeps the fill the bar just painted on the
 * track-tone backing; a chosen key rings it in the accent and lifts its name to the strongest ink.
 */
function CountLegendButton({
  item,
  selectedKey,
  onSelect,
  entrance,
}: {
  item: DistributionPart;
  selectedKey: string | null;
  onSelect: (key: string) => void;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const selected = item.key === selectedKey;
  const alpha = item.opacity ?? 1;
  const normalColor = opaqueOnBackground(item.color, colors.background, alpha);
  const dimColor = opaqueOnBackground(
    item.color,
    colors.background,
    alpha * DISTRIBUTION_DIM_OPACITY
  );
  // 0 is the untouched stage, 1 is dimmed by another choice, 2 is the chosen stage at full accent.
  const target = selectedKey == null ? 0 : selected ? 2 : 1;
  const swatch = useSharedValue(target);
  const emphasis = useSharedValue(selected ? 1 : 0);

  useEffect(() => {
    swatch.value = reducedMotion
      ? target
      : withTiming(target, {duration: DISTRIBUTION_SELECT_MS, easing: PRESS_EASING_REANIMATED});
  }, [target, reducedMotion, swatch]);
  useEffect(() => {
    const next = selected ? 1 : 0;
    emphasis.value = reducedMotion
      ? next
      : withTiming(next, {duration: DISTRIBUTION_SELECT_MS, easing: PRESS_EASING_REANIMATED});
  }, [selected, reducedMotion, emphasis]);

  const swatchStyle = useAnimatedStyle(() => ({
    backgroundColor: interpolateColor(
      swatch.value,
      [0, 1, 2],
      [normalColor, dimColor, item.color]
    ),
  }));
  const ringStyle = useAnimatedStyle(() => ({opacity: emphasis.value}));
  const labelStyle = useAnimatedStyle(() => ({
    color: interpolateColor(
      emphasis.value,
      [0, 1],
      [colors.onSurfaceVariant, colors.onSurface]
    ),
  }));

  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityState={{selected}}
      accessibilityLabel={`${item.value} ${item.label}`}
      onPress={() => onSelect(item.key)}
      style={styles.countLegendItem}
    >
      <AnimatedValueText
        value={item.value}
        count={{value: item.count, decimals: 0}}
        entrance={entrance}
        style={[typeScale.titleMedium, {color: colors.onSurface}]}
      />
      <View style={styles.countLegendKey}>
        {/* The swatch is the same opaque composite the bar paints for this stage, so a dimmed bar
            and its key always show one shade; the chosen key adds an accent ring outside the swatch
            without changing the 8 dp key's size. */}
        <View style={styles.countLegendSwatchWrap}>
          <Animated.View style={[styles.countLegendSwatch, swatchStyle]} />
          <Animated.View
            pointerEvents="none"
            style={[styles.countLegendSwatchRing, {borderColor: colors.accent}, ringStyle]}
          />
        </View>
        <Animated.Text
          numberOfLines={2}
          style={[typeScale.labelSmall, styles.countLegendLabel, labelStyle]}
        >
          {item.label}
        </Animated.Text>
      </View>
    </PressableScale>
  );
}

/**
 * One proportional bar for parts of a whole: every stage is painted as its own fully rounded pill at
 * its cumulative width, and the layers are drawn darkest/last-stage first so each lighter stage lies
 * over the beginning of the next. That leaves no gap, notch or border between stages — each lighter
 * cap simply overlaps the darker one below — while the outer pill clips the one right cap that should
 * show. The bar carries the whole statement for a screen reader, and the key belongs to the caller,
 * which hands the same parts to [CountLegend] so the bar and its key are one array read twice.
 *
 * The bar is also a way to choose: the wrapper is a real 48 dp touch row with the capsule centred
 * inside it, and the hit layer below still divides that row by each stage's own count — the
 * cumulative paint layers never replace the non-cumulative hit regions. A zero-count category has no
 * segment to press, so it is chosen from its legend like any other.
 */
export function StackedBar({
  segments,
  selectedKey,
  onSelect,
  entrance,
}: {
  segments: DistributionPart[];
  selectedKey: string | null;
  onSelect: (key: string) => void;
  entrance: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  const drawn = segments.filter((segment) => segment.count > 0);
  const total = drawn.reduce((sum, segment) => sum + segment.count, 0);
  const layers: {segment: DistributionPart; cumulative: number}[] = [];
  let running = 0;
  for (const segment of drawn) {
    running += segment.count;
    layers.push({segment, cumulative: total > 0 ? running / total : 0});
  }
  const barWidth = useSharedValue(0);
  // One reveal mask animates the bar's left-to-right entrance instead of every layer animating its
  // own width. The mask is the only node whose width changes per frame; the layers keep their final
  // cumulative widths and only their selection colour animates.
  const revealStyle = useAnimatedStyle(() => ({
    width: barWidth.value * entrance.value,
  }));
  const summary = segments
    .map((segment) => `${segment.spoken ?? segment.label} ${segment.count}`)
    .join(", ");
  return (
    <View style={styles.stackedBarWrap}>
      <View
        accessible
        accessibilityRole="image"
        accessibilityLabel={summary}
        onLayout={(event) => {
          barWidth.value = event.nativeEvent.layout.width;
        }}
        style={[styles.stackedBar, {backgroundColor: colors.outlineVariant}]}
      >
        <Animated.View pointerEvents="none" style={[styles.stackedBarReveal, revealStyle]}>
          {/* Reverse stage order: the full/darkest layer is laid down first, then each lighter
              stage over it, so the visible region for a stage is its own layer and its rounded
              right cap overlaps the next darker stage's beginning. */}
          {layers
            .slice()
            .reverse()
            .map(({segment, cumulative}) => (
              <StackedPaintLayer
                key={segment.key}
                segment={segment}
                cumulative={cumulative}
                selectedKey={selectedKey}
                barWidth={barWidth}
              />
            ))}
        </Animated.View>
      </View>
      {/* The bar's touch layer, a normal child of the 48 dp wrapper: the same non-cumulative
          proportional columns as the counts, independent of the cumulative paint layers above. It
          carries no ink and no accessibility of its own — the visible key is the spoken control, and
          this is only the sighted reader's quicker way to it. */}
      <View style={styles.stackedHitLayer} pointerEvents="box-none">
        {drawn.map((segment) => (
          <PressableScale
            key={segment.key}
            accessible={false}
            importantForAccessibility="no-hide-descendants"
            onPress={() => onSelect(segment.key)}
            style={{flexGrow: segment.count, height: "100%"}}
          />
        ))}
      </View>
    </View>
  );
}

/**
 * One cumulative paint layer. Its width is the running share of the drawn total, so the rounded right
 * cap always sits where that stage ends; its colour is an opaque composite of the stage's accent
 * strength on the page background, so no layer opacity can bleed through the next. Selection moves
 * the colour between the untouched, dimmed and full-accent composites on the existing interaction
 * timing.
 */
function StackedPaintLayer({
  segment,
  cumulative,
  selectedKey,
  barWidth,
}: {
  segment: DistributionPart;
  cumulative: number;
  selectedKey: string | null;
  barWidth: SharedValue<number>;
}): React.JSX.Element {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const alpha = segment.opacity ?? 1;
  const normalColor = opaqueOnBackground(segment.color, colors.background, alpha);
  const dimColor = opaqueOnBackground(
    segment.color,
    colors.background,
    alpha * DISTRIBUTION_DIM_OPACITY
  );
  // 0 is the untouched stage, 1 is dimmed by another choice, 2 is the chosen stage at full accent.
  const target = selectedKey == null ? 0 : segment.key === selectedKey ? 2 : 1;
  const emphasis = useSharedValue(target);
  useEffect(() => {
    emphasis.value = reducedMotion
      ? target
      : withTiming(target, {duration: DISTRIBUTION_SELECT_MS, easing: PRESS_EASING_REANIMATED});
  }, [target, reducedMotion, emphasis]);
  const layerStyle = useAnimatedStyle(() => ({
    width: barWidth.value * cumulative,
    backgroundColor: interpolateColor(
      emphasis.value,
      [0, 1, 2],
      [normalColor, dimColor, segment.color]
    ),
  }));
  return <Animated.View pointerEvents="none" style={[styles.stackedPaintLayer, layerStyle]} />;
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
  barRowTrack: {flex: 1, minWidth: 0},
  // The count is the figure of the row, so it is set in the strongest ink and right-aligned against
  // its column; its width, like the label's and the note's, is set inline at the reader's text size.
  barRowCount: {textAlign: "right", fontWeight: "700"},
  // Twelve columns of seven rows. The gaps are the size of the hole between two cells, and the rows
  // share the grid's width because the cells flex — that is what keeps the grid inside a 320 dp
  // section without a scroll or a second layout for narrow screens.
  heatmapBlock: {position: "relative"},
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
  // The fill is absolute so the value's own opacity, outline or fill is drawn on a layer the press
  // feedback cannot overwrite, while the button root still carries the 17 dp touch target.
  heatmapDayFill: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    borderRadius: HEATMAP_CELL_RADIUS,
  },
  // The selected day's accent ring hangs a hair outside the cell so the 17 dp square's own size and
  // the widths of its neighbours never move; only its opacity is animated.
  heatmapDayRing: {
    position: "absolute",
    top: -2,
    left: -2,
    right: -2,
    bottom: -2,
    borderWidth: 2,
    borderRadius: HEATMAP_CELL_RADIUS + 2,
  },
  // The day popover is a quiet surface with a hairline, not a card: it floats over the grid, is
  // clamped inside the grid's width and grows downward, so long text wraps instead of clipping.
  heatmapTooltip: {
    position: "absolute",
    left: 0,
    top: 0,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.s,
    borderWidth: 1,
    borderRadius: 10,
  },
  heatmapTooltipDetail: {marginTop: 2},
  // The chart's aggregate summary stays available to a screen reader without drawing a second
  // visible sentence under the grid.
  visuallyHidden: {position: "absolute", width: 1, height: 1, opacity: 0},
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
  countLegendItem: {minWidth: 0, flexShrink: 1, minHeight: DISTRIBUTION_TOUCH_HEIGHT},
  // The swatch sits on the label's first line rather than centred on the block, so a name that wraps
  // to a second line still has its key beside the word it starts with. The 4 dp is half of the 16 dp
  // label line less the 8 dp square, which is what puts the two on one optical line.
  countLegendKey: {flexDirection: "row", alignItems: "flex-start", gap: 5, marginTop: 2},
  countLegendSwatchWrap: {
    width: DISTRIBUTION_SWATCH_SIZE,
    height: DISTRIBUTION_SWATCH_SIZE,
    marginTop: 4,
  },
  countLegendSwatch: {
    flex: 1,
    borderRadius: 2,
  },
  // The ring sits outside the swatch, so a selected key wears the accent without changing the 8 dp
  // key's footprint. It fades in and out with the choice.
  countLegendSwatchRing: {
    position: "absolute",
    top: -3,
    left: -3,
    right: -3,
    bottom: -3,
    borderWidth: 2,
    borderRadius: 5,
  },
  // A name may wrap rather than truncate: "Apprentice 1-4" cut to "Appren…" would be a key that does
  // not name anything, and the row has already spread itself around whatever space is left.
  countLegendLabel: {flexShrink: 1},
  // The chart's own 48 dp touch row: the 9 dp capsule is centred in it and the invisible hit layer
  // fills it, so the whole row is a real Android touch target rather than an overflowed one.
  stackedBarWrap: {height: DISTRIBUTION_TOUCH_HEIGHT, justifyContent: "center"},
  stackedBar: {
    height: DISTRIBUTION_BAR_HEIGHT,
    borderRadius: DISTRIBUTION_LAYER_RADIUS,
    overflow: "hidden",
  },
  // The reveal mask owns the entrance sweep: one width animation clips the final cumulative
  // layers left to right, so the per-frame layout work is one node instead of one per stage.
  stackedBarReveal: {
    position: "absolute",
    left: 0,
    top: 0,
    bottom: 0,
    borderRadius: DISTRIBUTION_LAYER_RADIUS,
    overflow: "hidden",
  },
  // Each cumulative stage is a full pill at its own width; the outer pill clips the one right cap
  // that should show and the layers above cover the left caps of the layers below.
  stackedPaintLayer: {
    position: "absolute",
    left: 0,
    top: 0,
    bottom: 0,
    borderRadius: DISTRIBUTION_LAYER_RADIUS,
  },
  // The transparent layer over the capsule: the same proportional columns as the counts beneath it,
  // filling the wrapper's own height.
  stackedHitLayer: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    flexDirection: "row",
  },
  unavailable: {borderTopWidth: 1, borderBottomWidth: 1, paddingVertical: 10},
  unavailableText: {lineHeight: 20},
  // A 48 dp target that hugs the label: the action gets a full-height touch area without becoming a
  // block the eye would read as a control surface.
  unavailableAction: {minHeight: 48, alignSelf: "flex-start", justifyContent: "center"},
  skeletonLine: {height: 12, borderRadius: 0, marginBottom: 10},
});
