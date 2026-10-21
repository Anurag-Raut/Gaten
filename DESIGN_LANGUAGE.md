# Gaten design language

| Area | Current rule | Source of truth |
|---|---|---|
| Theme | System / Light / Dark × Gaten / Mono / Cool / Warm × seven accents | [theme.tsx](src/rn/ui/theme.tsx), [Color.kt](app/src/main/java/com/gaten/app/ui/theme/Color.kt) |
| Shared spacing | 4, 8, 12, 16, 24, 32 dp | [tokens.ts](src/rn/ui/tokens.ts) |
| Shared type | Manrope for Latin across shared roles; reference-matched Home elements use bundled Inter; system fallback for Japanese | [Type.kt](app/src/main/java/com/gaten/app/ui/theme/Type.kt), [inter.xml](app/src/main/res/font/inter.xml) |
| Shared shapes | 12 / 20 / 28 dp; cards use 1 dp elevation, no border | [Theme.kt](app/src/main/java/com/gaten/app/ui/theme/Theme.kt), [components.tsx](src/rn/ui/components.tsx) |
| Home target | Reference-matched header/search and streak, compact side-by-side Anki/WaniKani cards, CTA, recent lookups, and floating tabs | [HomeScreen.tsx](src/rn/ui/HomeScreen.tsx), [HomeDashboard.tsx](src/rn/ui/home/HomeDashboard.tsx), [BottomBar.tsx](src/rn/ui/BottomBar.tsx) |
| Interaction | 48 dp target for standalone controls; local inline exceptions; press scale 0.98 over 100 ms | [PressableScale.tsx](src/rn/ui/PressableScale.tsx), [GatenHaptics.ts](src/rn/native/GatenHaptics.ts) |
| Data display | Show only source-backed counts; distinguish zero, unavailable, and not connected | [useHomeDashboard.ts](src/rn/ui/home/useHomeDashboard.ts) |

## Scope

This file records the shared app theme and the approved Home screen design. Kotlin owns the color, typography, and shape definitions; React Native reads resolved colors through the native theme bridge and mirrors the shared type and spacing tokens. Home has additional, intentionally fixed illustration colors and compact component measurements described below. Those Home treatments do not replace the shared semantic palette.

Use the shared tokens and components for new work. Keep component-specific exceptions local to their owning screen. This guide describes the current foundation and Home target; it does not claim that every existing Compose screen has already been visually rebuilt to match Home.

## Theme model and color

The user selects three independent preferences: mode (`System`, `Light`, `Dark`), neutral palette (`Gaten`, `Mono`, `Cool`, `Warm`), and accent (`Indigo`, `Blue`, `Violet`, `Teal`, `Green`, `Rose`, `Black`). Defaults are System, Gaten, and Indigo. System mode follows the live OS setting; explicit Light or Dark wins over it. Wallpaper-based dynamic color is not used. React Native screens read semantic roles through `useTheme`; the provider waits for its first resolved color map and refreshes on OS scheme and native preference changes.

Palette changes retint the neutral ladder while preserving its shipped relative luminance. Accent changes only accent roles. Errors remain red. The Kotlin theme bridge is authoritative: consume its semantic roles instead of calculating colors in a screen.

### Shipped neutral and status colors

| Role | Light | Dark |
|---|---|---|
| `background` | `#FAFAF9` | `#111111` |
| `surface` | `#FFFFFF` | `#1A1A1A` |
| `surfaceVariant`, `surfaceContainer` | `#F1F1EF` | `#242424` |
| `surfaceContainerHigh` | `#EAEAE7` | `#2B2B2B` |
| `surfaceContainerHighest`, `outline` | `#E4E4E1` | `#2E2E2E` |
| `onSurface` | `#1A1A1A` | `#EDEDED` |
| `onSurfaceVariant` | `#6B6B6B` | `#9A9A9A` |
| `error` | `#C62828` | `#EF9A9A` |
| `errorContainer` | `#FFEBEE` | `#B71C1C` |
| `onErrorContainer` | `#B71C1C` | `#FFEBEE` |

