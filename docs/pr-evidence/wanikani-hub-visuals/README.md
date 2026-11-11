# WaniKani hub visuals — capture provenance

## Provenance

| Field | Value |
| --- | --- |
| Head SHA (the code these captures demonstrate) | `169e156` (this docs commit adds only the captures and this README) |
| Base | `e2a537f` (`origin/dev`) |
| Change | WaniKani hub: Reviews/Lessons `StackedBar` + `CountLegend` under "Available study"; a lesson-budget bar under the Today pair (see Not exercised) |
| Build command (after) | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`, APK `/home/Qui/.paseo/worktrees/1t28llpl/gaten-wk-hub-visuals/app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK (after) | 205,469,616 B, sha256 `7ab7b7e78f065cbec249abef843b3b6acc2d381fdba447950c2ccca2bec766c6` — the on-device `base.apk` was re-hashed to this exact value before the captures, so the stills are provably from the head build |
| Build command (before) | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`, in a throwaway worktree of `e2a537f`, APK `/home/Qui/gaten-wk-review-before/app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK (before) | 205,468,312 B, sha256 `3415773eb5d565a0…` (first 12 as staged; this is the build actually installed for the before stills) |
| Device | serial `emulator-5556`, AVD `Gaten_Lookup_Review`, Android 14 (API 34), x86_64, 1080×2340 @ 440 dpi, font scale 1.0, headless `-no-window` |
| Themes | dark (`cmd uimode night yes`) and light (`night no`) |
| WaniKani account | connected (token not shown; `no_backup/wanikani/token` present; Home card read "WaniKani · Connected · 51 reviews due") |
| Capture method / driver | `adb` + `monkey` launch + `input tap` on the Home WaniKani card, cold start (`am force-stop`) before every still; `screenrecord` for the entrance clip. `uiautomator` cannot read these React Native screens (no text nodes), so navigation was coordinate-based after screenshot inspection |

## Files

| File | What it shows |
| --- | --- |
| `01-hub-base-dark.png` / `02-hub-base-light.png` | Base build (`e2a537f`): "Your study" → Available study → the two rows only. 51 reviews due / 66 lessons ready, stated as numbers. No bar, no legend |
| `03-hub-head-dark.png` / `04-hub-head-light.png` | Head build: the same rows unchanged and tappable, now under a two-tone `StackedBar` (51 Reviews vs 66 Lessons, one accent in two weights) and a `CountLegend` restating both figures |
| `05-hub-head-entrance.mp4` | 12 s, cold start → hub tap → the one-shot entrance sweep on the head build |

## Not exercised

- **The Today block and its lesson-budget bar did not render in this state on either build.** The
  budget bar requires a known learned-today figure (`learnedToday` against `dailyLimit`, both
  stamped with today's date), and this account/device has none (Home shows "Daily streak 0 days");
  with no known figures the whole Today block stands down, exactly as designed. The bar is
  implemented and typechecked but **not** device-demonstrated here.
- The **all-clear/empty state** (both queues complete → the success-check block, no bar) and a
  **paused-session state** were not reachable with 51 reviews and 66 lessons due.
- **No accessibility-tree evidence**: the React Native views expose no text nodes to
  `uiautomator`, so the spoken labels (bar summary, budget row, legend keys) were verified in code,
  not on-device.
- Reviews and lessons were not worked on this account; the captures are navigation-only.
