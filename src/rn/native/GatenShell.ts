/**
 * The shell bridge: the settings page an outside launch asked for, held by MainActivity until a
 * screen takes it.
 */
import { NativeModules } from "react-native";

interface GatenShellNative {
  getSettingsPageRequest(): Promise<string | null>;
  consumeSettingsPageRequest(): Promise<void>;
}

const GatenShell = NativeModules.GatenShell as GatenShellNative;

/** The pending request, or null when there is none. */
export function getSettingsPageRequest(): Promise<string | null> {
  return GatenShell.getSettingsPageRequest();
}

/** Clears the pending request: the screen that read it has navigated. */
export function consumeSettingsPageRequest(): Promise<void> {
  return GatenShell.consumeSettingsPageRequest();
}
