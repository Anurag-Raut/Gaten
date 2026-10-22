/**
 * The small facts about the app the settings pages put on screen: the version, the dictionary's
 * schema version, the optional manga-ocr model, and whether lookup mode is on.
 */
import { NativeModules } from "react-native";

/** What is on the phone of the optional manga-ocr model, read fresh on every ask. */
export interface MangaOcrState {
  installed: boolean;
  /** The catalog entry the installed model came from, when it is known. */
  catalogId: string | null;
  /** Total size of the installed files in bytes, 0 when nothing is installed. */
  sizeBytes: number;
}

interface GatenAppInfoNative {
  getAppVersion(): Promise<string>;
  getDictionarySchemaVersion(): Promise<number>;
  getMangaOcrState(): Promise<MangaOcrState>;
  isLookupModeActive(): Promise<boolean>;
}

const GatenAppInfo = NativeModules.GatenAppInfo as GatenAppInfoNative;

/** The app's own version name. */
export function getAppVersion(): Promise<string> {
  return GatenAppInfo.getAppVersion();
}

/** The dictionary schema the bundled database was built for (`PRAGMA user_version`). */
export function getDictionarySchemaVersion(): Promise<number> {
  return GatenAppInfo.getDictionarySchemaVersion();
}

/** Whether the optional manga-ocr model is installed, and what it is. */
export function getMangaOcrState(): Promise<MangaOcrState> {
  return GatenAppInfo.getMangaOcrState();
}

/**
 * Whether lookup mode is on: Gaten's accessibility service is enabled in the system's own
 * settings. That grant is given outside the app, so re-check on every resume rather than caching.
 */
export function isLookupModeActive(): Promise<boolean> {
  return GatenAppInfo.isLookupModeActive();
}
