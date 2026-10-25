# Gaten unified Ink hubs — V4 (21 October 2026)

This is the user-supplied *Gaten unified Ink hubs V4* agent package, kept in the repository so the
hub specification stays readable next to the screens it governs. It is the current screen-specific
visual authority for **both** provider hubs: Anki's deck picker and WaniKani's study hub now share
one row grammar, and WaniKani's earlier black Start buttons are gone. Read it with
[Gaten / Ink v1](../Gaten_Ink_Design_Language_v1.md) (the app-wide authority) and the
[implementation map](../design-language.md).

The [25 October study hub refinement](../design-language.md#study-hub-refinement-25-october-2026)
supersedes this frozen prototype's fixed-green completion marks, unconditional chevrons and
refresh-only stale-row actions, and adds separate daily activity figures. The prototype files remain
unchanged as a record of the supplied V4 design.

## What is in the package

| File | Use |
| --- | --- |
| [`index.html`](index.html) | The prototype that is the latest visual authority. Dropdowns select each Anki/WaniKani state, light/dark and accent. Open it in a browser; it needs nothing else in this folder. |
| `AGENT_START_HERE.md` | The package's own reading order for an incoming agent (it still names WaniKani's second row `New cards`; see the deviations below). |
| `DESIGN_SPEC.md` | Sizes, tokens, components and copy for the unified rows. |
| `STATE_MATRIX.md` | Every Anki and WaniKani state and what it must say. |
| `AGENT_IMPLEMENTATION_PROMPT.md` | The original coding mandate and acceptance list. |
| `QA_VALIDATION.md` | What the package's own browser preview pass covered, and what it did not. |
| `STATUS.md` | What the app implements today, read out of the working tree, plus the deviations taken on purpose. |
| [`screenshots/`](screenshots/) | The prototype's own captures: 8 state pairs in light and dark, `light-<state>-<state>.png` and `dark-<state>-<state>.png`. Fixture data, not app screenshots. |
| `references/` | Earlier approved boards. Their WaniKani buttons and rounded Anki cards are superseded by `index.html`. |
| `assets/` | The prototype's own SVG marks (chevron, check, provider glyphs, refresh, offline) and its `deck-art-examples/`. `index.html` embeds the marks it draws, so nothing here has to be fetched to view it. |
| `src/deck-art.ts`, `src/deck-art.js`, `docs/PROCEDURAL_DECK_ART.md` | The deterministic deck artwork the app reimplements in `src/rn/ui/review/deck-art.ts` and `DeckThumbnail.tsx`. |
| `tests/`, `fixtures/` | The package's own artwork test and the fixture scenarios. |

`docs/Gaten_Ink_Design_Language_v1.md` inside this package is **not** the authority: its copy predates
two unrelated app decisions (the Settings subtitle and the tab names). Use
[the repository copy](../Gaten_Ink_Design_Language_v1.md).

## Where the app implements it

| Spec | Implementation |
| --- | --- |
| Shared row grammar, `SuccessCheck`, cleared rows, chevrons, accessibility labels | `src/rn/ui/review/review-ui.tsx` (`StudyRow`, `DeckRow`, `SuccessCheck`) |
| WaniKani's two rows, clear/locked/loading/failure states and copy | `src/rn/ui/review/WaniKaniHome.tsx`, `src/rn/ui/review/wanikani-hub-model.ts` |
| Anki deck list, due summary, all-clear notice, loading skeleton, filtered-empty state | `src/rn/ui/review/AnkiDeckPicker.tsx` |
| Stable per-deck artwork (FNV-1a id → palette, motif, tilt, phase) | `src/rn/ui/review/deck-art.ts`, `src/rn/ui/review/DeckThumbnail.tsx` |
| Provider reads, stale/offline/failed snapshots, paused sessions, completion receipts, post-session refresh | `src/rn/ui/review/review-store.ts` |
| Behaviour and state regressions | `tests/wanikani-hub.test.cjs`, `tests/deck-art.test.cjs` |

## Known deviations from the prototype

- During Anki's first read the subtitle becomes a spinner with `Syncing with Anki…` instead of the
  prototype's fixture count; this is deliberate, because the prototype's `31` is sample data and a
  count is never shown before it is read.
- The Anki row floor is 90 dp against the prototype's 93 (inside the spec's 86–94 dp range), and the
  check disc is 22 dp against the prototype's 21. The loading skeleton is drawn in that same 90 dp
  hairline geometry as the loaded rows, while the prototype's `.skrow` is 83 dp.
- The second WaniKani row is titled `Lessons`, where the prototype says `New cards`. Only the title
  differs: the subline stays `N lessons available`, the trailing count keeps its `ready` caption, and
  the row's accessibility label is spoken from the same title (`Lessons, 77 ready, open lessons`).
  The package's own files are kept verbatim, so `DESIGN_SPEC.md`, `STATE_MATRIX.md`,
  `AGENT_IMPLEMENTATION_PROMPT.md` and the prototype's own markup still read `New cards`.

## QA still owned by the app, not by this package

The package's own pass was browser-only. The Android emulator checks its `QA_VALIDATION.md` defers —
320/340/390/430 dp widths, 100/130/160 % type, TalkBack labels and row hit bounds, real AnkiDroid and
WaniKani data, offline and permission failures, and artwork stability across reloads and theme
changes — are the app's acceptance work, recorded with the hub captures under
`artifacts/visual-review/`.
