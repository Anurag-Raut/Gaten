# WaniKani study page — device evidence

The study page repeated the same two numbers three times: a `Today` row of two labelled bars, a
legend restating those counts, and then the two queue cards repeating them again. The revision splits
it into two sections — **Today's progress** (the two device-local counts over **one** segmented bar)
and **Available study** (the two queue cards, counts stated once) — and makes the Lessons count obey
the daily lesson allowance instead of showing the whole unlocked queue.

## Source

| Item | Value |
| --- | --- |
| Base | `9350181` (`origin/dev`) |
| Branch | `feat/wanikani-study-progress` |
| Committed | `1e9aace` two-section page · `54741f3` one stacked bar · `fc221be` evidence links pinned |
| Uncommitted | `src/rn/ui/review/wanikani-hub-model.ts`, `tests/wanikani-hub.test.cjs` (Lessons allowance) |
| Production diff | `git diff 9350181 -- src/rn tests \| sha256sum` = `08f60f95f4c8feffd6839871c0ea6d4f8d06eba8fe65f2ef4c7a4bdfcfc32ebe` (4 files, +191/−169) |
| `WaniKaniHome.tsx` | `8655fe23acf7febdbd805bdc26adbce80e1afa6ba5339a3c45f294391dbe84d1` |
| `ink.tsx` | `ed9ef05ff51f56ba20c17e2f4d9ebab293460a92641574e4b16170ffb5ba7fab` |
| `wanikani-hub-model.ts` | `988ed3948ef4028a81eec10e793d346e87c446af298398fda5847654ea0b95e8` |
| `wanikani-hub.test.cjs` | `fc5a58ac7afeee24bccfb3e9cd8f29bb5b562103ed70e65f0dc5d6a58e115429` |
| Fixture injection | `fixture-injection.patch`, sha256 `4dceb9092d82d097befd36fe75e4edc1d50f96afee3ab9da9513564f0a29bc79` |

`ink.tsx` is shared with the Analytics screen. Its `Metric`/`MetricPair` blocks are unchanged for
callers that pass no swatch; only the WaniKani study page passes one.

## Build

```
flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64
```

Debug builds set `debuggableVariants = emptyList()`, so the JS bundle is embedded in the APK and Metro
is not needed to launch. APK splits are per ABI; all captures use the `x86_64` split. Builds ran in a
detached worktree at the base commit (for the BEFORE pair) and at the branch head (for the AFTER
pair), so the source under capture is exactly the source recorded above. The build needs the
generated `jmdict`/`examples`/`rtk` SQLite assets, which are gitignored and were hardlinked in from
the main checkout.

| APK | Bytes | APK sha256 | Embedded bundle sha256 |
| --- | --- | --- | --- |
| `before-fixture.apk` | 207 820 927 | `c06db074d2e916e90a395431c05b98ca7a8b9fc5295721e44192cb8d6d558eec` | `076936eb2e2da2c32d714e97133de172a8e05b2f63a7afe4120844f425b8eb3b` |
| `before-production.apk` | 208 351 819 | `56f93104630ecb0e6f4a785f5248dea185f5cf13aab95909aa1876f2870b267e` | `9369de5075c91c0d7bb46917deb2a5e308265a1db27d9dde4a5d0b670ebf195b` |
| `swatch-before-production.apk` | 208 352 723 | `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4` | `42d8c1b391b634fd73459fc21004f2a1d55f5ad749d66573627f72326dd8f8b7` |
| `after-fixture.apk` | 207 821 491 | `96ac7019a8cd8f1757d261b605c75355451da2730203d31c21fc694fd261bccd` | `ea65d614f70ce5580858880051044e63d96336855f3c656bb89a50778e052c43` |
| `after-production.apk` | 208 352 223 | `fb8cb83539c29abd055116dcedf2b410782fd81b183c44a208e176e095f4b2ab` | `28fb35000ecbc00f6a19e413eb37242d9f2a24b4ac865813fabbb7cea84df965` |

`swatch-before-production.apk` is the previous committed head (`fc221be`) and exists only as the
regression baseline for the shared `Metric` change. The installed `base.apk` sha256 was read back from
the device after each install and matched the table.

Bundle-content checks on the extracted `assets/index.android.bundle` (Hermes stores non-ASCII strings
as UTF-16LE, which is why a UTF-8-only search misses them):

| String | before | after |
| --- | --- | --- |
| `Today’s progress` | absent | present (UTF-16LE) |
| `Nothing completed yet today.` | absent | present (UTF-8) — accessibility label only, not visible text |
| `Share of today’s completed activity` | absent | absent — the caption was dropped in the one-bar revision |
| `Daily lesson goal ·` | absent | absent — the goal line was dropped in the one-bar revision |
| `Your study` / `Available study` | present | present |
| Fixture markers (`anki-ready`, `gallery`) | absent | absent |
| App-shell markers (`Daily streak`, `Recent lookups`) | present | present |

