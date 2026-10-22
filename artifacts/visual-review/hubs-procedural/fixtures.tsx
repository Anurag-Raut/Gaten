import React, {useEffect, useState} from "react";
import {AppRegistry, DeviceEventEmitter, ScrollView, StatusBar, StyleSheet, Text, View} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import {SafeAreaProvider, useSafeAreaInsets} from "react-native-safe-area-context";
import {ThemeProvider, useTheme} from "../../../src/rn/ui/theme";
import {AnkiDeckPicker} from "../../../src/rn/ui/review/AnkiDeckPicker";
import DeckThumbnail from "../../../src/rn/ui/review/DeckThumbnail";
import {WaniKaniHome} from "../../../src/rn/ui/review/WaniKaniHome";
import type {WaniKaniHubState, WaniKaniSummary} from "../../../src/rn/ui/review/review-store";
import {deckArtSpec, DECK_ART_PALETTES} from "../../../src/rn/ui/review/deck-art";
import type {AnkiDeckResult} from "../../../src/rn/native/GatenAnki";
import * as Shell from "../../../src/rn/native/GatenShell";
import * as ThemeBridge from "../../../src/rn/native/GatenTheme";

const now = Date.now();
const originalTheme = ThemeBridge.getTheme();
const summary = (patch: Partial<WaniKaniSummary> = {}): WaniKaniSummary => ({
  dueNow: 23, lessonsNow: 15, nextReviewAt: now + 15 * 60_000,
  nextHourCount: 6, fetchedAt: now, sync: "fresh", retryAfterSeconds: null, ...patch,
});
const wkBase: WaniKaniHubState = {
  kind: "wkHome", summary: summary(), failed: false, failure: null,
  connection: "connected", refreshing: false, lessonsLocked: false,
  pausedLesson: null, pausedReview: null, reviewFailure: null, lessonFailure: null,
  learningPreferences: {dailyLimit: null, learnedToday: 0},
};
const wkStates: Record<string, WaniKaniHubState> = {
  ready: wkBase,
  partial: {...wkBase, lessonFailure: {failure: "OFFLINE", retryAfterSeconds: null}},
  allClear: {...wkBase, summary: summary({dueNow: 0, lessonsNow: 0})},
  loading: {...wkBase, summary: null},
  error: {...wkBase, summary: null, failed: true, failure: {failure: "SERVER", retryAfterSeconds: null}},
  offlineCached: {...wkBase, summary: summary({sync: "offline"})},
};
const ankiDecks: AnkiDeckResult[] = [
  {id: 101, name: "Default", newCount: 1, learnCount: 0, reviewCount: 4, filtered: false},
  {id: 202, name: "Japanese", newCount: 0, learnCount: 2, reviewCount: 12, filtered: false},
  {id: 404, name: "Vocabulary", newCount: 1, learnCount: 0, reviewCount: 0, filtered: false},
  {id: 505, name: "Grammar", newCount: 0, learnCount: 0, reviewCount: 0, filtered: false},
  {id: 606, name: "Sentence Mining", newCount: 2, learnCount: 1, reviewCount: 8, filtered: false},
];
const zeroDecks = ankiDecks.map((deck) => ({...deck, newCount: 0, learnCount: 0, reviewCount: 0}));
const galleryKeys = (() => {
  const selected = new Map<string, number>();
  for (let id = 0; id < 50_000 && selected.size < DECK_ART_PALETTES.length * 5; id++) {
    const spec = deckArtSpec(`ankidroid-local:${id}`);
    const key = `${spec.paletteId}-${spec.motif}`;
    if (!selected.has(key)) selected.set(key, id);
  }
  return [...selected.entries()].map(([key, id]) => ({key, id, spec: deckArtSpec(`ankidroid-local:${id}`)}));
})();

