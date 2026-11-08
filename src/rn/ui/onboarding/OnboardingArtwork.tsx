/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * The first-run artwork as ONE persistent scene rather than three illustrations.
 *
 * The scene is a fixed 340 x 220 stage drawn at the flow's width, centred in a taller 340 x 268 hero
 * zone whose added margin a faint backdrop fills (see [HeroBackdrop]). Inside the core, one card
 * bearing the compound 合点 is the subject the whole flow is about: a reading pane the lens examines
 * on the lookup page, the front card of a deck on the Anki page, the subject card beside the torii on
 * the WaniKani page. It is the SAME card throughout — its pose (position, size, rotation) is
 * interpolated from the single scene clock the flow owns, so the card glides and resizes between
 * pages instead of one card leaving and another arriving. The two supporting Anki cards fan and
 * gather around it on the same clock, and the lens, the torii and the second subject card enter and
 * leave as coherent groups.
 *
 * Everything a page adds — the lens, the deck's supporting cards, the rating controls, the torii and
 * the second subject card — is one absolutely positioned layer over the stage, animated only by
 * numeric transforms and opacity on the UI thread. The card itself is a single layer, so its
 * inscription travels with it: the 合 and 点 outlines never move independently of their host.
 *
 * The kanji are the bundled face's own outlines, not approximations — see [GLYPH_AU] and
 * [GLYPH_TEN]. Every colour is a theme role (`surface` for the card, `surfaceVariant` for its
 * reading inset, `iconSecondary` for the drawn strokes, `onSurfaceVariant` for the kanji ink itself,
 * `accent` for the single meaningful mark a scene carries), so light and dark repaint the same
 * geometry and nothing is drawn in a literal white.
 */
import React from "react";
import Animated, {
  Extrapolation,
  interpolate,
  type SharedValue,
  useAnimatedProps,
  useAnimatedStyle,
} from "react-native-reanimated";
import Svg, {
  Circle,
  ClipPath,
  Defs,
  Ellipse,
  G,
  Path,
  type PathProps,
  Rect,
  type RectProps,
} from "react-native-svg";
import {useTheme} from "../theme";

/** The shared stage. Every scene is drawn in this box and scales with the flow's width. */
export const SCENE_WIDTH = 340;
export const SCENE_HEIGHT = 220;

/**
 * The hero zone around the core stage. The first-run illustration reserves [HERO_PAD] of breathing
 * room above and below the unchanged 340 x 220 core, so the card keeps its proportions and the extra
 * height reads as a taller illustration zone rather than blank padding or a stretched card. At rest
 * the core is centred in this zone; as the WaniKani form takes over, the zone collapses on the same
 * clock to the compact [HERO_ANCHOR_HEIGHT] the core's own recede leaves behind.
 */
const HERO_PAD = 24;
const HERO_HEIGHT = SCENE_HEIGHT + HERO_PAD * 2;

/** The core's recede as the WaniKani form takes over; its 0.45 complement is the anchor height. */
const CORE_RECEDE = 0.55;
const HERO_ANCHOR_HEIGHT = SCENE_HEIGHT * (1 - CORE_RECEDE);

/** The scene clock's three stops, one per setup page. */
const STOPS: number[] = [0, 1, 2];

/** The 合点 card's own box, in stage units: the shape every pose scales. */
const CARD_WIDTH = 184;
const CARD_HEIGHT = 128;

/** The card's pose per page: centre, scale and rotation. */
const CARD_X: number[] = [153, 177, 217];
const CARD_Y: number[] = [103, 101, 86];
const CARD_SCALE: number[] = [1, 0.66, 0.46];
const CARD_ROTATE: number[] = [-5, 5, 8];

/** The Anki deck's supporting cards, drawn at the front card's Anki-page size. */
const ANKI_CARD_WIDTH = CARD_WIDTH * CARD_SCALE[1];
const ANKI_CARD_HEIGHT = CARD_HEIGHT * CARD_SCALE[1];

/** The card layer's own box, overriding the full-stage size the other layers use. */
const CARD_BOX_STYLE = {width: CARD_WIDTH, height: CARD_HEIGHT};

/** A supporting deck card's own box: its rotation must pivot on the card, not the stage centre. */
const DECK_BOX_STYLE = {width: ANKI_CARD_WIDTH, height: ANKI_CARD_HEIGHT};

/**
 * The backdrop's own box: the full hero zone, raised by [HERO_PAD] so its centre lines up with the
 * core's centre and its added margin reaches [HERO_PAD] beyond the core on either side. Overrides
 * [LAYER_STYLE]'s top and height while keeping its width and absolute positioning.
 */
