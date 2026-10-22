/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Settings tab's screens, as the shell mounts them. `SettingsTab` is the tab root — the hub —
 * and `SettingsPageScreen` is the page pushed above it, keyed by its route name and a fresh
 * instance so one page can sit on a stack twice.
 */
import React from "react";
import {useIsFocused, useNavigation} from "@react-navigation/native";
import type {NativeStackNavigationProp} from "@react-navigation/native-stack";
import {
  openSettingsPage,
  type SettingsPageName,
  type SettingsStackParamList,
} from "./navigation";
import {SettingsHub} from "./settings/SettingsScreen";
import {LookupSettingsScreen} from "./settings/LookupSettingsScreen";
import {RecognitionSettingsScreen} from "./settings/RecognitionSettingsScreen";
import {AnkiSettingsScreen} from "./settings/AnkiSettingsScreen";
import {WaniKaniSettingsScreen} from "./settings/WaniKaniSettingsScreen";
import {AppearanceSettingsScreen} from "./settings/AppearanceSettingsScreen";
import {AboutScreen} from "./settings/AboutScreen";

export function SettingsTab() {
  const navigation =
    useNavigation<NativeStackNavigationProp<SettingsStackParamList, "SettingsTab">>();
  return (
    <SettingsHub onOpenPage={(page) => openSettingsPage(navigation, page)} />
  );
}

/**
 * One settings page, on whichever stack pushed it. Its own focus is what the pages that guard Back
 * or ask before discarding answer with: a page that has been left — another tab is on screen, or a
 * second copy sits above it — is still composed, and must not answer for the reader's screen.
 */
export function SettingsPageScreen({page}: {page: SettingsPageName}) {
  // This page is mounted by Home's, Analytics' and Settings' own stacks, so its navigation is not
  // typed after one of them; all it does with it is close itself, which the nearest stack handles.
  const navigation = useNavigation();
  const isCurrentDestination = useIsFocused();
  const onClose = () => navigation.goBack();
  switch (page) {
    case "lookup":
      return <LookupSettingsScreen onClose={onClose} />;
    case "recognition":
      return <RecognitionSettingsScreen onClose={onClose} />;
    case "anki":
      return <AnkiSettingsScreen onClose={onClose} isCurrentDestination={isCurrentDestination} />;
    case "wanikani":
      return <WaniKaniSettingsScreen onClose={onClose} />;
    case "appearance":
      return <AppearanceSettingsScreen onClose={onClose} />;
    default:
      return <AboutScreen onClose={onClose} />;
  }
}