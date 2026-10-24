/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The React Native root of the lookup sheet, mounted inside the native accessibility overlay.
 *
 * The overlay owns the window, the frozen capture, OCR and selection. Everything inside the sheet —
 * the word tabs, the entry detail, the WaniKani kanji pages, the shared dictionary search, the
 * empty states and the Anki footer — is this app's own RN UI, reusing the same components and store
 * the Home tab uses. Native hands over the resolved entries in the state payload, so the sheet never
 * re-searches a surface or guesses which entry the reader meant.
 *
 * The panel slides on the UI thread (Reanimated) and is reduced-motion safe. `pointerEvents` on the
 * transparent backdrop is `box-none` so the lasso/selection underneath the sheet is never blocked.
 */
import React, {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {BackHandler, FlatList, ScrollView, StyleSheet, Text, TextInput, View} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import Animated, {
  SlideInDown,
  SlideOutDown,
  useReducedMotion,
} from "react-native-reanimated";
import {SafeAreaProvider, useSafeAreaInsets} from "react-native-safe-area-context";
import {IconButton} from "../components";
import {useTheme, ThemeProvider} from "../theme";
import {MAX_CONTENT_WIDTH, shape, spacing, typeScale} from "../tokens";
import {
  SEARCH_LIST_CONTENT,
  SearchField,
  SearchIdlePrompt,
  SearchRowItem,
  SearchRowSeparator,
  searchRows,
  type SearchRow,
} from "../search/searchParts";
import {SearchNotice} from "../search/SearchScreen";
import {
  AddToAnkiBar,
  HeadwordSection,
  MeaningSection,
  OtherSpellingsRow,
  WordKanjiSection,
} from "../search/WordDetailScreen";
import {SearchKanjiScreen, SearchRadicalScreen} from "../search/ReferenceScreen";
import {useAnkiExport} from "../search/useAnkiExport";
import {distinctIdeographs} from "../components";
import type {SearchResult} from "../../native/GatenDict";
import * as GatenDict from "../../native/GatenDict";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import * as store from "../search/searchStore";
import * as bridge from "../../native/GatenLookupSheet";
import {NoMatchesArt, NoTextArt} from "./LookupEmptyArtwork";

/** A page over the entry, walked down by Back before the lookup itself closes. */
type ReferenceTarget =
  | {kind: "kanji"; character: string}
  | {kind: "radical"; id: number};

function LookupSheetRoot() {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const reducedMotion = useReducedMotion();
  const anki = useAnkiExport();

  const [state, setState] = useState<bridge.LookupSheetState>({
    mode: "sheet",
    source: "",
    words: [],
    selected: 0,
    query: "",
    emptyKind: null,
  });
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchedEntry, setSearchedEntry] = useState<SearchResult | null>(null);
  const [references, setReferences] = useState<ReferenceTarget[]>([]);
  const [wanikaniConfigured, setWanikaniConfigured] = useState(false);
  const input = useRef<TextInput>(null);

  // One state from native rebuilds the whole sheet; the first read covers a missed event.
  useEffect(() => {
    let cancelled = false;
    void bridge.getState().then((raw) => {
      if (!cancelled) setState(bridge.parseState(raw));
    });
    const subscription = bridge.addLookupSheetStateListener((raw) => {
      setState(bridge.parseState(raw));
      setSearchOpen(false);
      setSearchedEntry(null);
      setReferences([]);
    });
    bridge.ready();
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, []);

  useEffect(() => {
    return () => bridge.setTextEntryActive(false);
  }, []);

  const closeReference = useCallback(() => {
    setReferences((stack) => stack.slice(0, -1));
  }, []);

  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      if (references.length > 0) {
        closeReference();
      } else if (searchOpen) {
        setSearchOpen(false);
      } else {
        bridge.close();
      }
      return true;
    });
    return () => subscription.remove();
  }, [closeReference, references.length, searchOpen]);

  const selectedWord = state.words[state.selected] ?? state.words[0] ?? null;
  const displayedEntry = useMemo<SearchResult | null>(() => {
    if (searchedEntry != null) return searchedEntry;
    return selectedWord?.entries[0] ?? null;
  }, [searchedEntry, selectedWord]);

  // The entry's session, run on the surface that shows it: remember the lookup and refresh Anki's
  // duplicate state. This is the same work the Home word detail does on focus, without needing a
  // navigator in the overlay.
  const recordedEntryId = useRef<number | null>(null);
  useEffect(() => {
    if (displayedEntry == null) return;
    void GatenWaniKani.isConfigured().then(setWanikaniConfigured);
    if (recordedEntryId.current !== displayedEntry.id) {
      recordedEntryId.current = displayedEntry.id;
      void GatenDict.rememberLookup(displayedEntry).catch((error: unknown) => {
        recordedEntryId.current = null;
        console.warn("Could not save the recent word lookup", error);
      });
    }
    void anki.prepareExport(displayedEntry);
  }, [displayedEntry]);

  const searchState = store.useSearchState();
  const searchRowsData = useMemo(
    () => searchRows(searchState),
    [searchState.hits, searchState.hitsQuery, searchState.query],
  );

  useEffect(() => {
    if (searchOpen) {
      store.loadRecentSearches();
      store.setSearchActive(true);
    }
  }, [searchOpen]);

  const searchNotice =
    searchState.status === "unsupported" ? (
      <SearchNotice
        icon="translate"
        title="Search in Japanese"
        body="Type kanji or kana, or a reading in romaji such as neko or tokyo."
      />
    ) : searchState.status === "failed" ? (
      <SearchNotice
        icon="error"
        title="The dictionary could not be read"
        body={searchState.error ?? "The dictionary could not be opened."}
        action={{label: "Try again", onPress: store.retrySearch}}
      />
    ) : searchState.status === "searching" ? (
      <SearchNotice spin title="Searching the dictionary…" />
    ) : searchState.status === "results" ? (
      <SearchNotice
        icon="search"
        title={`No matches for ${searchState.query}`}
        body="Check the spelling, or try a shorter word. A dictionary form matches best — 食べる rather than 食べた."
      />
    ) : null;

  const pickSearched = useCallback(
    (entryId: number) => {
      const hit = store.hitFor(entryId);
      if (hit != null) {
        store.rememberSearchedQuery(store.getSearchState().hitsQuery);
        setSearchedEntry(hit);
      }
      input.current?.blur();
      setSearchOpen(false);
      store.setSearchActive(false);
    },
    [],
  );

  // A WaniKani page opened over the entry owns the sheet for as long as it is up.
  const reference = references[references.length - 1];
  if (reference != null) {
    return (
      <View style={styles.backdrop} pointerEvents="box-none">
        <Animated.View
          entering={reducedMotion ? undefined : SlideInDown}
          exiting={reducedMotion ? undefined : SlideOutDown}
          style={[
            styles.panel,
            styles.referencePanel,
            {backgroundColor: colors.surface, maxHeight: "90%"},
          ]}
        >
          {reference.kind === "kanji" ? (
            <SearchKanjiScreen
              character={reference.character}
              onBack={closeReference}
              onOpenRadical={(id) => setReferences((s) => [...s, {kind: "radical", id}])}
              onOpenSettingsPage={() => bridge.openSettingsPage("wanikani")}
            />
          ) : (
            <SearchRadicalScreen
              id={reference.id}
              onBack={closeReference}
              onOpenRadical={(id) => setReferences((s) => [...s, {kind: "radical", id}])}
              onOpenSettingsPage={() => bridge.openSettingsPage("wanikani")}
            />
          )}
        </Animated.View>
      </View>
    );
  }

  // Search is the same field, history, rows and notices the Home tab uses.
  if (searchOpen) {
    return (
      <View style={[styles.backdrop, styles.searchCover, {backgroundColor: colors.surface}]}>
        <View style={[styles.toolbar, {borderBottomColor: colors.outlineVariant}]}>
          <IconButton name="arrow_back" accessibilityLabel="Back to lookup" onPress={() => setSearchOpen(false)} />
          <Text style={[typeScale.titleLarge, styles.toolbarTitle, {color: colors.onSurface}]}>
            Search dictionary
          </Text>
          <IconButton name="close" accessibilityLabel="Close lookup" onPress={() => bridge.close()} />
        </View>
        <View style={styles.fieldWrap}>
          <SearchField
            inputRef={input}
            query={searchState.query}
            queryLimited={searchState.queryLimited}
            onChangeQuery={store.onQueryChange}
            onFocus={() => bridge.setTextEntryActive(true)}
            onSearch={() => {
              input.current?.blur();
              store.searchNow();
            }}
          />
        </View>
        <FlatList<SearchRow>
          data={searchRowsData}
          keyExtractor={(row) => row.key}
          renderItem={({item}) => <SearchRowItem row={item} onOpen={pickSearched} />}
          ItemSeparatorComponent={SearchRowSeparator}
          contentContainerStyle={[SEARCH_LIST_CONTENT, {paddingBottom: spacing.xxl + insets.bottom}]}
          ListHeaderComponent={searchState.query.length === 0 ? <SearchIdlePrompt /> : null}
          ListEmptyComponent={
            searchState.query.length > 0 && searchNotice != null ? searchNotice : null
          }
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          showsVerticalScrollIndicator={false}
        />
      </View>
    );
  }

  return (
    <View style={styles.backdrop} pointerEvents="box-none">
      <Animated.View
        entering={reducedMotion ? undefined : SlideInDown}
        exiting={reducedMotion ? undefined : SlideOutDown}
        style={[styles.panel, {backgroundColor: colors.surface, maxHeight: "70%"}]}
      >
        {displayedEntry == null ? (
          <EmptyState
            kind={state.emptyKind}
            source={state.source}
            onSearch={() => setSearchOpen(true)}
          />
        ) : (
          <EntryDetail
            entry={displayedEntry}
            words={state.words}
            selected={state.selected}
            searched={searchedEntry != null}
            source={state.source}
            anki={anki}
            wanikaniConfigured={wanikaniConfigured}
            onSelectWord={(index) => {
              setSearchedEntry(null);
              setState((s) => ({...s, selected: index}));
            }}
            onOpenKanji={(character) => setReferences((s) => [...s, {kind: "kanji", character}])}
            onSearch={() => setSearchOpen(true)}
            onSelectAgain={() => bridge.selectAgain()}
            onClose={() => bridge.close()}
          />
        )}
      </Animated.View>
    </View>
  );
}

