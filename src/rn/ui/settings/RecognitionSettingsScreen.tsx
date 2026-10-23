/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The optional manga-ocr model: what is installed, the models the catalog offers, and the ways
 * onto the phone.
 *
 * There is no switch: manga-ocr is the reader as soon as its model is on the phone, and with none
 * installed the built-in reader (ML Kit) reads the screen — the Help dialog says so, because the
 * sentence is longer than a row. Downloading is one of the app's two network uses, and it only
 * happens after the size has been shown and confirmed; importing opens a document picker for a
 * phone with no network or a model fetched elsewhere. Both end in the same directory, and Remove
 * takes the model off again.
 *
 * All of that state lives in the recognition store, outside any screen: leaving the page, opening
 * another one or the phone turning neither cancels a running download nor throws away what it has
 * done.
 */
import React, {useEffect, useState} from "react";
import {AppState, Text, View} from "react-native";
import * as GatenRecognition from "../../native/GatenRecognition";
import type {MangaOcrCatalogEntry} from "../../native/GatenRecognition";
import {Button} from "../components";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {useRecognition} from "./useRecognition";
import {
  DialogShell,
  SettingsGroup,
  SettingsPageScaffold,
  SettingsRow,
  SettingsTile,
} from "./settings-components";

const HELP_PARAGRAPHS = [
  "Without a model, ML Kit reads the screen. manga-ocr helps with stylized lettering; unreadable lines keep ML Kit's text.",
  "Recognition runs on this device. Import model files offline, or download after confirming the size.",
];

/** "manga-ocr (int8) installed (117 MB)", or just what is there when the entry is not known. */
function statusText(
  installed: boolean,
  installedId: string | null,
  sizeBytes: number,
  catalog: MangaOcrCatalogEntry[]
): string {
  const size = shortFileSize(sizeBytes);
  const label = catalog.find((entry) => entry.id === installedId)?.label ?? null;
  if (!installed) {
    return "No model installed";
  }
  return label != null
    ? `${label} installed (${size})`
    : `Installed model (${size})`;
}

/** "42% · decoder.onnx", or just the file name when the entry declares no sizes. */
function progressText(progress: {fraction: number | null; fileName: string} | null): string {
  if (progress == null) {
    return "Starting…";
  }
  if (progress.fraction == null) {
    return progress.fileName;
  }
  return `${Math.round(progress.fraction * 100)}% · ${progress.fileName}`;
}

/** A short file size, the way the Compose screen formatted one (1.2 MB, 340 kB). */
function shortFileSize(bytes: number): string {
  if (bytes >= 1_000_000) {
    return `${(bytes / 1_000_000).toFixed(bytes >= 10_000_000 ? 0 : 1)} MB`;
  }
  if (bytes >= 1_000) {
    return `${Math.round(bytes / 1_000)} kB`;
  }
  return `${bytes} B`;
}

