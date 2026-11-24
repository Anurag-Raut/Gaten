/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The Anki cards page while its saved settings are being read: the loaded page's own sections,
 * captions and row heights with quiet placeholders where the values go, so the form lands in place
 * instead of replacing a lone spinner. Same placeholder look as the WaniKani hub's skeleton rows.
 *
 * It stays blank for the first `SKELETON_DELAY_MS`: a read that finishes that fast never flashes a
 * placeholder. Screen readers get one busy, polite live-region label the whole time, including the
 * blank beat.
 */
import React, {useEffect, useState} from "react";
import {View, useWindowDimensions, type ViewStyle} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useTheme} from "../theme";
import {contentInsetFor, shape, spacing} from "../tokens";
import {CONTENT_COLUMN, SettingsGroup} from "./settings-components";

/** How long a read may take before the placeholder appears. */
export const SKELETON_DELAY_MS = 250;

/** True once `delayMs` has passed since mount. */
export function useAfterDelay(delayMs: number): boolean {
  const [elapsed, setElapsed] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setElapsed(true), delayMs);
    return () => clearTimeout(timer);
  }, [delayMs]);
  return elapsed;
}

function Bar({width, height, style}: {width: ViewStyle["width"]; height: number; style?: ViewStyle}) {
  const {colors} = useTheme();
  return <View style={[{width, height, borderRadius: 5, backgroundColor: colors.surfaceVariant}, style]} />;
}

function FieldSkeleton() {
  const {colors} = useTheme();
  return <View style={{minHeight: 49, borderRadius: shape.small, backgroundColor: colors.surfaceVariant}} />;
}

/** One study-deck row's geometry: 24 dp glyph, a name, and the switch's track. */
function DeckRowSkeleton({last, width}: {last?: boolean; width: ViewStyle["width"]}) {
  const {colors} = useTheme();
  return (
    <View
      style={{
        flexDirection: "row",
        alignItems: "center",
        minHeight: 60,
        paddingVertical: spacing.m,
        gap: spacing.m,
        borderBottomWidth: last ? 0 : 1,
        borderBottomColor: colors.outlineVariant,
      }}
    >
      <Bar width={24} height={24} style={{borderRadius: 7}} />
      <View style={{flex: 1, minWidth: 0}}>
        <Bar width={width} height={15} />
      </View>
      <Bar width={52} height={32} style={{borderRadius: 16}} />
    </View>
  );
}

export function AnkiSettingsSkeleton() {
  const insets = useSafeAreaInsets();
  const {width} = useWindowDimensions();
  const horizontalInset = contentInsetFor(width);
  const shown = useAfterDelay(SKELETON_DELAY_MS);
  const pad = {paddingVertical: spacing.m};
  return (
    <View
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel="Reading Anki settings"
      accessibilityLiveRegion="polite"
      accessibilityState={{busy: true}}
      importantForAccessibility="yes"
      style={{flex: 1}}
    >
      {shown && (
        <View
          // Same column, insets and top rhythm as the loaded page's scroller.
          style={{
            ...CONTENT_COLUMN,
            paddingLeft: insets.left + horizontalInset,
            paddingRight: insets.right + horizontalInset,
            paddingTop: spacing.s,
          }}
        >
          <SettingsGroup title="Anki" style={{marginTop: spacing.s}}>
            <View style={pad}>
              <View style={{minHeight: 48, justifyContent: "center"}}>
                <Bar width={150} height={16} />
              </View>
            </View>
          </SettingsGroup>

          <SettingsGroup title="Study decks" style={{marginTop: spacing.l}}>
            <View style={pad}>
              <DeckRowSkeleton width="52%" />
              <DeckRowSkeleton width="38%" />
              <DeckRowSkeleton width="46%" last />
              <View style={{minHeight: 48, justifyContent: "center", marginTop: spacing.s}}>
                <Bar width={120} height={16} />
              </View>
              <Bar width="82%" height={12} style={{marginTop: spacing.xs}} />
            </View>
          </SettingsGroup>

          <SettingsGroup title="Export" style={{marginTop: spacing.l}}>
            <View style={pad}>
              <Bar width={34} height={14} style={{marginVertical: 3, marginBottom: spacing.s + 3}} />
              <FieldSkeleton />
            </View>
            <View style={pad}>
              <Bar width={34} height={14} style={{marginVertical: 3, marginBottom: 3}} />
              <Bar width="42%" height={12} style={{marginVertical: 2.5, marginBottom: spacing.xs + 2.5}} />
              <FieldSkeleton />
            </View>
          </SettingsGroup>

          <SettingsGroup title="Card layout" style={{marginTop: spacing.l}}>
            <View style={pad}>
              <View style={{flexDirection: "row", alignItems: "center", minHeight: 48, paddingVertical: spacing.xs}}>
                <View style={{flex: 1}}>
                  <Bar width="60%" height={15} />
                </View>
                <Bar width={20} height={10} />
              </View>
            </View>
          </SettingsGroup>
        </View>
      )}
    </View>
  );
}
