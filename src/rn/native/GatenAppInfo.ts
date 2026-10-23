/**
 * The small facts about the app the settings pages put on screen: the version, the dictionary's
 * schema version, and whether lookup mode is on.
 */
import { NativeModules } from "react-native";

interface GatenAppInfoNative {
  getAppVersion(): Promise<string>;
  getDictionarySchemaVersion(): Promise<number>;
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

/**
 * Whether lookup mode is on: Gaten's accessibility service is enabled in the system's own
 * settings. That grant is given outside the app, so re-check on every resume rather than caching.
 */
export function isLookupModeActive(): Promise<boolean> {
  return GatenAppInfo.isLookupModeActive();
}
