/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The five motifs the Analytics page draws where a source has no numbers to show: one per section, from
 * the geometry the design supplies, in the reader's accent on the page's own canvas. None of them is a
 * chart — a source that cannot be read has nothing to plot — so no legend, figure or axis is invented
 * here. Each is decorative: the sentence beside it carries the status, and every piece is hidden from
 * accessibility services by the block that draws it.
 */
import React from "react";
import Svg, {Circle, G, Path, Rect} from "react-native-svg";

/** The two tones every motif is drawn from: the accent, and the canvas its cut-outs show through. */
export interface Artwork {
  color: string;
  background: string;
}

/** AnkiDroid: a deck with a star on it, and the orbit behind it. */
export function AnkiConnectionArt({color, background}: Artwork) {
  return (
    <Svg viewBox="0 0 120 96" width="100%" height="100%">
      <Circle cx={59} cy={47} r={39} fill={color} fillOpacity={0.065} />
      <Path
        d="M21 37c-2-18 33-28 62-15"
        fill="none"
        stroke={color}
        strokeOpacity={0.27}
        strokeWidth={1.5}
        strokeDasharray={[3, 6]}
        strokeLinecap="round"
      />
      <G rotation={-13} originX={57} originY={47}>
        <Rect
          x={30}
          y={15}
          width={54}
          height={70}
          rx={9}
          fill={background}
          stroke={color}
          strokeWidth={2}
        />
        <Rect
          x={36}
          y={21}
          width={42}
          height={58}
          rx={5}
          fill="none"
          stroke={color}
          strokeOpacity={0.22}
          strokeWidth={1}
        />
        <Path
          d="m57 33 5.5 11.3 12.5 1.8-9 8.8 2.1 12.4L57 61.4 45.9 67.3 48 54.9l-9-8.8 12.5-1.8Z"
          fill={color}
          fillOpacity={0.78}
        />
      </G>
      <Circle cx={99} cy={21} r={2.8} fill={color} />
      <Circle cx={19} cy={74} r={1.8} fill={color} fillOpacity={0.56} />
      <Path
        d="M99 69v9m-4.5-4.5h9"
        fill="none"
        stroke={color}
        strokeWidth={1.5}
        strokeLinecap="round"
      />
    </Svg>
  );
}

/** WaniKani: a torii inside its own dashed orbit. */
export function WaniKaniConnectionArt({color}: Artwork) {
  return (
    <Svg viewBox="0 0 120 96" width="100%" height="100%">
      <Circle cx={60} cy={47} r={39} fill={color} fillOpacity={0.075} />
      <Circle
        cx={60}
        cy={47}
        r={31}
        fill="none"
        stroke={color}
        strokeOpacity={0.35}
        strokeWidth={1.5}
        strokeDasharray={[2, 6]}
      />
      <Path
        d="M26 33c23 7 45 7 68 0"
        fill="none"
        stroke={color}
        strokeWidth={5}
        strokeLinecap="round"
      />
      <Path
        d="M35 39h50M42 41v35m36-35v35M36 76h48"
        fill="none"
        stroke={color}
        strokeWidth={4}
        strokeLinecap="round"
      />
      <Path
        d="M54 47v14m12-14v14m-15 1h18"
        fill="none"
        stroke={color}
        strokeWidth={2.3}
        strokeLinecap="round"
      />
      <Path
        d="m22 16 3 3m70 56 3 3"
        fill="none"
        stroke={color}
        strokeOpacity={0.45}
        strokeWidth={2}
        strokeLinecap="round"
      />
    </Svg>
  );
}

