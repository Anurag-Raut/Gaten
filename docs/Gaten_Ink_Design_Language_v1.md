# Gaten / Ink — Design Language

Rules for the shipped app: Home, the Anki and WaniKani study hubs, review and lessons, Analytics,
Settings, the screen lookup workflow and the shared tokens. This is the single current design
authority. The frozen packages and historical records under `docs/` are indexed in
[`docs/README.md`](README.md); none of them overrides this file. The filename keeps `v1` for stable
links.

## Summary

| Area | Rule |
| --- | --- |
| Visual language | Quiet, mostly monochrome pages; flat rows, whitespace and 1 dp separators establish hierarchy. |
| Home | Gaten wordmark → search → Daily streak → Anki and WaniKani rows → Recently looked up. The service rows are the study actions; there is no separate Start studying button. |
| Study hubs | Anki and WaniKani share one flat row grammar: a 48 dp art or icon holder, a title with sublines, one trailing count lane or an accent check, and a chevron only on an actionable row. Each hub carries a flat **Today** block above its destinations. |
| Review and lessons | Anki review renders the note's template; WaniKani review and lessons share one field, feedback and pinned footer. Meaning is teal and Reading is violet in both, independent of the reader's accent. |
| Analytics | One page, no source switcher: Anki → WaniKani → Study activity → Accuracy → Level progress, with no cards at all — hairline-separated sections, paired figures split by a single vertical rule, unboxed charts. |
| Settings | Seven destinations in three flat groups: Lookup, Study and App. No row cards or tinted section containers. |
| Color | System/Light/Dark modes and seven selectable accents. The chosen accent colors actions, selection, Home due counts and streak marks — and, on Analytics, every chart and every ring, in accent-intensity ladders rather than extra hues. No success green, brand pink or brand blue appears in an analytical element. |
| Navigation | Three bottom tabs: Home, Analytics and Settings. Review is a pushed flow, not a fourth tab. |
| Screen lookup | Over foreign content: an animated screen-edge outline, a 40 % black freeze veil, a two-pass selection mark, a rounded neutral status rail with an accent gesture glyph, and a flat result sheet that expands into full-screen search. Its query control matches Home and searches as the reader types. |
| Exceptions | Neutral input fills, brand-mark holders, dialogs and approved sparse empty-state artwork have specific treatments below. |
| Source of truth | The React Native UI in `src/rn/ui/`, the lookup overlay in `app/src/main/java/com/gaten/app/ui/lookup/`, and the shared tokens are the implementation this document describes. Historical previews and implementation records do not override it. |

**North star:** a quiet study instrument. Japanese lookup and study remain the focus. This is a
reusable design guideline, stated as present-tense rules rather than sample counts, mockup-only
controls or a change log.

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

The current rules for the Anki deck hub and the WaniKani study hub are in §8. The user-supplied
*Gaten unified Ink hubs V4* package ([`unified-ink-hubs-v4/`](unified-ink-hubs-v4/README.md)) is the
frozen visual reference for those rows, and
the [Anki/WaniKani hub handoff](hubs-procedural-art/README.md) and
[WaniKani Ink V3](wanikani-ink-v3/README.md) are earlier historical packages. Where any of them
differs from §8, §8 wins. Frozen prototype screenshots use sample data and are not evidence of
shipped behavior.

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

Use `onSurfaceVariant` for copy; keep the `fine` role and the secondary-icon tones for decorative
details and glyphs, never for body text. Contrast targets are at least 4.5:1 for normal text and
3:1 for large text.

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
these roles. The selected navigation pill also uses `primary / onPrimary`.

### 2.3 Brand and task exceptions

- Home Anki holder: #EAF4FF light / #182D40 dark.
- Home WaniKani holder: #FFF0F2 light / #38212A dark.
- Brand color stays inside those holders and supplied marks; adjacent row text/surfaces remain neutral.
- Home due numbers, streak flame, completed-day fills, today's accent marker and See all action
  follow the selected accent.
