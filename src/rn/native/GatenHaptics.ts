/**
 * The haptics bridge: the touch feedback the Compose screens played, through the same Feedback
 * class — haptics from the window that is on screen, sounds only when the reader turned them on.
 */
import { NativeModules } from "react-native";

/**
 * What play() plays: `tick`, `lookup` and `confirm` are haptics (a step, a dictionary hit, an
 * action that landed); `added` and `done` are the two sound cues, gated by the sounds preference.
 */
export type FeedbackKind = "tick" | "lookup" | "confirm" | "added" | "done";

interface GatenHapticsNative {
  play(kind: FeedbackKind): Promise<boolean>;
  getSoundsEnabled(): Promise<boolean>;
  setSoundsEnabled(enabled: boolean): Promise<boolean>;
}

const GatenHaptics = NativeModules.GatenHaptics as GatenHapticsNative;

/**
 * Plays `kind`. Rejects with `E_FEEDBACK_KIND` for an unknown kind, and with `E_NO_WINDOW` when
 * there is no activity window to feel the haptic through.
 */
export function play(kind: FeedbackKind): Promise<boolean> {
  return GatenHaptics.play(kind);
}

/** Whether the reader wants sound with their actions. */
export function getSoundsEnabled(): Promise<boolean> {
  return GatenHaptics.getSoundsEnabled();
}

/** Stores whether the reader wants sound with their actions. */
export function setSoundsEnabled(enabled: boolean): Promise<boolean> {
  return GatenHaptics.setSoundsEnabled(enabled);
}
