# Overlay startup timing evidence

Read-only instrumentation pass for TickTick task `6ae3325b717ab2bce9f1ac48` (improve app startup and
the trigger→overlay delay). Raw captures are the `*.log` files beside this file; they are trimmed to
the service's own stage lines.

## What changed (native capture/overlay timing path)

| Change | File | Evidence |
|---|---|---|
| The floating bubble fires the lookup **on the tap** instead of waiting for its 150 ms release-shrink animation to end | `app/src/main/java/com/gaten/app/trigger/FloatingTriggerButton.kt` | `bubble-baseline.log` vs `bubble-fixed.log` |
| The now-unused `onEnd` callback on the shrink animation was removed | same file | — |
| The Quick Settings tile's fixed shade wait is cut **400 ms → 300 ms**, calibrated to the measured collapse | `app/src/main/java/com/gaten/app/GatenAccessibilityService.kt` | `tile-wait.log` |
| Stale comment corrected: the service **does** read the interactive window list (`flagRetrieveInteractiveWindows` is set, `OverlayWindowController.focusWentToInputMethod` uses it), but the window focus returns at dismissal, not when the collapse animation ends, so the wait stays a fixed one | same file | comment |
| Inline `onMain` entry-handling was **reverted**; triggers keep their original posted-to-main-queue semantics | `app/src/main/java/com/gaten/app/GatenAccessibilityService.kt` | not in diff |

## Method

- Emulator `emulator-5554`, AVD `Gaten_Lookup_Review` (Pixel 5, API 34 google_apis, x86_64, 8 GB),
  headless, `-no-snapshot`. **It is not the reporter's phone and does not measure it.**
- Debug APK (JS bundled; no Metro). Samples taken with the host otherwise idle.
- Stage clock: the service's own `elapsedSinceTrigger()` lines (absolute milliseconds since
  `triggerAtMs`, which is stamped inside `triggerCapture`).
- Bubble-tap latency measured **externally**: device-side `date +%s.%N` immediately before
  `input tap`, compared to the device-side logcat timestamp of the next stage. `adb shell input tap`
  command overhead is included and is constant across both runs; the host/guest clock offset is
  removed by using the device clock on both sides.
- Trigger timestamp caveat: `triggerAtMs` starts *after* the bubble's shrink animation, so the
  service's own logs cannot show that wait. The external tap timing is what measures it.

## Results

Bubble tap (device-side, wall clock from the tap command to the stage):

| Stage | Baseline (pre-change) | Fixed | Difference |
|---|---|---|---|
| tap → `Trigger accepted` | 0.161–0.174 s (6 samples) | 0.008–0.011 s (3 samples) | **≈ −155 ms** |
| tap → `Overlay window added` (frozen frame posted) | 0.222–0.234 s | 0.053–0.062 s | ≈ −170 ms |
| tap → `Frozen frame on screen` | 0.261–0.274 s | 0.094–0.098 s | ≈ −170 ms |

Broadcast trigger (service log clock, `Trigger accepted` → stage), bubble enabled:

| Stage | Baseline | Fixed |
|---|---|---|
| → `performCapture called` (bubble `hideThen` wait) | 34–39 ms | 32–35 ms |
| → `Screenshot delivered` | 37–43 ms | 35–38 ms |
| → `Overlay window added` | 57–62 ms | 54 ms |
| → `Frozen frame on screen` | 97–100 ms | 95–97 ms |

The broadcast path is unchanged within noise: it never had the shrink wait.

Quick Settings tile (service log clock, `Trigger accepted` → stage; the shade is dismissed by the
tile itself and the service then waits out the collapse):

| Stage | Before (400 ms wait) | After (300 ms wait) | Difference |
|---|---|---|---|
| `performCapture called` | 404–408 ms | 303 ms | ≈ −100 ms |
| `Overlay window added` | 441–493 ms | 329–338 ms | ≈ −130 ms |
| `Frozen frame on screen` | 484–540 ms | 371–375 ms | ≈ −140 ms |

The collapse itself measured ≈150–200 ms on this emulator (animation scale 1.0); nothing earlier is
safe (focus returns at ≈125 ms, before it finishes, and the window list keeps the shade at
full-display bounds throughout). All eight tile takes with the 300 ms wait froze a shade-free frame
(top-band mean luminance 151.9–152.6). Detail and caveats: `tile-wait.log`.

## Text readiness (source-confirmed; not changed by this patch)

The frozen frame and "text you can tap" are two different moments:

- The frozen frame is up ~95–100 ms after the trigger (above).
- **A tap is refused until the full-screen ML Kit index exists.** `onTap` returns early while
  `ScanState` is `Scanning` (`LookupOverlay.kt:561-567`), and that state awaits the scan submitted at
  `OverlayWindowController.kt:145`.
