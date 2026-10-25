# Gaten / Ink — Design Language

**Version 1.6 · 23 October 2026 · Current Home, Analytics, Settings and Screen lookup baseline**

## Summary

| Area | Current rule |
| --- | --- |
| Visual language | Quiet, mostly monochrome pages; flat rows, whitespace and 1 dp separators establish hierarchy. |
| Home | Gaten heading → search → Daily streak → Anki and WaniKani rows → Recently looked up. The service rows are the study actions; there is no separate Start studying button. |
| Analytics | One page, no source switcher: Anki → WaniKani → Study activity → Accuracy → Level progress, with no cards at all — hairline-separated sections, paired figures split by a single vertical rule, unboxed charts. |
| Settings | Six destinations in three flat groups: Lookup, Study and App. No row cards or tinted section containers. |
| Color | System/Light/Dark modes and seven selectable accents. The chosen accent colors actions, selection, Home due counts and streak marks — and, on Analytics, every chart and every ring, in accent-intensity ladders rather than extra hues. No success green, brand pink or brand blue appears in an analytical element. |
| Navigation | Three bottom tabs: Home, Analytics and Settings. Review is a pushed flow, not a fourth tab. |
| Screen lookup | Over foreign content: an animated screen-edge outline, a 40 % black freeze veil, a two-pass selection mark, a rounded neutral status rail with an accent gesture glyph, and a flat result sheet that expands into full-screen search. Its query control matches Home and searches as the reader types. |
| Exceptions | Neutral input fills, brand-mark holders, dialogs and approved sparse empty-state artwork have specific treatments below. |
| Source of truth | This document consolidates the current Home/Analytics/Settings implementation and shared tokens. Historical previews do not override it. |
| Audit scope | Checked against source code on 22 October 2026, and against a debug build on an Android 14 emulator at 392 dp in all seven accents, light and dark, plus 320/340/430 dp and 130/160 % text. |

The filename retains `v1` to preserve existing links. Version 1.1 replaces the conflicting v1.0
Home, Settings, palette, navigation and motion guidance in this file. The supplied original
[Ink document inside the V4 package](unified-ink-hubs-v4/docs/Gaten_Ink_Design_Language_v1.md)
remains a historical reference; it is not a second current specification.

**North star:** a quiet study instrument. Japanese lookup and study remain the focus.
This is a reusable design guideline, with current screen behavior stated explicitly rather than
sample counts or mockup-only controls.

## 1. Principles and scope

- **Content before containers.** Default to rows and rules. Home service rows and Settings
  sections have no enclosing card, rounded section background or drop shadow.
- **One clear hierarchy.** Use confident page headings, compact row titles, readable secondary
  text and a trailing value or action. Do not repeat an explanation the title/value already provides.
- **Neutral foundation, selected accent.** The paper and ink stay neutral. Use the reader's accent
  for meaningful action and state; do not hardcode violet as the universal highlight.
- **Direct actions.** The whole Home service row opens its destination. Anki opens the deck hub
  even when only one deck is selected; WaniKani opens its study/connection flow.
- **Honest state.** Loading, disconnected, permission-needed, failed, offline and fetched-zero
  states have different meanings. Never invent a due count or completion receipt.
- **Purposeful artwork.** Keep approved service marks and sparse empty/search artwork. Do not
  add ambient animation, mascot decoration, gradients, glow or brand-colored page washes.
- **Native behavior.** Use real insets, keyboard behavior, scalable text and current routes.
  A prototype device frame, status bar, gesture indicator or sample data is never production UI.

The current [unified Ink hubs V4](unified-ink-hubs-v4/README.md) remains the screen-specific
reference for the Anki deck hub and WaniKani study hub. Its exceptions do not change Home or
Settings geometry. The [analytics content proposal](analytics/CONTENT_PLAN.md) is a planning
document, not an implemented Analytics redesign.

## 2. Color system

Use semantic theme roles from the shared theme. Hex values below record the current code;
they are not permission to create separate palettes in individual screens.

### 2.1 Neutral and status roles

| Role | Light | Dark | Use |
| --- | --- | --- | --- |
| `background` | #FDFDFD | #101114 | Page canvas |
| `surface` | #FFFFFF | #17181C | Input/dialog/artwork surface where specified |
| `surfaceVariant` | #F6F6F8 | #1B1C21 | Search fill and quiet control surfaces |
| `onSurface` | #17171A | #F5F5F6 | Primary text |
| `onSurfaceVariant` | #6C6C75 | #A7A7B0 | Secondary and explanatory text |
| `outline / outlineVariant` | #E9E9ED | #303136 | 1 dp row rules and understated borders |
| `bottom` | #F5F5F6 | #1C1D21 | Floating navigation track |
| `fine` | #A1A1AB | #777781 | Decorative details and inactive bottom-tab glyphs |
| `iconPrimary` | #000000 | #F5F5F6 | Strong icons |
| `iconSecondary` | #83838C | #909099 | Secondary glyphs, not small body text |
| `success` | #16A476 | #53C99B | Connected status |
| `warning` | #DC6673 | #EF8A96 | Attention/status markers |
| `error` | #B42335 | #EF9A9A | Error text and feedback |
| `errorContainer / onErrorContainer` | #FFEBEE / #B71C1C | #B71C1C / #FFEBEE | Bounded error feedback when needed |

Container ladder: in light mode `surfaceContainer`, `surfaceContainerHigh` and
`surfaceContainerHighest` are #F6F6F8. In dark mode they are #1B1C21, #1C1D21 and #303136.
These roles do not imply that Settings groups should acquire background fills.

The older #83838C body-text baseline, #909099 dark body-text baseline and the implementation
record's former #B0B0BA dark secondary text are superseded for these screens. Use
`onSurfaceVariant` for copy; keep `fine` and secondary-icon tones in their own roles.
Contrast targets remain at least 4.5:1 for normal text and 3:1 for large text; this source audit
does not claim a new contrast test run.

### 2.2 Selectable accents

The stored default is **Indigo**. Theme choices are **System**, **Light**, and **Dark**.
Appearance offers the following seven accents in this order:

| Accent | Light primary | Dark primary | Light container | Dark container |
| --- | --- | --- | --- | --- |
| Indigo | #4338CA | #A5B4FC | #EEF2FF | #312E81 |
| Blue | #0B4FB8 | #93C5FD | #EFF6FF | #1E3A8A |
| Violet | #6D28D9 | #C4B5FD | #F5F3FF | #4C1D95 |
| Teal | #115E59 | #5EEAD4 | #F0FDFA | #134E4A |
| Green | #166534 | #6EE7B7 | #F0FDF4 | #14532D |
| Rose | #9F1239 | #FDA4AF | #FFF1F2 | #881337 |
| Black | #000000 | #FFFFFF | #EAEAEA | #404040 |

`onPrimary` is #FFFFFF in light mode and #111111 in dark mode. Always pair container fills
with the resolved `onPrimaryContainer`, not an assumed white label. The colorless stored
choice remains `Black`; Appearance calls it **White** when showing dark mode.