export function RecognitionSettingsScreen({onClose}: {onClose: () => void}) {
  const {state, refreshInstalled, download, cancelDownload, importFiles, removeModel} =
    useRecognition();
  const {colors} = useTheme();
  // The entry the confirm dialog is asking about. The download itself belongs to the store.
  const [confirmDownload, setConfirmDownload] = useState<MangaOcrCatalogEntry | null>(null);
  const [metered, setMetered] = useState(false);
  const busy = state.busy;

  // The model directory can change while this page is not looking — a debug build copies its
  // bundled model out on the first lookup, which the accessibility service runs with the app in
  // the background — so what is installed is re-read on every arrival and every foregrounding.
  // The store drops the ask while a download or an import owns the directory.
  useEffect(() => {
    refreshInstalled();
    const subscription = AppState.addEventListener("change", (appState) => {
      if (appState === "active") refreshInstalled();
    });
    return () => subscription.remove();
  }, [refreshInstalled]);

  useEffect(() => {
    if (confirmDownload == null) return;
    let cancelled = false;
    void GatenRecognition.isMetered().then((v) => {
      if (!cancelled) setMetered(v);
    });
    return () => {
      cancelled = true;
    };
  }, [confirmDownload]);

  return (
    <SettingsPageScaffold
      title="Text recognition"
      onClose={onClose}
      help={HELP_PARAGRAPHS}
    >
      <SettingsGroup title="Model" style={{marginTop: spacing.s}}>
        <SettingsRow
          icon={state.installed ? "check_circle" : "text_snippet"}
          title="Text recognition"
          summary="ML Kit reads the screen; manga-ocr is optional and is not bundled in release builds."
          value={statusText(state.installed, state.installedId, state.sizeBytes, state.catalog)}
        />
      </SettingsGroup>

      <SettingsGroup title="Available model" style={{marginTop: spacing.xl}}>
        {state.catalog.map((entry) => {
          const installed = state.installed && state.installedId === entry.id;
          return (
            <SettingsRow
              key={entry.id}
              icon={installed ? "check_circle" : "download"}
              title={entry.label}
              value={installed ? "Installed" : `~${entry.approxSizeMb} MB`}
              disabled={!installed && busy}
              onPress={installed ? undefined : () => setConfirmDownload(entry)}
            />
          );
        })}
      </SettingsGroup>

      {state.downloading != null && (
        <View style={{marginTop: spacing.l}}>
          <SettingsTile>
            <View style={{paddingVertical: spacing.m}}>
              <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>
                {`Downloading ${state.downloading.label}…`}
              </Text>
              <View
                style={{
                  marginTop: spacing.s,
                  height: 4,
                  borderRadius: 2,
                  backgroundColor: colors.surfaceVariant,
                  overflow: "hidden",
                }}
              >
                {state.progress?.fraction != null && (
                  <View
                    style={{
                      height: 4,
                      borderRadius: 2,
                      backgroundColor: colors.accent,
                      width: `${Math.round(state.progress.fraction * 100)}%` as `${number}%`,
                    }}
                  />
                )}
              </View>
              <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}>
                {progressText(state.progress)}
              </Text>
            </View>
            <SettingsRow
              icon="close"
              title="Cancel download"
              onPress={() => cancelDownload()}
            />
          </SettingsTile>
        </View>
      )}

      <View style={{marginTop: spacing.xl}}>
        <SettingsTile>
          <SettingsRow
            icon="file_open"
            title="Import model files"
            disabled={busy}
            onPress={() => {
              void GatenRecognition.pickModelFiles().then((uris) => {
                if (uris.length > 0) importFiles(uris);
              });
            }}
          />
        </SettingsTile>
        {state.installed && (
          <SettingsTile>
            <SettingsRow
              icon="delete"
              title="Remove model"
              disabled={busy}
              onPress={() => removeModel()}
            />
          </SettingsTile>
        )}
      </View>

      <View style={{height: spacing.xl}} />
      {confirmDownload != null && (
        <ConfirmDownloadDialog
          entry={confirmDownload}
          metered={metered}
          onConfirm={() => {
            download(confirmDownload);
            setConfirmDownload(null);
          }}
          onDismiss={() => setConfirmDownload(null)}
        />
      )}
    </SettingsPageScaffold>
  );
}

/**
 * The size is shown before anything is fetched, and on mobile data the wording says so: the
 * download must never start as a surprise on a metered connection.
 */
function ConfirmDownloadDialog({
  entry,
  metered,
  onConfirm,
  onDismiss,
}: {
  entry: MangaOcrCatalogEntry;
  metered: boolean;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  const {colors} = useTheme();
  return (
    <DialogShell
      title={`Download ${entry.label}?`}
      onDismiss={onDismiss}
      actions={
        <>
          <Button variant="text" label="Cancel" onPress={onDismiss} />
          <Button variant="text" label="Download" onPress={onConfirm} />
        </>
      }
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant}]}>
        {metered
          ? `You are on mobile data. About ${entry.approxSizeMb} MB will be downloaded from huggingface.co — use Wi-Fi if you can.`
          : `About ${entry.approxSizeMb} MB will be downloaded from huggingface.co. Use Wi-Fi if you can.`}
      </Text>
    </DialogShell>
  );
}
