/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import Svg, {Circle, Path} from "react-native-svg";

export type InkIconName =
  | "search"
  | "shield"
  | "text"
  | "cards"
  | "torii"
  | "sun"
  | "user"
  | "chevron";

/** The shared outline glyphs from the approved Ink preview. */
export function InkIcon({
  name,
  size = 20,
  color,
}: {
  name: InkIconName;
  size?: number;
  color: string;
}) {
  return (
    <Svg
      width={size}
      height={size}
      viewBox={name === "chevron" ? "0 0 16 16" : "0 0 24 24"}
      fill="none"
      stroke={color}
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {name === "search" ? <><Circle cx={10.5} cy={10.5} r={6.5} /><Path d="m16 16 5 5" /></> : null}
      {name === "shield" ? <Path d="M12 3 5 6v5c0 5 3.5 8 7 10 3.5-2 7-5 7-10V6z" /> : null}
      {name === "text" ? <Path d="M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6" /> : null}
      {name === "cards" ? <Path d="M5 5h11v11H5zM8 8h11v11H8z" /> : null}
      {name === "torii" ? <Path d="M3 7h18M6 10h12M8 10v11m8-11v11M6 15h12" /> : null}
      {name === "sun" ? <><Circle cx={12} cy={12} r={4} /><Path d="M12 2v2m0 16v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></> : null}
      {name === "user" ? <><Circle cx={12} cy={12} r={9} /><Circle cx={12} cy={9} r={3} /><Path d="M6 19c.5-5 11.5-5 12 0" /></> : null}
      {name === "chevron" ? <Path d="m6 3 5 5-5 5" /> : null}
    </Svg>
  );
}