`onBackground` aliases `onSurface`; `surfaceContainerLow` aliases `surfaceContainer`; `surfaceContainerLowest` aliases `surface`; and `outlineVariant` aliases `outline`. Secondary and tertiary container roles alias the selected accent's container. Inverse roles use the other mode's matching neutral and accent tones.

Mono uses zero hue and saturation. Cool uses hue 215°; its paper/ink saturations are 0.30/0.10 in Light and 0.25/0.12 in Dark. Warm uses hue 45°; paper/ink saturations are 0.45/0.10 in Light and 0.30/0.12 in Dark. The bridge retints each role at the shipped luminance (rounded upward where an 8-bit channel falls between steps). Gaten has no tint and returns the exact neutral values above.

### Accent roles

The following tuples are `(primary, primaryContainer, onPrimaryContainer, tertiary)`. `onPrimary` is `#FFFFFF` in Light and `#111111` in Dark for every accent.

Every accent is pitched for contrast, not tinting: a Light primary is deep enough to carry white ink and to be read as text on the paper, and a Dark primary is light enough to carry the near-black ink — both at 4.5:1 or better, held by `PaletteTest.everyAccentCarriesItsInkAndReadsOnThePaper`. Black is the colourless accent, the same ramp with no hue.

| Accent | Light | Dark |
|---|---|---|
| Indigo | `#4338CA`, `#EEF2FF`, `#1E1B4B`, `#6366F1` | `#A5B4FC`, `#312E81`, `#E0E7FF`, `#6366F1` |
| Blue | `#0B4FB8`, `#EFF6FF`, `#172554`, `#2563EB` | `#93C5FD`, `#1E3A8A`, `#DBEAFE`, `#3B82F6` |
| Violet | `#6D28D9`, `#F5F3FF`, `#2E1065`, `#7C3AED` | `#C4B5FD`, `#4C1D95`, `#EDE9FE`, `#8B5CF6` |
| Teal | `#115E59`, `#F0FDFA`, `#042F2E`, `#0D9488` | `#5EEAD4`, `#134E4A`, `#CCFBF1`, `#14B8A6` |
| Green | `#166534`, `#F0FDF4`, `#052E16`, `#16A34A` | `#6EE7B7`, `#14532D`, `#DCFCE7`, `#10B981` |
| Rose | `#9F1239`, `#FFF1F2`, `#4C0519`, `#E11D48` | `#FDA4AF`, `#881337`, `#FFE4E6`, `#F43F5E` |
| Black | `#000000`, `#EAEAEA`, `#17171A`, `#4A4A4A` | `#FFFFFF`, `#404040`, `#EAEAEA`, `#CBCBCB` |

## Spacing, layout, and shape

Use the named spacing scale in dp: `xs=4`, `s=8`, `m=12`, `l=16`, `xl=24`, `xxl=32`. Small optical adjustments such as 1–2 dp, hairlines, and component-specific gaps may remain local when the layout calls for them; do not add a second general scale. Shared content is capped at 840 dp and centered; the shared two-column threshold is 640 dp.

The shared corner steps are 12 dp (small), 20 dp (medium), and 28 dp (large). A shared `Card` uses the surface color, medium radius, 1 dp elevation, and a subtle black shadow (8% opacity, 2 dp blur, 1 dp downward offset); it has no border. Use `outlineVariant` as the hairline for row separators. Shared buttons are pills; filled, tonal, and outlined controls are 48 dp high, while inline text buttons are 40 dp high with 4 dp hit slop. A standard icon button has a 48 dp target; compact icon buttons are 36 dp. Some existing Home inline actions use 36–40 dp; keep those local when matching this target, and use 48 dp for new standalone actions. `IconBadge` defaults to 44×44 dp, 12 dp radius, and a 22 dp glyph. A `Notice` uses `surfaceVariant`, medium radius, 16 dp padding, and 8 dp internal gap.

### Home layout measurements

