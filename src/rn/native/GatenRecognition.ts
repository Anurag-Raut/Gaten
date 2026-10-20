/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The optional manga-ocr model bridge: what is on the phone, the catalog it can be fetched
 * from, and the ways onto the phone — download, document picker, or removal.
 *
 * The download itself runs in Kotlin, carried by the module rather than by the screen, so
 * leaving the page neither cancels it nor throws away what it has done. Progress arrives on the
 * `gaten.recognitionProgress` event as `{fraction, fileName}`; `fraction` is null until the
 * first file's total size is known.
 */
import { DeviceEventEmitter, EmitterSubscription, NativeModules } from "react-native";
import type { MangaOcrState } from "./GatenAppInfo";

/** One entry of the static model catalog, as the rows render it. */
export interface MangaOcrCatalogEntry {
  id: string;
  label: string;
  description: string;
  approxSizeMb: number;
}

/** Where the model sits right now, read fresh on every ask. */
export type RecognitionInstalledState = {
  installed: boolean;
  catalogId: string | null;
  sizeBytes: number;
};

/** What one long operation on the model directory ended as. */
export type RecognitionOutcome =
  | { kind: "installed" }
  | { kind: "cancelled" }
  | { kind: "failed"; reason: string }
  | { kind: "busy" }
  | { kind: "imported"; done: boolean }
  | { kind: "removed"; removed: boolean };


interface GatenRecognitionNative {
  getInstalled(): Promise<RecognitionInstalledState>;
  getCatalog(): Promise<MangaOcrCatalogEntry[]>;
  download(catalogId: string): Promise<RecognitionOutcome>;
  cancelDownload(): Promise<boolean>;
  pickModelFiles(): Promise<string[]>;
  importFiles(uris: string[]): Promise<RecognitionOutcome>;
  removeModel(): Promise<RecognitionOutcome>;
  isMetered(): Promise<boolean>;
}

const GatenRecognition = NativeModules.GatenRecognition as GatenRecognitionNative;

/** The model directory's state, re-read on every call. */
export function getInstalled(): Promise<RecognitionInstalledState> {
  return GatenRecognition.getInstalled();
}

/** The entries the catalog offers, with the sizes the confirm dialog shows. */
export function getCatalog(): Promise<MangaOcrCatalogEntry[]> {
  return GatenRecognition.getCatalog();
}

/**
 * Fetches one catalog entry. The size was already shown and confirmed by the caller; the
 * promise answers only when the whole download ends. One mutation runs at a time — a second
 * ask while the directory is owned resolves `{kind: "busy"}`.
 */
export function download(catalogId: string): Promise<RecognitionOutcome> {
  return GatenRecognition.download(catalogId);
}

/** Asks the download in flight to stop; nothing is installed and the staged files are deleted. */
export function cancelDownload(): Promise<boolean> {
  return GatenRecognition.cancelDownload();
}

/** Opens the document picker for the model files; resolves the chosen URIs. */
export function pickModelFiles(): Promise<string[]> {
  return GatenRecognition.pickModelFiles();
}

/**
 * Copies the picked files in, each recognised by the role its name stands for; the answer
 * tells whether the model became complete.
 */
export function importFiles(uris: string[]): Promise<RecognitionOutcome> {
  return GatenRecognition.importFiles(uris);
}

/** Takes the model off the phone; `removed` tells whether the directory really went. */
export function removeModel(): Promise<RecognitionOutcome> {
  return GatenRecognition.removeModel();
}

/** True when a download would run on mobile data. */
export function isMetered(): Promise<boolean> {
  return GatenRecognition.isMetered();
}

/** Subscribes to the download progress events; `fraction` is null until sizes are known. */
export function onRecognitionProgress(
  listener: (progress: { fraction: number | null; fileName: string }) => void
): { remove: () => void } {
  const subscription: EmitterSubscription = DeviceEventEmitter.addListener(
    "gaten.recognitionProgress",
    listener
  );
  return { remove: () => subscription.remove() };
}
