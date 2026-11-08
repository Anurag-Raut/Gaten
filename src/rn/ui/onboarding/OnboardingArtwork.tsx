/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run artwork (v2): one persistent scene of three poses on a soft disc, driven by the single
 * scene clock the flow owns. In every pose the word is 合点 (Gaten, the app's own name), reading
 * がてん.
 *
 *  - Lookup (0): a screen card with 合点 highlighted, a result card below it showing 合点 · がてん
 *    with two meaning lines, and the floating trigger resting over the page beside the word: the
 *    Gaten G button itself, not a search glyph.
 *  - Anki (1): three stacked flashcards, 合点 large with がてん beneath, and a row of four answer
 *    pills of which the third ("Good") is filled with the accent.
 *  - WaniKani (2): a torii in neutral ink, a 合点 card and a "Level 1" card with a short accent bar.
 *
 * The three compositions cross-fade and drift on the scene clock, and the disc behind them (faintly
 * tinted with the accent) stays put, so a step change and its reverse travel the same path. Every
 * colour is a theme role (`surface`, `outline`, `onSurface`) except the accent, which is the app's
 * default Indigo fixed per scheme (see `INDIGO_LIGHT` / `INDIGO_DARK`): light and dark repaint the
 * same geometry, the reader's accent choice does not apply here. The one drawn element that is
 * neither: the floating trigger's own mark, the black Gaten G on its flat white disc, painted as the
 * button actually ships and so the same in both schemes.
 *
 * The lettering is the bundled IBM Plex Sans JP's own outlines (Medium for the kanji and kana,
 * SemiBold for "Level 1"), extracted from `app/src/main/res/font/ibm_plex_sans_jp_*.ttf` (unitsPerEm
 * 1000, y up). The bundled face is the full font, so 合, 点, が, て and ん are all present; no subset
 * is involved because the glyphs are drawn as paths, not rendered through a font at runtime.
 *
 * The whole artwork is decorative: hidden from screen readers and never a touch target.
 */
import React from "react";
import Animated, {
  Extrapolation,
  interpolate,
  type SharedValue,
  useAnimatedProps,
  useAnimatedStyle,
} from "react-native-reanimated";
import Svg, {Circle, Ellipse, G, Path, type PathProps, Rect} from "react-native-svg";
import {useTheme} from "../theme";

/** The shared stage. Every scene is drawn in this box and scales with the flow's width. */
export const SCENE_WIDTH = 340;
/** The core stage is the disc: 176 dp across, so the artwork fits under a two-row step list. */
export const SCENE_HEIGHT = 176;

/** Breathing room above and below the disc; at rest the disc is centred in this taller zone. */
const HERO_PAD = 8;
const HERO_HEIGHT = SCENE_HEIGHT + HERO_PAD * 2;

/** The core's recede as the WaniKani form takes over; its complement is the anchor height. */
const CORE_RECEDE = 0.55;
const HERO_ANCHOR_HEIGHT = SCENE_HEIGHT * (1 - CORE_RECEDE);

const DISC_CX = SCENE_WIDTH / 2;
const DISC_CY = SCENE_HEIGHT / 2;
const DISC_R = SCENE_HEIGHT / 2;

/** The scene clock's three stops, one per setup page. */
const STOPS: number[] = [0, 1, 2];

