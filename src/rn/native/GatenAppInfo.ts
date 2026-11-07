/**
 * The small facts about the app the settings pages put on screen: the version, the dictionary's
 * schema version, whether lookup mode is on, and the support popup's own state.
 */
import { NativeModules } from "react-native";

interface GatenAppInfoNative {
  getAppVersion(): Promise<string>;
  getDictionarySchemaVersion(): Promise<number>;
  isLookupModeActive(): Promise<boolean>;
  getSupportPromptState(): Promise<SupportPromptState>;
  recordSupportAppOpen(): Promise<number>;
  dismissSupportPrompt(permanent: boolean): Promise<void>;
}

/** The support popup's own state, as the Kotlin `getSupportPromptState` map reports it. */
export interface SupportPromptState {
  /** How many times the app has been opened, across launches. */
  openCount: number;
  /** True once the reader chose "Don't show again". */
  never: boolean;
  /** Epoch millis before which "Not now" holds the popup back; 0 until it is chosen. */
  nextEligibleAt: number;
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
 * The support popup's stored state: the app-open count, whether it was silenced for good, and the
 * earliest moment it may be offered again. Read on each launch, since only this device's own
 * dismissals move it.
 */
export function getSupportPromptState(): Promise<SupportPromptState> {
  return GatenAppInfo.getSupportPromptState();
}

/** Counts one app open and resolves the running total. */
export function recordSupportAppOpen(): Promise<number> {
  return GatenAppInfo.recordSupportAppOpen();
}

/** Records a dismissal: `permanent` is "Don't show again", otherwise "Not now". */
export function dismissSupportPrompt(permanent: boolean): Promise<void> {
  return GatenAppInfo.dismissSupportPrompt(permanent);
}
