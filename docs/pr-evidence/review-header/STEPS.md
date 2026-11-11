# Review screen evidence — centred header and centred answer headword

Captured for PR #35 (`fix/center-heading-ticktick`): the review flow's header title is centred on the
screen with the back control left and the progress count right, and the answer's headword block
(expression + reading) stays centred across **Show answer** instead of jumping to the left edge.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `48f2acf` — the headword commit, on top of `3c51ea7` (centred header), rebased on `origin/dev` `d6364ee` |
| JS actually running | Metro dev server started from this worktree on port 8081; the `index.bundle` it served contains both `subHeaderRightLane` and `answerHeadword` |
| App | `com.gaten` 0.1.0 (debuggable debug build, versionCode 10) |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080x2340 @ 440 dpi, system font scale 0.94 (1.5 for the large-font capture) |
| Fixtures | AnkiDroid 2.25.2, deck **Gaten** with 2 new cards due; WaniKani connected, 96 reviews due. Nothing was answered in either flow |

## Steps

1. `agent-device boot --device Gaten_Lookup_Review --headless`, `adb reverse tcp:8081 tcp:8081`, then
   `npx react-native start --port 8081` in this worktree and
   `agent-device open com.gaten --platform android --foreground --relaunch --metro-port 8081`.
2. Dismissed the LogBox banner (`agent-device react-native dismiss-overlay`).
3. Home → **Anki** card → deck picker → deck **Gaten** → question card 猫 → **Show answer**:
   question capture, reveal recording, answer capture.
4. Repeated the answer step at system font scale 1.5, and again at 360 dp (720x1600 @ 320 dpi) with
   font scale 1.5, then reverted every override (`wm size reset`, `wm density reset`, font scale 0.94).
5. Home → **WaniKani** card → hub → **Reviews** row → the WaniKani review question (kanji 締,
   `1 of 96`), the other screen that shares the header.

## Results

Offsets are measured from the PNGs in this directory: the header band's title ink against the
capture's own centre, and the answer's expression/reading lines (row bands below the progress
hairline) against it.

| File | Screen | Title | Expression | Reading |
|---|---|---|---|---|
| `01-anki-question.png` | question — deck name `Gaten`, `1 of 2` | −0.5 px | — | — |
| `02-anki-answer.png` | answer — `Answer`, `1 of 2` | −0.5 px | **−1.5 px** | **−2.0 px** |
| `05-answer-font-scale-1.5.png` | answer at font scale 1.5 | −0.5 px | **−0.5 px** | **−4.0 px** |
| `06-answer-narrow-360dp-font-scale-1.5.png` | answer, 360 dp wide at font scale 1.5 | 0.0 px | **0.0 px** | **−2.0 px** |
| `03-anki-deck-picker.png` | deck picker — `Anki`, no count | −0.5 px | — | — |
| `07-wanikani-review-question.png` | WaniKani review question — `WaniKani`, `1 of 96` | −0.5 px | — | — |
| `04-anki-reveal.mp4` | 4.9 s recording: question → reveal → answer (Android `screenrecord` emits a frame only when the screen changes, so the file is 7 frames; its last frame measures like `02-anki-answer.png`) | | | |

Before this change the same measurement on the answer screen put the expression **428.5 px** and the
reading **438.0 px** left of centre, while the meaning block below sat at −73.5 px — that block is
unchanged by the fix and still measures −73.5 px (1080 wide) and −69.0 px (720 wide), so only the
headword moved. The reading's residual of 2–4 px is the kana glyphs' own ink asymmetry rather than a
layout offset: at the same 1080 px width it grows from −2.0 px to −4.0 px when the font scales 1.5×,
which a fixed padding could not do.

## Limitations

- One Anki deck with two new cards on this emulator. The header was exercised on the Anki question,
  answer and deck-picker steps and on the WaniKani review question; the WaniKani revealed/answer state
  and the WaniKani lesson pages (same `SubHeader`) were not driven.
- Narrow/large-font cases were produced with `wm size` / `wm density` / `settings put system
  font_scale` overrides and then reverted; they are a phone-sized emulator, not a second device.
- No grading was performed in either flow, so neither the Anki collection nor the WaniKani account
  state was changed by this pass.
