/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run handoff's readiness channel.
 *
 * The shell shows the setup flow over the tabs while Home mounts beneath it, and fades the flow
 * away only once the frame it would reveal is really settled: Home has laid out with its own
 * insets, and the floating bottom bar has drawn its measured form. The two pieces that can tell the
 * shell that — Home's root and the tab bar — are not on a path the shell can pass a prop through
 * (Home is drawn by a stack route; the bar is rendered by the navigator), so they report through
 * this one small context instead. Outside a handoff the context is absent and consuming it is null,
 * so no other mount of either component changes.
 */
import {createContext, useContext} from "react";

export interface HandoffReadiness {
  /** Home's root has laid out with its own safe-area insets applied. */
  reportHomeReady: () => void;
  /** The bottom bar has laid out its visible, label-measured form. */
  reportBarReady: () => void;
}

const HandoffReadinessContext = createContext<HandoffReadiness | null>(null);

export const HandoffReadinessProvider = HandoffReadinessContext.Provider;

/** The handoff reporters, or null when no handoff is in progress. */
export function useHandoffReadiness(): HandoffReadiness | null {
  return useContext(HandoffReadinessContext);
}
