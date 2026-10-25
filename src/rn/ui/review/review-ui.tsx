/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The review flow's own primitives, drawn from the approved Ink review workflow: the page title, the
 * sub-screen header, the thin progress line, the study row the Anki and WaniKani hubs are both made
 * of, the success check, the pinned action and the rating row.
 *
 * They live here rather than in the shared kit because they are this workflow's visual language —
 * rows separated by hairlines, one dark action, quiet metadata — and because the app's own screens
 * already share the tokens these are built from (`tokens.ts`), so a change to the scale reaches them
 * all. Every measurement is the approved design's, in dp: a 43 dp brand holder at radius 12, one 48 dp
 * art holder at radius 12 for the study rows (90 dp an Anki deck, 110 dp a WaniKani queue, each ended
 * by the one hairline), a 101 dp source row, a 52 dp action at radius 13, a 63 dp nav capsule drawn
 * by `BottomBar`.
 *
 * Motion is the design's own, and only ever the result of something the reader did: a nested screen
 * entering (8 dp horizontally, 70% to full opacity), the progress line moving to its new value, and
 * the press feedback every control in the app already answers with (`PressableScale`). All of it is
 * skipped when the system asks for reduced motion.
 */
import React, {useEffect, useRef} from "react";
import {
  ActivityIndicator,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
  type StyleProp,
  type TextInputProps,
  type TextStyle,
  type ViewStyle,
} from "react-native";
import Animated, {
  Easing as ReanimatedEasing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {InkIcon} from "../InkIcon";
import {Icon} from "../components";
import {PressableScale} from "../PressableScale";
import {shape, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {AnkiServiceMark, ToriiServiceMark} from "../home/HomeArtwork";
import DeckThumbnail from "./DeckThumbnail";

/** The curve every short Ink transition uses: fast out, settled in — the app's own emphasized one. */
const INK_EASING = ReanimatedEasing.bezier(0.2, 0, 0, 1);

/** How long a nested screen takes to arrive, and how far it travels: 8 dp, per the design. */
const ENTER_MS = 200;
const ENTER_TRAVEL = 8;

/** How long the progress line takes to reach a new value. */
const PROGRESS_MS = 180;

/** When WaniKani's next reviews come up: a clock time today, and a weekday when it is later. */
export function nextReviewLabel(at: number): string {
  const when = new Date(at);
  const sameDay = when.toDateString() === new Date().toDateString();
  return sameDay
    ? when.toLocaleTimeString([], {hour: "numeric", minute: "2-digit"})
    : when.toLocaleString([], {weekday: "short", hour: "numeric", minute: "2-digit"});
}

/** The page title: the same 35 dp Ink heading Home, Analytics and Settings use. */
export function ScreenTitle({
  children,
  style,
}: {
  children: string;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Text
      accessibilityRole="header"
      style={[typeScale.headlineSmall, {color: colors.onSurface}, styles.title, style]}
    >
      {children}
    </Text>
  );
}

/** One short line under a title: what the page is for, in the reader's quiet ink. */
export function ScreenSubtitle({
  children,
  style,
}: {
  children: string;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Text style={[typeScale.description, {color: colors.onSurfaceVariant}, styles.subtitle, style]}>
      {children}
    </Text>
  );
}

/** The small caption over a group of rows. */
export function SectionCaption({
  children,
  style,
}: {
  children: string;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  return (
    <Text
      style={[
        typeScale.labelMedium,
        styles.caption,
        {color: colors.onSurfaceVariant, borderBottomColor: colors.outlineVariant},
        style,
      ]}
      accessibilityRole="header"
    >
      {children}
    </Text>
  );
}

/**
 * The brand mark in its own holder: the app's real Anki or WaniKani logo on the service's own tint,
 * which is the one place a brand colour belongs. The tint is read from the canvas rather than from a
 * theme role because it is a brand surface, not an app one.
 */
export function BrandTile({
  brand,
  size = 43,
  radius = 12,
}: {
  brand: "anki" | "wanikani";
  size?: number;
  radius?: number;
}) {
  const {colors} = useTheme();
  const dark = Number.parseInt(colors.background.slice(1, 3), 16) < 128;
  const background =
    brand === "anki"
      ? dark
        ? "#182D40"
        : "#EAF4FF"
      : dark
        ? "#38212A"
        : "#FFF0F2";
  const glyph = Math.round(size * 0.7);
  return (
    <View
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[styles.tile, {width: size, height: size, borderRadius: radius, backgroundColor: background}]}
    >
      {brand === "anki" ? (
        <AnkiServiceMark size={glyph} />
      ) : (
        <ToriiServiceMark size={glyph} />
      )}
    </View>
  );
}

/**
 * The Ink integration row: a brand mark, the service's name, one quiet status line, and the one thing
 * waiting on the right — a count, or the action this state needs. The whole row is the target; there
 * is no button inside it, because the row *is* the button.
 */
export function SourceRow({
  brand,
  title,
  status,
  value,
  valueLabel,
  actionLabel,
  onPress,
  first = false,
  accessibilityLabel,
}: {
  brand: "anki" | "wanikani";
  title: string;
  status: string;
  /** A count for the right-hand side, in the design's numeric step. */
  value?: string | null;
  /** What that count is, under it. */
  valueLabel?: string | null;
  /** What the row does, when there is no count to show instead. */
  actionLabel?: string | null;
  onPress: () => void;
  /** The first row of a group carries the group's top hairline; every row carries its bottom one. */
  first?: boolean;
  accessibilityLabel?: string;
}) {
  const {colors} = useTheme();
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? `${title}, ${status}`}
      onPress={onPress}
      style={[
        styles.source,
        {
          borderBottomColor: colors.outlineVariant,
          borderTopColor: colors.outlineVariant,
          borderTopWidth: first ? 1 : 0,
        },
      ]}
    >
      <BrandTile brand={brand} />
      <View style={styles.sourceMain}>
        <Text numberOfLines={1} ellipsizeMode="tail" style={[styles.sourceName, {color: colors.onSurface}]}>
          {title}
        </Text>
        <Text numberOfLines={2} style={[typeScale.bodySmall, styles.sourceStatus, {color: colors.onSurfaceVariant}]}>
          {status}
        </Text>
      </View>
      <View style={styles.sourceSide}>
        {value != null ? (
          <>
            <Text numberOfLines={1} style={[styles.sourceValue, {color: colors.onSurface}]}>
              {value}
            </Text>
            {valueLabel != null && (
              <Text numberOfLines={1} style={[styles.sourceValueLabel, {color: colors.onSurfaceVariant}]}>
                {valueLabel}
              </Text>
            )}
          </>
        ) : actionLabel != null ? (
          <Text numberOfLines={2} style={[styles.sourceAction, {color: colors.onSurfaceVariant}]}>
            {actionLabel}
          </Text>
        ) : null}
      </View>
      <InkIcon name="chevron" size={15} color={colors.fine} />
    </PressableScale>
  );
}

