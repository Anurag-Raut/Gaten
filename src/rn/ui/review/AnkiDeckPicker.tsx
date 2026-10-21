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
 * the reader may be looking for it, and the session it opens says it is caught up.
 *
 * The floating bar stays on this screen: it is still the tab's own ground, and the reader is choosing
 * rather than studying. It goes away with the session the tap opens.
 */
import React from "react";
import {ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {BOTTOM_BAR_HEIGHT, MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import type {AnkiDeckResult} from "../../native/GatenAnki";
import {BusyRow, DeckRow, ScreenSubtitle, ScreenTitle, SectionCaption, SubHeader} from "./review-ui";

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
  onBack,
  onStart,
}: {
  decks: AnkiDeckResult[];
  busy: boolean;
  onBack: () => void;
  onStart: (deck: AnkiDeckResult) => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  // Parent decks count their children, so only the top level is added up: the same rule Home's
  // dashboard reads the collection's own due count by.
  const ready = decks
    .filter((deck) => !deck.name.includes("::"))
    .reduce((total, deck) => total + dueOf(deck), 0);

  return (
    <View style={[styles.screen, {backgroundColor: colors.background, paddingTop: insets.top}]}>
      <View style={{paddingHorizontal: contentInset}}>
        <SubHeader title="Anki" right="Decks" onBack={onBack} />
      </View>
      <ScrollView
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
        <ScreenSubtitle style={styles.summary}>
          {ready === 1 ? "1 card ready to study" : `${ready} cards ready to study`}
        </ScreenSubtitle>
        <SectionCaption style={styles.caption}>Available decks</SectionCaption>
        {decks.length === 0 ? (
          <Text style={[typeScale.bodyMedium, styles.empty, {color: colors.onSurfaceVariant}]}>
            AnkiDroid has no decks yet. Create one there and it will appear here.
          </Text>
        ) : (
          decks.map((deck) => (
            <DeckRow
              key={deck.id}
              name={deck.name}
              counts={countsLine(deck)}
              due={dueOf(deck)}
              onPress={() => onStart(deck)}
            />
          ))
        )}
        {busy && (
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
  caption: {marginTop: 16},
  empty: {marginTop: spacing.s},
  busy: {marginTop: spacing.l},
});
