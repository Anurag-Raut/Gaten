# WaniKani answer feedback and the leave question — device evidence

Captured on a live connected WaniKani account, from this tree's own debug build on the shared
emulator. Two changes are shown: a judged answer now carries its own touch cue (a right answer and a
wrong one are different wave forms, and a wrong one also shakes the feedback line), and backing out
of the WaniKani **lesson** page now asks first — through the header's Back, through the system Back,
with **Stay** keeping the page and **Leave** exiting it.

The **review** page could not be captured: this account's review queue is empty (`0 reviews due · All
caught up`, `01-hub-before.png`), so there is no way to reach it. The lesson page is the reachable
half and exercises the same code paths — the same `Feedback` line, the same `useJudgedAnswer` cue and
the same `shouldConfirmLeaving` question in `ReviewScreen`. Which screen was and was not driven is
spelled out under *What this evidence does not establish*.

The personal access token is never shown, written into this repo, or included in any capture.

## Provenance

| Field | Value |
| --- | --- |
| Code under test | branch `feat/wanikani-wrong-answer-feedback`; item 1 `efb535f`, item 2 `149a0c1`; `HEAD^{tree}` `2953669a060857d00fb990c7a658a9bf161edb85` — the tree this APK and these captures were made from (every commit after it touches `docs/pr-evidence/` only) |
| Base | `b38ff19` (`origin/dev`) |
| Device | serial `emulator-5554`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, `font_scale` 0.94, headless; the one shared emulator, not a second AVD |
| Theme | system dark mode throughout |
| Haptics | `haptic_feedback_enabled 1`; a vibrator service with `AMPLITUDE_CONTROL` and a `Previous vibrations for usage TOUCH` history — see `vibrator-trace.txt` |
| Build | `./gradlew :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL` (the same command CI runs) |
| APK under test | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — 205,499,847 bytes, sha256 `18fe75105d447960dfde553e39ee3de351d140e5ecbab6c37743be1fed3d0b26`; installed with `agent-device install` (`-r`, no uninstall, so the connected account survived); the installed `/data/app/…/base.apk` was re-hashed **on the device** (`run-as com.gaten sha256sum base.apk`) to the same value |
| JS actually running | Metro from this worktree on port 8081 with `adb reverse tcp:8081 tcp:8081`; the served `index.bundle?platform=android&dev=true&minify=false` is HTTP 200, 10,440,609 bytes, sha256 `d20235b6de552e1e2d50ace0a1fc8aa241fbbcc4d6bf3af67b56a9cc1eae0bab`, and it contains `shouldConfirmLeaving` (6 occurrences) and `Leave the lesson?` (1) — so the running JS is this revision. (`Feedback pattern` appears 0 times in it because the cue is Kotlin, in the APK.) |
| Account | WaniKani **connected**; 0 reviews due; 47 lessons available at the start (`01-hub-before.png`) |
| Driver | agent-device (`install`, `open`, `press`, `type`, `keyboard`/`back`, `screenshot`, `record`, `logs`, `close`), taps placed by coordinate after reading a screenshot — `uiautomator` sees no text nodes of this app's RN content on this image |

## What the captures show

| File | Shows |
| --- | --- |
| `01-hub-before.png` | The WaniKani hub before anything was driven: **0 Reviews · All caught up · No reviews due**, **47 Lessons available · Next batch · 5 cards**, `Learned today · 2 of 15` |
| `02-lesson-teaching-card.png` | The lesson page: teaching card `1 of 5`, kanji 妙 (Level 28), its Meaning (Peculiar, Unusual, Odd, Strange), its Reading (みょう) and mnemonics, pinned **Next** |
| `03-lesson-leave-dialog.png` | **The question (new).** Header Back on the lesson page raises `Leave the lesson?` — *"Nothing is sent to WaniKani by leaving. The subjects you have not finished stay in your lessons, and the ones already started stay started."* — with `Stay` and `Leave` |
| `04-lesson-stayed.png` | After `Stay`: the dialog is gone and the lesson is where it was, same card, same progress — nothing was left |
| `05-lesson-left-to-home.png` | After `Leave`: the page is gone and Gaten's Home is showing, which is where Back already went |
| `06-lesson-system-back-dialog.png` | **The question (new), system Back.** The platform's Back gesture raises the same dialog on the lesson page, so the guard is not only the header's own control |
| `07-lesson-quiz-prompt.png` | The lesson quiz reached (five teaching cards passed): `1 of 5`, kanji 妙, the **Meaning** prompt, "Type the meaning", the disabled **Check answer** |
| `08-lesson-wrong-answer.png` | **The wrong answer (new).** After typing `zzz` and checking: the field is cleared, the answer line reads **Incorrect** in the error colour, and **Check answer** re-arms. This is the frame the shake starts from |
| `09-lesson-wrong-shake-frames.png` | Frame strip of `13-lesson-wrong-answer.mp4`: the answer line's own band at each encoded frame, with the frame's presentation time and the measured left edge — the move is visible and measured, not asserted |
| `10-lesson-right-to-reading.png` | **The right answer.** After typing `peculiar` and checking, the quiz moved from the subject's Meaning to its Reading (`こたえ`) — so the right cue fired without a subject being started (see *Account state*) |
| `11-hub-after.png` | The hub after the capture: **0 Items reviewed**, `Lessons learned 2`, `Learned today · 2 of 15` — unchanged from `01-hub-before.png` — and the paused batch holding the reader's place locally (`5 lessons left in batch · Progress · 6 of 15 parts`) |
| `12-lesson-back-dialog.mp4` | 21.0 s recording of the whole question: header Back → dialog → `Stay` (page kept) → header Back → dialog → `Leave` (page gone, Home). Android's `screenrecord` encodes a frame only when the screen changes, so the clip is short |
| `13-lesson-wrong-answer.mp4` | 5.6 s recording of the wrong answer and its shake: typing `zzz`, checking, `Incorrect` appearing and the line shuffling left and right once |
| `vibrator-trace.txt` | The two answer cues as the app logged them, and as the platform's own vibration history recorded them |

## Measurements — the shake

From the 39 encoded frames of `13-lesson-wrong-answer.mp4` (`ffprobe` presentation times), the
answer line's red ink (`#EF9A9A`, the dark-theme error colour, sampled as (237, 152, 151)) was
located in its own band of each frame (`y 1040..1220`). Resting, its left edge is **62 px**; the
table is the move away from that:

| Frame (s) | Left edge (px) | Δ (px) | Δ (dp) |
| --- | --- | --- | --- |
| 3.061 | 62 | 0 | 0.0 |
| 3.079 | 38 | −24 | −8.7 |
| 3.095 | 36 | −26 | −9.5 |
| 3.112 | 36 | −26 | −9.5 |
| 3.129 | 58 | −4 | −1.5 |
| 3.146 | 76 | +14 | +5.1 |
| 3.162 | 84 | +22 | +8.0 |
| 3.178 | 88 | +26 | +9.5 |
| 3.195 | 90 | +28 | +10.2 |
| 3.211 | 90 | +28 | +10.2 |
| 3.229 | 90 | +28 | +10.2 |
| 3.245 | 70 | +8 | +2.9 |
| 3.262 | 64 | +2 | +0.7 |
| 3.279 | 62 | 0 | 0.0 |
| 3.295 | 62 | 0 | 0.0 |
| 3.579 | 62 | 0 | 0.0 |

At 440 dpi, 1 dp is 2.75 px, so the extremes (−26 px, +28 px) are the shake constant's own travel:
`SHAKE_TRAVEL` is 10 dp ≈ 27.5 px. The move lasts 3.061 → 3.279 s (218 ms) against a `withSequence`
of 50 + 100 + 50 ms. Every frame from 3.279 s to the end of the clip (5.58 s) holds at 62 px: the
shake happens **once** and is not repeated by the renders that follow.

## Measurements — the two wave forms

`vibrator-trace.txt` carries both rows verbatim. What they say:

| Cue | Platform's record of the effect | Duration | Status |
| --- | --- | --- | --- |
| Wrong | `Step{amplitude=0.0, duration=0}, Step{amplitude=0.78431374, duration=45}, Step{amplitude=0.0, duration=70}, Step{amplitude=0.78431374, duration=45}`, `repeat=-1` | 161 ms | `finished` |
| Right | `Step{amplitude=0.0, duration=0}, Step{amplitude=0.3529412, duration=35}`, `repeat=-1` | 37 ms | `finished` |

0.78431374 is 200/255 and 0.3529412 is 90/255 — exactly `FeedbackPattern.Wrong`'s and
`FeedbackPattern.Right`'s amplitudes, at their own timings, each attributed to `uid=10201`
(`com.gaten`) with `Usage=TOUCH`, and each reported by the platform as **finished** rather than
cancelled. Both are also in the app's own log, from the native path
(`Feedback pattern Wrong played`, `Feedback pattern Right played`).

Immediately before each of them the platform's history holds a
`status: cancelled_superseded` `Prebaked{effect=TICK}` row: that is the **Check answer** press's own
tick, replaced by the answer's cue within 37 ms. One answer, one thing felt.

## Method

