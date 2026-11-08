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
 * screen that shows them reads the store in scope: Home's default, or the lookup sheet's own
 * scoped instance while the overlay is up.
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
  CONTENT_COLUMN,
  contentInsetFor,
  PRESS_EASING_REANIMATED,
  shape,
  spacing,
  typeScale,
} from "../tokens";
import type {SearchResult} from "../../native/GatenDict";
import {SearchArtwork} from "./SearchArtwork";
import * as store from "./searchStore";
import {useSearchStore} from "./searchStore";

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
export const SearchRowItem = React.memo(function SearchRowItem({
  row,
  onOpen,
  flat = false,
}: {
  row: SearchRow;
  onOpen: (entryId: number) => void;
  /** The hosted list already paints the surface; the card carries it down to the row. */
  flat?: boolean;
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
  return <ResultCard hit={row.hit} onOpen={onOpen} flat={flat} />;
});

/** Stable key extractor so the list does not see a new function every render. */
export const searchRowKey = (row: SearchRow) => row.key;

/**
 * The gap between two rows of the results. A flat list has no card fill to part its rows, so the gap
 * becomes the hairline the sheet's own separators use.
 */
export function SearchRowSeparator({flat = false}: {flat?: boolean}) {
  const {colors} = useTheme();
  if (flat) {
    return (
      <View
        style={{
          borderBottomWidth: StyleSheet.hairlineWidth,
          borderBottomColor: colors.outlineVariant,
        }}
      />
    );
  }
  return <View style={{height: spacing.m}} />;
}

/**
 * The empty search's own content: the words to tap rather than type, and the drawing under them.
 * Until the reader has searched at all those words are the examples; once they have, their own
 * searched words take their place under a line naming them — the words they came back for, not a
 * lesson on what the field takes. A chip is a choice rather than typing, so it is asked for at once
 * — anything half-typed is cancelled so it cannot land over it.
 */
export function SearchIdlePrompt({flat = false}: {flat?: boolean}) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  const searchStore = useSearchStore();
  const {recentSearches} = React.useSyncExternalStore(
    searchStore.subscribe,
    searchStore.getState,
  );
  const firstSearch = recentSearches.length === 0;
  // The list already insets itself by the screen's own content inset; the drawing takes what is
  // left of that column, up to the width it is drawn for.
  const inset = contentInsetFor(width);
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
              searchStore.onQueryChange(word);
              searchStore.searchNow();
            }}
            style={[styles.exampleChip, flat ? styles.exampleChipFlat : {backgroundColor: colors.surfaceVariant}]}
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
          Search by kanji, kana, or a reading in romaji.
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
  onBlur,
  style,
  compact = false,
  flat = false,
}: {
  inputRef: React.RefObject<TextInput | null>;
  query: string;
  queryLimited: boolean;
  onChangeQuery: (text: string) => void;
  onSearch: () => void;
  onFocus?: () => void;
  onBlur?: () => void;
  style?: React.ComponentProps<typeof View>["style"];
  compact?: boolean;
  /** The hosted tree already paints the surface: a hairline rule stands in for the filled shell. */
  flat?: boolean;
}) {
  const {colors} = useTheme();
  const reducedMotion = useReducedMotion();
  const focus = useSharedValue(0);
  // Flat, the resting rule is the surface's own hairline; the accent still arrives on focus.
  const restBorder = flat ? colors.outlineVariant : "transparent";
  const focusRing = useAnimatedStyle(() => ({
    borderColor: interpolateColor(
      focus.value,
      [0, 1],
      [restBorder, colors.accent],
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
          flat && styles.flatFieldShell,
          !flat && {backgroundColor: colors.surfaceVariant},
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
          onBlur={() => {
            settle(0);
            onBlur?.();
          }}
          accessibilityLabel="Search Japanese dictionary"
          placeholder="猫, ねこ, neko..."
          placeholderTextColor={colors.onSurfaceVariant}
          cursorColor={colors.accent}
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
export const ResultCard = React.memo(function ResultCard({
  hit,
  onOpen,
  flat = false,
}: {
  hit: SearchResult;
  onOpen: (entryId: number) => void;
  /** The hosted list already paints the surface: the card drops its fill and rounding. */
  flat?: boolean;
}) {
  const {colors} = useTheme();
  return (
    <PressableCard
      accessibilityLabel={`Open ${hit.primary}`}
      onPress={() => onOpen(hit.id)}
      style={[styles.resultCard, flat ? styles.resultCardFlat : {backgroundColor: colors.surfaceVariant}]}
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
            <View style={styles.metaPill}>
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
});

/** The box a results list puts its rows in: the app's content column, with the screen's rhythm. */
export const SEARCH_LIST_CONTENT = {
  ...CONTENT_COLUMN,
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
  // Flat, the chip is the bare word: the fill and its rounding go, but the 48 dp target stays —
  // `minWidth` is what keeps a one-character example from shrinking below it.
  exampleChipFlat: {borderRadius: 0, paddingHorizontal: 0, minWidth: 48},
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
  // Flat, the field keeps its size and drops to a hairline rule on the surface's own ink.
  flatFieldShell: {
    borderWidth: 0,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderRadius: 0,
  },
  field: {flex: 1, minWidth: 0, paddingHorizontal: 0, paddingVertical: spacing.m, ...typeScale.bodyMedium},
  limitNote: {marginHorizontal: spacing.xl, marginTop: spacing.xs},
  header: {marginTop: spacing.s},
  resultCount: {marginBottom: spacing.xs},
  resultCard: {borderRadius: shape.small},
  // Flat, the card is a row on the surface: no fill, no rounding, parted from the next by the list's
  // own hairline.
  resultCardFlat: {backgroundColor: "transparent", borderRadius: 0},
  resultRow: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: spacing.m,
    paddingHorizontal: spacing.l,
  },
  resultMain: {flex: 1},
  headwordRow: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  // The part of speech is the line of text itself: the card it sits in is the only box.
  metaPill: {alignSelf: "flex-start", marginTop: spacing.xs},
  line: {marginTop: spacing.xs},
  glosses: {marginTop: spacing.s},
});
