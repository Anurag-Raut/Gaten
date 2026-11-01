# Tab heading lettering (Analytics & Settings)

> The screen captures for this work now live in **`docs/pr-evidence/font-ibm-plex/`** (Home, Analytics,
> Settings in light and dark, plus a dictionary lookup), taken from the final build of `60aaa5a`. This
> directory keeps the lettering specimens and the large-font / narrow-width runs, which the final
> capture set does not repeat.

Branch `fix/tab-heading-brand-typography`, source commit `60aaa5a`, based on `dev` at `188608c`.

## What changed

The Analytics and Settings titles were type set in a font beside Home's geometric SVG wordmark, which
never matched. Both titles are now **drawn as the wordmark's own lettering**: the tab-heading role no
longer exists in `tokens.ts`, and `src/rn/ui/TabHeading.tsx` renders one `Svg` per glyph in a wrapping
row.

- `a`, `e`, `n`, `t` are the approved wordmark's own contours, split out of
  `src/rn/assets/branding/gaten-wordmark-light.svg` (subpaths 0–5) and translated to start at x 0.
- `A`, `S`, `l`, `y`, `i`, `c`, `s`, `g` — the letters the wordmark does not carry — are Poppins
  SemiBold outlines scaled to the wordmark's x-height (lowercase) or cap height (capitals) and given a
  compensating SVG stroke so every stem lands on the wordmark's own 92-unit stem.
- The approved wordmark and the G with its two accent marks are untouched: Home still renders
  `GatenWordmark` verbatim, and the G is deliberately absent from the heading glyph set so no
  mark-less G exists anywhere.

The row keeps behaving like text: it scales with the reader's font scale, wraps between letters, and
carries `accessible` + `accessibilityRole="header"` with the label on the row while every glyph is
hidden from the accessibility tree. Layout is unchanged — Analytics starts 23 dp below the safe area
with the refresh control centred beside the title and freshness below, and Settings keeps its 23 dp
inset.

## Large-font and narrow-width captures (Android emulator)

| File | Screen | Conditions |
| --- | --- | --- |
| `analytics-large-narrow.png` | Analytics | 320 dp wide, font scale 1.5 |
| `settings-large-narrow.png` | Settings | 320 dp wide, font scale 1.5 |
| `settings-large-wrap.png` | Settings | 196 dp wide, font scale 1.5 (wrap stress) |

- **Device:** AVD `Gaten_Analytics_Capture` — Pixel 7 profile, Android 34 (`google_apis`, x86_64),
  1080x2340 @ 440 dpi, launched with `-gpu host`. (`-gpu swiftshader_indirect` returns all-black
  screencaps on this host.)
- **Build:** `ANDROID_HOME=/home/Qui/Android/Sdk ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- **APK these came from:** `app/build/outputs/apk/debug/app-x86_64-debug.apk`,
  `sha256 e1bf4e9469587b36657d9dd285618466d3da1d519ed14dfc9c4943dbfcb306a1` — a build of the same tree
  as the final `60aaa5a` captures, before the typeface migration and the streak-row tweak that
  followed it. The lettering they exercise is unchanged since.
- **Capture:** `agent-device` 0.21.31, package `com.gaten` (`com.gaten.app` is the namespace).
- **Narrow/large runs:** `adb shell wm size 880x2340` (320 dp) and `540x2340` (196 dp) with
  `settings put system font_scale 1.5`; both restored afterwards (`wm size reset`, `font_scale 1.0`).

At 320 dp with font scale 1.5 both titles still fit on one line, unclipped and without overlapping the
refresh control. At 196 dp the Settings row wraps between letters onto a second line at the same scale
and baseline.

## The lettering itself

`heading-lettering-light.png` / `heading-lettering-dark.png` (from `heading-lettering-*.svg`) label the
approved wordmark — the file's own contents, every contour and both accent marks — above the two
titles drawn from the same glyph set.

`build-glyphs.py` generates both `src/rn/ui/tabHeadingGlyphs.ts` and those specimens. It needs
`fontTools` (a throwaway venv is fine — it is not a project dependency) and fetches the Poppins
SemiBold face from `https://raw.githubusercontent.com/google/fonts/main/ofl/poppins/Poppins-SemiBold.ttf`
into `/tmp/fontcand` when absent.

Metrics, all in the wordmark's own viewBox units: baseline 451, cap 435, x-height 321, stem 92, shared
glyph box −30…620, ink gap 18. The heading draws at a 24.5 dp cap in a 44 dp line box, both scaled by
`useWindowDimensions().fontScale`; each glyph's viewport is the full line box while its width is the
glyph's ink width, so `xMidYMid meet` keeps one scale and one baseline across wrapped rows. The
generator asserts every glyph's ink — stroke included — fits its viewBox.

## Licensing

Poppins is OFL 1.1: `app/src/main/assets/licenses/Poppins-OFL.txt`, with the derivation recorded in
`NOTICE`. No Poppins font file is bundled — only outlines of eight letters inside
`src/rn/ui/tabHeadingGlyphs.ts`.

## Verification

- `npx tsc --noEmit`: passed.
- `node --test tests/analytics.test.cjs`: 23 passed, 0 failed.
- `:app:assembleDebug`: BUILD SUCCESSFUL.
- Real-app capture on the emulator above at 320 dp/1.5× and 196 dp/1.5× here, and — for the final
  `60aaa5a` build — at default scale in light and dark, plus a dictionary lookup, in
  `docs/pr-evidence/font-ibm-plex/`.

## Limitations

- The four reused letters are the wordmark's own contours; the other eight are derived outlines, so
  they match the wordmark's weight and geometry but are not the logo designer's own drawings.
- Only `Analytics` and `Settings` are supported (`TabHeadingLabel`), which is every glyph in the set.
- Captures are one emulator at one density; no physical-device or tablet run.
