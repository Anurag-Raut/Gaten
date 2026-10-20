/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import {Image} from "react-native";
import Svg, {Circle, Path, SvgXml} from "react-native-svg";
import {homeSvgXml} from "./HomeSvgAssets";
import {homeStreakDayXml, homeStreakFlameXml} from "./HomeStreakAssets";

type ArtworkSize = {width?: number; height?: number; dark?: boolean};
type MarkProps = {size?: number; dark?: boolean};
export type CatVariant = "default" | "sleeping" | "celebrating" | "happy" | "allDone";
export type WeekdayState = "empty" | "partial" | "checked";
export type StreakDayState = "complete" | "missed" | "today" | "empty" | "unknown";

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
 * The Anki and WaniKani marks a service card's icon tile draws: the supplied square art at the
 * tile's own size. It is opaque, so the tile's background stays behind its rounded corners.
 */
export function AnkiServiceMark({size = 42}: {size?: number}) {
  return (
    <Image source={require("./assets/anki-stack.png")} style={{width: size, height: size}} resizeMode="cover" />
  );
}

export function ToriiServiceMark({size = 42}: {size?: number}) {
  return (
    <Image source={require("./assets/wanikani-torii.png")} style={{width: size, height: size}} resizeMode="cover" />
  );
}

export function StreakPowerIcon({size = 24, dark = false}: MarkProps) {
  return <Artwork name="streak_new" width={size} height={size} dark={dark} />;
}

export function ActiveFlameMark({size = 24, dark = false}: MarkProps) {
  return <Artwork name="flame" width={size} height={size} dark={dark} />;
}

/** Renders the exact flame paths and four layer colors supplied in the streak reference. */
export function StreakFlameMark({active, size = 40}: MarkProps & {active: boolean}) {
  return <SvgXml xml={homeStreakFlameXml(active)} width={size} height={size} />;
}

export function StreakDayMark({state, size = 40}: MarkProps & {state: StreakDayState}) {
  if (state === "unknown") {
    return (
      <Svg width={size} height={size} viewBox="0 0 40 40">
        <Circle cx="20" cy="20" r="18.5" fill="#FFFFFF" stroke="#E2DEF4" strokeWidth="1.5" />
        <Path d="M16.5 20h7" stroke="#B3AECB" strokeWidth="2.5" strokeLinecap="round" />
      </Svg>
    );
  }
  return <SvgXml xml={homeStreakDayXml(state)} width={size} height={size} />;
}

export function CheckGreenMark({size = 24, dark = false}: MarkProps) {
  return <Artwork name="check_green" width={size} height={size} dark={dark} />;
}

export function CheckPurpleMark({size = 24, dark = false}: MarkProps) {
  return <Artwork name="check_purple" width={size} height={size} dark={dark} />;
}

export function ConnectLinkMark({size = 24, dark = false}: MarkProps) {
  return (
    <Svg width={size} height={size} viewBox="0 0 24 24" fill="none">
      <Path
        d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-.71"
        stroke={dark ? "#A489FF" : "#805BF8"}
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

