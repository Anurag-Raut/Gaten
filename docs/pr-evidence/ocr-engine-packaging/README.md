# OCR engine packaging — ML Kit bundled by default, PP-OCRv6 Medium recommended

Evidence for the change that makes ML Kit's Japanese recognizer the bundled, always-installed
default and the fallback for every downloadable engine, marks PP-OCRv6 Medium as recommended, and
leaves Manga OCR as an ordinary on-demand engine.

## Provenance

| Item | Value |
| --- | --- |
| Head SHA | `7a03404537e2c274edd60442cd649e68bef6c159` — `feat(ocr): bundle ML Kit as the default recognizer, recommend PP-OCRv6 Medium` |
| Base SHA | `e2a537f8a63799bf8d146e80d946d04b6e43d1dc` — `tic-tic-three-prs-evidence`, identical to `origin/dev` |
| Change | `RecognitionMode.ML_KIT` (bundled) becomes `DEFAULT` and the read-time fallback; `PP_MEDIUM` is marked `recommended`; Manga OCR stays a normal download |
| Build (head) | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL`; `:app:testDebugUnitTest` 617 tests, 0 failures, 0 errors |
| Build (base) | `git worktree add /home/Qui/gaten-ocr-before e2a537f`, then `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` → `BUILD SUCCESSFUL` |
| Typecheck | `./node_modules/.bin/tsc --noEmit` → clean |
| APK (base, x86_64) | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — **205,468,360 bytes**, `sha256 edea04b259c1eed62d042060ef88e0472bb640c6cbe168fd9d5b8d6ccbb978a8` |
| APK (head, x86_64) | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — **205,471,104 bytes**, `sha256 5145119da8c50f56db0d604af3a62d438e978700566532cdb297384c5098051a` |
| APK size delta | **+2,744 bytes (+0.0013 %)** — code only. The change adds no dependency and no weights: ML Kit was already in the APK as the line detector, and no downloadable engine ships. There is no size win to claim here. |
| Device | AVD `Gaten_PR19_Capture` → `emulator-5554`; Android 14 (API 34), `ro.product.cpu.abi` = x86_64, 1080×2400 @ 420 dpi, light theme (`cmd uimode night` = `Night mode: no`), animation scales 1.0 |
| Package | installed `applicationId` is **`com.gaten`** (`app/build.gradle.kts`: `namespace = "com.gaten.app"`, `applicationId = "com.gaten"`), so the fresh state is `adb shell pm clear com.gaten` — not `com.gaten.app` |
| Services | accessibility service `com.gaten/com.gaten.app.GatenAccessibilityService` enabled and bound (`bound=1`) before every capture; OCR engine install state: **nothing downloaded** — `pm clear`, then no Download was ever pressed, and the Text recognition screen itself reports all three downloadable engines as *not installed* (`after-recognition.txt`, `before-recognition.txt`) |
| Driver | `adb` from the Android SDK platform-tools, inside one `flock /tmp/gaten-emulator.lock` block that boots, installs, drives, captures and kills the emulator. `agent-device` 0.21.31 is installed but was not used, so the owned serial is guaranteed and no sibling AVD is touched. |
| Capture scripts | driven by a `flock`-wrapped shell script; its whole output is `capture-log.txt` |

## What the captures show

**Settings → Text recognition, fresh install, nothing downloaded.**

| | After (head) | Before (base) |
| --- | --- | --- |
| First row | **ML Kit** — selected, `Japanese text · bundled · works offline` | **Manga OCR** — selected, with a `Download · 116.6 MB` button |
| Recommended marker | **PP-OCRv6 Medium** carries a `Recommended` badge | none |
| Manga OCR | listed as an ordinary download (`not installed`, `Download · 116.6 MB`) | the only default |
| Hub row | `Text recognition. ML Kit` | `Text recognition. Manga OCR. Model not downloaded` |

- `after-01-recognition-fresh-install.png` / `before-01-recognition-fresh-install.png` — the two screens.
- `after-recognition.txt` / `before-recognition.txt` — the accessibility trees behind them, so the
  rows can be checked mechanically (`content-desc="PP-OCRv6 Medium, Recommended"`,
  `text="Japanese text · bundled · works offline"`, …).
- `after-hub.txt` / `before-hub.txt` — the Settings hub row on each build.

**A real lookup reading Japanese, with nothing downloaded.** The Japanese on screen is the app's own
**Word details** page for 猫, reached by typing romaji `neko` into its dictionary search (the app
converts romaji to kana, so no Japanese input is needed on an emulator). The same screen, the same
gesture, the same AVD state on both builds:

| | After (head) | Before (base) |
| --- | --- | --- |
| Target | `after-01c-target.png` — 猫, ねこ・ネコ, 猫に小判。, ネコがいる。 | `before-01c-target.png` — the same screen |
| Tap | `Tap hit line 3` | `Tap hit line 3` |
| Crop read | **`crop read=476ms`** — the crop is actually read | **`crop read=0ms`** — nothing ran |
| Tap complete | 491 ms | 2 ms |
| Sheet | `after-02-lookup.png` — the ねこ・ネコ line is selected and the sheet shows the 猫 / 猫 chips, then 猫 · ねこ・ネコ · Common · noun · "Cat (esp. the domestic cat, Felis catus), feline" | `before-02-lookup.png` — the same selection, and the sheet says **"No text found"** |

The 476 ms vs 0 ms crop read is the fingerprint: on the head build the selected engine (Manga OCR) is
not installed, so the read falls through to ML Kit's own recognition of the crop — which is what
produced the word. On the base build there is no fallback, so nothing reads the crop and the sheet is
empty. The trigger/index log (`after-logcat-trigger1.txt`) shows the overlay opening on the frozen
frame and ML Kit indexing 20 lines, with every Latin line then dropped for holding no Japanese.

- `after-logcat-02-lookup.txt` / `before-logcat-02-lookup.txt` — the tap and crop-read lines.
- `after.mp4` / `before.mp4` — short recordings of the settings page and then the lookup and its result.
- `after-search.txt` / `before-search.txt`, `after-worddetails.txt` / `before-worddetails.txt` — the
  intermediate trees (the romaji search and the target screen).
- `after-00-home.png` / `before-00-home.png` — the app's Home on a fresh install, for context.

## Not exercised / limitations

- **One screenshot of the sheet per build, not an accuracy comparison.** No timing, memory or
  accuracy claim is made for ML Kit against the downloadable engines, and the recommendation of
  PP-OCRv6 Medium is the user's report plus the catalog marker, not something measured here.
- **The Japanese target is app UI text**, not a manga page or video thumbnail: the emulator cannot
  type Japanese through `adb`, so the read was exercised on the app's own dictionary screen, which is
  one text style. The app's Home search-field placeholder (grey, low contrast) was tried first and ML
  Kit produced no line box there, which is why the Word details page was used instead.
- **Only the fresh-install state.** A downloaded engine that then fails, a mid-download switch, the
  dark theme, and any device other than this emulator are not exercised.
- **`uiautomator dump` does not see the lookup overlay** (it is a separate accessibility overlay
  window), so the sheet appears in the screenshots and the logcat, not in the `*-02-lookup` trees;
  those trees show the app behind the sheet.
- The emulator's `wm size`/`wm density` are the AVD defaults (1080×2400 @ 420 dpi); the overlay log
  confirms a 1:1 mapping (`bitmap 1080x2400, view 1080x2400, origin (0, 0), scale 1.0x1.0`).