Home is a vertically scrolling, safe-area-aware column with a 16 dp horizontal inset and 12 dp between dashboard sections. Its content is capped at 412 dp. The `Gaten` brand is Inter 28/36 sp at 700 weight, with 14 dp top and 12 dp bottom margin. The compact search field is 44 dp high with a 22 dp radius, 16 dp horizontal padding, and 12 dp icon/text gap; its text is Inter 16/22 sp at 400 weight. On Gaten Light, the page is `#FAFAFA`, brand ink is `#14142B`, and the search fill/stroke are `#EFEFF1` / `#E3E3E7`; its glyph and placeholder are `#4A4A50` / `#7C7C84`. Other palettes and Dark use resolved theme colors.

| Home component | Current measurements |
|---|---|
| Streak card | Maximum width 400 dp; minimum height 120 dp; radius 20 dp; 12 dp padding on all sides. Header min-height 38 dp, flame/copy gap 8 dp; weekday row starts 10 dp below. |
| Streak type and marks | Inter title 16/20 sp at 700; short copy 12/16 sp at 400; total value 15/18 sp at 700 and 10/12 sp caption. Flame 30 dp, weekday marks 28 dp; labels are Inter 11/14 sp at 500, 4 dp below the mark; today label is 700. |
| Service cards | Side-by-side pair, 12 dp gap; equal flex columns with `minWidth: 0`. At a 393 dp phone width each card is about 175 dp wide. Minimum height 112 dp, radius 20 dp, single 1 dp border, 12 dp horizontal/11 dp vertical padding. Header min-height 37 dp; bottom count/action row min-height 44 dp and starts 6 dp below. Title Inter 16/22 sp at 700; status 11/15 sp at 600 with a 6 dp dot and 6 dp gap; count 24/28 sp at 700 plus 11/14 sp unit with 5 dp gap; action 13/17 sp at 600. Original service art is 100×100 dp at bottom-right, overlaid at 35% Light / 20% Dark opacity; text uses the full row and may cross the artwork where contrast holds. |
| Start studying | Minimum height 52 dp; radius 26 dp; vertical gradient `#5A3EF0 → #4B31E3`; Inter 18/24 sp at 600; trailing white chevron sits 20 dp from the end. Disabled state remains noninteractive and is exposed as disabled to accessibility while retaining the requested saturated Indigo sample. |
| Recent lookups | Section begins 8 dp below the CTA; heading Inter 20/28 sp. Empty state uses a 32 dp icon with 10 dp lower gap, title 16/22 sp at 600 and hint 13/18 sp. A populated list has 14 dp radius; rows are at least 58 dp high with 12 dp horizontal and 4 dp vertical padding; glyph tile 38 dp. |
| Floating bottom bar | 16 dp side gutter and top gap; 64 dp surface height plus the system bottom inset outside the surface; radius 32 dp and Light background `#F1F1F3`. Selected Home capsule 113×44 dp, radius 22 dp, `#4F35E8`, icon + Inter 16/22 sp label at 600 with 8 dp gap. Other tab slots are 66 dp wide and retain at least 48 dp touch targets. Native bar animation is preserved. |

Home cards use flexible content and a vertical scroll rather than shrinking text to fit a fixed page height. The two service cards stay in one row at phone widths; at default font scale their 112 dp pair saves 148 dp (about 57%) compared with two 124 dp cards plus a 12 dp gap. The compact 120 dp streak plus 112 dp service pair occupies 232 dp versus 428 dp at the former 168 dp streak and 260 dp stacked services, saving 196 dp (about 46%) before section gaps. Keep each service's status, due count, and action visible; labels may wrap and cards grow for larger font scales or longer states rather than clipping. The bottom bar measures its labels at the user's current font scale and sizes its selected capsule to the measured label.

## Typography

Latin text uses bundled Manrope at 400, 500, or 600 weight for shared Compose/RN roles. Reference-matched Home elements use bundled Inter at 400, 500, 600, and 700. Japanese glyphs fall back to the platform system face; do not add a separate Japanese font or force bold CJK. Text sizes follow the user's system font scale without an app cap. Layouts that contain text must grow or scroll instead of clipping it in a fixed-height box.

