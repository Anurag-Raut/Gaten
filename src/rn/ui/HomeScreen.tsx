/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React, {useCallback, useMemo, useRef} from "react";
import {
  FlatList,
  KeyboardAvoidingView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import {SafeAreaView} from "react-native-safe-area-context";
import {useIsFocused} from "@react-navigation/native";
import type {CompositeScreenProps} from "@react-navigation/native";
import type {MaterialTopTabScreenProps} from "@react-navigation/material-top-tabs";
import type {NativeStackScreenProps} from "@react-navigation/native-stack";
import {useTheme} from "./theme";
import {spacing, typeScale} from "./tokens";
import {HomeDashboard} from "./home/HomeDashboard";
import {
  SEARCH_LIST_CONTENT,
  SearchField,
  SearchRowItem,
  SearchRowSeparator,
  searchRows,
  type SearchRow,
} from "./search/searchParts";
import * as store from "./search/searchStore";
import {SearchNotice} from "./search/SearchScreen";
import {
  freshSettingsInstance,
  type HomeStackParamList,
  type RootTabParamList,
  type SettingsPageName,
} from "./navigation";
import {useHomeDashboard} from "./home/useHomeDashboard";

type HomeScreenProps = Partial<
  CompositeScreenProps<
    NativeStackScreenProps<HomeStackParamList, "HomeTab">,
    MaterialTopTabScreenProps<RootTabParamList>
  >
> & {
  onOpenWord?: (entryId: number) => void;
  onOpenReview?: () => void;
  onOpenSettingsPage?: (page: SettingsPageName) => void;
};

export function HomeScreen({
  navigation,
  onOpenWord,
  onOpenReview,
  onOpenSettingsPage,
}: HomeScreenProps) {
  const {colors, palette} = useTheme();
  const referenceLight = palette === "Gaten" && Number.parseInt(colors.background.slice(1, 3), 16) >= 128;
  const focused = useIsFocused();
  const home = useHomeDashboard(focused);

  const searchState = store.useSearchState();
  const rows = useMemo(
    () => searchRows(searchState),
    [searchState.hits, searchState.hitsQuery, searchState.query],
  );
  const searching = store.normalizeQuery(searchState.query).length > 0;
  const searchInput = useRef<TextInput>(null);
  const list = useRef<FlatList<SearchRow>>(null);
  const restored = useRef(false);

  /** Restores the shared search list's last position after a Home tab remount. */
  const restoreScroll = () => {
    if (restored.current) {
      return;
    }
    restored.current = true;
    if (store.searchScroll.offset > 0) {
      list.current?.scrollToOffset({offset: store.searchScroll.offset, animated: false});
    }
  };

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

  const openReview = useCallback(() => {
    if (onOpenReview != null) {
      onOpenReview();
      return;
    }
    navigation?.navigate("Review", {screen: "ReviewTab", pop: true});
  }, [navigation, onOpenReview]);

  const openSettings = useCallback(
    (page: SettingsPageName) => {
      if (onOpenSettingsPage != null) {
        onOpenSettingsPage(page);
        return;
      }
      navigation?.navigate("Settings", {
        screen: "SettingsPage",
        params: {page, instance: freshSettingsInstance()},
      });
    },
    [navigation, onOpenSettingsPage],
  );

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

  return (
    <SafeAreaView style={[styles.root, {backgroundColor: referenceLight ? "#FAFAFA" : colors.background}]} edges={["top", "left", "right"]}>
      <KeyboardAvoidingView behavior="padding" style={styles.root}>
        <View style={styles.topHeader}>
          <View style={styles.headerColumn}>
            <Text style={[typeScale.headlineSmall, styles.brand, {color: referenceLight ? "#14142B" : colors.onSurface}]}>
              Gaten
            </Text>
            <SearchField
              compact
              inputRef={searchInput}
              query={searchState.query}
              queryLimited={searchState.queryLimited}
              onChangeQuery={store.onQueryChange}
              onSearch={() => {
                searchInput.current?.blur();
                store.searchNow();
              }}
            />
          </View>
        </View>
        <FlatList<SearchRow>
          ref={list}
          data={rows}
          keyExtractor={(row) => row.key}
          renderItem={({item}) => <SearchRowItem row={item} onOpen={openRow} />}
          ItemSeparatorComponent={SearchRowSeparator}
          contentContainerStyle={[
            SEARCH_LIST_CONTENT,
            styles.listContent,
          ]}
          ListHeaderComponent={
            searching ? null : (
              <HomeDashboard
                home={home}
                onOpenReview={openReview}
                onOpenSettingsPage={openSettings}
                onOpenWord={openRow}
              />
            )
          }
          ListEmptyComponent={searching ? searchStateNotice : null}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          onContentSizeChange={restoreScroll}
          onScroll={(event) => {
            store.searchScroll.offset = event.nativeEvent.contentOffset.y;
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
  headerColumn: {
    width: "100%",
    maxWidth: 412,
    alignSelf: "center",
  },
  brand: {
    marginHorizontal: spacing.l,
    marginTop: 14,
    marginBottom: 12,
    fontFamily: "Inter",
    fontWeight: "700",
    fontSize: 28,
    lineHeight: 36,
  },
  listContent: {
    paddingHorizontal: spacing.l,
    paddingTop: 12,
    maxWidth: 412,
  },
});
