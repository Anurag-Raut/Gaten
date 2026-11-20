/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The in-app feedback sheet raised from Settings → App → Send feedback.
 *
 * Keeps Gaten's privacy contract intact: nothing is sent automatically, and the exact non-PII
 * app/Android summary included in bug reports is shown right in the dialog before the reader taps.
 */
import React, {useEffect, useState} from "react";
import {StyleSheet, Text, View} from "react-native";
import * as GatenAppInfo from "../../native/GatenAppInfo";
import type {DiagnosticsInfo} from "../../native/GatenAppInfo";
import {Button, Icon, IconBadge} from "../components";
import {DialogShell} from "../DialogShell";
import {useTheme} from "../theme";
import {shape, spacing, typeScale} from "../tokens";
import {
  copyDiagnostics,
  diagnosticsSummaryLine,
  openFeedbackDestination,
  type FeedbackKind,
} from "./feedback-links";

const FEEDBACK_BODY =
  "Found a bug or have an idea for Gaten? Reports open on GitHub — nothing is sent from the app automatically.";

export function FeedbackSheet({
  onDismiss,
  onOpenFeedback,
  onCopyDiagnostics,
}: {
  onDismiss: () => void;
  onOpenFeedback?: (kind: FeedbackKind, diagnostics: DiagnosticsInfo | null) => void;
  onCopyDiagnostics?: (diagnostics: DiagnosticsInfo | null) => void;
}) {
  const {colors} = useTheme();
  const [diagnostics, setDiagnostics] = useState<DiagnosticsInfo | null>(null);

  useEffect(() => {
    let cancelled = false;
    void GatenAppInfo.getDiagnosticsInfo()
      .then((info) => {
        if (!cancelled) setDiagnostics(info);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const openDestination = (kind: FeedbackKind) => {
    if (onOpenFeedback) {
      onOpenFeedback(kind, diagnostics);
    } else {
      void openFeedbackDestination(kind, diagnostics);
    }
  };

  const handleCopy = () => {
    if (onCopyDiagnostics) {
      onCopyDiagnostics(diagnostics);
    } else {
      void copyDiagnostics(diagnostics);
    }
  };

  return (
    <DialogShell
      title="Send feedback"
      onDismiss={onDismiss}
      scrollBody
      titleLeading={
        <IconBadge
          name="help"
          size={40}
          background={colors.accentSubtle}
          color={colors.accent}
        />
      }
      actions={<Button label="Close" variant="text" onPress={onDismiss} />}
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{FEEDBACK_BODY}</Text>

      <View
        style={[
          styles.diagnosticsBox,
          {
            backgroundColor: colors.surfaceContainerLow,
            borderColor: colors.outlineVariant,
          },
        ]}
        accessible
        accessibilityLabel={`Included in bug reports: ${diagnosticsSummaryLine(diagnostics)}`}
      >
        <Text style={[typeScale.labelSmall, {color: colors.onSurfaceVariant}]}>
          Included in bug reports
        </Text>
        <Text style={[typeScale.bodySmall, {color: colors.onSurface, marginTop: spacing.xs}]}>
          {diagnosticsSummaryLine(diagnostics)}
        </Text>
      </View>

      <View style={{gap: spacing.s, marginTop: spacing.l}}>
        <Button
          label="Report a bug"
          variant="tonal"
          fullWidth
          leading={<Icon name="open_in_new" size={20} color={colors.onActionSecondary} />}
          onPress={() => openDestination("bug")}
        />
        <Button
          label="Suggest a feature"
          variant="outlined"
          fullWidth
          leading={<Icon name="add" size={20} color={colors.accent} />}
          onPress={() => openDestination("feature")}
        />
        <Button
          label="Copy device info"
          variant="outlined"
          fullWidth
          leading={<Icon name="content_copy" size={20} color={colors.accent} />}
          onPress={handleCopy}
        />
      </View>
    </DialogShell>
  );
}

const styles = StyleSheet.create({
  diagnosticsBox: {
    marginTop: spacing.m,
    paddingHorizontal: spacing.m,
    paddingVertical: spacing.s,
    borderRadius: shape.small,
    borderWidth: StyleSheet.hairlineWidth,
  },
});
