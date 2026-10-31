/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React, {useCallback, useEffect, useMemo, useRef} from "react";
import {
  BackHandler,
  FlatList,
  KeyboardAvoidingView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useWindowDimensions,
} from "react-native";
import {SafeAreaView, useSafeAreaInsets} from "react-native-safe-area-context";
import {useIsFocused} from "@react-navigation/native";
import type {CompositeScreenProps} from "@react-navigation/native";
import type {MaterialTopTabScreenProps} from "@react-navigation/material-top-tabs";
import type {NativeStackScreenProps} from "@react-navigation/native-stack";
import Animated, {
  FadeInDown,
  FadeInRight,
  FadeInUp,
  FadeOutRight,
  FadeOutUp,
  LinearTransition,
  useReducedMotion,
} from "react-native-reanimated";
import {Icon} from "./components";
import {useTheme} from "./theme";
import {BOTTOM_BAR_HEIGHT, CONTENT_COLUMN, contentInsetFor, MAX_CONTENT_WIDTH, PAGE_EASING_REANIMATED, spacing, typeScale} from "./tokens";
import {HomeDashboard} from "./home/HomeDashboard";
import {
  SEARCH_LIST_CONTENT,
  SearchIdlePrompt,
  SearchField,
  SearchRowItem,
  SearchRowSeparator,
  searchRows,
  type SearchRow,
} from "./search/searchParts";
import {PressableScale} from "./PressableScale";
import * as store from "./search/searchStore";
import {SearchNotice} from "./search/SearchScreen";
import {type HomeStackParamList, type RootTabParamList} from "./navigation";
import {useHomeDashboard} from "./home/useHomeDashboard";
import {reviewStore} from "./review/review-store";

type HomeScreenProps = Partial<
  CompositeScreenProps<
    NativeStackScreenProps<HomeStackParamList, "HomeTab">,
    MaterialTopTabScreenProps<RootTabParamList>
  >
> & {
  onOpenWord?: (entryId: number) => void;
  /** Opens the review flow on AnkiDroid — its deck list, or the state that stops it. */
  onOpenAnki?: () => void;
  /** Opens the review flow on WaniKani — its connection state, or its queue. */
  onOpenWaniKani?: () => void;
};