/** Glyph outlines: advance width and path in font units (1000 per em, y up). */
const GLYPHS: Record<string, {w: number; d: string}> = {
  "合": {w: 1000, d: "M551 840Q594 751 692.5 675.0Q791 599 978 511L929 423Q805 483 726 532V459H272V535Q190 481 72 423L22 512Q203 597 296.0 672.5Q389 748 433 840ZM706 545Q614 606 563.0 666.5Q512 727 496 794H488Q471 726 422.5 665.5Q374 605 287 545ZM240 -72H147V351H853V-72H760V-24H240ZM760 267H240V62H760Z"},
  "点": {w: 1000, d: "M536 541H854V220H146V541H441V837H536V727H912V644H536ZM239 304H759V458H239ZM828 182Q905 79 953 -35L866 -73Q822 37 749 151ZM46 -27Q132 67 182 181L262 150Q218 32 126 -79ZM636 164Q679 56 704 -57L610 -77Q589 41 551 146ZM432 155Q454 55 461 -70L367 -80Q361 36 345 147Z"},
  "が": {w: 1000, d: "M915 641Q861 744 816 806L880 837Q936 756 977 673ZM419 595Q429 596 449 596Q625 596 625 402Q625 216 607.0 124.5Q589 33 551.5 3.0Q514 -27 444 -27Q394 -27 305 -14L311 79Q380 68 426 68Q469 68 489.0 88.5Q509 109 518.5 180.5Q528 252 528 410Q528 458 508.0 481.5Q488 505 439 505Q424 505 416 504L339 497Q301 343 258.0 216.0Q215 89 158 -38L64 0Q119 118 161.0 234.5Q203 351 239 488L70 472L61 564L262 581Q290 703 309 804L402 789Q375 652 361 590ZM814 595Q898 429 938 239L842 212Q799 419 705 589L781 623Q740 701 702 753L766 784Q835 681 864 619Z"},
  "て": {w: 1000, d: "M800 -29Q573 -17 462.0 61.5Q351 140 351 284Q351 379 395.5 453.5Q440 528 507 571Q559 603 632 618V624Q366 608 91 586L86 688Q498 714 905 733L908 641Q847 639 803.0 632.5Q759 626 708 608Q644 588 586.0 541.5Q528 495 492.0 432.5Q456 370 456 304Q456 231 491.5 183.0Q527 135 606.0 107.0Q685 79 816 71Z"},
  "ん": {w: 1000, d: "M65 -12Q102 100 175 307Q214 418 301 642L371 822L471 789L377 556Q312 391 294 349L302 348Q370 462 463 462Q539 462 575.0 411.5Q611 361 611 250Q611 178 616.0 141.0Q621 104 637.5 86.0Q654 68 688 68Q740 68 779.0 135.0Q818 202 848 361L943 331Q907 143 844.0 54.0Q781 -35 680 -35Q590 -35 551.5 21.0Q513 77 513 203Q513 272 505.0 308.0Q497 344 478.5 358.5Q460 373 424 373Q377 373 335.0 337.5Q293 302 263 234Q244 191 225.5 140.5Q207 90 195.5 55.0Q184 20 178 4Q174 -10 170.0 -21.0Q166 -32 163 -42Z"},
  "L": {w: 545, d: "M510 117V0H88V733H220V117Z"},
  "e": {w: 584, d: "M42 274Q42 360 72.0 425.0Q102 490 159.5 525.0Q217 560 295 560Q376 560 431.5 523.5Q487 487 514.5 425.0Q542 363 542 285V242H176V227Q176 164 211.0 127.0Q246 90 311 90Q358 90 392.5 110.5Q427 131 453 167L523 91Q490 43 431.5 15.0Q373 -13 299 -13Q219 -13 161.5 22.0Q104 57 73.0 122.0Q42 187 42 274ZM176 321H408V331Q408 392 378.5 427.5Q349 463 296 463Q242 463 209.0 426.0Q176 389 176 329Z"},
  "v": {w: 546, d: "M349 0H196L13 547H140L219 304L271 113H279L332 304L411 547H533Z"},
  "l": {w: 306, d: "M209 0Q144 0 111.5 33.0Q79 66 79 127V777H207V102H280V0Z"},
  "1": {w: 630, d: "M596 107V0H96V107H297V638H288L125 451L44 520L226 733H428V107Z"}
};

/** Width of [text] at [size], in stage units. */
function textWidth(text: string, size: number): number {
  let w = 0;
  for (const ch of text) w += (GLYPHS[ch]?.w ?? 260) * (size / 1000);
  return w;
}

/**
 * [text] as outlines: left edge (or centre, with `anchor="middle"`) at [x], alphabetic baseline at
 * [y], em height [size]. Each glyph is one path under a `translate scale(s -s)` that flips the font's
 * y-up outlines onto the canvas.
 */
function Glyphs({
  text,
  x,
  y,
  size,
  fill,
  opacity = 1,
  anchor = "start",
}: {
  text: string;
  x: number;
  y: number;
  size: number;
  fill: string;
  opacity?: number;
  anchor?: "start" | "middle";
}) {
  const s = size / 1000;
  let pen = anchor === "middle" ? x - textWidth(text, size) / 2 : x;
  const parts: React.ReactNode[] = [];
  [...text].forEach((ch, i) => {
    const g = GLYPHS[ch];
    if (!g) {
      pen += 260 * s; // a space
    } else {
      parts.push(<Path key={i} d={g.d} transform={`translate(${pen.toFixed(2)} ${y}) scale(${s} ${-s})`} />);
      pen += g.w * s;
    }
  });
  return (
    <G fill={fill} fillOpacity={opacity}>
      {parts}
    </G>
  );
}

