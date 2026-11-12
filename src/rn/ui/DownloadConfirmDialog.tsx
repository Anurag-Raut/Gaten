/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The one confirmation before a recognition download leaves the device. The onboarding lookup page
 * and Settings' Text recognition row raise this same component, so the two entry points cannot show
 * different copy, actions or styling for the same transfer.
 *
 * It is a [DialogShell] with the app's own type roles, colours and buttons — no system alert — so it
 * follows light/dark and the reader's accent like every other dialog. Scrim and Back dismiss it as
 * Cancel, and the body scrolls rather than pushing the actions out of reach at a large text size.
 */
import React from "react";
import {Text, View, useWindowDimensions} from "react-native";
import {Button, IconBadge} from "./components";
import {DialogShell} from "./DialogShell";
import {downloadConfirmCopy} from "./download-confirm";
import {useTheme} from "./theme";
import {spacing, typeScale} from "./tokens";

export function DownloadConfirmDialog({
  name = "Enhanced",
  bytes,
  onCancel,
  onConfirm,
}: {
  /** The reader's name for the engine; the onboarding and settings rows both call it Enhanced. */
  name?: string;
  /** The download size the row already reported, in bytes; 0 shows that the size is not known. */
  bytes: number;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const {colors} = useTheme();
  const {fontScale} = useWindowDimensions();
  const copy = downloadConfirmCopy(name, bytes);
  // The largest system text no longer lets both labels share a line without shrinking below their
  // touch target, so the two actions take a full-width line each — the same step the disclosure
  // dialog takes.
  const stackedActions = fontScale >= 1.6;
  return (
    <DialogShell
      title={copy.title}
      onDismiss={onCancel}
      scrollBody
      fitToViewport
      titleLeading={
        <IconBadge
          name="download"
          size={40}
          background={colors.accentSubtle}
          color={colors.accent}
        />
      }
      actions={
        <View
          style={{
            width: "100%",
            flexDirection: stackedActions ? "column" : "row",
            justifyContent: "flex-end",
            alignItems: stackedActions ? "stretch" : "flex-end",
            gap: spacing.s,
          }}
        >
          <Button
            variant="text"
            label="Cancel"
            accessibilityHint="Closes the dialog without downloading."
            onPress={onCancel}
            fullWidth={stackedActions}
          />
          <Button
            label="Download"
            accessibilityHint={`Downloads ${name} once, then it works offline.`}
            onPress={onConfirm}
            fullWidth={stackedActions}
          />
        </View>
      }
    >
      <View style={{gap: spacing.m}}>
        <View
          accessible
          accessibilityLabel={
            copy.size == null ? "Download size not known" : `Download size ${copy.size}`
          }
          style={{flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: spacing.m}}
        >
          <Text style={[typeScale.labelMedium, {color: colors.onSurfaceVariant}]}>Download size</Text>
          <Text style={[typeScale.titleLarge, {color: colors.onSurface, textAlign: "right"}]}>
            {copy.size ?? "Not known"}
          </Text>
        </View>
        <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{copy.reason}</Text>
        <Text style={[typeScale.bodySmall, {color: colors.onSurfaceVariant}]}>{copy.note}</Text>
      </View>
    </DialogShell>
  );
}