| Role | Size / line height | Weight |
|---|---:|---:|
| `displayMedium` | 40 / 48 sp | 500, system face |
| `displaySmall` | 34 / 44 sp | 500, system face |
| `headlineSmall` | 26 / 34 sp | 600, Manrope |
| `titleLarge` | 24 / 32 sp | 600, Manrope |
| `titleMedium` | 19 / 26 sp | 500, Manrope |
| `titleSmall` | 16 / 24 sp | 600, Manrope |
| `bodyLarge` | 17 / 26 sp | 400, Manrope |
| `bodyMedium` | 15 / 22 sp | 400, Manrope |
| `bodySmall` | 13 / 18 sp | 400, Manrope |
| `labelLarge` | 15 / 20 sp | 500, Manrope; 0.1 letter spacing |
| `labelMedium` | 13 / 18 sp | 500, Manrope; 0.3 letter spacing |
| `labelSmall` | 12 / 16 sp | 500, Manrope; 0.4 letter spacing |

The reference-matched Home brand, compact search field, streak, service cards, CTA, recent-lookups heading, and empty-state title use the bundled Inter face and local sizes above. Shared search-result and secondary utility text continues to use the shared Manrope roles where those components reuse them. Japanese glyphs use the platform fallback. Use the shared roles elsewhere.

## Home color and vector treatment

The Home target keeps its illustrated surfaces stable across accent choices. Semantic controls and the navigation capsule use the selected accent. The compact streak card is Light `#F1EEFF` and Dark `#27243B`, with title `#1B1740` / `#F6F2FF` and copy `#5A5780` / `#C9C3E0` in Light / Dark. Its reference flame retains four source layers, scaled to 30 dp: active `#FF982A`, `#FF8700`, `#FFC433`, `#FFD250`; inactive `#C9C4DD`, `#B1ABCC`, `#E3E0F0`, `#F1EFF8`. Day marks retain their supplied reference palette in both modes, scaled to 28 dp: completed `#6A4BF4` with a white check; current day white with `#6A4BF4` ring and center dot; missed `#DDDAEA` with `#5F5C7C` cross; known-empty/future white with `#E2DEF4` ring and `#B3AECB` dot. Unknown activity adds a neutral dash mark. Preserve each source SVG's paths and viewBox while scaling it; do not recolor the supplied state art by semantic accent.

Service card colors and source illustrations retain the supplied HTML palette and PNGs; the phone Home layout uses the compact pair specified above. Anki is Light `#EEF4FF → #D9E8FF` with border `#CFE0FA`, text `#14142B`, muted `#4B4F6B`, and count `#3B4FE0`; Dark is `#1B2A3E → #263B55`, border `#344A67`, text `#F7FAFF`, muted `#CAD5E7`, and count `#AFC2FF`. WaniKani is Light `#FFF3FA → #FBE3F1` with border `#F4D3E6`, text `#14142B`, muted `#4B4F6B`, and count `#C42B52`; Dark is `#2D2330 → #3B2A3A`, border `#563D52`, text `#FFF8FC`, muted `#E0CDD9`, and count `#FF9AB9`. Connected status is green (`#166534` text, `#16A34A` dot); disconnected/error is red (`#991B1B` text, `#DC2626` dot); neutral/loading status uses muted gray. The source PNGs are [Anki stack](src/rn/ui/home/assets/anki-stack.png) (178×174) and [WaniKani torii](src/rn/ui/home/assets/wanikani-torii.png) (181×174). They render at 100×100 dp in each card's bottom-right corner at 35% opacity in Light / 20% in Dark and fade from transparent to opaque across the left 45% edge. They stay decorative and hidden from accessibility. These opaque PNGs have no dark variants; Dark mode additionally fades their top and bottom 8% and rightmost 8%, while the interior remains the supplied pastel art. Because the art is faint and not a content layer, service text can use the full card width across it. Keep adequate contrast when changing opacity, surface colors, or copy. The floating bar uses `surfaceContainer` on the page background. Its default Indigo selected capsule is Light `#4F35E8` with white ink and Dark `#B9A7FF` with `#171124` ink; other accents use semantic `primary` / `onPrimary`.

