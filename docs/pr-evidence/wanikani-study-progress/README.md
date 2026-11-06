# WaniKani study page — device evidence

Two sections replace one that repeated itself. **Today's progress** carries the two device-local
counts over **one** stacked bar of the day's completed work — reviews and lessons as two segments,
each keyed by a swatch on its own caption — and **Available study** carries the Reviews and Lessons
cards, each with its own count. The two labelled bars, their captions, the daily-goal line and the
Available-study distribution legend are all gone.

## Source under test

| | |
| --- | --- |
| Base | `9350181` — tip of `origin/dev` at branch time (`dev` is the repo's integration branch; `.github/DEPLOYMENT.md:11-12`) |
| On the branch | `1e9aace` — the two-section page as first committed |
| Captured head | that commit **plus the uncommitted revision** that replaces the two bars with one stacked bar; 2 changed files |
| Captured head files | `src/rn/ui/review/WaniKaniHome.tsx` `8655fe23acf7febdbd805bdc26adbce80e1afa6ba5339a3c45f294391dbe84d1` · `src/rn/ui/analytics/ink.tsx` `ed9ef05ff51f56ba20c17e2f4d9ebab293460a92641574e4b16170ffb5ba7fab` |
| Earlier files, unchanged by the revision | `src/rn/ui/review/wanikani-hub-model.ts` `efb8e5e067efbf4a751d4657f75943bf8a4dd65e271367a89381be7fc4094a3c` · `tests/wanikani-hub.test.cjs` `c35d9b18cc305891927476922bde27a6fd84c7041a1f86f76818b3c8d73452ec` |
| Production diff hash | `git diff 9350181 -- <the four files> \| sha256sum` = `85426fbfc250c89ad1c60edb61ffd308e3c204c85b9df185530d90361b3e691f` |
| Whole-tree diff hash | `git diff 9350181 \| sha256sum` = `eff7d0351de4fac89246c3ad6928ead9df77bd5cf29b4978f2dbf83d4f35fd82` (includes this evidence directory) |

The revision is a layout change plus an accessibility-only one:

- `WaniKaniHome.tsx` — one `StackedBar` under the figures, replacing the two labelled `InlineBar`s,
  their captions and the separate goal line; the daily limit is no longer read here at all.
- `ink.tsx` — `MetricBlock` takes an optional decorative `swatch`. With no swatch the caption renders
  its original geometry (same 5 dp step, same type), so existing callers are untouched; `StackedBar`
  takes an optional `onSelect` and an `accessibilityLabel` override so the bar can be a single static
  accessibility node with no hit layer when a page does not want selection.

The commit SHA replaces the diff hash in the pull request once the revision is committed.

## Build

```sh
flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug \
  -PreactNativeArchitectures=x86_64 --max-workers=2 --console=plain
```

Built in isolated worktrees — base sources for the BEFORE pair, head sources for the AFTER pair.
Debug variants embed `assets/index.android.bundle` (`app/build.gradle.kts` sets
`debuggableVariants = emptyList()`), so no Metro server was involved.

| APK | Size | APK sha256 | embedded bundle sha256 |
| --- | --- | --- | --- |
| before-fixture | 207 820 927 | `c06db074d2e916e90a395431c05b98ca7a8b9fc5295721e44192cb8d6d558eec` | `076936eb2e2da2c32d714e97133de172a8e05b2f63a7afe4120844f425b8eb3b` |
| before-production | 208 351 819 | `56f93104630ecb0e6f4a785f5248dea185f5cf13aab95909aa1876f2870b267e` | `9369de5075c91c0d7bb46917deb2a5e308265a1db27d9dde4a5d0b670ebf195b` |
| after-fixture | 207 821 467 | `c09ee0cef422b2e1faf5bc0c4673a99b4f19264fa402895e672a0edd46e98070` | `f648b72a353d48704305fe10d4640cc260d9cc4d334dca97674c2342cdcad2e6` |
| after-production | 208 352 199 | `a6c00619650bb556f53668b5d53ba125b07570e5c0a208cac207f044c26c4803` | `9e55e0078c8fcb8f317436a73a42f308ce008633babe1f58892dcf96c3cd7910` |
| swatch-before-production (regression baseline) | 208 352 723 | `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4` | `42d8c1b391b634fd73459fc21004f2a1d55f5ad749d66573627f72326dd8f8b7` |

`swatch-before-production` is the previous `after-production` build (the design committed as
`1e9aace`): same everything except the revision, so it is the before/after pair for the shared
`Metric` change. The installed `base.apk` was checked with `sha256sum` on the device for every
capture — `c09ee0ce…` for `05`–`09`, `a6c00619…` for `10`–`14`, and `06668b2d…` / `a6c00619…` for
the two Analytics stills.

Bundle-content checks on the extracted `assets/index.android.bundle` (Hermes stores non-ASCII
strings as UTF-16LE, so a UTF-8-only search misses them):

| Marker | before-fixture | after-fixture | before-production | after-production |
| --- | --- | --- | --- | --- |
| `Daily streak`, `Recent lookups` (app shell) | absent | absent | present | present |
| `anki-ready`, `gallery` (fixture harness) | present | present | absent | absent |
| `Today’s progress` | absent | present (UTF-16LE) | absent | present (UTF-16LE) |
| `Nothing completed yet today.` | absent | present (UTF-8) | absent | present (UTF-8) |
| `Your study` / `Available study` | present | present | present | present |

The fixture scenes are keyed by a name derived at runtime from the launch extra, so no `wkMixed`
literal exists in the bundle; the injected values are confirmed by the captures themselves — the
stills show 28 reviews / 47 lessons and 27 / 3 completed today, which is the injected state and not
the harness default (23 / 15).

## Device

| | |
| --- | --- |
| Device | Android emulator `emulator-5554`, AVD `Gaten_Lookup_Review`, Android 14 (API 34), `sdk_gphone64_x86_64` |
| Display | 1080 × 2340 @ 440 dpi, system `font_scale` 0.94 (the device's own value, unchanged) |
| Theme | dark (`cmd uimode night` → `yes`) |
| Account | the project's existing connected WaniKani test account; the token file was never read or printed, and its presence was asserted after every install |
| Installed at release | `after-production.apk`, `a6c00619650bb556f53668b5d53ba125b07570e5c0a208cac207f044c26c4803` |

Installs used `install -r` throughout — never `uninstall`, never `pm clear`. No review was submitted
and no lesson was started.

## Files

| File | Shows |
| --- | --- |
| `05-after-fixture-mixed-dark.png` | **Primary.** After, synthetic 27 reviewed / 3 learned, dark — the two figures with their swatches over one stacked bar whose reviews segment is ~90% and lessons ~10%, then the two queue cards. |
| `06-after-fixture-mixed-light.png` | The same state in light. |
| `07-after-fixture-today-dark.png` | After, synthetic 27 / 0 — a single full-width reviews segment. |
| `08-after-fixture-zero-dark.png` | After at 0 / 0 — the bar's neutral track, no narrative line. |
| `09-after-fixture-unknown-dark.png` | After with the counters stamped with yesterday — a dash and its reason for each figure, and no bar at all, so unknown is distinct from zero. |
| `10-real-after-study-dark.png` | Real app, production bundle at the captured head, connected account — `Today's progress` present at 0 / 0 and the cards carrying `89 due` / `47 ready` once each. |
| `11-analytics-before-swatch.png` | Regression baseline: the Analytics screen on the pre-revision bundle (shared `Metric` blocks, no swatch). |
| `12-analytics-after-swatch.png` | The same screen on the revised bundle. |
| `13-analytics-diff-only-timestamp.png` | The amplified pixel difference between `11` and `12`. |
| `14-after-study-open.mp4` | Real app, Home → WaniKani card → the study page. |
| `01-before-fixture-today-dark.png`, `02`-light, `03`-zero-dark, `04-real-before-study-dark.png` | The pre-PR BEFORE references: the reported page (27 / 0 with `Learned today · 0 of 15` and the duplicated `28 Reviews / 47 Lessons` legend), its light variant, its zero state where the whole section disappeared, and the real account where the page shows no `Today` section and prints both counts twice. |

## What is real and what is synthetic

- **`04`, `10`, `11`, `12`, `14`** are the shipped entry point, launched normally on the connected
  test account; the counts (89 reviews due, 47 lessons) are the account's own.
- **`01`–`03`, `05`–`09`** render the same `WaniKaniHome` component from a **synthetic** hub state
  injected through the repo's inert visual-review harness. They exist because a live account cannot
  show completed activity without submitting real reviews, and because the device-local counters
  reset at local midnight.
  - Harness: `artifacts/visual-review/hubs-procedural/fixtures.tsx` plus an evidence-only entry
    (`index.js.fixture`) that registers the fixture as the `Gaten` component; the production entry
    was restored before the production captures. The harness is wrapped in a `NavigationContainer`
    because the chart primitives call `useIsFocused()` (`ink.tsx:326`) — a harness gap, not an app
    defect, since in the app they always render inside the navigator.
  - Injection patch: `fixture-injection.patch` (sha256
    `4dceb9092d82d097befd36fe75e4edc1d50f96afee3ab9da9513564f0a29bc79`), applied only inside the
    throwaway build worktree — **no fixture change is part of the pull request**.
  - Scenes: `wkMixed` (`reviewedToday: 27`, `learnedToday: 3`, `dailyLimit: 15`, `dueNow: 28`,
    `lessonsNow: 47`) so both segments carry width, `wkToday` (27 / 0), `wkZero` (0 / 0), `wkUnknown`
    (counters stamped with yesterday).

## Shared-primitive regression check

The revision adds an optional `swatch` to `Metric`/`MetricBlock`, which the Analytics screen also
renders. Both builds were opened to Analytics and captured with the same device, theme and font
scale: the only differing pixels are the relative timestamp glyph ("Updated 1m ago" vs "Updated
2m ago") — **220 of 2 397 600 pixels (0.009%)** below the status bar, confined to an 18 × 26 px box
at (208, 354)–(226, 380). Every metric block, ring and chart on the screen is pixel-identical, so
callers that pass no swatch render exactly as before. `13-analytics-diff-only-timestamp.png` is that
difference amplified 12×.

## Limitations

- The live account has no completed items today, so the real-app still is the zero state and the
  stacked bar's segments are evidenced only by the labelled synthetic stills.
- The recording is a screen capture of navigation and arrival (Android encodes a frame only when the
  screen changes), not a frame-perfect animation study; the new bar is static by design.
- Not exercised: large system font scales, TalkBack, landscape, and the light theme on a real
  account. Captures use the emulator's own 0.94 font scale.
- The Analytics regression pair is a whole-screen compare on one device; it does not enumerate every
  screen that renders a `Metric` block.
