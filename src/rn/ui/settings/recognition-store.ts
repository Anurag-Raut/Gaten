/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The state of the optional manga-ocr model, held at the app-shell level rather than in any
 * screen: a running download survives leaving the page, opening another one, or the phone
 * turning — the same way the Compose shell's RecognitionViewModel outlived its destinations.
 *
 * The screens subscribe through `useRecognition`; this store is the only writer, and every
 * mutation answers a toast with the same sentence the Compose screen showed.
 */
import { toast } from "../../native/GatenPlatform";
import * as Recognition from "../../native/GatenRecognition";
import type { MangaOcrCatalogEntry } from "../../native/GatenRecognition";

/** How far the download in flight is, exactly as the progress events carry it. */
export interface RecognitionProgressState {
  /** 0..1, or null until the first file's total size is known. */
  fraction: number | null;
  fileName: string;
}

/** Everything the recognition page renders. */
export interface RecognitionStoreState {
  installed: boolean;
  installedId: string | null;
  sizeBytes: number;
  /** The entry being fetched, while one is. */
  downloading: MangaOcrCatalogEntry | null;
  progress: RecognitionProgressState | null;
  /** True while the model directory is being written to or cleared: one mutation at a time. */
  busy: boolean;
  catalog: MangaOcrCatalogEntry[];
}

let state: RecognitionStoreState = {
  installed: false,
  installedId: null,
  sizeBytes: 0,
  downloading: null,
  progress: null,
  busy: false,
  catalog: [],
};

const listeners = new Set<() => void>();

function notify() {
  for (const listener of listeners) listener();
}

function patch(partial: Partial<RecognitionStoreState>) {
  state = { ...state, ...partial };
  notify();
}

/** Reads what is on the phone; a running mutation re-reads the directory itself when it ends. */
async function readInstalled() {
  try {
    const installed = await Recognition.getInstalled();
    patch({
      installed: installed.installed,
      installedId: installed.catalogId,
      sizeBytes: installed.sizeBytes,
    });
  } catch {
    // The directory could not be read; the page keeps what it last knew.
  }
}

async function ensureCatalog() {
  if (state.catalog.length > 0) return;
  try {
    patch({ catalog: await Recognition.getCatalog() });
  } catch {
    // No catalog yet; the page shows no rows rather than wrong ones.
  }
}

/** The one mutation at a time: the second ask while the directory is owned is dropped. */
async function mutate(run: () => Promise<void>) {
  if (state.busy) return;
  patch({ busy: true });
  try {
    await run();
  } finally {
    patch({ busy: false });
  }
}

/** Progress events arrive while the download runs, wherever the reader is in the app. */
Recognition.onRecognitionProgress((event) => {
  if (state.downloading == null) return;
  const fraction =
    typeof event.fraction === "number" && Number.isFinite(event.fraction)
      ? Math.min(Math.max(event.fraction, 0), 1)
      : null;
  patch({ progress: { fraction, fileName: event.fileName } });
});

export const recognitionStore = {
  subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => listeners.delete(listener);
  },

  getSnapshot(): RecognitionStoreState {
    return state;
  },

  /** Called on every arrival to the page and every resume: the directory can change unseen. */
  refreshInstalled() {
    if (state.busy) return;
    void readInstalled();
    void ensureCatalog();
  },

  /** Fetches `entry`, whose size the reader has already confirmed. */
  download(entry: MangaOcrCatalogEntry) {
    void mutate(async () => {
      patch({ downloading: entry, progress: null });
      let outcome: Recognition.RecognitionOutcome;
      try {
        outcome = await Recognition.download(entry.id);
      } catch (e) {
        outcome = { kind: "failed", reason: String(e) };
      }
      patch({ downloading: null, progress: null });
      // Read back rather than assumed: a cancelled or failed download leaves whatever was
      // installed before it exactly as it was.
      await readInstalled();
      switch (outcome.kind) {
        case "installed":
          void toast(`${entry.label} installed`);
          break;
        case "cancelled":
          void toast("Download cancelled");
          break;
        case "failed":
          void toast(`Download failed: ${outcome.reason}`);
          break;
        default:
          break;
      }
    });
  },

  /** Asks the download in flight to stop. */
  cancelDownload() {
    void Recognition.cancelDownload().catch(() => undefined);
  },

  /**
   * Copies the picked files in. A picker that came back while a download was running, or a
   * second tap, is dropped rather than queued: the model directory is written by one thing at
   * a time.
   */
  importFiles(uris: string[]) {
    if (uris.length === 0) return;
    void mutate(async () => {
      let done = false;
      try {
        const outcome = await Recognition.importFiles(uris);
        done = outcome.kind === "imported" ? outcome.done : false;
      } catch {
        done = false;
      }
      await readInstalled();
      void toast(
        done
          ? "manga-ocr model installed"
          : "Import failed: encoder, decoder and vocab files are all needed"
      );
    });
  },

  /** Takes the model off the phone; nothing else about the app changes. */
  removeModel() {
    void mutate(async () => {
      let removed = false;
      try {
        const outcome = await Recognition.removeModel();
        removed = outcome.kind === "removed" ? outcome.removed : false;
      } catch {
        removed = false;
      }
      await readInstalled();
      void toast(removed ? "Model removed" : "Could not remove the model files");
    });
  },
};
