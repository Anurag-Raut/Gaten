/**
 * The lookup-sheet bridge: the RN sheet inside the native accessibility overlay.
 *
 * The overlay owns the window, the frozen capture, OCR and selection; the sheet inside it is this
 * app's own React Native UI, mounted by the overlay onto the application's React host. Native
 * publishes the whole state on every change (including the resolved entries, so the sheet never
 * re-searches), and JS calls the methods here to act on the session. Nothing touches the Activity.
 */
import {DeviceEventEmitter, NativeModules} from "react-native";
import type {SearchResult} from "./GatenDict";

/** Which surface the sheet is showing. */
export type LookupSheetMode = "sheet" | "search" | "empty";

/** One resolved scanned word and the dictionary entries the overlay found for it. */
export interface LookupSheetWord {
  surface: string;
  baseForm: string;
  context: string;
  entries: SearchResult[];
}

/** One complete sheet state, rebuilt by native on every change. */
export interface LookupSheetState {
  mode: LookupSheetMode;
  source: string;
  words: LookupSheetWord[];
  selected: number;
  query: string;
  emptyKind: "noText" | "noMatches" | null;
}

interface GatenLookupSheetNative {
  close(): void;
  selectAgain(): void;
  textEntryActive(active: boolean): void;
  ready(): void;
  openSettingsPage(page: string): void;
  getState(): Promise<Record<string, unknown>>;
}

const GatenLookupSheet = NativeModules.GatenLookupSheet as GatenLookupSheetNative;

/** The JS event carrying one raw state map. */
export const LOOKUP_SHEET_STATE_EVENT = "gaten.lookupSheetState";

/** Parses one raw native map into the typed state; a malformed map is an empty sheet, never a crash. */
export function parseState(raw: Record<string, unknown>): LookupSheetState {
  const mode =
    raw.mode === "search" || raw.mode === "empty" ? raw.mode : "sheet";
  let words: LookupSheetWord[] = [];
  if (typeof raw.words === "string" && raw.words.length > 0) {
    try {
      words = JSON.parse(raw.words) as LookupSheetWord[];
    } catch {
      words = [];
    }
  }
  const emptyKind =
    raw.emptyKind === "noText" || raw.emptyKind === "noMatches" ? raw.emptyKind : null;
  return {
    mode,
    source: typeof raw.source === "string" ? raw.source : "",
    words,
    selected: typeof raw.selected === "number" ? raw.selected : 0,
    query: typeof raw.query === "string" ? raw.query : "",
    emptyKind,
  };
}

/** Close the lookup and dismiss the overlay. */
export function close(): void {
  GatenLookupSheet.close();
}

/** Discard the current result and return to selection. */
export function selectAgain(): void {
  GatenLookupSheet.selectAgain();
}

/** Tell the overlay whether the sheet's own field is editing, for focus-loss attribution. */
export function setTextEntryActive(active: boolean): void {
  GatenLookupSheet.textEntryActive(active);
}

/** Tell the overlay the sheet has drawn its first frame. */
export function ready(): void {
  GatenLookupSheet.ready();
}

/** Open the app's WaniKani settings page from the sheet's kanji panel. */
export function openSettingsPage(page: string): void {
  GatenLookupSheet.openSettingsPage(page);
}

/** The current state, so a mount that missed an event still renders the right thing. */
export function getState(): Promise<Record<string, unknown>> {
  return GatenLookupSheet.getState();
}

/** One new raw state from native. */
export function addLookupSheetStateListener(
  listener: (state: Record<string, unknown>) => void,
): {remove: () => void} {
  return DeviceEventEmitter.addListener(LOOKUP_SHEET_STATE_EVENT, listener);
}
