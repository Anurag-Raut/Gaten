/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The three first-run illustrations, one per setup page: a reading pane with a lens over a selected
 * character, a deck becoming a review card with its rating controls, and a torii beside the two
 * subjects it supplies.
 *
 * They are one family: the same 340 x 220 canvas, the same ground ellipse, the same opacity ladder
 * for structural ink and supporting detail, and the same dashed-path rhythm — only the central
 * object changes. Every colour is a theme role (`surface` for the opaque overlapping shells,
 * `surfaceVariant` for the quiet insets, `iconSecondary` for the ink, `accent` for the single
 * meaningful mark a piece carries), so light and dark repaint the same geometry and nothing is drawn
 * in a literal white. Each piece scales by its `width` prop; the chrome that places it owns its
 * accessibility hiding and its responsive mode.
 *
 * The kanji are the bundled face's own outlines, not approximations — see [GLYPH_DAY].
 */
import React from "react";
import Svg, {Circle, Ellipse, G, Path, Rect} from "react-native-svg";
import {useTheme} from "../theme";

/** The shared canvas. Every piece is drawn in this box and scales by its width. */
const VIEW_WIDTH = 340;
const VIEW_HEIGHT = 220;

/**
 * 日 and 山 as IBM Plex Sans JP Regular's own outlines, extracted from the bundled
 * `app/src/main/res/font/ibm_plex_sans_jp_regular.ttf` (unitsPerEm 1000, y up) rather than redrawn
 * from lines. Each is placed by the transform on its `<Kanji>` below, which fits the outline's tight
 * ink bounds proportionally inside the box the piece reserves for it.
 */
const GLYPH_DAY = "M152 -64V782H848V-64H782V14H219V-64ZM782 433V721H219V433ZM219 373V76H782V373Z";
const GLYPH_MOUNTAIN = "M831 79V660H897V-64H831V16H170V-64H103V662H170V79H459V821H526V79Z";

/** Every piece stands on the same ground: one flat ellipse at (170,184), barely there in either mode. */
function Ground() {
  const {colors} = useTheme();
  return <Ellipse cx={170} cy={184} rx={82} ry={4} fill={colors.onSurfaceVariant} fillOpacity={0.04} />;
}

/**
 * One kanji in the glyph tone, placed by a `translate(…) scale(s -s)` that fits its ink bounds into
 * the reserved box: the font draws y up, so the negative y scale flips it onto the canvas.
 */
function Kanji({outline, transform}: {outline: string; transform: string}) {
  const {colors} = useTheme();
  return (
    <G transform={transform} fill={colors.onSurfaceVariant} fillOpacity={0.6}>
      <Path d={outline} />
    </G>
  );
}

/** Page 1: a reading pane, one character selected and enlarged through a lens. */
export function LookupArtwork({width}: {width: number}) {
  const {colors} = useTheme();
  return (
    <Svg
      width={width}
      height={Math.round((width * VIEW_HEIGHT) / VIEW_WIDTH)}
      viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
      preserveAspectRatio="xMidYMid meet"
    >
      <Ground />
      <Path
        d="M66 128 C44 87 55 43 92 32"
        stroke={colors.iconSecondary}
        strokeOpacity={0.16}
        strokeWidth={1.5}
        strokeDasharray="3 8"
      />
      <G transform="rotate(-5 153 103)">
        <Rect
          x={61}
          y={39}
          width={184}
          height={128}
          rx={14}
          fill={colors.surface}
          stroke={colors.iconSecondary}
          strokeOpacity={0.45}
          strokeWidth={1.8}
        />
        <Path d="M80 59 H117" stroke={colors.iconSecondary} strokeOpacity={0.23} strokeWidth={1.7} />
        <Kanji outline={GLYPH_DAY} transform="translate(106.759 101.379) scale(0.03448 -0.03448)" />
        <Rect
          x={106}
          y={67}
          width={38}
          height={44}
          rx={4}
          fill={colors.accent}
          fillOpacity={0.03}
          stroke={colors.accent}
          strokeOpacity={0.32}
          strokeWidth={1.8}
        />
        <Path
          d="M80 87 H98 M153 87 H219"
          stroke={colors.iconSecondary}
          strokeOpacity={0.23}
          strokeWidth={1.7}
        />
        <Path
          d="M80 126 H210 M80 140 H172"
          stroke={colors.iconSecondary}
          strokeOpacity={0.23}
          strokeWidth={1.7}
        />
      </G>
      <Path d="M252 145 L280 174" stroke={colors.iconSecondary} strokeOpacity={0.65} strokeWidth={6} />
      <Circle
        cx={227}
        cy={120}
        r={35}
        fill={colors.surface}
        stroke={colors.iconSecondary}
        strokeOpacity={0.65}
        strokeWidth={3}
      />
      <Circle cx={227} cy={120} r={29} fill={colors.accent} fillOpacity={0.03} />
      <Kanji outline={GLYPH_DAY} transform="translate(205.448 135.474) scale(0.0431 -0.0431)" />
      <Path
        d="M207 117 C208 109 213 103 220 101"
        stroke={colors.iconSecondary}
        strokeOpacity={0.2}
        strokeWidth={2}
      />
    </Svg>
  );
}

