# WaniKani review: a correct answer stops, and Enter continues — device evidence

Captured on the review screen of the head build, on a live connected WaniKani account. The personal
access token is never shown, written into this repo, or included in any capture; the connect screen and
the settings token field are not photographed at all.

## Provenance

| Field | Value |
| --- | --- |
| Code under test | branch `wanikani-review-feedback`; implementation commit `f024f2d`; branch head at capture time `da0ea66` (every commit after it touches `docs/pr-evidence/` only) |
| Base | `e2a537f` (`origin/dev`) |
| Device | serial `emulator-5556`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, `font_scale` 0.94, headless (`-no-window`) |
| Theme | system light mode for `04-home-connected.png`; system dark mode for the review stills `01`–`03` |
| IME | `com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME` (Gboard), with `show_ime_with_hard_keyboard 1` |
| Build | `ANDROID_HOME=$HOME/Android/Sdk flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` → `BUILD SUCCESSFUL in 19s` |
| APK under test | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — 205,471,372 bytes, sha256 `abfded2d1c0a005b15583149f663c5668660697c60365c5631f1292984f3eb6e`; the installed `base.apk` was re-hashed on-device (`run-as com.gaten sha256sum`) to the same value by the agent driving the capture |
| Install | `adb -s emulator-5556 install -r -d` → `Success`; `-r` only — no `uninstall`, no `pm clear`, so the account in app-private storage survived |
| Account | WaniKani **connected** (`no_backup/wanikani/token` present, `0600`) — recorded as `connected (token not shown)` |
| Driver | `adb`: `monkey -p com.gaten -c android.intent.category.LAUNCHER 1` to launch, `input tap` at screenshot-derived coordinates, `input text`, `input keyevent 66`, `screencap` (and one `screenrecord`, not shipped) |

Why taps are by coordinate: `uiautomator dump` cannot see this app's React Native content — the only
nodes it exposes are the bottom bar's `content-desc`. Every tap was placed after looking at a
screenshot.

## What the captures show

| File | Shows |
| --- | --- |
| `04-home-connected.png` | Home with the account live: **WaniKani · Connected · 51 reviews due** (token nowhere on screen). |
| `01-review-card-meaning.png` | The review screen live: `1 of 98`, subject 垂直 (Vocabulary, Level 26), the **Meaning** prompt, "Type the meaning", keyboard up. |
| `02-correct-answer-stop.png` | **The behaviour change.** After typing `vertical` correctly the card **stops** on its own prompt: still `1 of 98`, the answer in the field, `✓ Correct`, and a **Continue** button. It did not auto-forward. |
| `03-after-enter-advanced.png` | After Enter: `2 of 98`, subject 占い — the same pass moves on, so the stop and the continue are both real. |

## Reviews submitted to the account

**None.** Three independent checks:

- The header advanced `1 of 98` → `2 of 98`, i.e. 垂直's meaning → 占い's meaning. Only a subject's
  *last* part posts its review, and a submission would have moved the cursor past 占い to `3 of 98`
  (召). Nothing was sent.
- The due set is unchanged: `GET /v2/assignments?immediately_available_for_review=true&hidden=false`
  → `total_count: 51` before the capture and `51` after it, and `GET /v2/summary` still shows the
  current bucket at 51 subjects.
- `GET /v2/reviews` → `total_count: 0` for this token: no review has ever been posted through the API
  on this account.

Exactly one subject's meaning was answered (`vertical`) and nothing else was typed.

## Not exercised / limitations

- **No recording.** One clip was recorded, but it was pulled before `screenrecord` finalised it, so it
  is truncated and undecodable (`moov atom not found`) and is not shipped. Re-recording was declined
  because it needs another correct answer, i.e. another review on the live account. The interaction is
  evidenced by `02` and `03` instead.
- **The 5–10 prompt spacing window is not device-demonstrated.** Reaching a subject's reading prompt
  takes roughly six or more correct answers on the live account. The window is covered by
  `tests/wanikani-review-prompt-order.test.cjs` (5/5), which asserts the gap over queues of 5–101
  subjects, the non-adjacent short-queue fallback, radicals sharing the queue, the correct-answer stop
  and a meaning miss surviving the gap.
- **No "before" still.** The base build's auto-advance was not captured; the base APK is built and
  staged at `/home/Qui/gaten-wk-review-before/app/build/outputs/apk/debug/app-x86_64-debug.apk`
  (sha256 `3415773eb5d565a019a85872b23d66c938f77f507035471abd91a0306ff7c7c3`).
- **No still of the keyboard staying up across the stop.** Enter was sent with `input keyevent 66`,
  which dismisses the IME on this device, so the keyboard is absent from `02`/`03` even though the
  field keeps its focus, its answer and its Continue. `01` shows the keyboard before any Enter.
- Not driven: the end of the queue (`finishWaniKani`), the paused-session resume row, the lesson quiz,
  the wrong-answer path, the `submitFailed` retry, and the offline / rate-limited / rejected-token
  states.
- No accessibility-tree evidence (the RN views expose no text nodes), and no physical device — one
  x86_64 emulator with a software GPU.