```sh
# 1. Metro from this worktree, and the device pointed at it (the APK carries the Kotlin; Metro the JS)
npx react-native start --port 8081 &        # detached; watchman is absent on this host
adb reverse tcp:8081 tcp:8081

# 2. the build this evidence is of, then the install and the on-device hash check
./gradlew :app:assembleDebug :app:testDebugUnitTest
agent-device install app/build/outputs/apk/debug/app-x86_64-debug.apk --platform android --serial emulator-5554
adb shell run-as com.gaten sha256sum /data/app/…/com.gaten-…/base.apk

# 3. open, and the LogBox banner out of the way of the pinned action
agent-device open com.gaten --platform android --foreground --relaunch --metro-port 8081 --serial emulator-5554
agent-device react-native dismiss-overlay --platform android --serial emulator-5554

# 4. the captures (every tap by coordinate, verified by re-reading the screen)
agent-device logs clear --restart --platform android --serial emulator-5554   # the app log the cue lands in
agent-device record start docs/pr-evidence/wanikani-feedback/12-lesson-back-dialog.mp4 --scope device
agent-device press 72 222        # the header's Back on the lesson page
…
agent-device record stop

# 5. the shake's frames and their measurement (ffmpeg + PIL, not shipped as a script)
ffprobe -v error -select_streams v:0 -show_entries frame=pts_time -of csv=p=0 13-lesson-wrong-answer.mp4
ffmpeg -v error -i 13-lesson-wrong-answer.mp4 -fps_mode passthrough /tmp/shake/f%03d.png
#   then, per frame, the bounding box of the error-red ink in y 1040..1220

# 6. the platform's own record of the cues, and the session closed
adb shell dumpsys vibrator_manager | grep "Step{amplitude"
agent-device close --platform android --serial emulator-5554
```

Fixtures: the WaniKani account connected with 47 lessons available and 0 reviews due; the lesson
batch of the five kanji that WaniKani offered next; no AnkiDroid access (the Anki card was in
`Permission needed`, untouched).

## Account state

Nothing was graded, nothing was submitted, and **no lesson was started by this capture**.

- **No review was submitted**, and none could be: the queue was empty (`0 reviews due · All caught
  up` before and after, `01-hub-before.png` / `11-hub-after.png`), and the review page was never
  reached.
- **No lesson was started by this capture.** `Learned today · 2 of 15` and `Lessons learned 2` read
  identically in `01-hub-before.png` — taken *before* the lesson page was opened for the first time
  — and in `11-hub-after.png`, after every capture in this directory. Those two pre-date this run.
- **The one wrong answer was never sent.** A lesson answer is sent only when a subject's quiz is
  fully correct; this capture answered the meaning of 妙 wrongly, then correctly, which moves the
  quiz to *its reading* (`10-lesson-right-to-reading.png`) and leaves the subject unstarted. The
  wrong answer lives in the app's local session (the hub reads `Progress · 6 of 15 parts` —
  the five teaching cards plus that one part) and is never reported, so
  `incorrect_meaning_answers` was not touched for 妙 and the account's SRS is unchanged.
- Leaving the page kept that local session, exactly as the dialog says: the hub offers it back as
  `5 lessons left in batch`.

## What this evidence does not establish

- **The WaniKani review page.** It is unreachable on this account (0 due), so the review half of both
  changes — the right/wrong cue there and the `Leave the review session?` question — was **not**
  driven. It shares the code path: the same `Feedback` line, the same `useJudgedAnswer` cue read from
  `reviewJudgement`, and the same `shouldConfirmLeaving`/`leaveQuestion` in `ReviewScreen` that the
  lesson half exercises. The unit tests pin the review rules (`tests/wanikani-answer-feedback.test.cjs`,
  `tests/wanikani-leave-confirm.test.cjs`).
- **The feel of the cue.** No emulator can say whether a 35 ms pulse or a 45/70/45 ms double buzz
  reads as "right" or "wrong" in the hand. What is established here is that the call was made (the
  app's log), that the platform accepted the exact wave form asked for and ran it to completion (its
  own history), and that it is a different rhythm and strength for each answer. Whether those numbers
  feel right is a hardware judgement and needs a phone.
- **The shake on the typed answer.** The measurement is of the feedback line under the field, which
  is the element the change animates; the answer field itself does not move.
- **One device, one theme, one subject.** A single x86_64 emulator in dark mode, one kanji (妙), one
  wrong and one right answer, no reduced-motion pass, no other font scale, and no landscape.
- **No "before" capture.** The old build's silent wrong answer was not recorded; the change is shown
  as it is, not as a before/after pair.
- No accessibility-tree evidence: the RN views expose no text nodes on this image, so every tap was
  placed by coordinate from a screenshot.