/** The empty sheet: the mark for the fact, its caption, and nothing else — the toolbar keeps the
 * search and reselect actions, so no standalone button competes with the illustration. */
function EmptyState({
  kind,
  source,
  onSearch,
}: {
  kind: "noText" | "noMatches" | null;
  source: string;
  onSearch: () => void;
}) {
  const {colors} = useTheme();
  const noText = kind !== "noMatches";
  return (
    <View style={styles.emptyBody}>
      <View style={styles.toolbarInner}>
        <IconButton name="search" accessibilityLabel="Search dictionary" onPress={onSearch} />
        <View style={styles.spacer} />
        <IconButton name="refresh" accessibilityLabel="Select again" onPress={() => bridge.selectAgain()} />
        <IconButton name="close" accessibilityLabel="Close lookup" onPress={() => bridge.close()} />
      </View>
      <View style={styles.emptyGroup}>
        {noText ? <NoTextArt size={140} /> : <NoMatchesArt size={140} />}
        <Text style={[typeScale.bodyMedium, styles.emptyMessage, {color: colors.onSurfaceVariant}]}>
          {noText ? "No text found" : "No dictionary matches"}
        </Text>
        {!noText && source.length > 0 && (
          <Text style={[typeScale.bodySmall, styles.emptySource, {color: colors.onSurfaceVariant}]}>
            {source}
          </Text>
        )}
      </View>
    </View>
  );
}