Action aliases share the accent: `accent / actionPrimary = primary`,
`onActionPrimary = onPrimary`, `accentSubtle / actionSecondary = primaryContainer`,
and `onActionSecondary = onPrimaryContainer`. Filled, tonal, outlined and text buttons use
these roles. The selected navigation pill also uses `primary / onPrimary`. Neutral black
action fills from v1.0 are no longer the default.

### 2.3 Brand and task exceptions

- Home Anki holder: #EAF4FF light / #182D40 dark.
- Home WaniKani holder: #FFF0F2 light / #38212A dark.
- Brand color stays inside those holders and supplied marks; adjacent row text/surfaces remain neutral.
- Home due numbers, streak flame, completed-day fills, today's accent marker and See all action
  follow the selected accent.
- Review task colors remain independent: Meaning #2B7178 light / #8FC5CA dark;
  Reading #6D28D9 light / #B0A0FF dark. These are task semantics, not the user's accent.

## 3. Typography

Inter is bundled and named explicitly by the shared Latin text roles. Japanese uses platform
glyph fallback. A display role without an explicit font family retains native fallback.
Do not infer current UI typography from a historical preview or an attribution-row title.

| Shared role | Size / line height | Weight | Letter spacing |
| --- | --- | --- | --- |
| Brand heading | 35 / 38 | 700 | -1.85 |
| Page heading | 35 / 44 | 700 | -1.85 |
| Section heading / settings app-bar title | 17 / 22 | 700 | -0.57 |
| Row title | 15 / 20 | 700 | -0.45 |
| Button label | 15 / 20 | 600 | -0.20 |
| Body and input text | 14 / 20 | 400 | 0 |
| Description | 13 / 19 | 400 | 0 |
| Small body | 12 / 17 | 400 | 0 |
| Field/value label | 12 / 18 | 600 | 0 |
| Small label | 12 / 16 | 500 | 0 |
| Selected navigation label | 11 / 14 | 600 | 0 |
| Shared metric | 25 / 28 | 700 | -1.20 |
| Japanese display medium | 40 / 48 | 500 | 0 |
| Display small | 34 / 44 | 500 | 0 |

Home-specific overrides are intentional: the due count is 24 / 24, weight 700, tracking -0.9;
the streak count is 20 / 22, weight 500; the empty-history caption is 13 / 18, weight 700.
Weekday labels use the 12 / 18 field/value role, not the original 10 px mockup labels.

Numbers are React Native font sizes that scale with the system. Use readable line heights
and flexible row height; do not port arbitrary variable-font weights such as 745 from HTML.

## 4. Layout, spacing, shapes and elevation

A centered single column is capped at **560 dp including its horizontal padding**.
Home and Settings use **22 dp** horizontal insets, reduced to **16 dp below 360 dp**.
Native safe-area/status/gesture insets are additional responsibilities of each screen shell.

| Spacing token | Value |
| --- | --- |
| Micro gap | 4 dp |
| Small | 8 dp |
| Medium | 12 dp |
| Large | 16 dp |
| Compact section | 20 dp |
| Content inset | 22 dp |
| Section | 24 dp |
| Large section | 32 dp |

Screen-specific rhythm may use 11, 20, 22, 23 or 34 dp where recorded below; do not replace
the current composition with uniform card padding.

| Element | Geometry / treatment |
| --- | --- |
| Home search | Minimum 52 dp, radius 14 dp, neutral fill |
| Home service row | Minimum 69 dp, square outer shape, bottom rule |
| Home service-mark holder | 37 × 37 dp, radius 10 dp; mark 28 dp |
| Settings destination/switch row | Minimum 60 dp, 12 dp vertical padding, 12 dp content gaps |
| Settings text field | Minimum 49 dp, radius 14 dp, 1 dp outline; 16 dp horizontal / 12 dp vertical padding |
| Filled/tonal/outlined button | Minimum 52 dp, radius 13 dp |
| Text/icon action | Minimum 48 dp effective target |
| Dialog | Radius 20 dp, padding 24 dp, width capped at 360 dp |
| Floating bottom bar | Height 63 dp before native bottom inset, radius 20 dp |
| Selected bottom pill | Visual height 46 dp, radius 15 dp; touch region at least 48 dp |
| Rule / focus | 1 dp separator; 2 dp accent focus outline |

Existing `shape.settings = 17` and `shape.settingsIcon = 11` tokens do not authorize
rounded Settings row cards or icon tiles; the current hub uses bare glyphs.

Content surfaces stay flat. The bottom capsule is the sole raised in-page surface:
elevation 8, black shadow with opacity 0.18, radius 16 and offset (0, 0), as declared in code.
The centered shadow is part of the navigation treatment, not a reusable card shadow.

## 5. Home

### 5.1 Composition and search

Resting order: **Gaten → search → Daily streak → Anki → WaniKani → Recently looked up**.
There is no separate filled study CTA and no optional split-card variant in the current baseline.

The header has 12 dp top/bottom padding; the brand adds 11 dp above and 20 dp below.
Search uses a minimum 52 dp neutral shell, 14 dp radius, 15 dp horizontal padding,
13 dp icon/input gap, and placeholder **猫, ねこ, neko...**. Its 2 dp border is transparent
at rest and accent-colored on focus. Focus starts through the reader's action.

Focusing search switches Home into search mode: hide the brand/dashboard and bottom bar,
retain the search field, and show a 48 dp circular outlined Cancel control.
When the query is empty, show recent search terms or the supplied example chips and sparse
search artwork. Typed results replace the prompt. Loading, unsupported input, failure and
no matches have their own explanatory states.

Cancel or Android Back exits search mode, clears the query and restores the dashboard.
Word detail is pushed onto the existing stack. Keep one result/body scroller below the
header; do not nest a scrolling dashboard inside a scrolling results list.

### 5.2 Daily streak

Use an unfilled section with a 1 dp bottom rule, 24 dp top margin and 22 dp bottom padding.
A 24 × 28 dp accent flame and **Daily streak** sit left; the number and **days** sit right.
There is no supporting slogan or tinted streak card.

Seven evenly spaced Monday–Sunday markers sit 20 dp below the header.
Markers are 29 dp with an 8 dp gap to weekday labels:

| State | Current treatment |
| --- | --- |
| Studied | Accent-filled circle and contrasting check |
| Today, not yet studied | Accent outline and center dot |
| Missed after earlier recorded activity | Muted outline and short dash |
| Empty / upcoming | Quiet outline and center dot |
| Unknown | Quiet empty visual; accessibility explicitly says activity is unknown |

This is based on **Anki's last seven study-day searches**, not a combined Anki/WaniKani or
lifetime streak. The streak can continue from yesterday before today's first study.
It is bounded by the available seven days; do not claim the displayed number is a lifetime maximum.
The current numeric fallback is 0 when data is unavailable; this is an existing presentation
limitation, not evidence of zero study. The weekday accessibility labels retain unknown status.
Any future lifetime or combined streak needs its own data decision.

