/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import Svg, {Circle, Path, SvgXml} from "react-native-svg";
import {useTheme} from "../theme";
import {homeSvgXml} from "./HomeSvgAssets";

type ArtworkSize = {width?: number; height?: number; dark?: boolean};
type MarkProps = {size?: number; dark?: boolean};
export type CatVariant = "default" | "sleeping" | "celebrating" | "happy" | "allDone";
export type WeekdayState = "empty" | "partial" | "checked";
export type StreakDayState = "complete" | "missed" | "today" | "empty" | "unknown" | "upcoming";

/** Renders a supplied XML asset at the requested size; its original viewBox preserves proportions. */
function Artwork({
  name,
  width,
  height,
  dark = false,
}: ArtworkSize & {name: Parameters<typeof homeSvgXml>[0]}) {
  return <SvgXml xml={homeSvgXml(name, dark)} width={width} height={height} />;
}

/**
 * The Anki and WaniKani marks from the approved Ink preview, drawn inside the service tiles.
 */
export function AnkiServiceMark({size = 42}: {size?: number}) {
  return (
    <Svg width={size} height={size} viewBox="0 0 32 32">
      <Path fill="#258ff0" d="m3 13 13-9 13 9-13 9Z" />
      <Path fill="#fff" d="m3 13 13 9 13-9v4l-13 9L3 17Z" />
      <Path fill="#258ff0" d="m3 18 13 9 13-9v4l-13 9L3 22Z" />
      <Path fill="#7ccaff" d="m24 2 1.5 3 3 1-3 1.5-1.5 3-1.5-3-3-1.5 3-1Z" />
    </Svg>
  );
}

export function ToriiServiceMark({size = 42}: {size?: number}) {
  return (
    <Svg width={size} height={size} viewBox="0 0 32 32">
      <Path fill="#ef5376" d="M3 7h26v4H3Z" />
      <Path fill="#e53f65" d="M7 12h18v3H7Z" />
      <Path fill="#dc3158" d="M9 14h4v15H9ZM19 14h4v15h-4Z" />
      <Path fill="#ef5376" d="M6 21h20v3H6Z" />
      <Path fill="#fb90a6" d="m5 9 3-6 3 2-3 5ZM23 5l3-3 3 3-4 4Z" />
    </Svg>
  );
}

export function StreakPowerIcon({size = 24, dark = false}: MarkProps) {
  return <Artwork name="streak_new" width={size} height={size} dark={dark} />;
}

export function ActiveFlameMark({size = 24, dark = false}: MarkProps) {
  return <Artwork name="flame" width={size} height={size} dark={dark} />;
}

/** Draws the streak flame in the current theme's single violet accent color. */
export function StreakFlameMark({width = 24, height = 28}: ArtworkSize) {
  const {colors} = useTheme();
  return (
    <Svg width={width} height={height} viewBox="0 0 24 30">
      <Path d="M14 1C15 7 8 10 9 16c-2-2-3-5-2-8C1 14 2 21 5 25c3 5 12 6 16-2 2-5 0-9-3-13-.1 4-2 6-3 6 2-6-1-11-1-15Z" fill={colors.accent} />
      <Path d="M12 16c1 4-3 5-1 8-3-1-3-4-3-4-4 7 3 10 6 7 3-3 2-7-2-11Z" fill={colors.background} />
    </Svg>
  );
}

export function StreakDayMark({state, size = 29}: MarkProps & {state: StreakDayState}) {
  const {colors} = useTheme();
  const complete = state === "complete";
  const missed = state === "missed";
  const today = state === "today";
  const upcoming = state === "upcoming";
  const fill = complete ? colors.accent : colors.background;
  const stroke = complete || today ? colors.accent : missed ? colors.iconSecondary : colors.outlineVariant;
  const mark = complete ? colors.onActionPrimary : stroke;
  return (
    <Svg width={size} height={size} viewBox="0 0 29 29">
      <Circle cx="14.5" cy="14.5" r="14" fill={fill} stroke={stroke} strokeWidth="1" />
      {complete ? (
        <Path d="m8.5 14.5 4 4 8-9" fill="none" stroke={mark} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      ) : state === "unknown" || missed ? (
        <Path d="M11.5 14.5h6" stroke={mark} strokeWidth="1.5" strokeLinecap="round" />
      ) : state === "empty" || today || upcoming ? (
        <Circle cx="14.5" cy="14.5" r="2.5" fill={today ? colors.accent : colors.iconSecondary} />
      ) : null}
    </Svg>
  );
}

export function CheckGreenMark({size = 24, dark = false}: MarkProps) {
  return <Artwork name="check_green" width={size} height={size} dark={dark} />;
}

export function CheckPurpleMark({size = 24, dark = false}: MarkProps) {
  return <Artwork name="check_purple" width={size} height={size} dark={dark} />;
}

export function ConnectLinkMark({size = 24}: MarkProps) {
  const {colors} = useTheme();
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-.71"
        stroke={colors.accent}
        strokeWidth={2.2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </Svg>
  );
}

/** The pack's weekday icons are complete circular badges, not inner-only marks. */
export function WeekdayMark({
  state,
  size = 32,
  dark = false,
}: MarkProps & {state: WeekdayState}) {
  const name = state === "checked"
    ? "streak_checked"
    : state === "partial"
      ? "streak_partial"
      : "streak_empty";
  return <Artwork name={name} width={size} height={size} dark={dark} />;
}

export function HomeCat({
  celebrating = false,
  variant,
  size = 100,
  dark = false,
}: {
  celebrating?: boolean;
  variant?: CatVariant;
  size?: number;
  dark?: boolean;
}) {
  const name = variant === "allDone"
    ? "cat_all_done"
    : variant === "sleeping"
      ? "cat_3day_sleeping"
      : variant === "celebrating" || celebrating
        ? "cat_5day_celebrating"
        : variant === "happy"
          ? "cat_12day_happy"
          : "cat_12day_default";
  return <Artwork name={name} width={size * 1.125} height={size} dark={dark} />;
}

export function NoStreakArtwork({width = 110, height = 105, dark = false}: ArtworkSize) {
  return <Artwork name="sprout_no_streak" width={width} height={height} dark={dark} />;
}