## Captures

All stills are 1080×2340. Dark theme throughout unless the name says light. The emulator was a single
instance (`Gaten_Lookup_Review`, API 34, x86_64) driven over `adb` and `agent-device`; font scale 0.94,
dark theme, single user — all as found, restored afterwards.

| File | Scene | Source |
| --- | --- | --- |
| `01-before-fixture-today-dark.png` | base design, 27 reviewed / 0 learned, limit 15 | synthetic |
| `02-before-fixture-today-light.png` | same, light theme | synthetic |
| `03-before-fixture-zero-dark.png` | base design, 0 / 0 | synthetic |
| `04-real-before-study-dark.png` | base design, connected test account | real app |
| `05-after-fixture-mixed-dark.png` | new design, 27 reviewed / 3 learned → bar 90/10, Lessons 12 ready | synthetic |
| `06-after-fixture-mixed-light.png` | same, light theme | synthetic |
| `07-after-fixture-today-dark.png` | new design, 27 / 0 → single full-width segment, Lessons 15 ready | synthetic |
| `08-after-fixture-zero-dark.png` | new design, 0 / 0 → "Nothing completed yet today." | synthetic |
| `09-after-fixture-unknown-dark.png` | counters stamped yesterday → dash + reason, no bar | synthetic |
| `10-real-after-study-dark.png` | new design, connected test account, 90 due / Lessons 15 ready | real app |
| `14-after-study-open.mp4` | navigation into the study page on the new design, 5.72 s | real app |
| `11`–`13` | Analytics regression pair + amplified diff | real app |

Synthetic means the `artifacts/visual-review/hubs-procedural/fixtures.tsx` harness renders
`WaniKaniHome` with injected `WaniKaniHubState` values; those captures show the real component tree and
layout, but the numbers are chosen by the fixture rather than fetched. The harness scene is chosen at
launch with `am start … --es settings_page <scene>`.

### Lessons allowance

`remainingDailyBudget = max(0, dailyLimit - learnedToday)`, then
`allowance = remainingDailyBudget == null ? count : min(count, remainingDailyBudget)` applied to the
trailing `ready` value in both the cached and the freshly fetched branch. The trailing count is the
number of lessons the day still allows, capped by what is actually unlocked; the support line
(`Next batch · N cards`) and the `learnedToday >= dailyLimit` "Daily limit reached" state are
unchanged. With 47 unlocked and a 15/day limit and nothing learned today the row reads **15 ready**;
after 3 lessons it reads **12 ready**; with only 3 unlocked it reads **3 ready**. Unknown limit keeps
the raw queue count.

### Fixture injection

The harness change lives only in `fixture-injection.patch` and is never part of the production commit.
It adds four scenes and wraps the fixture root in a `NavigationContainer`, because the shared chart
primitives call `useIsFocused()` and the original harness had no navigation context. The app itself
always renders inside a navigator, so this is a harness gap rather than an app defect. The APKs used
for capture were built from a scratch worktree whose entry point was swapped to register the fixture
component; production APKs were built from the same tree with the production entry restored.

## Verification

- `tsc --noEmit`: exit 0.
- `node --test tests/wanikani-hub.test.cjs` on Node v24.19.0: **68 tests, 24 pass, 44 fail**, run
  against this head, and against the base commit `9350181` in a separate detached worktree. Both give
  the same counts and a byte-identical set of 44 failing test names, single cause
  (`Error: Unexpected import: ./wanikani-romaji`, a pre-existing test-harness defect). The change adds
  no new failures and fixes none; the suite is broken identically at the base commit.
- Gradle `:app:assembleDebug`: `BUILD SUCCESSFUL` for all five APKs.
- Analytics regression: comparing `11` and `12` pixel-for-pixel leaves **220 of 2 397 600 pixels
  (0.009%)** different, all of them in the "Updated _m ago" relative-timestamp glyph. Every metric
  block renders identically, so the shared `Metric` change does not affect Analytics.

## Limitations

- The synthetic captures render the production component tree with injected state; they are not
  network-backed. The real-app captures are network-backed but constrained to what the connected test
  account holds right now.
- The test account has no in-app activity today, so `10-real-after-study-dark.png` shows the zero state
  of the progress block; the populated bar is evidenced only by the synthetic stills, which the PR body
  labels as such. Consequently `14-after-study-open.mp4` shows navigation and arrival rather than a bar
  filling in.
- The light theme is covered by synthetic stills only.
- Captures use the emulator's own 0.94 font scale rather than 1.0.
