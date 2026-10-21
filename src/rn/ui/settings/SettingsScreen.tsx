/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The settings hub: what screen access is, then a row per page under the group it belongs to.
 *
 * Everything on it is a summary read from wherever the truth lives — the platform's
 * enabled-services setting, the model directory, AnkiDroid, the stored token — and read again on
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
import * as GatenRecognition from "../../native/GatenRecognition";
import type {AnkiStatus} from "../../native/GatenAnki";
import type {SettingsPageName} from "../navigation";
import {useTheme} from "../theme";
import {BOTTOM_BAR_HEIGHT, spacing, typeScale} from "../tokens";
import {
  CONTENT_COLUMN,
  SettingsGroup,
  SettingsRow,
  useLookupModeRequest,
} from "./settings-components";

/** The name of the model in place, shown on the right of the recognition row. */
function recognitionValue(
  installed: boolean,
  installedId: string | null,
  catalog: GatenRecognition.MangaOcrCatalogEntry[]
): string {
  if (!installed) {
    return "None";
  }
  return catalog.find((entry) => entry.id === installedId)?.label ?? "None";
}

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
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const {request: requestLookupMode, dialog: disclosureDialog} = useLookupModeRequest();

  const [lookupMode, setLookupMode] = useState(false);
  const [modelInstalled, setModelInstalled] = useState(false);
  const [modelId, setModelId] = useState<string | null>(null);
  const [ankiStatus, setAnkiStatus] = useState<AnkiStatus>("notInstalled");
  const [ankiDeck, setAnkiDeck] = useState<string | null>(null);
  const [wanikaniConnected, setWanikaniConnected] = useState(false);
  const [versionName, setVersionName] = useState("");
  const [catalog, setCatalog] = useState<GatenRecognition.MangaOcrCatalogEntry[]>([]);

  // Lookup mode ends outside Gaten as easily as it starts — the status-bar chip, the notification,
  // the screen locking — and a model or a token changes inside a page, so the rows are read on
  // every arrival — the return from one of the pages this hub opens included — and every time the
  // app comes back to the foreground while the hub is the screen on show. `isFocused` is the
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
      void GatenAppInfo.getMangaOcrState().then((v) => {
        if (cancelled) return;
        setModelInstalled(v.installed);
        setModelId(v.catalogId);
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
      void GatenRecognition.getCatalog().then((v) => {
        if (!cancelled) setCatalog(v);
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
      {/* The hub scrolls as one, exactly as the Compose hub did: the bar below owns the bottom
          inset while it is on screen, so only the top is taken here and nothing above the bar is
          padded twice. The content column is the pages' own. The top inset is padding on the
          scroller itself rather than on its content, because Compose's hub took safeDrawing's top
          inset outside its scroller: its content never slid under the status bar, where a content
          inset let this page's title run under the clock as soon as it scrolled. */}
      <ScrollView style={{marginTop: insets.top, marginBottom: BOTTOM_BAR_HEIGHT + insets.bottom}}>
        <View style={[CONTENT_COLUMN, {paddingHorizontal: width < 360 ? 16 : 22}]}>
          {/* One confident heading with room to breathe above it, then nothing but rows. */}
          <View style={{marginTop: 23}}>
            <Text style={[typeScale.headlineSmall, {color: colors.onSurface}]}>
              Settings
            </Text>
            <Text style={[typeScale.description, {color: colors.onSurfaceVariant, marginTop: 10}]}>
              Manage your Gaten preferences.
            </Text>
          </View>
          <SettingsGroup bare title="Lookup" style={{marginTop: 22}}>
            <SettingsRow
              surface
              icon={lookupMode ? "check_circle" : "shield"}
              inkIcon={!lookupMode ? "shield" : undefined}
              title="Lookup mode"
              summary="Read Japanese off any screen"
              value={lookupMode ? "On" : "Off"}
              onPress={lookupMode ? () => onOpenPage("lookup") : requestLookupMode}
            />
            <SettingsRow
              surface
              icon="search"
              inkIcon="search"
              title="Screen lookup"
              summary="Button, tile & shortcut"
              onPress={() => onOpenPage("lookup")}
            />
            <SettingsRow
              surface
              icon="text_snippet"
              inkIcon="text"
              title="Text recognition"
              summary="Stylized text model"
              value={recognitionValue(modelInstalled, modelId, catalog)}
              onPress={() => onOpenPage("recognition")}
            />
          </SettingsGroup>
          <SettingsGroup bare title="Study" style={{marginTop: 22}}>
            <SettingsRow
              surface
              icon="library_books"
              inkIcon="cards"
              title="Anki cards"
              summary="Deck & card layout"
              value={ankiStatus === "ready" ? ankiDeck ?? "Choose deck" : ankiSummary(ankiStatus)}
              onPress={() => onOpenPage("anki")}
            />
            <SettingsRow
              surface
              icon="translate"
              inkIcon="torii"
              title="WaniKani"
              summary="Kanji & vocabulary"
              value={wanikaniConnected ? "Connected" : "Not connected"}
              onPress={() => onOpenPage("wanikani")}
            />
          </SettingsGroup>
          <SettingsGroup bare title="App" style={{marginTop: 22}}>
            <SettingsRow
              surface
              icon="palette"
              inkIcon="sun"
              title="Appearance"
              summary="Theme preferences"
              value={theme.mode}
              onPress={() => onOpenPage("appearance")}
            />
            <SettingsRow
              surface
              icon="info"
              inkIcon="user"
              title="About"
              summary="Offline, privacy & licences"
              value={versionName}
              onPress={() => onOpenPage("about")}
            />
          </SettingsGroup>
          <View style={{height: 18}} />
        </View>
      </ScrollView>
      {disclosureDialog}
    </View>
  );
}
