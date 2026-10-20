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
import {AppState, ScrollView, Text, View} from "react-native";
import {useIsFocused} from "@react-navigation/native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenAnki from "../../native/GatenAnki";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import * as GatenRecognition from "../../native/GatenRecognition";
import type {AnkiStatus} from "../../native/GatenAnki";
import type {SettingsPageName} from "../navigation";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {
  CONTENT_COLUMN,
  SettingsGroup,
  SettingsRow,
  useScreenAccessRequest,
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
      return "AnkiDroid is not installed";
    case "needsPermission":
      return "Allow Gaten to add cards to AnkiDroid";
    default:
      return "Deck, note type and card layout";
  }
}

export function SettingsHub({onOpenPage}: {onOpenPage: (page: SettingsPageName) => void}) {
  const theme = useTheme();
  const colors = theme.colors;
  const insets = useSafeAreaInsets();
  const {request: requestScreenAccess, dialog: disclosureDialog} = useScreenAccessRequest();

  const [screenAccess, setScreenAccess] = useState(false);
  const [modelInstalled, setModelInstalled] = useState(false);
  const [modelId, setModelId] = useState<string | null>(null);
  const [ankiStatus, setAnkiStatus] = useState<AnkiStatus>("notInstalled");
  const [ankiDeck, setAnkiDeck] = useState<string | null>(null);
  const [wanikaniConnected, setWanikaniConnected] = useState(false);
  const [versionName, setVersionName] = useState("");
  const [catalog, setCatalog] = useState<GatenRecognition.MangaOcrCatalogEntry[]>([]);

  // Screen access is granted outside Gaten and a model or a token changes inside a page, so the rows
  // are read on every arrival — the return from one of the pages this hub opens included — and every
  // time the app comes back to the foreground while the hub is the screen on show. `isFocused` is the
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
      void GatenAppInfo.isScreenAccessEnabled().then((v) => {
        if (!cancelled) setScreenAccess(v);
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
      <ScrollView style={{marginTop: insets.top}}>
        <View style={CONTENT_COLUMN}>
          {/* One confident heading with room to breathe above it, then nothing but rows. */}
          <View style={{marginTop: spacing.xxl + spacing.m}}>
            <Text style={[typeScale.displaySmall, {color: colors.onSurface}]}>
              Settings
            </Text>
            <Text
              style={[
                typeScale.bodyMedium,
                {color: colors.onSurfaceVariant, marginTop: spacing.s},
              ]}
            >
              What Gaten reads, where it sends it, and how it looks.
            </Text>
          </View>
          <SettingsGroup bare title="Look up" style={{marginTop: spacing.xl}}>
            <SettingsRow
              surface
              icon={screenAccess ? "check_circle" : "shield"}
              title="Screen access"
              summary={
                screenAccess
                  ? "Circle any Japanese text on screen to look it up."
                  : "Gaten cannot read the screen until you turn it on."
              }
              value={screenAccess ? "On" : "Off"}
              onPress={screenAccess ? () => onOpenPage("lookup") : requestScreenAccess}
            />
            <SettingsRow
              surface
              icon="search"
              title="Screen lookup"
              summary="Floating button, Quick Settings tile and hardware key"
              onPress={() => onOpenPage("lookup")}
            />
            <SettingsRow
              surface
              icon="text_snippet"
              title="Text recognition"
              summary="Optional stylized-text model"
              value={recognitionValue(modelInstalled, modelId, catalog)}
              onPress={() => onOpenPage("recognition")}
            />
          </SettingsGroup>
          <SettingsGroup bare title="Study" style={{marginTop: spacing.xl}}>
            <SettingsRow
              surface
              icon="library_books"
              title="Anki cards"
              summary={ankiSummary(ankiStatus)}
              value={ankiStatus === "ready" ? ankiDeck : null}
              onPress={() => onOpenPage("anki")}
            />
            <SettingsRow
              surface
              icon="translate"
              title="WaniKani"
              summary="Kanji and vocabulary reference"
              value={wanikaniConnected ? "Connected" : "Not connected"}
              onPress={() => onOpenPage("wanikani")}
            />
          </SettingsGroup>
          <SettingsGroup bare title="App" style={{marginTop: spacing.xl}}>
            <SettingsRow
              surface
              icon="palette"
              title="Appearance"
              summary="Theme, palette and accent"
              value={`${theme.mode} · ${theme.accent}`}
              onPress={() => onOpenPage("appearance")}
            />
            <SettingsRow
              surface
              icon="info"
              title="About"
              summary="Offline, privacy, licences"
              value={versionName}
              onPress={() => onOpenPage("about")}
            />
          </SettingsGroup>
          <View style={{height: spacing.xxl}} />
        </View>
      </ScrollView>
      {disclosureDialog}
    </View>
  );
}