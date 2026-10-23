import React, {useId, useMemo} from "react";
import Svg, {Circle, ClipPath, Defs, G, Path, Rect} from "react-native-svg";
import {deckArtSpec} from "./deck-art";
import {useTheme} from "../theme";

type Props = {
  deckId: number | string;
  accountScope?: string;
  width?: number;
  height?: number;
};

/** A clipped, decorative tile whose motif stays attached to the stable deck ID. */
export default function DeckThumbnail({
  deckId,
  accountScope = "ankidroid-local",
  width = 44,
  height = 44,
}: Props) {
  const {dark} = useTheme();
  const stableKey = `${accountScope}:${deckId}`;
  const art = useMemo(() => deckArtSpec(stableKey, dark), [stableKey, dark]);
  const clipId = `deck-art-${useId().replace(/:/g, "")}`;
  const {palette: colors, motif, tilt, phase} = art;
  const rotation = tilt * 6;
  const horizontal = phase % 2 === 0 ? 0 : 4;
  const vertical = phase >= 2 ? 2 : -2;

  return (
    <Svg
      width={width}
      height={height}
      viewBox="0 0 64 64"
      preserveAspectRatio="none"
      accessible={false}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <Defs>
        <ClipPath id={clipId}>
          <Rect
            width={64}
            height={64}
            rx={(12 * 64) / width}
            ry={(12 * 64) / height}
          />
        </ClipPath>
      </Defs>
      <G clipPath={`url(#${clipId})`}>
        <Rect width={64} height={64} fill={colors.base} />
        <G transform={`translate(${horizontal} ${vertical}) rotate(${rotation} 32 32)`}>
          {motif === 0 ? <>
            <Path d="M-12 66C-7 32 14 19 35 21C54 23 68 8 79-13" fill="none" stroke={colors.primary} strokeWidth={18} />
            <Path d="M-13 72C5 45 23 38 45 42C63 45 69 34 80 19" fill="none" stroke={colors.secondary} strokeWidth={11} />
          </> : motif === 1 ? <>
            <Circle cx={67} cy={70} r={55} fill={colors.primary} />
            <Circle cx={71} cy={69} r={39} fill={colors.secondary} />
            <Circle cx={73} cy={69} r={25} fill={colors.base} opacity={0.55} />
          </> : motif === 2 ? <>
            <Path d="M-12 72C-5 28 24 23 37 36C44 42 45 52 41 75Z" fill={colors.primary} />
            <Path d="M11 74C17 43 44 20 76 12C68 46 49 74 11 74Z" fill={colors.secondary} />
          </> : motif === 3 ? <>
            <Path d="M-21 48L45-17L69 7L3 73Z" fill={colors.primary} />
            <Path d="M12 85L81 18L92 30L24 98Z" fill={colors.secondary} />
          </> : <>
            <Circle cx={43} cy={58} r={37} fill={colors.primary} />
            <Path d="M-6 55Q20 37 44 53T74 47V76H-6Z" fill={colors.secondary} />
          </>}
        </G>
      </G>
    </Svg>
  );
}
