/**
 * The shell bridge: the settings page an outside launch asked for, held by MainActivity until a
 * screen takes it, and the window's system-gesture insets.
 */
import { NativeModules } from "react-native";

/** A window inset rectangle, in dp. */
export interface SystemInsets {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

interface GatenShellNative {
  getSettingsPageRequest(): Promise<string | null>;
  consumeSettingsPageRequest(): Promise<void>;
  getSystemGestureInsets(): Promise<SystemInsets | null>;
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

/**
 * The window's system-gesture insets, in dp, or `null` before the window has laid out. Android 15's
 * edge-to-edge gesture navigation reports a zero navigation-bar inset, so this is what the safe-area
 * provider reads to keep bottom-padded content clear of the gesture handle.
 */
export function getSystemGestureInsets(): Promise<SystemInsets | null> {
  return GatenShell.getSystemGestureInsets();
}