const BACKDROP_BOX_STYLE = {top: -HERO_PAD, width: SCENE_WIDTH, height: HERO_HEIGHT};

/**
 * 合 and 点 as IBM Plex Sans JP Regular's own outlines, extracted from the bundled
 * `app/src/main/res/font/ibm_plex_sans_jp_regular.ttf` (unitsPerEm 1000, y up) rather than redrawn
 * from strokes. Each is placed by the transform on its `<Inscription>` below, which fits the
 * outline's tight ink bounds into the box the card reserves for it — the same fitting the previous
 * 日 and 山 paths used. 合's ink bounds are x 28..972, y -69..837; 点's are x 53..948, y -75..834.
 */
const GLYPH_AU =
  "M28 496Q212 581 310 662Q408 743 454 837H530Q575 745 678.5 663.5Q782 582 972 495L939 435Q737 529 629.5 619Q522 709 495 804H488Q460 708 358 618Q256 528 62 435ZM726 472H272V532H726ZM157 343H843V-69H778V-16H222V-69H157ZM222 283V45H778V283Z";
const GLYPH_TEN =
  "M522 530H843V227H157V530H456V834H522V712H908V654H522ZM222 286H777V471H222ZM819 176Q895 80 948 -36L886 -64Q843 33 763 153ZM53 -33Q142 59 194 174L252 151Q204 33 110 -71ZM626 160Q671 53 695 -56L628 -71Q608 34 565 146ZM418 152Q441 49 448 -67L383 -75Q377 31 356 145Z";
const GLYPH_MOUNTAIN = "M831 79V660H897V-64H831V16H170V-64H103V662H170V79H459V821H526V79Z";

/** Every scene stands on the same ground: one flat ellipse at (170,184), barely there in either mode. */
function Ground() {
  const {colors} = useTheme();
  return <Ellipse cx={170} cy={184} rx={82} ry={4} fill={colors.onSurfaceVariant} fillOpacity={0.04} />;
}

/**
 * One kanji in the glyph tone, placed by a `translate(…) scale(s -s)` that fits its ink bounds into
 * the box the caller reserves: the font draws y up, so the negative y scale flips it onto the
 * canvas. [opacity] lets the card's own inscription read a little stronger than the quiet 山 on the
 * secondary subject card.
 */
function Inscription({
  outline,
  transform,
  opacity = 0.6,
}: {
  outline: string;
  transform: string;
  opacity?: number;
}) {
  const {colors} = useTheme();
  return (
    <G transform={transform} fill={colors.onSurfaceVariant} fillOpacity={opacity}>
      <Path d={outline} />
    </G>
  );
}

/** The stage's own box: absolutely positioned, one per animated layer, never a touch target. */
const LAYER_STYLE = {position: "absolute" as const, left: 0, top: 0, width: SCENE_WIDTH, height: SCENE_HEIGHT};

/** One decorative layer: a full-stage box whose whole content moves and fades as a unit. */
function Layer({style, children}: {style?: object; children: React.ReactNode}) {
  return (
    <Animated.View
      style={[LAYER_STYLE, style]}
      pointerEvents="none"
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      {children}
    </Animated.View>
  );
}

/** A full-stage SVG with the shared canvas settings every layer is drawn with. */
function StageSvg({children}: {children: React.ReactNode}) {
  return (
    <Svg
      width={SCENE_WIDTH}
      height={SCENE_HEIGHT}
      viewBox={`0 0 ${SCENE_WIDTH} ${SCENE_HEIGHT}`}
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
      preserveAspectRatio="xMidYMid meet"
    >
      {children}
    </Svg>
  );
}

/**
 * The hero zone's own backdrop, drawn behind the core on the taller stage. Two faint concentric
 * washes — the surface variant once and the accent almost not at all — ground the composition in
 * the full 340 x 268 zone, and three dashed ink guides (the same 3-8 rhythm the lookup guide uses)
 * carry the eye from the card out into the added top and bottom margin. Every tone is an existing
 * theme role and the whole layer stays quiet, so the card keeps its dominance; it is decorative
 * only and never a touch target.
 */