/** One decorative layer: a full-stage box whose whole content moves and fades as a unit. */
const LAYER_STYLE = {position: "absolute" as const, left: 0, top: 0, width: SCENE_WIDTH, height: SCENE_HEIGHT};

function Layer({style, children}: {style?: object; children: React.ReactNode}) {
  return (
    <Animated.View
      style={[LAYER_STYLE, style]}
      pointerEvents="none"
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Svg
        width={SCENE_WIDTH}
        height={SCENE_HEIGHT}
        viewBox={`0 0 ${SCENE_WIDTH} ${SCENE_HEIGHT}`}
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {children}
      </Svg>
    </Animated.View>
  );
}

/**
 * The artwork follows light/dark but not the reader's accent: onboarding is shown with the app's
 * default Indigo, so its tones are fixed. Hex values are IndigoLight / IndigoDark in
 * app/src/main/java/com/gaten/app/ui/theme/Color.kt (primary, and the onPrimary ink that reads on it).
 *
 * `toriiInk` is the one neutral the WaniKani scene draws its torii in. It is fixed per scheme rather
 * than read from `colors.outline`, because that outline is near the disc's own tone on the device
 * theme (its contrast against the composited disc is ~1:1, so the gate vanished). Each value below is
 * a muted neutral picked to clear 3:1 non-text contrast against that disc — the light disc composites
 * to ~#EAE9F6, the dark to ~#22242E — while staying quieter than the cards' near-full-strength ink.
 */
const INDIGO_LIGHT = {accent: "#4338CA", onAccent: "#FFFFFF", toriiInk: "#5B5B66"};
const INDIGO_DARK = {accent: "#A5B4FC", onAccent: "#111111", toriiInk: "#9A9AA4"};

function useIndigo() {
  const {dark} = useTheme();
  return dark ? INDIGO_DARK : INDIGO_LIGHT;
}

/** The soft disc, faintly tinted with the accent over the surface variant. */
function Disc() {
  const {colors} = useTheme();
  const {accent} = useIndigo();
  return (
    <>
      {/* A flat ground shadow resting just inside the disc's lower edge. */}
      <Ellipse cx={DISC_CX} cy={DISC_CY + DISC_R - 10} rx={38} ry={3} fill={colors.onSurface} fillOpacity={0.07} />
      <Circle cx={DISC_CX} cy={DISC_CY} r={DISC_R} fill={colors.surfaceVariant} fillOpacity={0.6} />
      <Circle cx={DISC_CX} cy={DISC_CY} r={DISC_R} fill={accent} fillOpacity={0.08} />
    </>
  );
}

/** A card: surface fill, hairline outline. */
function Card({
  x,
  y,
  w,
  h,
  r = 7,
  rotate = 0,
  accentEdge = false,
  children,
}: {
  x: number;
  y: number;
  w: number;
  h: number;
  r?: number;
  rotate?: number;
  accentEdge?: boolean;
  children?: React.ReactNode;
}) {
  const {colors} = useTheme();
  const {accent} = useIndigo();
  return (
    <G transform={rotate ? `rotate(${rotate} ${x + w / 2} ${y + h / 2})` : undefined}>
      <Rect
        x={x}
        y={y}
        width={w}
        height={h}
        rx={r}
        fill={colors.surface}
        stroke={accentEdge ? accent : colors.outline}
        strokeOpacity={accentEdge ? 0.9 : 0.6}
        strokeWidth={1.2}
      />
      {children}
    </G>
  );
}

/** Placeholder text lines: quiet rules in the outline tone. */
function Lines({x, y, widths, gap = 6}: {x: number; y: number; widths: number[]; gap?: number}) {
  const {colors} = useTheme();
  const d = widths.map((w, i) => `M${x} ${y + i * gap}H${x + w}`).join("");
  return <Path d={d} stroke={colors.outline} strokeOpacity={0.55} strokeWidth={1.6} />;
}

