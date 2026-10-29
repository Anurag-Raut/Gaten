/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The routes the shell builds: one independent stack per tab, seeded with its root, plus the pages
 * that tab pushes above it. The names here are what the Compose keys carried: WordKey(entryId),
 * KanjiKey(character) and RadicalKey(id) belong to the tab the reader searched from — Home, where the
 * search lives — and a settings page belongs to the tab it was opened from, so every tab that can
 * open one carries it. Only an outside launch picks the Settings tab for a page.
 *
 * The review flow is a pushed page rather than a tab: Home's two service cards are the only ways in,
 * and the flow is something the reader does, not a place they browse.
 */
import {Keyboard} from "react-native";
import type {NavigationState, NavigatorScreenParams} from "@react-navigation/native";

/** The three tabs, in the order the bar shows them. Each holds a nested stack (v7's typing). */
export type RootTabParamList = {
  Home: NavigatorScreenParams<HomeStackParamList> | undefined;
  Analytics: NavigatorScreenParams<AnalyticsStackParamList> | undefined;
  Settings: NavigatorScreenParams<SettingsStackParamList> | undefined;
};

/**
 * One page inside Settings, and the copy of it. `instance` distinguishes two copies of the same page
 * on one stack: the reader has one page open, an outside launch asks for the same one, and a second
 * copy opens over it with the lower one untouched.
 */
export type SettingsPageParams = {page: SettingsPageName; instance: string};

/**
 * The Home tab: the dashboard with its own search on it, the pages that search opens, the settings
 * page a card on Home or a footer link on a word reaches, and the review flow. All of them stay on
 * Home, so Back returns the reader to the card or the word they came from.
 */
export type HomeStackParamList = {
  HomeTab: undefined;
  /**
   * Studying: AnkiDroid's deck list and its cards, or WaniKani's connection screen and its queue.
   * Which of the two this is — and which step of it — is the review store's own state, set before
   * this page is pushed, so the page has no params: it draws whatever the store was asked for.
   */
  Review: undefined;
  /** A dictionary entry, by its JMdict/JMnedict id. */
  Word: {entryId: number};
  /** A WaniKani kanji, by its character. */
  Kanji: {character: string};
  /** A WaniKani radical, by its id. */
  Radical: {id: number};
  SettingsPage: SettingsPageParams;
};

/**
 * The Analytics tab: one dashboard over both services, plus the settings page a card opens when the
 * service behind it has to be connected or configured.
 */
export type AnalyticsStackParamList = {
  AnalyticsTab: undefined;
  SettingsPage: SettingsPageParams;
};

/** The Settings tab: the hub, and the pages it lists. */
export type SettingsStackParamList = {
  SettingsTab: undefined;
  SettingsPage: SettingsPageParams;
};

/** The pages inside Settings, in the order the hub lists them (HomeState.kt's SettingsRoutes). */
export const SETTINGS_PAGES = [
  "lookup",
  "recognition",
  "anki",
  "wanikani",
  "reference",
  "appearance",
  "about",
] as const;

export type SettingsPageName = (typeof SETTINGS_PAGES)[number];

let settingsInstanceCounter = 0;

/** A fresh instance id, so the same page can be pushed over itself with its own state. */
export function freshSettingsInstance(): string {
  settingsInstanceCounter += 1;
  return `${Date.now()}-${settingsInstanceCounter}`;
}

/** What a tab's stack has to offer for a settings page to open on it. */
type SettingsHostNavigation = {
  getState(): NavigationState;
  push(name: "SettingsPage", params: SettingsPageParams): void;
};

/**
 * Opens [page] above whatever the reader is looking at, on the stack of the tab they are on: a
 * settings page is the reader's own errand, and Back has to return them to the page they left —
 * never to another tab's hub.
 *
 * The page already on top is not opened a second time: that is one tap answered twice, and the guard
 * reads the navigator's own state, which the first push has committed by the time a second press
 * arrives, so no timer and no in-flight flag is needed. Any other page pushes a new entry with its
 * own `instance`, so the page underneath — drafts and all — is left alone.
 *
 * The keyboard goes with the push: the field that had it (the search field on the page the reader is
 * leaving) is no longer on screen, and nothing else dismisses it — the library's own
 * `keyboardDismissMode` only fires on a tab change, and the pushed page has no focused field.
 */
export function openSettingsPage(
  navigation: SettingsHostNavigation,
  page: SettingsPageName,
): void {
  const state = navigation.getState();
  const top = state.routes[state.index];
  const open = top.params as SettingsPageParams | undefined;
  if (top.name === "SettingsPage" && open?.page === page) {
    return;
  }
  Keyboard.dismiss();
  navigation.push("SettingsPage", {page, instance: freshSettingsInstance()});
}
