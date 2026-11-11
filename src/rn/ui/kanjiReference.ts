/**
 * The kanji reference choice, shared by every surface that opens kanji details.
 *
 * The choice lives on the device (`GatenRtk.getReferenceSource`). This module keeps the last known
 * value in one place so the settings page, the word page, the lookup overlay and the review screen
 * all agree the moment it changes — a screen never has to guess from the previous source's content.
 *
 * A device that never chose follows the WaniKani connection: WaniKani while an account is connected,
 * Remembering the Kanji when none is (it needs no account at all). The device makes that decision;
 * this module only keeps its answer, and re-reads it after connecting or disconnecting WaniKani.
 * [INITIAL_KANJI_REFERENCE_SOURCE] is what the screens show until the first answer arrives, never a
 * default of its own.
 */
import {useEffect, useSyncExternalStore} from "react";
import * as GatenRtk from "../native/GatenRtk";
import type {KanjiReferenceSource} from "../native/GatenRtk";

export type {KanjiReferenceSource};

/**
 * What the screens render before the device has answered. It is WaniKani because a connected
 * device — every device that had this setting before it existed — must not flash the other source;
 * a device without an account is corrected by the first read, which happens as screens mount.
 */
export const INITIAL_KANJI_REFERENCE_SOURCE: KanjiReferenceSource = "wanikani";

let cached: KanjiReferenceSource = INITIAL_KANJI_REFERENCE_SOURCE;
let loaded = false;
let inFlight: Promise<KanjiReferenceSource> | null = null;
const listeners = new Set<() => void>();

function announce(): void {
  for (const listener of listeners) {
    listener();
  }
}

/** The value the screens render now; the pre-read placeholder until the device has answered. */
export function getKanjiReferenceSource(): KanjiReferenceSource {
  return cached;
}

/** Reads the device and adopts its answer. A failed read leaves the last known value in force. */
function readFromDevice(): Promise<KanjiReferenceSource> {
  return GatenRtk.getReferenceSource()
    .then((source) => {
      cached = source;
      loaded = true;
      announce();
      return source;
    })
    .catch(() => cached);
}

/** Reads the choice once per runtime (and again after a failed read). */
export function loadKanjiReferenceSource(): Promise<KanjiReferenceSource> {
  if (loaded) {
    return Promise.resolve(cached);
  }
  if (inFlight == null) {
    inFlight = readFromDevice().finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

/**
 * Reads the device again. Connecting WaniKani makes WaniKani the default for a device that never
 * chose, and disconnecting it makes Remembering the Kanji the default; a stored choice reads back
 * unchanged either way. Call this wherever the connection itself changes.
 */
export function reloadKanjiReferenceSource(): Promise<KanjiReferenceSource> {
  return readFromDevice();
}

/**
 * Stores [source] and adopts what the device reports afterwards. A write that did not take leaves
 * the previous value in force rather than showing a choice the next restart would not honour.
 */
export async function setKanjiReferenceSource(
  source: KanjiReferenceSource
): Promise<KanjiReferenceSource> {
  const stored = await GatenRtk.setReferenceSource(source);
  cached = stored;
  loaded = true;
  announce();
  return stored;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** The selected source, live: a change in Settings re-renders every subscriber immediately. */
export function useKanjiReferenceSource(): KanjiReferenceSource {
  const source = useSyncExternalStore(
    subscribe,
    getKanjiReferenceSource,
    getKanjiReferenceSource
  );
  useEffect(() => {
    void loadKanjiReferenceSource();
  }, []);
  return source;
}

/**
 * Whether the kanji tiles under a word have anywhere to go. WaniKani tiles only work while a token
 * is stored; RTK tiles are answered from the bundled data, so they show with no account at all.
 */
export function showKanjiStrip(
  source: KanjiReferenceSource,
  wanikaniConfigured: boolean
): boolean {
  return source === "rtk" || wanikaniConfigured;
}

/** The label the kanji section and its tile actions carry for [source]. */
export function kanjiReferenceLabel(source: KanjiReferenceSource): string {
  return source === "rtk" ? "Remembering the Kanji" : "WaniKani kanji";
}