/** Page 2: a deck becoming a review card, with four quiet rating controls. */
export function AnkiArtwork({width}: {width: number}) {
  const {colors} = useTheme();
  return (
    <Svg
      width={width}
      height={Math.round((width * VIEW_HEIGHT) / VIEW_WIDTH)}
      viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
      preserveAspectRatio="xMidYMid meet"
    >
      <Ground />
      <Path
        d="M72 116 C53 76 78 37 118 32"
        stroke={colors.iconSecondary}
        strokeOpacity={0.16}
        strokeWidth={1.5}
        strokeDasharray="3 8"
      />
      <G transform="rotate(-10 142 112)">
        <Rect
          x={83}
          y={57}
          width={118}
          height={110}
          rx={13}
          fill={colors.surface}
          stroke={colors.iconSecondary}
          strokeOpacity={0.3}
          strokeWidth={1.5}
        />
      </G>
      <G transform="rotate(-4 158 106.5)">
        <Rect
          x={99}
          y={49}
          width={118}
          height={115}
          rx={13}
          fill={colors.surface}
          stroke={colors.iconSecondary}
          strokeOpacity={0.3}
          strokeWidth={1.5}
        />
      </G>
      <G transform="rotate(5 178.5 103.5)">
        <Rect
          x={119}
          y={45}
          width={119}
          height={117}
          rx={13}
          fill={colors.surface}
          stroke={colors.iconSecondary}
          strokeOpacity={0.45}
          strokeWidth={1.8}
        />
        <Rect x={133} y={59} width={91} height={60} rx={8} fill={colors.surfaceVariant} fillOpacity={0.5} />
        <Kanji outline={GLYPH_DAY} transform="translate(154.859 107.974) scale(0.04728 -0.04728)" />
        <Path
          d="M145 132 H207 M145 142 H182"
          stroke={colors.iconSecondary}
          strokeOpacity={0.23}
          strokeWidth={1.7}
        />
      </G>
      {[158, 191, 224, 257].map((x) => (
        <Rect
          key={x}
          x={x}
          y={145}
          width={29}
          height={30}
          rx={7}
          fill={colors.surfaceVariant}
          fillOpacity={0.5}
          stroke={colors.iconSecondary}
          strokeOpacity={0.23}
          strokeWidth={1.2}
        />
      ))}
      <Path
        d="M166 157 H179 M199 157 H212 M232 157 H245 M265 157 H278"
        stroke={colors.iconSecondary}
        strokeOpacity={0.23}
        strokeWidth={1.7}
      />
      <Path d="M232 166 H245" stroke={colors.accent} strokeOpacity={0.32} strokeWidth={1.8} />
    </Svg>
  );
}

/** Page 3: a torii beside two subject cards, one being learned and one reviewed. */
export function WaniKaniArtwork({width}: {width: number}) {
  const {colors} = useTheme();
  return (
    <Svg
      width={width}
      height={Math.round((width * VIEW_HEIGHT) / VIEW_WIDTH)}
      viewBox={`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`}
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
      preserveAspectRatio="xMidYMid meet"
    >
      <Ground />
      <Path
        d="M163 98 C176 76 182 74 192 84"
        stroke={colors.iconSecondary}
        strokeOpacity={0.16}
        strokeWidth={1.5}
        strokeDasharray="3 8"
      />
      <Path
        d="M164 126 C183 124 197 133 214 141"
        stroke={colors.iconSecondary}
        strokeOpacity={0.16}
        strokeWidth={1.5}
        strokeDasharray="3 8"
      />
      <Path d="M72 72 Q120 87 168 72" stroke={colors.iconSecondary} strokeOpacity={0.55} strokeWidth={6} />
      <Path d="M84 88 H156" stroke={colors.iconSecondary} strokeOpacity={0.5} strokeWidth={4} />
      <Path d="M96 90 V170 M144 90 V170" stroke={colors.iconSecondary} strokeOpacity={0.55} strokeWidth={5} />
      <Path d="M88 170 H152" stroke={colors.iconSecondary} strokeOpacity={0.4} strokeWidth={3} />
      <Path
        d="M112 106 V124 M128 106 V124 M108 126 H132"
        stroke={colors.iconSecondary}
        strokeOpacity={0.28}
        strokeWidth={2}
      />
      <G transform="rotate(8 219 87.5)">
        <Rect
          x={185}
          y={48}
          width={68}
          height={79}
          rx={11}
          fill={colors.surface}
          stroke={colors.iconSecondary}
          strokeOpacity={0.45}
          strokeWidth={1.8}
        />
        <Kanji outline={GLYPH_DAY} transform="translate(201.759 91.379) scale(0.03448 -0.03448)" />
        <Path
          d="M198 107 H240 M198 115 H225"
          stroke={colors.iconSecondary}
          strokeOpacity={0.23}
          strokeWidth={1.7}
        />
      </G>
      <G transform="rotate(-7 248 143)">
        <Rect
          x={213}
          y={109}
          width={70}
          height={68}
          rx={11}
          fill={colors.surface}
          stroke={colors.iconSecondary}
          strokeOpacity={0.45}
          strokeWidth={1.8}
        />
        <Rect x={223} y={116} width={50} height={35} fill={colors.accent} fillOpacity={0.03} />
        <Kanji outline={GLYPH_MOUNTAIN} transform="translate(231.616 145.903) scale(0.03277 -0.03277)" />
        <Path d="M227 161 H269" stroke={colors.accent} strokeOpacity={0.32} strokeWidth={1.8} />
      </G>
    </Svg>
  );
}
