/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The base of every dialog the app raises: dimmed backdrop, centred card, rounded. It lives at the
 * app level, not under `ui/settings`, because the review flow and the study hubs raise the same
 * shell as the settings pages do.
 */
import React from "react";
import {Modal, Pressable, ScrollView, Text, View} from "react-native";
import {useTheme} from "./theme";
import {shape, spacing, typeScale} from "./tokens";

export function DialogShell({
  title,
  children,
  actions,
  onDismiss,
  scrollBody = false,
  footer,
  bodyGap = spacing.m,
}: {
  title: string;
  children: React.ReactNode;
  actions: React.ReactNode;
  onDismiss: () => void;
  scrollBody?: boolean;
  /** Pinned under the body, scrolling or not: what the reader must reach whatever the body's length. */
  footer?: React.ReactNode;
  /** The gap between the title and the body; the disclosure pulls its first heading closer. */
  bodyGap?: number;
}) {
  const {colors} = useTheme();
  return (
    <Modal transparent animationType="fade" onRequestClose={onDismiss} visible>
      <Pressable
        style={{
          flex: 1,
          backgroundColor: "rgba(0,0,0,0.5)",
          alignItems: "center",
          justifyContent: "center",
          padding: spacing.xl,
        }}
        onPress={onDismiss}
      >
        <Pressable
          style={{
            backgroundColor: colors.surface,
            borderRadius: shape.medium,
            padding: spacing.xl,
            width: "100%",
            maxWidth: 360,
          }}
          onPress={(event) => event.stopPropagation()}
        >
          <Text style={[typeScale.titleLarge, {color: colors.onSurface}]}>{title}</Text>
          {scrollBody ? (
            <ScrollView showsVerticalScrollIndicator={false} showsHorizontalScrollIndicator={false} style={{maxHeight: 360, marginTop: bodyGap}}>{children}</ScrollView>
          ) : (
            <View style={{marginTop: bodyGap}}>{children}</View>
          )}
          {footer}
          <View style={{flexDirection: "row", justifyContent: "flex-end", marginTop: spacing.l}}>
            {actions}
          </View>
        </Pressable>
      </Pressable>
    </Modal>
  );
}
