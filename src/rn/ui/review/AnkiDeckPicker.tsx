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
 * Above the rows sit the page's two figures — cards studied across all decks today, and the cards
 * ready in the selected study decks now — drawn as the paired figures the rest of the app uses. Each
 * is shown only when the read that produced it answered; anything else is a dash with the reason.
 *
 * The floating bar stays on this screen: it is still the tab's own ground, and the reader is choosing
 * rather than studying. It goes away with the session the tap opens.
 */
import React, {useState} from "react";
import {ScrollView, StyleSheet, Text, View, useWindowDimensions} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {BOTTOM_BAR_HEIGHT, MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";
import {useTheme} from "../theme";
import {IconButton} from "../components";
import {DialogShell} from "../settings/settings-components";
import type {AnkiDeckResult} from "../../native/GatenAnki";
import {useAnkiDecks, type AnkiDecksFailure} from "../anki/useAnkiDecks";
import {BusyRow, DeckRow, ScreenTitle, SectionCaption, SubHeader, SuccessCheck} from "./review-ui";
import {
  CountLegend,
  MetricPair,
  StackedBar,
  useAnalyticsEntrance,
  type DistributionPart,
} from "../analytics/ink";

/** AnkiDroid's own counts for one deck, in the design's one-line form. */
function countsLine(deck: AnkiDeckResult): string {
  return `${deck.newCount} new · ${deck.learnCount} learning · ${deck.reviewCount} reviews`;
}

/** Why the today figure has no number: the words the reader can act on, per failure kind. */
function todayFailureCopy(failure: AnkiDecksFailure): string {
  switch (failure) {
    case "NOT_INSTALLED":
      return "AnkiDroid isn't installed.";
    case "PERMISSION":
      return "AnkiDroid access is needed.";
    case "NOT_SET_UP":
      return "Open AnkiDroid once to set it up.";
    default:
      return "Couldn't read AnkiDroid.";
  }
}

/** What is due in one deck right now: the three counts AnkiDroid reports, added up. */
function dueOf(deck: AnkiDeckResult): number {
  return deck.newCount + deck.learnCount + deck.reviewCount;
}

/** The accent strengths the ready-now bar is drawn from — one hue, three weights. */
const READY_INTENSITY = [0.2, 0.42, 0.67] as const;

export function AnkiDeckPicker({
  decks,
  busy,
  filteredEmpty = false,
  onBack,
  onStart,
}: {
  decks: AnkiDeckResult[];
  busy: boolean;
  filteredEmpty?: boolean;
  onBack: () => void;
  onStart: (deck: AnkiDeckResult) => void;
}) {
  const {colors} = useTheme();
  const anki = useAnkiDecks();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const contentInset = width < 360 ? spacing.l : spacing.contentInset;
  // No cached/offline state is labelled here: the AnkiDroid read is a local provider read, and the
  // store funnels any failure to the full-screen blocked/failed step.
  // Parent decks count their children, so only the top level is added up: the same rule Home's
  // dashboard reads the collection's own due count by.
  const topLevelDecks = decks.filter(
    (deck) => !decks.some((parent) => deck.name.startsWith(`${parent.name}::`)),
  );
  const ready = topLevelDecks.reduce((total, deck) => total + dueOf(deck), 0);
  const allClear = !busy && decks.length > 0 && ready === 0;
  // The three counts behind the ready bar and the deck rows, added up across the top level.
  const newTotal = topLevelDecks.reduce((sum, deck) => sum + deck.newCount, 0);
  const learnTotal = topLevelDecks.reduce((sum, deck) => sum + deck.learnCount, 0);
  const reviewTotal = topLevelDecks.reduce((sum, deck) => sum + deck.reviewCount, 0);
  // What the ready-now bar splits: the same three counts the deck rows carry, added up across the
  // top level, drawn as one distribution in the accent's own weights. Nothing to draw is no bar —
  // an empty or still-loading read stays metric-only.
  const readyParts: DistributionPart[] =
    ready === 0 || busy
      ? []
      : [
          {key: "new", label: "New", count: newTotal},
          {key: "learning", label: "Learn.", spoken: "Learning", count: learnTotal},
          {key: "review", label: "Review", count: reviewTotal},
        ].map((part, index) => ({
          ...part,
          value: String(part.count),
          color: colors.accent,
          opacity: READY_INTENSITY[index],
        }));
  const [selectedReady, setSelectedReady] = useState<string | null>(null);
  const [todayHelpOpen, setTodayHelpOpen] = useState(false);
  // The analytics page's one-shot entrance: figures sweep in once, when the deck list first answers.
  const entrance = useAnalyticsEntrance(!busy && decks.length > 0);
  // The today figure is its own read, so it can fail or still be running while the deck list is
  // ready: a retained earlier count says so — saved, possibly outdated — never fresh, and an unread
  // figure is a dash with the reason beside it.
  const todayHint =
    anki.todayFailure != null
      ? anki.studiedToday != null
        ? "Saved count · may be outdated"
        : todayFailureCopy(anki.todayFailure)
      : anki.todayRefreshing
        ? anki.studiedToday != null
          ? "Updating…"
          : "Checking…"
        : null;

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
        {/* Nothing to report says nothing: the block stands only while one of its figures is a real
            count, and an unread figure (a dash) keeps it up so its reason stays visible. */}
        {!(anki.studiedToday === 0 && ready === 0) && (
          <>
            <View style={[styles.todayHeading, {borderBottomColor: colors.outlineVariant}]}>
              <SectionCaption style={styles.todayCaption}>Today</SectionCaption>
              <IconButton
                name="help"
                size={18}
                accessibilityLabel="About today’s figures"
                onPress={() => setTodayHelpOpen(true)}
              />
            </View>
            <View style={styles.todayMetrics}>
              <MetricPair
                left={{
                  value: anki.studiedToday != null ? String(anki.studiedToday) : "—",
                  count: anki.studiedToday != null ? {value: anki.studiedToday} : null,
                  caption: "Cards studied",
                  note: todayHint,
                }}
                right={{
                  value: busy && decks.length === 0 ? "—" : String(ready),
                  count: busy || decks.length === 0 ? null : {value: ready},
                  caption: "Ready now",
                  // Only a state that needs saying is said: a failure, a read still on its way, or a
                  // selection with nothing in it. What each figure counts lives behind the help control.
                  note:
                    busy && decks.length === 0
                      ? "Checking…"
                      : anki.studyDecks != null && anki.studyDecks.length === 0
                        ? "No study decks selected"
                        : null,
                }}
                entrance={entrance}
              />
            </View>
          </>
        )}
        {readyParts.length > 0 && (
          <>
            <StackedBar
              segments={readyParts}
              selectedKey={selectedReady}
              onSelect={setSelectedReady}
              entrance={entrance}
            />
            <CountLegend
              items={readyParts}
              selectedKey={selectedReady}
              onSelect={setSelectedReady}
              entrance={entrance}
            />
          </>
        )}
        {allClear && (
          <View style={styles.caughtUp} accessible accessibilityLabel="You're all caught up. Every available deck is clear for now.">
            <SuccessCheck />
            <Text style={[styles.caughtUpText, {color: colors.onSurfaceVariant}]}>
              You’re all caught up — every available deck is clear for now.
            </Text>
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
      {todayHelpOpen && (
        <DialogShell
          title="About today’s figures"
          onDismiss={() => setTodayHelpOpen(false)}
          actions={
            <Text
              accessibilityRole="button"
              onPress={() => setTodayHelpOpen(false)}
              style={[styles.dialogAction, {color: colors.accent}]}
            >
              Close
            </Text>
          }
        >
          <Text style={[styles.dialogParagraph, {color: colors.onSurfaceVariant}]}>
            Cards studied counts the distinct cards you answered in AnkiDroid today, across every
            deck. Answering the same card again the same day counts once.
          </Text>
          <Text style={[styles.dialogParagraph, {color: colors.onSurfaceVariant}]}>
            Ready now adds up the cards due right now in the study decks you selected. “Today” is
            AnkiDroid’s own study day, which rolls over when your Anki day does — not at midnight.
          </Text>
        </DialogShell>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {flex: 1},
  body: {width: "100%", maxWidth: MAX_CONTENT_WIDTH, alignSelf: "center"},
  title: {marginTop: 18},
  caption: {marginTop: 22},
  // The Today heading row: the caption's own text stretches, the help target stands beside it, and
  // the row carries the one hairline the SectionCaption used to draw alone.
  todayHeading: {
    flexDirection: "row",
    alignItems: "flex-end",
    marginTop: 22,
    paddingBottom: 12,
    borderBottomWidth: 1,
  },
  todayCaption: {flex: 1, marginTop: 0, marginBottom: 0, borderBottomWidth: 0, paddingBottom: 0},
  todayMetrics: {marginTop: 12},
  dialogAction: {fontSize: 15, fontWeight: "600", lineHeight: 20, paddingHorizontal: spacing.m, paddingVertical: 14},
  dialogParagraph: {fontFamily: "Inter", fontSize: 14, lineHeight: 20, marginBottom: spacing.m},
  empty: {marginTop: spacing.s},
  // The all-clear state is one flat line, not a surface: the accent check and a quiet sentence.
  caughtUp: {flexDirection: "row", alignItems: "center", gap: spacing.s, marginTop: -6, marginBottom: 24},
  caughtUpText: {fontFamily: "Inter", fontSize: 12, lineHeight: 17, flex: 1, minWidth: 0},
  skeletonList: {marginTop: spacing.s},
  // The skeleton is the shared row's own geometry: a hairline divider, no card, no fill.
  skeletonRow: {minHeight: 90, flexDirection: "row", alignItems: "center", gap: spacing.m, paddingVertical: spacing.m, borderBottomWidth: 1},
  skeletonTile: {width: 48, height: 48, borderRadius: 12},
  skeletonCopy: {flex: 1, gap: spacing.s},
  skeletonName: {width: "58%", height: 15, borderRadius: 5},
  skeletonMeta: {width: "82%", height: 11, borderRadius: 5},
  skeletonDue: {width: 24, height: 23, borderRadius: 5, marginEnd: spacing.s},
  busy: {marginTop: spacing.l},
});
