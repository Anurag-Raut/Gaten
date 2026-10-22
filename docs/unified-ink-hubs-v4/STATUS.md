# Implementation status — unified Ink hubs V4

Read out of the working tree on 21 October 2026. This file records what the code does and the
deviations taken on purpose; the package itself is indexed in [README.md](README.md), and
[the implementation map](../design-language.md) stays the app-wide source of precedence.

## What the app implements now

- **One shared row.** `StudyRow`, `SectionCaption` and `SuccessCheck` live in
  `src/rn/ui/review/review-ui.tsx`. A row is a leading 48 dp art or icon holder, the title with its
  subline and at most one `Label · value` line, then the trailing lane — the count in the reader's
  accent with its caption, or the green check — and a 22 dp chevron. The whole row is one target of
  48 dp or more with one accessibility label and no button nested inside it.
- **WaniKani** (`WaniKaniHome.tsx`, `wanikani-hub-model.ts`): back bar, `Your study`, one
  `Available study` caption and exactly two whole-row-tappable rows, `Reviews` and `Lessons`
  (17 dp titles, a 24 dp provider glyph in the 48 dp neutral holder) at a 110 dp floor — 118 dp
  below 340 dp width or at 150 % type. Tapping a row opens the review or lesson flow, retries a
  failed read, or opens the connection screen. There are no black Start buttons, no count split, no
  tabs, no `Coming up` section and no summary line. When both queues are clear both rows stay
  visible under one compact `You’re all caught up` notice.
- **Anki** (`AnkiDeckPicker.tsx`): `Choose a deck` with the real due total, `Available decks`, then
  one row per deck at a 90 dp floor — 48 × 48 procedural art, the name, AnkiDroid's own counts, and
  the accent due total or the check with `Clear for now` / `Completed today`. A deck with nothing
  due leads nowhere; a deck with work due always opens its session. No count is stated before it is
  read, and an all-clear list keeps every row visible under the same compact notice.
- **The one green mark.** `SuccessCheck` is a 22 dp disc: `#238858` on light with a white tick,
  `#69C796` on dark with a near-black `#17171A` tick. Nothing else in either hub is green, and
  completion is always carried by its words as well as its colour.
- **Truthful states.** Loading is a neutral skeleton rather than a zero: both hubs draw it in the
  shared row's hairline geometry — a 1 dp divider at the row's own floor, no card, no radius, no
  fill. A zero is never completion. A cached or failed WaniKani snapshot states its caveat under
  the row it belongs to (`Offline · saved count may be outdated. Reconnect before starting.` or
  `Refresh failed · showing saved count`), said once when both rows would otherwise repeat the same
  words. The AnkiDroid read is local, so the deck list carries no cache label, and a failed read
  hands off to the store's full-screen blocked step rather than showing an empty list.

## Deliberate deviations

- The art and icon holder is a square 48 dp at radius 12, superseding the older board's 56 × 72 dp
  Anki tiles.
- The trailing caption belongs to the numeric count only: it is never drawn under the green check.
- The stale or offline caveat is one deduplicated line drawn under the last row that carries it,
  rather than a hub-level line after the list.
- Anki's first read replaces the subtitle with a spinner and `Syncing with Anki…`; the prototype's
  count there is sample data and a count is never invented.
- The second WaniKani row is titled `Lessons`, where the package and the prototype say `New cards`.
  Only the title changed: the subline stays `N lessons available`, the trailing count keeps its
  `ready` caption, and the accessibility label `rowLabel()` speaks the same title
  (`Lessons, 77 ready, open lessons`). The package's own files are kept verbatim, so `DESIGN_SPEC.md`,
  `STATE_MATRIX.md`, `AGENT_IMPLEMENTATION_PROMPT.md` and the prototype's markup still read
  `New cards`.

`README.md` lists the remaining places where the drawn pixels differ from the prototype — the 90 dp
Anki row floor against 93 (the loading skeleton included) and the 22 dp check disc against 21.

## Verification

This record was taken by reading the files named above; no code changed for it. The local gates and
the emulator acceptance work the package defers — widths, type scale, TalkBack labels, live
AnkiDroid and WaniKani data, offline and permission failures, artwork stability — belong to the
implementation session, which records its captures under `artifacts/visual-review/`.