/** Study activity: a quiet rhythm of bars under a dashed trend that claims nothing. */
export function StudyActivityArt({color}: Artwork) {
  return (
    <Svg viewBox="0 0 190 94" width="100%" height="100%">
      <Path
        d="M8 62c22-33 40-34 62-19S107 46 130 30s32-16 50 0"
        fill="none"
        stroke={color}
        strokeOpacity={0.35}
        strokeWidth={1.6}
        strokeDasharray={[3, 7]}
        strokeLinecap="round"
      />
      <G fill={color}>
        <Rect x={13} y={64} width={12} height={18} rx={3} opacity={0.17} />
        <Rect x={33} y={55} width={12} height={27} rx={3} opacity={0.27} />
        <Rect x={53} y={49} width={12} height={33} rx={3} opacity={0.2} />
        <Rect x={73} y={61} width={12} height={21} rx={3} opacity={0.38} />
        <Rect x={93} y={48} width={12} height={34} rx={3} opacity={0.25} />
        <Rect x={113} y={58} width={12} height={24} rx={3} opacity={0.32} />
        <Rect x={133} y={44} width={12} height={38} rx={3} opacity={0.49} />
        <Rect x={153} y={38} width={12} height={44} rx={3} opacity={0.22} />
      </G>
      <Path
        d="M91 8v7m-3.5-3.5h7M157 12v5m-2.5-2.5h5"
        fill="none"
        stroke={color}
        strokeWidth={1.8}
        strokeLinecap="round"
      />
    </Svg>
  );
}

/** Accuracy: a ring left deliberately incomplete, with no share stated in it. */
export function AccuracyArt({color, background}: Artwork) {
  return (
    <Svg viewBox="0 0 180 94" width="100%" height="100%">
      <Circle
        cx={90}
        cy={46}
        r={32}
        fill="none"
        stroke={color}
        strokeOpacity={0.15}
        strokeWidth={10}
      />
      <Circle
        cx={90}
        cy={46}
        r={32}
        fill="none"
        stroke={color}
        strokeWidth={10}
        strokeDasharray={[52, 201]}
        rotation={-90}
        originX={90}
        originY={46}
      />
      <Circle
        cx={90}
        cy={46}
        r={32}
        fill="none"
        stroke={color}
        strokeOpacity={0.36}
        strokeWidth={10}
        strokeDasharray={[43, 201]}
        strokeDashoffset={-52}
        rotation={-90}
        originX={90}
        originY={46}
      />
      <Circle
        cx={90}
        cy={46}
        r={32}
        fill="none"
        stroke={color}
        strokeOpacity={0.18}
        strokeWidth={10}
        strokeDasharray={[31, 201]}
        strokeDashoffset={-95}
        rotation={-90}
        originX={90}
        originY={46}
      />
      <Circle cx={90} cy={46} r={14} fill={background} />
      <Path
        d="M138 18v10m-5-5h10M45 72l-3 7"
        fill="none"
        stroke={color}
        strokeWidth={1.8}
        strokeLinecap="round"
      />
    </Svg>
  );
}

/** Level progress: bars that climb, with a pennant at the top and no level named. */
export function LevelProgressArt({color, background}: Artwork) {
  return (
    <Svg viewBox="0 0 180 94" width="100%" height="100%">
      <G fill={color}>
        <Rect x={26} y={64} width={22} height={17} rx={3} opacity={0.18} />
        <Rect x={53} y={54} width={22} height={27} rx={3} opacity={0.29} />
        <Rect x={80} y={43} width={22} height={38} rx={3} opacity={0.43} />
        <Rect x={107} y={31} width={22} height={50} rx={3} opacity={0.62} />
        <Rect x={134} y={21} width={22} height={60} rx={3} opacity={0.85} />
      </G>
      <Path
        d="M145 21V7l18 5-18 5"
        fill={background}
        stroke={color}
        strokeWidth={2}
        strokeLinejoin="round"
      />
      <Path
        d="M12 84h158"
        fill="none"
        stroke={color}
        strokeOpacity={0.2}
        strokeLinecap="round"
      />
      <Path
        d="M170 20v7m-3.5-3.5h7"
        fill="none"
        stroke={color}
        strokeWidth={1.6}
        strokeLinecap="round"
      />
    </Svg>
  );
}