- `Early screen scan ready` measured **389–628 ms** in these runs (smoke sample: 414 ms, 6 lines).
  So tap-readiness is roughly frozen-frame + scan ≈ **0.5 s**, which matches the reported impression.
- The lasso/circle is **already immediate**: `LassoLayer.interactive` is
  `phase == Phase.Selecting && !closing` (`LookupOverlay.kt:672`), independent of the scan, and the
  circle path reads its own crop.

Checked and ruled out: no duplicate full-screen scan (one submission; `detectLines` is crop-only,
`LookupPipeline.kt:365`), and no per-trigger ML Kit model reload (`MlKitClient` caches the recognizer;
dropped only on 90 s idle, memory pressure, or service unbind).

## Caveats

- **The reporter's "≈500 ms on every trigger" was NOT reproduced**: the emulator's warm
  trigger→frozen-frame is ~95–100 ms on both the broadcast and bubble paths after the fix. No claim
  is made that a physical phone behaves the same.
- The frozen-frame path that remains is platform work the fix does not touch: `takeScreenshot`
  round-trip, the full-screen ARGB_8888 software copy, the overlay window add and one composited
  frame. On this emulator those account for most of the ~95 ms; on other hardware they may be larger.
  This is **not** claimed to be the whole of the perceived delay.
- **ML Kit index readiness is a separate, larger term** (389–628 ms) and is not platform latency: it
  is this app's OCR work, and it gates tap-to-select specifically. It was left untouched here
  (A/B/C proposals declined pending evidence).
- **Bubble wait fixed** (≈155 ms, measured). **All-trigger ~500 ms not reproduced and not resolved** —
  the time that remains is the two terms above, neither of which this patch changed.
- The Quick Settings tile's shade wait is **300 ms**, down from 400 ms. An early exit from the
  window list was prototyped and rejected: the window focus returns at dismissal, not when the
  collapse animation finishes, and the window list keeps the shade at full-display bounds for the
  whole animation, so no earlier signal is safe. 300 ms is the measured ≈150–200 ms collapse plus a
  safety band; on a slower device a half-collapsed shade in a capture means this must go back up.
- One-time cold cost is visible on the first capture after the service starts (~170–250 ms to the
  frozen frame vs ~95 ms warm). This is warm-up and is not the recurring delay.
- Full-chain smoke on the fixed build (trigger → capture → overlay → frozen frame → ML Kit index,
  6 lines in 414 ms) is in `smoke-fixed.log`; no `AndroidRuntime` errors were logged.

## Build provenance

- Base commit: `7d12ac44799fd909f897de65b1f11090c8f7596d`. Fixed measurements used the startup workspace with its native changes uncommitted; the three UI fixes were not included.
- Native source diff SHA-256: `15de610b23903816d48867a7a06181c3bbbe474f0d242c0878e3f6b799d91954`.
- Baseline APK SHA-256: `ad9b39ac91d439f6a782d016827ff941df6084a7a05d74772637292308f6c75c`.
- Fixed native-only APK SHA-256: `e51280d97ec962a17367ec9d9fa315938c20712b4c30d8fb8a3d63288ace11fb`.

## Early-tap buffering — verified on device

Source-confirmed defect (before the fix): a tap that landed while the full-screen ML Kit index was
still building hit `onTap`'s early return and was dropped, so the reader had to tap again. Fix, in
`ui/lookup/LookupOverlay.kt` only: the latest such tap is buffered and replayed through the same
indexed `onTap` once the index arrives — same line and phrase as a later tap — and is cleared on a
lasso start, `close()`, `selectAgain()`, any tap that really ran, and by the replay's own guards
(index must hold lines; the overlay must still be selecting and not closing). No crop fallback, no
model change, no UI change.

