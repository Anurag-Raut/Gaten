/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * A WaniKani subject's accepted meanings as a list, in the word detail page's manner: the primary
 * meaning is the lead in the accent, the others sit under it as a bulleted list, and a long list
 * shows the first few with the rest behind a "Show N more" toggle. A subject with a dozen accepted
 * answers then reads as one meaning with alternatives instead of a wall of accent text.
 */
import React, { useState } from "react";
import { StyleSheet, Text, View } from "react-native";
import { Collapsible, DisclosureChevron } from "../Collapsible";
import { PressableScale } from "../PressableScale";
import { useTheme } from "../theme";
import { spacing, typeScale } from "../tokens";
import { ReferenceValue } from "./ReferenceText";

/** The most alternatives shown at once; any beyond this go behind the toggle. */
const PREVIEW_ALTERNATES = 5;
const COLLAPSE_ABOVE = PREVIEW_ALTERNATES;
const BULLET_SIZE = 5;

export function ReferenceMeanings({ meanings }: { meanings: string[] }) {
  const { colors } = useTheme();
  const [expanded, setExpanded] = useState(false);
  const [primary, ...alternates] = meanings;
  if (primary == null) return null;
  const collapsible = alternates.length > COLLAPSE_ABOVE;
  const toggleLabel = expanded ? "Show fewer" : `Show ${alternates.length - PREVIEW_ALTERNATES} more`;
  const rows = alternates.map((meaning, index) => (
    <View key={index} style={styles.row}>
      <View style={[styles.bullet, { backgroundColor: `${colors.primary}80` }]} />
      <Text style={[typeScale.bodyMedium, styles.text, { color: colors.onSurface }]}>{meaning}</Text>
    </View>
  ));
  return (
    <View accessible={!collapsible} accessibilityLabel={collapsible ? undefined : meanings.join(", ")}>
      <ReferenceValue>{primary}</ReferenceValue>
      {alternates.length > 0 && (
        <View style={styles.list}>
          {collapsible ? rows.slice(0, PREVIEW_ALTERNATES) : rows}
          {collapsible && <Collapsible expanded={expanded}>{rows.slice(PREVIEW_ALTERNATES)}</Collapsible>}
        </View>
      )}
      {collapsible && (
        <PressableScale
          accessibilityRole="button"
          accessibilityLabel={toggleLabel}
          accessibilityState={{ expanded }}
          onPress={() => setExpanded((value) => !value)}
          style={styles.toggle}
        >
          <Text style={[typeScale.labelLarge, { color: colors.onSurfaceVariant }]}>{toggleLabel}</Text>
          <DisclosureChevron expanded={expanded} color={colors.onSurfaceVariant} size={16} />
        </PressableScale>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  list: { marginTop: spacing.s },
  // A meaning and its dot, the dot centred on the first line of the text.
  row: { flexDirection: "row", alignItems: "flex-start", gap: 10, paddingVertical: 3 },
  bullet: {
    width: BULLET_SIZE,
    height: BULLET_SIZE,
    borderRadius: BULLET_SIZE,
    marginTop: (typeScale.bodyMedium.lineHeight - BULLET_SIZE) / 2,
  },
  text: { flex: 1, minWidth: 0 },
  // Left-aligned under the bullets, 40 dp tall so it is an easy target without a heavy row.
  toggle: { minHeight: 40, flexDirection: "row", alignItems: "center", gap: spacing.xs, alignSelf: "flex-start" },
});
