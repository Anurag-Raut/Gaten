/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Which reference a kanji page shows: WaniKani's online subjects, or Remembering the Kanji's
 * bundled offline dataset.
 *
 * The choice is remembered on the device through the RTK bridge, and it applies the moment the page
 * is left — every kanji page reads the same shared value. It is deliberately only about the
 * reference content: the WaniKani connection, its settings, reviews, lessons and analytics are
 * untouched by it, which is why the WaniKani row lives on its own page.
 */
import React, {useEffect, useState} from "react";
import {Text, View} from "react-native";
import {
  loadKanjiReferenceSource,
  setKanjiReferenceSource,
  useKanjiReferenceSource,
} from "../kanjiReference";
import type {KanjiReferenceSource} from "../kanjiReference";
import {PressableScale} from "../PressableScale";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {SettingsGroup, SettingsPageScaffold} from "./settings-components";

const OPTIONS: {source: KanjiReferenceSource; title: string; summary: string}[] = [
  {
    source: "wanikani",
    title: "WaniKani",
    summary: "Your WaniKani account's kanji pages and radicals · needs a connected account",
  },
  {
    source: "rtk",
    title: "Remembering the Kanji",
    summary: "Keywords, Heisig stories and Koohii mnemonics · bundled, works offline",
  },
];

export function KanjiReferenceSettingsScreen({onClose}: {onClose: () => void}) {
  const {colors} = useTheme();
  const source = useKanjiReferenceSource();
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);

  // The stored choice is read on arrival; until it is, nothing is shown as selected.
  useEffect(() => {
    let cancelled = false;
    void loadKanjiReferenceSource().then(() => {
      if (!cancelled) setReady(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const select = (next: KanjiReferenceSource) => {
    if (next === source || busy) return;
    setBusy(true);
    setFailed(false);
    void setKanjiReferenceSource(next)
      .catch(() => setFailed(true))
      .finally(() => setBusy(false));
  };

  return (
    <SettingsPageScaffold
      title="Kanji reference"
      onClose={onClose}
      help={[
        "This chooses which reference a kanji page shows: WaniKani's online kanji pages, or Remembering the Kanji's bundled keywords, stories and community mnemonics.",
        "Remembering the Kanji is stored on this device. It needs no account, token, network connection or extra download, and its pages work offline.",
        "Changing this does not connect or disconnect WaniKani, and it does not affect WaniKani reviews, lessons or analytics. The WaniKani connection has its own settings page.",
      ]}
    >
      <View accessibilityRole="radiogroup" accessibilityLabel="Kanji reference">
        <SettingsGroup title="Kanji reference" style={{marginTop: spacing.s}}>
          {OPTIONS.map((option) => {
            const selected = source === option.source;
            const disabled = !ready || busy;
            return (
              <PressableScale
                key={option.source}
                accessibilityRole="radio"
                accessibilityLabel={`${option.title}. ${option.summary}`}
                accessibilityState={{checked: selected, disabled}}
                disabled={disabled}
                onPress={() => select(option.source)}
                style={{
                  minHeight: 60,
                  paddingVertical: spacing.m,
                  flexDirection: "row",
                  alignItems: "center",
                  gap: spacing.m,
                }}
              >
                <View style={{flex: 1, minWidth: 0}}>
                  <Text style={[typeScale.titleMedium, {color: colors.onSurface}]}>
                    {option.title}
                  </Text>
                  <Text
                    style={[
                      typeScale.bodySmall,
                      {color: colors.onSurfaceVariant, marginTop: spacing.xs},
                    ]}
                  >
                    {option.summary}
                  </Text>
                </View>
                <View
                  style={{
                    width: 22,
                    height: 22,
                    borderRadius: 11,
                    borderWidth: 2,
                    borderColor: disabled
                      ? colors.disabled
                      : selected
                        ? colors.accent
                        : colors.iconSecondary,
                    alignItems: "center",
                    justifyContent: "center",
                  }}
                >
                  {selected && (
                    <View
                      style={{
                        width: 10,
                        height: 10,
                        borderRadius: 5,
                        backgroundColor: colors.accent,
                      }}
                    />
                  )}
                </View>
              </PressableScale>
            );
          })}
        </SettingsGroup>
      </View>
      <Text
        style={[
          typeScale.bodySmall,
          {color: colors.onSurfaceVariant, marginTop: spacing.l},
        ]}
      >
        Only what a kanji page shows changes. Your WaniKani connection, reviews, lessons and
        analytics stay exactly as they are.
      </Text>
      {failed && (
        <Text style={[typeScale.bodySmall, {color: colors.error, marginTop: spacing.s}]}>
          The choice could not be saved. Try again.
        </Text>
      )}
    </SettingsPageScaffold>
  );
}
