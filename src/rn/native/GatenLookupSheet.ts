/**
 * The lookup-sheet bridge: the RN sheet inside the native accessibility overlay.
 *
 * The overlay owns the window, the frozen capture, OCR and selection; the sheet inside it is this
 * app's own React Native UI, mounted by the overlay onto the application's React host. JS calls the
 * methods here to act on the session, and listens for `GatenLookupSheetState` for each new state
 * native publishes. Nothing in this file touches the Activity.
 */
import {DeviceEventEmitter, NativeModules} from "react-native";

/** Which surface the sheet is showing: the result panel, or the shared dictionary search. */
export type LookupSheetMode = "sheet" | "search";

/** How one scanned word is being presented inside the sheet. */
export interface LookupSheetWord {
  /** The OCR surface that was selected. */
  surface: string;
  /** The dictionary entry id it resolved to, when it resolved. */
  entryId: number | null;
}

/**
 * One complete sheet state. Native rebuilds and republishes the whole map on every change, so the
 * RN side never has to merge partial updates.
 */
export interface LookupSheetState {
  mode: LookupSheetMode;
  /** The accepted OCR line, for provenance. */
  source: string;
  /** The words the selection resolved to, most likely first. */
  words: LookupSheetWord[];
  /** The word index the tabs currently show. */
  selected: number;
  /** The query in the shared search field, when search is open. */
  query: string;
}

interface GatenLookupSheetNative {
  close(): void;
  openSearch(): void;
  selectAgain(): void;
  textEntryActive(active: boolean): void;
  ready(): void;
  getState(): Promise<Record<string, unknown>>;
}

const GatenLookupSheet = NativeModules.GatenLookupSheet as GatenLookupSheetNative;

/** The JS event carrying one [LookupSheetState]. */
export const LOOKUP_SHEET_STATE_EVENT = "gaten.lookupSheetState";

/** Close the lookup and dismiss the overlay. */
export function close(): void {
  GatenLookupSheet.close();
}

/** Open the shared dictionary search inside the same surface. */
export function openSearch(): void {
  GatenLookupSheet.openSearch();
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

/** The current state, so a mount that missed an event still renders the right thing. */
export function getState(): Promise<Record<string, unknown>> {
  return GatenLookupSheet.getState();
}

/** One new state from native. */
export function addLookupSheetStateListener(
  listener: (state: Record<string, unknown>) => void,
): {remove: () => void} {
  return DeviceEventEmitter.addListener(LOOKUP_SHEET_STATE_EVENT, listener);
}