function Gallery() {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  return <ScrollView contentContainerStyle={[styles.gallery, {paddingTop: insets.top + 24, backgroundColor: colors.background}]}>
    <Text style={[styles.galleryTitle, {color: colors.onSurface}]}>Deck artwork · curated palette and motif set</Text>
    <View style={styles.galleryGrid}>{galleryKeys.map(({key, id, spec}) =>
      <View key={key} style={styles.galleryItem}>
        <DeckThumbnail deckId={id} />
        <Text style={[styles.galleryLabel, {color: colors.onSurfaceVariant}]}>{spec.paletteName} · motif {spec.motif + 1}</Text>
      </View>,
    )}</View>
  </ScrollView>;
}

function FixtureContent() {
  const [entry, setEntry] = useState("anki-ready-light");
  const {mode, setMode} = useTheme();
  const apply = (value: string | null | undefined) => {
    if (!value) return;
    if (value === "restore") {
      void originalTheme.then(async (choices) => {
        await ThemeBridge.setThemeMode(choices.mode);
        await ThemeBridge.setPalette(choices.palette);
        await ThemeBridge.setAccent(choices.accent);
        setEntry("anki-ready-light");
      });
      void Shell.consumeSettingsPageRequest();
      return;
    }
    const parts = value.split("-");
    const requestedTheme = parts.pop();
    const requestedScene = parts.join("");
    if (!requestedScene || (requestedTheme !== "light" && requestedTheme !== "dark")) return;
    setEntry(value);
    setMode(requestedTheme === "dark" ? "Dark" : "Light");
    void Shell.consumeSettingsPageRequest();
  };
  useEffect(() => {
    void Shell.getSettingsPageRequest().then(apply).catch(() => {});
    const subscription = DeviceEventEmitter.addListener(
      "gaten.settingsPageRequest",
      (event: {page?: string} | undefined) => apply(event?.page),
    );
    return () => subscription.remove();
  }, []);

  const scene = entry.split("-").slice(0, -1).join("");
  const visibleAnkiDecks =
    scene === "ankiLoading" || scene === "ankiEmpty"
      ? []
      : scene === "ankiAllClear"
        ? zeroDecks
        : scene === "ankiCompleted"
          ? ankiDecks.map((deck) => deck.id === 101 ? {...deck, newCount: 0, learnCount: 0, reviewCount: 0} : deck)
          : ankiDecks;
  return <>
    <StatusBar barStyle={mode === "Dark" ? "light-content" : "dark-content"} backgroundColor="transparent" />
    {scene === "gallery" ? <Gallery /> : scene.startsWith("wk") ? (
      <WaniKaniHome
        hub={wkStates[scene.slice(2, 3).toLowerCase() + scene.slice(3)] ?? wkBase} busy={false} onBack={() => {}}
        onStartReviews={() => {}} onStartLessons={() => {}} onRetrySync={() => {}} onConnect={() => {}}
      />
    ) : (
      <AnkiDeckPicker
        decks={visibleAnkiDecks}
        busy={scene === "ankiLoading" || scene === "ankiReadyRefreshing"}
        completedDeckIds={scene === "ankiCompleted" ? [101] : []}
        onBack={() => {}} onStart={() => {}}
      />
    )}
  </>;
}

function VisualReviewFixture() {
  return <GestureHandlerRootView style={styles.root}>
    <SafeAreaProvider><ThemeProvider><FixtureContent /></ThemeProvider></SafeAreaProvider>
  </GestureHandlerRootView>;
}

const styles = StyleSheet.create({
  root: {flex: 1},
  gallery: {paddingHorizontal: 20, paddingBottom: 32, flexGrow: 1},
  galleryTitle: {fontSize: 18, fontWeight: "700", marginBottom: 16},
  galleryGrid: {flexDirection: "row", flexWrap: "wrap", gap: 8},
  galleryItem: {width: 60, alignItems: "center", gap: 4},
  galleryLabel: {fontSize: 9, lineHeight: 11, textAlign: "center"},
});

AppRegistry.registerComponent("Gaten", () => VisualReviewFixture);
