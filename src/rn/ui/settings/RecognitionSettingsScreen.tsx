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
import {Button, Card, Icon} from "../components";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {useRecognition} from "./useRecognition";
import {
  Chip,
  DialogShell,
  OutlinedButton,
  RowIcon,
  SettingsGroup,
  SettingsPageScaffold,
} from "./settings-components";

const HELP_PARAGRAPHS = [
  "The built-in reader (ML Kit) is reading your screen. A model below makes the stylized lettering it misreads readable.",
  "ML Kit finds the lines on screen; manga-ocr reads them, including the stylized fonts it misreads — outlined video-thumbnail text, hand-lettered manga lettering. Any line it cannot read keeps ML Kit's text. Everything runs on the device, and a model already on your phone can be imported instead of downloaded.",
  "The download is one of the two network uses Gaten has, and it only ever starts from this page.",
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

  const container = state.installed ? colors.primaryContainer : colors.surface;
  const content = state.installed ? colors.onPrimaryContainer : colors.onSurface;
  const valueColor = state.installed ? colors.onPrimaryContainer : colors.onSurfaceVariant;

  return (
    <SettingsPageScaffold
      title="Text recognition"
      onClose={onClose}
      help={HELP_PARAGRAPHS}
    >
      <View style={{marginTop: spacing.s}}>
        <Card style={{backgroundColor: container}}>
          <View style={{padding: spacing.xl, flexDirection: "row", alignItems: "center"}}>
            <Icon name="memory" size={20} color={content} />
            <Text style={[typeScale.bodyLarge, {color: content, marginStart: spacing.s}]}>
              Text recognition
            </Text>
            <Text
              style={[
                typeScale.labelMedium,
                {color: valueColor, flex: 1, textAlign: "right", marginStart: spacing.m},
              ]}
            >
              {statusText(state.installed, state.installedId, state.sizeBytes, state.catalog)}
            </Text>
          </View>
        </Card>
      </View>

      <SettingsGroup title="Models" style={{marginTop: spacing.xl}}>
        {state.catalog.map((entry, index) => (
          <React.Fragment key={entry.id}>
            <ModelRow
              entry={entry}
              installed={state.installed && state.installedId === entry.id}
              busy={busy}
              onDownload={() => setConfirmDownload(entry)}
            />
          </React.Fragment>
        ))}
      </SettingsGroup>

      {state.downloading != null && (
        <View style={{marginTop: spacing.l}}>
          <Card>
            <View style={{padding: spacing.xl}}>
              <Text style={[typeScale.bodyLarge, {color: colors.onSurface}]}>
                {`Downloading ${state.downloading.label}…`}
              </Text>
              <View
                style={{
                  marginTop: spacing.s,
                  height: 4,
                  borderRadius: 2,
                  backgroundColor: colors.surfaceContainerHighest,
                  overflow: "hidden",
                }}
              >
                {state.progress?.fraction != null && (
                  <View
                    style={{
                      height: 4,
                      borderRadius: 2,
                      backgroundColor: colors.primary,
                      width: `${Math.round(state.progress.fraction * 100)}%` as `${number}%`,
                    }}
                  />
                )}
              </View>
              <View style={{flexDirection: "row", alignItems: "center", marginTop: spacing.s}}>
                <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, flex: 1}]}>
                  {progressText(state.progress)}
                </Text>
                <Button
                  variant="text"
                  label="Cancel"
                  onPress={() => cancelDownload()}
                />
              </View>
            </View>
          </Card>
        </View>
      )}

      <View style={{marginTop: spacing.xl, flexDirection: "row", gap: spacing.s}}>
        <OutlinedButton
          label="Import model files"
          disabled={busy}
          onPress={() => {
            void GatenRecognition.pickModelFiles().then((uris) => {
              if (uris.length > 0) importFiles(uris);
            });
          }}
        />
        {state.installed && (
          <OutlinedButton label="Remove" disabled={busy} onPress={() => removeModel()} />
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
 * One catalog entry: what it is, how big it is, and the one thing that can be done with it. The
 * size is the row's value on the right of the name — the one fact that makes two entries
 * comparable.
 */
function ModelRow({
  entry,
  installed,
  busy,
  onDownload,
}: {
  entry: MangaOcrCatalogEntry;
  installed: boolean;
  busy: boolean;
  onDownload: () => void;
}) {
  const {colors} = useTheme();
  return (
    <View style={{paddingHorizontal: spacing.l, paddingVertical: spacing.m, flexDirection: "row"}}>
      <RowIcon name="download" />
      <View style={{flex: 1, marginStart: spacing.m}}>
        <View style={{flexDirection: "row", alignItems: "center"}}>
          <Text style={[typeScale.bodyLarge, {color: colors.onSurface, flex: 1}]}>{entry.label}</Text>
          <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant, marginStart: spacing.s}]}>
            {`~${entry.approxSizeMb} MB`}
          </Text>
        </View>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          {entry.description}
        </Text>
        <View style={{marginTop: spacing.s, alignSelf: "flex-start"}}>
          {installed ? (
            <Chip label="Installed" />
          ) : (
            <OutlinedButton label="Download" disabled={busy} onPress={onDownload} />
          )}
        </View>
      </View>
    </View>
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