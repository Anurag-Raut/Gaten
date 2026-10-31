# Tapped-line selection outline — 6 dp padding

Rendered evidence for `LookupOverlay.kt`'s tap-selected line: the frozen outline and its clear region
are inflated by 6 dp on every side, so the selection ring no longer sits on the OCR bounds and clips
the glyphs. Captured from the real app on the emulator, on **this branch's build** and on the parent
build, with the same frozen screen and the same tapped line in both runs.

| Item | Value |
| --- | --- |
| Change | `frozenBox = anchorBox.inflate(tapOutlinePaddingPx)` where `tapOutlinePaddingPx = 6.dp` (`app/src/main/java/com/gaten/app/ui/lookup/LookupOverlay.kt`) |
| Before | selection ring drawn on the OCR line bounds — the stroke overlapped the ねこ glyphs |
| After | the same ring 6 dp clear of the glyphs on every side, in both the outline and the clear region |
| Measured | selection box **68 × 37 px → 102 × 71 px** = **+34 px per axis (+17 px per side = 6.2 dp @ 2.75 px/dp)** |
| Device | `emulator-5554`, AVD `Gaten Lookup Review` (pixel_5, API 34, x86_64), 1080×2340 @ 440 dpi, light theme |
| Left unchanged | the OCR crop, the result anchoring and the circle/lasso path — the tap path only |

## Provenance

| | |
| --- | --- |
| Branch / head | `add-padding-to-tapped-line-selection-outline` @ `83c0a4c06016b3532851c670bcff751cdf85972c` (PR #54, base `dev`) |
| Before build | `935e05c` — the PR head's parent; the same tree with `LookupOverlay.kt` restored from `935e05c` (the PR change is a 3-line diff in that one file) |
| Build command | `./gradlew :app:assembleDebug` (both builds; debug variant, JS bundled into `assets/index.android.bundle`) |
| After APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 296,487,628 B, SHA-256 `0d4d6b2ef7812a219e8210ed969e579899aa418b18104adb7388aeea57f1dd78` |
| Before APK | same path, 296,487,628 B, SHA-256 `ae591e7ca04f8919759d56ce00dbce2843a3297cf5aeb59e70e7c160dd0bdbcb` |
| Installed | each APK installed with `agent-device install com.gaten <path> --device "Gaten Lookup Review"`; the on-device `/data/app/…/base.apk` re-hashed to the same SHA-256 as the installed file in the same run |
| Trigger | `agent-device push com.gaten '{"action":"com.gaten.action.TRIGGER_LOOKUP"}'` (broadcast to the running accessibility service) |
| Recording | `agent-device record start/stop --scope device --quality high` |

The commands, the logcat gates and the reason the tap and the stills come from `adb` are in
[`capture-log.txt`](capture-log.txt).

## The fixture

Gaten Home with 猫 in **Recently looked up**. That screen is frozen into the overlay by the trigger,
ML Kit indexes it, and the tap selects the indexed **ねこ** readings line of the 猫 row. Both builds
ran against the identical screen and the identical index:

| | After (`83c0a4c`) | Before (`935e05c`) |
| --- | --- | --- |
| `Overlay window added` | +58 ms since the trigger | +69 ms since the trigger |
| `Screen scan ready` | 795 ms (25 lines) | 893 ms (25 lines) |
| Tap | `Tap hit line 5` | `Tap hit line 5` |
| Result | `Tap complete in 403 ms` | `Tap complete in 804 ms` |

`Tap hit line 5` on a 25-line index in both runs is what makes the box measurement a like-for-like
comparison: same screen, same line, same OCR bounds — only the build differs.

## Measured geometry

The selection box is the clear region the ring outlines. Measured in the full-resolution stills by
thresholding the un-dimmed pixels and the accent ring inside a window around the tapped line:

| | Box x | Box y | Size |
| --- | --- | --- | --- |
| Before (`before-01-tap-selection-outline.png`) | 91 … 158 | 1654 … 1690 | 68 × 37 px |
| After (`after-01-tap-selection-outline.png`) | 74 … 175 | 1637 … 1707 | 102 × 71 px |
| Difference | −17 / +17 | −17 / +17 | **+34 × +34 px** |

34 px over two sides is 17 px per side; at 440 dpi (2.75 px/dp) that is 6.2 dp per side against the
6 dp the change asks for, the residue being the anti-aliased stroke edge in the threshold.

## What each file shows

| File | What it shows |
| --- | --- |
| `before-01-tap-selection-outline.png` | the before build, the instant after the tap: the ring hugs the ねこ glyphs and the stroke sits on them, while the rail reads "Reading selection…" |
| `after-01-tap-selection-outline.png` | the after build in the same state: the ring stands 6 dp clear of the glyphs, in both the outline and the clear region |
| `before-02-tap-result.png` | the before build once the lookup settles: the 猫 result sheet (unchanged by this PR) |
| `after-02-tap-result.png` | the after build's settled result sheet — same headword, same reading, same meanings |
| `outline-before-after-crop.png` | the two selection outlines cropped and enlarged side by side (before left, after right) — the glyph/ring relationship in one frame |
| `before-tap-selection.mp4` | the before build's tap: trigger → frozen frame → tap → outline → sheet (7.9 s of encoded content, 1080×2340) |
| `after-tap-selection.mp4` | the after build's tap: the same steps on the padded outline (13.4 s of encoded content, 1080×2340) |
| `capture-log.txt` | builds, hashes, install commands, the exact tap coordinates, the logcat gates and the tool limitation behind the adb fallback |

## Limitations, stated plainly

- **The tap and the stills are `adb`.** agent-device's semantic path attaches UiAutomation, and with a
  session open the enabled Gaten accessibility services are unbound (`dumpsys accessibility` shows
  `Bound services:{}`, and the trigger broadcast is enqueued with **0 receivers**); the trigger
  therefore never reaches the app while agent-device holds the device that way. agent-device does the
  trigger (`push` → `am broadcast`) and the recording; the tap and the screenshots use `adb`. Same
  limitation as [../external-trigger/VERIFICATION.md](../external-trigger/VERIFICATION.md).
- **`agent-device record` encodes on change**, so static stretches contribute few frames and the
  before clip covers 7.9 s of encoded content against a ~12 s window. Nothing was retimed; the outline
  and the sheet appear in the frames they appear in on the device.
- **The before build is the parent commit, not a signed release.** It is the same worktree with
  `LookupOverlay.kt` restored from `935e05c`; the two APKs differ only by that file's compiled code —
  their sizes are identical and their SHA-256s are not (the debug APK is not reproducible
  byte-for-byte across builds).
- **The emulator is not the reporter's phone.** The 6.2 dp measurement is the emulator's 440 dpi
  density; the code's unit is dp, so the physical size tracks density on any device.
- **Not exercised:** the circle/lasso path, dismissals, and the sheet content (all unchanged by this
  PR), and any device other than this AVD.
