/**
 * The floating-trigger bridge: the on-screen bubble's switch, stored in `gaten_settings` and
 * announced to the running service through the floating-button-changed broadcast.
 */
import { NativeModules } from "react-native";

interface GatenTriggerNative {
  getFloatingButtonEnabled(): Promise<boolean>;
  setFloatingButtonEnabled(enabled: boolean): Promise<void>;
  tileAdded(): Promise<boolean>;
  setTileAdded(added: boolean): Promise<void>;
  /** "added" or "notAdded" is the system's own answer; "failed" means the request could not be made. */
  requestAddTile(): Promise<"added" | "notAdded" | "failed">;
}

const GatenTrigger = NativeModules.GatenTrigger as GatenTriggerNative;

/** Whether the bubble is switched on. */
export function getFloatingButtonEnabled(): Promise<boolean> {
  return GatenTrigger.getFloatingButtonEnabled();
}

/**
 * Stores whether the bubble is switched on; the native side sends the floating-button-changed
 * broadcast itself, so the service reacts even when no Gaten screen is open.
 */
export function setFloatingButtonEnabled(enabled: boolean): Promise<void> {
  return GatenTrigger.setFloatingButtonEnabled(enabled);
}

/** Whether the lookup tile is already in the reader's Quick Settings, as Gaten recorded it. */
export function tileAdded(): Promise<boolean> {
  return GatenTrigger.tileAdded();
}

/** Records the tile's presence; the system's own editor owns removing it. */
export function setTileAdded(added: boolean): Promise<void> {
  return GatenTrigger.setTileAdded(added);
}

/**
 * Asks the system to add the lookup tile (API 33+). The system may answer the reader directly;
 * "added" tells the page to record the tile as present, and anything else leaves it alone.
 */
export function requestAddTile(): Promise<"added" | "notAdded" | "failed"> {
  return GatenTrigger.requestAddTile();
}
