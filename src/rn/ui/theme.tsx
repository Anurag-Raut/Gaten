/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The theme provider: the reader's two choices (mode, accent) and the colours they resolve to, held
 * in one place and re-resolved whenever the bridge announces a change.
 *
 * The Compose shell owned this state; the RN shell does the same through this provider. Settings
 * screens call the setters — each one stores through the bridge, and the resolved map is re-read
 * from it so the answer is never assumed. Children do not render until the first resolve is in,
 * so every screen below [ThemeProvider] sees a complete colour map.
 */
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {Alert, findNodeHandle, requireNativeComponent, useColorScheme, type ViewProps} from "react-native";
import * as ThemeBridge from "../native/GatenTheme";
import type {Accent, ThemeChoices, ThemeColorMap, ThemeMode} from "../native/GatenTheme";

/**
 * Gaten's own roles over the Material map. The action roles point at the accent — a button and the
 * selected navigation pill are the same tone — while the named `accent`/`accentSubtle` aliases let a
 * screen say "accent" where the meaning is the accent itself rather than an action.
 */
export interface ThemeColors extends ThemeColorMap {
  bottom: string;
  fine: string;
  actionPrimary: string;
  onActionPrimary: string;
  actionSecondary: string;
  onActionSecondary: string;
  iconPrimary: string;
  iconSecondary: string;
  accent: string;
  accentSubtle: string;
  reviewMeaning: string;
  reviewReading: string;
  success: string;
  warning: string;
  disabled: string;
  onDisabled: string;
}

/** What every screen reads its colours and type from. */
export interface Theme {
  /** The resolved scheme for the current choices; opaque `#RRGGBB` per Material role. */
  colors: ThemeColors;
  /** The light/dark mode resolved together with these colors. */
  dark: boolean;
  mode: ThemeMode;
  accent: Accent;
  setMode(mode: ThemeMode): void;
  setAccent(accent: Accent): void;
}

const ThemeContext = createContext<Theme | null>(null);

interface ResolvedTheme {
  choices: ThemeChoices;
  colorMap: ThemeColorMap;
  dark: boolean;
  revision: number;
}

const NativeThemeTransition = requireNativeComponent<ViewProps & {revision: number}>(
  "GatenThemeTransition",
);

function reportSaveError(error: unknown) {
  console.error("Could not save the appearance preference", error);
  Alert.alert("Could not change appearance", "Please try again.");
}

/** Resolves each theme atomically and captures the outgoing screen before a light/dark change. */
export function ThemeProvider({children}: {children: React.ReactNode}) {
  const systemScheme = useColorScheme();
  const [resolved, setResolved] = useState<ResolvedTheme | null>(null);
  const lastResolved = useRef<ResolvedTheme | null>(null);
  const transitionView = useRef<React.ComponentRef<typeof NativeThemeTransition>>(null);

  useEffect(() => {
    let live = true;
    let request = 0;
    // Read the saved choices and matching system-resolved colors together. Each await is guarded
    // so a late response from an earlier OS/theme change cannot repaint a newer scheme.
    const reload = async () => {
      const currentRequest = ++request;
      try {
        const stored = await ThemeBridge.getTheme();
        if (!live || currentRequest !== request) return;
        const colorMap = await ThemeBridge.colors(stored.mode, stored.accent);
        if (!live || currentRequest !== request) return;
        const dark = stored.mode === "Dark" || (stored.mode === "System" && systemScheme === "dark");
        const previous = lastResolved.current;
        if (previous && previous.dark !== dark) {
          const viewTag = findNodeHandle(transitionView.current);
          if (viewTag != null) {
            try {
              await ThemeBridge.prepareThemeTransition(viewTag);
            } catch (error) {
              console.warn("Could not prepare the theme transition", error);
            }
          }
        }
        if (!live || currentRequest !== request) return;
        const next = {choices: stored, colorMap, dark, revision: (previous?.revision ?? 0) + 1};
        lastResolved.current = next;
        setResolved(next);
      } catch (error) {
        console.warn("Could not read the appearance preference", error);
        // Keep the last complete theme; the next preference or system change retries the read.
      }
    };
    void reload();
    const subscription = ThemeBridge.onThemeChanged(() => void reload());
    return () => {
      live = false;
      subscription.remove();
    };
  }, [systemScheme]);

  const setMode = useCallback(
    (mode: ThemeMode) => {
      // The bridge event is the single reload path, so a setter and its event cannot race a fade.
      void ThemeBridge.setThemeMode(mode).catch(reportSaveError);
    },
    [],
  );

  const setAccent = useCallback(
    (accent: Accent) => {
      void ThemeBridge.setAccent(accent).catch(reportSaveError);
    },
    [],
  );

  const theme = useMemo<Theme | null>(() => {
    if (!resolved) {
      return null;
    }
    const {choices, colorMap, dark} = resolved;
    return {
      colors: {
        ...colorMap,
        bottom: dark ? "#1C1D21" : "#F5F5F6",
        fine: dark ? "#777781" : "#A1A1AB",
        // Every button wears the reader's accent: the action roles are the accent's own tones, so a
        // filled action and the selected navigation pill are the same colour by construction, and
        // changing the accent in Settings repaints both. `accent`/`onPrimary` are the pairing the
        // theme's own tests already hold to contrast in either mode.
        actionPrimary: colorMap.primary,
        onActionPrimary: colorMap.onPrimary,
        actionSecondary: colorMap.primaryContainer,
        onActionSecondary: colorMap.onPrimaryContainer,
        iconPrimary: dark ? colorMap.onSurface : "#000000",
        iconSecondary: dark ? "#909099" : "#83838C",
        accent: colorMap.primary,
        accentSubtle: colorMap.primaryContainer,
        // Review task colors stay distinct regardless of the reader's chosen accent.
        reviewMeaning: dark ? "#8FC5CA" : "#2B7178",
        reviewReading: dark ? "#B0A0FF" : "#6D28D9",
        success: dark ? "#53C99B" : "#16A476",
        warning: dark ? "#EF8A96" : "#DC6673",
        disabled: dark ? colorMap.surfaceContainerHighest : "#E9E9ED",
        onDisabled: dark ? colorMap.onSurfaceVariant : "#83838C",
      },
      dark,
      mode: choices.mode,
      accent: choices.accent,
      setMode,
      setAccent,
    };
  }, [resolved, setMode, setAccent]);

  if (!theme || !resolved) {
    // The first resolve has not landed yet; nothing is drawn until the colours exist.
    return null;
  }

  return (
    <ThemeContext.Provider value={theme}>
      <NativeThemeTransition ref={transitionView} revision={resolved.revision} style={{flex: 1}}>
        {children}
      </NativeThemeTransition>
    </ThemeContext.Provider>
  );
}

/** The theme of the shell this screen sits in. Must render below [ThemeProvider]. */
export function useTheme(): Theme {
  const theme = useContext(ThemeContext);
  if (!theme) {
    throw new Error("useTheme must be used inside ThemeProvider");
  }
  return theme;
}
