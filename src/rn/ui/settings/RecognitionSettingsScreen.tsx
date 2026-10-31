/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import {Text} from "react-native";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {SettingsGroup, SettingsPageScaffold} from "./settings-components";

export function RecognitionSettingsScreen({onClose}: {onClose: () => void}) {
  const {colors} = useTheme();
  return (
    <SettingsPageScaffold
      title="Text recognition"
      onClose={onClose}
      help={[
        "Every lookup uses Manga OCR to recognize the selected text. The model is included with Gaten, prepared automatically on the first lookup, and works offline.",
        "Google ML Kit locates text so you can tap a line quickly. Its readings are not used as lookup answers. If Manga OCR cannot read a selection, select it again to retry.",
      ]}
    >
      <SettingsGroup title="Recognition engine" style={{marginTop: spacing.s}}>
        <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>Manga OCR</Text>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          Required for every lookup · included with Gaten
        </Text>
      </SettingsGroup>
    </SettingsPageScaffold>
  );
}