export function HomeScreen({
  navigation,
  onOpenWord,
  onOpenAnki,
  onOpenWaniKani,
}: HomeScreenProps) {
  const {colors} = useTheme();
  const {width} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const contentInset = contentInsetFor(width);
  const reducedMotion = useReducedMotion();
  const focused = useIsFocused();
  const home = useHomeDashboard();

  const searchState = store.useSearchState();
  const rows = useMemo(
    () => searchRows(searchState),
    [searchState.hits, searchState.hitsQuery, searchState.query],
  );
  const searching = store.normalizeQuery(searchState.query).length > 0;
  const searchMode = searchState.searchActive || searching;
  const searchInput = useRef<TextInput>(null);
  const list = useRef<FlatList<SearchRow>>(null);
  const restored = useRef(false);
  const searchTransitioned = useRef(false);
  const motionDuration = reducedMotion ? 0 : SEARCH_TRANSITION_MS;
  const layoutTransition = LinearTransition
    .duration(motionDuration)
    .easing(PAGE_EASING_REANIMATED);

  /** Restores the shared search list's last position after a Home tab remount. */
  const restoreScroll = () => {
    if (!searching || restored.current) {
      return;
    }
    restored.current = true;
    if (store.searchScroll.offset > 0) {
      list.current?.scrollToOffset({offset: store.searchScroll.offset, animated: false});
    }
  };

  const enterSearch = useCallback(() => {
    // A lookup overlay can add a recent search while Home stays mounted underneath it.
    store.loadRecentSearches();
    if (!searchMode) {
      store.searchScroll.offset = 0;
      list.current?.scrollToOffset({offset: 0, animated: false});
    }
    searchTransitioned.current = true;
    store.setSearchActive(true);
  }, [searchMode]);

  const exitSearch = useCallback(() => {
    searchInput.current?.blur();
    store.onQueryChange("");
    store.searchScroll.offset = 0;
    restored.current = true;
    store.setSearchActive(false);
    list.current?.scrollToOffset({offset: 0, animated: false});
  }, []);

  useEffect(() => {
    if (searchMode) {
      searchTransitioned.current = true;
    }
  }, [searchMode]);

  // The words the empty prompt offers are the ones kept on this device; read once, on the screen
  // that shows them.
  useEffect(() => {
    store.loadRecentSearches();
  }, []);

  useEffect(() => {
    if (!focused || !searchMode) {
      return;
    }
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      exitSearch();
      return true;
    });
    return () => subscription.remove();
  }, [exitSearch, focused, searchMode]);

  const openRow = useCallback(
    (entryId: number) => {
      searchInput.current?.blur();
      if (onOpenWord != null) {
        onOpenWord(entryId);
        return;
      }
      navigation?.navigate("Word", {entryId});
    },
    [navigation, onOpenWord],
  );

  /**
   * Opening a result also remembers the query that found it: a word the reader searched with, even
   * if the search was only the debounce's and never went through the keyboard's search action.
   */
  const openSearchRow = useCallback(
    (entryId: number) => {
      store.rememberSearchedQuery(store.getSearchState().hitsQuery);
      openRow(entryId);
    },
    [openRow],
  );

  /**
   * The review flow's two service screens. Opening one means asking the review store for that
   * service's step *and* pushing the page, so a tap on Home's Anki or WaniKani card lands on that
   * service rather than on a page that has to be tapped again. The shell owns both when it passes the
   * callback; without one — a mount that has no tab above it — this screen does the same two things
   * itself, exactly as it does for a word or a settings page.
   */
  const openAnki = useCallback(() => {
    if (onOpenAnki != null) {
      onOpenAnki();
      return;
    }
    reviewStore.openAnki();
    navigation?.push("Review");
  }, [navigation, onOpenAnki]);

  const openWaniKani = useCallback(() => {
    if (onOpenWaniKani != null) {
      onOpenWaniKani();
      return;
    }
    reviewStore.openWaniKani();
    navigation?.push("Review");
  }, [navigation, onOpenWaniKani]);

  const searchStateNotice =
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

  const listHeader = searchMode
    ? searching
      ? null
      : (
          <Animated.View
            key="search-prompt"
            entering={FadeInDown.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
            exiting={FadeOutUp.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
          >
            <SearchIdlePrompt />
          </Animated.View>
        )
    : (
        <Animated.View
          key="home-dashboard"
          entering={searchTransitioned.current
            ? FadeInDown.duration(motionDuration).easing(PAGE_EASING_REANIMATED)
            : undefined}
          exiting={FadeOutUp.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
        >
          <HomeDashboard
            home={home}
            onOpenAnki={openAnki}
            onOpenWaniKani={openWaniKani}
            onOpenWord={openRow}
          />
        </Animated.View>
      );

  return (
    <SafeAreaView style={[styles.root, {backgroundColor: colors.background}]} edges={["top", "left", "right"]}>
      <KeyboardAvoidingView behavior="padding" style={styles.root}>
        <View style={styles.topHeader}>
          <Animated.View style={[styles.headerColumn, {paddingHorizontal: contentInset}]} layout={layoutTransition}>
            {!searchMode && (
              <Animated.Text
                entering={searchTransitioned.current
                  ? FadeInUp.duration(motionDuration).easing(PAGE_EASING_REANIMATED)
                  : undefined}
                exiting={FadeOutUp.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
                style={[typeScale.brand, styles.brand, {color: colors.onSurface}]}
              >
                Gaten
              </Animated.Text>
            )}
            <Animated.View style={styles.searchRow} layout={layoutTransition}>
              <Animated.View style={styles.searchField} layout={layoutTransition}>
                <SearchField
                  compact
                  inputRef={searchInput}
                  query={searchState.query}
                  queryLimited={searchState.queryLimited}
                  onChangeQuery={store.onQueryChange}
                  onFocus={enterSearch}
                  onSearch={() => {
                    searchInput.current?.blur();
                    store.searchNow();
                  }}
                />
              </Animated.View>
              {searchMode && (
                <Animated.View
                  entering={FadeInRight.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
                  exiting={FadeOutRight.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
                >
                  <PressableScale
                    accessibilityRole="button"
                    accessibilityLabel={searchState.query.length > 0 ? "Clear search" : "Cancel search"}
                    onPress={() => {
                      if (store.getSearchState().query.length > 0) {
                        store.onQueryChange("");
                      } else {
                        exitSearch();
                      }
                    }}
                    style={[styles.cancelSearch, {borderColor: colors.outlineVariant}]}
                  >
                    <Icon name="close" size={20} color={colors.accent} />
                  </PressableScale>
                </Animated.View>
              )}
            </Animated.View>
          </Animated.View>
        </View>
        <FlatList<SearchRow>
          ref={list}
          showsVerticalScrollIndicator={false}
          showsHorizontalScrollIndicator={false}
          data={rows}
          keyExtractor={(row) => row.key}
          renderItem={({item}) => (
            <Animated.View
              entering={FadeInDown.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
              exiting={FadeOutUp.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
            >
              <SearchRowItem row={item} onOpen={openSearchRow} />
            </Animated.View>
          )}
          ItemSeparatorComponent={SearchRowSeparator}
          contentContainerStyle={[
            SEARCH_LIST_CONTENT,
            styles.listContent,
            {
              paddingHorizontal: contentInset,
              // Keep content visible beside the floating bar, with room to scroll the last row above it.
              paddingBottom: searchMode
                ? SEARCH_LIST_CONTENT.paddingBottom
                : BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
            },
          ]}
          ListHeaderComponent={listHeader}
          ListEmptyComponent={
            searchMode && searching && searchStateNotice != null ? (
              <Animated.View
                entering={FadeInDown.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
                exiting={FadeOutUp.duration(motionDuration).easing(PAGE_EASING_REANIMATED)}
              >
                {searchStateNotice}
              </Animated.View>
            ) : null
          }
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          onContentSizeChange={restoreScroll}
          onScroll={(event) => {
            if (searchMode) {
              store.searchScroll.offset = event.nativeEvent.contentOffset.y;
            }
          }}
          scrollEventThrottle={16}
        />
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  topHeader: {width: "100%"},
  headerColumn: {...CONTENT_COLUMN, paddingTop: spacing.m, paddingBottom: spacing.m},
  searchRow: {
    width: "100%",
    flexDirection: "row",
    alignItems: "center",
    gap: spacing.s,
  },
  searchField: {flex: 1},
  cancelSearch: {
    width: 48,
    height: 48,
    borderRadius: 24,
    borderWidth: 2,
    alignItems: "center",
    justifyContent: "center",
  },
  brand: {
    marginTop: 11,
    marginBottom: 20,
  },
  listContent: {
    paddingHorizontal: spacing.l,
    paddingTop: 0,
    maxWidth: MAX_CONTENT_WIDTH,
  },
});

/** Search mode uses the same short, emphasized transition in both directions. */
const SEARCH_TRANSITION_MS = 220;
