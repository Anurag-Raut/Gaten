/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The drawing of a radical WaniKani has no character for. It comes from the same reference lookup the
 * kanji pages use, so it is cached after the first time; while it is on its way the prompt is simply
 * empty rather than a word the reader is being asked for.
 *
 * Shared by the review and lesson screens: a radical that exists only as an image is the same picture
 * whether it is being asked about or taught.
 */
import React, {useEffect, useMemo, useState} from "react";
import {StyleSheet, View} from "react-native";
import * as GatenWaniKani from "../../native/GatenWaniKani";
import {GatenCardWebView} from "../../native/GatenCardWebView";
import {svgDocument} from "../wanikani/WaniKaniPanel";
import {useTheme} from "../theme";

export function RadicalDrawing({subjectId}: {subjectId: number}) {
  const {colors, dark} = useTheme();
  const [svg, setSvg] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setSvg(null);
    void GatenWaniKani.lookupRadical(subjectId)
      .then((result) => {
        if (cancelled) return;
        setSvg(
          result.status === "available" && result.subject.kind === "radical"
            ? result.subject.svg ?? null
            : null
        );
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [subjectId]);

  const document = useMemo(
    () => (svg == null ? null : svgDocument(svg, dark)),
    [svg, dark]
  );

  if (document == null) return <View style={styles.drawing} />;
  return (
    <View style={styles.drawing} accessibilityLabel="Radical">
      <GatenCardWebView
        document={document}
        backgroundColor={colors.background}
        mode="svg"
        style={styles.drawing}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  drawing: {width: 99, height: 99, marginTop: 22, marginBottom: 8},
});
