# Design language implementation

The visual foundation is [Gaten / Ink v1](Gaten_Ink_Design_Language_v1.md), provided by the user
on 21 October 2026. This implementation record and its dated updates clarify where the current
product direction has moved beyond that original specification. The supplied v1 document remains
available as design history.

## Current Settings direction (22 October 2026)

The Settings hub and all six pages it opens—Screen lookup, Text recognition, Anki cards, WaniKani,
Appearance, and About—use the same flat row language as Home, the Anki deck picker, and the WaniKani
hub. The later direction in this section supersedes the earlier Settings mockup's individual row
cards and rounded section tiles.

- Use the page background for groups and rows, with 1 dp `outlineVariant` hairline separators and
  whitespace to show grouping. Do not wrap rows or whole settings sections in cards or tinted
  surfaces. Content surfaces stay flat; the floating bottom navigation capsule is the single surface
  that carries an all-round medium shadow, so it reads as hovering above the page.
- Keep rows compact and scannable. Use a 15–17 sp title, muted secondary text only for a short label
  or essential hint, and a trailing value or control when one helps. Descriptions are optional; put
  longer explanations behind the existing help action or beside the setting that needs them.
- Keep Theme and Accent choices visible on the Appearance page as direct controls in the
  same flat layout. Their selected states remain clear without enclosing each choice group in a card.
- Keep rows and controls at least 48 dp tall, let rows grow with font scale and wrapping, and keep the
  shared 560 dp maximum content width with 22 dp horizontal insets (16 dp below 360 dp).
- Keep the existing 64 dp settings app bar and native insets. Flat presentation changes do not alter
  route behavior, setting values, or accessibility labels.

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

- Ink is the shipped Gaten palette and the only one. Theme and accent preferences remain user-selectable.
- The accent ladder is pitched for contrast: every accent's primary carries its ink (white in light, near-black in dark) and reads as text on the paper at 4.5:1 or better, held by `PaletteTest.everyAccentCarriesItsInkAndReadsOnThePaper`. Black is the colourless accent, replacing the earlier Graphite; it is black ink on light paper and white ink on dark, and the Appearance picker names it for the tone it paints in the mode on show ("White" in dark).
- Use readable secondary text (`#6C6C75` light, `#B0B0BA` dark). Fine details and status dots have separate roles.
- Compact explanatory text starts at 12 sp; the selected bottom-navigation label follows the preview's 11 sp size and scales with Android font settings.
- The reader's accent is the action colour as well as the selection colour: the selected bottom-navigation pill *and* every button are filled with `accent`/`primary` — filled buttons take the accent fill with its ink, tonal ones the accent's container tone, outlined and text ones the accent as their border and label ink. Changing the accent in Settings repaints all of them; the v1 document's neutral `--ink-active-bg` action fill is no longer used. `onPrimary`/`onPrimaryContainer` remain the inks, so the palette's own contrast guarantee still holds.
- Layout uses dp, a 22 dp content inset (16 dp below 360 dp), and a maximum 560 dp content column.
- Named corner radii: brand marks 10, setting icon tiles 11, empty-state icons 12, buttons 13, inputs 14, selected navigation 15, floating navigation/contained groups 20. The v1 Settings row radius of 17 dp belongs to the historical card preview; current Settings rows have no enclosing radius.
- Separators and visible row borders are 1 dp. Focus indicators are 2 dp. Content surfaces have no shadows.
- Settings sections use transparent groups and flat rows separated by 1 dp `outlineVariant` hairlines. Inputs and choice controls keep their own visible outlines or selected states without a card around the section.
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

## WaniKani hub and lessons (21 October 2026)

- The app-wide visual authority remains [Gaten / Ink v1](Gaten_Ink_Design_Language_v1.md). The
  current screen-specific authority for **both** provider hubs is the user-supplied
  *Gaten unified Ink hubs V4* package (21 October 2026,
  [kept in the repository](unified-ink-hubs-v4/README.md)), whose `index.html`, `DESIGN_SPEC.md` and
  `STATE_MATRIX.md` supersede the earlier hub boards and the WaniKani Ink V3 hub visuals: Anki and
  WaniKani are now one row grammar. The [Anki + WaniKani hub handoff](hubs-procedural-art/README.md)
  and [WaniKani Ink V3](wanikani-ink-v3/README.md) are retained as historical visual documentation;
  neither replaces Ink tokens or other screens. A true zero queue is distinct from studied-today
  status: a zero due count is clear on its own, while `Completed today` needs a confirmed receipt
  from an accepted grade in this session. Visual QA is specified at 100% font scale.
- The [WaniKani Ink V3 reference](wanikani-ink-v3/README.md) is retained as historical visual
  documentation. Its lesson-flow and API notes remain context, subject to current code and the V4
  hub spec.