The default Indigo Start studying action uses Light `#5A3EF0 → #4B31E3` with white text; in Dark when actionable it uses `#C8B6FF → #B9A7FF` with `#261849` text. When Anki cannot open a review session, the default Light Indigo sample intentionally stays saturated at 90% opacity but has no press action and reports disabled accessibly. Other disabled treatments are muted: Light `#F0EBFF → #E8E0FF` with `#8573B5` text, Dark `#383246 → #302A3C` with `#BDB2D5` text. Other enabled accents use the current semantic primary. Do not infer that visible purple means the action is available.

Home artwork has three sources: the original shared vector pack in [HomeSvgAssets.ts](src/rn/ui/home/HomeSvgAssets.ts), the reference streak flame/day SVGs in [HomeStreakAssets.ts](src/rn/ui/home/HomeStreakAssets.ts), and the original service-card PNGs above. Preserve source `viewBox` and aspect ratio; service art uses the compact bottom-right placement with left-edge alpha fade. The shared pack includes cat and sprout illustrations, app marks, and tab glyphs. Its recurring gradients are violet fur (Light `#D9CBFF → #7C55F9 → #5E43DF`, Dark `#AA96E8 → #906BFF → #7352F2`), blue (`#A8DBFF → #448DFF → #5364E9` Light; middle stop `#67AAFF` Dark), main blue (`#87C9FF → #326FF3`), violet background (`#CCB5FF → #9A70FA`), rose (`#FFC0D7 → #EF759C → #E64E69`), green (`#8BE4B6 → #37B98C`), and yellow (`#FFF5A0 → #FFB72B → #FA7925`). These belong to the supplied illustrations, not semantic accent roles. Keep the streak flame/day SVGs at 40 dp and tab icons at 28 dp. Do not stretch artwork, replace supplied marks with emoji, or tint the illustration pack as a semantic icon font. The generic Material path icon remains 24 dp inside its control unless a call site specifies another size.

## System surfaces, motion, and feedback

The app draws edge-to-edge. Keep content inside `SafeAreaView`/safe-area insets: Home owns top/left/right insets; the floating bottom bar includes the bottom gesture inset and leaves an 8 dp gap below its rounded surface. Status-bar background and icon contrast follow the resolved page theme; Android navigation-bar color and gesture-icon contrast are updated through the native theme bridge. These are real OS surfaces: keep the OS clock, status icons, gesture handle, and system insets native rather than drawing screenshot chrome inside the app. Do not add an extra opaque inset view that breaks the rounded floating-bar perimeter.

`PressableScale` is the common non-tab press response: 1.0 to 0.98 over 100 ms on `[0.4, 0, 0.2, 1]`, driven by Reanimated. The bottom-bar capsule and glyphs follow pager position with animated transforms and opacity. Tab selection ticks once; reselecting the current tab is a no-op. Use the existing feedback bridge: `tick` for a meaningful step or selection, `lookup` for a dictionary hit, `confirm` for a landed action; `added` and `done` are sound cues and respect the sound preference. Keep reduced-motion behavior in the app shell and do not add motion to static artwork.

## Data and visible states

Treat Anki, WaniKani, and local recent lookups as independent sources. Loading, disconnected, permission-needed, setup, authentication failure, and retryable error each keep their own label and available action; a failure from one source must not hide good data from another. Counts should say what they measure, and a stale or unavailable value must not be presented as zero.

