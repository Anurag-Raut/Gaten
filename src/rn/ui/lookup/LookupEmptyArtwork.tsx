/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The lookup's two empty marks, in the sheet's own neutral tone. They are not interchangeable:
 * [NoTextArt] is the empty reading frame ("nothing was read here") and [NoMatchesArt] is the
 * dictionary whose lens found nothing. The geometry is the same 140 x 140 source the native overlay
 * drew (`artifacts/lookup-redesign/mockups/artwork/`), so the RN sheet and the old Compose sheet
 * show the same picture — only the renderer changed.
 */
import React from "react";
import Svg, {Path} from "react-native-svg";
import {useTheme} from "../theme";

export function NoTextArt({size = 140}: {size?: number}) {
  const {colors} = useTheme();
  const stroke = colors.onSurfaceVariant;
  return (
    <Svg width={size} height={size} viewBox="0 0 140 140" fill="none">
      <Path d="M32 54V36Q32 32 36 32H54" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M86 32H104Q108 32 108 36V54" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M32 86V104Q32 108 36 108H54" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M86 108H104Q108 108 108 104V86" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M52 70H62" stroke={stroke} strokeWidth={1.5} strokeLinecap="round" />
      <Path d="M78 70H88" stroke={stroke} strokeWidth={1.5} strokeLinecap="round" />
    </Svg>
  );
}

export function NoMatchesArt({size = 140}: {size?: number}) {
  const {colors} = useTheme();
  const stroke = colors.onSurfaceVariant;
  return (
    <Svg width={size} height={size} viewBox="0 0 140 140" fill="none">
      <Path d="M70 42Q54 34 30 38V96Q54 92 70 100" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M70 42Q86 34 110 38V62M70 100V42" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M42 54Q50 53 58 56M42 66Q50 65 58 68M42 78Q50 77 58 80" stroke={stroke} strokeWidth={1.5} strokeLinecap="round" />
      <Path d="M110 84A18 18 0 1 1 74 84A18 18 0 1 1 110 84Z" stroke={stroke} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
      <Path d="M105 97L118 110" stroke={stroke} strokeWidth={2} strokeLinecap="round" />
    </Svg>
  );
}
