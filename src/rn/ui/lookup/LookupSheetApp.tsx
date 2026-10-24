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
import {
  BackHandler,
  Dimensions,
  FlatList,
  KeyboardAvoidingView,
  type GestureResponderEvent,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import Animated, {
  Easing,
  FadeIn,
  FadeOut,
  SlideInDown,
  SlideOutDown,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withTiming,
} from "react-native-reanimated";
import {SafeAreaProvider, useSafeAreaInsets} from "react-native-safe-area-context";
import {IconButton} from "../components";
import {PressableScale} from "../PressableScale";
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

function LookupSheetRoot({sessionId}: {sessionId?: string}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const reducedMotion = useReducedMotion();
  const anki = useAnkiExport();

  const [state, setState] = useState<bridge.LookupSheetState>({
    sessionId: sessionId ?? "",
    mode: "hidden",
    source: "",
    words: [],
    selected: 0,
    query: "",
    emptyKind: null,
  });
  const [searchOpen, setSearchOpen] = useState(false);
  const [searchedEntry, setSearchedEntry] = useState<SearchResult | null>(null);
  const [searchedQuery, setSearchedQuery] = useState<string | null>(null);
  const [references, setReferences] = useState<ReferenceTarget[]>([]);
  const [wanikaniConfigured, setWanikaniConfigured] = useState(false);
  const input = useRef<TextInput>(null);

  // The two snap heights are taken from the physical screen once, not from the live window: the
  // overlay is a service window, so the IME must not be able to resize the shell and make the
  // field jump a second time. The landing snap is the same for the entry and every WaniKani page
  // opened over it, so navigating never changes the sheet's height; only search expands it.
  const screenHeight = useRef(Dimensions.get("screen").height).current;
  const collapsedHeight = Math.round(screenHeight * 0.7);
  const expandedHeight = Math.round(screenHeight * 0.92);
  // One value drives the whole search transition: the shell height, the landing content's fade and
  // the search content's reveal. Reassigning a timing interrupts the running one, so a rapid
  // open/back reverses from wherever it is.
  const searchProgress = useSharedValue(0);

  // The move itself, and its one authority: the shell animates, the field focuses on open and
  // gives up native text entry on close, and the accessibility tree follows the visible layer.
  useEffect(() => {
    searchProgress.value = withTiming(searchOpen ? 1 : 0, {
      duration: reducedMotion ? 0 : 240,
      easing: Easing.bezier(0.2, 0, 0, 1),
    });
    if (searchOpen) {
      input.current?.focus();
    } else {
      input.current?.blur();
      bridge.setTextEntryActive(false);
    }
  }, [searchOpen, reducedMotion, searchProgress]);

  // The shell's own height: the landing snap at rest and the expanded snap while searching.
  const shellStyle = useAnimatedStyle(() => ({
    height: collapsedHeight + (expandedHeight - collapsedHeight) * searchProgress.value,
  }));
  // The landing content is pushed out of the way and the search content arrives below the field.
  const landingStyle = useAnimatedStyle(() => ({
    opacity: 1 - searchProgress.value,
    transform: [{translateY: -16 * searchProgress.value}],
  }));
  const searchLayerStyle = useAnimatedStyle(() => ({
    opacity: searchProgress.value,
    transform: [{translateY: 20 * (1 - searchProgress.value)}],
  }));

  // One state from native rebuilds the whole sheet. The listener is registered first, then the
  // mount reports itself ready: native holds the latest state until that handshake and flushes it
  // then, so a state built while the surface was still loading is never emitted into a runtime
  // with no listener. States from another lookup session are dropped, so a stale surface can never
  // apply a newer lookup's state.
  useEffect(() => {
    const belongs = (parsed: bridge.LookupSheetState) =>
      sessionId == null || parsed.sessionId === sessionId;
    const subscription = bridge.addLookupSheetStateListener((raw) => {
      const parsed = bridge.parseState(raw);
      if (!belongs(parsed)) return;
      setState(parsed);
      setSearchOpen(false);
      setSearchedEntry(null);
      setSearchedQuery(null);
      setReferences([]);
    });
    bridge.ready();
    return () => {
      subscription.remove();
    };
  }, [sessionId]);

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
        const committedQuery = store.getSearchState().hitsQuery;
        store.rememberSearchedQuery(committedQuery);
        setSearchedEntry(hit);
        setSearchedQuery(committedQuery);
      }
      input.current?.blur();
      setSearchOpen(false);
      store.setSearchActive(false);
    },
    [],
  );

  // A WaniKani page opened over the entry is a nested layer of the same shell: the shell's height
  // and the landing content's own scroll state are untouched, and the page crossfades in.
  const reference = references[references.length - 1];
  const referenceKey =
    reference == null
      ? "entry"
      : reference.kind === "kanji"
        ? `kanji:${reference.character}`
        : `radical:${reference.id}`;

  // The dimmed area above the sheet is the way out. It only dismisses when the tap really landed
  // above the panel's current top — a tap on the panel's own empty space is inside the sheet and
  // must not close it — and a search that is up collapses back to the landing before anything
  // else. Covering the whole screen also keeps the touch from falling through to the app below.
  const onBackdropPress = useCallback(
    (event: GestureResponderEvent) => {
      const top = screenHeight - (searchOpen ? expandedHeight : collapsedHeight);
      if (event.nativeEvent.pageY >= top) return;
      if (searchOpen) setSearchOpen(false);
      else bridge.close();
    },
    [searchOpen, screenHeight, expandedHeight, collapsedHeight],
  );

  // A hidden mode is the surface waiting for its first result, or the panel's own exit: rendering
  // nothing here lets Reanimated play the exit instead of the surface blinking out.
  if (state.mode === "hidden") return null;

  return (
    <View style={styles.backdrop} pointerEvents="box-none">
      <Pressable
        style={StyleSheet.absoluteFill}
        accessible={false}
        importantForAccessibility="no"
        onPress={onBackdropPress}
      />
      <Animated.View
        entering={reducedMotion ? undefined : SlideInDown}
        exiting={reducedMotion ? undefined : SlideOutDown}
        style={[styles.panel, {backgroundColor: colors.surface}, shellStyle]}
      >
        <Animated.View
          style={[styles.layer, landingStyle]}
          pointerEvents={searchOpen ? "none" : "auto"}
          accessibilityElementsHidden={searchOpen}
          importantForAccessibility={searchOpen ? "no-hide-descendants" : "auto"}
        >
          {reference != null ? (
            <Animated.View
              key={referenceKey}
              entering={reducedMotion ? undefined : FadeIn.duration(170)}
              exiting={reducedMotion ? undefined : FadeOut.duration(120)}
              style={styles.flex}
            >
              {reference.kind === "kanji" ? (
                <SearchKanjiScreen
                  character={reference.character}
                  onBack={closeReference}
                  onOpenRadical={(id) => setReferences((s) => [...s, {kind: "radical", id}])}
                  showSettings={false}
                  overlay
                />
              ) : (
                <SearchRadicalScreen
                  id={reference.id}
                  onBack={closeReference}
                  onOpenRadical={(id) => setReferences((s) => [...s, {kind: "radical", id}])}
                  showSettings={false}
                  overlay
                />
              )}
            </Animated.View>
          ) : displayedEntry == null ? (
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
              searchedQuery={searchedQuery}
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

        <Animated.View
          style={[StyleSheet.absoluteFill, searchLayerStyle]}
          pointerEvents={searchOpen ? "auto" : "none"}
          accessibilityElementsHidden={!searchOpen}
          importantForAccessibility={searchOpen ? "auto" : "no-hide-descendants"}
        >
          <KeyboardAvoidingView behavior="padding" style={styles.flex}>
            <View style={[styles.toolbar, {borderBottomColor: colors.outlineVariant}]}>
              <IconButton
                name="arrow_back"
                accessibilityLabel="Back to lookup"
                onPress={() => setSearchOpen(false)}
              />
              <Text style={[typeScale.titleLarge, styles.toolbarTitle, {color: colors.onSurface}]}>
                Search dictionary
              </Text>
              <IconButton
                name="close"
                accessibilityLabel="Close lookup"
                onPress={() => bridge.close()}
              />
            </View>
            <View style={styles.fieldWrap}>
              <SearchField
                inputRef={input}
                query={searchState.query}
                queryLimited={searchState.queryLimited}
                onChangeQuery={store.onQueryChange}
                onFocus={() => bridge.setTextEntryActive(true)}
                onBlur={() => bridge.setTextEntryActive(false)}
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
              contentContainerStyle={[
                SEARCH_LIST_CONTENT,
                {paddingBottom: spacing.xxl + insets.bottom},
              ]}
              ListHeaderComponent={searchState.query.length === 0 ? <SearchIdlePrompt /> : null}
              ListEmptyComponent={
                searchState.query.length > 0 && searchNotice != null ? searchNotice : null
              }
              keyboardShouldPersistTaps="handled"
              keyboardDismissMode="on-drag"
              showsVerticalScrollIndicator={false}
            />
          </KeyboardAvoidingView>
        </Animated.View>
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
        {/* The same reselect the toolbar used to carry, as accent text under the caption: the
            circular arrow leaves the toolbar, and the action it stood for stays reachable. */}
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel="Search again"
          onPress={() => bridge.selectAgain()}
          style={styles.emptyAction}
        >
          <Text style={[typeScale.labelLarge, {color: colors.accent}]}>Search again</Text>
        </PressableScale>
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
  searchedQuery,
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
  searchedQuery: string | null;
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
    <View style={styles.entryRoot}>
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
          style={styles.tabsScroll}
          contentContainerStyle={styles.tabs}
        >
          {words.map((word, index) => (
            <Pressable
              key={`${word.surface}-${index}`}
              accessibilityRole="button"
              accessibilityState={{selected: index === selected}}
              onPress={() => onSelectWord(index)}
              style={[
                styles.tab,
                {backgroundColor: index === selected ? colors.primaryContainer : colors.surfaceVariant},
              ]}
            >
              <Text
                style={[
                  typeScale.titleMedium,
                  {color: index === selected ? colors.onPrimaryContainer : colors.onSurface},
                ]}
              >
                {word.entries[0]?.primary ?? word.surface}
              </Text>
            </Pressable>
          ))}
        </ScrollView>
      )}
      {searched && searchedQuery != null && searchedQuery.length > 0 && (
        <Text style={[typeScale.bodySmall, styles.searchedFrom, {color: colors.onSurfaceVariant}]}>
          Searched: {searchedQuery}
        </Text>
      )}
      <ScrollView
        style={styles.entryScroll}
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
export function LookupSheetApp({sessionId}: {sessionId?: string} = {}) {
  return (
    <GestureHandlerRootView style={styles.flex}>
      <SafeAreaProvider>
        <ThemeProvider>
          <LookupSheetRoot sessionId={sessionId} />
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  flex: {flex: 1},
  backdrop: {flex: 1, justifyContent: "flex-end"},
  panel: {
    width: "100%",
    maxWidth: MAX_CONTENT_WIDTH,
    alignSelf: "center",
    borderTopLeftRadius: shape.medium,
    borderTopRightRadius: shape.medium,
    overflow: "hidden",
  },
  // The shell now has a definite animated height, so its layers can fill it: the landing layer is
  // the entry and the WaniKani page opened over it, and the body scrolls inside.
  layer: {flex: 1},
  entryRoot: {flex: 1},
  entryScroll: {flex: 1},

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
  // The tab rail holds its content's own height: without this it is the one flexible row in the
  // capped, content-sized panel, so the body's shrink pulled it down to its padding and the labels
  // collapsed with it.
  tabsScroll: {flexGrow: 0, flexShrink: 0},
  tabs: {paddingHorizontal: spacing.l, paddingVertical: spacing.m, gap: spacing.s},
  // The pill is the Pressable and the label is a plain Text inside it, the same shape as the
  // shared WaniKani kanji chip: the background and its rounding belong to the container, and the
  // CJK label is never clipped by a Text box shorter than the glyph's own metrics.
  tab: {paddingHorizontal: spacing.l, paddingVertical: spacing.s, borderRadius: shape.small},
  entryContent: {paddingHorizontal: spacing.contentInset, paddingTop: spacing.xl, paddingBottom: spacing.xxl},
  searchedFrom: {paddingHorizontal: spacing.contentInset, paddingTop: spacing.m},
  emptyBody: {flex: 1, paddingBottom: spacing.xxl},
  emptyGroup: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: spacing.l,
  },
  emptyMessage: {marginTop: spacing.l, textAlign: "center"},
  emptySource: {marginTop: spacing.xs, textAlign: "center"},
  // Accent text, not a button: a comfortable hit area without a filled or outlined shape.
  emptyAction: {marginTop: spacing.m, paddingVertical: spacing.s, paddingHorizontal: spacing.m},
});
