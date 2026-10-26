# Review header evidence — centred title

Captured for PR #35 (`fix/center-heading-ticktick`): the review flow's header title is centred on the
screen while the back control keeps the left edge and the progress count the right edge.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `3c51ea7` — `fix(review): centre the review header title between the back control and the count` (rebased on `origin/dev` `d6364ee`) |
| JS actually running | Metro dev server started from this worktree on port 8081; `index.bundle` served by it contains `subHeaderRightLane` |
| App | `com.gaten` 0.1.0 (debuggable debug build, versionCode 10) |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080x2340 @ 440 dpi, system font scale 0.94 |
| Anki data | AnkiDroid 2.25.2, deck **Gaten** with 2 new cards due |

## Steps

1. `agent-device boot --device Gaten_Lookup_Review --headless`, then `adb reverse tcp:8081 tcp:8081`.
2. `npx react-native start --port 8081` in this worktree; `agent-device open com.gaten --platform android --foreground --relaunch --metro-port 8081`.
3. Dismissed the LogBox banner (`agent-device react-native dismiss-overlay`).
4. Home → **Anki** card (`Anki, Connected, 2 reviews due`) → deck picker → deck **Gaten** → question
   card 猫 with **Show answer** → tapped it to reveal the answer.
5. Captures at each step, plus a short recording of the reveal; no card was graded (leaving a session
   grades nothing, and the session was left and re-entered to record the reveal from the question side).

## Results

| File | Screen | Title measured | Offset from screen centre |
|---|---|---|---|
| `01-anki-question.png` | question phase — deck name `Gaten`, `1 of 2` | x 494–585 of 1080 | **−0.5 px** |
| `02-anki-answer.png` | answer phase — `Answer`, `1 of 2` | x 480–599 of 1080 | **−0.5 px** |
| `03-anki-deck-picker.png` | deck picker — `Anki`, no count | x 505–574 of 1080 | **−0.5 px** |
| `05-answer-font-scale-1.5.png` | answer phase at system font scale 1.5 | x 441–638 of 1080 | **−0.5 px** |
| `06-answer-narrow-360dp-font-scale-1.5.png` | answer phase, 360 dp wide (720x1600 @ 320 dpi) and font scale 1.5 | x 289–431 of 720 | **0.0 px** |
| `04-anki-reveal.mp4` | 8.0 s recording: question → reveal → answer (Android `screenrecord` emits a frame only when the screen changes, so the file is 10 frames) | — | — |

Offsets are the title's ink centre against the capture's own centre, measured from the PNGs in this
directory (header band only, the back glyph and the count excluded). The 0.5 px at 1080 is the
even/odd-width rounding of a symmetric row, not a layout shift.

Visible in the captures: the back arrow sits at the left inset, `1 of 2` at the right inset, the
progress hairline below, and the page headings that are *not* the review header (`Choose a deck`)
stay left-aligned.

## Limitations

- One deck with two new cards on this emulator; the header was exercised on the Anki question, answer
  and deck-picker steps, not on a WaniKani lesson/review (same `SubHeader`, not opened here).
- Narrow/large-font cases were produced with `wm size` / `wm density` / `settings put system
  font_scale` overrides and then reverted (`wm size reset`, `wm density reset`, font scale 0.94); they
  are a phone-sized emulator, not a second physical device.
- No grading was performed, so no Anki collection state was changed by this pass.
