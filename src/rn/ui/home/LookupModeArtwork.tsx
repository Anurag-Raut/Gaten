/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Lookup mode's Home mark: a reading frame with two text strokes and a lens over its lower-right
 * corner — "inspect text in this area of the screen". The frame keeps it distinct from Home's
 * ordinary dictionary-search lens, and the two broad strokes suggest text without squeezing
 * unreadable kana into a header glyph.
 *
 * The drawing owns only the three resting states; `checking` is the status button's spinner, so it
 * never reaches here. On and off differ both by the filled/hollow lens and by the check, so the
 * states survive without the accent colour. Geometry, widths and opacities are identical in light
 * and dark; only the theme roles change.
 */
import React from "react";
import Svg, {Circle, Path} from "react-native-svg";
import {useTheme} from "../theme";

/** The states the mark itself draws. */
export type LookupMarkState = "on" | "off" | "unavailable";

const VIEWBOX = 28;
/** The artwork sits inside the header's 48 dp target at this size. */
const DEFAULT_SIZE = 26;
const STROKE = 1.8;

/**
 * The reading frame's four corners are drawn as three brackets — the lens owns the lower-right —
 * plus two text strokes. All four use the neutral ink at full opacity; the faint embellishments of
 * the large illustrations would make this small functional control too weak.
 */
const FRAME = [
  "M3 9V5Q3 3 5 3H9",
  "M15 3H19Q21 3 21 5V9",
  "M3 15V19Q3 21 5 21H9",
  "M7 9H15",
  "M7 13H11",
];

/** The lens with its diagonal handle, drawn in the state's own colour. */
const LENS = "M22 22L25 25";
/** The check drawn inside the enabled lens, in the action's own ink. */
const CHECK = "M15.5 18L17.3 19.8L20.7 16.4";
/** The question mark drawn inside the lens when the status could not be read. */
const QUESTION = "M16.3 16.3C16.3 14.7 19.7 14.7 19.7 16.4C19.7 17.5 18 17.6 18 18.8";

/** Lookup mode's status mark, centred by its caller in the 48 dp target. */
export function LookupModeMark({
  state,
  size = DEFAULT_SIZE,
}: {
  state: LookupMarkState;
  size?: number;
}) {
  const {colors} = useTheme();
  const enabled = state === "on";
  const ink = enabled ? colors.accent : colors.iconSecondary;
  return (
    <Svg width={size} height={size} viewBox={`0 0 ${VIEWBOX} ${VIEWBOX}`} fill="none">
      {FRAME.map((d) => (
        <Path
          key={d}
          d={d}
          stroke={colors.iconSecondary}
          strokeWidth={STROKE}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      ))}
      <Path d={LENS} stroke={ink} strokeWidth={STROKE} strokeLinecap="round" />
      <Circle
        cx={18}
        cy={18}
        r={5.5}
        fill={enabled ? colors.accent : "none"}
        stroke={ink}
        strokeWidth={STROKE}
      />
      {enabled && (
        <Path
          d={CHECK}
          stroke={colors.onActionPrimary}
          strokeWidth={1.6}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      )}
      {state === "unavailable" && (
        <>
          <Path
            d={QUESTION}
            stroke={colors.iconSecondary}
            strokeWidth={1.5}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <Circle cx={18} cy={21} r={0.65} fill={colors.iconSecondary} />
        </>
      )}
    </Svg>
  );
}
