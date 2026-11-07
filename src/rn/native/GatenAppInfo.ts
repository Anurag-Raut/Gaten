/**
 * The small facts about the app the settings pages put on screen: the version, the dictionary's
 * schema version, whether lookup mode is on, and how far the first-run setup flow has got.
 */
import { NativeModules } from "react-native";

interface GatenAppInfoNative {
  getAppVersion(): Promise<string>;
  getDictionarySchemaVersion(): Promise<number>;
  isLookupModeActive(): Promise<boolean>;
  getOnboardingStep(): Promise<number>;
  setOnboardingStep(step: number): Promise<void>;
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
 * How far the first-run setup flow has got: 0, 1 or 2 is the page being shown and 3 means it is
 * finished. A device that has never stored it answers 0, so the flow runs on the first launch.
 */
export function getOnboardingStep(): Promise<number> {
  return GatenAppInfo.getOnboardingStep();
}

/**
 * Stores the onboarding step — the page being shown, or 3 once the flow is finished. The shell
 * writes the next step before it shows the next page, so a page on screen is never ahead of what
 * is stored.
 */
export function setOnboardingStep(step: number): Promise<void> {
  return GatenAppInfo.setOnboardingStep(step);
}