- The Anki card shows collection-wide due counts: sum new, learning, and review counts from top-level deck rows only, because parent rows already include child decks. It does not depend on the selected export deck. When today's completed-card count is positive, label the due count `remaining`; otherwise label it `reviews due`. A review session opens the collection deck picker. Keep zero distinct from unavailable counts.
- The streak uses Anki's seven collection-wide study-day counts. It can prove a streak up to the seven-day window; seven active days are described as a full week, not as an invented lifetime streak. Anki's collection rollover determines study-day boundaries. The lifetime total remains an em dash until an all-time distinct-day source exists; never substitute the seven-day sum.
- A weekday check means known activity. A missed mark is only for a known past zero after earlier activity; with observed study activity, today without reviews is the outlined current mark. A completely empty history stays hollow, and future days are empty. Unknown history uses a neutral dash on the white `#E2DEF4` ring (`#B3AECB` dash), not the known-empty dot. Weekday names use the local Monday–Sunday calendar while counts follow Anki's configured study-day rollover; the streak title's accessibility label explains that boundary. When history cannot load, say so and provide the source's retry action.
- The WaniKani card shows its actual due-now count. Its available stats source has no completed-review history, so do not show completed progress or claim a daily completion count.
- Recent lookups are local device history. Loading, empty, and error have separate copy; errors expose retry. Preserve source-backed entries on refresh failure rather than replacing them with an empty state. In Gaten Light, the empty badge uses `#EDEDEF`, its search glyph `#3A3A3F`, and hint text `#6B6B72`; list surfaces and separators use semantic theme roles.
- A disabled Start studying control stays visibly disabled to touch and accessibility even when its requested Indigo artwork remains saturated. Explain the missing connection in its accessible label.

## Source of truth

- Shared React Native tokens and roles: [tokens.ts](src/rn/ui/tokens.ts), [theme.tsx](src/rn/ui/theme.tsx), [GatenTheme.ts](src/rn/native/GatenTheme.ts).
- Native theme choices and bridge: [ThemePreferences.kt](app/src/main/java/com/gaten/app/ui/theme/ThemePreferences.kt), [GatenThemeModule.kt](app/src/main/java/com/gaten/app/rn/GatenThemeModule.kt).
- Native color, shape, type, spacing, and motion: [Color.kt](app/src/main/java/com/gaten/app/ui/theme/Color.kt), [Theme.kt](app/src/main/java/com/gaten/app/ui/theme/Theme.kt), [Type.kt](app/src/main/java/com/gaten/app/ui/theme/Type.kt), [Spacing.kt](app/src/main/java/com/gaten/app/ui/theme/Spacing.kt), [Motion.kt](app/src/main/java/com/gaten/app/ui/theme/Motion.kt).
- Shared RN building blocks and interaction: [components.tsx](src/rn/ui/components.tsx), [PressableScale.tsx](src/rn/ui/PressableScale.tsx), [BottomBar.tsx](src/rn/ui/BottomBar.tsx), [AppShell.tsx](src/rn/ui/AppShell.tsx).
- Home layout, artwork, and source state: [HomeScreen.tsx](src/rn/ui/HomeScreen.tsx), [HomeDashboard.tsx](src/rn/ui/home/HomeDashboard.tsx), [HomeArtwork.tsx](src/rn/ui/home/HomeArtwork.tsx), [HomeSvgAssets.ts](src/rn/ui/home/HomeSvgAssets.ts), [HomeStreakAssets.ts](src/rn/ui/home/HomeStreakAssets.ts), [useHomeDashboard.ts](src/rn/ui/home/useHomeDashboard.ts), [Anki native bridge](app/src/main/java/com/gaten/app/rn/GatenAnkiModule.kt), [Anki review source](app/src/main/java/com/gaten/app/anki/AnkiReview.kt).
- Home Inter font: [weighted Android family](app/src/main/res/font/inter.xml), [registered RN family](app/src/main/java/com/gaten/app/MainApplication.kt), [Inter variable font](app/src/main/res/font/inter_variable.ttf), [OFL license](app/src/main/assets/licenses/Inter-OFL.txt). It is the Google Fonts Inter 4.1 variable font, registered at weights 400, 500, 600, and 700.
