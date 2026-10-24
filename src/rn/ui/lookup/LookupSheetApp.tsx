/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The React Native root of the lookup sheet, mounted inside the native accessibility overlay.
 *
 * The overlay owns the window, the frozen capture, OCR and selection. Everything inside the sheet —
 * the toolbar, the dictionary search and its results, the empty states and (in later steps) the
 * entry detail and Anki footer — is this app's own RN UI, reusing the same search components and
 * store the Home tab uses rather than a second copy. The root registers an action listener with the
 * native module so a session close, a `select again` or an `open search` reaches the overlay, and it
 * tells the overlay when its field is editing so a window focus loss can be attributed to the IME.
 */
import React, {useCallback, useEffect, useMemo, useRef} from "react";
import {BackHandler, FlatList, StyleSheet, Text, TextInput, View} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import {SafeAreaProvider, useSafeAreaInsets} from "react-native-safe-area-context";
import {IconButton} from "../components";
import {useTheme, ThemeProvider} from "../theme";
import {spacing, typeScale} from "../tokens";
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
import * as store from "../search/searchStore";
import * as bridge from "../../native/GatenLookupSheet";

/** The root the overlay mounts; the sheet's own theme comes from the bridge, as the main app's does. */
function LookupSheetRoot() {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const state = store.useSearchState();
  const rows = useMemo(
    () => searchRows(state),
    [state.hits, state.hitsQuery, state.query],
  );
  const searching = store.normalizeQuery(state.query).length > 0;
  const input = useRef<TextInput>(null);

  // The sheet's history is Home's history; read it on mount, on the surface that shows it.
  useEffect(() => {
    store.loadRecentSearches();
    store.setSearchActive(true);
    bridge.ready();
  }, []);

  // While the field is editing, the overlay must not read the IME handoff as the user leaving.
  useEffect(() => {
    return () => bridge.setTextEntryActive(false);
  }, []);

  // System back leaves search first, then closes the lookup — the same order the overlay used.
  useEffect(() => {
    const subscription = BackHandler.addEventListener("hardwareBackPress", () => {
      bridge.close();
      return true;
    });
    return () => subscription.remove();
  }, []);

  const notice =
    state.status === "unsupported" ? (
      <SearchNotice
        icon="translate"
        title="Search in Japanese"
        body="Type kanji or kana, or a reading in romaji such as neko or tokyo."
      />
    ) : state.status === "failed" ? (
      <SearchNotice
        icon="error"
        title="The dictionary could not be read"
        body={state.error ?? "The dictionary could not be opened."}
        action={{label: "Try again", onPress: store.retrySearch}}
      />
    ) : state.status === "searching" ? (
      <SearchNotice spin title="Searching the dictionary…" />
    ) : state.status === "results" ? (
      <SearchNotice
        icon="search"
        title={`No matches for ${state.query}`}
        body="Check the spelling, or try a shorter word. A dictionary form matches best — 食べる rather than 食べた."
      />
    ) : null;

  const openRow = useCallback((entryId: number) => {
    // The entry opens as the sheet's own detail step; the overlay session stays open.
    bridge.openSearch();
    store.hitFor(entryId);
  }, []);

  return (
    <View style={[styles.root, {backgroundColor: colors.surface}]}>
      <View style={[styles.toolbar, {borderBottomColor: colors.outlineVariant}]}>
        <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>
          Lookup
        </Text>
        <View style={styles.toolbarActions}>
          <IconButton
            name="refresh"
            accessibilityLabel="Select again"
            onPress={() => bridge.selectAgain()}
          />
          <IconButton
            name="close"
            accessibilityLabel="Close lookup"
            onPress={() => bridge.close()}
          />
        </View>
      </View>
      <View style={styles.fieldWrap}>
        <SearchField
          inputRef={input}
          query={state.query}
          queryLimited={state.queryLimited}
          onChangeQuery={store.onQueryChange}
          onFocus={() => bridge.setTextEntryActive(true)}
          onSearch={() => {
            input.current?.blur();
            store.searchNow();
          }}
        />
      </View>
      <FlatList<SearchRow>
        data={rows}
        keyExtractor={(row) => row.key}
        renderItem={({item}) => (
          <SearchRowItem row={item} onOpen={openRow} />
        )}
        ItemSeparatorComponent={SearchRowSeparator}
        contentContainerStyle={[
          SEARCH_LIST_CONTENT,
          {paddingBottom: spacing.xxl + insets.bottom},
        ]}
        ListHeaderComponent={
          searching ? null : <SearchIdlePrompt />
        }
        ListEmptyComponent={
          searching && notice != null ? notice : null
        }
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        showsVerticalScrollIndicator={false}
      />
    </View>
  );
}

/** Wraps the root in the providers the Home tab already assumes. */
export function LookupSheetApp() {
  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <ThemeProvider>
          <LookupSheetRoot />
        </ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1},
  toolbar: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: spacing.l,
    paddingVertical: spacing.s,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  toolbarActions: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  fieldWrap: {paddingTop: spacing.s},
});
