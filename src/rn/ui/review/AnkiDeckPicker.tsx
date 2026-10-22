/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * AnkiDroid's decks, one row each, for the reader to choose from.
 *
 * Choosing *is* starting: a tap on a deck opens its review immediately, so there is no radio to set
 * and no second button to press — the deck's own counts are what the reader chooses on. The counts
 * are AnkiDroid's, read for the collection as it is now; a deck with nothing due still lists, because
 * the reader may be looking for it. Clear decks are informational and do not open empty sessions.
 *
 * The floating bar stays on this screen: it is still the tab's own ground, and the reader is choosing
 * rather than studying. It goes away with the session the tap opens.
 */
import React from "react";
import {ActivityIndicator, ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {BOTTOM_BAR_HEIGHT, MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import type {AnkiDeckResult} from "../../native/GatenAnki";
import {BusyRow, DeckRow, ScreenTitle, SectionCaption, SubHeader, SuccessCheck} from "./review-ui";

/** AnkiDroid's own counts for one deck, in the design's one-line form. */
function countsLine(deck: AnkiDeckResult): string {
  return `${deck.newCount} new · ${deck.learnCount} learning · ${deck.reviewCount} reviews`;
}

/** What is due in one deck right now: the three counts AnkiDroid reports, added up. */
function dueOf(deck: AnkiDeckResult): number {
  return deck.newCount + deck.learnCount + deck.reviewCount;
}

export function AnkiDeckPicker({
  decks,
  busy,
  filteredEmpty = false,
  completedDeckIds = [],
  onBack,
  onStart,
}: {
  decks: AnkiDeckResult[];
  busy: boolean;
  filteredEmpty?: boolean;
  completedDeckIds?: number[];
  onBack: () => void;
  onStart: (deck: AnkiDeckResult) => void;
}) {
  const {colors} = useTheme();
  const dark = Number.parseInt(colors.background.slice(1, 3), 16) < 128;
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  // No cached/offline state is labelled here: the AnkiDroid read is a local provider read, and the
  // store funnels any failure to the full-screen blocked/failed step.
  // Parent decks count their children, so only the top level is added up: the same rule Home's
  // dashboard reads the collection's own due count by.
  const ready = decks
    .filter((deck) => !decks.some((parent) => deck.name.startsWith(`${parent.name}::`)))
    .reduce((total, deck) => total + dueOf(deck), 0);
  const allClear = !busy && decks.length > 0 && ready === 0;

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title="Anki" onBack={onBack} />
      </View>
      <ScrollView
        showsVerticalScrollIndicator={false}
        showsHorizontalScrollIndicator={false}
        style={styles.screen}
        contentContainerStyle={[
          styles.body,
          {
            paddingHorizontal: contentInset,
            paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
          },
        ]}
      >
        <ScreenTitle style={styles.title}>Choose a deck</ScreenTitle>
        {busy && decks.length === 0 ? (
          <View style={[styles.loadingStatus, styles.summary]}>
            <ActivityIndicator size="small" color={colors.accent} />
            <Text style={[typeScale.description, {color: colors.onSurfaceVariant}]}>Syncing with Anki…</Text>
          </View>
        ) : allClear ? (
          <Text style={[typeScale.description, styles.summary, {color: colors.onSurfaceVariant}]}>
            No cards are due right now
          </Text>
        ) : (
          <Text style={[typeScale.description, styles.summary, {color: colors.onSurfaceVariant}]}>
            <Text style={ready > 0 ? {color: colors.accent} : undefined}>{ready}</Text>
            {ready === 1 ? " card ready to study" : " cards ready to study"}
          </Text>
        )}
        {allClear && (
          <View style={[styles.notice, {backgroundColor: dark ? "#18291F" : "#ECF8F0", borderColor: dark ? "#294734" : "#D8ECDC"}]}>
            <SuccessCheck />
            <View style={styles.noticeCopy}>
              <Text style={[styles.noticeTitle, {color: colors.onSurface}]}>You’re all caught up</Text>
              <Text style={[styles.noticeBody, {color: colors.onSurfaceVariant}]}>Every available deck is clear for now.</Text>
            </View>
          </View>
        )}
        <SectionCaption style={styles.caption}>Available decks</SectionCaption>
        {busy && decks.length === 0 ? (
          <View style={styles.skeletonList}>
            {[0, 1, 2, 3, 4].map((key) => (
              <View key={key} style={[styles.skeletonRow, {borderBottomColor: colors.outlineVariant}]}>
                <View style={[styles.skeletonTile, {backgroundColor: colors.surfaceVariant}]} />
                <View style={styles.skeletonCopy}>
                  <View style={[styles.skeletonName, {backgroundColor: colors.surfaceVariant}]} />
                  <View style={[styles.skeletonMeta, {backgroundColor: colors.surfaceVariant}]} />
                </View>
                <View style={[styles.skeletonDue, {backgroundColor: colors.surfaceVariant}]} />
              </View>
            ))}
          </View>
        ) : decks.length === 0 ? (
          <Text style={[typeScale.bodyMedium, styles.empty, {color: colors.onSurfaceVariant}]}>
            {filteredEmpty
              ? "No study decks selected. Choose decks in Settings → Anki cards."
              : "No decks found. Create a deck in AnkiDroid to get started."}
          </Text>
        ) : (
          decks.map((deck) => (
            <DeckRow
              key={deck.id}
              deckId={deck.id}
              name={deck.name}
              counts={countsLine(deck)}
              due={dueOf(deck)}
              completed={ready === 0 || completedDeckIds.includes(Number(deck.id))}
              completedToday={completedDeckIds.includes(Number(deck.id))}
              onPress={() => onStart(deck)}
            />
          ))
        )}
        {busy && decks.length > 0 && (
          <View style={styles.busy}>
            <BusyRow message="Reading AnkiDroid…" />
          </View>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  title: {marginTop: 18},
  summary: {marginTop: 10, marginBottom: 24},
  caption: {marginTop: 22},
  empty: {marginTop: spacing.s},
  notice: {flexDirection: "row", alignItems: "center", gap: 11, padding: 12, borderWidth: 1, borderRadius: 13},
  noticeCopy: {flex: 1, gap: 2},
  noticeTitle: {fontSize: 13, lineHeight: 17, fontWeight: "700"},
  noticeBody: {fontSize: 11, lineHeight: 15},
  skeletonList: {marginTop: spacing.s},
  // The skeleton is the shared row's own geometry: a hairline divider, no card, no fill.
  skeletonRow: {minHeight: 90, flexDirection: "row", alignItems: "center", gap: spacing.m, paddingVertical: spacing.m, borderBottomWidth: 1},
  loadingStatus: {flexDirection: "row", alignItems: "center", gap: spacing.s},
  skeletonTile: {width: 48, height: 48, borderRadius: 12},
  skeletonCopy: {flex: 1, gap: spacing.s},
  skeletonName: {width: "58%", height: 15, borderRadius: 5},
  skeletonMeta: {width: "82%", height: 11, borderRadius: 5},
  skeletonDue: {width: 24, height: 23, borderRadius: 5, marginEnd: spacing.s},
  busy: {marginTop: spacing.l},
});
