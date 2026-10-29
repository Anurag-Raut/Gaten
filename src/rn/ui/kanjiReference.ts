/**
 * The kanji reference choice, shared by every surface that opens kanji details.
 *
 * The choice lives on the device (`GatenRtk.getReferenceSource`). This module keeps the last known
 * value in one place so the settings page, the word page, the lookup overlay and the review screen
 * all agree the moment it changes — a screen never has to guess from the previous source's content.
 * The initial value is WaniKani, which is what every device had before the choice existed; the
 * stored value replaces it as soon as the bridge answers.
 */
import {useEffect, useSyncExternalStore} from "react";
import * as GatenRtk from "../native/GatenRtk";
import type {KanjiReferenceSource} from "../native/GatenRtk";

export type {KanjiReferenceSource};

/** The source a device that never chose shows: WaniKani, exactly as before this setting existed. */
export const DEFAULT_KANJI_REFERENCE_SOURCE: KanjiReferenceSource = "wanikani";

let cached: KanjiReferenceSource = DEFAULT_KANJI_REFERENCE_SOURCE;
let loaded = false;
let inFlight: Promise<KanjiReferenceSource> | null = null;
const listeners = new Set<() => void>();

function announce(): void {
  for (const listener of listeners) {
    listener();
  }
}

/** The value the screens render now; WaniKani until the stored choice has been read. */
export function getKanjiReferenceSource(): KanjiReferenceSource {
  return cached;
}

/** Reads the stored choice once per runtime (and again after a failed read). */
export function loadKanjiReferenceSource(): Promise<KanjiReferenceSource> {
  if (loaded) {
    return Promise.resolve(cached);
  }
  if (inFlight == null) {
    inFlight = GatenRtk.getReferenceSource()
      .then((source) => {
        cached = source;
        loaded = true;
        announce();
        return source;
      })
      .catch(() => cached)
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
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
