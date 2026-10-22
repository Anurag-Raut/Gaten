# Anki and WaniKani hub handoff

> **Historical (21 October 2026).** The user-supplied *Gaten unified Ink hubs V4* package supersedes
> this handoff for both provider hubs: Anki and WaniKani now share one row grammar with hairline
> separators, 48 dp art/icon holders and no WaniKani action buttons. The layout rules below (rounded
> ~90 dp Anki cards, 56 × 72 dp art, green completion panel, five skeletons, WaniKani selected A
> sections) no longer describe the current screens. Keep this file for the state and completion
> distinctions it records, which V4 restates.

This was the screen-specific reference for the Anki deck hub and WaniKani study hub. It describes the
presentation and data truth as agreed before V4; the app no longer implements its hub layouts.

## Authority and scope

Use [Gaten / Ink v1](../Gaten_Ink_Design_Language_v1.md) as the shared visual authority for colors,
typography, accessibility, spacing, and other app screens. This handoff overrides older Anki and
WaniKani hub visuals only. In particular, the older [WaniKani Ink V3
package](../wanikani-ink-v3/README.md) remains in the repository as historical documentation; its
hub composition is superseded here. Its lesson-flow notes are retained as context and must be checked
against current code and provider behavior.

The latest user-approved [Anki board](references/ANKI-user-final-board.png) supersedes only the
earlier Anki borderless-row, 44 dp square-art, and compact all-clear presentation directions. Anki
uses rounded ~90 dp rows with 18 dp gaps, 56 × 72 dp procedural art, a green completion panel, and
five loading skeletons. WaniKani's selected A handoff remains unchanged. A real zero queue is not
evidence of studying today; report completion only from confirmed receipt IDs after an accepted
grade and an empty queue. A current, confirmed all-zero queue uses the green panel. Visual QA is at
100% font scale only; no further zoom testing is specified.

The handoff does not authorize replacing the existing scheduler, authentication, API clients, review
flow, or unrelated screens. Use the production theme and routes after inspecting current code.

## Reference material

- [Selected Anki A](references/ANKI-A-normal-selected.png) preserves the existing deck-picker
  structure and adds deterministic artwork to the left of each deck title; the newer user-approved
  board above takes precedence for Anki row and queue-state presentation.
- [Selected WaniKani A](references/WANIKANI-A-normal-selected.png) shows two open, stacked sections
  for Reviews and New cards.
- [Previous Anki screen](references/ANKI-existing-screen.png) and [previous WaniKani
  screen](references/WANIKANI-existing-screen.png) provide app-specific navigation and scale context.
- [Selected session completion](references/SESSION-completion-selected.png) is a separate completion
  destination. Hub zero states do not replace it.
- `examples/` contains light/dark prototype captures for visual reference. These captures use
  illustrative fixture data; they are not production screenshots or implementation evidence.

## Implementation documents

- [Implementation handoff](IMPLEMENTATION_HANDOFF.md) contains the approved layouts, state behavior,
  integration boundaries, and accessibility requirements.
- [State matrix](STATE_MATRIX.md) records queue and completion distinctions.
- [Procedural deck art](PROCEDURAL_DECK_ART.md) specifies the stable pastel SVG artwork required for
  Anki rows.

The sample counts, dates, and browser previews are fabricated examples. Production labels and
numbers must come from current provider state. Unknown, stale, partial, and empty queues remain
distinct.
