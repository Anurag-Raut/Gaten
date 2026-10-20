/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The pieces the search surfaces share: the capsule field, the example chips, the row list and its
 * cards. They were the Search tab's own file; the Home tab now shows the same field and the same
 * rows inline, so both screens draw from here rather than from two copies. Nothing here holds
 * state — the query, the results and the reading position all live in `searchStore`, and every
 * screen that shows them reads the same store.
 */
import React from "react";
import {
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import Svg, {Circle, Path} from "react-native-svg";
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {CommonChip, Icon, PressableCard} from "../components";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {
  MAX_CONTENT_WIDTH,
  PRESS_EASING_REANIMATED,
  shape,
  spacing,
  typeScale,
} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import * as store from "./searchStore";

/** How long the field's focus ring takes to fade in or out. */
const FOCUS_MS = 150;

/** The examples the empty prompt offers, as a direct choice rather than something to type. */
export const SEARCH_EXAMPLES = ["猫", "たべる", "食べた", "ありがとう", "学校", "面白い"];

/** One row of the results list: the count, the prefix header, or a hit. */
export type SearchRow =
  | {
      key: "count";
      kind: "count";
      count: number;
      /**
       * The query the visible rows really answer, when that is not the one in the field: typing moves
       * the field at once and the rows only when the lookup lands, so the count line says which search
       * these rows belong to instead of leaving the reader to guess.
       */
      forQuery: string | null;
    }
  | {key: "suggestions-header"; kind: "header"; label: string}
  | {key: string; kind: "hit"; hit: SearchResult};

/**
 * The rows for one search state: the results themselves, not the status, so that while the reader
 * types the next word the last word's rows stay on screen — labelled by the query that found them —
 * until the new ones land.
 */
export function searchRows(state: store.SearchState): SearchRow[] {
  if (state.hits.length === 0) {
    return [];
  }
  const answering = store.normalizeQuery(state.hitsQuery);
  const asked = store.normalizeQuery(state.query);
  const rows: SearchRow[] = [
    {
      key: "count",
      kind: "count",
      count: state.hits.length,
      forQuery: answering.length > 0 && answering !== asked ? answering : null,
    },
  ];
  for (const hit of state.hits) {
    if (hit.match !== "PREFIX") {
      rows.push({key: `hit-${hit.id}`, kind: "hit", hit});
    }
  }
  const prefix = state.hits.filter((hit) => hit.match === "PREFIX");
  if (prefix.length > 0) {
    rows.push({
      key: "suggestions-header",
      kind: "header",
      label: `Words starting with ${store.normalizeQuery(state.hitsQuery)}`,
    });
    for (const hit of prefix) {
      rows.push({key: `hit-${hit.id}`, kind: "hit", hit});
    }
  }
  return rows;
}

/** One result row: the count line, a prefix header, or the card for a hit. */
export function SearchRowItem({
  row,
  onOpen,
}: {
  row: SearchRow;
  onOpen: (entryId: number) => void;
}) {
  const {colors} = useTheme();
  if (row.kind === "count") {
    return (
      <Text style={[typeScale.labelLarge, {color: colors.onSurfaceVariant}]}>
        {row.forQuery == null ? `${row.count} results` : `${row.count} results for ${row.forQuery}`}
      </Text>
    );
  }
  if (row.kind === "header") {
    return (
      <Text style={[typeScale.titleSmall, styles.header, {color: colors.onSurfaceVariant}]}>
        {row.label}
      </Text>
    );
  }
  return <ResultCard hit={row.hit} onOpen={onOpen} />;
}

/** The gap between two rows of the results. */
export function SearchRowSeparator() {
  return <View style={{height: spacing.m}} />;
}

/**
 * The empty state's own content: what a search can be, and one tap to try it. A chip is a choice
 * rather than typing, so it is asked for at once — anything half-typed is cancelled so it cannot
 * land over it.
 */
export function SearchIdlePrompt() {
  const {colors} = useTheme();
  return (
    <View style={styles.prompt}>
      <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
        Look up a word
      </Text>
      <Text
        style={[typeScale.bodyMedium, styles.promptHint, {color: colors.onSurfaceVariant}]}
      >
        Kanji, kana, a romaji reading such as neko or tokyo, or an inflected form such as
        食べた. Gaten looks up words offline, from the dictionary on this device.
      </Text>
      <View style={styles.flowRow}>
        {SEARCH_EXAMPLES.map((example) => (
          <PressableScale
            key={example}
            accessibilityRole="button"
            accessibilityLabel={`Search for ${example}`}
            onPress={() => {
              store.onQueryChange(example);
              store.searchNow();
            }}
            style={[styles.exampleChip, {backgroundColor: colors.surfaceVariant}]}
          >
            <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{example}</Text>
          </PressableScale>
        ))}
      </View>
    </View>
  );
}

/**
 * The query field: a capsule on a quiet fill, with the accent appearing only while the reader is in
 * one — a 1dp ring that fades from the outline to the accent. It never takes the cursor by itself:
 * the keyboard opens on the reader's own tap and on nothing else.
 */
export function SearchField({
  inputRef,
  query,
  queryLimited,
  onChangeQuery,
  onSearch,
  style,
  compact = false,
}: {
  inputRef: React.RefObject<TextInput | null>;
  query: string;
  queryLimited: boolean;
  onChangeQuery: (text: string) => void;
  onSearch: () => void;
  style?: React.ComponentProps<typeof View>["style"];
  compact?: boolean;
}) {
  const {colors, palette} = useTheme();
  const referenceLight = compact && palette === "Gaten" && Number.parseInt(colors.background.slice(1, 3), 16) >= 128;
  const reducedMotion = useReducedMotion();
  const focus = useSharedValue(0);
  const focusRing = useAnimatedStyle(() => ({
    borderColor: interpolateColor(
      focus.value,
      [0, 1],
      [referenceLight ? "#E3E3E7" : colors.outlineVariant, colors.primary],
    ),
  }));
  const settle = (target: number) => {
    focus.value = reducedMotion
      ? target
      : withTiming(target, {duration: FOCUS_MS, easing: PRESS_EASING_REANIMATED});
  };
  return (
    <View style={style}>
      <Animated.View
        style={[
          styles.fieldShell,
          compact && styles.compactFieldShell,
          {backgroundColor: referenceLight ? "#EFEFF1" : colors.surfaceVariant},
          focusRing,
        ]}
      >
        <Svg width={compact ? 22 : 24} height={compact ? 22 : 24} viewBox="0 0 24 24"><Circle cx={10.5} cy={10.5} r={6.5} stroke={referenceLight ? "#4A4A50" : colors.onSurfaceVariant} strokeWidth={2.4} fill="none" /><Path d="m16 16 4.5 4.5" stroke={referenceLight ? "#4A4A50" : colors.onSurfaceVariant} strokeWidth={2.4} strokeLinecap="round" /></Svg>
        <TextInput
          ref={inputRef}
          value={query}
          onChangeText={onChangeQuery}
          onFocus={() => settle(1)}
          onBlur={() => settle(0)}
          placeholder="猫, ねこ, neko…"
          placeholderTextColor={referenceLight ? "#7C7C84" : colors.onSurfaceVariant}
          returnKeyType="search"
          onSubmitEditing={onSearch}
          style={[styles.field, compact && styles.homeField, {color: colors.onSurface}]}
          underlineColorAndroid="transparent"
        />
        {query.length > 0 && (
          <PressableScale
            accessibilityRole="button"
            accessibilityLabel="Clear the query"
            onPress={() => onChangeQuery("")}
            style={styles.clearButton}
          >
            <Icon name="close" size={24} color={colors.onSurfaceVariant} />
          </PressableScale>
        )}
      </Animated.View>
      {queryLimited && (
        <Text style={[typeScale.bodySmall, styles.limitNote, {color: colors.onSurfaceVariant}]}>
          Searches look at the first {store.QUERY_LIMIT} characters.
        </Text>
      )}
    </View>
  );
}

/** One dictionary entry as a card: the headword, its reading, its first glosses and its part of speech. */
export function ResultCard({
  hit,
  onOpen,
}: {
  hit: SearchResult;
  onOpen: (entryId: number) => void;
}) {
  const {colors} = useTheme();
  return (
    <PressableCard
      accessibilityLabel={`Open ${hit.primary}`}
      onPress={() => onOpen(hit.id)}
      style={styles.resultCard}
    >
      <View style={styles.resultRow}>
        <View style={styles.resultMain}>
          <View style={styles.headwordRow}>
            <Text numberOfLines={1} style={[typeScale.titleLarge, {color: colors.onSurface}]}>
              {hit.primary}
            </Text>
            {hit.common && <CommonChip />}
          </View>
          {hit.kana.length > 0 && (
            <Text
              numberOfLines={1}
              style={[typeScale.bodyMedium, styles.line, {color: colors.onSurfaceVariant}]}
            >
              {hit.kana.join("・")}
            </Text>
          )}
          {hit.glosses.length > 0 && (
            <Text
              numberOfLines={2}
              style={[typeScale.bodyLarge, styles.glosses, {color: colors.onSurface}]}
            >
              {hit.glosses.slice(0, 3).join("; ")}
            </Text>
          )}
          {hit.pos.length > 0 && (
            <View style={[styles.metaPill, {backgroundColor: colors.surfaceVariant}]}>
              <Text numberOfLines={1} style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>
                {hit.pos[0]}
              </Text>
            </View>
          )}
          {(hit.match === "NORMALIZED" || hit.match === "LOOSE") && (
            <Text
              numberOfLines={1}
              style={[typeScale.labelMedium, styles.line, {color: colors.primary}]}
            >
              from {hit.form}
            </Text>
          )}
        </View>
        <Icon name="keyboard_arrow_right" color={colors.onSurfaceVariant} />
      </View>
    </PressableCard>
  );
}

/** The box a results list puts its rows in: the app's content column, with the screen's rhythm. */
export const SEARCH_LIST_CONTENT = {
  width: "100%" as const,
  maxWidth: MAX_CONTENT_WIDTH,
  alignSelf: "center" as const,
  paddingHorizontal: spacing.xl,
  paddingTop: spacing.m,
  paddingBottom: spacing.xxl,
};

const styles = StyleSheet.create({
  prompt: {padding: spacing.xl},
  promptHint: {marginTop: spacing.s},
  flowRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: spacing.s,
    marginTop: spacing.l,
  },
  exampleChip: {
    borderRadius: shape.large,
    paddingHorizontal: spacing.l,
    paddingVertical: spacing.m,
  },
  fieldShell: {
    flexDirection: "row",
    alignItems: "center",
    // The radius ladder's largest step is exactly half this field's height, so it is the capsule.
    borderRadius: shape.large,
    borderWidth: 1,
    height: 56,
    gap: spacing.s,
    marginHorizontal: spacing.xl,
    marginVertical: spacing.s,
    paddingHorizontal: spacing.l,
  },
  compactFieldShell: {
    height: 44,
    borderRadius: 22,
    marginHorizontal: spacing.l,
    marginVertical: 0,
    paddingHorizontal: 16,
    gap: 12,
  },
  homeField: {fontFamily: "Inter", fontSize: 16, lineHeight: 22, fontWeight: "400"},
  field: {flex: 1, padding: 0, ...typeScale.bodyLarge},
  clearButton: {width: 40, height: 40, alignItems: "center", justifyContent: "center"},
  limitNote: {marginHorizontal: spacing.xl, marginTop: spacing.xs},
  header: {marginTop: spacing.s},
  resultCard: {borderRadius: shape.medium},
  resultRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: spacing.l,
    paddingHorizontal: spacing.xl,
  },
  resultMain: {flex: 1},
  headwordRow: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  metaPill: {
    alignSelf: "flex-start",
    borderRadius: 999,
    paddingHorizontal: spacing.s,
    paddingVertical: 2,
    marginTop: spacing.xs,
  },
  line: {marginTop: spacing.xs},
  glosses: {marginTop: spacing.s},
});