/**
 * The compact header of a step inside the flow: the way back, what the screen is, and — when
 * there is one — the count or state that belongs to it. The glyph is the design's 21 dp; its target is
 * Android's own 48 dp, so the arrow sits where the design puts it without being hard to hit.
 *
 * The title is centred on the screen: the back button and the count each occupy a 48 dp lane of the
 * row, so the flexed title between them has the screen's own centre.
 */
export function SubHeader({
  title,
  right,
  onBack,
}: {
  title: string;
  right?: string | null;
  onBack: () => void;
}) {
  const {colors} = useTheme();
  return (
    <View style={styles.subHeader}>
      <PressableScale
        accessibilityRole="button"
        accessibilityLabel="Back"
        onPress={onBack}
        style={styles.backButton}
      >
        <InkIcon name="back" size={21} color={colors.onSurface} />
      </PressableScale>
      <Text numberOfLines={1} ellipsizeMode="tail" style={[styles.subHeaderTitle, {color: colors.onSurface}]}>
        {title}
      </Text>
      <View style={styles.subHeaderRightLane}>
        {right != null && (
          <Text numberOfLines={1} style={[styles.subHeaderRight, {color: colors.onSurfaceVariant}]}>
            {right}
          </Text>
        )}
      </View>
    </View>
  );
}

