/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The full-screen WaniKani pages the Search tab pushes: one behind a tapped kanji chip, one
 * behind a tapped radical component. No app bar — the panel brings its own heading and back
 * arrow, since the real title is not known until the lookup answers.
 *
 * A radical tap hands the id straight to the shell, which owns the Tick and the open-or-toggle
 * decision: only the shell knows whether the tap opens a new radical page or closes the open one
 * (a close stays silent).
 */
import React from "react";
import {View} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {WaniKaniPanel, type WaniKaniTarget} from "../wanikani/WaniKaniPanel";
import {useTheme} from "../theme";

export interface SearchKanjiScreenProps {
  character: string;
  onBack: () => void;
  onOpenRadical: (id: number) => void;
  onOpenSettingsPage: (page: "wanikani") => void;
}

export interface SearchRadicalScreenProps {
  id: number;
  onBack: () => void;
  onOpenRadical: (id: number) => void;
  onOpenSettingsPage: (page: "wanikani") => void;
}

function ReferencePage({
  target,
  onBack,
  onOpenRadical,
  onOpenSettingsPage,
}: {
  target: WaniKaniTarget;
  onBack: () => void;
  onOpenRadical: (id: number) => void;
  onOpenSettingsPage: (page: "wanikani") => void;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: colors.background,
        paddingTop: insets.top,
        paddingBottom: insets.bottom,
      }}
    >
      <WaniKaniPanel
        target={target}
        showBack
        onBack={onBack}
        scrollable
        onRadical={onOpenRadical}
        onSettings={() => onOpenSettingsPage("wanikani")}
      />
    </View>
  );
}

export function SearchKanjiScreen({
  character,
  onBack,
  onOpenRadical,
  onOpenSettingsPage,
}: SearchKanjiScreenProps) {
  return (
    <ReferencePage
      target={{kind: "kanji", character}}
      onBack={onBack}
      onOpenRadical={onOpenRadical}
      onOpenSettingsPage={onOpenSettingsPage}
    />
  );
}

export function SearchRadicalScreen({
  id,
  onBack,
  onOpenRadical,
  onOpenSettingsPage,
}: SearchRadicalScreenProps) {
  return (
    <ReferencePage
      target={{kind: "radical", id}}
      onBack={onBack}
      onOpenRadical={onOpenRadical}
      onOpenSettingsPage={onOpenSettingsPage}
    />
  );
}
