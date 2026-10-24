/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Add-to-Anki state machine, ported from SearchViewModel's export half: where one entry's
 * button is, the configuration the answer belongs to, and the celebration cue for a note that was
 * just written.
 *
 * The state lives outside any component — an add under way must survive the reader navigating
 * away from the word — and every guard is the ViewModel's: Busy always wins, an answer whose
 * configuration no longer holds reads as Idle again, and a done entry is never written twice
 * under the same configuration.
 */
import {useSyncExternalStore} from "react";
import * as GatenAnki from "../../native/GatenAnki";
import type {AnkiPreferencesResult, AnkiStatus} from "../../native/GatenAnki";
import * as GatenHaptics from "../../native/GatenHaptics";
import type {SearchResult} from "../../native/GatenDict";

/** How long the "Added ✓" cue stays on the button after a note was written. */
export const CELEBRATION_MS = 1600;

/** How an export to AnkiDroid ended, for the button's final label. */
export type ExportOutcome = "added" | "duplicate";

/** Where the "Add to Anki" button of one entry is. */
export type ExportState =
  | {kind: "idle"}
  | {kind: "checking"}
  | {kind: "busy"}
  | {kind: "done"; outcome: ExportOutcome}
  | {kind: "failed"; message: string};

/** Where one entry's export is, and the configuration the answer belongs to. */
export interface ExportProgress {
  state: ExportState;
  settings: AnkiPreferencesResult;
  status: AnkiStatus;
}

/** The part of a dictionary entry an Anki card is built from. */
export interface ExportEntry {
  id: number;
  primary: string;
  kana: string[];
  glosses: string[];
  pos: string[];
}

export interface AnkiExportState {
  ankiStatus: AnkiStatus;
  ankiSettings: AnkiPreferencesResult;
  exports: Record<number, ExportProgress>;
  /** The entry whose add just wrote a note; cleared after CELEBRATION_MS. */
  celebrating: number | null;
}

/** What the word detail reads and calls: the state above plus the ViewModel's public calls. */
export interface AnkiExport extends AnkiExportState {
  exportFor: (entryId: number) => ExportState;
  prepareExport: (entry: ExportEntry) => Promise<void>;
  addToAnki: (entry: ExportEntry) => Promise<void>;
  refreshAnkiStatus: () => Promise<void>;
}

const EMPTY_SETTINGS: AnkiPreferencesResult = {
  deckName: "",
  tags: "",
  modelId: null,
  modelFields: [],
  fieldSources: [],
  front: "",
  back: "",
  css: "",
  validationError: null,
};

let state: AnkiExportState = {
  ankiStatus: "notInstalled",
  ankiSettings: EMPTY_SETTINGS,
  exports: {},
  celebrating: null,
};

const listeners = new Set<() => void>();

function set(patch: Partial<AnkiExportState>) {
  state = {...state, ...patch};
  for (const listener of listeners) {
    listener();
  }
}

function setExport(entryId: number, progress: ExportProgress) {
  set({exports: {...state.exports, [entryId]: progress}});
}

