/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import {StyleProp, useWindowDimensions, View, ViewStyle} from "react-native";
import Svg, {Path} from "react-native-svg";
import {brandHeadingScale, TAB_HEADING_GLYPHS, TAB_HEADING_METRICS} from "./tabHeadingGlyphs";

/** The two titles this lettering exists for; every glyph below is one of their letters. */
export type TabHeadingLabel = "Analytics" | "Settings";

/**
 * The two tab titles, drawn in the shared logo's letterforms rather than in a font: a, e, n and t are
 * the approved wordmark's own contours, and the letters it does not carry are matching outlines.
 *
 * Each glyph is its own `Svg` in a wrapping row, so a title still behaves like text: the whole row
 * scales with the reader's font scale, and a title too long for the column wraps between letters
 * instead of clipping or shrinking. Every glyph's viewport is the full line box while its width is
 * the glyph's own ink width, so `xMidYMid meet` keeps the ink at one scale and on one baseline in
 * every row, wrapped or not. Glyph widths and the line height are the only numbers involved — there
 * is no font engine here.
 *
 * The heading is a real accessibility heading: `accessible` plus the label on the row makes a reader
 * hear "Analytics" once, and every glyph is hidden from the tree so it is not read as nine images.
 */
export function TabHeading({
  label,
  color,
  style,
}: {
  label: TabHeadingLabel;
  color: string;
  style?: StyleProp<ViewStyle>;
}) {
  const {fontScale} = useWindowDimensions();
  const {boxTop, boxBottom, gap, lineHeightDp} = TAB_HEADING_METRICS;
  const scale = brandHeadingScale(fontScale);
  const lineHeight = lineHeightDp * fontScale;
  const letters = [...label];

  return (
    <View
      accessible
      accessibilityRole="header"
      accessibilityLabel={label}
      style={[
        {
          flexDirection: "row",
          flexWrap: "wrap",
        },
        style,
      ]}
    >
      {letters.map((ch, index) => {
        const glyph = TAB_HEADING_GLYPHS[ch];
        if (!glyph) {
          // Unreachable while `label` is a TabHeadingLabel: the union and the glyph set are one list.
          throw new Error(`TabHeading has no glyph for "${ch}"`);
        }
        return (
          <Svg
            key={`${ch}${index}`}
            width={glyph.w * scale}
            height={lineHeight}
            viewBox={`0 ${boxTop} ${glyph.w} ${boxBottom - boxTop}`}
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{marginRight: index === letters.length - 1 ? 0 : gap * scale}}
          >
            <Path
              d={glyph.d}
              fill={color}
              fillRule="evenodd"
              {...(glyph.stroke > 0
                ? {stroke: color, strokeWidth: glyph.stroke, strokeLinejoin: "round" as const}
                : null)}
            />
          </Svg>
        );
      })}
    </View>
  );
}
