/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Feeds the platform's system-gesture inset into the safe-area context, so every `useSafeAreaInsets`
 * below the provider lays out from a bottom inset that is real on Android 15's edge-to-edge gesture
 * navigation.
 *
 * `react-native-safe-area-context` reads only the navigation bar, which Android reports as zero on a
 * gesture-navigation device — the system draws the gesture handle instead. The shell mirrors the
 * reserved gesture strip into JS, and this provider re-publishes the library's own context with only
 * the bottom edge raised to it (see `systemInsets.ts`). It is placed directly under `SafeAreaProvider`
 * so the bar, Home, Settings, Analytics and every pushed page read the same corrected value.
 *
 * The native read is asynchronous and can answer `null` before the window has laid out, so the
 * provider starts from the safe-area value and refreshes: on mount, whenever it comes back to the
 * foreground, and whenever the safe-area insets themselves change — which is what hiding or showing
 * the gesture handle does, since that flips the navigation-bar inset the library reads.
 */
import React, {useEffect, useMemo, useState} from "react";
import {AppState} from "react-native";
import {
  SafeAreaInsetsContext,
  useSafeAreaInsets,
} from "react-native-safe-area-context";
import * as ShellBridge from "../native/GatenShell";
import {mergeBottomInset} from "./systemInsets";

export function SystemInsetsProvider({children}: {children: React.ReactNode}) {
  const insets = useSafeAreaInsets();
  const [gestureBottom, setGestureBottom] = useState(0);

  useEffect(() => {
    let live = true;
    const read = () => {
      ShellBridge.getSystemGestureInsets()
        .then((value) => {
          if (live && value != null) {
            setGestureBottom(value.bottom);
          }
        })
        .catch(() => {
          // No window insets yet: the safe-area value stands and the next resume retries.
        });
    };
    read();
    const subscription = AppState.addEventListener("change", (state) => {
      if (state === "active") {
        read();
      }
    });
    return () => {
      live = false;
      subscription.remove();
    };
  }, [insets.bottom]);

  const merged = useMemo(
    () => ({...insets, bottom: mergeBottomInset(insets.bottom, gestureBottom)}),
    [insets, gestureBottom],
  );

  return (
    <SafeAreaInsetsContext.Provider value={merged}>
      {children}
    </SafeAreaInsetsContext.Provider>
  );
}
