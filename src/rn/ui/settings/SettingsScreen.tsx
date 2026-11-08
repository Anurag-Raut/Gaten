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
import {AppState, ScrollView, View, useWindowDimensions} from "react-native";
import {useIsFocused} from "@react-navigation/native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenRecognition from "../../native/GatenRecognition";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import type {AnkiStatus} from "../../native/GatenAnki";
import type {RecognitionEngine, RecognitionSettings} from "../../native/GatenRecognition";
import type {SettingsPageName} from "../navigation";
import {useKanjiReferenceSource} from "../kanjiReference";
import {useTheme} from "../theme";
import {TabHeading} from "../TabHeading";
import {SupportSheet} from "../support/SupportSheet";
import {BOTTOM_BAR_HEIGHT, contentInsetFor, spacing} from "../tokens";
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

/** The engine's short name for the hub row; the full names live on the recognition page. */
function recognitionModeLabel(mode: RecognitionEngine): string {
  switch (mode) {
    case "ml_kit":
      return "ML Kit";
    default:
      return "PP-OCRv6 Medium";
  }
}

export function SettingsHub({onOpenPage}: {onOpenPage: (page: SettingsPageName) => void}) {
  const theme = useTheme();
  const colors = theme.colors;
  const kanjiReference = useKanjiReferenceSource();
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();

  const [lookupMode, setLookupMode] = useState(false);
  const [recognitionSettings, setRecognitionSettings] = useState<RecognitionSettings | null>(null);
  const [ankiStatus, setAnkiStatus] = useState<AnkiStatus>("notInstalled");
  const [ankiDeck, setAnkiDeck] = useState<string | null>(null);
  const [wanikaniConnected, setWanikaniConnected] = useState(false);
  const [versionName, setVersionName] = useState("");
  const [supportOpen, setSupportOpen] = useState(false);

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
      void GatenRecognition.getRecognitionSettings().then((v) => {
        if (!cancelled) setRecognitionSettings(v);
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

  // The selected engine's name, and whether its model is actually on the device: the bundled ML Kit
  // is always ready, but a selected downloadable engine that is not installed is read by ML Kit
  // instead, so the row says so rather than implying no recognition is available.
  const recognitionMode = recognitionSettings?.mode ?? "ml_kit";
  const selectedEngine = recognitionSettings?.engines.find(
    (engine) => engine.id === recognitionMode,
  );
  const recognitionSummary =
    selectedEngine != null && !selectedEngine.installed ? "Not downloaded · ML Kit reads" : undefined;

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
            <TabHeading label="Settings" color={colors.onSurface} />
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
              summary={recognitionSummary}
              value={recognitionModeLabel(recognitionMode)}
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
            {/* Always here, whatever the popup's own dismissal state: silencing the popup must
                never take away the way to support the project. */}
            <SettingsRow
              icon="public"
              title="Support Gaten"
              onPress={() => setSupportOpen(true)}
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
      {supportOpen && <SupportSheet onDismiss={() => setSupportOpen(false)} />}
    </View>
  );
}