- Review task colors remain independent: Meaning #2B7178 light / #8FC5CA dark;
  Reading #6D28D9 light / #B0A0FF dark. These are task semantics, not the user's accent.

## 3. Typography

**IBM Plex Sans JP** is bundled and is the face for Latin and Japanese alike, so a mixed string stays
in one face; only a glyph the face does not carry falls back to the platform. Every shared text role
names it explicitly (`APP_FONT` in `tokens.ts`). Do not infer current typography from a historical
preview or an attribution-row title.

| Shared role | Size / line height | Weight | Letter spacing |
| --- | --- | --- | --- |
| Home wordmark, `Analytics` and `Settings` headings | SVG artwork, shared scale below | — | traced |
| Text heading (non-artwork fallback) | 35 / 44 | 700 | -1.85 |
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
| Primary value (meaning, reading, keyword) | 20 / 28 | 600 | 0 |
| Kana reading line under a headword | 17 / 24 | 400 | 0 |
| Small print | 11 / 15 | 400 | 0 |
| Uppercase tracked label | 11 / 14 | 700 | 1.20 |
| State / narrative title | 22 / 28 | 700 | -0.70 |
| Single-kanji hero (lesson, review) | 64 / 80 | 500 | 0 |
| Sentence-size Japanese | 28 / 36 | 500 | 0 |
| Analytics hero figure | 34 / 38 | 700 | -1.50 |
| Home due count (override) | 24 / 24 | 700 | -0.90 |
| Home streak count (override) | 20 / 22 | 500 | 0 |
| Home empty-history caption (override) | 13 / 18 | 700 | 0 |

Home-specific overrides are intentional: the due count is 24 / 24, weight 700, tracking -0.9;
the streak count is 20 / 22, weight 500; the empty-history caption is 13 / 18, weight 700.
Weekday labels use the 12 / 18 field/value role, not the original 10 px mockup labels.

### Content text roles

Which role carries which kind of text, on every surface — the answer, the panels, the lesson, Word
details and the lookup sheet alike. The colours are the theme's own roles, not new ones.