function HeroBackdrop() {
  const {colors} = useTheme();
  const mid = HERO_HEIGHT / 2;
  return (
    <Svg
      width={SCENE_WIDTH}
      height={HERO_HEIGHT}
      viewBox={`0 0 ${SCENE_WIDTH} ${HERO_HEIGHT}`}
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
      preserveAspectRatio="xMidYMid meet"
    >
      <Ellipse cx={170} cy={mid} rx={150} ry={130} fill={colors.surfaceVariant} fillOpacity={0.12} />
      <Ellipse cx={170} cy={mid} rx={108} ry={96} fill={colors.accent} fillOpacity={0.04} />
      <Path d="M170 14 V34 M170 234 V254" stroke={colors.iconSecondary} strokeOpacity={0.07} strokeWidth={1.5} strokeDasharray="3 8" />
      <Path
        d="M40 258 C18 196 26 96 70 30"
        stroke={colors.iconSecondary}
        strokeOpacity={0.07}
        strokeWidth={1.5}
        strokeDasharray="3 8"
      />
      <Path
        d="M300 258 C322 196 314 96 270 30"
        stroke={colors.iconSecondary}
        strokeOpacity={0.07}
        strokeWidth={1.5}
        strokeDasharray="3 8"
      />
    </Svg>
  );
}

/**
 * The card's accent, animated. `strokeOpacity` and `fillOpacity` are numeric SVG props the renderer
 * takes directly, so the mark brightens on the UI thread when a WaniKani account is really
 * connected — the one place a real status reaches the artwork.
 */
const AnimatedAccentRect = Animated.createAnimatedComponent(Rect);
const AnimatedAccentRule = Animated.createAnimatedComponent(Path);

/**
 * The one card, and its own local box: the rounded surface, its reading inset, the accent that marks
 * the inscription, and 合点 itself. Drawn in local coordinates so the layer around it can scale and
 * rotate the whole card — inscription included — as one object.
 *
 * [success] is the flow's real-connection accent, 0 to 1; it only brightens the mark the card
 * already carries, so a card that is not connected is exactly the card the other pages show.
 */
function GatenCard({success}: {success: SharedValue<number>}) {
  const {colors} = useTheme();
  const accentProps = useAnimatedProps<RectProps>(() => ({
    strokeOpacity: 0.32 + 0.38 * success.value,
    fillOpacity: 0.03 + 0.07 * success.value,
  }));
  const ruleProps = useAnimatedProps<PathProps>(() => ({strokeOpacity: 0.7 * success.value}));
  return (
    <Svg
      width={CARD_WIDTH}
      height={CARD_HEIGHT}
      viewBox={`0 0 ${CARD_WIDTH} ${CARD_HEIGHT}`}
      fill="none"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <Rect
        x={0.9}
        y={0.9}
        width={CARD_WIDTH - 1.8}
        height={CARD_HEIGHT - 1.8}
        rx={14}
        fill={colors.surface}
        stroke={colors.iconSecondary}
        strokeOpacity={0.45}
        strokeWidth={1.8}
      />
      <Path d="M20 20 H58" stroke={colors.iconSecondary} strokeOpacity={0.23} strokeWidth={1.7} />
      <Rect x={33} y={28} width={118} height={64} rx={8} fill={colors.surfaceVariant} fillOpacity={0.5} />
      <AnimatedAccentRect
        x={43}
        y={33}
        width={98}
        height={50}
        rx={4}
        fill={colors.accent}
        stroke={colors.accent}
        strokeWidth={1.8}
        strokeOpacity={0.32}
        fillOpacity={0.03}
        animatedProps={accentProps}
      />
      {/* 合 and 点 side by side, each fitted to a 40-unit ink height and centred as one pair. */}
      <Inscription outline={GLYPH_AU} transform="translate(47.733 74.954) scale(0.04415 -0.04415)" opacity={0.72} />
      <Inscription outline={GLYPH_TEN} transform="translate(93.315 74.7) scale(0.044 -0.044)" opacity={0.72} />
      <Path
        d="M20 100 H164 M20 114 H120"
        stroke={colors.iconSecondary}
        strokeOpacity={0.23}
        strokeWidth={1.7}
      />
      {/* The connected mark: a rule under the inscription that only a real connection draws. */}
      <AnimatedAccentRule
        d="M43 90 H141"
        stroke={colors.accent}
        strokeWidth={1.8}
        strokeOpacity={0}
        animatedProps={ruleProps}
      />
    </Svg>
  );
}

/** One of the deck's plain supporting cards, in its own local box. */
function DeckCard({width, height}: {width: number; height: number}) {
  const {colors} = useTheme();
  return (
    <Svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} fill="none">
      <Rect
        x={0.9}
        y={0.9}
        width={width - 1.8}
        height={height - 1.8}
        rx={13}
        fill={colors.surface}
        stroke={colors.iconSecondary}
        strokeOpacity={0.3}
        strokeWidth={1.5}
      />
    </Svg>
  );
}

