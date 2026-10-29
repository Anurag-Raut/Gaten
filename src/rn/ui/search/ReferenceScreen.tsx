/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The full-screen WaniKani pages the Search tab pushes: one behind a tapped kanji chip, one
 * behind a tapped radical component. Full pages use the shared app bar above the subject heading;
 * the lookup overlay keeps its compact panel heading and local back action.
 *
 * A radical tap hands the id straight to the shell, which owns the Tick and the open-or-toggle
 * decision: only the shell knows whether the tap opens a new radical page or closes the open one
 * (a close stays silent).
 */
import React from "react";
import {View} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {WaniKaniPanel, type WaniKaniTarget} from "../wanikani/WaniKaniPanel";
import {RtkKanjiPanel} from "../rtk/RtkKanjiPanel";
import {useKanjiReferenceSource} from "../kanjiReference";
import {ScreenHeader} from "../ScreenHeader";
import {useTheme} from "../theme";

export interface SearchKanjiScreenProps {
  character: string;
  onBack: () => void;
  onOpenRadical: (id: number) => void;
  onOpenSettingsPage?: (page: "wanikani") => void;
  /**
   * Whether the page offers the WaniKani settings shortcut. The lookup overlay leaves it off: it is
   * a window over somebody else's screen and never offered a route into app settings.
   */
  showSettings?: boolean;
  /**
   * The lookup overlay hosts the page inside its own bottom sheet rather than as a full screen:
   * the sheet is not under the status bar and already paints the surface, so the page drops the
   * status-bar inset and the top padding and matches the sheet's own background.
   */
  overlay?: boolean;
}

export interface SearchRadicalScreenProps {
  id: number;
  onBack: () => void;
  onOpenRadical: (id: number) => void;
  onOpenSettingsPage?: (page: "wanikani") => void;
  /** @see SearchKanjiScreenProps.showSettings */
  showSettings?: boolean;
  /** @see SearchKanjiScreenProps.overlay */
  overlay?: boolean;
}

function ReferencePage({
  target,
  onBack,
  onOpenRadical,
  onOpenSettingsPage,
  showSettings = true,
  overlay = false,
}: {
  target: WaniKaniTarget;
  onBack: () => void;
  onOpenRadical: (id: number) => void;
  onOpenSettingsPage?: (page: "wanikani") => void;
  showSettings?: boolean;
  overlay?: boolean;
}) {
  const onSettings =
    showSettings && onOpenSettingsPage != null
      ? () => onOpenSettingsPage("wanikani")
      : undefined;
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  // The chosen reference decides what a kanji page is. Radicals exist only in WaniKani's subject
  // graph, so a radical page is always WaniKani's regardless of the choice.
  const source = useKanjiReferenceSource();
  const rememberKanji = target.kind === "kanji" && source === "rtk";
  return (
    <View
      style={{
        flex: 1,
        backgroundColor: overlay ? colors.surface : colors.background,
        paddingTop: overlay ? 0 : insets.top,
        paddingBottom: insets.bottom,
      }}
    >
      {!overlay && (
        <ScreenHeader
          title={target.kind === "kanji" ? "Kanji details" : "Radical details"}
          onBack={onBack}
        />
      )}
      {rememberKanji && target.kind === "kanji" ? (
        <RtkKanjiPanel
          character={target.character}
          showBack={overlay}
          onBack={onBack}
          scrollable
        />
      ) : (
        <WaniKaniPanel
          target={target}
          showBack={overlay}
          onBack={onBack}
          scrollable
          onRadical={onOpenRadical}
          onSettings={onSettings}
        />
      )}
    </View>
  );
}

export function SearchKanjiScreen({
  character,
  onBack,
  onOpenRadical,
  onOpenSettingsPage,
  showSettings,
  overlay,
}: SearchKanjiScreenProps) {
  return (
    <ReferencePage
      target={{kind: "kanji", character}}
      onBack={onBack}
      onOpenRadical={onOpenRadical}
      onOpenSettingsPage={onOpenSettingsPage}
      showSettings={showSettings}
      overlay={overlay}
    />
  );
}

export function SearchRadicalScreen({
  id,
  onBack,
  onOpenRadical,
  onOpenSettingsPage,
  showSettings,
  overlay,
}: SearchRadicalScreenProps) {
  return (
    <ReferencePage
      target={{kind: "radical", id}}
      onBack={onBack}
      onOpenRadical={onOpenRadical}
      onOpenSettingsPage={onOpenSettingsPage}
      showSettings={showSettings}
      overlay={overlay}
    />
  );
}
