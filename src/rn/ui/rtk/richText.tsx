/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/** Renders the dataset's emphasis runs as nested React Native text. The parse lives in
 *  `emphasis.ts`, apart from the renderer, so it can be tested without one. */
import React, {useMemo} from "react";
import {Text, type StyleProp, type TextStyle} from "react-native";
import {useTheme} from "../theme";
import {parseEmphasis} from "./emphasis";

/**
 * A story paragraph with its emphasis rendered and its paragraphs kept apart. Long prose wraps
 * normally; the text is selectable so a story can be copied out of the page.
 */
export function EmphasizedText({
  text,
  style,
}: {
  text: string;
  style?: StyleProp<TextStyle>;
}) {
  const {colors} = useTheme();
  const paragraphs = useMemo(
    () => text.split(/\n+/).filter((paragraph) => paragraph.trim() !== ""),
    [text]
  );
  return (
    <Text selectable style={style}>
      {paragraphs.map((paragraph, index) => (
        <React.Fragment key={index}>
          {index > 0 && "\n\n"}
          {parseEmphasis(paragraph).map((span, spanIndex) => (
            <Text
              key={spanIndex}
              style={[
                span.bold ? {fontWeight: "700"} : null,
                span.italic ? {fontStyle: "italic"} : null,
                span.bold || span.italic ? {color: colors.onSurface} : null,
              ]}
            >
              {span.text}
            </Text>
          ))}
        </React.Fragment>
      ))}
    </Text>
  );
}
