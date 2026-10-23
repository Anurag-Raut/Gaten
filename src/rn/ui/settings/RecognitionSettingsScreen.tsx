/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React, {useEffect, useState} from "react";
import * as GatenRecognition from "../../native/GatenRecognition";
import {SettingsGroup, SettingsPageScaffold, SettingsRow} from "./settings-components";
import {spacing} from "../tokens";

export function RecognitionSettingsScreen({onClose}: {onClose: () => void}) {
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
  const selectedMangaOcr = mangaOcrEnabled ?? true;

  return (
    <SettingsPageScaffold
      title="Text recognition"
      onClose={onClose}
      help={[
        "Manga OCR is included with Gaten and recommended for stylized manga lettering. It is prepared automatically on the first lookup and works offline.",
        "Google ML Kit is an alternative for standard Japanese text. It runs on this device.",
      ]}
    >
      <SettingsGroup title="Recognition engine" style={{marginTop: spacing.s}}>
        <SettingsRow
          icon={selectedMangaOcr ? "check_circle" : "text_snippet"}
          title="Manga OCR"
          summary="Recommended · included with Gaten"
          value={selectedMangaOcr ? "Selected" : undefined}
          disabled={mangaOcrEnabled == null}
          onPress={selectedMangaOcr ? undefined : () => selectEngine(true)}
        />
        <SettingsRow
          icon={selectedMangaOcr ? "text_snippet" : "check_circle"}
          title="Google ML Kit"
          summary="Google's on-device Japanese text recognition"
          value={selectedMangaOcr ? undefined : "Selected"}
          disabled={mangaOcrEnabled == null}
          onPress={selectedMangaOcr ? () => selectEngine(false) : undefined}
        />
      </SettingsGroup>
    </SettingsPageScaffold>
  );
}
