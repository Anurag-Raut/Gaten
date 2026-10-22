# V4 hub captures — installed debug APK

Captured on `emulator-5554` (1080×2340, font scale 1.0) from `app/build/outputs/apk/debug/app-x86_64-debug.apk`
built from the V4 hub implementation. Light theme and dark theme, both hubs, real provider data
(AnkiDroid 5 due in `Default`; WaniKani 23 reviews due, 77 lessons available). No review, lesson or grade was
submitted.

| File | Screen | Theme |
|---|---|---|
| `anki-ready-light.png` | Anki — `Choose a deck`, one deck row, accent due count | light |
| `anki-ready-dark.png` | Anki — same state, dark palette (deck art changes tones only) | dark |
| `wanikani-ready-light.png` | WaniKani — `Available study`: `Reviews` and `Lessons` rows | light |
| `wanikani-ready-dark.png` | WaniKani — same state, dark palette | dark |

What the captures verify against the V4 package (`docs/unified-ink-hubs-v4/`):

- Shared row grammar in both hubs: 48×48 art/icon holder, title + subline + one extra line, trailing
  accent count with its caption, 22 dp chevron, 1 dp hairline divider under the section caption and
  under each row; no enclosing card, no green wash.
- WaniKani has exactly two whole-row-tappable rows under one `Available study` caption; both black
  `Start reviews` / `Start lessons` buttons are gone; the lessons row is titled `Lessons`.
- Anki keeps `Anki` → `Choose a deck` → due summary → `Available decks` → one row per deck with 48×48
  procedural art seeded by the stable deck id.
- Dark mode changes only the palette: the procedural art keeps its identity, the count stays on the
  reader's accent (Blue here), and the muted text uses the V4 dark token (`#A7A7B0`).

Not captured here (no fixture entry point in this build): loading skeletons, offline/stale, locked
lessons, permission failure, one-deck-cleared and all-clear states. Those are covered by the model and
store tests (`tests/wanikani-hub.test.cjs`, 50 cases) and by the prototype screenshots in
`docs/unified-ink-hubs-v4/screenshots/`.