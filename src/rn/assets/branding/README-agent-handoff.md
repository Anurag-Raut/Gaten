# Gaten final icon + wordmark assets — flat background revision

The approved **G and its two dakuten strokes are unchanged**. Only the icon background treatment has changed: **no rounded tile, no border, no drop shadow, no gray surround**.

## Icons
- `gaten-icon-light.svg`: solid **#FFFFFF** full square background, black **#000000** approved G.
- `gaten-icon-dark.svg`: solid **#101114** full square background, light **#F5F5F6** approved G.
- `gaten-icon-themeable.svg`: same artwork, switches via CSS `prefers-color-scheme` in compatible SVG viewers. React Native should use `useTheme().dark` to choose the explicit light/dark SVG instead.
- `gaten-icon-monochrome-transparent.svg`: the same artwork as a WHITE foreground with transparent background, intended as an optional Android monochrome/adaptive foreground resource (system controls tint/background).

## Wordmarks (unchanged)
- `gaten-wordmark-light.svg`
- `gaten-wordmark-dark.svg`
- `gaten-wordmark-themeable.svg`
All wordmark backgrounds are transparent. Wordmark path data is unchanged from the previously approved ZIP.

## Agent instructions
1. Work on the `dev` branch of https://github.com/Anurag-Raut/Gaten.
2. Replace only the branding assets and references that are necessary. Keep G/dakuten geometry identical to these files.
3. The launcher icon background must be a FULL, FLAT solid background. **Do not introduce a card, rounded rect, stroke, bevel, shadow, gradient, or gray backdrop** in the icon art or Android layers. Android may apply its own adaptive icon mask at the launcher level; do not pre-mask the artwork.
4. For in-app wordmark, select light/dark assets via existing `useTheme().dark` and maintain existing layout and transition behavior. Do not use selectable accent colors.
5. For Android adaptive icons, use the monochrome foreground asset if needed, paired with a flat background color; do not rasterize a shadowed/card-style icon. Check standard, round, light/dark, and system-themed launcher icons on device.
6. The app's theme can be independent from the OS launcher theme. A CSS `prefers-color-scheme` rule does not automatically respond to app-specific `useTheme()` in React Native or Android launcher resources.
7. Verify the light icon is exactly #FFFFFF at every background pixel and has no border radius; verify the dark icon is exactly #101114 outside the G.
