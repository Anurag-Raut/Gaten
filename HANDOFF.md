# Handoff — Japanese Circle-to-Search Prototype (Android only)

## Mission (most important requirement first)

**Prove or disprove that screen capture + Japanese OCR works on the target phone before
building anything else.** A buildable APK is a bonus; the verdict is the deliverable.

Target device: **OnePlus Nord 6 running OxygenOS** (Android, owned by the requester).

> Android only. iOS is explicitly out of scope. Do not spend time on cross-platform work.

## The end goal (only if capture+OCR is proven)

Overlay workflow while watching YouTube with Japanese on screen:

1. Trigger a screen-selection overlay **without leaving YouTube**.
2. Circle / drag-select a region containing Japanese text.
3. Capture the region and OCR the Japanese text **on-device**.
4. Show the result in a **simple custom popup** (not a full screen).
5. In the popup, show **mocked** dictionary data: kanji, hiragana/kana, English meaning,
   plus an "Add to Anki" button (button can be a no-op stub; no Anki integration yet).

This is a **prototype / proof of concept**, not a production app. Keep dependencies and
code changes minimal. Reuse the existing repo's capture, selection, and OCR code wherever
possible instead of writing new pipelines.

## Starting repo (already cloned here)

This directory **is** a clone of https://github.com/AKS-Labs/CircleToSearch
(`git remote -v` to confirm; branch `main`, commit `d9ee98d`).

Upstream is a GPLv3 "Circle to Search" app: draw over the screen, crop, then reverse-image
search or copy OCR'd text. Stack: Kotlin, Jetpack Compose, minSdk 29, targetSdk 36,
AGP/Gradle wrapper, `com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.7.0`
for on-device OCR.

### Key files (read these first)

- `app/src/main/AndroidManifest.xml` — services and permissions. Note the accessibility
  service (`CircleToSearchAccessibilityService`) and voice/assist services.
- `app/src/main/res/xml/accessibility_service_config.xml` — **`canTakeScreenshot="true"`
  and `canRetrieveWindowContent="true"`**. This is how the app captures.
- `app/src/main/java/com/akslabs/circletosearch/CircleToSearchAccessibilityService.kt` —
  the trigger + capture core. `takeScreenshot(...)` is called around line ~695 via
  `AccessibilityService.takeScreenshot` (API 30+, no MediaProjection, no consent dialog).
- `app/src/main/java/com/akslabs/circletosearch/AssistSessionService.kt` — capture via the
  assist/default-assistant path (`BitmapRepository.setScreenshot`, ~line 76).
- `app/src/main/java/com/akslabs/circletosearch/data/BitmapRepository.kt` — in-memory
  screenshot holder (singleton bitmap). Consider memory size for a full-res screen bitmap.
- `app/src/main/java/com/akslabs/circletosearch/OverlayActivity.kt` — transparent overlay
  activity that draws the captured bitmap and hosts the selection UI.
- `app/src/main/java/com/akslabs/circletosearch/ui/CircleToSearchScreen.kt` and
  `ui/components/CopyTextOverlayManager.kt` / `CopyTextOverlayManagerOG.kt` — region
  selection + the existing "copy text" OCR flow to reuse.
- `app/src/main/java/com/akslabs/circletosearch/ocr/TesseractEngine.kt` — Tesseract
  wrapper. `prepareTessData()` copies `assets/tessdata/eng.traineddata` to `filesDir/tessdata`;
  `extractWords()` uses `prefs.getString("selected_lang", "eng")`; the engine can also
  import external `.traineddata` files (see `ui/OcrSettingsScreen.kt`).
- `app/src/main/assets/tessdata/eng.traineddata` — **the only language data present.**
- `app/src/main/java/com/akslabs/circletosearch/ui/OcrSettingsScreen.kt` — language
  picker; import an external model here at runtime as a quick test path.
- `app/src/main/java/com/akslabs/circletosearch/CircleToSearchTileService.kt`,
  `TileTriggerActivity.kt`, `CircleToSearchVoiceService.kt` — alternative triggers.

## Current state

- Repo cloned; no changes made yet.
- No Android device is attached to the build machine (`adb devices` is empty), and the
  OnePlus Nord 6 is not reachable from here. **The requester must run the on-device tests.**
- Java 17 and the Android SDK are installed at `/home/Qui/Android/Sdk`
  (`sdkmanager`, `adb`, `emulator` on PATH). Gradle comes from the repo wrapper
  (`./gradlew`); there is no system `gradle`.
- Toolchain note: build JDK is 17; `compileSdk`/AGP may want a newer JDK. If the build
  fails on Java version, record the error and try the Android Studio JBR if present
  (e.g. `/opt/android-studio/jbr` or `~/android-studio/jbr`) instead of hacking versions.

