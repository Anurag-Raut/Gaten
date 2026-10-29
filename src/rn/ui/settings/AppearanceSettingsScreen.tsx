/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Appearance: dark or light, and the one accent in it.
 *
 * Every choice applies the moment it is tapped — the theme lives above the shell, so a tap here
 * recolours this very page — and is stored through the theme bridge, which the overlay and the
 * floating bubble read too. The previews are drawn in the mode the reader's choice resolves to,
 * not the device's.
 */
import React, {useEffect, useState} from "react";
import {Text, useWindowDimensions, View} from "react-native";
import * as GatenHaptics from "../../native/GatenHaptics";
import * as GatenTheme from "../../native/GatenTheme";
import type {Accent, ThemeMode} from "../../native/GatenTheme";
import {PressableScale} from "../PressableScale";
import {SwitchRow, SettingsGroup, SettingsPageScaffold, RowIcon} from "./settings-components";
import {useTheme} from "../theme";
import {contentInsetFor, MAX_CONTENT_WIDTH, spacing, typeScale} from "../tokens";

/** The accents, in the order the Compose screen laid them out: a 4 + 3 block. */
const ACCENTS: Accent[] = ["Indigo", "Blue", "Violet", "Teal", "Green", "Rose", "Black"];

export function AppearanceSettingsScreen({onClose}: {onClose: () => void}) {
  const theme = useTheme();
  const {width} = useWindowDimensions();
  const horizontalPadding = contentInsetFor(width);
  const accentGridWidth = Math.min(width - horizontalPadding * 2, MAX_CONTENT_WIDTH - 44);
  const accentCellWidth = Math.floor(
    (accentGridWidth - spacing.s * 3) / 4
  );
  // What the swatches below sit on: the reader's choice, not their device's.
  const {dark} = theme;

  // Each accent's primary, resolved once per mode through the bridge, so every swatch shows the
  // accent's own tone rather than the selected one's.
  const [accentPrimaries, setAccentPrimaries] = useState<Record<string, string>>({});
  useEffect(() => {
    let cancelled = false;
    const modeCandidate: ThemeMode = dark ? "Dark" : "Light";
    void Promise.all(
      ACCENTS.map(async (accent) => ({
        accent,
        primary: (await GatenTheme.colors(modeCandidate, accent)).primary,
      }))
    ).then((resolved) => {
      if (cancelled) return;
      setAccentPrimaries(Object.fromEntries(resolved.map(({accent, primary}) => [accent, primary])));
    });
    return () => {
      cancelled = true;
    };
  }, [dark]);

  const selectAccent = (accent: Accent) => {
    // A tap that lands on the accent already chosen changes nothing and gets no tick for it.
    if (accent === theme.accent) return;
    void GatenHaptics.play("tick").catch(() => {});
    theme.setAccent(accent);
  };

  const [soundsEnabled, setSoundsEnabled] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void GatenHaptics.getSoundsEnabled().then((v) => {
      if (!cancelled) setSoundsEnabled(v);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const toggleSounds = (next: boolean) => {
    if (next === soundsEnabled) return;
    setSoundsEnabled(next);
    void GatenHaptics.setSoundsEnabled(next).catch(() => {});
    void GatenHaptics.play("tick").catch(() => {});
  };

  return (
    <SettingsPageScaffold title="Appearance" onClose={onClose}>
      <SettingsGroup title="Theme" style={{marginTop: spacing.s}}>
        {(["System", "Light", "Dark"] as ThemeMode[]).map((mode) => {
          const selected = mode === theme.mode;
          return (
            <PressableScale
              key={mode}
              accessibilityRole="radio"
              accessibilityLabel={`Theme ${mode}`}
              accessibilityState={{selected}}
              onPress={() => theme.setMode(mode)}
              style={{minHeight: 56, justifyContent: "center"}}
            >
              <View style={{paddingVertical: spacing.s, flexDirection: "row", alignItems: "center"}}>
                <Text style={[typeScale.titleMedium, {color: theme.colors.onSurface, flex: 1}]}>{mode}</Text>
                <View
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 11,
                    borderWidth: 2,
                    borderColor: selected ? theme.colors.accent : theme.colors.iconSecondary,
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  {selected && <View style={{width: 10, height: 10, borderRadius: 5, backgroundColor: theme.colors.accent}} />}
                </View>
              </View>
            </PressableScale>
          );
        })}
      </SettingsGroup>

      <SettingsGroup title="Accent" style={{marginTop: spacing.xl}}>
        <View style={{paddingVertical: spacing.l}}>
          <View
            style={{
              flexDirection: "row",
              flexWrap: "wrap",
              gap: spacing.s,
              maxWidth: accentGridWidth,
            }}
          >
            {ACCENTS.map((accent) => {
              const selected = accent === theme.accent;
              const primary = accentPrimaries[accent] ?? theme.colors.accent;
              // The colourless accent is black ink on light paper and white ink on dark: it is named
              // for the tone it actually paints in the mode on show.
              const label = accent === "Black" && dark ? "White" : accent;
              return (
                <PressableScale
                  key={accent}
                  accessibilityRole="radio"
                  accessibilityLabel={label}
                  accessibilityState={{selected}}
                  onPress={() => selectAccent(accent)}
                  style={{alignItems: "center", width: accentCellWidth}}
                >
                  <View
                    style={{
                      width: accentCellWidth,
                      height: 48,
                      alignItems: "center",
                      justifyContent: "center",
                    }}
                  >
                    <View
                      style={{
                        width: 40,
                        height: 40,
                        borderRadius: 20,
                        borderWidth: selected ? 2 : 0,
                        borderColor: theme.colors.onSurface,
                        padding: 5,
                      }}
                    >
                      <View style={{flex: 1, borderRadius: 15, backgroundColor: primary}} />
                    </View>
                  </View>
                  <Text
                    style={[
                      typeScale.labelMedium,
                      {
                        color: theme.colors.onSurface,
                        marginTop: spacing.xs,
                        width: accentCellWidth,
                        textAlign: "center",
                      },
                    ]}
                  >
                    {label}
                  </Text>
                </PressableScale>
              );
            })}
          </View>
        </View>
      </SettingsGroup>

      <SettingsGroup title="Feedback" style={{marginTop: spacing.l}}>
        <View
          style={{
            paddingVertical: spacing.m,
            flexDirection: "row",
            alignItems: "center",
          }}
        >
          <RowIcon name="vibration" />
          <Text style={[typeScale.titleMedium, {color: theme.colors.onSurface, flex: 1, marginStart: spacing.m}]}>Haptics</Text>
          <Text style={[typeScale.labelMedium, {color: theme.colors.onSurfaceVariant}]}>System</Text>
        </View>
        <SwitchRow
          icon="volume_up"
          title="Sounds"
          summary="Anki and review cues"
          value={soundsEnabled}
          onValueChange={toggleSounds}
        />
      </SettingsGroup>

      <View style={{height: spacing.xl}} />
    </SettingsPageScaffold>
  );
}
