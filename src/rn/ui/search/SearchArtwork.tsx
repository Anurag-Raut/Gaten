/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import React from "react";
import Svg, {Circle, Ellipse, G, Path, Rect} from "react-native-svg";
import {useTheme} from "../theme";

/** A quiet dictionary-search illustration: neutral kana cards and a thin lens with a hint of accent. */
export function SearchArtwork({width = 300}: {width?: number}) {
  const {colors} = useTheme();
  return (
    <Svg width={width} height={Math.round(width * 220 / 340)} viewBox="0 0 340 220" fill="none">
      <Ellipse cx={170} cy={180} rx={71} ry={4} fill={colors.onSurfaceVariant} fillOpacity={0.04} />
      <Path d="M87 123c-6-35 13-68 46-79M235 47c15 7 25 22 28 39" stroke={colors.iconSecondary} strokeOpacity={0.16} strokeWidth={1.5} strokeLinecap="round" strokeDasharray="3 8" />

      <G transform="rotate(-8 142 103)">
        <Rect x={102} y={51} width={80} height={104} rx={13} fill={colors.surface} stroke={colors.iconSecondary} strokeOpacity={0.4} strokeWidth={1.6} />
        <Rect x={112} y={61} width={60} height={58} rx={8} fill={colors.surfaceVariant} fillOpacity={0.5} />
        <G transform="translate(122 102) scale(0.04 -0.04)" fill={colors.onSurfaceVariant} fillOpacity={0.6}>
          <Path d="M613 441C571 329 510 248 444 185C433 243 426 304 426 368L427 409C473 426 531 441 596 441ZM727 551 648 571C647 554 642 528 637 513L634 503L597 504C546 504 485 495 429 479C432 521 435 563 439 602C562 608 695 622 800 640L799 714C697 690 575 677 448 671L460 747C463 761 467 779 472 792L388 794C389 782 387 764 386 746L378 669L310 668C267 668 180 675 145 681L147 606C188 603 266 599 309 599L370 600C366 553 361 503 359 453C221 389 109 258 109 129C109 44 161 3 227 3C282 3 342 25 397 58L413 2L485 24C477 49 469 76 461 105C546 177 627 288 684 430C777 403 828 335 828 259C828 129 716 36 535 17L578 -50C810 -13 905 111 905 255C905 365 831 457 706 490L707 494C712 510 721 537 727 551ZM356 378V360C356 285 366 204 380 133C329 97 281 80 242 80C204 80 185 101 185 142C185 224 259 323 356 378Z" />
        </G>
        <Path d="M119 133h29M119 141h18" stroke={colors.iconSecondary} strokeOpacity={0.23} strokeWidth={1.7} strokeLinecap="round" />
      </G>

      <G transform="rotate(9 216 74)">
        <Rect x={193} y={49} width={46} height={50} rx={10} fill={colors.surface} stroke={colors.iconSecondary} strokeOpacity={0.3} strokeWidth={1.5} />
        <G transform="translate(204 84) scale(0.025 -0.025)" fill={colors.onSurfaceVariant} fillOpacity={0.45}>
          <Path d="M855 579 799 607C782 604 762 602 735 602H497C499 635 501 669 502 705C503 729 505 764 508 787H414C418 763 421 726 421 704C421 668 419 634 417 602H241C203 602 162 604 127 608V523C162 527 203 527 242 527H410C383 321 311 196 212 106C182 77 141 49 109 32L182 -27C349 88 453 240 489 527H769C769 420 756 174 718 98C707 73 689 65 660 65C618 65 565 69 511 76L521 -7C573 -10 631 -14 682 -14C737 -14 769 5 789 47C834 143 846 434 850 530C850 543 852 562 855 579Z" />
        </G>
      </G>

      <Circle cx={190} cy={125} r={33} fill={colors.surface} stroke={colors.iconSecondary} strokeOpacity={0.65} strokeWidth={3} />
      <Circle cx={190} cy={125} r={27} fill={colors.accent} fillOpacity={0.03} />
      <Path d="M212 149l26 26" stroke={colors.iconSecondary} strokeOpacity={0.65} strokeWidth={6} strokeLinecap="round" />
      <Path d="M171 122c1-9 7-16 16-18" stroke={colors.iconSecondary} strokeOpacity={0.2} strokeWidth={2} strokeLinecap="round" />

      <Circle cx={89} cy={91} r={5} fill={colors.accent} fillOpacity={0.08} />
      <Circle cx={253} cy={132} r={3} fill={colors.iconSecondary} fillOpacity={0.18} />
      <Path d="M251 105h10M256 100v10" stroke={colors.accent} strokeOpacity={0.08} strokeWidth={1.8} strokeLinecap="round" />
    </Svg>
  );
}