/**
 * The persistent scene. [scene] is the flow's single clock, in page units (0 lookup, 1 Anki, 2
 * WaniKani); [entrance] is the first-run settle, 0 to 1; [formProgress] recedes the scene — and the
 * hero zone around the core — to the small anchor the WaniKani token form leaves behind, 0 to 1;
 * [success] is the card's real connection accent, 0 to 1, and only brightens the mark the card
 * already carries. The component owns its full layout height: the taller zone at rest, the core's
 * own anchor once the form is up.
 *
 * Every layer's motion is a pure function of those values, so a step change and its reverse travel
 * the same path — back is the forward journey played backwards, never a second entrance.
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
  const {colors} = useTheme();
  const scale = width / SCENE_WIDTH;

  // The scene's own box: the full hero zone at rest, shrinking to the core's compact anchor as the
  // WaniKani form takes the page over, and settling in on the first run. Both ends are the hero
  // geometry times the window scale, so the added margin closes exactly as the core recedes.
  const boxStyle = useAnimatedStyle(() => ({
    height:
      interpolate(formProgress.value, [0, 1], [HERO_HEIGHT, HERO_ANCHOR_HEIGHT], Extrapolation.CLAMP) * scale,
    opacity: entrance.value,
    transform: [{translateY: (1 - entrance.value) * 10}],
  }));

  // The inner stage is a fixed 340 x 220 box scaled once for the window and again for the recede. It
  // stays centred in the outer box, so the core sits [HERO_PAD] from each edge at rest and exactly
  // flush at the anchor.
  const innerStyle = useAnimatedStyle(() => ({
    transform: [{scale: scale * (1 - CORE_RECEDE * formProgress.value)}],
  }));

  // The card: one object gliding between the three poses. Its inscription is inside this layer, so
  // host and lettering move as one.
  const cardStyle = useAnimatedStyle(() => {
    const s = scene.value;
    return {
      transform: [
        {translateX: interpolate(s, STOPS, CARD_X, Extrapolation.CLAMP) - CARD_WIDTH / 2},
        {translateY: interpolate(s, STOPS, CARD_Y, Extrapolation.CLAMP) - CARD_HEIGHT / 2},
        {scale: interpolate(s, STOPS, CARD_SCALE, Extrapolation.CLAMP)},
        {rotate: `${interpolate(s, STOPS, CARD_ROTATE, Extrapolation.CLAMP)}deg`},
      ],
    };
  });

  // The two supporting cards each carry their own small rotation and translation swing: gathered
  // behind the front card at 0, fanned out at 1. How far the deck is dealt eases in and out across
  // the two transitions, so the cards fan out on the way in and gather back on the way out.
  const deckBackStyle = useAnimatedStyle(() => {
    const d = interpolate(scene.value, [0.15, 0.8, 1.2, 1.85], [0, 1, 1, 0], Extrapolation.CLAMP);
    return {
      opacity: d,
      transform: [
        {translateX: interpolate(d, [0, 1], [172, 156]) - ANKI_CARD_WIDTH / 2},
        {translateY: interpolate(d, [0, 1], [100, 96]) - ANKI_CARD_HEIGHT / 2},
        {rotate: `${interpolate(d, [0, 1], [3, -12])}deg`},
      ],
    };
  });

  const deckMidStyle = useAnimatedStyle(() => {
    const d = interpolate(scene.value, [0.15, 0.8, 1.2, 1.85], [0, 1, 1, 0], Extrapolation.CLAMP);
    return {
      opacity: d,
      transform: [
        {translateX: interpolate(d, [0, 1], [175, 166]) - ANKI_CARD_WIDTH / 2},
        {translateY: interpolate(d, [0, 1], [100, 99]) - ANKI_CARD_HEIGHT / 2},
        {rotate: `${interpolate(d, [0, 1], [4, -5])}deg`},
      ],
    };
  });

  // The rating controls rise into place a beat after the deck. They always leave downwards — 10
  // units toward the WaniKani page, 14 back toward the lookup page — because they belong to the Anki
  // page and drop away in whichever direction the reader leaves it.
  const ratingsStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [0.25, 0.85, 1.15, 1.85], [0, 1, 1, 0], Extrapolation.CLAMP),
    transform: [
      {
        translateY: interpolate(scene.value, [0.25, 0.85, 1.15, 1.85], [14, 0, 0, 10], Extrapolation.CLAMP),
      },
    ],
  }));

  // The torii and the second subject card settle in together once the card has arrived beside them.
  const toriiStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [1.15, 1.9], [0, 1], Extrapolation.CLAMP),
    transform: [
      {translateX: interpolate(scene.value, [1.15, 1.9], [-18, 0], Extrapolation.CLAMP)},
    ],
  }));

  // The lens examines the pane on the lookup page and withdraws up and to the right as the page
  // leaves — the same path back.
  const lensStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [0, 0.5], [1, 0], Extrapolation.CLAMP),
    transform: [
      {translateX: interpolate(scene.value, [0, 0.5], [0, 30], Extrapolation.CLAMP)},
      {translateY: interpolate(scene.value, [0, 0.5], [0, -24], Extrapolation.CLAMP)},
    ],
  }));

  const guidesStyle = useAnimatedStyle(() => ({
    opacity: interpolate(scene.value, [0, 0.45], [1, 0], Extrapolation.CLAMP),
  }));

  // The hero backdrop shares the scene's entrance and window scale, but its extra margin belongs to
  // the roomy first run alone: it fades out by the time the WaniKani form is a third of the way in,
  // so the compact anchor the form leaves behind stays clean.
  const backdropStyle = useAnimatedStyle(() => ({
    opacity: interpolate(formProgress.value, [0, 0.3], [1, 0], Extrapolation.CLAMP),
  }));

  return (
    <Animated.View style={[{width, alignItems: "center", justifyContent: "center"}, boxStyle]}>
      <Animated.View style={[{width: SCENE_WIDTH, height: SCENE_HEIGHT}, innerStyle]}>
        {/* The hero zone's own backdrop: the wash and guides that fill the added margin. */}
        <Layer style={[backdropStyle, BACKDROP_BOX_STYLE]}>
          <HeroBackdrop />
        </Layer>

        {/* The ground never moves or leaves. */}
        <Layer>
          <StageSvg>
            <Ground />
          </StageSvg>
        </Layer>

        {/* Lookup: the quiet guide curve behind the pane. */}
        <Layer style={guidesStyle}>
          <StageSvg>
            <Path
              d="M66 128 C44 87 55 43 92 32"
              stroke={colors.iconSecondary}
              strokeOpacity={0.16}
              strokeWidth={1.5}
              strokeDasharray="3 8"
            />
          </StageSvg>
        </Layer>

        {/* Anki: the deck's two supporting cards, each fanning on its own swing. */}
        <Layer style={[deckBackStyle, DECK_BOX_STYLE]}>
          <DeckCard width={ANKI_CARD_WIDTH} height={ANKI_CARD_HEIGHT} />
        </Layer>
        <Layer style={[deckMidStyle, DECK_BOX_STYLE]}>
          <DeckCard width={ANKI_CARD_WIDTH} height={ANKI_CARD_HEIGHT} />
        </Layer>

        {/* Anki: the four quiet rating controls. */}
        <Layer style={ratingsStyle}>
          <StageSvg>
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
          </StageSvg>
        </Layer>

        {/* WaniKani: the torii and the second subject card, settling as one group. */}
        <Layer style={toriiStyle}>
          <StageSvg>
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
              <Inscription
                outline={GLYPH_MOUNTAIN}
                transform="translate(231.616 145.903) scale(0.03277 -0.03277)"
              />
              <Path d="M227 161 H269" stroke={colors.accent} strokeOpacity={0.32} strokeWidth={1.8} />
            </G>
          </StageSvg>
        </Layer>

        {/* The one card. */}
        <Layer style={[cardStyle, CARD_BOX_STYLE]}>
          <GatenCard success={success} />
        </Layer>

        {/* Lookup: the lens examining the pane, in front of it. */}
        <Layer style={lensStyle}>
          <StageSvg>
            <Defs>
              <ClipPath id="lensAperture">
                <Circle cx={227} cy={120} r={29} />
              </ClipPath>
            </Defs>
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
            {/* The pane's own 点, enlarged and cut to the aperture, so the lens is looking at the same
                compound the card carries rather than at empty glass. It is the bundled outline the
                card's inscription uses, drawn in the same ink and inside this layer, so it withdraws
                with the lens. */}
            <G clipPath="url(#lensAperture)">
              <Inscription
                outline={GLYPH_TEN}
                transform="translate(192.866 145.882) scale(0.0682 -0.0682)"
                opacity={0.6}
              />
            </G>
            <Path
              d="M207 117 C208 109 213 103 220 101"
              stroke={colors.iconSecondary}
              strokeOpacity={0.2}
              strokeWidth={2}
            />
          </StageSvg>
        </Layer>
      </Animated.View>
    </Animated.View>
  );
}