/**
 * The session's progress: a 2 dp hairline the accent fills to the reader's place in the queue. It
 * animates to its new value and never bounces, and it is the only thing on the screen that moves
 * while a card is being answered.
 */
export function ProgressLine({value, height = 2}: {value: number; height?: number}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const progress = useSharedValue(value);
  const clamped = Math.max(0, Math.min(1, value));

  useEffect(() => {
    progress.value = reducedMotion
      ? clamped
      : withTiming(clamped, {duration: PROGRESS_MS, easing: INK_EASING});
  }, [clamped, progress, reducedMotion]);

  const fill = useAnimatedStyle(() => ({width: `${progress.value * 100}%`}));

  return (
    <View style={styles.progressArea} accessible accessibilityRole="progressbar" accessibilityValue={{min: 0, max: 100, now: Math.round(clamped * 100)}}>
      <View style={[styles.progressTrack, {height, backgroundColor: colors.outlineVariant}]}>
        <Animated.View style={[styles.progressFill, {height, backgroundColor: colors.accent}, fill]} />
      </View>
    </View>
  );
}

/**
 * The one success mark the app draws: a filled disc with a tick on it, at 22 dp. The pair is the
 * reader's chosen accent with the ink that reads on it — the same accent/onPrimary pairing the
 * filled actions and the selected navigation pill are held to — so the mark follows the accent
 * chosen in Settings instead of a fixed status green.
 */
export function SuccessCheck({size = 22}: {size?: number}) {
  const {colors} = useTheme();
  return (
    <View
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={[
        styles.check,
        {
          width: size,
          height: size,
          borderRadius: size / 2,
          backgroundColor: colors.accent,
        },
      ]}
    >
      <Icon name="check" size={size * 0.64} color={colors.onPrimary} />
    </View>
  );
}

/**
 * Two flat figures side by side, split by one vertical hairline — the paired-figure grammar the
 * Analytics page uses, without any card around it. A "—" value means the figure is not known; the
 * optional hint says why, or names the scope the figure covers. A narrow window or large type stacks
 * the pair so no line is clipped.
 */
export function MetricPair({
  metrics,
}: {
  metrics: Array<{value: string; label: string; hint?: string | null}>;
}) {
  const {colors} = useTheme();
  // A narrow window or large type leaves each half-column too little room for its hint to read:
  // the pair then stacks, so every line grows to the full content width and wraps freely.
  const {width, fontScale} = useWindowDimensions();
  const stacked = width < 340 || fontScale >= 1.5;
  return (
    <View style={[styles.metricRow, stacked && styles.metricRowStacked]}>
      {metrics.map((metric, index) => (
        <View
          key={metric.label}
          accessible
          accessibilityLabel={`${
            metric.value === "—" ? "Not available" : metric.value
          }, ${metric.label}${metric.hint == null ? "" : `, ${metric.hint}`}`}
          style={[
            styles.metric,
            !stacked && index > 0 && styles.metricSpaced,
            !stacked && index > 0 && {borderStartColor: colors.outlineVariant},
          ]}
        >
          <Text style={[styles.metricValue, {color: colors.onSurface}]}>{metric.value}</Text>
          <Text numberOfLines={2} style={[styles.metricLabel, {color: colors.onSurfaceVariant}]}>
            {metric.label}
          </Text>
          {metric.hint != null && (
            <Text style={[styles.metricHint, {color: colors.onSurfaceVariant}]}>
              {metric.hint}
            </Text>
          )}
        </View>
      ))}
    </View>
  );
}

