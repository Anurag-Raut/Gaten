# WaniKani settings evidence

Captured on 26 October 2026 on `emulator-5554` (Android 14, x86_64, 1080 × 2340 at 440 dpi, font
scale 0.94) from the working tree committed as the source revision below — the same tree the whole
PR is reviewed from.

| Fact | Value |
| --- | --- |
| Source revision (UI commit) | `20bec14` (contained in the capture revision `03a0c16`) |
| Build | `flock /tmp/gaten-redesign-gradle.lock ./gradlew :app:assembleDebug` |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `1a6e389936626b2f9f770105e5dc7d2e178383d291f5003b9c8b99d3e755627e`, versionCode 10 |
| Installed | `adb install -r` (debug signature `c5bcc30b…`, the same one the fixture already had) |
| Driver | `agent-device 0.21.31` (`open`, `click`/`fill`/`press --settle`, `wait text`, `screenshot`, `record`) |
| Freshness | the installed APK's `assets/index.android.bundle` contains this change's copy, and the running UI shows it |

| State | Page | After Save | Recording |
| --- | --- | --- | --- |
| Disconnected (isolated user) | [after-disconnected-top.png](after-disconnected-top.png) | [after-disconnected-saved.png](after-disconnected-saved.png) | [wk-save-blank-token-disconnected.mp4](wk-save-blank-token-disconnected.mp4) · [contact sheet](wk-save-blank-token-disconnected-sheet.png) |
| Connected (owner's fixture) | [after-connected-top.png](after-connected-top.png) | [after-connected-saved.png](after-connected-saved.png) | [wk-save-blank-token-connected.mp4](wk-save-blank-token-connected.mp4) · [contact sheet](wk-save-blank-token-connected-sheet.png) |

## What the captures show

- **Whole page, disconnected.** `Connection` carries the state as a composition — `cloud_off` in the
  neutral ink, the service name, `Not connected`, and one sentence saying what connecting buys the
  reader — instead of a bold line on its own. Group hairlines, the field, its two hints, the `Token`
  section with `Get a token` as a text link, and **one filled `Save` at the end** of the form, 24 dp
  below the section rule. The whole page fits one screen at this size; nothing is cut off or clipped.
- **Saving the lesson limit with a blank token while disconnected** (recording, 9.4 s): the field is
  typed into, the keyboard is dismissed, `Save` is tapped, `Saved.` appears under the hints, and the
  connection stays `Not connected` — no token was sent, no error, no state change.
- **Connected.** `check_circle` in the success ink with `Connected`, the token section hidden behind
  the quiet `Change token` action, `Recheck` and `Disconnect` as separate text actions, and the same
  single `Save`. The account is the owner's existing fixture, untouched: the page reads `Connected`
  after the app data was carried over an in-place install, and Home still reports the same
  `96 reviews due`.
- **Saving with a blank token while connected** (recording, 6.5 s): `Change token` opens the token
  field (empty), `Save` is tapped with that field empty, `Saved.` appears, and the page stays
  `Connected` with `Disconnect` still offered — the stored token was neither replaced nor erased.

## How the disconnected state was captured without touching the owner's account

`pm create-user wk_disconnected` created an isolated secondary Android user, the APK was installed
for it (`adb install --user 10 -r`), and the capture ran there; the owner's user 0, its AnkiDroid
link and its WaniKani token were never cleared, disconnected or opened for editing. The user was
removed afterwards (`pm remove-user 10`), which deletes only that user's data.

## Not exercised

- 320 dp / 160 % text and TalkBack; the wrapping and touch-target behaviour at those settings is
  reasoned from the shared patterns, not observed.
- Entering a real token, and a rejected or unreachable token: no credentials were requested, exposed
  or typed. The rejected-token path is covered by the existing code and its unchanged copy.
- The `Retry` path for a failed lesson-preference read.