/**
 * The floating trigger's mark: the approved Gaten G, byte-for-byte the path in
 * `app/src/main/res/drawable/ic_trigger_g.xml` (viewport 760x666, its group's own `translate(-6 -6)`
 * dropping the launcher artwork's 6-unit margin so the viewport is the ink). One flat black shape
 * under `evenodd`; never tinted.
 */
const TRIGGER_G =
  "M 720 8 L 719 10 L 716 16 L 716 18 L 707 37 L 692 66 L 692 68 L 687 78 L 689 78 L 690 79 L 698 79 L 699 78 L 708 79 L 710 79 L 728 79 L 731 75 L 734 68 L 744 50 L 744 48 L 755 27 L 757 25 L 766 7 Z M 655 8 L 654 10 L 652 14 L 648 22 L 626 66 L 626 68 L 615 90 L 658 89 L 659 87 L 664 76 L 666 74 L 676 55 L 677 53 L 686 36 L 687 34 L 687 32 L 688 30 L 698 13 L 701 7 Z M 504 6 L 335 7 L 307 8 L 295 9 L 285 10 L 277 11 L 271 12 L 265 13 L 250 16 L 234 20 L 227 22 L 209 28 L 201 31 L 194 34 L 179 41 L 177 42 L 166 48 L 161 51 L 153 56 L 144 62 L 137 67 L 128 74 L 122 79 L 114 86 L 93 107 L 86 115 L 81 121 L 74 130 L 69 137 L 65 143 L 59 152 L 50 167 L 46 175 L 38 191 L 34 200 L 32 205 L 30 210 L 27 218 L 23 230 L 17 251 L 16 255 L 12 275 L 11 281 L 10 287 L 9 294 L 8 303 L 7 314 L 6 331 L 6 358 L 7 375 L 8 386 L 9 395 L 11 408 L 14 423 L 16 432 L 19 443 L 21 450 L 28 471 L 34 486 L 38 495 L 48 515 L 53 524 L 56 529 L 61 537 L 69 549 L 78 561 L 82 566 L 87 572 L 97 583 L 104 590 L 116 601 L 122 606 L 127 610 L 131 613 L 135 616 L 142 621 L 145 623 L 148 625 L 156 630 L 163 634 L 187 646 L 194 649 L 202 652 L 223 659 L 234 662 L 238 663 L 258 667 L 265 668 L 272 669 L 281 670 L 292 671 L 311 672 L 676 672 L 676 305 L 674 306 L 666 305 L 354 307 L 353 310 L 351 312 L 347 319 L 339 333 L 323 361 L 314 377 L 302 400 L 300 402 L 298 406 L 551 406 L 553 408 L 552 542 L 474 543 L 320 543 L 305 542 L 297 541 L 290 540 L 285 539 L 273 536 L 267 534 L 258 531 L 253 529 L 246 526 L 233 519 L 228 516 L 225 514 L 222 512 L 215 507 L 211 504 L 204 498 L 187 481 L 182 475 L 179 471 L 176 467 L 168 455 L 165 450 L 161 443 L 155 431 L 151 422 L 149 417 L 142 396 L 139 384 L 138 379 L 137 374 L 136 368 L 135 362 L 134 352 L 134 308 L 135 299 L 136 293 L 137 287 L 138 282 L 141 269 L 142 265 L 145 256 L 149 245 L 152 238 L 155 232 L 160 222 L 164 215 L 167 210 L 173 201 L 176 197 L 180 192 L 186 185 L 200 171 L 207 165 L 212 161 L 219 156 L 230 149 L 236 146 L 248 140 L 253 138 L 271 132 L 275 131 L 279 130 L 284 129 L 290 128 L 297 127 L 307 126 L 579 125 L 580 120 L 583 112 L 586 104 L 595 83 L 607 51 L 612 37 L 616 23 L 618 20 L 621 9 L 622 7 L 556 6 L 547 6 L 510 6 Z";

/** The mark's own ink box and the app's trigger disc, all in stage units (the scene draws at 1:1 dp). */
const TRIGGER_G_INK = {w: 760, h: 666};
const TRIGGER_DISC_R = 24; // 48 dp across
/** The G on that disc: 27 dp wide, its height the ink's own ratio (27 * 666/760 = 23.66 dp). */
const TRIGGER_G_W = 27;