/**
 * The one row both hubs are built from: what the row is about at the leading edge, its name with the
 * lines under it, and — in the trailing lane both providers share — the count with its caption or the
 * green check, then the chevron that says the row leads somewhere. Anki puts deck art here and
 * WaniKani a provider glyph on a neutral holder; only what goes in `art` and how tall the row stands
 * differ. The row is one target and holds no button of its own.
 */
export function StudyRow({
  art,
  holderSurface = false,
  title,
  subline,
  extra = null,
  trailing = null,
  complete = false,
  loading = false,
  minHeight,
  accessibilityLabel,
  disabled = false,
  onPress = null,
  titleSize = 15,
}: {
  /** The leading tile or glyph, drawn by the caller. */
  art: React.ReactNode;
  /** True wraps the art in the 48 dp neutral holder the provider rows use. */
  holderSurface?: boolean;
  title: string;
  subline: string;
  /** The one further line this row has room for, in the `Label · value` grammar. */
  extra?: string | null;
  /** The count and its caption; null when the queue has nothing to count. */
  trailing?: {value: string; label: string} | null;
  complete?: boolean;
  /** True while the row's own action is in flight: a spinner takes the trailing lane's place. */
  loading?: boolean;
  minHeight: number;
  accessibilityLabel: string;
  disabled?: boolean;
  /** Null when the row has nowhere to lead, which is what a cleared or locked queue is. */
  onPress?: (() => void) | null;
  /** WaniKani's rows carry a 17 dp title; Anki's decks keep the list's own 15. */
  titleSize?: number;
}) {
  const {colors} = useTheme();
  const content = (
    <>
      {holderSurface ? (
        <View
          accessible={false}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
          style={[styles.studyHolder, {backgroundColor: colors.surfaceVariant}]}
        >
          {art}
        </View>
      ) : (
        art
      )}
      <View style={styles.studyMain}>
        <Text
          numberOfLines={2}
          ellipsizeMode="tail"
          style={[
            styles.studyTitle,
            {fontSize: titleSize, lineHeight: Math.round(titleSize * 1.35), color: colors.onSurface},
          ]}
        >
          {title}
        </Text>
        {subline !== "" && (
          <Text numberOfLines={2} style={[styles.studySubline, {color: colors.onSurfaceVariant}]}>
            {subline}
          </Text>
        )}
        {extra != null && (
          <Text numberOfLines={2} style={[styles.studyExtra, {color: colors.onSurfaceVariant}]}>
            {extra}
          </Text>
        )}
      </View>
      <View style={styles.studyTrailing}>
        {loading ? (
          <ActivityIndicator size="small" color={colors.accent} />
        ) : complete ? (
          <SuccessCheck />
        ) : trailing != null ? (
          <Text numberOfLines={1} style={[styles.studyCount, {color: colors.accent}]}>
            {trailing.value}
          </Text>
        ) : null}
        {!complete && !loading && trailing != null && (
          <Text numberOfLines={1} style={[styles.studyCountLabel, {color: colors.onSurfaceVariant}]}>
            {trailing.label}
          </Text>
        )}
      </View>
      {/* The chevron says the row leads somewhere: a cleared or locked informational row has none. */}
      {onPress != null && <InkIcon name="chevron" size={22} color={colors.onSurfaceVariant} />}
    </>
  );
  const rowStyle = [styles.studyRow, {minHeight, borderBottomColor: colors.outlineVariant}];

  if (onPress == null) {
    return (
      <View
        accessible
        accessibilityRole="none"
        accessibilityLabel={accessibilityLabel}
        accessibilityState={loading ? {busy: true} : undefined}
        style={rowStyle}
      >
        {content}
      </View>
    );
  }

  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
      accessibilityState={{disabled, busy: loading}}
      disabled={disabled}
      onPress={onPress}
      style={rowStyle}
    >
      {content}
    </PressableScale>
  );
}

