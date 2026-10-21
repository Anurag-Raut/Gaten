# Design language implementation

The approved source of truth is [Gaten / Ink v1](Gaten_Ink_Design_Language_v1.md),
provided by the user on 21 October 2026. It supersedes the earlier design-language draft,
including its pure-white canvas and generic 14/20 radius scale. The supplied document is
kept verbatim in this directory.

## Shared implementation

| Concern | Source |
| --- | --- |
| Light/dark neutrals and accent defaults | `app/src/main/java/com/gaten/app/ui/theme/Color.kt` |
| Native typography and shapes | `app/src/main/java/com/gaten/app/ui/theme/Type.kt`, `Theme.kt` |
| Native-to-React Native color bridge | `app/src/main/java/com/gaten/app/rn/GatenThemeModule.kt` |
| React Native semantic colors | `src/rn/ui/theme.tsx` |
| Typography, spacing and named radii | `src/rn/ui/tokens.ts` |
| Shared controls | `src/rn/ui/components.tsx` |
| Floating tab navigation | `src/rn/ui/BottomBar.tsx` |

## Production choices

- Ink is the default Gaten palette. Existing user-selected palette and accent preferences remain available.
- Use readable secondary text (`#6C6C75` light, `#B0B0BA` dark). Fine details and status dots have separate roles.
- Compact explanatory text starts at 12 sp; the selected bottom-navigation label follows the preview's 11 sp size and scales with Android font settings.
- Layout uses dp, a 22 dp content inset (16 dp below 360 dp), and a maximum 560 dp content column.
- Named corner radii: brand marks 10, setting icon tiles 11, empty-state icons 12, buttons 13, inputs 14, selected navigation 15, settings rows 17, floating navigation/contained groups 20.
- Separators and visible row borders are 1 dp. Focus indicators are 2 dp. Content surfaces have no shadows.
- Settings subpage sections use rounded tiles filled with the neutral secondary surface (`surfaceVariant`); form fields use the main surface with a quiet outline so they remain distinct inside a tile.
- Preserve the application's real Home, Analytics, Review and Settings destinations. The mockup's Profile tab is not a new feature request.
- Japanese reading/display text retains native glyph fallback and readable sizes. Imported Anki card HTML keeps its own authored styling.
- Native status/gesture bars use Android insets; no mock system bars are drawn in the application.

## User-approved preview refinements

The user-supplied `gaten-ink-light-dark-preview.html` is the visual reference for Home and the Settings hub. Its inline outline icons and Anki/WaniKani SVG marks replace the previous in-app glyphs for those elements; the service marks use the preview's light/dark tinted holders. Keep platform status/gesture bars native and retain live app data.

- Home shows only the flame and `Daily streak` on the left; the current recorded-day value and `days recorded` stay on the right.
- The third tab keeps opening Review while using the preview's person outline. The App settings row keeps its real About label and destination.
- Settings rows follow the preview's 72 dp minimum height and grow with font scale; small copy remains readable and scalable.
- Preview geometry never changes route behavior or replaces actual values with sample content.

## Implementation checks (21 October 2026)

- TypeScript compilation and Android debug build pass; seven native palette tests pass.
- Emulator inspected: Home, Settings, Analytics and review deck selection at normal scale;
  Home and Settings at 320 dp / 160% text; dark Home at 130% text.
- Larger text moves integration outcomes below their header and keeps the selected navigation label readable.
- The full width/scale matrix, TalkBack navigation and native screen-lookup overlay have not been exercised in this pass.
