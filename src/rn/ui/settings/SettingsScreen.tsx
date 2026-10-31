/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The settings hub: what lookup mode is, then a row per page under the group it belongs to.
 *
 * Everything on it is a summary read from wherever the truth lives — the platform's
 * enabled-services setting, the selected OCR engine, AnkiDroid, the stored token — and read again on
 * every arrival and every time the app comes back to the foreground, because all of it can change
 * while the reader is elsewhere. Nothing here asks for anything: a missing permission or model is
 * stated, and the page that owns it is where it is asked for.
 */
import React, {useEffect, useState} from "react";
import {AppState, ScrollView, Text, View, useWindowDimensions} from "react-native";
import {useIsFocused} from "@react-navigation/native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {AnkiStatus} from "../../native/GatenAnki";
import type {SettingsPageName} from "../navigation";
import {useKanjiReferenceSource} from "../kanjiReference";
import {useTheme} from "../theme";
import {BOTTOM_BAR_HEIGHT, contentInsetFor, spacing, typeScale} from "../tokens";
import {CONTENT_COLUMN, SettingsGroup, SettingsRow} from "./settings-components";

/** What the Anki row says under its name: the page's own business, or AnkiDroid's other two states. */
function ankiSummary(status: AnkiStatus): string {
  switch (status) {
    case "notInstalled":
      return "Not installed";
    case "needsPermission":
      return "Permission needed";
    default:
      return "Connected";
  }
}

export function SettingsHub({onOpenPage}: {onOpenPage: (page: SettingsPageName) => void}) {
  const theme = useTheme();
  const colors = theme.colors;
  const kanjiReference = useKanjiReferenceSource();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();

  const [lookupMode, setLookupMode] = useState(false);
  const [ankiStatus, setAnkiStatus] = useState<AnkiStatus>("notInstalled");
  const [ankiDeck, setAnkiDeck] = useState<string | null>(null);
  const [wanikaniConnected, setWanikaniConnected] = useState(false);
  const [versionName, setVersionName] = useState("");

  // Lookup mode is a system setting the reader grants outside Gaten, and a model or a token
  // changes inside a page, so the rows are read on every arrival — the return from one of the
  // pages this hub opens included — and every time the app comes back to the foreground while the
  // hub is the screen on show. `isFocused` is the
  // stable thing an arrival changes that an effect can depend on: a callback rebuilt per render would
  // either read in a loop or read once and never again. Nothing is read while another screen is on
  // top, and a resume that happens somewhere else reads nothing here.
  const isFocused = useIsFocused();
  useEffect(() => {
    if (!isFocused) {
      return;
    }
    let cancelled = false;
    const readAll = () => {
      void GatenAppInfo.isLookupModeActive().then((v) => {
        if (!cancelled) setLookupMode(v);
      });
      void GatenAnki.status().then((v) => {
        if (!cancelled) setAnkiStatus(v);
      });
      void GatenAnki.getPreferences().then((v) => {
        if (!cancelled) setAnkiDeck(v.deckName);
      });
      void GatenWaniKani.isConfigured().then((v) => {
        if (!cancelled) setWanikaniConnected(v);
      });
      void GatenAppInfo.getAppVersion().then((v) => {
        if (!cancelled) setVersionName(v);
      });
    };
    readAll();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") readAll();
    });
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, [isFocused]);

  return (
    <View style={{flex: 1, backgroundColor: colors.background}}>
      {/* Keep the title below the status bar. Bottom padding lets rows scroll above the floating
          bar while remaining visible beside it, matching Analytics. */}
      <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} style={{marginTop: insets.top}}>
        <View
          style={[
            CONTENT_COLUMN,
            {
              paddingHorizontal: contentInsetFor(width),
              paddingBottom: BOTTOM_BAR_HEIGHT + insets.bottom + spacing.l,
            },
          ]}
        >
          {/* One confident heading with room to breathe above it, then nothing but rows. */}
          <View style={{marginTop: 23}}>
            <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
              Settings
            </Text>
          </View>
          <SettingsGroup title="Lookup" style={{marginTop: 22}}>
            <SettingsRow
              icon="search"
              inkIcon="search"
              title="Screen lookup"
              value={lookupMode ? "On" : "Off"}
              onPress={() => onOpenPage("lookup")}
            />
            <SettingsRow
              icon="text_snippet"
              inkIcon="text"
              title="Text recognition"
              value="Manga OCR"
              onPress={() => onOpenPage("recognition")}
            />
          </SettingsGroup>
          <SettingsGroup title="Study" style={{marginTop: 22}}>
            <SettingsRow
              icon="library_books"
              inkIcon="cards"
              title="Anki cards"
              value={ankiStatus === "ready" ? ankiDeck ?? "Choose deck" : ankiSummary(ankiStatus)}
              onPress={() => onOpenPage("anki")}
            />
            <SettingsRow
              icon="translate"
              inkIcon="torii"
              title="WaniKani"
              value={wanikaniConnected ? "Connected" : "Not connected"}
              onPress={() => onOpenPage("wanikani")}
            />
            <SettingsRow
              icon="school"
              title="Kanji reference"
              value={kanjiReference === "rtk" ? "Remembering the Kanji" : "WaniKani"}
              onPress={() => onOpenPage("reference")}
            />
          </SettingsGroup>
          <SettingsGroup title="App" style={{marginTop: 22}}>
            <SettingsRow
              icon="palette"
              inkIcon="sun"
              title="Appearance"
              value={theme.mode}
              onPress={() => onOpenPage("appearance")}
            />
            <SettingsRow
              icon="info"
              inkIcon="user"
              title="About"
              value={versionName}
              onPress={() => onOpenPage("about")}
            />
          </SettingsGroup>
        </View>
      </ScrollView>
    </View>
  );
}