| Kind of text | Role | Colour |
| --- | --- | --- |
| Headword (word being studied: Anki answer, Word details, lookup sheet) | `displayMedium` 40 / 48 | onSurface |
| Kanji glyph in chip / tile / panel heading | `displaySmall` 34 / 44 (+ `KANJI_LETTERING`) | onSurface |
| Lesson/review hero (single kanji) / (word) | `glyphHero` 64 / 80 / `displayMedium` 40 / 48 | onSurface |
| Kana reading line under a headword | `readingLine` 17 / 24 | onSurfaceVariant |
| Metadata line (Common, part of speech summary) | `bodyMedium` | onSurfaceVariant, POS word in `colors.primary` |
| Field caption (Meaning, Reading, Meanings, Part of speech, Keyword, Heisig’s story, Radical composition…) | `labelMedium` 12 / 18 600 | onSurfaceVariant |
| Primary value (the meaning / the reading / the keyword) | `valueLarge` 20 / 28 600 | `colors.accent` (the reader's accent, never a fixed colour) |
| Secondary senses, mnemonics, story text | `bodyMedium` | onSurfaceVariant for mnemonics/story; onSurface for 2nd+ dictionary senses |
| Example sentence JA / EN | `bodyMedium` / `bodySmall` | onSurface / onSurfaceVariant |
| Section heading (Remembering the Kanji, WaniKani kanji, Example sentences) | `titleSmall` 15 / 700 | onSurface |
| Row label / row value (Other spellings, deck row) | `labelLarge` unchanged / `bodyMedium` | unchanged |
| Notes, counts, helper text | `bodySmall` | onSurfaceVariant |
| Small print | `micro` | onSurfaceVariant |

The Meaning of anything is always a caption plus the accent-coloured `valueLarge`.

The roles are the only place a size or a weight is written as a number, and
`tests/typography-guard.test.cjs` enforces it: the test walks `src/rn/ui/**/*.ts(x)` and fails on any
numeric `fontSize:` literal or `fontWeight: "<n>"` literal, naming `file:line` for each offender. A
screen spreads a role instead. Comment lines are skipped, so a doc comment may still name a size.
Each exemption is recorded beside its entry in the test's `ALLOW` table, scoped to the kind of literal
it covers. Both kinds are exempt in `tokens.ts` itself, where the roles are defined, and in the
onboarding screens `onboarding/WelcomeScreen.tsx` and `onboarding/OnboardingFlow.tsx`, whose marketing
and narrative text is scaled by `fontScale` and timed with the artwork. `fontWeight` alone is exempt
in four places, and a numeric `fontSize:` written in any of them still fails: `AppShell.tsx` (the
navigation theme's `fonts` map, which the library takes as numeric weights), `analytics/ink.tsx`
(weight-only emphasis laid over a role, and the 13/19/600 status line no single role carries),
`rtk/richText.tsx` (an inline bold span inside story text) and `settings/settings-components.tsx`
(an inline emphasis span inside a body paragraph). Sizes are never exempt on a screen: every
screen's type comes from a role.

Kotlin `Type.kt` is deliberately not extended with these roles: the lookup sheet's body is React Native
(`LookupSheetApp`), so `tokens.ts` is the single source of truth and `SheetType.kt` stays as it is.

The Home wordmark (`GatenWordmark.tsx`) and the `Analytics` and `Settings` headings
(`TabHeading.tsx`) are the approved SVG letterforms, not text. All three share one scale —
`brandHeadingScale(fontScale) = 24.5 × fontScale / 435`, drawn in a `44 dp × fontScale` line box; the
wordmark keeps its own aspect ratio and box (`1833 × scale` wide, `44 × fontScale` tall). The
headings therefore grow with the system font scale like text and wrap between glyphs rather than
clipping. Keep the approved artwork and these shared metrics; never substitute plain text or a font.

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
| Settings text field | Minimum 49 dp, radius 14 dp, filled `surfaceVariant`, no outline; 16 dp horizontal / 12 dp vertical padding; caret follows the accent |
| Filled/tonal/outlined button | Minimum 52 dp, radius 13 dp |
| Text/icon action | Minimum 48 dp effective target |
| Dialog | Radius 20 dp, padding 24 dp, width capped at 360 dp |
| Floating bottom bar | Height 63 dp before native bottom inset, radius 20 dp |
| Selected bottom pill | Visual height 46 dp, radius 15 dp; touch region at least 48 dp |
| Rule / focus | 1 dp separator; 2 dp accent focus outline |

The current Settings hub uses bare glyphs and flat rows: no rounded row cards or icon tiles.

Content surfaces stay flat. The bottom capsule is the sole raised in-page surface:
elevation 8, black shadow with opacity 0.18, radius 16 and offset (0, 0), as declared in code.
The centered shadow is part of the navigation treatment, not a reusable card shadow.

## 5. Home

### 5.1 Composition and search

Resting order: **Gaten → search → Daily streak → Anki → WaniKani → Recently looked up**.
There is no separate filled study CTA and no optional split-card variant in the current baseline.

The header has 12 dp top/bottom padding; the brand adds 11 dp above and 20 dp below.
The brand is the approved Gaten wordmark artwork (`GatenWordmark`), drawn at the shared heading scale
(§3) in the ink the supplied light or dark wordmark file uses — chosen from `useTheme().dark`, so it
follows light/dark alone and never one of the accents. It scales with the system font size like the
tab headings; it is not a fixed text size.
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

Rows have a 58 dp minimum height, 12 dp horizontal and 4 dp vertical padding, a headword/reading
block and a trailing gloss, with 1 dp separators between entries. At normal scale the gloss occupies
at most 34 % of the row, right-aligned on the row's content bottom edge, and clamps to two lines
(`numberOfLines={2}`). Above 1.2 font scale it moves below the word, left-aligned at full width, and
still clamps to two lines. A clamped gloss draws its tail ellipsis as three baseline periods — swapped
in from the platform's `…` once layout reports the cut — and keeps the full gloss as its
accessibility label.

The current empty state is a centered, theme-aware **history tray and clock** SVG,
up to 340 × 148 dp, followed by **No recent searches found**. Keep this approved exception sparse;
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

- **Header** matches Home: the `Analytics` SVG heading (§3), the freshness line 13 sp 10 dp beneath
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

Use the same page canvas and 560 dp column as Home, with native top inset and the `Settings` SVG
heading (§3). The heading begins 23 dp below the inset; each group starts 22 dp after the preceding
element.

| Group | Destination | Trailing live value |
| --- | --- | --- |
| Lookup | Screen lookup | On / Off |
| Lookup | Text recognition | Installed model label / None |
| Study | Anki cards | Export deck, Choose deck, Not installed or Permission needed |
| Study | WaniKani | Connected / Not connected |
| Study | Kanji reference | Remembering the Kanji / WaniKani |
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

All seven pages keep the flat section language. Use a **64 dp app bar below the top safe inset**,
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
| WaniKani | Connection status, Lessons → New per day, and token controls. The daily goal is a whole number from 1 to 20, and 15 until the reader sets one — the field always shows the goal the device works to, so there is no unlimited setting, no placeholder and no helper line, and no line reports the learner's running count. One filled **Save** at the foot of the page commits both drafts: it writes the lesson limit, and checks and stores a token only when the field holds one, so a blank field saves the limit without touching a token that is already saved. Disconnect stays a separate, explicit text action. A saved limit is carried by the button's own Saving… → Save round-trip; only a failure takes ink under the field. |
| Kanji reference | One radio group choosing the reference a kanji page shows: **WaniKani** (the account's online kanji pages and radicals, needs a connected account) or **Remembering the Kanji** (bundled keywords, Heisig stories and Koohii mnemonics, offline). Until the reader chooses, the selection follows the WaniKani connection — WaniKani while an account is connected, Remembering the Kanji when none is — and an explicit choice is kept whatever the connection does. The choice applies immediately and is independent of the WaniKani connection, reviews, lessons and Analytics. |
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

- Shared fields use 14 / 20 text, the quiet filled surface (`surfaceVariant`), radius 14 dp and **no
  outline**: a field draws no border at rest and no ring when focused — focus is the caret and the
  keyboard. The box carries its own inset and sizes the input inside it; the input's own style keeps
  no padding, because Android's EditText padding otherwise wins for the entered text and a value sits
  at the platform's inset while the placeholder uses the box's.
- A field's name is `labelLarge` in `onSurface` — the strongest of the two labels a form has, so it
  never reads as another section heading (the quiet 12 / 18 `labelMedium` above it). A field that
  carries only a placeholder states its name in its accessible label too.
- Switch rows use the same 60 dp minimum and flexible copy block. The thumb disc reaches past its
  track, so the on state fills the thumb with the accent and the track with `accentSubtle`; off keeps
  `surfaceVariant` with the secondary-icon grey thumb, disabled the theme's disabled roles.
- Shared dialogs center a surface with 20 dp corners and 24 dp padding over a 50% black
  backdrop. Cap width at 360 dp; long help content scrolls, with actions remaining reachable.
- Keep permission disclosure explicit, including its acknowledgement where required.
  Concise hub copy must not conceal permission consequences.
- Anki selectors and editors remain their real controls, not decorative sample menus.

## 8. Study hubs — Anki and WaniKani

### 8.1 One row grammar

Both provider hubs draw the same flat row: a leading **48 dp** holder (the provider glyph on the
neutral surface, or Anki's deterministic procedural deck art), the title with its subline and at
most one `Label · value` line, then the trailing lane — the count in the reader's accent with its
caption, or a **22 dp accent check** — and a **22 dp chevron**. The whole row is one target of at
least 48 dp with one accessibility label and no button nested inside it. A chevron appears only on a
row that opens something.

Rows carry a **1 dp `outlineVariant`** bottom rule on the page surface; there is no card, tinted
panel or radius. Row floors: **90 dp** for an Anki deck row, **110 dp** for a WaniKani row, raised to
**118 dp** below 340 dp width or at 150 % type so the lines wrap instead of clipping.

`SuccessCheck` is a 22 dp disc filled with the reader's **accent** carrying an `onPrimary` tick. It is
the one completion mark in either hub, and completion is always carried by words as well as colour —
there is no green disc, no pale-green row wash and no green panel. An all-clear list keeps every row
visible under one compact neutral `You're all caught up` notice.

### 8.2 Honest state

Unknown data is never rendered as zero. Loading draws row-shaped skeletons with no numbers; a cached
snapshot stays marked stale; a failed read keeps its actionable retry/connect row instead of a count.
A fetched zero is clear on its own and never opens an empty session. A deck with nothing due leads
nowhere and reads **`Clear for now`** unless a session in it finished today, when it reads
**`Completed today`**. The other queue stays independent.

### 8.3 Anki deck picker

`Choose a deck` with the real due total, an `Available decks` caption, then one row per deck: 48 × 48
procedural art, the name, AnkiDroid's own counts, and the accent due total or the check. Opening Anki
always shows this hub, even when a single deck matches; starting a queue requires choosing that deck.
When AnkiDroid is not installed the state offers **Download AnkiDroid**, the same store-opening action
Analytics uses, and installation or first-time setup is rechecked on return.

Study-deck selection is stored by stable deck IDs: a null selection means all decks and an explicit
empty selection means none; the selection filters this hub and Home's Anki due counts. Parent decks
include their subdecks. It is separate from the export-deck destination.

### 8.4 WaniKani hub

A back bar, `Your study` with `Choose what to work on next.` (`Everything available is clear for now.`
when both queues are clear), one `Available study` caption and exactly two whole-row-tappable rows,
**Reviews** and **Lessons**, at a 110 dp floor. There are no black Start buttons, no tabs, no summary
or sync line and no `Coming up` section. A clear queue shows the accent check and its clear detail.

### 8.5 Today's activity

Both hubs carry a flat **Today** block above their destinations — page canvas, whitespace and
hairlines, no card or tint. Each block has its own help target (`About today's figures` on Anki,
`About today's activity` on WaniKani) that explains the read's scope.

- **Anki** states **`Cards studied`** — the distinct cards answered in AnkiDroid today, across every
  deck, with repeated ratings counted once — beside **`Ready now`** — the cards due now in the study
  decks the reader selected. The two figures have different scopes and never form a completion
  percentage. A `New / Learn. / Review` ready distribution sits under them.
- **WaniKani** states **`Items reviewed`** and **`Lessons learned`**, both device-local and bounded by
  local midnight. The two captions carry accent swatches at intensity 1 and 0.42, and they key **one
  segmented bar** that breaks the day's completed work into reviews and lessons. That bar states no
  count and is not a completion percentage: a zero day draws its neutral track, and a figure that could
  not be read draws no bar at all and says why.

A figure the source did not answer is an em dash with a reason, never a zero.

## 9. Review and lessons

### 9.1 Anki review

An Anki question renders the note's own template and deck context. Revealing changes the step title to
**Answer** and presents the known Gaten fields in a scalable **40 / 48 dp** expression, then 17 / 24 dp
reading and meaning; other and imported templates keep Anki's HTML renderer. Example sentences sit
behind a **56 dp** accessible disclosure row and are read from the bundled Tanaka Corpus — no network.
Up to three are shown shortest-first; a word the corpus lacks falls back to the note's own `Sentence`
field, then to a quiet no-examples line. The rating footer stays pinned above the native system insets,
and saving shows `Saving to AnkiDroid`.

### 9.2 WaniKani review and lessons

A subject badge carries the kind and level, a hero prompt (single kanji **64 / 80**, vocabulary
**40 / 56**, shrinking to fit one line) is the visual focus, and a compact `Meaning` or `Reading` task
label sits beside it. Meaning keeps the fixed semantic **teal** and Reading the **violet**, in both
review and lessons, independent of the reader's selected accent; other text and surfaces use the theme
roles.

The answer field's text is **centred** in the WaniKani review and lesson (`AnswerInput` overrides
`textAlign`); the shared `AnswerInput` default stays left-aligned for other callers. The placeholder is
`Type the meaning` or `こたえ`. The keyboard stays up through the judgement — the submit action consumes
the key without blurring — so the reader can keep typing. The typed answer wears the outcome: `error`
red while a wrong answer is held for its retry, `success` green on a correct one while it is sent; the
underline beneath it stays the accent.

- **A wrong answer stops on its own card** behind **Continue**: it marks the answer incorrect in red
  and never reveals the correct one for the reader. **Show answer** is a disclosure that opens on the
  part being asked (Reading for a reading prompt); **Primary meaning** is the hero and **Other
  meanings** sit quiet under it, with mnemonic and context tabs. The reader can edit the held answer:
  **Done** / **Continue** rejudges changed, non-empty text in place, while unchanged or emptied text
  clears to re-arm the retry. The incorrect count and the same prompt are kept and nothing is
  submitted prematurely.
- **A refused reading** (non-kana input) shows exactly **`Remove invalid characters`** in quiet
  `onSurfaceVariant` with the wrong-answer shake and buzz, judges and counts nothing, and clears the
  note on the next keystroke.
- **Saving** is stated above the scrolling card, directly under the progress line, while
  `phase === submitting`: a `BusyRow` reading **`Saving to WaniKani`** stays on screen at any scroll
  position and above the keyboard — it is never in the footer. `BusyRow`'s ring is a custom 900 ms
  circular animation (16 dp, 2 dp accent) that deliberately keeps spinning under `ReduceMotion.Never`,
  even at animator scale 0; this is the workflow's one intentional motion exception, not a general
  claim that motion ignores the reduced-motion setting. Settings' own `Save` keeps its `Saving…` label;
  the review and lesson saving labels carry no ellipsis. A failed send keeps its real cause and offers
  `Send again` (or `Reconnect` when the token was refused); sending again is safe because WaniKani
  records a review once.

### 9.3 Lessons

A lesson flow teaches a batch of subjects one card at a time, then quizzes them. A teach card carries
the kind and level, the character (or the radical drawing), the meanings and readings with their
mnemonics, and up to two example sentences for vocabulary; Meaning and Reading keep their review
colours. The quiz reuses the review's field, placeholder, feedback and pinned footer exactly.

A finished lesson is sent with **`PUT /assignments/<id>/start`**, never as a review, which WaniKani's
reference forbids. Each subject is started the moment its quiz passes, so leaving mid-batch keeps what
was finished; starting is idempotent, so a retry is always safe. The Lessons row then reads
`N lessons left in batch` with its progress and resumes the batch when tapped.

Each offered batch is capped at the smaller of the queue, five subjects, and the remaining daily
allowance. The Lessons row's trailing count is **today's allowance**, not the whole queue. Reaching the
daily limit is not "all caught up": the row states the limit, for example `Learned today · N of M`.

Lessons and reviews respect the account's entitlement ceiling (`max_level_granted`): content above it
is not offered at all.

Review and lesson writes are real: an Anki review save and a WaniKani review or lesson submit write to
the provider (`POST /reviews`, `PUT /assignments/{id}/start`). The study integration is not read-only.

### 9.4 Kanji and radical detail

A kanji page keeps its character beneath the app bar, because the character is the page's anchor.
A radical page draws its drawing unboxed on the page surface — no panel or tint behind it — with the
primary meaning as the one primary block beside it and the name stated once; the page's refresh and
open-on-WaniKani actions sit in a flat foot row. A radical with neither drawing nor character draws no
art rather than repeating its name. The nested radical detail inside a review expansion uses the same
page.

## 10. Screen lookup

The lookup workflow is the one part of Gaten drawn *over* somebody else's content: an accessibility
overlay window, a frozen screenshot, a finger-drawn selection, and a reading surface. It carries the
same paper, ink, accent and type scale as the app; what it adds is a small set of overlay-specific
recipes (§10.10) that exist only because the pixels underneath are not ours. The exhaustive tables —
every value, state, string and mockup — are in
[the lookup design specification](../artifacts/lookup-redesign/DESIGN.md).

### 10.1 Surfaces and states

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

### 10.2 Freeze and arming

- The first visible frame is the opaque frozen capture alone. Keep the controller's
  frame-commit → additional vsync → media-pause → UI ordering and its safety timeouts; no visual
  animation may delay capture, media pause or OCR. Capture takes one full-screen `ARGB_8888` software
  copy, shared with the overlay, the selection crop and OCR; that copy is the single source the whole
  lookup reads, so its lifetime is owned by the pipeline, not by any one consumer.
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

### 10.3 The selection mark

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

### 10.4 The status rail

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

### 10.5 The result sheet

Keep the **20 dp** dock corner and the **60 %** bottom-dock fraction — this is a window that
genuinely contains the reading task, not an in-page row card — on `surface` at alpha 1.00, elevation
0, with one **1 dp `outline` rule on the exposed top edge**. Content inset is the house 22 dp —
`16 dp` below 360 dp of content width — and the column caps at 560 dp.

Held sideways the sheet is one panel in the other shape: it fills the height the window gives it and
keeps the same centred 560 dp column. A top inset the window reports is reserved above it, so the
frozen status bar stays visible where the platform reports one; where the window reports none — the
accessibility-overlay window on the test emulator reports 0 — the panel starts at the display's top
edge and its toolbar sits in the status-bar band, and no reservation is guaranteed. The landscape
side dock, its **45 %** fraction and its exposed inner-edge rule are not used: held sideways the sheet
is the full-height panel described below.

Inside, there is no intermediate filled card: **grip → header → one body scroller → footer**. The
Anki action is that footer in both orientations — the content's own last item, reached by scrolling
the entry it belongs to, never a bar pinned to the panel's bottom.

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
- **Word tabs** are transparent, 0 dp radius, selected = `primary` text +
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

### 10.6 The floating button

48 dp, **opaque `primary` fill**, a 24 dp glyph in `onPrimary`, and a hard **2 dp `onPrimary`
perimeter** drawn inside the circle; no shadow, gradient or secondary ring. It is an isolated accent
action over foreign content, so it keeps a filled disc and a paired rim instead of a tinted holder —
arbitrary pixels cannot be trusted to supply an icon's background. Press is opacity 0.72 over
`SHORT`/`pressEasing`; drag is immediate and clamped to the display; the saved position is restored.
It is **absent**, not dimmed, while a lookup is being admitted, captured or open, and it never appears
on a locked screen.

### 10.7 Settings, disclosure and the tile

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

### 10.8 Copy

Sentence case, one clause per line, in the app's voice; the workflow's full string list is in
[the specification](../artifacts/lookup-redesign/DESIGN.md#9-copy-inventory). Data — the OCR source,
glosses, forms, parts of speech, deck names, mnemonics — is never rewritten to fit the one-clause
rule. Failure copy names the real cause and the one action that fixes it, and never claims a success
the app did not observe.

### 10.9 Motion

Overlay timings use the shared tokens only: dim and rail `SHORT` (150 ms), sheet entrance `MEDIUM`
(300 ms), sheet exit and full dismissal `SHORT`, all on `emphasized`; finger-driven geometry is
immediate; state changes (commit, edge weight, expand/collapse, action label) are 0 ms layout changes;
a sub-threshold drag returns on the `overshoot` spring. Search animates the modal bounds to full
screen and back with the shared motion tokens, revealing the query controls smoothly. The keyboard
appears after expansion and its geometry follows the native IME insets. The restored screen-edge
outline retains its bottom-to-top reveal and result-time retraction. Functional spinners keep the platform's own timing. Reduced motion is
the platform's animation scale, which the overlay's Compose animations follow; the one exception is
the review/lesson `BusyRow` ring in §9.2, which is deliberately pinned to keep spinning.

### 10.10 Amendments this workflow makes

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

## 11. Floating bottom navigation

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

## 12. Feedback, motion and microcopy

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

## 13. Accessibility and responsive requirements

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
- A visual pass checks light/dark, each affected accent, narrow widths and 100 %/130 %/160 % text
  scale, including keyboard and TalkBack behavior.

## 14. Source map

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
| Shared pushed-screen app bar | [ScreenHeader.tsx](../src/rn/ui/ScreenHeader.tsx) |
| Shared dialogs | [DialogShell.tsx](../src/rn/ui/DialogShell.tsx) |
| Heading artwork and tab-lettering metrics | [TabHeading.tsx](../src/rn/ui/TabHeading.tsx), [tabHeadingGlyphs.ts](../src/rn/ui/tabHeadingGlyphs.ts), [GatenWordmark.tsx](../src/rn/ui/home/GatenWordmark.tsx) |
| Study hubs, review and lessons | [review/](../src/rn/ui/review/), [ReviewScreen.tsx](../src/rn/ui/ReviewScreen.tsx) |
| Portrait-only app orientation, accessibility overlay | [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) |
| Buttons and press/focus feedback | [components.tsx](../src/rn/ui/components.tsx), [PressableScale.tsx](../src/rn/ui/PressableScale.tsx) |
| Bottom navigation and route behavior | [BottomBar.tsx](../src/rn/ui/BottomBar.tsx), [AppShell.tsx](../src/rn/ui/AppShell.tsx) |
| Lookup overlay: session, scrim, status rail, gesture wiring | [LookupOverlay.kt](../app/src/main/java/com/gaten/app/ui/lookup/LookupOverlay.kt), [LookupStatusRail.kt](../app/src/main/java/com/gaten/app/ui/lookup/LookupStatusRail.kt) |
| Lookup overlay: lasso, selection hole and edge | [LassoLayer.kt](../app/src/main/java/com/gaten/app/ui/lookup/LassoLayer.kt) |
| Lookup result sheet and inner reference pages (RN surface in the native window) | [LookupSheetApp.tsx](../src/rn/ui/lookup/LookupSheetApp.tsx), [LookupSheetHost.kt](../app/src/main/java/com/gaten/app/ui/lookup/LookupSheetHost.kt), [LookupSheetView.kt](../app/src/main/java/com/gaten/app/ui/lookup/LookupSheetView.kt), [SheetPlacement.kt](../app/src/main/java/com/gaten/app/ui/lookup/SheetPlacement.kt), [WaniKaniDetail.kt](../app/src/main/java/com/gaten/app/ui/wanikani/WaniKaniDetail.kt) |
| Lookup trigger: floating button, tile, capture ordering | [FloatingTriggerButton.kt](../app/src/main/java/com/gaten/app/trigger/FloatingTriggerButton.kt), [LookupTileService.kt](../app/src/main/java/com/gaten/app/LookupTileService.kt), [GatenAccessibilityService.kt](../app/src/main/java/com/gaten/app/GatenAccessibilityService.kt), [OverlayWindowController.kt](../app/src/main/java/com/gaten/app/ui/overlay/OverlayWindowController.kt) |
| Lookup strings, settings page and disclosure | [strings_lookup.xml](../app/src/main/res/values/strings_lookup.xml), [LookupSettingsScreen.tsx](../src/rn/ui/settings/LookupSettingsScreen.tsx) |
| Lookup design specification and evidence | [lookup-redesign/DESIGN.md](../artifacts/lookup-redesign/DESIGN.md), [lookup-redesign/](../artifacts/lookup-redesign/) |

For a future UI change, update the affected section here and the shared token source where
applicable. Keep supplied historical packages intact and keep proposals out of this file.