### 5.3 Service rows

Each row is one action: leading tinted service-mark holder, title and connection status,
then a trailing due count/caption or recovery action, plus chevron when actionable.
No row fill, outer radius, duplicate watermark, status pill or separate Start button.

Use the existing SVG marks from `HomeArtwork.tsx`. Connection text has a 6 dp dot:
success for connected, neutral for disconnected/loading, attention color for failure.
Error text uses the error role. The count uses the selected accent.

| Situation | What the row communicates |
| --- | --- |
| Connected | Due count when known; Anki can say remaining after activity today |
| Loading | Checking/loading copy; no fabricated count |
| Missing setup / permission | Open Anki, Grant access, Connect or Reconnect, according to the source |
| Read failure | Explicit failure and retry action; any retained count carries retry/failure context |
| WaniKani offline | Internet required and offline glyph; suppress the cached due count |
| Confirmed zero | Show the genuine zero; do not invent a completed-session receipt |

Home Anki due counts respect selected study decks and avoid adding a selected parent and
its children twice. The streak still uses collection-wide Anki activity; it is not scoped
by that deck selection. WaniKani does not supply a completed-today count here.

The trailing lane is capped at 132 dp normally. When `width / fontScale < 270`, move
the outcome below the title area, still aligned to the trailing edge with its chevron.
This is a responsive row, not a two-column tile layout.

### 5.4 Recently looked up

Start this section 34 dp after the services. Use the 17 / 22 heading and show the first
three recent entries. More entries expose **See all**, then **Show less**, with an accessible
expanded state. Recent lookups are opened entries; search-mode recent terms are a separate list.

Rows have a 58 dp minimum height, 12 dp horizontal padding, a headword/reading block and
a trailing gloss, with 1 dp separators between entries. At normal scale the gloss occupies
at most 34% of the row and up to two lines. Above 1.2 font scale it moves under the word,
aligns left and wraps without a fixed line limit.

The current empty state is a centered, theme-aware **history tray and clock** SVG,
up to 340 × 148 dp, followed by **No recent searches found**. This replaces the older
39 dp icon and two-line horizontal empty-state copy. Keep this approved exception sparse;
do not substitute a mascot or colored feature panel.

Loading and failure use compact icon/copy rows; failure provides Try again.
Existing entries remain visible if only their refresh fails.

## 6. Analytics

