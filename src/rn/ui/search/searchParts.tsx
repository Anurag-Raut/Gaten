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
  useWindowDimensions,
} from "react-native";
import Animated, {
  interpolateColor,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {CommonChip, Icon, PressableCard} from "../components";
import {InkIcon} from "../InkIcon";
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
import {SearchArtwork} from "./SearchArtwork";
import * as store from "./searchStore";

/** How long the field's focus ring takes to fade in or out. */
const FOCUS_MS = 150;

/** The empty search's drawing is drawn for this column; wider, and it stops reading as a mark. */
const ART_MAX_WIDTH = 300;

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
      <Text style={[typeScale.bodySmall, styles.resultCount, {color: colors.onSurfaceVariant}]}>
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
 * The empty search's own content: the words to tap rather than type, and the drawing under them.
 * Until the reader has searched at all those words are the examples; once they have, their own
 * searched words take their place under a line naming them — the words they came back for, not a
 * lesson on what the field takes. A chip is a choice rather than typing, so it is asked for at once
 * — anything half-typed is cancelled so it cannot land over it.
 */
export function SearchIdlePrompt() {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  const {recentSearches} = store.useSearchState();
  const firstSearch = recentSearches.length === 0;
  // The list already insets itself by the screen's own content inset; the drawing takes what is
  // left of that column, up to the width it is drawn for.
  const inset = width < 360 ? spacing.l : spacing.contentInset;
  const artWidth = Math.min(ART_MAX_WIDTH, width - (inset + spacing.l) * 2);
  return (
    <View style={styles.prompt}>
      {!firstSearch && (
        <Text style={[typeScale.labelLarge, {color: colors.onSurfaceVariant}]}>
          Recent searches
        </Text>
      )}
      <View style={[styles.flowRow, !firstSearch && styles.recentRow]}>
        {(firstSearch ? SEARCH_EXAMPLES : recentSearches).map((word) => (
          <PressableScale
            key={word}
            accessibilityRole="button"
            accessibilityLabel={`Search for ${word}`}
            onPress={() => {
              store.onQueryChange(word);
              store.searchNow();
            }}
            style={[styles.exampleChip, {backgroundColor: colors.surfaceVariant}]}
          >
            <Text numberOfLines={1} style={[typeScale.titleMedium, {color: colors.onSurface}]}>
              {word}
            </Text>
          </PressableScale>
        ))}
      </View>
      <View style={styles.art}>
        <SearchArtwork width={artWidth} />
        <Text style={[typeScale.bodyMedium, styles.artCaption, {color: colors.onSurfaceVariant}]}>
          Find your next Japanese word
        </Text>
      </View>
    </View>
  );
}

/**
 * The query field uses the shared control shape and neutral fill. Its quiet border becomes the
 * accent on focus, and focus is still opened only by the reader's own tap.
 */
export function SearchField({
  inputRef,
  query,
  queryLimited,
  onChangeQuery,
  onSearch,
  onFocus,
  style,
  compact = false,
}: {
  inputRef: React.RefObject<TextInput | null>;
  query: string;
  queryLimited: boolean;
  onChangeQuery: (text: string) => void;
  onSearch: () => void;
  onFocus?: () => void;
  style?: React.ComponentProps<typeof View>["style"];
  compact?: boolean;
}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const focus = useSharedValue(0);
  const focusRing = useAnimatedStyle(() => ({
    borderColor: interpolateColor(
      focus.value,
      [0, 1],
      ["transparent", colors.accent],
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
        onTouchEnd={() => inputRef.current?.focus()}
        style={[
          styles.fieldShell,
          compact && styles.compactFieldShell,
          {backgroundColor: colors.surfaceVariant},
          focusRing,
        ]}
      >
        <InkIcon name="search" color={colors.iconPrimary} />
        <TextInput
          ref={inputRef}
          value={query}
          onChangeText={onChangeQuery}
          onFocus={() => {
            settle(1);
            onFocus?.();
          }}
          onBlur={() => settle(0)}
          accessibilityLabel="Search Japanese dictionary"
          placeholder="猫, ねこ, neko..."
          placeholderTextColor={colors.onSurfaceVariant}
          returnKeyType="search"
          onSubmitEditing={onSearch}
          style={[styles.field, {color: colors.onSurface}]}
          underlineColorAndroid="transparent"
        />
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
      style={[styles.resultCard, {backgroundColor: colors.surfaceVariant}]}
    >
      <View style={styles.resultRow}>
        <View style={styles.resultMain}>
          <View style={styles.headwordRow}>
            <Text numberOfLines={1} style={[typeScale.titleMedium, {color: colors.onSurface}]}>
              {hit.primary}
            </Text>
            {hit.common && <CommonChip />}
          </View>
          {hit.kana.length > 0 && (
            <Text
              numberOfLines={1}
              style={[typeScale.bodySmall, styles.line, {color: colors.onSurfaceVariant}]}
            >
              {hit.kana.join("・")}
            </Text>
          )}
          {hit.glosses.length > 0 && (
            <Text
              numberOfLines={2}
              style={[typeScale.bodyMedium, styles.glosses, {color: colors.onSurface}]}
            >
              {hit.glosses.slice(0, 3).join("; ")}
            </Text>
          )}
          {hit.pos.length > 0 && (
            <View style={[styles.metaPill, {backgroundColor: colors.surface}]}>
              <Text numberOfLines={1} style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>
                {hit.pos[0]}
              </Text>
            </View>
          )}
          {(hit.match === "NORMALIZED" || hit.match === "LOOSE") && (
            <Text
              numberOfLines={1}
              style={[typeScale.bodySmall, styles.line, {color: colors.accent}]}
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
  paddingHorizontal: spacing.l,
  paddingTop: spacing.m,
  paddingBottom: spacing.xxl,
};

const styles = StyleSheet.create({
  prompt: {padding: spacing.l},
  flowRow: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: spacing.s,
    marginTop: spacing.l,
  },
  // The searched words sit under the line that names them, a step closer to it than to nothing.
  recentRow: {marginTop: spacing.s},
  art: {alignItems: "center", marginTop: spacing.xl},
  artCaption: {marginTop: spacing.m, maxWidth: ART_MAX_WIDTH, textAlign: "center"},
  exampleChip: {
    borderRadius: shape.small,
    minHeight: 48,
    maxWidth: "100%",
    justifyContent: "center",
    paddingHorizontal: spacing.l,
    paddingVertical: spacing.m,
  },
  fieldShell: {
    flexDirection: "row",
    alignItems: "center",
    borderRadius: shape.small,
    borderWidth: 2,
    minHeight: 49,
    gap: spacing.s,
    marginHorizontal: spacing.contentInset,
    marginVertical: 0,
    paddingHorizontal: 15,
  },
  compactFieldShell: {
    minHeight: 52,
    borderRadius: shape.small,
    marginHorizontal: 0,
    marginVertical: 0,
    paddingHorizontal: 15,
    paddingVertical: 0,
    gap: 13,
  },
  field: {flex: 1, minWidth: 0, paddingHorizontal: 0, paddingVertical: spacing.m, ...typeScale.bodyMedium},
  limitNote: {marginHorizontal: spacing.xl, marginTop: spacing.xs},
  header: {marginTop: spacing.s},
  resultCount: {marginBottom: spacing.xs},
  resultCard: {borderRadius: shape.small},
  resultRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: spacing.m,
    paddingHorizontal: spacing.l,
  },
  resultMain: {flex: 1},
  headwordRow: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  metaPill: {
    alignSelf: "flex-start",
    borderRadius: shape.brandTile,
    paddingHorizontal: spacing.s,
    paddingVertical: spacing.xs,
    marginTop: spacing.xs,
  },
  line: {marginTop: spacing.xs},
  glosses: {marginTop: spacing.s},
});