Verified on `emulator-5554` with the combined debug APK, Gaten Home foreground (猫 in "Recently
looked up"; the run's index held 26 lines), `adb` only — no agent-device/UiAutomation snapshots. The
tap was issued in the same device-side shell as the broadcast, ~200 ms later (test-side delay; the
product adds none).

| Run | Tap timing | Log evidence | Result |
|---|---|---|---|
| Control | ~1.2 s after trigger (index settled) | `Tap hit line 4` → `Tap complete in 432 ms` | sheet: 猫 / ねこ・ネコ / "Cat (esp. the domestic cat, Felis catus), feline" |
| Queued | ~0.2 s after trigger, inside Scanning | `Tap queued …` (+278 ms) → `Screen scan ready in 737 ms (26 lines)` → `Replaying the queued tap` → `Tap hit line 4` | same line, same sheet as the control |
| Latest tap wins | two taps in Scanning (line 4, then line 2) | two `Tap queued …` → one `Replaying the queued tap` → `Tap hit line 2` | the later tap won |
| Dismiss before ready | tap in Scanning, BACK at ~0.45 s | `Tap queued …` → `Overlay window removed (resumed=true)` | **no** `Replaying` — buffer discarded |
| Lasso before ready | tap in Scanning, swipe at ~0.45 s | `Tap queued …`, then the index settled, **no** `Replaying` | the lasso start cleared it; the index held 26 lines and the phase stayed Selecting, so the replay guard would otherwise have fired |

Evidence files: `queued-tap.log`, `queued-tap-control.log`, `queued-tap-latest.log`,
`queued-tap-discard.log`, and `queued-tap-sheet.png` (the replayed tap's sheet — identical headword,
reading and first meaning to the control's).

Recording: `queued-tap-replay.mp4` (15 s `adb screenrecord`) with its own log window in
`queued-tap-replay.log` — `Tap queued …` (+232 ms) → `Screen scan ready in 785 ms (18 lines)` →
`Replaying the queued tap` → `Tap hit line 11` → `Tap complete in 512 ms`. Labelled honestly: the
foreground in that clip is the app's own **Word details** screen (猫), not the Home fixture, so the
replayed tap landed on a different line and its crop produced no dictionary match (the sheet reads
"No dictionary matches" with "View all meanings (6)"). It shows the interaction; the identical-entry
claim rests on the control/queued pair above, which both hit line 4 on the Home fixture.

Not claimed: faster OCR, or any change to index timing (737–869 ms scans in these runs).

## Final build provenance (queued-tap build)

- Combined debug APK, coordinator `pumped-dog` build 2026-10-29 (`app/build/outputs/apk/debug/app-x86_64-debug.apk`):
  `sha256 c57ee24b5f447b27aff66137150471482df1394c80e48e91596a39f99ed34121`
- Patched sources, byte-identical between the startup workspace and the integrated one:
  - `ui/lookup/LookupOverlay.kt`: `sha256 c78d7d4c22eb97ff1c8b07ac9f3c485437aee1a30aa888a53a29be684f6310e9`
  - `trigger/FloatingTriggerButton.kt`: `sha256 9d9d7713007a60fd57f2d5979bd2c98b1274123a3e9c68f6effdf1286d176b54`
- The timing tables above and the UI-pass screenshots were captured on **earlier builds** (the
  native-only fixed APK `e51280d9…`, native changes uncommitted). The queued-tap patch changes no RN
  UI code, so those screenshots still describe the current UI; the timings were not re-taken here.

## Current head spot-check — `e7276069`

The native code on this head is **byte-identical** to the combined build the sections above were
captured on, so they still describe this head's behaviour and this is a bounded spot-check rather than
a re-run of every scenario. How that was checked: the three upstream commits (`7d12ac4..7e02486`)
touch only `src/rn/**` and `docs/**` — no `app/` file at all, i.e. no Kotlin/native code — and each
patched file's `sha256` matches the verified worktree:
`FloatingTriggerButton.kt 9d9d7713007a60fd…6d176b54`, `LookupOverlay.kt c78d7d4c22eb97ff…4f6310e9`,
`GatenAccessibilityService.kt ac90c3fdfe247d0a…4a40e4b`.

One 15 s `adb screenrecord` (`head41-bubble-queued.mp4`), Gaten Home foreground (猫 under "Recently
looked up"), `emulator-5554` (API 34 x86_64), this head's APK `sha256 59c356af…` — the bubble tap
triggers the overlay, then a tap ~0.5 s later, inside Scanning, is queued and replayed:

- `Trigger accepted` **0.018 s** after the bubble tap, measured on the device clock (the earlier
  build's baseline was 0.161–0.174 s).
- `Overlay window added` +101 ms → `Frozen frame on screen` +126 ms.
- `Tap queued while the screen index is still building` → `Screen scan ready in 910 ms (26 lines)` →
  `Replaying the queued tap` → `Tap hit line 4` → `Tap complete in 840 ms`.
- `head41-sheet.png` — the replayed tap's sheet: 猫 / ねこ・ネコ / "Cat (esp. the domestic cat, Felis
  catus), feline", i.e. the same line and entry as the earlier control.

Log window: `head41-clip.log`. A first take with the second tap at ~0.2 s is deliberately **not**
offered as evidence: it landed before the overlay's UI was composed (its log has no `Tap …` line), so
it shows only the bubble-tap half.
