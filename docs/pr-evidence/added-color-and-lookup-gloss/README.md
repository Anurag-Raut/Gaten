# Added-button fill and the recent-lookup gloss

Rendered evidence for the two fixes in this change set: the Anki action keeps its **disabled gray** fill
after a press instead of staying on the accent, and a recent-lookup row shows its **whole** definition
instead of clipping it after two lines.

| Item | Value |
|---|---|
| Change | `src/rn/ui/PressableScale.tsx` (the press animation no longer wins over the disabled fill) and `src/rn/ui/home/HomeDashboard.tsx` (the gloss wraps in full) |
| Source | `ca2a7b4` (the `dev` tip this branch sits on) plus the working-tree diff hashing `0cb9bf336272aad8604aee765ef4256fe97d71673abf9c18e5272446ba254476` — 2 files, 13 insertions, 12 deletions, captured before it was committed |
| Base build | `./gradlew :app:assembleDebug`, `BUILD SUCCESSFUL in 2m 11s` (282 tasks) — the same tree with both files at their `ca2a7b4` contents |
| Head build | `./gradlew :app:assembleDebug`, `BUILD SUCCESSFUL in 14s` (43 executed) — the tree with the fix applied |
| Base APK | `app-x86_64-debug.apk`, 296,478,332 B, SHA-256 `8866dadd5fc206e6ff545cd44585302d7edf89cdfc2385db765c811688c80e34` |
| Head APK | `app-x86_64-debug.apk`, 299,852,363 B, SHA-256 `19f5dfcd2f7fc30dc3fe01f9b8671283090dedc17483d495247b05e39322328e` |
| JS inside those APKs | `assets/index.android.bundle` — base SHA-256 `d7de3bd6b4d4f190a1553197da07d86b6053b2a8be340b3215414ebf6a31b13b`, head `815b2d397d7772ecf45a69e0f68b63e2317c1cdd802bedc65c7d990a81508833` |
| Device | `emulator-5554`, AVD `Gaten_Evidence_Overlay` (pixel_5, `sdk_gphone64_x86_64`, API 34, google_apis), 1080×2340 @ 440 dpi, light theme, default text scale |
| AnkiDroid | 2.25.3, `com.ichi2.anki.permission.READ_WRITE_DATABASE` granted to `com.gaten`, all-files access allowed; its collection is **cleared before each run** so both builds make a first-time add of 取る into the deck `Gaten` |
| Kanji reference | **Remembering the Kanji** (Settings ▸ Kanji reference), so the word page draws its kanji section without a WaniKani account |
| Driver | agent-device 0.21.31 — `press`, `scroll`, `screenshot` and `record --scope device`; the tap is dispatched through UiAutomation, so no accessibility overlay is involved here |

## What each file shows

| File | What it shows |
|---|---|
| `before-01-worddetail-added.png` | Base build, 取る's word page after tapping **Add to Anki**: the action is `Added` and disabled, but its fill is still the accent indigo |
| `after-01-worddetail-added.png` | Head build, the identical page and steps: the same disabled action now carries the theme's disabled gray |
| `before-after-anki-button.png` | The two button bands stacked (base on top, head below) — the whole claim in one still |
| `before-02-recent-lookups.png` | Base build, Home ▸ **Recently looked up**: 取る's definition is cut off after two lines (`to take, to pick up, to grab, to catch, …`) |
| `after-02-recent-lookups.png` | Head build, the same list in the same order: the definition wraps in full (`… to catch, to hold`) |
| `before-after-recent-rows.png` | The two `Recently looked up` sections stacked, base on top and head below |
| `before-anki-press.mp4` | Base build: the tap cycle on the same button — resting, held, the busy spinner, then the wrong `Added` fill (18.7 s) |
| `after-anki-press.mp4` | Head build: the same cycle ending on the disabled gray fill (14.7 s) |

The two Home stills are the same screen state: identical list order (取る, それは), identical `Anki / Not
set up / Open Anki` banner, identical WaniKani row. Only the definition column differs.

## Measurements

The action's fill, sampled from a text-free band of the button (mean of 200×60 px at x 100..300,
y 2110..2170, in the full-resolution stills):

| Still | Mean RGB | Nearest token |
|---|---|---|
| `before-01-worddetail-added.png` | (91, 82, 208) `#5B52D0` | the accent family — neither `actionPrimary` `#4338CA` nor the held tone, i.e. the release animation frozen where the control went disabled |
| `after-01-worddetail-added.png` | (233, 233, 237) `#E9E9ED` | `colors.disabled`, exactly |

The frozen value is not fixed: earlier base runs of the same steps froze at exactly (67, 56, 202)
`#4338CA` — the resting `actionPrimary` — which is what a stale interpolated value looks like when the
press animation finishes before the button disables. Both are the accent; neither is the disabled gray.
The label's colour (`colors.onDisabled`) is the same in both builds, so the stills isolate the fill.

## Method

```sh
# both APKs, from the one worktree: base first, then the working tree restored
git checkout -- src/rn/ui/PressableScale.tsx src/rn/ui/home/HomeDashboard.tsx
./gradlew :app:assembleDebug          # base
# …restore the two files…
./gradlew :app:assembleDebug          # head

# per run: fresh AnkiDroid collection, then the build under test
adb shell pm clear com.ichi2.anki
adb shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow
adb shell rm -rf /storage/emulated/0/AnkiDroid
adb install -r -d /tmp/gaten-evidence/<build>-x86_64-debug.apk

# drive: Home ▸ Recently looked up ▸ 取る ▸ scroll to the action
agent-device record start after-anki-press.mp4 --scope device
agent-device press 'role=button label="Add to Anki"'
# 7 s for the celebrating state to settle into `Added`, then
agent-device screenshot --out after-01-worddetail-added.png
agent-device record stop
```

`capture-log.txt` has the same steps with the values this run actually used.

## What this evidence does not establish

- **Haptics.** The emulator cannot show the tick; only the visual halves of the press are captured.
- **The frozen fill as motion.** The defect's end state is static, so the stills carry the colour
  claim; the two recordings show the tap → spinner → `Added` transition around it.
- **Dark theme, other text scales, other devices.** Only the light theme at the default text scale was
  exercised. The clipping the second fix removes is visible at that scale, which is why it is the one
  captured; the head build's wrap was not re-checked at 130 %+ text.
- **The Home banner.** `Anki / Not set up / Open Anki` is the fixture's state after its collection was
  cleared for a first-time add. It is not part of this change and reads the same in both stills.
- **The rest of the word page.** Example sentences and the Remembering-the-Kanji tile come from the
  same builds but are not part of this change set.
