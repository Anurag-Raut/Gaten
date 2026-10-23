/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React, {useEffect, useState} from "react";
import {Text, View} from "react-native";
import * as GatenRecognition from "../../native/GatenRecognition";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {SettingsGroup, SettingsPageScaffold} from "./settings-components";

export function RecognitionSettingsScreen({onClose}: {onClose: () => void}) {
  const {colors} = useTheme();
  const [mangaOcrEnabled, setMangaOcrEnabled] = useState<boolean | null>(null);

  useEffect(() => {
    let cancelled = false;
    void GatenRecognition.isMangaOcrEnabled().then((enabled) => {
      if (!cancelled) setMangaOcrEnabled(enabled);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const selectEngine = (enabled: boolean) => {
    setMangaOcrEnabled(enabled);
    void GatenRecognition.setMangaOcrEnabled(enabled);
  };

  return (
    <SettingsPageScaffold
      title="Text recognition"
      onClose={onClose}
      help={[
        "Manga OCR is included with Gaten and recommended for stylized manga lettering. It is prepared automatically on the first lookup and works offline.",
        "Google ML Kit is an alternative for standard Japanese text. It runs on this device.",
      ]}
    >
      <View accessibilityRole="radiogroup" accessibilityLabel="Recognition engine">
        <SettingsGroup title="Recognition engine" style={{marginTop: spacing.s}}>
          {[
            {title: "Manga OCR", summary: "Recommended · included with Gaten", enabled: true},
            {title: "Google ML Kit", summary: "Google's on-device Japanese text recognition", enabled: false},
          ].map((engine) => {
            const selected = mangaOcrEnabled === engine.enabled;
            const disabled = mangaOcrEnabled == null;
            return (
              <PressableScale
                key={engine.title}
                accessibilityRole="radio"
                accessibilityLabel={`${engine.title}. ${engine.summary}`}
                accessibilityState={{checked: selected, disabled}}
                disabled={disabled}
                onPress={() => {
                  if (!selected) selectEngine(engine.enabled);
                }}
                style={{minHeight: 60, paddingVertical: spacing.m, flexDirection: "row", alignItems: "center", gap: spacing.m}}
              >
                <View style={{flex: 1, minWidth: 0}}>
                  <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>{engine.title}</Text>
                  <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>{engine.summary}</Text>
                </View>
                <View
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 11,
                    borderWidth: 2,
                    borderColor: disabled ? colors.disabled : selected ? colors.accent : colors.iconSecondary,
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  {selected && <View style={{width: 10, height: 10, borderRadius: 5, backgroundColor: colors.accent}} />}
                </View>
              </PressableScale>
            );
          })}
        </SettingsGroup>
      </View>
    </SettingsPageScaffold>
  );
}
