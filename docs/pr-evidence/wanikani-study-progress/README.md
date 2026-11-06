# WaniKani study page — device evidence

Two sections replace one that repeated itself. **Today's progress** carries the two device-local
counts and a horizontal bar chart of the day's mix; **Available study** carries the Reviews and
Lessons cards, each with its own count. The bar-and-legend that restated both counts directly above
those cards is gone, and the Today section now stays visible at zero instead of disappearing.

## Source under test

| | |
| --- | --- |
| Base | `9350181` — tip of `origin/dev` at branch time (`dev` is the repo's integration branch; `.github/DEPLOYMENT.md:11-12`) |
| Head | **uncommitted working tree** at capture time, 3 files, `+164/−152` |
| Head diff hash | `git diff 9350181 \| sha256sum` = `776076f72f28a85a371737da587a4f179a5a57ccf7184ce538992c7003d410e2` |
| Files | `src/rn/ui/review/WaniKaniHome.tsx` `8385631d5b36572e351a74ea996b38c4ec4e5eacf4db2caac47939f97dc9477a` · `src/rn/ui/review/wanikani-hub-model.ts` `efb8e5e067efbf4a751d4657f75943bf8a4dd65e271367a89381be7fc4094a3c` · `tests/wanikani-hub.test.cjs` `c35d9b18cc305891927476922bde27a6fd84c7041a1f86f76818b3c8d73452ec` |

The only production edit after the first evidence pass is an accessibility-only correction to the
chart's spoken label (`WaniKaniHome.tsx`): the nonzero label now speaks percentage shares
(`"${reviewedPercent}% reviews and ${learnedPercent}% lessons of today's completed activity."`, one
rounding plus its complement, so the two never total 101%) instead of repeating the counts the metric
pair already speaks. No JSX, style or pixel change.

The commit SHA replaces the diff hash in the pull request once the commit is authorized.

## Build

```sh
flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug \
  -PreactNativeArchitectures=x86_64 --max-workers=2 --console=plain
```

Built in an isolated detached worktree at `9350181` for the BEFORE pair and with the head working
tree for the AFTER pair. Debug variants embed `assets/index.android.bundle`
(`app/build.gradle.kts` sets `debuggableVariants = emptyList()`), so no Metro server was involved.

| APK | Size | APK sha256 | embedded bundle sha256 |
| --- | --- | --- | --- |
| before-fixture | 207 820 927 | `c06db074d2e916e90a395431c05b98ca7a8b9fc5295721e44192cb8d6d558eec` | `076936eb2e2da2c32d714e97133de172a8e05b2f63a7afe4120844f425b8eb3b` |
| before-production | 208 351 819 | `56f93104630ecb0e6f4a785f5248dea185f5cf13aab95909aa1876f2870b267e` | `9369de5075c91c0d7bb46917deb2a5e308265a1db27d9dde4a5d0b670ebf195b` |
| after-fixture | 207 821 739 | `ea54df17c6f9c879f96685142e0fa327a29ffea4a60c93aa83e9dd6b5ddd715d` | `3abbd66351ff23eb711da356f069fc6c763c09e533acb0d9924e6b659c6db591` |
| after-production | 208 352 723 | `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4` | `42d8c1b391b634fd73459fc21004f2a1d55f5ad749d66573627f72326dd8f8b7` |

The fixture pair was rebuilt after the harness fix described below; the AFTER pair was rebuilt after
the accessibility correction. The production pair's APK hashes were confirmed on-device with
`sha256sum` of the installed `base.apk` before and after capture:
`56f93104…` (before) and `06668b2d…` (after).

Bundle-content checks on the extracted `assets/index.android.bundle` (Hermes stores non-ASCII
strings as UTF-16LE, which is why a UTF-8-only search misses them):

Which entry point each APK carries — app shell versus fixture harness:

| Marker | before-fixture | after-fixture | before-production | after-production |
| --- | --- | --- | --- | --- |
| `Daily streak`, `Recent lookups` (app shell) | absent | absent | present | present |
| `anki-ready`, `gallery` (fixture harness) | present | present | absent | absent |

Which copy each APK carries:

| String | before-fixture | after-fixture | before-production | after-production |
| --- | --- | --- | --- | --- |
| `Today’s progress` | absent | present (UTF-16LE) | absent | present (UTF-16LE) |
| `Share of today’s completed activity` | absent | present (UTF-16LE) | absent | present (UTF-16LE) |
| `Daily lesson goal ·` | absent | present (UTF-16LE) | absent | present (UTF-16LE) |
| `Nothing completed yet today.` | absent | present (UTF-8) | absent | present (UTF-8) |
| `of today's completed activity.` (spoken percentage label) | absent | present (UTF-16LE) | absent | present (UTF-16LE) |
| `Your study` / `Available study` | present | present | present | present |

The fixture scenes are keyed by a name derived at runtime from the launch extra, so no `wkToday`
literal exists in the bundle; the injected values are confirmed instead by the captures themselves —
the fixture stills show 28 reviews / 47 lessons and 27 reviewed today, which is the injected state
and not the harness default (23 / 15).

## Device

| | |
| --- | --- |
| Device | Android emulator `emulator-5554`, AVD `Gaten_Lookup_Review`, Android 14 (API 34), `sdk_gphone64_x86_64` |
| Display | 1080 × 2340 @ 440 dpi, system `font_scale` 0.94 (the device's own value; left unchanged) |
| Theme | dark (`cmd uimode night` → `yes`) |
| Account | the project's existing connected WaniKani test account on this emulator; token presence asserted after every install, and the token file was never read or printed |
| Installed at release | `after-production.apk`, `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4` |

Installs used `adb install -r` throughout — never `uninstall`, never `pm clear`; the stored token
survived every swap. No review was submitted and no lesson was started.

## Files

| File | Shows |
| --- | --- |
| `01-before-fixture-today-dark.png` | BEFORE, synthetic 27/0, dark — the reported page: a `Today` block (27 / 0, `Learned today · 0 of 15`) and an `Available study` bar with the legend `28 Reviews / 47 Lessons` sitting directly above cards that repeat `28 due` / `47 ready`. |
| `02-before-fixture-today-light.png` | The same state in light. |
| `03-before-fixture-zero-dark.png` | BEFORE at 0/0 — the whole `Today` block is absent, so nothing says the day is zero rather than unknown. |
| `04-after-fixture-today-dark.png` | AFTER, same 27/0 state, dark — `Today's progress` with 27 / 0, two labelled bars (`Reviews` full, `Lessons` empty), `Share of today's completed activity`, `Daily lesson goal · 15 lessons`; `Available study` is the two cards alone. |
| `05-after-fixture-today-light.png` | The same state in light. |
| `06-after-fixture-zero-dark.png` | AFTER at 0/0 — `Today's progress` stays, showing 0 / 0 and `Nothing completed yet today.` with the goal line, and no bars. |
| `07-after-fixture-unknown-dark.png` | AFTER with the counters stamped with yesterday — the figures stay a dash with their reason and no bars are drawn, so "unknown" is distinct from "zero". |
| `08-real-before-study-dark.png` | Real app, production bundle at the base, connected account — the actual before: 89 reviews due / 47 lessons, no `Today` section at all, and the count pair repeated twice (bar legend, then the cards). |
| `09-real-after-study-dark.png` | Real app, production bundle at the head, same account — `Today's progress` present (0 / 0 and `Nothing completed yet today.`, because nothing was reviewed in-app today) and the `Available study` cards carrying `89 due` / `47 ready` once each. |
| `10-real-after-study-open.mp4` | Real app, 5.7 s, 1080 × 2340: Home → WaniKani card → the study page, showing the new section arriving in place of the old duplicated one. |

## What is real and what is synthetic

- **`08-real-*`, `09-real-*`, `10-real-*`** are the shipped entry point, launched normally on the
  connected test account; the counts (89 reviews due, 47 lessons) are the account's own.
- **`01`–`07`** render the same `WaniKaniHome` component from a **synthetic** hub state injected
  through the repo's inert visual-review harness. They exist because the 27/0 state cannot be
  produced on a live account without submitting real reviews, and because the device-local counters
  reset at local midnight.
  - Harness: `artifacts/visual-review/hubs-procedural/fixtures.tsx` plus an evidence-only entry
    (`index.js.fixture`) that registers the fixture as the `Gaten` component. The production
    `index.js` was restored before the production captures and is untouched in the repository.
  - Injection patch: `fixture-injection.patch` (sha256
    `8b462e733155c9068eb2c7990f21e4445e318a0a367559e451180f2e6f9c24a9`), applied only inside the
    throwaway build worktree — **no fixture change is part of the pull request's production code**.
  - The patch also wraps the fixture root in a `NavigationContainer`: the chart primitives call
    `useIsFocused()` (`src/rn/ui/analytics/ink.tsx:326`), which throws outside a navigation context.
    That is a harness gap, not an app defect — in the app these primitives always render inside the
    navigator.
  - Scenes: `wkToday` (`reviewedToday: 27`, `learnedToday: 0`, `dailyLimit: 15`, `dueNow: 28`,
    `lessonsNow: 47`), `wkZero` (same queues, both counters 0), `wkUnknown` (counters stamped with
    yesterday, so today's figures read as unknown rather than zero).

## Limitations

- The live account had **no completed items today**, so the bar chart draws no bars in the real-app
  still and the entrance cannot be seen in the real-app recording; the bars are shown only in the
  synthetic stills (`04`, `05`), which are labelled as such.
- The recording is a screen capture of navigation and arrival, not a frame-perfect animation study
  (Android encodes a frame only when the screen changes; 31 frames over 5.7 s).
- Not exercised: large system font scales, TalkBack, landscape, and the light theme on a real
  account. The light theme is covered by the synthetic stills only.
- Captures use the emulator's own 0.94 font scale rather than 1.0.
