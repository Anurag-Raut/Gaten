# WaniKani review spacing + correct-answer stop — device evidence

Captured on a live connected WaniKani account, on the review screen, for the change that spaces a
subject's meaning and reading apart and stops a correct answer behind **Continue** instead of
auto-forwarding. The account's token is never shown, written to the repo, or included in any capture.

## Provenance

| Field | Value |
| --- | --- |
| Head | `da0ea66ffeb82b347692c71960a54004115e2984` (branch `wanikani-review-feedback`) |
| Base | `e2a537f` (`origin/dev`) |
| Device | serial `emulator-5556`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, dark theme, headless (`-no-window`) |
| IME | `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` (Gboard) |
| Build | `ANDROID_HOME=$HOME/Android/Sdk flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` → BUILD SUCCESSFUL |
| APK under test | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — 205,471,372 bytes, `sha256 abfded2d1c0a005b15583149f663c5668660697c60365c5631f1292984f3eb6e`; the installed `base.apk` was re-hashed on-device (`run-as com.gaten sha256sum`) to this same value before the captures |
| Install | `adb install -r -d` → `Success` (the stored token survives `-r`; no `pm clear`, no uninstall was run) |
| Account | WaniKani **connected** (`no_backup/wanikani/token` present) — recorded as `connected (token not shown)` |
| Driver | `adb` + `monkey -p com.gaten -c android.intent.category.LAUNCHER 1`, `input tap` at screenshot-derived coordinates, `input text`/`keyevent 66`, `screencap`/`screenrecord` |

Why coordinates: `uiautomator dump` cannot read this app's React Native content — the only nodes it
exposes are the bottom bar's `content-desc`. Every tap was chosen by looking at a screenshot first.

## What the captures show

| File | Shows |
| --- | --- |
| `04-home-connected.png` | Home with the account live: **WaniKani · Connected · 51 reviews due**. |
| `01-review-card-meaning.png` | The review screen: `1 of 98`, subject 垂直 (Vocabulary, Level 26), the **Meaning** prompt, "Type the meaning", keyboard up. |
| `02-correct-answer-stop.png` | **The behaviour change.** After typing `vertical` the card **stops**: still `1 of 98`, the answer in green, `✓ Correct`, and a **Continue** button — it did not auto-forward. |
| `03-after-enter-advanced.png` | After pressing Enter: `2 of 98`, subject 占い — Enter continues and advances. |

**One review was submitted to the account during this capture** (垂直, answered `vertical`), because
answering a subject's final part posts that subject's review. Nothing else was answered.

## Not exercised / limitations

- **No recording.** A clip was recorded, but it was pulled from the device before `screenrecord`
  finished, so it is truncated and undecodable (`moov atom not found`); it is not shipped. Re-recording
  was declined because it needs another correct answer, i.e. another review posted to the live account.
  The interaction is evidenced by the two stills (`02`, `03`) instead.
- **The 5–10 prompt spacing window is not device-demonstrated.** Reaching the reading prompt of a
  subject requires answering roughly six or more prompts correctly on the live account. The window is
  covered by `tests/wanikani-review-prompt-order.test.cjs` (5/5), which asserts the gap over queues of
  5–101 subjects, the non-adjacent short-queue fallback, and a meaning miss surviving the gap.
- **No "before" still** of the old auto-advance behaviour, and **no still showing the keyboard staying
  up** across the stop: Enter was sent with `input keyevent 66`, which dismisses the IME, so the
  keyboard-visible state cannot be shown from this capture even though the field does keep focus.
- No accessibility-tree evidence (the RN views expose no text nodes). Only the dark theme was captured.
