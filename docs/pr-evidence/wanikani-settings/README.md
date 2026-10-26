# WaniKani settings evidence

> **Superseded by the reader's later styling and goal changes.** These captures are from `dc64529`:
> they show the outlined fields with the `Unlimited` placeholder and the old 1–1000 goal, which this
> branch has since replaced with a filled, outline-free field and a goal of 1–20 that is 15 until the
> reader sets one. What they still show is the behaviour that did not change — one Save, the
> connection composition, the blank-token save, no success line, the measured field inset — and the
> whole set is replaced by a capture of the current head in the next emulator window.

Captured on 26 October 2026 on `emulator-5554` (Android 14, x86_64, 1080 × 2340 at 440 dpi, font
scale 0.94), driven with `agent-device 0.21.31`.

| Fact | Value |
| --- | --- |
| Captured source revision | `dc64529` (the fix commit; the evidence commit that adds these files follows it) |
| Build | `flock /tmp/gaten-redesign-gradle.lock ./gradlew :app:assembleDebug` |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `fac7009a5f02f36819117391f0467f8168b7837b02c5f4a61d7e1c141c4f132e`, versionCode 10 |
| Installed | `adb install -r` for the owner's user 0, and `adb install --user 11 -r` in an isolated user |

| State | Files |
| --- | --- |
| Disconnected, isolated user | [empty](after-disconnected-empty.png) · [20 typed](after-disconnected-filled.png) · [invalid limit](after-disconnected-error.png) · [saved](after-disconnected-saved.png) · [reopened](after-disconnected-persisted.png) · [recording](wk-save-blank-token-disconnected.mp4) ([sheet](wk-save-blank-token-disconnected-sheet.png), 5.1 s) |
| Connected, owner's fixture | [page](after-connected-top.png) · [after save](after-connected-saved.png) · [recording](wk-save-blank-token-connected.mp4) ([sheet](wk-save-blank-token-connected-sheet.png), 4.4 s) |
| Shared field on an existing page (Anki cards) | [Deck and Tags](after-settings-anki-fields.png) · [template editors](after-settings-anki-templates.png) |

## What the captures show

- **Hierarchy, not two labels that look alike.** `Lessons` stays the quiet 12 / 18 section heading;
  `New per day` is the field's own name at `labelLarge` in the surface ink. The same role reaches
  every form using the shared label — Anki's Deck, Tags and monospace template editors, and the
  review flow's token field — which is why the Anki page is captured here beside this screen.
- **Nothing the field already says.** The Lessons section is the field and its `Unlimited`
  placeholder and nothing else: no "Blank = unlimited." line, no learned-today count, no success
  line. A save shows through the button's own `Saving… → Save` round-trip, and persistence is proved
  by leaving and reopening the page (`after-disconnected-persisted.png` shows `20` back with no prose
  anywhere). Failures keep their ink under the field they belong to:
  `after-disconnected-error.png` carries "Use 1–1000, or leave blank.", and a deliberately garbage
  token submitted from the isolated user keeps its rejection under the Token section.
- **The field inset, measured before and after.** With the box's padding on the input itself, the
  *entered* value rendered at the platform's own inset while the placeholder used the box's. On this
  device, with the box border at x = 61 px (2.75 px/dp): the typed `20` began at x = 86 px (9.1 dp),
  the password dots at x = 77 px (5.8 dp), and the placeholder at x = 110 px (17.8 dp). With the
  inset on the wrapper that sizes the input, both agree: `20` at x = 121 px (21.8 dp) and the dots at
  x = 112 px (18.5 dp). The same measurement on the Anki page's own fields — Deck x = 109 px, Tags
  x = 109–116 px (17.5–20 dp) — shows the component's existing callers unchanged, and the multiline
  card-layout editors keep their 60–120 dp box with the text inset inside it.
- **The inset is not dead space.** A tap 14 px inside the box's left padding focuses the field
  (the snapshot reports the input `[focused]`), so the box still behaves as one target.
- **The connected flow is intact.** After the in-place install the owner's fixture still reads
  `Connected`, and Home still reports the same `96 reviews due`; `Change token`, `Recheck` and
  `Disconnect` stay separate text actions, and saving with an empty token field leaves the page
  `Connected` with `Disconnect` still offered — the stored token is neither replaced nor erased.

## Not exercised

- 320 dp / 160 % text and TalkBack.
- Entering a real token, and a genuinely unreachable WaniKani: no credentials were requested,
  exposed or typed. The rejection line is visible in the capture only because the isolated user
  submitted a deliberately invalid value.
- The `Retry` path for a failed lesson-preference read, which needs a storage failure to trigger.
- The Save predicate has no automated test; the three guards (blank token never sent, no write on an
  unread preference, invalid limit blocked) are stated in the pull request instead.

## Restored after the capture

The isolated user was removed, the current user is 0 as found, 1080 × 2340 / 440 dpi / font scale
0.94 are unchanged, the fixture's lesson limit is left at blank = unlimited, the `agent-device`
session was closed and no Metro server was started.
