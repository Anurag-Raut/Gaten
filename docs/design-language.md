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
- The accent ladder is pitched for contrast: every accent's primary carries its ink (white in light, near-black in dark) and reads as text on the paper at 4.5:1 or better, held by `PaletteTest.everyAccentCarriesItsInkAndReadsOnThePaper`. Black is the colourless accent, replacing the earlier Graphite.
- Use readable secondary text (`#6C6C75` light, `#B0B0BA` dark). Fine details and status dots have separate roles.
- Compact explanatory text starts at 12 sp; the selected bottom-navigation label follows the preview's 11 sp size and scales with Android font settings.
- The selected bottom-navigation pill is filled with the reader's accent (`primary`, with `onPrimary` ink) instead of the v1 document's neutral `--ink-active-bg`. Filled primary actions elsewhere keep that neutral action fill.
- Layout uses dp, a 22 dp content inset (16 dp below 360 dp), and a maximum 560 dp content column.
- Named corner radii: brand marks 10, setting icon tiles 11, empty-state icons 12, buttons 13, inputs 14, selected navigation 15, settings rows 17, floating navigation/contained groups 20.
- Separators and visible row borders are 1 dp. Focus indicators are 2 dp. Content surfaces have no shadows.
- Settings subpage sections use rounded tiles filled with the neutral secondary surface (`surfaceVariant`); form fields use the main surface with a quiet outline so they remain distinct inside a tile.
- Preserve the application's real Home, Analytics and Settings destinations, and the review flow
  Home's two service cards open. The mockup's Profile tab is not a new feature request.
- Home has no filled primary action: the Ink source row *is* the action, and Anki's row opens the
  deck list. This deviates from the supplied Ink document's "one strong action per screen" ("Start
  studying"), which was removed on the user's call once the Anki row opened the decks itself.
- Japanese reading/display text retains native glyph fallback and readable sizes. Imported Anki card HTML keeps its own authored styling.
- Native status/gesture bars use Android insets; no mock system bars are drawn in the application.

## Review presentation refinements (21 October 2026)

- In `ReviewScreen.tsx`, the Anki question keeps its template and deck context; reveal changes the step title to **Answer** and presents known Gaten fields in a scalable 40/48 dp expression, then 17/24 dp reading and meaning. These roles use native Japanese fallback and Inter for Latin meaning text. Other/imported templates retain Anki's HTML renderer.
- Example sentences sit behind a 56 dp, accessible disclosure row and are read from the bundled Tanaka Corpus when expanded — no network at all. Up to three sentences are shown with their English translation, shortest first; a word the corpus does not carry falls back to the note's own `Sentence` field, then to a quiet "no examples" line. The reading, meaning, examples and WaniKani kanji section align to the 22 dp content grid (16 dp on narrow screens) inside the 560 dp maximum column, and the embedded WaniKani detail panel drops its own inset so its heading, sections and mnemonics share one left edge. The rating footer remains pinned above native system insets.
- `WaniKaniReview.tsx` places **Meaning** or **Reading** beside the subject kind in one compact row. The task word uses fixed semantic teal for Meaning and violet for Reading, independent of the reader's selected accent; other text and surfaces use semantic theme roles. The Japanese prompt remains the visual focus and fits on one line by shrinking from 64/80 dp for a single kanji or 40/56 dp for vocabulary to the available width.
- The English/reading instruction is centered with the field. Gaten draws the empty placeholder as centered text over an empty native input so the actual insertion point remains centered; the overlay is hidden while typing and from accessibility. Keyboard motion uses `react-native-keyboard-controller` 1.22.5 with the app's existing edge-to-edge and safe-area policy. The fixed footer, WaniKani submission flow and kana/romaji conversion remain intact.
- Text uses scalable React Native sizes, controls retain 48 dp or larger targets, longer screen content scrolls, and feedback stays below the prompt/input so it does not move the learning target.
- Visual references: [Anki answer, collapsed examples](../artifacts/visual-review/60-answer-collapsed-latest.png), [Anki answer with sourced examples and WaniKani](../artifacts/visual-review/62-answer-expanded-wk-visible.png), [WaniKani Reading fixture](../artifacts/visual-review/56-wk-reading-no-keyboard.png), [WaniKani dark at 130% text](../artifacts/visual-review/79-wk-meaning-dark-130.png), and [WaniKani compact at 160% text](../artifacts/visual-review/67-wk-reading-320-160.png). WaniKani captures use local in-memory fixtures with submission disabled; they do not represent a live queue.

## User-approved preview refinements

The user-supplied `gaten-ink-light-dark-preview.html` is the visual reference for Home and the Settings hub. Its inline outline icons and Anki/WaniKani SVG marks replace the previous in-app glyphs for those elements; the service marks use the preview's light/dark tinted holders. Keep platform status/gesture bars native and retain live app data.

- Home shows only the flame and `Daily streak` on the left; the current recorded-day value and `days recorded` stay on the right.
- Settings is the third tab, with the app's own gear glyph rather than the preview's person outline.
  The App settings row keeps its real About label and destination.
- Settings rows follow the preview's 72 dp minimum height and grow with font scale; small copy remains readable and scalable.
- Preview geometry never changes route behavior or replaces actual values with sample content.

## Implementation checks (21 October 2026)

- TypeScript compilation and Android debug build pass; eight native palette tests pass.
- Emulator inspected: Home, Settings, Analytics and review deck selection at normal scale;
  Home and Settings at 320 dp / 160% text; dark Home at 130% text.
- Larger text keeps the Home integration rows on one line — the due count stays on the trailing edge beside the chevron — until the scaled type genuinely needs the outcome below the header, and moves it to the trailing edge there too. Recent-lookup glosses move under the word and wrap in full once type grows instead of clipping.
- The full width/scale matrix, TalkBack navigation and native screen-lookup overlay have not been exercised in this pass.
