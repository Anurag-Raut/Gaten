/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The theme provider: the reader's three choices (mode, palette, accent) and the colours they
 * resolve to, held in one place and re-resolved whenever the bridge announces a change.
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
  useState,
} from "react";
import {useColorScheme} from "react-native";
import * as ThemeBridge from "../native/GatenTheme";
import type {
  Accent,
  Palette,
  ThemeChoices,
  ThemeColorMap,
  ThemeMode,
} from "../native/GatenTheme";

/** Action roles are separate from accent roles so focus and charts keep the selected accent. */
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
  mode: ThemeMode;
  palette: Palette;
  accent: Accent;
  setMode(mode: ThemeMode): void;
  setPalette(palette: Palette): void;
  setAccent(accent: Accent): void;
}

const ThemeContext = createContext<Theme | null>(null);

/** The stored defaults, until the bridge's own answer arrives. */
const DEFAULT_CHOICES: ThemeChoices = {
  mode: "System",
  palette: "Gaten",
  accent: "Indigo",
};

export function ThemeProvider({children}: {children: React.ReactNode}) {
  const systemScheme = useColorScheme();
  const [choices, setChoices] = useState<ThemeChoices>(DEFAULT_CHOICES);
  const [colorMap, setColorMap] = useState<ThemeColorMap | null>(null);

  const apply = useCallback(async (next: ThemeChoices) => {
    setChoices(next);
    setColorMap(await ThemeBridge.colors(next.mode, next.palette, next.accent));
  }, []);

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
        const resolved = await ThemeBridge.colors(stored.mode, stored.palette, stored.accent);
        if (!live || currentRequest !== request) return;
        setChoices(stored);
        setColorMap(resolved);
      } catch {
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
      ThemeBridge.setThemeMode(mode)
        .then((stored) => apply(stored))
        .catch(() => {});
    },
    [apply],
  );

  const setPalette = useCallback(
    (palette: Palette) => {
      ThemeBridge.setPalette(palette)
        .then((stored) => apply(stored))
        .catch(() => {});
    },
    [apply],
  );

  const setAccent = useCallback(
    (accent: Accent) => {
      ThemeBridge.setAccent(accent)
        .then((stored) => apply(stored))
        .catch(() => {});
    },
    [apply],
  );

  const theme = useMemo<Theme | null>(() => {
    if (!colorMap) {
      return null;
    }
    const dark = choices.mode === "Dark" || (choices.mode === "System" && systemScheme === "dark");
    return {
      colors: {
        ...colorMap,
        bottom: dark ? "#1C1D21" : "#F5F5F6",
        fine: dark ? "#777781" : "#A1A1AB",
        actionPrimary: dark ? "#F5F5F7" : "#18181B",
        onActionPrimary: dark ? "#17171A" : "#FFFFFF",
        actionSecondary: colorMap.surfaceVariant,
        onActionSecondary: colorMap.onSurface,
        iconPrimary: dark ? colorMap.onSurface : "#000000",
        iconSecondary: dark ? "#909099" : "#83838C",
        accent: colorMap.primary,
        accentSubtle: colorMap.primaryContainer,
        // Review task colors stay distinct regardless of the reader's chosen accent or palette.
        reviewMeaning: dark ? "#8FC5CA" : "#2B7178",
        reviewReading: dark ? "#B0A0FF" : "#6D28D9",
        success: dark ? "#53C99B" : "#16A476",
        warning: dark ? "#EF8A96" : "#DC6673",
        disabled: dark ? colorMap.surfaceContainerHighest : "#E9E9ED",
        onDisabled: dark ? colorMap.onSurfaceVariant : "#83838C",
      },
      mode: choices.mode,
      palette: choices.palette,
      accent: choices.accent,
      setMode,
      setPalette,
      setAccent,
    };
  }, [choices, colorMap, systemScheme, setMode, setPalette, setAccent]);

  if (!theme) {
    // The first resolve has not landed yet; nothing is drawn until the colours exist.
    return null;
  }

  return <ThemeContext.Provider value={theme}>{children}</ThemeContext.Provider>;
}

/** The theme of the shell this screen sits in. Must render below [ThemeProvider]. */
export function useTheme(): Theme {
  const theme = useContext(ThemeContext);
  if (!theme) {
    throw new Error("useTheme must be used inside ThemeProvider");
  }
  return theme;
}
