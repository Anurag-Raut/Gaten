/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * About: the version, what the app does with the screen, the only two things it ever sends
 * anywhere, and who the parts belong to.
 *
 * The two claims here are the app's contract with the reader — the screenshot and the looked-up
 * text stay on the device, and the network is used only for the model download and for WaniKani
 * when a token is connected — so they are stated as plainly as they can be. Every attribution is
 * a row that opens the licence or the project it names; with no browser to open, the tap does
 * nothing.
 */
import React, {useEffect, useState} from "react";
import {Text, View} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import * as GatenPlatform from "../../native/GatenPlatform";
import {Card, Icon} from "../components";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {SettingsGroup, SettingsPageScaffold, SettingsRow} from "./settings-components";

/** Where each attribution and licence lives. Opened in the browser, which may be nowhere. */
const EDRDG_LICENCE_URL = "https://www.edrdg.org/edrdg/licence.html";
const MANGA_OCR_URL = "https://github.com/kha-white/manga-ocr";
const MLKIT_TERMS_URL = "https://developers.google.com/ml-kit/terms";
const WANIKANI_URL = "https://www.wanikani.com/";
const GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html";
const CIRCLETOSEARCH_URL = "https://github.com/AKS-Labs/CircleToSearch";
const MANROPE_URL = "https://github.com/googlefonts/manrope";
const WANAKANA_URL = "https://github.com/esnaultdev/wanakana-kt";

/** One paragraph card: a policy, the icon that stands for it, and the sentence that says it. */
function AboutCard({
  icon,
  title,
  body,
}: {
  icon: "shield" | "cloud_off";
  title: string;
  body: string;
}) {
  const {colors} = useTheme();
  return (
    <Card>
      <View style={{padding: spacing.xl}}>
        <View style={{flexDirection: "row", alignItems: "center"}}>
          <Icon name={icon} size={20} color={colors.primary} />
          <Text style={[typeScale.titleMedium, {color: colors.onSurface, marginStart: spacing.s}]}>
            {title}
          </Text>
        </View>
        <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant, marginTop: spacing.s}]}>
          {body}
        </Text>
      </View>
    </Card>
  );
}

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
      <View style={{marginTop: spacing.s}}>
        <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>Gaten</Text>
        <Text style={[typeScale.bodyMedium, {color: colors.onSurfaceVariant, marginTop: spacing.xs}]}>
          Circle Japanese on screen. Read it offline.
        </Text>
        <Text style={[typeScale.labelMedium, {color: colors.primary, marginTop: spacing.xs}]}>
          {`Gaten ${versionName}`}
        </Text>
      </View>

      <View style={{marginTop: spacing.xl}}>
        <AboutCard
          icon="shield"
          title="Offline by default"
          body="The dictionary, the recognition and the lookup all run on this device, and the screenshot never leaves it."
        />
        <View style={{marginTop: spacing.l}}>
          <AboutCard
            icon="cloud_off"
            title="Two network uses, both optional"
            body="Downloading a text-recognition model from huggingface.co, and WaniKani if you connect a personal access token. Nothing else is ever sent."
          />
        </View>
      </View>

      <SettingsGroup title="Attributions" style={{marginTop: spacing.xl}}>
        <SettingsRow
          icon="public"
          title="EDRDG dictionary"
          summary="JMdict and JMnedict, the dictionary Gaten reads: © EDRDG, CC BY-SA 4.0"
          value="edrdg.org"
          onPress={() => open(EDRDG_LICENCE_URL)}
        />
        <SettingsRow
          icon="public"
          title="manga-ocr"
          summary="Optional stylized-text model (kha-white, Apache-2.0) run on ONNX Runtime"
          value="github.com/kha-white/manga-ocr"
          onPress={() => open(MANGA_OCR_URL)}
        />
        <SettingsRow
          icon="public"
          title="ML Kit"
          summary="Google's on-device text recognition, which reads the screen when no model is installed"
          value="developers.google.com/ml-kit"
          onPress={() => open(MLKIT_TERMS_URL)}
        />
        <SettingsRow
          icon="public"
          title="WaniKani"
          summary="Reference content © Tofugu LLC. Gaten is not affiliated with WaniKani, and nothing from it is bundled with the app."
          value="wanikani.com"
          onPress={() => open(WANIKANI_URL)}
        />
        <SettingsRow
          icon="public"
          title="Manrope typeface"
          summary="SIL Open Font License 1.1 — Mikhail Sharanda and the Manrope Project Authors. Bundled to draw Gaten's Latin text."
          value="github.com/googlefonts/manrope"
          onPress={() => open(MANROPE_URL)}
        />
        <SettingsRow
          icon="public"
          title="wanakana"
          summary="MIT licence — the Kotlin port of WanaKana. Turns typed romaji into kana for dictionary search."
          value="github.com/esnaultdev/wanakana-kt"
          onPress={() => open(WANAKANA_URL)}
        />
      </SettingsGroup>

      <SettingsGroup title="Licence" style={{marginTop: spacing.xl}}>
        <Text
          style={[
            typeScale.bodySmall,
            {color: colors.onSurfaceVariant, paddingHorizontal: spacing.l, paddingVertical: spacing.m},
          ]}
        >
          Gaten is free software under GPL-3.0-or-later, derived from CircleToSearch by AKS-Labs.
        </Text>
        <SettingsRow
          icon="gavel"
          title="Read the licence"
          summary="gnu.org/licenses/gpl-3.0"
          onPress={() => open(GPL_URL)}
        />
        <SettingsRow
          icon="gavel"
          title="CircleToSearch on GitHub"
          summary="github.com/AKS-Labs/CircleToSearch"
          onPress={() => open(CIRCLETOSEARCH_URL)}
        />
      </SettingsGroup>

      <View style={{height: spacing.xl}} />
    </SettingsPageScaffold>
  );
}