/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Appearance: dark or light, the paper the app is written on, and the one accent in it.
 *
 * Every choice applies the moment it is tapped — the theme lives above the shell, so a tap here
 * recolours this very page — and is stored through the theme bridge, which the overlay and the
 * floating bubble read too. The previews are drawn in the mode the reader's choice resolves to,
 * not the device's.
 */
import React, {useEffect, useState} from "react";
import {Text, useColorScheme, View} from "react-native";
import * as GatenHaptics from "../../native/GatenHaptics";
import * as GatenTheme from "../../native/GatenTheme";
import type {Accent, Palette, ThemeColorMap, ThemeMode} from "../../native/GatenTheme";
import {PressableScale} from "../PressableScale";
import {SwitchRow, SettingsGroup, SettingsPageScaffold, RowIcon, SettingsTile} from "./settings-components";
import {useTheme, type Theme} from "../theme";
import {spacing, typeScale} from "../tokens";

/** The palettes, in the order the Compose screen laid them out. */
const PALETTES: Palette[] = ["Gaten", "Mono", "Cool", "Warm"];

/** The accents, in the order the Compose screen laid them out: a 4 + 3 block. */
const ACCENTS: Accent[] = ["Indigo", "Blue", "Violet", "Teal", "Green", "Rose", "Black"];