Analytics is the design's most literal screen: one page, no source switcher, and **every analytical
card removed**. There is no card, panel, tinted tile, raised chart or drop shadow anywhere in its
content. Whitespace, 1 dp hairlines and type size are the whole hierarchy, and the section-by-section
geometry, colour roles and states are recorded in
[analytics/PLAN.md](analytics/PLAN.md#layout).

### 6.1 Composition

- **Header** matches Home: `Analytics` at 35 sp/700, the freshness line 13 sp 10 dp beneath
  (`Updated now`, `Updated 4m ago`, `Updated on 21 Oct`), and one 43 dp refresh square (radius 14 dp,
  `surfaceVariant` fill, accent glyph, ≥48 dp target) — the only chip on the page.
- **Five sections, in this order:** `Anki` (the collection's shape), `WaniKani` (the curriculum's
  shape), `Study activity` (a twelve-week Anki heatmap), `Accuracy` (cumulative accuracy and its
  per-kind figures as three columns), `Level progress` (the level the reader is
  on, with its elapsed time and the recent level durations). The two source summaries lead — they are
  what the page is
  opened for — and the long-history chart follows them. Each names its source and scope in its own
  meta line; there is no `All / Anki / WaniKani` switcher.
- **Sections** are separated by a 1 dp `outlineVariant` rule with 24 dp above and 22 dp of padding
  below it; the first section has no rule and starts 22 dp under the header. Section titles are
  15 sp/700 with an 11–12 sp right-aligned meta line capped at 44 % of the width.
- **Paired figures** sit side by side split by exactly one vertical 1 dp rule, 18 dp of inset on the
  right figure and 12 dp on the left. There is no outer boundary of any kind.
- **Charts** are unboxed: a 12 × 7 heatmap of 17 dp cells with only `M`/`W`/`F` row labels, 84 dp
  rings with a 9 dp stroke, 9 dp distribution bars, two labelled accuracy tracks, and the level
  durations as rows. Nothing on this screen animates.
- **Copy is a label, not an explanation.** The meta line carries the scope (`12 weeks · Anki`,
  `Collection`, `Progress`); a note under the numbers is one short line and exists only to
  stop a misreading — a unit (`Distinct cards, not button presses.`), a definition (`Young: under 21
  days.`) or a gap (`Some days unknown.`). Reasoning longer than that belongs in
  [analytics/PLAN.md](analytics/PLAN.md), not on the page.

### 6.2 Colour

Analytics is near-monochrome: the shared neutral roles, plus the reader's selected accent at a few
strengths. **No `success` green, no brand pink and no brand blue appears in an analytical element** —
a green accent chosen by the reader flows through like any other, but the page never invents an
independent colour language of its own. The rings, the accuracy tracks, the bars, the text actions and
the heatmap's levels are all the one accent; the heatmap and the two distribution bars are that accent
at 20/42/67/100 %, and the five SRS stages at 20/40/60/80/100 %.

The ladders are what keep the charts legible for the colourless accent, where hue roles would have
collapsed into one another. The per-accent `primary` values and the full element-to-role table for
both modes are in [analytics/PLAN.md](analytics/PLAN.md#colour-by-element-and-mode).

### 6.3 Honesty rules

- A figure the source did not answer is an em dash with a sentence, never a zero.
- A heatmap day the source would not answer is an outlined cell, not a filled one; a confirmed zero is
  filled in the hairline tone, so "nothing studied" and "not read" never look the same.
- A chart bucket the source would not answer draws no bar at all; a counted zero draws a 2 dp stub.
- A source that is not set up is a rule-fenced line of prose with the one action that fixes it, in its
  own place — it never covers the other source's numbers, and the action appears once per source, not
  once per section.
- Cumulative accuracy carries no date range, because the source has no answer history to scope it to.
- A cached figure is marked once at the foot of the page; a genuine zero is drawn as a zero.

## 7. Settings

### 7.1 Hub

Use the same page canvas and 560 dp column as Home, with native top inset and a
**Settings** page heading. The heading begins 23 dp below the inset; each group starts
22 dp after the preceding element.

| Group | Destination | Trailing live value |
| --- | --- | --- |
| Lookup | Screen lookup | On / Off |
| Lookup | Text recognition | Installed model label / None |
| Study | Anki cards | Export deck, Choose deck, Not installed or Permission needed |
| Study | WaniKani | Connected / Not connected |
| App | Appearance | System / Light / Dark |
| App | About | Actual app version |

There is no separate Screen access row, Account/Profile destination, or fixed sample Gaten
deck value. The Anki hub summary names the export deck; it does not summarize selected study decks.

Each destination is a flat minimum-60 dp row with a bare 22 dp Ink outline icon, 15 / 20
title, optional 12 / 17 explanation, trailing 12 / 18 value and 15 dp chevron.
The value is right-aligned, capped at 40% width, and allowed to wrap. Whole rows are tappable.
Groups use sentence-case 12 / 18 labels, an 8 dp heading gap and 1 dp row-bottom rules.

Do not place hub rows or groups in cards, tinted sections, icon tiles or separate rounded surfaces.
The hub normally needs no description below each title. Values refresh on arrival and on
foreground return while the hub is focused.

### 7.2 Shared detail-page grammar

All six pages keep the flat section language. Use a **64 dp app bar below the top safe inset**,
Back control, 17 / 22 page title and Help action where provided.
The body scrolls in the shared column, with bottom safe-area padding. The bottom tab bar is
hidden because these are pushed pages.

Settings opened from an in-app destination stay on that destination's stack; Back returns
there. Only an external settings launch selects the Settings tab.

Short labels and essential hints belong beside their control. Longer explanations belong
in the existing Help dialog or the permission disclosure that needs them.
Inputs, selected swatches and radio indicators can have their own fill/outline; their
surrounding section remains transparent.

Use the shared button variants. Keep disabled, saving, invalid and retry states visible.
Do not add a new save pattern to pages that apply immediately or remove an existing Save
from pages with a draft.

### 7.3 Page-specific content and controls

| Page | Current structure and interaction |
| --- | --- |
| Screen lookup | Lookup mode, Floating button, Quick Settings tile and shortcut/key mapping; During lookup includes the media-pause permission/control. Preserve the explicit permission disclosure before native permission prompts. |
| Text recognition | Current model and available model, download/import/remove actions, progress and cancel, installation/error states, and Help. Download confirmation is a dialog; progress stays in the flat form. |
| Anki cards | AnkiDroid access/refresh, Study decks, Export deck/tags, and Card layout with note type, field mappings and HTML/CSS controls. Toolbar Save commits the draft; leaving dirty state offers Discard / Keep editing. Study-deck selection is independent of export destination. |
| WaniKani | Connection status, Lessons → New per day, and token controls. Blank means Unlimited; accepted daily limits are 1–1000. Learned today explicitly says This device. One filled **Save** at the foot of the page commits both drafts: it writes the lesson limit, and checks and stores a token only when the field holds one, so a blank field saves the limit without touching a token that is already saved. Disconnect stays a separate, explicit text action. |
| Appearance | Theme radio rows, visible Accent swatches, and Feedback. Theme/accent apply immediately. Feedback shows Haptics → System and a Sounds switch with the short Anki and review cues description. |
| About | App/version, Privacy, Attributions and Licence. Use the same flat rows for links, with essential legal explanations allowed to wrap. |

Anki study decks are stored by stable IDs: all decks and an explicit empty selection differ.
Parent decks include subdecks. Do not confuse these choices with the export-deck field.
Opening Anki still leads to the deck hub before starting a queue.

Appearance Theme rows have a 56 dp minimum and a trailing 22 dp radio ring with a 10 dp
selected center. The accent grid wraps in four columns, then three, with 8 dp gaps.
Each swatch has a 48 dp high touch region, a 40 dp circular visual and a 2 dp selection
ring; its label uses 12 / 18 centered text. Swatches resolve in the chosen display mode.
Selection must be conveyed semantically as well as visually.

### 7.4 Dialogs and fields

- Shared fields use 14 / 20 text, a surface fill, 1 dp neutral outline and radius 14 dp.
  Focus adds a 2 dp accent outline with 2 dp offset.
- Switch rows use the same 60 dp minimum and flexible copy block. On-state tracks use the
  accent with paired foreground; disabled state uses the theme's disabled roles.
- Shared dialogs center a surface with 20 dp corners and 24 dp padding over a 50% black
  backdrop. Cap width at 360 dp; long help content scrolls, with actions remaining reachable.
- Keep permission disclosure explicit, including its acknowledgement where required.
  Concise hub copy must not conceal permission consequences.
- Anki selectors and editors remain their real controls, not decorative sample menus.

## 8. Screen lookup

The lookup workflow is the one part of Gaten drawn *over* somebody else's content: an accessibility
overlay window, a frozen screenshot, a finger-drawn selection, and a reading surface. It carries the
same paper, ink, accent and type scale as the app; what it adds is a small set of overlay-specific
recipes (§8.10) that exist only because the pixels underneath are not ours. The exhaustive tables —
every value, state, string and mockup — are in
[the lookup design specification](../artifacts/lookup-redesign/DESIGN.md).

### 8.1 Surfaces and states

Four surfaces, one instrument: the **floating button** (the entry point on foreign screens), the
**freeze scrim with the selection mark**, the **status rail** (what the tool is doing), and the
**result sheet** (what it found). The states are selecting → reading the screen → reading the
selection → result, plus the terminal no-text, no-words and dictionary-unavailable states and the
non-destructive tap miss.

- Resolve the reader's theme and accent exactly as the app does (`ThemePreferences` → `accentRoles`
  → `GatenTheme`); never infer the overlay's theme from the brightness of the screenshot.
- Opaque surfaces are alpha 1.00, elevation 0, no shadow, blur, glow or gradient. The restored
  screen-edge outline retains its original drifting accent gradient.
- The capture is never animated, tinted, moved or scaled. A missing region stays opaque black and
  never reveals the live app.

### 8.2 Freeze and arming

- The first visible frame is the opaque frozen capture alone. Keep the controller's
  frame-commit → additional vsync → media-pause → UI ordering and its safety timeouts; no visual
  animation may delay capture, media pause or OCR.
- The dim is fixed black at **0.40** in both modes, fading 0 → 0.40 over `SHORT` on `emphasized`. It
  is context separation, not a contrast guarantee for whatever is underneath.
- Restore the **screen-edge outline**: two strokes reveal from the bottom midpoint, travel up the
  sides and meet at the top. It follows the display corners, drifts while reading, and retracts
  when the result appears. It draws above the selection without receiving touches or delaying OCR.
- **The lookup ends when the reader leaves.** A window-focus loss still closes it after the 200 ms
  bounce, with the media left paused for whatever took the screen; but an overlay window above every
  app keeps that focus when the launcher or another app comes forward, so the same case is also read
  from the foreground app: while the frozen frame is up, a **full-screen window from a package that
  is neither ours nor the app the capture froze** ends the lookup, without resuming media. The app
  beneath is excluded because its own window events are the screen already frozen, and the active
  **input method is excluded by name** because a keyboard reports itself as full screen and arrives
  exactly when the reader starts typing. Leaving the frozen frame on top of whatever the reader
  switched to is the failure this prevents.

### 8.3 The selection mark

- The lasso is two solid strokes on one smoothed path: **5 dp `onPrimary` outside, 3 dp `primary`
  inside**, both alpha 1.00, round cap and round join, with the width independent of how far the
  finger travelled. A single sample draws the same pair as concentric disks. No bloom, no gradient,
  no dark casing: the pair *is* the contrast strategy — on a white page the deep member reads, on a
  dark frame the light one does, and both are always present as one hard-edged mark.
- The hole is the sampled (or tapped) bounding rectangle with a **4 dp** corner radius, clamped to
  half its smaller side. Outside it the dim stays 0.40; inside it the frozen pixels are untouched.
- Edge while drawing and while the crop is read: **4 dp `onPrimary` + 2 dp `primary`**. With a result
  up: **3 dp `onPrimary` + 1 dp `primary`**. Weight, never transparency, makes the source secondary.
- The mark is a source marker, not a card: no connector, arrow, thumbnail or accent line ties it to
  the sheet, and selecting another word in the sheet does not pretend a narrower source is known.
- The 24 view-px minimum selection and the 24 dp tap tolerance are thresholds, not a gesture model:
  the lasso and the tap both stay available, and a miss never dismisses.

### 8.4 The status rail

One rounded, neutral status rail carries instructions, real progress and notices; empty lookup
results have a different, centred body treatment. Floating over the frozen screen, the rail is opaque
`surface` at alpha 1.00, with **13 dp corners**, a **1 dp `outlineVariant` perimeter**, 12 dp content
padding on every side and a 48 dp minimum height. It has **no accent leading rule and no decorative
substitute for one**. It is content-sized up to 360 dp, with 16 dp safe side margins and a position
16 dp below the real top insets. Inline notices use the same opaque fill, 13 dp corners, 1 dp neutral
perimeter and padding, at full content-column width with 16 dp of air above and below. Neither
placement has elevation, shadow, gradient, glow or screenshot-dependent colour sampling.

The opaque paper, neutral hairline and readable ink identify the rail over foreign content; the
existing 40 % black veil separates context but is not a universal contrast guarantee. Text is
`bodyMedium` in `onSurface`; a failure title and its 18 dp glyph use `error`, with an optional
`bodySmall` cause in `onSurfaceVariant` 4 dp below. A real foreground task uses a 16 dp, 2 dp-stroke
`primary` spinner with an 8 dp text gap. The 18 dp **gesture glyph uses `primary`**; other neutral
outline glyphs use `onSurfaceVariant`. An inline
action sits 8 dp below the message, uses `labelLarge` in `primary`, and has a wrapping 48 dp-minimum
target. Enter, exit and text replacement are opacity only over `SHORT` on `emphasized`; relayout
height immediately and announce each changed status once, politely.

Index loading says **Reading screen…**; readiness says **Circle or tap Japanese text** for three
seconds (at least 2000 ms, extended by the accessibility timeout, and hidden immediately on drawing);
the message that follows one in the same sequence stays two seconds. A committed crop says **Reading
selection…** until usable content or a terminal result. A tap miss says **No text here** and then the
applicable instruction without dismissing. An unavailable index says **Circle Japanese text**, never
a false confirmed zero. A confirmed zero-line index while idle opens the **No text found** sheet
without fabricating a selection; it never interrupts an active gesture, crop, result or search.
Selecting again returns to selection without reopening that same zero-index notice.

**No text found** and **No dictionary matches** use sparse neutral SVG artwork and a secondary caption
centred in the bounded modal body, with **Search dictionary** as the primary recovery and **Select
again** as the secondary action. They persist until the reader acts, reflow and scroll at large type,
and have no word tabs or Anki footer. Recognized source is preserved for the no-matches state.
**Dictionary unavailable** is a persistent inline error rail with its real readable cause, not an
illustrated empty state. No terminal message is duplicated in a floating rail. The exact artwork,
centring rules and search behaviour are specified in
[DESIGN-REV2.md](../artifacts/lookup-redesign/DESIGN-REV2.md) §§2–4.

### 8.5 The result sheet

Keep the **20 dp** dock corner and the **60 % / 45 %** dock fractions — this is a window that
genuinely contains the reading task, not an in-page row card — on `surface` at alpha 1.00, elevation
0, with one **1 dp `outline` rule on the exposed boundary only** (top for the bottom dock, the
inner/free edge for a side dock). Content inset is the house 22 dp — `16 dp` below 360 dp of content
width, and on a side dock (a strip down the edge rather than the width of the screen) — and the
column caps at 560 dp.

Inside, there is no intermediate filled card: **grip → header → one body scroller → pinned footer**.

- **Grip**: 48 dp band, a 32 × 4 dp handle at radius 2 dp in `onSurfaceVariant` 0.60, fixed width
  while dragging, carrying the accessible dismiss action.
- **Header**: transparent; the source line is `bodyMedium` `onSurfaceVariant` with the complete string
  for Copy and for TalkBack; a responsive cluster of **four `48 dp` controls** — Copy text → Search
  dictionary → Select again → Close lookup — with **20 dp** neutral glyphs and no backing; one 1 dp
  rule below. Physical width decides where they live, in this order: the source keeps the row only
  while `availableWidth / fontScale ≥ 300 dp` **and** 96 dp remains after the controls; below 192 dp
  Copy moves into the body as a text action after the reflowed source (omitted when the source is
  blank); below 144 dp Select again joins it (except where the body already offers it — an empty state
  or a failure rail); only below 96 dp does Search also become a labelled body action, leaving Close
  alone. Targets are never shrunk to keep icon density, and a blank source keeps its disabled Copy
  slot only where the four slots fit.
- **Word tabs** replace the old filled chips: transparent, 0 dp radius, selected = `primary` text +
  a **2 dp `primary` bottom rule** + a check in a reserved slot, unselected `onSurfaceVariant`. They
  live in the body scroller and are omitted when only one word can be selected.
- **Entry**: headword `displaySmall` in **`onSurface`** (never accent — the word is content), reading
  `titleMedium` `onSurfaceVariant`, metadata as plain text (`Common · {short POS}`), optional
  `Also written:` / `From {}` lines in `bodySmall`, then a 1 dp rule and a **Meaning** caption with
  numbered glosses in `bodyLarge` and **neutral** numbering, then the full part-of-speech line, then
  the kanji strip as **bare `primary` glyph actions** (no tiles, borders or radii).
- **Footer**: a tappable deck row with a change glyph plus one full-width **52 dp / 13 dp radius** accent action above the bottom
  inset, with 24 dp breathing room below the action and a 1 dp rule above the footer. Completion labels stay honest (`Added`, `Already in Anki`).
  The footer appears only while AnkiDroid is connected, and only an explicit activation ever writes to Anki.
- **WaniKani reference pages** are another depth in the same sheet — same grip, header and selector,
  one body scroller, no second sheet, no Anki footer, neutral section labels, flat skeletons.

**Search within lookup.** **Search dictionary** is a 48 dp neutral search action in the sheet's
top-right control cluster, ordered Copy → Search → Select again → Close. Source text reflows before
controls shrink; Copy, then Select again, relocate into the body when physical width requires it.
Search remains beside Close unless even two 48 dp targets cannot fit, when its labelled body action
remains available. Empty states also offer the prominent **Search dictionary** recovery.

Search **expands the result modal to full screen inside the same accessibility overlay**, starting
from the actual bottom or side-dock bounds. The search field appears smoothly during the transition;
the keyboard waits until expansion settles. Back reverses the transition to the saved sheet;
**Close lookup** ends the overlay. The field matches Home’s compact search control: **52 dp minimum,
14 dp corners, neutral `surfaceVariant` fill, inset search glyph and a 2 dp accent border on focus**.
There is no visible **Dictionary query** heading; the field keeps its accessible name. The first
query starts blank. Typing searches automatically after the same **250 ms** debounce as Home,
including ordinary edits and IME composing text; the keyboard Search action searches immediately.
The idle prompt offers the same example words or saved recent searches; tapping a chip searches
immediately. Submit and opening a result remember the query, while typing alone does not.
Japanese and romaji are both accepted, and the existing offline `DictionarySearch` helper and the
already-open repository are used verbatim. Result cards and the prefix section follow Home’s
search layout, preserving that helper’s bounded order and exposing their exact,
matched-form, approximate-reading or prefix provenance — never a distinction the result data do not
provide.

Picking a row returns to the original sheet with that chosen entry and an explicit Anki footer. OCR
word tabs are hidden while a searched entry is shown, the committed capture and original source are
preserved, the typed provenance is labelled **Searched**, and **Original lookup** restores the
captured result. Cancel restores the saved reference page; choosing clears its old WaniKani stack. No
search operation or row selection writes to Anki — only a fresh explicit footer activation may commit
the existing write/share route.

The full-size opaque window does not resize for the keyboard. The cover composes its content against
`WindowInsets.ime`, keeping Back/Close and the query reachable and reducing the results viewport; when
the height cannot hold both, the control bar stays and query plus results become one scroller. The
frozen capture is never resized or shifted. A positively attributed handoff to the active editor's own
IME is not a focus-loss dismissal, while another app or unrelated window taking focus still uses the
existing 200 ms bounce and automatic-dismiss policy, keyboard visible or not. System Back hides the
keyboard first, leaves search second, and then follows the existing sheet/reference/overlay order.
Full interaction and accessibility rules are in
[DESIGN-REV2.md](../artifacts/lookup-redesign/DESIGN-REV2.md) §4.

### 8.6 The floating button

48 dp, **opaque `primary` fill**, a 24 dp glyph in `onPrimary`, and a hard **2 dp `onPrimary`
perimeter** drawn inside the circle; no shadow, gradient or secondary ring. It is an isolated accent
action over foreign content, so it keeps a filled disc and a paired rim instead of a tinted holder —
arbitrary pixels cannot be trusted to supply an icon's background. Press is opacity 0.72 over
`SHORT`/`pressEasing`; drag is immediate and clamped to the display; the saved position is restored.
It is **absent**, not dimmed, while a lookup is being admitted, captured or open, and it never appears
on a locked screen.

### 8.7 Settings, disclosure and the tile

- The **Screen lookup** page uses the shared detail-page grammar (§7.2): flat rows, bare 22 dp icons,
  real values, no row cards. Lookup mode shows the service's own state; Pause media is an access
  grant, not a preference switch; the tile row shows `Added` or the one `Add tile` action; Key Mapper
  exposes the exact broadcast action in monospace with a Copy action.
- The **accessibility disclosure** keeps every consequence visible, in four headed blocks (Screen
  access, On this device, Optional connections, Next step), with the acknowledgement and the actions
  pinned outside the scrolling text. Privacy and permission consequences never move behind Help; only
  operational detail (tile removal, media fallback, key mapping) does.
- The **Quick Settings tile** is labelled **Screen lookup**, keeps the app's own glyph, and leaves
  size, tint and radius to the system. Its state describes lookup availability, not a latched capture
  toggle. A disabled tile routes to the same disclosure before any native permission prompt.

### 8.8 Copy

Sentence case, one clause per line, in the app's voice; the workflow's full string list is in
[the specification](../artifacts/lookup-redesign/DESIGN.md#9-copy-inventory). Data — the OCR source,
glosses, forms, parts of speech, deck names, mnemonics — is never rewritten to fit the one-clause
rule. Failure copy names the real cause and the one action that fixes it, and never claims a success
the app did not observe.

### 8.9 Motion

Overlay timings use the shared tokens only: dim and rail `SHORT` (150 ms), sheet entrance `MEDIUM`
(300 ms), sheet exit and full dismissal `SHORT`, all on `emphasized`; finger-driven geometry is
immediate; state changes (commit, edge weight, expand/collapse, action label) are 0 ms layout changes;
a sub-threshold drag returns on the `overshoot` spring. Search animates the modal bounds to full
screen and back with the shared motion tokens, revealing the query controls smoothly. The keyboard
appears after expansion and its geometry follows the native IME insets. The restored screen-edge
outline retains its bottom-to-top reveal and result-time retraction. Functional spinners keep the platform's own timing. Reduced motion is
the platform's animation scale — every one of these is a Compose animation and scales with it, and no
custom timing loop bypasses that scale.

### 8.10 Amendments this workflow makes

Approved for surfaces drawn over foreign content, and nowhere else:

1. A fixed **40 % black freeze veil** in both modes.
2. A neutral, opaque status rail with **13 dp corners and a 1 dp `outlineVariant` perimeter**, in both
   floating and inline placements, with **no accent leading rule**. Its boundary is a notice recipe,
   not a general rounded-card treatment.
3. A **two-pass opaque paired stroke** (5/3 dp, 4/2 dp and 3/1 dp pairs) for the lasso and the
   selection edge, where a single theme role cannot guarantee contrast on arbitrary pixels.
4. A **4 dp selection corner**, alongside the external sheet's 20 dp dock corner.
5. A **2 dp paired perimeter** on the isolated floating action.

These are component recipes, not new shared tokens: they use existing roles, the existing 13 dp
control radius value and shared spacing without adding a colour, typography, shape or motion rung.
Centred lookup artwork and the full-screen dictionary-search cover reuse the app's sparse-artwork,
field and focus grammar; they do not authorize translucent fields, tinted content cards or extra
windows. The screen-edge outline restores the original 900 ms reveal, 420 ms retraction and
2400 ms busy drift; these are the restored component’s existing timings.

## 9. Floating bottom navigation

The app has **Home · Analytics · Settings**. Review is pushed from Home.
Settings uses the app's gear glyph; do not copy a preview's person/Profile icon.

The floating neutral track stays centered and adapts to measured label widths.
It is not a full-width black dock and not necessarily as wide as the content column.
Use the 63 dp track, 6 dp inner padding, 20 dp track radius, 46 dp selected visual and
15 dp selected radius. The selected pill contains its 20 dp glyph plus the 11 / 14 label;
inactive tabs show only their glyph. The selected fill/ink follows the chosen accent.

The bar accounts for the native bottom inset and has at least 48 dp touch regions.
Keep body padding sufficient to scroll the last content above it.
Hide it on pushed detail/review/settings pages and during Home search.

Its motion follows the horizontal pager rather than a separately timed tab animation.
The existing short tap-settle and icon-arrival gestures, and the spring-based bar
departure/return, are intentional. Preserve reduced-motion behavior and measured labels;
do not truncate Settings to make a fixed capsule fit.

## 10. Feedback, motion and microcopy

| Concern | Current convention |
| --- | --- |
| Press | Shared PressableScale changes opacity to 0.72 over 100 ms; it does not scale the row |
| Focus | 2 dp accent outline with 2 dp offset |
| Search mode transition | 220 ms emphasized curve; reduced-motion duration is zero |
| Hand-timed review step transition | 260 ms emphasized curve |
| Bottom navigation | Pager-driven pill; existing selection and arrival/departure springs |
| Haptics / sounds | Retain existing feedback settings; no feedback for reselecting an unchanged accent |
| Loading | Name what is loading, prevent duplicate submission and retain stable control placement |
| Error | Plain-language cause/context with a useful next action |
| Empty | State the absence honestly; use the screen-specific empty treatment |
| Disabled | Semantic disabled state with the theme's disabled colors |

Avoid ambient animations and animated counters. The old blanket prohibition on all spring
or icon motion does not describe the current navigation. Respect the system reduced-motion
preference for custom transitions; do not add motion solely to fill empty space.

Use sentence case, short destination names and concise values. Prefer Connect, Reconnect,
Try again, Save, See all and Show less over enthusiastic or repetitive instructions.
Use the actual **days**, **No recent searches found**, **Screen lookup** and **About** labels
where specified above, rather than obsolete mockup copy. Legal, error and permission detail
may be longer when the reader needs it.

## 11. Accessibility and responsive requirements

- Keep effective touch targets at least 48 × 48 dp; smaller painted icons are fine inside them.
- Keep system-scalable fonts and flexible row height. Do not fix a Settings row at the
  minimum height when text needs more room.
- Preserve source/status and day-state accessibility labels, selected radio/tab semantics,
  disabled states and expanded/collapsed state.
- Pair colored dots with words and completed-day fill with a check. Color alone is insufficient.
- Use readable secondary text for descriptions. Decorative icon/fine roles are not body colors.
- Preserve Home's outcome stacking and large-text gloss layout, Settings value wrapping and
  measured bottom-nav labels.
- Use native status/gesture insets and keyboard avoidance. Never draw mock system chrome.
- When a visual pass is requested, check light/dark, each affected accent, narrow widths and
  100%/130%/160% text scale, including keyboard and TalkBack behavior. These are future checks,
  not claims that they were run in this documentation audit.

## 12. Source map and maintenance

| Concern | Current source |
| --- | --- |
| Neutral/status palette and accent ramps | [Color.kt](../app/src/main/java/com/gaten/app/ui/theme/Color.kt) |
| Native role mapping and color bridge | [Theme.kt](../app/src/main/java/com/gaten/app/ui/theme/Theme.kt), [GatenThemeModule.kt](../app/src/main/java/com/gaten/app/rn/GatenThemeModule.kt) |
| RN aliases and theme preferences | [theme.tsx](../src/rn/ui/theme.tsx), [GatenTheme.ts](../src/rn/native/GatenTheme.ts) |
| Shared type, spacing, shape and motion | [tokens.ts](../src/rn/ui/tokens.ts), [Type.kt](../app/src/main/java/com/gaten/app/ui/theme/Type.kt) |
| Home layout and search-mode behavior | [HomeScreen.tsx](../src/rn/ui/HomeScreen.tsx), [searchParts.tsx](../src/rn/ui/search/searchParts.tsx) |
| Home rows, streak, history and artwork | [HomeDashboard.tsx](../src/rn/ui/home/HomeDashboard.tsx), [HomeArtwork.tsx](../src/rn/ui/home/HomeArtwork.tsx) |
| Home data/observation scope | [useHomeDashboard.ts](../src/rn/ui/home/useHomeDashboard.ts) |
| Study state Home and the review flow share (Anki deck list, WaniKani counts) | [useAnkiDecks.ts](../src/rn/ui/anki/useAnkiDecks.ts), [useWaniKaniStats.ts](../src/rn/ui/analytics/useWaniKaniStats.ts), [review-store.ts](../src/rn/ui/review/review-store.ts) |
| Analytics composition and sections | [AnalyticsScreen.tsx](../src/rn/ui/analytics/AnalyticsScreen.tsx) |
| Analytics borderless blocks (section, rule, metric pair, heatmap, rings, bars, unavailable, skeleton) | [ink.tsx](../src/rn/ui/analytics/ink.tsx) |
| Analytics copy, sums, heatmap bands and chart labels | [model.ts](../src/rn/ui/analytics/model.ts), [tests/analytics.test.cjs](../tests/analytics.test.cjs) |
| Analytics reads (twelve-week Anki activity, WaniKani accuracy and level history) | [useAnkiStats.ts](../src/rn/ui/analytics/useAnkiStats.ts), [WaniKaniStats.kt](../app/src/main/java/com/gaten/app/wanikani/WaniKaniStats.kt) |
| Analytics data contract, colour mapping and states | [analytics/PLAN.md](analytics/PLAN.md) |
| Settings hub | [SettingsScreen.tsx](../src/rn/ui/settings/SettingsScreen.tsx) |
| Settings groups, rows, fields and dialogs | [settings-components.tsx](../src/rn/ui/settings/settings-components.tsx) |
| Settings detail pages | [settings/](../src/rn/ui/settings/) |
| Buttons and press/focus feedback | [components.tsx](../src/rn/ui/components.tsx), [PressableScale.tsx](../src/rn/ui/PressableScale.tsx) |
| Bottom navigation and route behavior | [BottomBar.tsx](../src/rn/ui/BottomBar.tsx), [AppShell.tsx](../src/rn/ui/AppShell.tsx) |
| Lookup overlay: session, scrim, status rail, gesture wiring | [LookupOverlay.kt](../app/src/main/java/com/gaten/app/ui/lookup/LookupOverlay.kt), [LookupStatusRail.kt](../app/src/main/java/com/gaten/app/ui/lookup/LookupStatusRail.kt) |
| Lookup overlay: lasso, selection hole and edge | [LassoLayer.kt](../app/src/main/java/com/gaten/app/ui/lookup/LassoLayer.kt) |
| Lookup result sheet, docks and inner reference pages | [ResultSheet.kt](../app/src/main/java/com/gaten/app/ui/lookup/ResultSheet.kt), [SheetPlacement.kt](../app/src/main/java/com/gaten/app/ui/lookup/SheetPlacement.kt), [WaniKaniDetail.kt](../app/src/main/java/com/gaten/app/ui/wanikani/WaniKaniDetail.kt) |
| Lookup trigger: floating button, tile, capture ordering | [FloatingTriggerButton.kt](../app/src/main/java/com/gaten/app/trigger/FloatingTriggerButton.kt), [LookupTileService.kt](../app/src/main/java/com/gaten/app/LookupTileService.kt), [GatenAccessibilityService.kt](../app/src/main/java/com/gaten/app/GatenAccessibilityService.kt), [OverlayWindowController.kt](../app/src/main/java/com/gaten/app/ui/overlay/OverlayWindowController.kt) |
| Lookup strings, settings page and disclosure | [strings_lookup.xml](../app/src/main/res/values/strings_lookup.xml), [LookupSettingsScreen.tsx](../src/rn/ui/settings/LookupSettingsScreen.tsx) |
| Lookup design specification and evidence | [lookup-redesign/DESIGN.md](../artifacts/lookup-redesign/DESIGN.md), [lookup-redesign/](../artifacts/lookup-redesign/) |
| Other screen refinements and historical checks | [Implementation record](design-language.md) |

For a future UI change, update the affected section here and the shared token source where
applicable. Record the reason, light/dark impact, accessibility implications and what was
actually checked. Keep supplied historical packages intact and distinguish proposals from
shipped behavior.

### Version 1.1 audit changes

Consolidated the selected-accent system and actual typography; removed the separate Home
CTA and optional split-card baseline; documented current search mode, bounded Anki streak,
service states and history artwork; replaced obsolete Settings entries/card geometry;
added all six Settings pages and their controls; corrected three-tab navigation, current
motion and dark secondary text. This was a documentation-only source audit.

### Version 1.2 changes

Added §6 Analytics: the three-view borderless layout, the switcher and section grammar, the
per-element colour roles with the three-step accent ladder and the one `success` segment, and the
honesty rules for missing, unavailable and cached figures. Renumbered the following sections; added
the analytics sources to §12. Checked on a debug build at 392 dp across all seven accents in light
and dark, and at 160 % text scale.

### Version 1.3 changes

§6 Analytics rewritten for the final single-page design: the source switcher is gone, the sections are
`Anki → WaniKani → Study activity → Recall → Ahead` (the two source summaries lead; the twelve-week
heatmap follows them; the last two became `Accuracy` and `Level progress` on 23 October, and §6 above
carries the current names), and the colour rule is now accent-only — the
`success` segment on the collection bar is removed, so no analytical element carries a hue the reader
did not choose. Added the heatmap's unknown-versus-zero treatment, the cumulative-accuracy rule, and
the once-per-source action rule. Checked on a debug build at 392 dp across all seven accents in light
and dark, at 320/340/430 dp, and at 130 % and 160 % text.

### Version 1.4 changes

Added §8 **Screen lookup**: the freeze veil with no screen-edge ring, the two-pass paired selection
mark (a 5/3 dp trail, 4/2 dp while reading and 3/1 dp once the answer is up, a 4 dp hole), the square
status rail with its 2 dp accent rule and its four lifetimes, the flat result sheet (grip → header →
one body scroller → pinned footer, unfilled word tabs, neutral headword, bare-glyph kanji strip,
honest Anki labels), the opaque floating button with its paired rim, the Screen lookup settings rows,
the regrouped disclosure, the tile's own name and state, the workflow's copy, and the amendments this
workflow is allowed to make (§8.10). Renumbered §8–§11 to §9–§12 and added the lookup sources and
evidence to the source map. The specification, the brief, the eight design renders and the device
stills are in [artifacts/lookup-redesign](../artifacts/lookup-redesign/README.md).

Checked on a debug build on the `Pixel_Safe` emulator (1080 × 2340 @ 440 dpi, portrait): the sheet,
lasso and rails in light (Indigo) and in dark with the Black accent, which resolves to white ink on a
dark page; the right dock in landscape; the empty-crop and tap-miss rails; the floating button at
rest over another app's content; and the sheet at 160 % text over a browser page, where the header
source reflows into the body and the deck value stacks above the action. The eight renders cover all
seven accents across light and dark, both side docks, the live gesture, the committed crop and the
disclosure. Reduced motion was not exercised: the transitions are Compose animations and follow the
platform's animation scale, which was left untouched.

### Version 1.5 changes

Revision 2 of the lookup workflow, from a review pass on the shipped redesign. The **status rail** is
now a 13 dp rounded neutral surface with **no accent leading rule** (it was square and carried one),
and a settled message stays three seconds before fading with the message that follows it staying two.
**No text found** and **No dictionary matches** became **centred, illustrated empty states** — sparse
neutral SVG artwork, the message, **Search dictionary** as the primary recovery and **Select again** as
the secondary action — replacing the left-aligned inline rails; **Dictionary unavailable** stays an
error rail. A confirmed zero-line screen index now opens that no-text sheet instead of spending a timed
notice. The sheet's header grew to a four-control cluster (Copy → Search → Select again → Close) that
yields controls into the body as physical width shrinks, and **Search dictionary** opens a
**full-screen query cover inside the same overlay window**: the house field, the existing offline
search helper verbatim (romaji included), flat rows carrying exact / matched-form / approximate-reading
/ prefix provenance, and a chosen entry returning to the sheet labelled **Searched** with **Original
lookup** to restore what the capture said.

Checked on a debug build on the `Pixel_Safe` emulator (1080 × 2340 @ 440 dpi, portrait and landscape,
100 % and 160 % text): the rounded rail and its dwell, both illustrated empty states (a blank crop and
a zero-hit query), the bottom dock and a 45 % side dock, the full-screen cover with the system keyboard
up, romaji input (`neko` → ねこ, 9 results, exact and prefix provenance), choosing a row, and
**Original lookup** restoring the captured result. The keyboard taking focus no longer ends the lookup,
which is what lets the reader type at all; the existing 200 ms focus bounce is otherwise unchanged. Not
exercised: the WaniKani reference page inside a searched entry (needs a token) and the reduced-motion
path (every transition is a Compose animation and follows the platform's animation scale).

The same revision closed a hole found while testing it: with the overlay up, an accessibility overlay
window keeps the window focus, so Home — or another app coming forward — never produced the focus loss
the dismissal was built on, and the frozen frame stayed on top of whatever the reader switched to. The
lookup now also ends on a **full-screen window from a package that is neither Gaten's nor the app the
capture froze** (nor the active keyboard), leaving the media paused for whatever took the screen.
Verified on the emulator: Home ends the lookup both when the service has seen the app come forward and
when it has not, an idle overlay survives twelve seconds untouched, Back still closes it, the keyboard
opening while searching does **not** close it, and Home while searching does.

### Version 1.6 — Lookup review follow-up

Restored the animated screen-edge outline and applied the selected accent to the instruction’s
gesture glyph. Matched the overlay query field to Home’s compact search control, removed the
visible Dictionary query heading, and restored automatic search while typing. Search now expands
from the result modal’s measured bounds to full screen rather than switching surfaces with a fade.
The native Compose field mirrors Home’s React Native control because the overlay uses a separate
native window. The Anki action keeps 24 dp of bottom breathing room above the system inset. Tapping
the deck name opens an in-overlay picker; choosing an existing deck updates the saved export deck
for future cards while preserving tags, templates and field mapping. Picking a deck never writes a
card, and the destination remains editable after a duplicate is found.