/**
 * The floating lookup button as it ships: the black Gaten G, centred on its ink box, on a flat white
 * 48 dp disc with a hairline black keyline drawn just inside the edge. The mark is the app's own —
 * white disc, black G, in every theme — so it is fixed here rather than read from the theme.
 */
function TriggerButton({cx, cy}: {cx: number; cy: number}) {
  const scale = TRIGGER_G_W / TRIGGER_G_INK.w;
  const x = cx - (TRIGGER_G_INK.w * scale) / 2;
  const y = cy - (TRIGGER_G_INK.h * scale) / 2;
  return (
    <G>
      <Circle
        cx={cx}
        cy={cy}
        r={TRIGGER_DISC_R - 0.5}
        fill="#FFFFFF"
        stroke="#000000"
        strokeOpacity={0.14}
        strokeWidth={1}
      />
      <G transform={`translate(${x.toFixed(2)} ${y.toFixed(2)}) scale(${scale.toFixed(6)})`}>
        <Path d={TRIGGER_G} transform="translate(-6 -6)" fill="#000000" fillRule="evenodd" />
      </G>
    </G>
  );
}

function LookupScene() {
  const {colors} = useTheme();
  const {accent, onAccent} = useIndigo();
  return (
    <>
      {/* The screen being read, with 合点 highlighted. */}
      <Card x={102} y={36} w={104} h={72} r={8} rotate={-4}>
        <Lines x={112} y={46} widths={[40]} />
        <Rect
          x={112}
          y={54}
          width={50}
          height={20}
          rx={4}
          fill={accent}
          fillOpacity={0.12}
          stroke={accent}
          strokeWidth={1.4}
        />
        <Glyphs text="合点" x={137} y={69} size={14} fill={colors.onSurface} anchor="middle" />
        {/* The reading cursor under the highlight. */}
        <Path d="M134 77.5H140M137 77.5V82.5M134 82.5H140" stroke={accent} strokeWidth={1.1} />
        <Lines x={112} y={88} widths={[84, 70]} />
        <Lines x={112} y={100} widths={[56]} />
      </Card>
      {/* The result card below it: 合点 · がてん with two meaning lines. */}
      <Card x={134} y={92} w={96} h={50} r={8} accentEdge>
        <Glyphs text="合点" x={143} y={110} size={12} fill={colors.onSurface} />
        <Glyphs text="がてん" x={170} y={110} size={8} fill={accent} />
        <Lines x={143} y={121} widths={[70, 52]} gap={7} />
      </Card>
      {/* The floating trigger over the page, beside the word it read. */}
      <TriggerButton cx={220} cy={64} />
    </>
  );
}

function AnkiScene() {
  const {colors} = useTheme();
  const {accent, onAccent} = useIndigo();
  const pills = [0, 1, 2, 3];
  return (
    <>
      {/* Three stacked flashcards. */}
      <Card x={122} y={32} w={100} h={72} r={8} rotate={-6} />
      <Card x={122} y={32} w={100} h={72} r={8} rotate={4} />
      <Card x={122} y={34} w={100} h={74} r={8}>
        <Glyphs text="合点" x={172} y={78} size={28} fill={colors.onSurface} anchor="middle" />
        <Glyphs text="がてん" x={172} y={96} size={9} fill={accent} anchor="middle" />
      </Card>
      {/* Four answer pills; the third, "Good", is filled with the accent. */}
      {pills.map((i) => (
        <G key={i}>
          <Rect
            x={121 + i * 26}
            y={124}
            width={22}
            height={11}
            rx={5.5}
            fill={i === 2 ? accent : colors.surface}
            stroke={i === 2 ? accent : colors.outline}
            strokeOpacity={i === 2 ? 1 : 0.6}
            strokeWidth={1.2}
          />
          <Path
            d={`M${128 + i * 26} 129.5H${136 + i * 26}`}
            stroke={i === 2 ? onAccent : colors.outline}
            strokeOpacity={i === 2 ? 1 : 0.7}
            strokeWidth={1.5}
          />
        </G>
      ))}
    </>
  );
}

/** The WaniKani level card's progress fill, brightened by a real connection. */
const AnimatedBar = Animated.createAnimatedComponent(Rect);

