/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The support sheet: the ways to review Gaten or donate to it, raised both by Settings' own row and
 * by the proactive popup. One component draws both, so the two entry points can never disagree
 * about the destinations or their order.
 */
import React from "react";
import {Text, View} from "react-native";
import {Button, Icon, IconBadge} from "../components";
import {DialogShell} from "../DialogShell";
import {useTheme} from "../theme";
import {spacing, typeScale} from "../tokens";
import {REVIEW_URL, SUPPORT_LINKS, openSupportLink} from "./support-links";

/** The one line above the actions: who this is for and what helps. */
const SUPPORT_BODY =
  "Gaten is a one-person project. If it has been useful, a review or a coffee helps it keep going.";

export function SupportSheet({
  title = "Support Gaten",
  body = SUPPORT_BODY,
  onDismiss,
  dismissalActions,
  onOpenLink = openSupportLink,
}: {
  /** Settings keeps the default; the proactive popup passes its softer wording. */
  title?: string;
  body?: string;
  onDismiss: () => void;
  /** Replaces the pinned Close action; the popup passes its own two dismissal actions instead. */
  dismissalActions?: React.ReactNode;
  /** Opens a destination; the popup passes the hook's own opener, Settings leaves the default. */
  onOpenLink?: (url: string) => void;
}) {
  const {colors} = useTheme();
  return (
    <DialogShell
      title={title}
      onDismiss={onDismiss}
      scrollBody
      titleLeading={
        <IconBadge
          name="favorite"
          size={40}
          background={colors.accentSubtle}
          color={colors.accent}
        />
      }
      actions={dismissalActions ?? <Button label="Close" variant="text" onPress={onDismiss} />}
    >
      <Text style={[typeScale.bodyMedium, {color: colors.onSurface}]}>{body}</Text>
      <View style={{gap: spacing.s, marginTop: spacing.l}}>
        <Button
          label="Rate on Play Store"
          variant="tonal"
          fullWidth
          leading={<Icon name="star" size={20} color={colors.onActionSecondary} />}
          onPress={() => onOpenLink(REVIEW_URL)}
        />
        {SUPPORT_LINKS.map((link) => (
          <Button
            key={link.label}
            label={link.label}
            variant="outlined"
            fullWidth
            leading={<Icon name={link.icon} size={20} color={colors.accent} />}
            onPress={() => onOpenLink(link.url)}
          />
        ))}
      </View>
    </DialogShell>
  );
}
