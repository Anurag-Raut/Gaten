/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import {Text, View} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {IconButton} from "./components";
import {useTheme} from "./theme";
import {spacing, typeScale} from "./tokens";

/** The bar's own height, below the status bar; the screen adds the top inset above it. */
const SCREEN_HEADER_HEIGHT = 64;

/**
 * Shared pushed-page bar: back, left-aligned title and optional actions/count. Handles horizontal
 * safe areas; the screen supplies the top inset. Minimum height lets large text grow the bar.
 */
export function ScreenHeader({
  title,
  onBack,
  trailing,
  bordered = false,
}: {
  title: string;
  onBack: () => void;
  /** What sits on the trailing edge: a Help action, a Save button, a count, or nothing. */
  trailing?: React.ReactNode;
  /** Draw the bar's closing hairline, as the search screens' bars do. */
  bordered?: boolean;
}) {
  const {colors} = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View
      style={[
        {
          minHeight: SCREEN_HEADER_HEIGHT,
          flexDirection: "row",
          alignItems: "center",
          backgroundColor: colors.background,
          paddingLeft: insets.left,
          paddingRight: insets.right + spacing.l,
        },
        bordered && {borderBottomWidth: 1, borderBottomColor: colors.outlineVariant},
      ]}
    >
      <IconButton
        name="arrow_back"
        accessibilityLabel="Back"
        onPress={onBack}
        color={colors.onSurface}
      />
      <Text
        accessibilityRole="header"
        numberOfLines={1}
        style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1, minWidth: 0}]}
      >
        {title}
      </Text>
      {trailing}
    </View>
  );
}
