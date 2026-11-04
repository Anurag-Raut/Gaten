# WaniKani review: spaced prompts and a correct-answer stop — device evidence

## At a glance

| # | Change | Rendered here |
| --- | --- | --- |
| 1 | A subject's meaning and reading prompts are asked 5–10 prompts apart (never back to back), by an explicit prompt order the review walks. | **Not captured — blocked, see below.** |
| 2 | A correct answer stops on its own card with the answer in the field; Enter or the footer's **Continue** moves on, and the keyboard does not drop. | **Not captured — blocked, see below.** |

Both changes are covered by `tests/wanikani-review-prompt-order.test.cjs` (5 tests) and by the
unchanged unit/type gates; the device captures the acceptance asks for could not be taken because
this AVD no longer carries a connected WaniKani account. `blocked-01` and `blocked-02` are the
evidence of that block, taken from the build under test.

## Device

| | |
| --- | --- |
| Emulator | `emulator-5554` (AVD `Gaten_Lookup_Review`, x86_64) |
| Android | 14 (API 34), model `sdk_gphone64_x86_64` |
| Display | 1080×2340 @ 440 dpi, `font_scale` 0.94, light mode |
| Keyboard | Gboard (`com.google.android.inputmethod.latin`), software keyboard forced on with `settings put secure show_ime_with_hard_keyboard 1` |
| Driver | `agent-device` 0.21.31 present but not used; every device step ran as `adb` inside one `flock /tmp/gaten-emulator.lock` block, with the emulator killed before the block ended |

## Builds

| | After (this change) | Before (base) |
| --- | --- | --- |
| Source | branch `wanikani-review-feedback`, commit `f024f2d` | base `e2a537f` in a throwaway worktree at `/home/Qui/gaten-wk-review-before` |
| Build | `ANDROID_HOME=$HOME/Android/Sdk flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug :app:testDebugUnitTest -PreactNativeArchitectures=x86_64` → `BUILD SUCCESSFUL in 1m 15s` | `… ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` → `BUILD SUCCESSFUL in 1m 25s` |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 205,471,372 bytes, sha256 `abfded2d1c0a005b15583149f663c5668660697c60365c5631f1292984f3eb6e` | same path, 205,468,312 bytes, sha256 `3415773eb5d565a019a85872b23d66c938f77f507035471abd91a0306ff7c7c3` |
| JS bundle in that APK | sha256 `9a705f6753417662ccb96d334e2c9738bff97f1ed6b00a942b8da8a6fee2d8f4` (3,404,692 bytes) | sha256 `a46a87bc579b1955afed5cc87d70723004603ac6b0920f869f44942e00ed2c0c` (3,401,896 bytes) |
| Install | `adb -s emulator-5554 install -r -d app-x86_64-debug.apk` → `Success`; the app launched to Home | not installed (the block was found before the base build was ever needed) |
| Unit tests | `:app:testDebugUnitTest` → 618 tests, 0 failures, 0 errors | not run (no source change in the base tree) |

Debug variants embed the JS bundle; no Metro server was involved.

## Account state

**The AVD is not connected to WaniKani.** The Home card reads `WaniKani · Not connected`
(`blocked-01`), and the connect screen shows an empty token field (`blocked-02`). On-device proof
that no token is stored — `KeystoreCredentials` keeps its ciphertext in `noBackupFilesDir`:

```
$ adb -s emulator-5554 shell run-as com.gaten ls -la /data/data/com.gaten/no_backup
total 16
drwxrwx--x 2 u0_a201 u0_a201 4096 2026-10-31 21:34 .
drwx------ 9 u0_a201 u0_a201 4096 2026-10-31 21:41 ..
```

`shared_prefs` holds only `com.google.mlkit.internal.xml`, `gaten_dictionary_history.xml` and
`gaten_settings.xml` — no WaniKani file. The AVD's app data carries the sibling OCR work
(`files/manga-ocr`, `profileInstalled`), so the app was reinstalled after the previous review
evidence was taken and the token went with it.

**No review was read, answered or submitted.** With no token there is no queue to read, so nothing
on the account moved.

## Files

| File | Shows |
| --- | --- |
| `blocked-01-home-wanikani-not-connected.png` | The build under test running on `Gaten_Lookup_Review`: Home's WaniKani card reads **Not connected**, so no review session can be started. |
| `blocked-02-wanikani-connect-empty.png` | The WaniKani connect screen with an empty **Personal access token** field — the state the token would have to be entered in. |

## What was not captured

- **Both required recordings and the correct-answer stills.** They need a WaniKani review session,
  which needs a connected account, which this AVD no longer has. A token is not available in the
  repository, the worktree or the machine's history, and the account's API token cannot be re-read
  from WaniKani's settings page. Faking the session was not an option.
- **The before/after comparison on device.** The base APK was built but never installed, because
  there was nothing to capture on either side of the change.