/** One deck of the collection: its name, AnkiDroid's own counts, and what is due in it right now. */
export function DeckRow({
  deckId,
  name,
  counts,
  due,
  onPress,
}: {
  deckId: number | string;
  name: string;
  counts: string;
  due: number;
  onPress: () => void;
}) {
  // Nothing due is clear on its own: a deck with no cards left does not need a receipt to say so.
  // A deck with cards due is never called clear.
  const cleared = due === 0;
  return (
    <StudyRow
      art={<DeckThumbnail deckId={deckId} width={48} height={48} />}
      title={name}
      subline={cleared ? "Clear for now" : counts}
      trailing={cleared ? null : {value: String(due), label: "due"}}
      complete={cleared}
      minHeight={90}
      accessibilityLabel={due > 0 ? `${name}, ${due} due, open deck` : `${name}, clear for now, open deck`}
      onPress={cleared ? null : onPress}
    />
  );
}

/** The screen's one dominant action: a flat fill at the design's 52 dp and 13 dp radius. */
export function FilledAction({
  label,
  onPress,
  disabled,
  style,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  /** A lane's own geometry - WaniKani's taller 54 dp button, at its own radius. */
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  return (
    <PressableScale
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityState={{disabled: disabled ?? false}}
      disabled={disabled}
      onPress={onPress}
      style={[
        styles.action,
        {backgroundColor: disabled ? colors.disabled : colors.actionPrimary},
        style,
      ]}
    >
      <Text
        style={[
          styles.actionLabel,
          {color: disabled ? colors.onDisabled : colors.onActionPrimary},
        ]}
      >
        {label}
      </Text>
    </PressableScale>
  );
}

/**
 * The actions pinned above the system navigation: one hairline, the screen's own canvas behind them,
 * and the reader's gesture inset inside the padding. Nothing here scrolls, so a control the reader
 * needs is never under the keyboard or the gesture bar.
 */
export function ActionFooter({
  children,
  bottomInset,
  style,
}: {
  children: React.ReactNode;
  bottomInset: number;
  style?: StyleProp<ViewStyle>;
}) {
  const {colors} = useTheme();
  return (
    <View
      style={[
        styles.footer,
        {
          backgroundColor: colors.background,
          borderTopColor: colors.outlineVariant,
          paddingBottom: spacing.s + bottomInset,
        },
        style,
      ]}
    >
      {children}
    </View>
  );
}

/** One rating button: the name to show, and the ease Anki expects for it. */
interface Rating {
  name: string;
  ease: number;
}

/**
 * The buttons a deck actually uses, from AnkiDroid's own button count: all four when it reports
 * four, Again/Good/Easy when it reports three, Again/Good when it reports two. The ease is
 * positional — button `i` is ease `i + 1` — because that is how AnkiDroid computed the interval
 * labels in the same answer.
 */
function ratingsFor(buttonCount: number): Rating[] {
  const again: Rating = {name: "Again", ease: 1};
  const good: Rating = {name: "Good", ease: 2};
  const easy: Rating = {name: "Easy", ease: 3};
  switch (Math.min(4, Math.max(2, buttonCount))) {
    case 2:
      return [again, good];
    case 3:
      return [again, good, easy];
    default:
      // Four buttons: the positional eases run 1..4 — Hard sits at position 2.
      return [
        again,
        {name: "Hard", ease: 2},
        {name: "Good", ease: 3},
        {name: "Easy", ease: 4},
      ];
  }
}

/**
 * The deck's ratings: one row of quiet buttons under the answer, each labelled with the interval the
 * scheduler itself reported for that position, so a tap sends back exactly the rating its interval
 * was computed for. The ink is the reader's accent — the row is the screen's own action, in the same
 * tone as every other button — except "Again", which takes the error ink: it is the one answer that
 * means the card was not known.
 */
export function RatingBar({
  labels,
  enabled,
  onGrade,
}: {
  labels: string[];
  enabled: boolean;
  onGrade: (ease: number) => void;
}) {
  const {colors} = useTheme();
  const ratings = ratingsFor(labels.length);
  return (
    <View style={styles.ratingRow}>
      {ratings.map((rating, index) => {
        const isAgain = rating.ease === 1;
        const ink = !enabled
          ? colors.onDisabled
          : isAgain
            ? colors.error
            : colors.accent;
        return (
          <View key={rating.ease} style={[styles.ratingCell, index > 0 && styles.ratingCellSpaced]}>
            <PressableScale
              accessibilityRole="button"
              accessibilityLabel={`${rating.name} ${labels[rating.ease - 1] ?? ""}`.trim()}
              accessibilityState={{disabled: !enabled}}
              disabled={!enabled}
              onPress={() => onGrade(rating.ease)}
              style={[
                styles.ratingButton,
                {
                  backgroundColor: colors.surfaceVariant,
                  borderColor: colors.outlineVariant,
                },
              ]}
            >
              <Text numberOfLines={1} style={[styles.ratingName, {color: ink}]}>
                {rating.name}
              </Text>
              <Text numberOfLines={1} style={[styles.ratingInterval, {color: ink, opacity: 0.75}]}>
                {labels[rating.ease - 1] ?? ""}
              </Text>
            </PressableScale>
          </View>
        );
      })}
    </View>
  );
}

/**
 * The field a WaniKani answer is typed into: the design's own line rather than a box. It carries only
 * a bottom stroke - no top, side or rounded border, no focus ring or background - and that stroke is
 * the accent at every state, focused or not, right or wrong. The answer's own text takes the outcome
 * colour; the line stays an action accent.
 */
export const AnswerInput = React.forwardRef<TextInput, TextInputProps>(function AnswerInput(
  props,
  ref,
) {
  const {colors} = useTheme();
  return (
    <TextInput
      ref={ref}
      {...props}
      placeholderTextColor={props.placeholderTextColor ?? colors.onSurfaceVariant}
      underlineColorAndroid="transparent"
      style={[styles.input, {color: colors.onSurface, borderBottomColor: colors.accent}, props.style]}
    />
  );
});

/**
 * The one line of feedback an answer gets: what happened, and — when the answer was wrong — what the
 * right one was. Colour is never the only signal; the words say it too.
 */
export function Feedback({
  tone,
  text,
}: {
  tone: "right" | "wrong";
  text: string;
}) {
  const {colors} = useTheme();
  return (
    <Text
      accessibilityLiveRegion="polite"
      style={[
        styles.feedback,
        {color: tone === "right" ? colors.success : colors.error},
      ]}
    >
      {text}
    </Text>
  );
}

/**
 * A screen with nothing to show yet: a loading state, an empty queue, a failure, or the connection
 * state. One mark, a title, one short paragraph, and the actions that answer it — centred, with no
 * card around it, because there is no content here for a surface to hold.
 */
export function StateBlock({
  mark,
  title,
  body,
  note,
  children,
}: {
  /** The mark above the title: a brand tile, or the state's own glyph. */
  mark?: React.ReactNode;
  title: string;
  body?: string;
  note?: string;
  children?: React.ReactNode;
}) {
  const {colors} = useTheme();
  return (
    <View style={styles.stateBlock}>
      {mark != null && <View style={styles.stateMark}>{mark}</View>}
      <Text style={[styles.stateTitle, {color: colors.onSurface}]}>{title}</Text>
      {body != null && (
        <Text style={[typeScale.description, styles.stateBody, {color: colors.onSurfaceVariant}]}>
          {body}
        </Text>
      )}
      {note != null && (
        <Text style={[typeScale.bodySmall, styles.stateNote, {color: colors.onSurfaceVariant}]}>
          {note}
        </Text>
      )}
      {children != null && <View style={styles.stateActions}>{children}</View>}
    </View>
  );
}

/** One quiet chip, for a state that is a fact rather than an action: `Not connected`. */
export function StateChip({label}: {label: string}) {
  const {colors} = useTheme();
  return (
    <View style={[styles.chip, {borderColor: colors.outlineVariant}]}>
      <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>{label}</Text>
    </View>
  );
}

/** "Something is on its way": one small spinner beside its own line, at the layout's own rhythm. */
export function BusyRow({message}: {message: string}) {
  const {colors} = useTheme();
  return (
    <View style={styles.busyRow} accessible accessibilityLabel={message}>
      <ActivityIndicator size="small" color={colors.accent} />
      <Text style={[typeScale.bodySmall, styles.busyText, {color: colors.onSurfaceVariant}]}>
        {message}
      </Text>
    </View>
  );
}

/**
 * The arrival of a nested screen: 8 dp from the trailing edge, and 70% to full opacity, over the
 * design's 200 ms. It keys on the screen's own identity, so moving between two cards of one session
 * changes nothing here — the design's motion belongs to entering a screen, not to every redraw. A back
 * move enters from the leading edge, which is the same gesture reversed.
 *
 * The first render after mount is not an arrival: this screen was already the one being looked at
 * (the shell mounts every tab's scene up front), so nothing animates until the page key changes.
 */
export function StepEnter({
  pageKey,
  forward,
  children,
}: {
  pageKey: string;
  forward: boolean;
  children: React.ReactNode;
}) {
  const reducedMotion = useReducedMotion();
  const progress = useSharedValue(1);
  const shown = useRef<string | null>(null);

  useEffect(() => {
    if (shown.current === null) {
      shown.current = pageKey;
      return;
    }
    if (shown.current === pageKey) return;
    shown.current = pageKey;
    if (reducedMotion) {
      progress.value = 1;
      return;
    }
    progress.value = 0;
    progress.value = withTiming(1, {duration: ENTER_MS, easing: INK_EASING});
  }, [pageKey, reducedMotion, progress]);

  const style = useAnimatedStyle(() => ({
    opacity: 0.7 + 0.3 * progress.value,
    transform: [{translateX: (1 - progress.value) * (forward ? ENTER_TRAVEL : -ENTER_TRAVEL)}],
  }));

  return <Animated.View style={[styles.enter, style]}>{children}</Animated.View>;
}

const styles = StyleSheet.create({
  title: {marginTop: 23},
  subtitle: {marginTop: 11},
  caption: {marginTop: 34, marginBottom: 10, paddingBottom: 12, borderBottomWidth: 1},
  tile: {alignItems: "center", justifyContent: "center", flexShrink: 0},
  source: {
    flexDirection: "row",
    alignItems: "center",
    minHeight: 101,
    paddingVertical: 19,
    borderBottomWidth: 1,
    width: "100%",
  },
  sourceMain: {flex: 1, minWidth: 0, marginStart: 14},
  sourceName: {fontSize: 16, fontWeight: "700", letterSpacing: -0.4},
  sourceStatus: {marginTop: 5},
  sourceSide: {alignItems: "flex-end", marginStart: spacing.m},
  sourceValue: {fontSize: 17, fontWeight: "700", letterSpacing: -0.45},
  sourceValueLabel: {fontSize: 10, lineHeight: 13},
  sourceAction: {fontSize: 11, lineHeight: 15, textAlign: "right"},
  subHeader: {
    flexDirection: "row",
    alignItems: "center",
    minHeight: 66,
    paddingTop: 20,
    paddingBottom: 10,
  },
  backButton: {
    width: 48,
    height: 48,
    alignItems: "flex-start",
    justifyContent: "center",
  },
  // The title's own padding is symmetric, so it keeps the screen's centre; it also keeps a gap to
  // the count for the titles that run out of room (a deck name) and ellipsize against it.
  subHeaderTitle: {
    flex: 1,
    minWidth: 0,
    paddingHorizontal: spacing.m,
    fontSize: 14,
    fontWeight: "700",
    letterSpacing: -0.3,
    textAlign: "center",
  },
  // The mirror of the back button's own 48 dp lane, so the title between the two lanes sits on the
  // screen's centre. A count wider than the lane (a large accessibility font) widens it and shifts
  // the title rather than letting the two run into each other; the title ellipsizes instead.
  subHeaderRightLane: {minWidth: 48, flexShrink: 0, alignItems: "flex-end", justifyContent: "center"},
  subHeaderRight: {fontSize: 11},
  progressArea: {marginTop: 2},
  progressTrack: {height: 2, width: "100%", overflow: "hidden"},
  progressFill: {height: 2},
  check: {alignItems: "center", justifyContent: "center", flexShrink: 0},
  studyRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.m,
    paddingVertical: spacing.m,
    borderBottomWidth: 1,
    width: "100%",
  },
  studyHolder: {
    width: 48,
    height: 48,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    flexShrink: 0,
  },
  studyMain: {flex: 1, minWidth: 0},
  studyTitle: {fontWeight: "700", letterSpacing: -0.25},
  studySubline: {fontSize: 12, lineHeight: 16, marginTop: 3},
  studyExtra: {fontSize: 11, lineHeight: 15, marginTop: 3},
  studyTrailing: {alignItems: "flex-end", marginStart: spacing.s},
  studyCount: {fontSize: 25, lineHeight: 29, fontWeight: "700", letterSpacing: -0.8},
  studyCountLabel: {fontSize: 10, lineHeight: 13, marginTop: 1},
  metricRow: {flexDirection: "row", alignItems: "flex-start", marginTop: spacing.s},
  metricRowStacked: {flexDirection: "column", gap: spacing.l},
  metric: {flex: 1, minWidth: 0},
  // One vertical hairline between the two figures, as the paired figures elsewhere in the app use.
  metricSpaced: {borderStartWidth: 1, paddingStart: spacing.l, marginStart: spacing.l},
  metricValue: {fontSize: 25, lineHeight: 29, fontWeight: "700", letterSpacing: -0.8},
  metricLabel: {fontSize: 12, lineHeight: 16, fontWeight: "600", marginTop: 3},
  metricHint: {fontSize: 11, lineHeight: 15, marginTop: 3},
  action: {
    minHeight: 52,
    borderRadius: shape.button,
    alignItems: "center",
    justifyContent: "center",
    width: "100%",
    paddingHorizontal: spacing.xl,
  },
  actionLabel: {fontSize: 15, fontWeight: "600", letterSpacing: -0.18},
  footer: {
    paddingHorizontal: spacing.contentInset,
    paddingTop: 17,
    borderTopWidth: 1,
    width: "100%",
  },
  ratingRow: {flexDirection: "row", width: "100%"},
  ratingCell: {flex: 1, minWidth: 0},
  ratingCellSpaced: {marginStart: 7},
  ratingButton: {
    minHeight: 49,
    borderRadius: 11,
    borderWidth: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: spacing.s,
    paddingHorizontal: spacing.xs,
  },
  ratingName: {fontSize: 12, fontWeight: "600"},
  ratingInterval: {fontSize: 10, lineHeight: 13, marginTop: 2},
  input: {
    fontSize: 21,
    lineHeight: 28,
    fontWeight: "700",
    textAlign: "left",
    width: "100%",
    minHeight: 62,
    paddingTop: 9,
    paddingBottom: 7,
    paddingHorizontal: 1,
    borderTopWidth: 0,
    borderLeftWidth: 0,
    borderRightWidth: 0,
    borderBottomWidth: 2,
    borderRadius: 0,
  },
  feedback: {fontSize: 12, lineHeight: 18, minHeight: 21, marginTop: 17},
  stateBlock: {alignItems: "center", width: "100%", maxWidth: 300, alignSelf: "center"},
  stateMark: {marginBottom: 21},
  stateTitle: {fontSize: 23, fontWeight: "700", letterSpacing: -0.7, textAlign: "center"},
  stateBody: {marginTop: 14, textAlign: "center"},
  stateNote: {marginTop: spacing.m, textAlign: "center"},
  stateActions: {marginTop: 21, width: "100%", gap: spacing.m},
  chip: {
    borderWidth: 1,
    borderRadius: 9,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.s,
    marginTop: 21,
    alignSelf: "center",
  },
  busyRow: {flexDirection: "row", alignItems: "center", minHeight: 21},
  busyText: {marginStart: spacing.m, flexShrink: 1},
  enter: {flex: 1},
});
