/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * About: the version, what the app does with the screen, the optional network use
 * anywhere, and who the parts belong to.
 *
 * The two claims here are the app's contract with the reader — the screenshot and the looked-up
 * text stay on the device, and the network is used only for WaniKani when a token is connected and
 * for the OCR model downloads the reader explicitly starts. Every attribution is a row that opens
 * the licence or the project it names; with no browser to open, the tap does nothing.
 */
import React, {useEffect, useState} from "react";
import {Text, View} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenPlatform from "../../native/GatenPlatform";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {SettingsGroup, SettingsPageScaffold, SettingsRow} from "./settings-components";

/**
 * The published privacy policy. It is the same URL the Play listing points at, and it is linked from
 * inside the app because Play requires both.
 */
const PRIVACY_POLICY_URL = "https://anurag-raut.github.io/Gaten/privacy/";

/** Where each attribution and licence lives. Opened in the browser, which may be nowhere. */
const EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html";
const MANGA_OCR_URL = "https://github.com/kha-white/manga-ocr";
const PADDLE_OCR_URL = "https://github.com/PaddlePaddle/PaddleOCR";
const PP_OCR_MANGA_URL = "https://huggingface.co/Kellenok/PP-OCRv6_manga";
const MLKIT_TERMS_URL = "https://developers.google.com/ml-kit/terms";
const WANIKANI_URL = "https://www.wanikani.com/";
const GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html";
const CIRCLETOSEARCH_URL = "https://github.com/AKS-Labs/CircleToSearch";
const PLEX_URL = "https://github.com/IBM/plex";
const WANAKANA_URL = "https://github.com/esnaultdev/wanakana-kt";

export function AboutScreen({onClose}: {onClose: () => void}) {
  const {colors} = useTheme();
  const [versionName, setVersionName] = useState("");

  useEffect(() => {
    let cancelled = false;
    void GatenAppInfo.getAppVersion().then((v) => {
      if (!cancelled) setVersionName(v);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const open = (url: string) => {
    void GatenPlatform.openUrl(url);
  };

  return (
    <SettingsPageScaffold title="About" onClose={onClose}>
      <View style={{paddingVertical: spacing.m}}>
        <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>Gaten</Text>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          {versionName ? `Version ${versionName} · ` : ""}Japanese lookup, offline by default.
        </Text>
      </View>

      <SettingsGroup title="Privacy" style={{marginTop: spacing.l}}>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant, paddingVertical: spacing.m}]}>
          Lookup and recognition run on this device; screenshots stay here and are never uploaded. Optional network use: WaniKani when connected, and the OCR models you choose to download (any connection works; mobile data may incur charges). Nothing else is sent.
        </Text>
        <SettingsRow
          icon="shield"
          title="Privacy policy"
          summary="What Gaten keeps, and when it uses the network"
          onPress={() => open(PRIVACY_POLICY_URL)}
        />
      </SettingsGroup>

      <SettingsGroup title="Attributions" style={{marginTop: spacing.xl}}>
        <SettingsRow
          icon="public"
          title="EDRDG dictionary"
          summary="JMdict and JMnedict · © EDRDG · CC BY-SA 4.0"
          onPress={() => open(EDRDG_LICENCE_URL)}
        />
        <SettingsRow
          icon="public"
          title="manga-ocr"
          summary="On-demand default reader by kha-white · Apache-2.0 · ONNX Runtime"
          onPress={() => open(MANGA_OCR_URL)}
        />
        <SettingsRow
          icon="public"
          title="PP-OCRv6"
          summary="Optional recognition models by PaddlePaddle · Apache-2.0 · ONNX Runtime"
          onPress={() => open(PADDLE_OCR_URL)}
        />
        <SettingsRow
          icon="public"
          title="PP-OCRv6 Manga v0.2"
          summary="Manga fine-tune by Kellenok · Apache-2.0"
          onPress={() => open(PP_OCR_MANGA_URL)}
        />
        <SettingsRow
          icon="public"
          title="ML Kit"
          summary="Google on-device text recognition"
          onPress={() => open(MLKIT_TERMS_URL)}
        />
        <SettingsRow
          icon="public"
          title="WaniKani"
          summary="Content © Tofugu LLC · Gaten is not affiliated; no content bundled"
          onPress={() => open(WANIKANI_URL)}
        />
        <SettingsRow
          icon="public"
          title="IBM Plex Sans JP"
          summary="Bundled typeface · SIL OFL 1.1 · IBM Corp. and the Plex Project Authors"
          onPress={() => open(PLEX_URL)}
        />
        <SettingsRow
          icon="public"
          title="wanakana"
          summary="MIT · Kotlin port of WanaKana for romaji-to-kana search"
          onPress={() => open(WANAKANA_URL)}
        />
      </SettingsGroup>

      <SettingsGroup title="Licence" style={{marginTop: spacing.xl}}>
        <Text
          style={[
            typeScale.bodySmall,
            {color: colors.onSurfaceVariant, paddingVertical: spacing.m},
          ]}
        >
          Gaten is free software under GPL-3.0-or-later, derived from CircleToSearch by AKS-Labs.
        </Text>
        <SettingsRow
          icon="gavel"
          title="GPL-3.0-or-later"
          summary="Read the licence"
          onPress={() => open(GPL_URL)}
        />
        <SettingsRow
          icon="gavel"
          title="CircleToSearch"
          summary="Original project by AKS-Labs"
          onPress={() => open(CIRCLETOSEARCH_URL)}
        />
      </SettingsGroup>

      <View style={{height: spacing.xl}} />
    </SettingsPageScaffold>
  );
}
