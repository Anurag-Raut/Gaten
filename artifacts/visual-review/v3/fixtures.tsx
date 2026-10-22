import React, {useEffect, useState} from "react";
import {AppRegistry, DeviceEventEmitter, StatusBar, StyleSheet} from "react-native";
import {GestureHandlerRootView} from "react-native-gesture-handler";
import {SafeAreaProvider} from "react-native-safe-area-context";
import {ThemeProvider, useTheme} from "../../../src/rn/ui/theme";
import {WaniKaniHome} from "../../../src/rn/ui/review/WaniKaniHome";
import type {WaniKaniHubState, WaniKaniSummary} from "../../../src/rn/ui/review/review-store";
import * as Shell from "../../../src/rn/native/GatenShell";
import * as ThemeBridge from "../../../src/rn/native/GatenTheme";

const now = Date.now();
const originalTheme = ThemeBridge.getTheme();
const summary = (patch: Partial<WaniKaniSummary> = {}): WaniKaniSummary => ({
  dueNow: 23,
  lessonsNow: 77,
  nextReviewAt: now + 15 * 60_000,
  nextHourCount: 6,
  fetchedAt: now,
  sync: "fresh",
  retryAfterSeconds: null,
  ...patch,
});
const base: WaniKaniHubState = {
  kind: "wkHome", summary: summary(), failed: false, failure: null,
  connection: "connected", refreshing: false, lessonsLocked: false,
  pausedLesson: null, pausedReview: null, reviewFailure: null, lessonFailure: null,
};
const states: Record<string, WaniKaniHubState> = {
  ready: base,
  reviewsDone: {...base, summary: summary({dueNow: 0})},
  lessonsDone: {...base, summary: summary({lessonsNow: 0})},
  bothDone: {...base, summary: summary({dueNow: 0, lessonsNow: 0})},
  loading: {...base, summary: null},
  refresh: {...base, refreshing: true},
  disconnected: {...base, connection: "disconnected"},
  expired: {...base, connection: "expired"},
  offlineCached: {...base, summary: summary({sync: "offline"})},
  offlineEmpty: {...base, summary: null, failed: true, failure: {failure: "OFFLINE", retryAfterSeconds: null}},
  error: {...base, summary: null, failed: true, failure: {failure: "SERVER", retryAfterSeconds: null}},
  locked: {...base, lessonsLocked: true},
  partial: {...base, lessonFailure: {failure: "OFFLINE", retryAfterSeconds: null}},
  paused: {...base, pausedReview: {items: [{}, {}, {}], index: 1, doneParts: 2, totalParts: 6} as WaniKaniHubState["pausedReview"]},
  dailyLimit: {...base, learningPreferences: {dailyLimit: 2, learnedToday: 2}},
};

function HubFixture() {
  const [entry, setEntry] = useState("ready-light");
  const {mode, setMode} = useTheme();
  const apply = (value: string | null | undefined) => {
    if (!value) return;
    const [state, requestedTheme] = value.split("-");
    if (state === "restore") {
      void originalTheme.then(async (choices) => {
        await ThemeBridge.setThemeMode(choices.mode);
        await ThemeBridge.setPalette(choices.palette);
        await ThemeBridge.setAccent(choices.accent);
        setEntry("ready-light");
      });
      void Shell.consumeSettingsPageRequest();
      return;
    }
    if (states[state] == null || (requestedTheme !== "light" && requestedTheme !== "dark")) return;
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

  const [stateName] = entry.split("-");
  return (
    <>
      <StatusBar barStyle={mode === "Dark" ? "light-content" : "dark-content"} backgroundColor="transparent" />
      <WaniKaniHome
        key={entry}
        hub={states[stateName]}
        busy={false}
        onBack={() => {}}
        onStartReviews={() => {}}
        onStartLessons={() => {}}
        onRetrySync={() => {}}
        onConnect={() => {}}
      />
    </>
  );
}

function VisualReviewFixture() {
  return (
    <GestureHandlerRootView style={styles.root}>
      <SafeAreaProvider>
        <ThemeProvider><HubFixture /></ThemeProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({root: {flex: 1}});
AppRegistry.registerComponent("Gaten", () => VisualReviewFixture);