export function subscribeAnkiExport(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getAnkiExportState(): AnkiExportState {
  return state;
}

/**
 * Whether two saved configurations are the same one: the deck, note type, templates and mapping
 * the answer was made under. The validation verdict is derived, so it is not compared.
 */
function sameSettings(
  a: AnkiPreferencesResult,
  b: AnkiPreferencesResult,
): boolean {
  return (
    a.deckName === b.deckName &&
    a.tags === b.tags &&
    a.modelId === b.modelId &&
    a.front === b.front &&
    a.back === b.back &&
    a.css === b.css &&
    a.modelFields.length === b.modelFields.length &&
    a.modelFields.every((field, index) => field === b.modelFields[index]) &&
    a.fieldSources.length === b.fieldSources.length &&
    a.fieldSources.every((field, index) => field === b.fieldSources[index])
  );
}

/** Where the Add-to-Anki button of [entryId] is, under the configuration in force right now. */
export function exportStateFor(entryId: number): ExportState {
  const progress = state.exports[entryId];
  if (!progress) {
    return {kind: "idle"};
  }
  if (progress.state.kind === "busy") {
    return progress.state;
  }
  const current =
    sameSettings(progress.settings, state.ankiSettings) &&
    progress.status === state.ankiStatus;
  return current ? progress.state : {kind: "idle"};
}

/** Where the entry's export really is, whatever configuration it was made under. */
function rawExportState(entryId: number): ExportState {
  return state.exports[entryId]?.state ?? {kind: "idle"};
}

/** The glosses a flashcard carries, over its parts of speech — AnkiCard.from's shape. */
const MAX_GLOSSES = 5;

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

/** Builds the note fields exactly the way AnkiCard.from(entry) builds them in Kotlin. */
export function buildAnkiCard(entry: ExportEntry): {
  expression: string;
  reading: string;
  meaning: string;
} {
  const glosses = escapeHtml(entry.glosses.slice(0, MAX_GLOSSES).join("; "));
  const pos = escapeHtml(entry.pos.join(", "));
  const meaning =
    pos.length === 0
      ? glosses
      : glosses.length === 0
        ? `<small>${pos}</small>`
        : `${glosses}<br><small>${pos}</small>`;
  return {
    expression: escapeHtml(entry.primary),
    reading: escapeHtml(entry.kana[0] ?? ""),
    meaning,
  };
}

async function readConfig(): Promise<{
  status: AnkiStatus;
  settings: AnkiPreferencesResult;
}> {
  const [status, settings] = await Promise.all([
    GatenAnki.status(),
    GatenAnki.getPreferences(),
  ]);
  return {status, settings};
}

/**
 * Reads whether AnkiDroid can be written to, and — when it can — whether [entry] is already in
 * the configured deck, so the button opens saying so. An export in flight is left alone.
 */
export async function prepareExport(entry: ExportEntry): Promise<void> {
  const {status, settings} = await readConfig();
  set({ankiStatus: status, ankiSettings: settings});
  if (status !== "ready") {
    return;
  }
  const filtered = exportStateFor(entry.id);
  if (filtered.kind === "checking" || filtered.kind === "busy") {
    return;
  }
  if (rawExportState(entry.id).kind === "busy") {
    return;
  }
  const attempt: ExportProgress = {
    state: {kind: "checking"},
    settings,
    status,
  };
  setExport(entry.id, attempt);
  let duplicate = false;
  try {
    duplicate = await GatenAnki.isDuplicate(buildAnkiCard(entry));
  } catch {
    duplicate = false;
  }
  if (state.exports[entry.id] !== attempt) {
    return;
  }
  setExport(entry.id, {
    state: duplicate
      ? {kind: "done", outcome: "duplicate"}
      : {kind: "idle"},
    settings,
    status,
  });
}

/**
 * Adds [entry] to the deck the Anki settings name. The whole attempt runs against the store, so
 * it finishes — and its result is kept, keyed by the entry — however the reader moves on. A note
 * already in the deck is not written twice under the configuration in force.
 */
export async function addToAnki(entry: ExportEntry): Promise<void> {
  if (rawExportState(entry.id).kind === "busy") {
    return;
  }
  const {status, settings} = await readConfig();
  set({ankiStatus: status, ankiSettings: settings});
  if (exportStateFor(entry.id).kind === "done") {
    return;
  }
  const attempt: ExportProgress = {state: {kind: "busy"}, settings, status};
  setExport(entry.id, attempt);
  let next: ExportState;
  try {
    const result = await GatenAnki.addNote(buildAnkiCard(entry));
    next =
      result === "added"
        ? {kind: "done", outcome: "added"}
        : {kind: "done", outcome: "duplicate"};
  } catch (error) {
    next = {
      kind: "failed",
      message:
        error instanceof Error && error.message
          ? error.message
          : String(error),
    };
  }
  // Re-read, so that a configuration changed mid-flight is what the button is judged against.
  const refreshed = await readConfig();
  set({ankiStatus: refreshed.status, ankiSettings: refreshed.settings});
  if (state.exports[entry.id] !== attempt) {
    return;
  }
  setExport(entry.id, {state: next, settings, status});
  // The celebration fires only on the transition writing → added; duplicates stay silent.
  if (next.kind === "done" && next.outcome === "added") {
    celebrate(entry.id);
  }
}

function celebrate(entryId: number) {
  void GatenHaptics.play("confirm").catch(() => {});
  void GatenHaptics.play("added").catch(() => {});
  set({celebrating: entryId});
  setTimeout(() => {
    if (state.celebrating === entryId) {
      set({celebrating: null});
    }
  }, CELEBRATION_MS);
}

/** Re-reads access and settings after a permission grant; nothing is exported here. */
export async function refreshAnkiStatus(): Promise<void> {
  const {status, settings} = await readConfig();
  set({ankiStatus: status, ankiSettings: settings});
}

/**
 * The export state the word detail reads: the AnkiDroid status and settings, every entry's
 * button state under the current configuration, and the celebration cue.
 */
export function useAnkiExport(): AnkiExport {
  useSyncExternalStore(subscribeAnkiExport, getAnkiExportState);
  return {
    ankiStatus: state.ankiStatus,
    ankiSettings: state.ankiSettings,
    exports: state.exports,
    celebrating: state.celebrating,
    exportFor: exportStateFor,
    prepareExport,
    addToAnki,
    refreshAnkiStatus,
  };
}
