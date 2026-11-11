/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The base of every dialog the app raises: dimmed backdrop, centred card, rounded. It lives at the
 * app level, not under `ui/settings`, because the review flow and the study hubs raise the same
 * shell as the settings pages do.
 *
 * A caller may ask for the bounded layout (`fitToViewport`): the card then fits the safe viewport,
 * the title and the pinned action keep their natural height, and the scrolling body takes what is
 * left, up to 440 dp — which is what keeps Close reachable once the reader's text size has grown the
 * title and the instructions alike. It is opt-in because every dialog written before it was drawn
 * against the fixed 360 dp body, and none of them was asked to change.
 */
import React from "react";
import {
  Modal,
  Pressable,
  ScrollView,
  Text,
  View,
  useWindowDimensions,
} from "react-native";
import {useSafeAreaInsets} from "react-native-safe-area-context";
import {useTheme} from "./theme";
import {shape, spacing, typeScale} from "./tokens";

/** The tallest a bounded scrolling body may stand, however much room the viewport has. */
const MAX_BODY_HEIGHT = 440;

/** The scrolling body's standing height for a card that is not bounded by the viewport. */
const FIXED_BODY_HEIGHT = 360;

export function DialogShell({
  title,
  titleLeading,
  children,
  actions,
  onDismiss,
  scrollBody = false,
  fitToViewport = false,
  footer,
  bodyGap = spacing.m,
}: {
  title: string;
  /** Drawn beside the title and centred against it — the provider's own mark on a setup dialog. */
  titleLeading?: React.ReactNode;
  children: React.ReactNode;
  actions: React.ReactNode;
  onDismiss: () => void;
  scrollBody?: boolean;
  /**
   * Bound the whole card by the safe viewport, with the body taking the height that is left (up to
   * 440 dp) and the horizontal rhythm tightening to 16 dp on a phone narrower than 360 dp. Absent,
   * the dialog keeps the fixed 360 dp body and the card's own padding it has always had.
   */
  fitToViewport?: boolean;
  /** Pinned under the body, scrolling or not: what the reader must reach whatever the body's length. */
  footer?: React.ReactNode;
  /** The gap between the title and the body; the disclosure pulls its first heading closer. */
  bodyGap?: number;
}) {
  const {colors} = useTheme();
  const {width, height} = useWindowDimensions();
  const insets = useSafeAreaInsets();
  // A phone narrower than the card's own ceiling tightens the horizontal rhythm — 16 dp of backdrop
  // margin and card padding rather than 24 — the same step the app's content inset takes.
  const narrow = width < 360;
  const backdropInset = narrow ? spacing.l : spacing.xl;
  const cardInset = narrow ? spacing.l : spacing.xl;
  // A bounded card fits the safe viewport inside the backdrop's own 24 dp of margin; a card that
  // needs more than that gives up the body's height, never the title's or the action's.
  const cardMaxHeight = height - insets.top - insets.bottom - spacing.xl * 2;
  return (
    <Modal transparent animationType="fade" onRequestClose={onDismiss} visible>
      <Pressable
        style={{
          flex: 1,
          backgroundColor: "rgba(0,0,0,0.5)",
          alignItems: "center",
          justifyContent: "center",
          ...(fitToViewport
            ? {paddingVertical: spacing.xl, paddingHorizontal: backdropInset}
            : {padding: spacing.xl}),
        }}
        onPress={onDismiss}
      >
        <Pressable
          style={{
            backgroundColor: colors.surface,
            borderRadius: shape.medium,
            width: "100%",
            maxWidth: 360,
            ...(fitToViewport
              ? {
                  paddingVertical: spacing.xl,
                  paddingHorizontal: cardInset,
                  maxHeight: cardMaxHeight,
                  // Only a card that does not fit the viewport shrinks, and it shrinks at the body.
                  flexShrink: 1,
                }
              : {padding: spacing.xl}),
          }}
          onPress={(event) => event.stopPropagation()}
        >
          {/* The title's own row: a mark, when the caller has one, sits beside the words and never
              takes width from them. */}
          <View style={{flexDirection: "row", alignItems: "center", gap: spacing.m}}>
            {titleLeading != null && <View style={{flexShrink: 0}}>{titleLeading}</View>}
            <Text style={[typeScale.titleLarge, {color: colors.onSurface, flex: 1, minWidth: 0}]}>
              {title}
            </Text>
          </View>
          {scrollBody ? (
            <ScrollView
              showsVerticalScrollIndicator={false}
              showsHorizontalScrollIndicator={false}
              style={{
                maxHeight: fitToViewport ? MAX_BODY_HEIGHT : FIXED_BODY_HEIGHT,
                marginTop: bodyGap,
                ...(fitToViewport ? {flexShrink: 1} : null),
              }}
            >
              {children}
            </ScrollView>
          ) : (
            <View style={{marginTop: bodyGap, ...(fitToViewport ? {flexShrink: 1} : null)}}>
              {children}
            </View>
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
