/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The platform bits the settings pages reach for directly: the system screen the accessibility
 * permission lives behind, opening a URL in the browser, the clipboard, and toasts.
 *
 * Every opener answers `false` when nothing on the device can take the intent — the screens
 * show a toast instead of pretending the move landed.
 */
import { NativeModules } from "react-native";

interface GatenPlatformNative {
  openAccessibilitySettings(): Promise<boolean>;
  openNotificationListenerSettings(): Promise<boolean>;
  /** True when Gaten is an enabled notification listener (the media-pause permission). */
  isNotificationAccessEnabled(): Promise<boolean>;
  openUrl(url: string): Promise<boolean>;
  copyToClipboard(text: string): Promise<boolean>;
  toast(message: string): Promise<boolean>;
}

const GatenPlatform = NativeModules.GatenPlatform as GatenPlatformNative;

/** Opens the system's Accessibility screen; `false` when no activity could take it. */
export function openAccessibilitySettings(): Promise<boolean> {
  return GatenPlatform.openAccessibilitySettings();
}

/** Opens the system's Notification Access screen; `false` when no activity could take it. */
export function openNotificationListenerSettings(): Promise<boolean> {
  return GatenPlatform.openNotificationListenerSettings();
}

/** Opens a URL in the browser; a silent no-op resolves `false` when no browser is installed. */
export function openUrl(url: string): Promise<boolean> {
  return GatenPlatform.openUrl(url);
}

/** True when Gaten is an enabled notification listener — the media-pause permission. */
export function isNotificationAccessEnabled(): Promise<boolean> {
  return GatenPlatform.isNotificationAccessEnabled();
}

/** Puts text on the clipboard. */
export function copyToClipboard(text: string): Promise<boolean> {
  return GatenPlatform.copyToClipboard(text);
}

/** Shows a toast, in the same short way the Compose screens did. */
export function toast(message: string): Promise<boolean> {
  return GatenPlatform.toast(message);
}
