/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The recognition page's window onto the model state. The state itself lives in
 * `recognition-store.ts` — a module singleton, so a running download survives leaving the page —
 * and this hook only subscribes to it and re-reads the directory on every arrival.
 */
import { useSyncExternalStore } from "react";
import { recognitionStore } from "./recognition-store";
import type { RecognitionStoreState } from "./recognition-store";
import type { MangaOcrCatalogEntry } from "../../native/GatenRecognition";

/** The model state the recognition page renders, plus the moves it can make on it. */
export interface UseRecognition {
  state: RecognitionStoreState;
  /** Re-reads what is on the phone; call on every arrival and every resume. */
  refreshInstalled(): void;
  download(entry: MangaOcrCatalogEntry): void;
  cancelDownload(): void;
  importFiles(uris: string[]): void;
  installBundled(): void;
  removeModel(): void;
}

export function useRecognition(): UseRecognition {
  const state = useSyncExternalStore(
    recognitionStore.subscribe,
    recognitionStore.getSnapshot,
    recognitionStore.getSnapshot
  );
  return {
    state,
    refreshInstalled: recognitionStore.refreshInstalled,
    download: recognitionStore.download,
    cancelDownload: recognitionStore.cancelDownload,
    importFiles: recognitionStore.importFiles,
    installBundled: recognitionStore.installBundled,
    removeModel: recognitionStore.removeModel,
  };
}