/** The entry sheet: word tabs (when the selection resolved several), the shared entry sections, and
 * the Anki footer — the same components the Home word detail renders. */
function EntryDetail({
  entry,
  words,
  selected,
  searched,
  source,
  anki,
  wanikaniConfigured,
  onSelectWord,
  onOpenKanji,
  onSearch,
  onSelectAgain,
  onClose,
}: {
  entry: SearchResult;
  words: bridge.LookupSheetWord[];
  selected: number;
  searched: boolean;
  source: string;
  anki: ReturnType<typeof useAnkiExport>;
  wanikaniConfigured: boolean;
  onSelectWord: (index: number) => void;
  onOpenKanji: (character: string) => void;
  onSearch: () => void;
  onSelectAgain: () => void;
  onClose: () => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const exportState = anki.exportFor(entry.id);
  return (
    <View style={styles.flex}>
      <View style={[styles.toolbarInner, {borderBottomColor: colors.outlineVariant}]}>
        <IconButton name="search" accessibilityLabel="Search dictionary" onPress={onSearch} />
        <View style={styles.spacer} />
        <IconButton name="refresh" accessibilityLabel="Select again" onPress={onSelectAgain} />
        <IconButton name="close" accessibilityLabel="Close lookup" onPress={onClose} />
      </View>
      {!searched && words.length > 1 && (
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.tabs}
        >
          {words.map((word, index) => (
            <Text
              key={`${word.surface}-${index}`}
              onPress={() => onSelectWord(index)}
              style={[
                typeScale.titleMedium,
                styles.tab,
                {
                  backgroundColor: index === selected ? colors.primaryContainer : colors.surfaceVariant,
                  color: index === selected ? colors.onPrimaryContainer : colors.onSurface,
                },
              ]}
            >
              {word.entries[0]?.primary ?? word.surface}
            </Text>
          ))}
        </ScrollView>
      )}
      {searched && source.length > 0 && (
        <Text style={[typeScale.bodySmall, styles.searchedFrom, {color: colors.onSurfaceVariant}]}>
          Searched: {source}
        </Text>
      )}
      <ScrollView
        style={styles.flex}
        contentContainerStyle={styles.entryContent}
        showsVerticalScrollIndicator={false}
      >
        <HeadwordSection hit={entry} />
        <MeaningSection glosses={entry.glosses} partsOfSpeech={entry.pos} />
        <OtherSpellingsRow primary={entry.primary} spellings={entry.kanji} />
        {wanikaniConfigured && (
          <WordKanjiSection
            characters={distinctIdeographs(entry.primary)}
            onOpenKanji={onOpenKanji}
          />
        )}
      </ScrollView>
      <AddToAnkiBar entry={entry} anki={anki} exportState={exportState} />
    </View>
  );
}

