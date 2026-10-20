/**
 * The small facts about the app the settings pages put on screen: the version, the dictionary's
 * schema version, the optional manga-ocr model, and the accessibility service's state.
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
  isScreenAccessEnabled(): Promise<boolean>;
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
 * Whether Gaten's accessibility service is enabled in the system's settings — granted outside the
 * app, so re-check on every resume rather than caching.
 */
export function isScreenAccessEnabled(): Promise<boolean> {
  return GatenAppInfo.isScreenAccessEnabled();
}