function WaniKaniScene({success}: {success: SharedValue<number>}) {
  const {colors} = useTheme();
  const {accent, toriiInk} = useIndigo();
  const barProps = useAnimatedProps<PathProps>(() => ({fillOpacity: 0.85 + 0.15 * success.value}) as PathProps);
  const ink = toriiInk;
  return (
    <>
      {/* The torii, in the fixed neutral ink that reads against the disc. */}
      <Path d="M80 62 Q112 69 144 62" stroke={ink} strokeWidth={2.6} />
      <Path d="M88 75 H136" stroke={ink} strokeWidth={2} />
      <Path d="M96 76 V130 M128 76 V130" stroke={ink} strokeWidth={2.4} />
      {/* A 合点 card, and the Level 1 card with its short accent bar. */}
      <Card x={158} y={38} w={54} h={34} r={7} rotate={6}>
        <Glyphs text="合点" x={185} y={61} size={17} fill={colors.onSurface} anchor="middle" />
      </Card>
      <Card x={152} y={84} w={66} h={40} r={7} rotate={-5} accentEdge>
        <Glyphs text="Level 1" x={161} y={101} size={9} fill={colors.onSurface} />
        <Rect x={161} y={108} width={48} height={5} rx={2.5} fill={colors.outline} fillOpacity={0.35} />
        <AnimatedBar
          x={161}
          y={108}
          width={20}
          height={5}
          rx={2.5}
          fill={accent}
          animatedProps={barProps as never}
        />
      </Card>
    </>
  );
}

/**
 * The persistent scene. [scene] is the flow's single clock, in page units (0 lookup, 1 Anki, 2
 * WaniKani); [entrance] is the first-run settle, 0 to 1; [formProgress] recedes the scene — and the
 * hero zone around it — to the small anchor the WaniKani token form leaves behind, 0 to 1; [success]
 * brightens the Level 1 progress bar when a WaniKani account is really connected. The component owns
 * its full layout height: the zone at rest, the core's own anchor once the form is up.
 *
 * Every layer's motion is a pure function of those values, so a step change and its reverse travel
 * the same path.
 */
export function OnboardingScene({
  width,
  scene,
  entrance,
  formProgress,
  success,
}: {
  width: number;
  scene: SharedValue<number>;
  entrance: SharedValue<number>;
  formProgress: SharedValue<number>;
  success: SharedValue<number>;
}) {
  const scale = width / SCENE_WIDTH;

  const boxStyle = useAnimatedStyle(() => ({
    height:
      interpolate(formProgress.value, [0, 1], [HERO_HEIGHT, HERO_ANCHOR_HEIGHT], Extrapolation.CLAMP) * scale,
    opacity: entrance.value,
    transform: [{translateY: (1 - entrance.value) * 10}],
  }));

  const innerStyle = useAnimatedStyle(() => ({
    transform: [{scale: scale * (1 - CORE_RECEDE * formProgress.value)}],
  }));

  // Each pose fades in and out around its own stop and drifts toward the pose it is leaving for.
  const lookupStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [0, 0.6], [1, 0], Extrapolation.CLAMP),
    transform: [{translateX: interpolate(scene.value, STOPS, [0, -14, -14], Extrapolation.CLAMP)}],
  }));
  const ankiStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [0.4, 1, 1.6], [0, 1, 0], Extrapolation.CLAMP),
    transform: [{translateX: interpolate(scene.value, [0, 1, 2], [14, 0, -14], Extrapolation.CLAMP)}],
  }));
  const wanikaniStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [1.4, 2], [0, 1], Extrapolation.CLAMP),
    transform: [{translateX: interpolate(scene.value, [1, 2], [14, 0], Extrapolation.CLAMP)}],
  }));

  return (
    <Animated.View style={[{width, alignItems: "center", justifyContent: "center"}, boxStyle]}>
      <Animated.View style={[{width: SCENE_WIDTH, height: SCENE_HEIGHT}, innerStyle]}>
        <Layer>
          <Disc />
        </Layer>
        <Layer style={lookupStyle}>
          <LookupScene />
        </Layer>
        <Layer style={ankiStyle}>
          <AnkiScene />
        </Layer>
        <Layer style={wanikaniStyle}>
          <WaniKaniScene success={success} />
        </Layer>
      </Animated.View>
    </Animated.View>
  );
}