/** Wraps the root in the providers the Home tab already assumes. */
export function LookupSheetApp() {
  return (
    <GestureHandlerRootView style={styles.flex}>
      <SafeAreaProvider>
        <ThemeProvider>
          <LookupSheetRoot />
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  flex: {flex: 1},
  backdrop: {flex: 1, justifyContent: "flex-end"},
  searchCover: {justifyContent: "flex-start"},
  panel: {
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    alignSelf: "center",
    borderTopLeftRadius: shape.medium,
    borderTopRightRadius: shape.medium,
    overflow: "hidden",
  },
  referencePanel: {maxWidth: "100%"},
  toolbar: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: spacing.s,
    minHeight: 52,
  },
  toolbarInner: {
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: spacing.s,
    minHeight: 52,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  toolbarTitle: {flex: 1, marginHorizontal: spacing.s},
  spacer: {flex: 1},
  fieldWrap: {paddingTop: spacing.s},
  tabs: {paddingHorizontal: spacing.l, paddingVertical: spacing.m, gap: spacing.s},
  tab: {paddingHorizontal: spacing.l, paddingVertical: spacing.s, borderRadius: shape.small, overflow: "hidden"},
  entryContent: {paddingHorizontal: spacing.contentInset, paddingTop: spacing.xl, paddingBottom: spacing.xxl},
  searchedFrom: {paddingHorizontal: spacing.contentInset, paddingTop: spacing.m},
  emptyBody: {paddingBottom: spacing.xxl},
  emptyGroup: {alignItems: "center", paddingHorizontal: spacing.l, paddingTop: spacing.xxl},
  emptyMessage: {marginTop: spacing.l, textAlign: "center"},
  emptySource: {marginTop: spacing.xs, textAlign: "center"},
});
