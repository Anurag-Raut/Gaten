/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Whether a review session holds the Review tab: the one piece of shell state the review slice
 * drives. The shell keeps the context filled — the bottom bar hides while a session runs, exactly
 * as the Compose shell hid its NavigationBar while `step.holdsSession(deck)`.
 */
import {createContext, useContext} from "react";

export interface SessionState {
  sessionActive: boolean;
}

export const SessionContext = createContext<SessionState>({sessionActive: false});

/** Whether the current tab is inside a live review session. */
export function useSessionActive(): boolean {
  return useContext(SessionContext).sessionActive;
}