export function AppearanceSettingsScreen({onClose}: {onClose: () => void}) {
  const theme = useTheme();
  const scheme = useColorScheme();
  // What the swatches below sit on: the reader's choice, not their device's.
  const dark = theme.mode === "Dark" || (theme.mode === "System" && scheme === "dark");

  // Each palette's neutral colours, resolved once per mode through the bridge; the accent is
  // held at the reader's current one, so no preview looks like another accent picker.
  const [paletteTones, setPaletteTones] = useState<Record<string, ThemeColorMap>>({});
  const [accentPrimaries, setAccentPrimaries] = useState<Record<string, string>>({});
  useEffect(() => {
    let cancelled = false;
    const modeCandidate: ThemeMode = dark ? "Dark" : "Light";
    void Promise.all(
      PALETTES.map(async (palette) => ({
        palette,
        tones: await GatenTheme.colors(modeCandidate, palette, theme.accent),
      }))
    ).then((resolved) => {
      if (cancelled) return;
      setPaletteTones(Object.fromEntries(resolved.map(({palette, tones}) => [palette, tones])));
    });
    void Promise.all(
      ACCENTS.map(async (accent) => ({
        accent,
        primary: (await GatenTheme.colors(modeCandidate, theme.palette, accent)).primary,
      }))
    ).then((resolved) => {
      if (cancelled) return;
      setAccentPrimaries(Object.fromEntries(resolved.map(({accent, primary}) => [accent, primary])));
    });
    return () => {
      cancelled = true;
    };
  }, [dark, theme.accent, theme.palette]);

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
      <View style={{marginTop: spacing.s}}>
        <ChoiceCard theme={theme} title="Theme" support="Follow your device, or keep Gaten light or dark on its own.">
          {/* One pill group: the three modes share a quiet track, and the chosen one is the raised
              capsule on it. */}
          <View
            style={{
              flexDirection: "row",
              borderRadius: 14,
              padding: spacing.xs,
                backgroundColor: theme.colors.surfaceVariant,
            }}
          >
            {(["System", "Light", "Dark"] as ThemeMode[]).map((mode) => {
              const selected = mode === theme.mode;
              return (
                <PressableScale
                  key={mode}
                  accessibilityRole="radio"
                  accessibilityState={{selected}}
                  onPress={() => theme.setMode(mode)}
                  style={{
                    flex: 1,
                    minHeight: 48,
                    alignItems: "center",
                    justifyContent: "center",
                    borderRadius: 14,
                    backgroundColor: selected ? theme.colors.surface : "transparent",
                    elevation: 0,
                  }}
                >
                  <Text
                    style={[
                      typeScale.labelLarge,
                      {
                        color: selected
                          ? theme.colors.onSurface
                          : theme.colors.onSurfaceVariant,
                      },
                    ]}
                  >
                    {mode}
                  </Text>
                </PressableScale>
              );
            })}
          </View>
        </ChoiceCard>
      </View>

      <View style={{marginTop: spacing.l}}>
        <ChoiceCard
          theme={theme}
          title="Palette"
          support="The colour of Gaten's paper. Your selected accent stays unchanged."
        >
          <View style={{flexDirection: "row", flexWrap: "wrap", gap: spacing.s}}>
            {PALETTES.map((palette) => {
              const selected = palette === theme.palette;
              const tones = paletteTones[palette];
              return (
                <PressableScale
                  key={palette}
                  accessibilityRole="radio"
                  accessibilityLabel={palette}
                  accessibilityState={{selected}}
                  onPress={() => theme.setPalette(palette)}
                  style={{alignItems: "center", width: 64}}
                >
                  <View
                    style={{
                      width: 64,
                      height: 48,
                      borderRadius: 10,
                      borderWidth: selected ? 2 : 1,
                      borderColor: selected ? theme.colors.onSurface : theme.colors.outlineVariant,
                      padding: 4,
                    }}
                  >
                    <View
                      style={{
                        flex: 1,
                        borderRadius: 8,
                        backgroundColor: tones?.surfaceVariant ?? "transparent",
                        alignItems: "center",
                        justifyContent: "center",
                      }}
                    >
                      {tones != null && (
                        <Text style={[typeScale.titleSmall, {color: tones.onSurface}]}>Aa</Text>
                      )}
                    </View>
                  </View>
                  <Text style={[typeScale.labelMedium, {color: theme.colors.onSurface, marginTop: spacing.xs}]}>
                    {palette}
                  </Text>
                </PressableScale>
              );
            })}
          </View>
        </ChoiceCard>
      </View>

      <View style={{marginTop: spacing.l}}>
        <ChoiceCard
          theme={theme}
          title="Accent"
          support="The one colour in Gaten: buttons, the edge outline and the lasso."
        >
          <View
            style={{
              flexDirection: "row",
              flexWrap: "wrap",
              gap: spacing.m,
              rowGap: spacing.m,
              maxWidth: 4 * 48 + 3 * (spacing.s + spacing.m),
            }}
          >
            {ACCENTS.map((accent) => {
              const selected = accent === theme.accent;
              const primary = accentPrimaries[accent] ?? theme.colors.accent;
              return (
                <PressableScale
                  key={accent}
                  accessibilityRole="radio"
                  accessibilityLabel={accent}
                  accessibilityState={{selected}}
                  onPress={() => selectAccent(accent)}
                  style={{alignItems: "center", width: 48}}
                >
                  <View
                    style={{
                      width: 48,
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
                    numberOfLines={1}
                    style={[
                      typeScale.labelMedium,
                      {
                        color: theme.colors.onSurface,
                        marginTop: spacing.xs,
                        // The swatch column is 48dp and stays 48dp so four accents keep fitting a
                        // row; the name gets its own width and centres under the swatch, so a long
                        // name is not broken mid-word the way a 48dp-wide label would break it.
                        width: 72,
                        textAlign: "center",
                      },
                    ]}
                  >
                    {accent}
                  </Text>
                </PressableScale>
              );
            })}
          </View>
        </ChoiceCard>
      </View>

      <SettingsGroup title="Feedback" style={{marginTop: spacing.l}}>
        <View
          style={{
            paddingHorizontal: spacing.l,
            paddingVertical: spacing.m,
            flexDirection: "row",
            alignItems: "center",
          }}
        >
          <RowIcon name="vibration" />
          <View style={{flex: 1, marginStart: spacing.m}}>
            <Text style={[typeScale.bodyLarge, {color: theme.colors.onSurface}]}>Haptics</Text>
            <Text style={[typeScale.bodySmall, {color: theme.colors.onSurfaceVariant}]}>
              Follows your phone's settings.
            </Text>
          </View>
        </View>
        <SwitchRow
          icon="volume_up"
          title="Sounds"
          summary="A short cue when a word is added to Anki, and when a review session ends."
          value={soundsEnabled}
          onValueChange={toggleSounds}
        />
      </SettingsGroup>

      <View style={{height: spacing.xl}} />
    </SettingsPageScaffold>
  );
}

/** One appearance choice: what it is, what it does, and the controls under it. */
function ChoiceCard({
  theme,
  title,
  support,
  children,
}: {
  theme: Theme;
  title: string;
  support: string;
  children: React.ReactNode;
}) {
  return (
    <SettingsTile style={{padding: spacing.xl}}>
      <Text style={[typeScale.titleLarge, {color: theme.colors.onSurface}]}>{title}</Text>
      <Text style={[typeScale.bodySmall, {color: theme.colors.onSurfaceVariant, marginTop: spacing.xs}]}>
        {support}
      </Text>
      <View style={{marginTop: spacing.l}}>{children}</View>
    </SettingsTile>
  );
}
