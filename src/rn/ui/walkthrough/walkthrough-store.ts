/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Shared walkthrough visibility & replay state so Settings can trigger a replay and Home can
 * suppress SupportPrompt while the walkthrough is visible — without coupling screens together.
 */
import {useEffect, useState} from "react";

type Listener = () => void;

const replayListeners = new Set<Listener>();
const activeListeners = new Set<Listener>();
let pendingReplay = false;
let walkthroughActive = false;

/** Requests a walkthrough replay; delivered immediately to a live listener or held until ready. */
export function requestWalkthroughReplay(): void {
  pendingReplay = true;
  for (const listener of replayListeners) {
    listener();
  }
}

/** Consumes any pending replay request once the shell acts on it. */
export function consumePendingWalkthroughReplay(): boolean {
  if (!pendingReplay) return false;
  pendingReplay = false;
  return true;
}

/** Subscribes to replay requests triggered from Settings. */
export function subscribeWalkthroughReplay(listener: Listener): () => void {
  replayListeners.add(listener);
  return () => {
    replayListeners.delete(listener);
  };
}

/** Updates whether the walkthrough overlay is currently visible. */
export function setWalkthroughActive(active: boolean): void {
  if (walkthroughActive === active) return;
  walkthroughActive = active;
  for (const listener of activeListeners) {
    listener();
  }
}

/** Current walkthrough visibility (used by tests and hooks). */
export function isWalkthroughActive(): boolean {
  return walkthroughActive;
}

/** Hook returning true while the walkthrough overlay is on screen. */
export function useWalkthroughActive(): boolean {
  const [active, setActive] = useState(walkthroughActive);
  useEffect(() => {
    const sync = () => setActive(walkthroughActive);
    sync();
    activeListeners.add(sync);
    return () => {
      activeListeners.delete(sync);
    };
  }, []);
  return active;
}
