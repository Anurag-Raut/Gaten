# Gaten Ink V4 — Unified row design specification

## Exact decision
- **Anki**: preserve back bar → `Choose a deck` → count subtitle → `Available decks` → simple separator rows.
- **WaniKani**: preserve back bar → `Your study` → `Choose what to work on next.` → `Available study` → two rows: `Reviews`, `New cards`.
- Both hubs use SAME horizontal inset, label styling, row gutters, 1dp divider, art/icon holder width, title/subline arrangement, right-side count/check and right chevron. WaniKani may be about 20dp taller per row for helpful next-review/next-batch line, but not separate card/button blocks.
- WaniKani **entire row** is tappable to start its review or lesson flow. No large Start buttons, no separate coming-up section, no WaniKani tabs, no rounded enclosing containers, no dividers between left/right count sections.
- Anki entire deck row remains tappable to enter deck-specific session. Never open an empty session for a cleared deck.

## Sizes (Android dp/sp suggestions)
- Content horizontal padding: 22–24dp; app header height existing native rules; hero heading 34–36sp, weight 700–750, compact tracking.
- Gap after heading: subtitle 12–13sp; space to section list label about 36dp. List label: 12sp, medium/bold.
- Row: Anki minimum ~86–94dp; WaniKani ~104–118dp because of extra time/batch line. Native screens naturally scroll, do not use fixed height or hard-code phone status bar.
- Row artwork holder: 46–48dp square, rounded radius 12–13dp; gap after icon 12dp. Title 15–17sp semibold. Subtext 11–12sp. Count: Anki 22–25sp, WaniKani 25–27sp; count label below 10–11sp. Right chevron is ~20–24dp and always aligned in a common trailing lane.
- Dividers: 1dp line flush with content edges or consistent inset; no shadows. Tap targets >=48dp; focus/screenreader states.

## Theme and colors
| Token | Light | Dark |
|---|---|---|
| background | `#FDFDFD` | `#101114` |
| main text | `#17171A` | `#F5F5F6` |
| readable muted text | `#6C6C75` | `#A7A7B0` |
| hairline | `#E9E9ED` | `#303136` |
| muted surface | `#F6F6F8` | `#1B1C21` |
| default accent | `#7564D9` | chosen theme accent; follow production theme |
| completion green | `#238858` | `#69C796` |
| completion soft bg | `#ECF8F0` | `#18291F` |
| completion soft border | `#D8ECDC` | `#294734` |

Accent is user-selectable; **success green must remain separate and consistent across both providers**. Avoid green success filling the whole page. Only use a subtle green row background for the *just completed* Anki deck, optional first return; persistent clear rows remain neutral with a small green check. WaniKani clear rows also remain mostly neutral. If both queues clear, optional compact pale-green success notice below title; never replace rows with an empty screen.

## Components
- `StudyHubHeader` common spacing/type (native back/header, title, subtitle).
- `InkStudyRow` shared semantic layout: leading art, main title, subline, optional tertiary detail, trailing due count or success check, chevron. Props for interactive/dimmed/accessible text.
- `SuccessCheck` one implementation, 21dp green filled circle with white tick (in dark mode ensure high contrast).
- `ProceduralDeckArt` generated from **stable deck ID** and six fixed pastel palettes (blue, blush, sand, mint, lilac, teal). Never random per render or inferred from due/complete status. See generator.
- `InkProviderIcon` for WaniKani uses the same 48dp holder and curated line icon; do NOT reuse deck artwork randomly for the two provider actions.

## Copy
- WaniKani Reviews ready: title `Reviews`; `23 reviews due`; `Next review · Due now` (avoid contradictory next-batch placeholder when due now).
- WaniKani New cards ready: `New cards`; `77 lessons available`; `Next batch · 5 cards` only if known.
- Completed section: `All caught up`, `No reviews due` or `No new lessons available`; next time only if real.
- Anki clear: `Clear for now` even without tracked session. `Completed today` only with evidence. Never say all done if unknown or sync failed.

## Not part of change
Do not redesign the separate full-screen session completion experience already approved. Keep normal scheduler and session routing unaltered; only update hub presentation and post-completion return states.
