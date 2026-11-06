## Changes

The WaniKani study page said the same two numbers three times. Its `Today` block hid itself whenever
both counters were zero, and its `Available study` block drew a distribution bar with a legend
("28 Reviews · 47 Lessons") directly above two cards that each already showed the same count. The
page is now two sections that each say one thing.

- **Today's progress** (`src/rn/ui/review/WaniKaniHome.tsx`) — the two device-local counts (Items
  reviewed / Lessons learned), each caption led by a small swatch in its own segment's accent weight,
  over **one** stacked horizontal bar that breaks the day's completed work into reviews and lessons.
  The two labelled bars, their captions and the separate daily-goal line are gone. A zero day draws
  the bar's neutral track; an unread figure keeps its dash and its reason and draws no bar at all, so
  unknown stays distinct from zero.
- **Available study** — the two queue cards alone, Reviews then Lessons, each carrying its own count.
- `src/rn/ui/analytics/ink.tsx` — `MetricBlock` gains an **optional decorative `swatch`**; with no
  swatch the caption keeps its original geometry, so existing callers are untouched. `StackedBar`
  gains an optional `onSelect` and an `accessibilityLabel` override, so a page can render it as one
  static accessibility node with no hit layer and no fake press targets.
- `src/rn/ui/review/wanikani-hub-model.ts` and `tests/wanikani-hub.test.cjs` were updated with the
  first commit of this work; the revision above touches only the two files named in `Source`.

## Visual evidence

- **UI changed:** Yes.
- **Screenshots:** `Today's progress` with a mixed day — 27 reviews and 3 lessons completed, so both
  segments carry width — rendered by the app on the connected account:

  ![After — Today's progress with the day's completed reviews and lessons in one bar](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/05-after-fixture-mixed-dark.png)

  <details>
  <summary>Before, the other states, light theme, the real-app still, and the regression check</summary>

  **Before** — the reported page, where the `Today` block vanishes at zero and both counts appear
  twice (the `28 Reviews / 47 Lessons` bar and legend, then the cards):

  ![Before — the reported page](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/01-before-fixture-today-dark.png)

  **Real app, production bundle, connected account** — before, with no `Today` section at all:

  ![Real app before](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/04-real-before-study-dark.png)

  and after:

  ![Real app after](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/10-real-after-study-dark.png)

  **A day with reviews but no lessons** — one full-width segment:

  ![After — 27 reviews, 0 lessons](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/07-after-fixture-today-dark.png)

  **Zero** — the neutral track, with both figures reading 0:

  ![After — nothing completed today](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/08-after-fixture-zero-dark.png)

  **Unknown** — counters stamped with yesterday: a dash and its reason, no bar:

  ![After — today's figures unknown](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/09-after-fixture-unknown-dark.png)

  **Light theme:**

  ![After — light theme](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/06-after-fixture-mixed-light.png)

  **Regression check for the shared `Metric` change.** The Analytics screen renders the same
  primitives. Opening it on the pre-revision bundle and on this one, on the same device, theme and
  font scale, leaves **220 differing pixels out of 2 397 600 (0.009%)** below the status bar — all of
  them the relative timestamp glyph ("Updated 1m ago" vs "Updated 2m ago") in an 18 × 26 px box. Every
  metric block, ring and chart is pixel-identical, so callers that pass no swatch are unchanged:

  ![Analytics before](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/11-analytics-before-swatch.png)

  ![Analytics after](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/12-analytics-after-swatch.png)

  ![The amplified difference: only the timestamp glyph](https://raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/13-analytics-diff-only-timestamp.png)

  **Provenance.** Build, per-APK and per-bundle hashes, device, bundle-content checks and the
  synthetic-scene harness are recorded in
  `docs/pr-evidence/wanikani-study-progress/README.md`.

  </details>

- **Interaction, navigation or motion:** [Screen recording of the page opening from Home on the real
  app](https://github.com/Anurag-Raut/Gaten/blob/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/14-after-study-open.mp4).
  The bar is static by design, so this shows arrival and layout, not an animation.
- **Source:** captured head is the working tree on top of `1e9aace`, based on `dev` `9350181`:
  `src/rn/ui/review/WaniKaniHome.tsx` `8655fe23acf7febdbd805bdc26adbce80e1afa6ba5339a3c45f294391dbe84d1`
  and `src/rn/ui/analytics/ink.tsx` `ed9ef05ff51f56ba20c17e2f4d9ebab293460a92641574e4b16170ffb5ba7fab`,
  production diff `85426fbfc250c89ad1c60edb61ffd308e3c204c85b9df185530d90361b3e691f` against the base;
  the committed SHA replaces this once the revision is committed. Device: Android emulator
  `emulator-5554`, AVD `Gaten_Lookup_Review`, Android 14 (API 34), 1080 × 2340 @ 440 dpi, dark, system
  font scale 0.94. Build: `flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug
  -PreactNativeArchitectures=x86_64 --max-workers=2`, debug variant with the JS bundle embedded (no
  Metro). APK sha256: after `a6c00619650bb556f53668b5d53ba125b07570e5c0a208cac207f044c26c4803`, before
  `56f93104630ecb0e6f4a785f5248dea185f5cf13aab95909aa1876f2870b267e`, synthetic after
  `c09ee0cef422b2e1faf5bc0c4673a99b4f19264fa402895e672a0edd46e98070`, synthetic before
  `c06db074d2e916e90a395431c05b98ca7a8b9fc5295721e44192cb8d6d558eec`, and the pre-revision Analytics
  baseline `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4`. The installed
  `base.apk` was verified on-device with `sha256sum` for every capture. Not exercised: large font
  scales, TalkBack, landscape, light theme on a real account.
- **Assets:** `docs/pr-evidence/wanikani-study-progress/` — README with every hash, fourteen captures,
  and `fixture-injection.patch` documenting the synthetic-scene harness used only inside the throwaway
  build worktree; it is not production code.
- **Blocked capture:** None. The test account has no completed items today, so the real-app still is
  the zero state and the bar's segments appear only in the labelled synthetic stills.

## Verification

- **Steps and result:** Built and installed the base and head bundles, opened the WaniKani study page
  from Home on the connected account before and after, and captured stills plus a recording; the
  mixed, reviews-only, zero and unknown days were then captured from synthetic hub state in the same
  component in both themes; the Analytics screen was captured on the pre-revision and revised bundles
  and compared pixel-by-pixel.
- **Checks:** `tsc --noEmit` exits 0 on the captured head. `./gradlew :app:assembleDebug
  -PreactNativeArchitectures=x86_64` is BUILD SUCCESSFUL for every APK in the table, and each
  installed `base.apk` hash was confirmed on-device. `node --test tests/wanikani-hub.test.cjs` on Node
  v24.19.0 reports `tests 68, pass 24, fail 44` — **identical on the pristine base `9350181` and on
  the head**, with a byte-identical set of 44 failing test names and one pre-existing cause,
  `Error: Unexpected import: ./wanikani-romaji`, raised by the test file's own TypeScript loader. The
  revision itself changes only view code, so that suite was not re-run for it; `tsc` and the Android
  build cover it.

## Problems

- The 44 `wanikani-hub.test.cjs` failures are a pre-existing harness defect (the test's inline TS
  loader cannot resolve `./wanikani-romaji`), present identically at the base commit and out of scope
  here.
- The real-app still is the zero state because the test account has no in-app activity today; the
  non-zero bar is evidenced only by the synthetic stills, which the body labels as such.