- WaniKani's hub has exactly two rows, **Reviews** and **Lessons**, drawn with the same grammar as
  Anki's deck rows: a leading 48 dp holder (provider glyph on the neutral surface) or procedural deck
  art, the title, one subline, at most one `Label · value` extra line, then the trailing lane of count
  and caption or the green check, and the chevron. The whole row is the action, so there are no black
  Start buttons, no two-column 51 sp metric, no vertical hairline, no summary or sync line, and no
  tabs or separate “Coming up” section. `Available study` labels the list exactly as `Available decks`
  does on Anki. Rows are separated by a 1 dp hairline on a monochrome surface with no radius, at a
  90 dp (Anki) / 110 dp (WaniKani) minimum. `SuccessCheck` is the one green mark in both hubs: a
  22 dp filled disc, `#238858` on light and `#69C796` on dark, whose tick turns near-black ink in dark
  mode. A clear row keeps its neutral surface and shows the check, `All caught up`, and its clear
  detail; the earlier pale-green row wash was a prototype mistake and is not implemented. The only
  green surfaces are the optional compact `You're all caught up` notices (`#ECF8F0` on `#D8ECDC`
  light, `#18291F` on `#294734` dark), which keep both rows visible below them. Anki keeps
  `Choose a deck`, the due summary and `Available decks`; WaniKani keeps `Your study` with
  `Choose what to work on next.` (`Everything available is clear for now.` when both queues are clear).
- A fetched zero is clear on its own and never opens an empty session, and a deck reads `Clear for
  now` unless a session in it finished today, when it reads `Completed today`. Retain the other queue
  independently. Unknown data is never rendered as zero: loading draws row-shaped skeletons with no
  numbers, a cached snapshot stays marked stale, and a failed read keeps its actionable retry/connect
  row instead of a count. Omit next-review, next-batch and subject-breakdown details unless the
  existing source supports them, and keep at most one such line per row.
- See [the V4 package](unified-ink-hubs-v4/README.md) for its state matrix, design spec, prototype
  captures and procedural Anki deck-art requirements, plus the historical
  [hub handoff](hubs-procedural-art/README.md) for earlier reference images. Included prototype
  screenshots use illustrative data and are not evidence that the spec has been implemented or tested
  in the app.
- The lesson flow teaches a batch of subjects one card at a time, then quizzes them. A teach card
  carries the kind and level, the character (or the radical image), the meanings and readings with
  their mnemonics, and up to two example sentences for vocabulary. Meaning keeps the semantic teal and
  Reading the violet, as in a review. The quiz reuses the review's field, placeholder, feedback and
  pinned footer exactly; a wrong answer shows the right one and asks for it to be typed.
- A finished lesson is sent with `PUT /assignments/<id>/start` - never as a review, which WaniKani's
  reference forbids. Each subject is started the moment its quiz is passed, so leaving mid-batch keeps
  what was finished; the Lessons row then reads `N lessons left in batch` with its progress and
  resumes the batch when tapped. Starting is idempotent, so a retry is always safe.
- V3 visual references: [the local package](wanikani-ink-v3/README.md). Its included screenshots
  are captures of the HTML reference and its sample values.

### Settings and production behavior

- WaniKani Settings contains connection status, the daily new-card limit, and token controls. Keep
  it minimal; do not restore the removed overview or make read-only, token-storage, or account-change
  claims there. The daily limit is blank and unlimited by default; accept whole numbers from 1 to
  1000. Count a lesson only after its successful `PUT`, on this device and by local calendar day.
  Cap each offered batch at the remaining daily allowance. Pausing keeps unfinished work for resume,
  and the review flow is unchanged.
- Anki cards Settings stores study-deck selection by stable deck IDs. A null selection means all
  decks; an explicit empty selection means none. The selection filters the WaniKani hub and Home due
  counts. Opening Anki always shows the deck hub, including when one deck matches; starting a card
  queue requires choosing that deck. Parent decks include subdecks as in AnkiDroid. Study-deck
  selection is separate from the export destination.

## User-approved preview refinements

The user-supplied `gaten-ink-light-dark-preview.html` is the visual reference for Home and the Settings hub. Its inline outline icons and Anki/WaniKani SVG marks replace the previous in-app glyphs for those elements; the service marks use the preview's light/dark tinted holders. Keep platform status/gesture bars native and retain live app data.

- Home shows only the flame and `Daily streak` on the left; the current recorded-day value and `days recorded` stay on the right.
- Settings is the third tab, with the app's own gear glyph rather than the preview's person outline.
  The App settings row keeps its real About label and destination.
- The 72 dp card rows in this preview are historical. Current Settings uses flat rows with flexible height, at least 48 dp touch targets, and scalable text.
- Preview geometry never changes route behavior or replaces actual values with sample content.

## Earlier implementation checks (21 October 2026; before V3 settings and Anki study-deck changes)

These checks describe the earlier implementation checkpoint only; they do not verify the later
settings and Anki study-deck changes above.

- TypeScript compilation and Android debug build pass; the native unit suite (including the lesson
  decode, queue ordering, entitlement filter and `PUT …/start` tests) passes.
- Emulator inspected: Home, Settings, Analytics and review deck selection at normal scale;
  Home and Settings at 320 dp / 160% text; dark Home at 130% text; the WaniKani hub in the ready,
  caught-up, cached/offline, sync-issue, restricted, in-progress and loading states, light and dark,
  at 100 %, 130 % text and 320 dp; the live lesson teach and quiz screens against the real account,
  with no review or lesson write made.
- Larger text keeps the Home integration rows on one line — the due count stays on the trailing edge beside the chevron — until the scaled type genuinely needs the outcome below the header, and moves it to the trailing edge there too. Recent-lookup glosses move under the word and wrap in full once type grows instead of clipping.
- The full width/scale matrix, TalkBack navigation and native screen-lookup overlay have not been exercised in this pass.