## What is already known / decisions so far

- Capture path to evaluate first: `AccessibilityService.takeScreenshot()` (already wired,
  `canTakeScreenshot="true"`). It is the modern supported API, needs no MediaProjection
  consent dialog, and respects **content protection / FLAG_SECURE** — if a surface is
  protected the OS returns `ERROR_TAKE_SCREENSHOT_*`; **do not try to bypass that.**
  Report it as a blocker instead.
- Only `eng.traineddata` ships. Japanese needs `jpn.traineddata` (from the official
  `tesseract-ocr/tessdata_fast` or `tessdata_best` repos). Adding it to
  `app/src/main/assets/tessdata/` and selecting `jpn` (or `jpn+eng`) is the expected OCR
  change; the existing import-external-model screen is a zero-build alternative for the
  first on-device test.
- Dictionary lookup is **mocked**, hardcoded. No real dictionary, no Anki API.
- Popup should be a small Compose surface, not a full activity screen.
- YouTube subtitles are drawn by the app; embedded burned-in video text is just pixels.
  Accessibility `getWindows()` / node tree will **not** reliably expose YouTube caption
  text (YouTube doesn't expose them as nodes to other apps). So OCR of the captured
  bitmap is the target, not the accessibility text tree. Verify which of the two the
  capture actually contains.
- Physical "Plus" button on the Nord 6: only investigate if trivial (OxygenOS may map it
  to the camera/assistant and may not be remappable to a third-party app). Do not sink
  time into it. Prefer trigger via the existing accessibility service / floating bubble /
  QS tile / default-assistant. Note the simplest reliable trigger for the report.

## Ordered plan — stop and report at the gate

**Gate A — capture exists at all.**
1. Build a debug APK (`./gradlew :app:assembleDebug`) to prove the toolchain works.
2. Give the requester install + enable steps.
3. Verify `takeScreenshot()` succeeds over YouTube (not just on the launcher/emulator):
   the captured bitmap must be non-null, full-screen, and show YouTube content.

**Gate B — is YouTube text actually in the capture?**
4. Save the raw full-screen capture to a file (`adb pull`), open it, and confirm whether
   (a) YouTube subtitles and (b) burned-in Japanese video text are legible.
   Ordinary screenshots/emulator runs/compilation do NOT prove this — only the phone does.

**Gate C — Japanese OCR.**
5. Add/select `jpn.traineddata`, crop the selected region, run Tesseract, and check the
   recognized Japanese string against what is on screen (include both in the report).

**Gate D — popup + mocked dictionary.**
6. Only after A–C pass: wire region selection → OCR text → small popup with mock
   kanji/reading/meaning + an "Add to Anki" stub button.

If Gate A or B fails, **stop building features** and write up the blocker, root cause, and
the simplest alternative (see below).

## Acceptance criteria

- [ ] `./gradlew :app:assembleDebug` succeeds and an APK path is reported.
- [ ] The report states the status of **each** stage: activation, capture, visible
      YouTube text, region selection, Japanese OCR, popup.
- [ ] Evidence attached/committed: raw captured PNG(s), exact OCR output strings,
      `adb logcat` excerpts, and the Tesseract `jpn` model source/version.
- [ ] Clear step-by-step testing instructions for the OnePlus Nord 6 (enable
      accessibility, grant/deny permissions, how to trigger over YouTube).
- [ ] Blockers listed with cause and the simplest fallback.
- [ ] Anything not runnable on the physical phone is explicitly labelled **UNVERIFIED**
      (emulator results do not count as proof of the on-device capture path).

## Constraints

- Android only; no iOS work.
- Respect Android permissions and content-protection/DRM restrictions. Never attempt to
  bypass FLAG_SECURE or DRM.
- Minimal diff and minimal new dependencies. Reuse existing components.
- Mock dictionary only; do not implement a real dictionary or Anki integration.
- Do not claim success from compilation or emulator runs alone.
- Keep the `app/src/main/assets/tessdata/` size sane (use `tessdata_fast`'s jpn if
  possible; note the file size in the report).

## Suggested evidence layout

```
docs/jp-prototype/
  REPORT.md            # the stage-by-stage verdict
  capture-full.png     # raw takeScreenshot() output over YouTube
  capture-region.png   # the cropped selection
  ocr-output.txt       # raw Tesseract text
  logcat.txt           # relevant logs
```

## Deliverable summary

1. Buildable debug APK + source changes (or a precise explanation of what blocks the build).
2. Short report: activation, capture, visible YouTube text, region selection, Japanese OCR,
   popup — works / fails / unverified, with the evidence above.
3. Testing instructions for the OnePlus Nord 6.
4. Blockers, causes, simplest alternative.
