/**
 * The small facts about the app the settings pages put on screen: the version, the dictionary's
 * schema version, and whether lookup mode is on.
 */
import { NativeModules } from "react-native";

/**
 * Lookup mode's whole state: whether Android's accessibility grant is on right now, whether it has
 * ever been on for this install, and whether Home's reminder for it has been dismissed.
 *
 * `everEnabled` is monotonic — the service records it where the grant is really on, and nothing
 * writes it back — so turning lookup mode off never makes the reminder eligible again.
 */
export interface LookupStatus {
  enabled: boolean;
  everEnabled: boolean;
  reminderDismissed: boolean;
}

interface GatenAppInfoNative {
  getAppVersion(): Promise<string>;
  getDictionarySchemaVersion(): Promise<number>;
  isLookupModeActive(): Promise<boolean>;
  getLookupStatus(): Promise<LookupStatus>;
  dismissLookupReminder(): Promise<void>;
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

/**
 * Lookup mode's state and history in one read, for Home's header and its reminder. `enabled` is
 * the platform's own enabled-services list, re-read on every ask rather than cached; the history
 * flags come from the app's settings file, where the accessibility service writes them.
 */
export function getLookupStatus(): Promise<LookupStatus> {
  return GatenAppInfo.getLookupStatus();
}

/**
 * Dismisses Home's lookup-mode reminder permanently: the flag is stored in the app's settings file,
 * so the reminder stays away across restarts.
 */
export function dismissLookupReminder(): Promise<void> {
  return GatenAppInfo.dismissLookupReminder();
}
