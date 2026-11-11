# WaniKani settings evidence

Captured on 26 October 2026 on `emulator-5554` (Android 14, x86_64, 1080 × 2340 at 440 dpi, font
scale 0.94, night mode off) with `agent-device 0.21.31`.

| Fact | Value |
| --- | --- |
| Captured source revision | `f78f3d4` |
| Build | `flock /tmp/gaten-redesign-gradle.lock ./gradlew :app:assembleDebug` |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `3eaa4fa4122124c3131c7addb86112d859cf9fe3ba711e90babcaab593a67748`, versionCode 10 — installed with `adb install -r` (owner) and `adb install --user 12 -r` (isolated), and the on-device `base.apk` hash was checked against this file |
| Disconnected captures | an isolated secondary Android user (`pm create-user` + `am switch-user`, removed afterwards), so the owner's account was never cleared, disconnected or edited |
| Connected captures | the owner's own user 0, in place install over the previous build (same debug signature), fixture preserved |

| State | Files |
| --- | --- |
| Disconnected — default goal | [after-disconnected-default.png](after-disconnected-default.png) |
| Disconnected — 20 typed | [after-disconnected-filled.png](after-disconnected-filled.png) |
| Disconnected — refusals | [0](after-disconnected-error-zero.png) · [21](after-disconnected-error-21.png) · [blank](after-disconnected-error-blank.png) |
| Disconnected — after saving 20 and reopening | [after-disconnected-persisted.png](after-disconnected-persisted.png) |
| Connected — page | [after-connected-top.png](after-connected-top.png) |
| Connected — empty-token save | [after-connected-saved.png](after-connected-saved.png) · [recording](wk-save-blank-token-connected.mp4) ([sheet](wk-save-blank-token-connected-sheet.png), 5.4 s) |
| Shared field on an existing page — long template draft | [box at the cap](after-settings-anki-long-template.png) · [scrolled inside](after-settings-anki-long-template-scrolled.png) |

## What the captures show

- **Filled fields, no outline.** The goal and token fields sit on the quiet filled surface with the
  box's radius, no border at rest and no ring when focused — the goal field shows the caret alone when
  it holds focus. The hierarchy reads: `Lessons` in the quiet 12 / 18 label, `New per day` as the
  field's own stronger name.
- **The default goal.** With nothing stored the field shows **15** — the goal the device works to —
  and the section carries nothing else: no placeholder, no helper line, no learned-today count, no
  success line. One filled `Save` closes the form.
- **The inset is the box's.** On the typed `20`, first ink sits at x = 107 px with the column edge at
  22 dp (61 px) and the field's fill radius accounting for the rest — the box's own 16 dp inset, not
  the platform's.
- **The range, observed live in the isolated user:** `1` saved with no error, `20` saved and read back
  as `20` after leaving and reopening the page, and `0`, `21` and blank each refused with
  **"Use 1–20."** — the message shows in the three refusal captures, and blank is allowed while
  editing and only refused when saved.
- **The long template draft.** A 288-character, 25-line value was typed into the Back (HTML) editor:
  the box measured 114 dp at the fill's own column (the 120 dp cap less its corner rounding) with the
  next field (`Styling (CSS)`) clear below it, and a swipe inside the box changed 44,510 pixels
  *inside* it while its geometry stayed identical — the text scrolls in the box rather than growing
  it. The draft was then discarded through the page's own "Discard changes?" dialog (which says the
  card-layout edits are not saved), and the Back value read back as the original
  `{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}`. This ran in the isolated user, so the
  owner's card configuration was never involved.
- **The connected fixture is intact.** After the in-place install the page still reads `Connected`
  (the owner's stored token survives), and `Change token` → `Save` with the token field left empty
  leaves it `Connected` with `Disconnect` still offered — the stored token is neither replaced nor
  erased.

## Not exercised

- **A legacy stored goal** (a `0` meaning "unlimited", or a goal above 20): those reads are covered by
  the preference-layer unit test's boundary cases, not by a capture — the isolated user starts unset
  and the owner's device had nothing stored, so no legacy number was present to photograph.
- 320 dp / 160 % text and TalkBack.
- Entering a real token, and a genuinely unreachable WaniKani: no credentials were requested, exposed
  or typed.
- The `Retry` path for a failed goal read, which needs a storage failure to trigger.
- The Save predicate has no automated test; the three guards are stated in the pull request.
- The Anki "Note type" selector keeps its outlined box: it is a menu, not a text field, and the
  reader's change was to input fields.

## Restored after the capture

The isolated user was removed, the current user is 0 as found, 1080 × 2340 / 440 dpi / font scale 0.94
are unchanged, the owner's daily goal is the value the device reads (15, nothing stored), the
fixture's account and AnkiDroid link are untouched, the `agent-device` session was closed and no Metro
server was started.
