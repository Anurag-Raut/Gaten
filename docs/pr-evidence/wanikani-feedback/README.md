# WaniKani answer cues and the leave question — device evidence

Captured on a live connected WaniKani account, from this tree's own debug build on the shared
emulator, **after merging `origin/dev` into the branch** (so this is the merged code, not the tree the
branch was cut from).

Two changes are shown:

1. **A judged answer carries its own touch cue.** A right answer and a wrong answer are different
   wave forms, and a wrong answer also **shakes the answer the reader typed, in the field they typed
   it into** — not the verdict under it.
2. **Backing out of the WaniKani review page and the lesson page now asks first** — through the
   header's Back and through the system Back, with **Stay** keeping the page and **Leave** exiting it.

Both halves of the answer cue are captured on this build: the **review** page (`review-wrong-field.mp4`)
and the **lesson quiz** (`lesson-quiz-wrong.mp4`), each with its own frame strip, its own platform
wave-form row and its own `Feedback` logcat line. The leave question is captured on both screens too.

> **The shake moved, and so did the evidence.** The first pass at this change shook the `Incorrect`
> line. The owner corrected that: the thing to shake is the text in the type box. The transform now
> wraps `AnswerInput`, and the artefacts from the first pass were **deleted, not left beside the new
> ones** — `review-wrong.mp4` and `review-wrong-shake-frames.png` no longer exist, because they record
> behaviour this branch no longer has. `review-wrong-field.*` replaces them, and `review-b3-wrong.png`
> was re-taken on the retargeted build.

Nothing was submitted. The review count read **5 reviews due** before the cue runs and **5 reviews
due** after, and the hub's `Today` counters (`Items reviewed` 1, `Lessons learned 2`, `Learned today ·
2 of 15`) were identical before and after. See *Account state* below for the exact reads and what the
"1" and the "2" are.

The personal access token is never shown, written into this repo, or included in any capture.

## Provenance

| Field | Value |
| --- | --- |
| Code under test | branch `feat/wanikani-wrong-answer-feedback`; item 1 `efb535f`, item 2 `149a0c1`, evidence `b2dd6b0`, merge `5de971c`, harness fix `f056c31`, first evidence pass `e1bd848`, **answer-field retarget `738eef2`**; **code tree** `73651425c2ad6b0a23eb00e89e25d74a3c561727` — the tree this APK and these captures were made from. Commits after it touch `docs/pr-evidence/` only, so the built artifact still corresponds to that tree |
| Base merged in | `c329631` (`origin/dev`, carrying #71, #69 and #70) |
| Device | serial `emulator-5554`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, `font_scale` 0.94, headless; the one shared emulator, not a second AVD |
| Theme | system dark mode throughout |
| Haptics | `haptic_feedback_enabled 1`; a vibrator service with `AMPLITUDE_CONTROL` and a `Previous vibrations for usage TOUCH` history — see `vibrator-trace.txt` |
| Build | `./gradlew :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL` on the retargeted tree |
| APK under test | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — 208,910,799 bytes, sha256 `84ae9d9eced2d8371c2d0af5530412b899c8c754ac53f69c7d06e74e70353e12`; installed with `agent-device install` (no uninstall, so the connected account survived); the installed `/data/app/…/base.apk` was re-hashed **on the device** with `sha256sum` to the same value |
| JS in the APK | the packaged `assets/index.android.bundle` — 3,410,892 bytes, sha256 `1540770c49b212067113775e3e66ef691f4dd2a5c0b6a86a3a9b00b8fb0ea4dc`, and it contains `answerShake`, `AnswerShake`, `shouldConfirmLeaving`, `Leave the review session?` and `Leave the lesson?` |
| JS served by Metro | this worktree's Metro on port 8081 with `adb reverse tcp:8081 tcp:8081`: the served `index.bundle?platform=android&dev=true&minify=false` is HTTP 200, 10,447,432 bytes, sha256 `28b228693a28162b2bf3b331c026e0ac4d7076b22406a0e2a849a877f66dd42a`, and it contains `answerShake` (6), `AnswerShake` (5), `shouldConfirmLeaving` (7), `Leave the review session?` (2) and `Leave the lesson?` (1). Both JS paths are this revision, so the run is this revision whichever one the app took |
| Which build each capture came from | The **cue** captures — `review-wrong-field.*`, `lesson-quiz-wrong.*`, `review-b3-wrong.png`, `hub-after-cues.png` — were taken on the retargeted build (`738eef2`, APK `84ae9d9e…`). The **leave** captures — `review-leave.mp4`, `lesson-leave.mp4`, their stills and their GIFs — were taken on the earlier merged build (`e1bd848`, APK `bb19d177…`). The retarget changed the shake's target only (`review-ui.tsx`, the two screens' JSX, `wanikani-cue.ts`, a doc comment), so it did not touch the leave path those clips exercise; they are labelled by build rather than presented as one run |
| Account | WaniKani **connected**; 5 reviews due before and after; `Today · Items reviewed 1 · Lessons learned 2`; `Learned today · 2 of 15`; 47 lessons available at the start |
| Driver | agent-device (`install`, `open`, `press`, `type`, `screenshot`, `close`), taps placed by coordinate after reading a screenshot — `uiautomator` sees no text nodes of this app's RN content on this image |

## What the captures show

### Review page — the cue on the answer field

| File | Shows |
| --- | --- |
| `review-b1-prompt.png` | The review page: `1 of 10`, `Vocabulary`, Level 27, the **Meaning** prompt, "Type the meaning", empty field, **Check answer** |
| `review-b2-typed.png` | A wrong answer typed before checking |
| `review-b3-wrong.png` | **The wrong answer**, re-taken on the retargeted build: the typed text held in the field in the error colour, the stroke under it, and **Incorrect** below — the verdict is the row that now stays still |
| `review-wrong-field.mp4` | **The wrong answer and its shake**, recorded from the check press (1080×2340, 6.18 s, ~60 fps) |
| `review-wrong-field-shake.gif` | The **same window** as a looping 620×126 crop, 37 frames at 50 ms, so the shake can be watched in place — GitHub plays a GIF inline and will not play a repository MP4 |
| `review-wrong-field-shake-frames.png` | Frame strip cut from that clip around the check, frames 268–284, cropped to the typed answer row and the `Incorrect` line |
| `review-b7-right.png` | **The right answer.** Checking the known answer gives **Correct** and **Continue** — the frame the right cue fires on |
| `review-leave.mp4` | **The leave interaction, end to end.** Back → dialog → `Stay` → Back → `Leave` → Home, as one clip |
| `review-leave-question.gif` | The same 60.3 s clip as a 5 fps, 380 px-wide looping GIF (204 KB) — the whole interaction playable in place, since a repository MP4 carries no player |
| `review-a2-dialog-back.png`, `review-a3-stay.png`, `review-a4-dialog-back2.png`, `review-a5-after-leave.png` | The question, `Stay` keeping the prompt, the question re-armed, and `Leave` returning to Home |
| `review-c2-hub-after.png`, `review-d1-hub-final.png` | After leaving: Home, then the hub reopened for the account check |

### Lesson page — the leave question and the cue

| File | Shows |
| --- | --- |
| `lesson-a2-dialog-back.png`, `lesson-a3-stay.png`, `lesson-a4-dialog-back2.png`, `lesson-a5-after-leave.png` | **The question** on the lesson page, `Stay` keeping the card, the question re-armed, and `Leave` returning to Home |
| `lesson-leave.mp4` | **The lesson leave interaction, end to end** (1080×2340, 38.5 s) |
| `lesson-leave-question.gif` | The same clip as a 5 fps, 380 px-wide looping GIF (433 KB) |
| `lesson-quiz-wrong.png` | **The lesson quiz wrong answer.** `Kanji` badge, Level 28, 妙, **Meaning** prompt, **Incorrect** under the field. The field is empty: a wrong lesson answer clears the typed text (`review-store.ts`), so the stroke is what moves |
| `lesson-quiz-wrong.mp4` | **The lesson cue and its shake**, recorded from the check press (1080×2340, 14.42 s, ~60 fps) |
| `lesson-quiz-wrong-shake.gif` | The **same window** as a looping 620×132 crop, 33 frames at 50 ms, for the same reason: a GIF plays in place, an MP4 in a repository does not |
| `lesson-quiz-wrong-shake-frames.png` | Frame strip cut from that clip around the check, frames 263–280, cropped to the field's stroke and the `Incorrect` line |
| `hub-after-cues.png` | The hub after both cue runs: `Today · Items reviewed 1 · Lessons learned 2`, `Learned today · 2 of 15`, `5 reviews left in session`, `5 lessons left in batch · Progress · 5 of 15 parts · 5 kanji` |

### Traces

| File | Shows |
| --- | --- |
| `vibrator-trace.txt` | The Wrong cue as the platform's own vibration history recorded it, on **both** screens, twice each — the runs the recordings come from, and the re-fires whose app-side lines are preserved |
| `feedback-logcat.txt` | The app's own `Feedback pattern Wrong played` lines, one per screen, each paired with the vibrator row carrying the same millisecond |

## Measurements

### The shake — measured from the recordings, not asserted

`SHAKE_TRAVEL` is 10 dp and each leg is `withSequence(−10 dp, +10 dp, 0)` over 50/100/50 ms, skipped
when the system asks for reduced motion. Measured per frame:

**Review page**, `review-wrong-field.mp4` — 371 frames over 6.177 s (~60 fps). Band measured: the
typed answer's red text, `y 902–931`. Control band: the `Incorrect` line, `y 1057–1079`.

| Frame | Time (s) | Field text left edge (px) | Displacement |
| --- | --- | --- | --- |
| 1–267 | 0.000–4.429 | — | no red on screen: the prompt is unanswered |
| 268 | 4.446 | 66 | rest — the wrong state appears |
| 269 | 4.462 | 66 | rest |
| 270 | 4.479 | 46 | −20 |
| 271 | 4.496 | 40 | −26 |
| 272–273 | 4.512–4.529 | 38 | **−28** (left extreme) |
| 274 | 4.546 | 60 | −6 |
| 275 | 4.562 | 78 | +12 |
| 276 | 4.579 | 86 | +20 |
| 277 | 4.596 | 90 | +24 |
| 278 | 4.612 | 92 | +26 |
| 279–280 | 4.629–4.646 | 94 | **+28** (right extreme) |
| 281 | 4.662 | 72 | +6 |
| 282 | 4.679 | 66 | rest |
| 283–371 | 4.696–6.177 | 66 | rest |

- Rest 66 px, left extreme 38 px (**−28 px**), right extreme 94 px (**+28 px**).
- The `Incorrect` line's left edge is **62 px in every frame it is on screen** (268 onward) and absent
  before that — the control holds, and it is what proves the motion is on the field and not on the
  verdict.
- At 440 dpi, 1 dp = 2.75 px, so ±28 px is ≈ **±10.2 dp** about rest — the animation's own travel.
- The motion runs frame 269 → 282: 13 frames ≈ **217 ms**, once, then flat for the remaining ~4.9 s.
  Left first, then right, then settle: one shake, not a loop.

**Lesson quiz**, `lesson-quiz-wrong.mp4` — 865 frames over 14.417 s (~60 fps). Band measured: the
field's accent stroke, `y 1058–1062` (it moves with the field). Control band: `Incorrect`,
`y 1119–1141`.

| Frame | Time (s) | Stroke left edge (px) | Displacement |
| --- | --- | --- | --- |
| 1–263 | 0.000–4.367 | 62 | rest — the prompt is unanswered |
| 264 | 4.384 | 62 | rest — the wrong state appears |
| 265 | 4.400 | 58 | −4 |
| 266 | 4.417 | 38 | −24 |
| 267–268 | 4.434–4.450 | 34 | **−28** (left extreme) |
| 269 | 4.467 | 56 | −6 |
| 270 | 4.484 | 74 | +12 |
| 271 | 4.500 | 82 | +20 |
| 272 | 4.517 | 86 | +24 |
| 273 | 4.534 | 88 | +26 |
| 274–275 | 4.550–4.567 | 89 | **+27** (right extreme) |
| 276 | 4.584 | 69 | +7 |
| 277 | 4.600 | 63 | +1 |
| 278 | 4.617 | 62 | rest |
| 279–865 | 4.634–14.417 | 62 | rest |

- Rest 62 px, left extreme 34 px (**−28 px**), right extreme 89 px (**+27 px**) — ≈ **±10 dp**.
- The stroke's right edge moves with it (1019 → 991/992 → 1046), so the whole field translates rather
  than stretching.
- The `Incorrect` line's left edge is **62 px in every frame it is on screen** (264 onward).
- The motion runs frame 265 → 277: 12 frames ≈ **200 ms**, then flat for the remaining ~9.8 s.
- **The field is empty when it shakes**, because a wrong lesson answer clears the answer
  (`entry: ""` in `review-store.ts`'s lesson wrong branch). So on the lesson the stroke and the
  placeholder move; the red text that moves on the review page is not there to move. That is the
  store's existing behaviour, not something the retarget changed, and it is stated rather than
  implied. If the lesson should also hold the wrong text in the field, that is a store change.

Both halves therefore show the same rule: **the field moves once, ±10 dp, ~200–220 ms, and the verdict
line never does.**

### The two wave forms — as the platform played them

From `vibrator-trace.txt` (`dumpsys vibrator_manager`, `Previous vibrations for usage TOUCH`,
`opPkg=com.gaten`, `status: finished`), the four Wrong cues of this session plus the earlier Right cue:

| Cue | Screen | Timestamp | durationMs | Segments |
| --- | --- | --- | --- | --- |
| Wrong (in `review-wrong-field.mp4`) | review | `20:22:21.382` | 162 | `0.0@0, 0.78431374@45, 0.0@70, 0.78431374@45` |
| Wrong (in `lesson-quiz-wrong.mp4`) | lesson | `20:25:39.441` | 160 | `0.0@0, 0.78431374@45, 0.0@70, 0.78431374@45` |
| Wrong (logcat pair) | review | `20:29:53.693` | 161 | same |
| Wrong (logcat pair) | lesson | `20:31:23.092` | 161 | same |
| Right (earlier build, see the file) | review | `19:21:27.229` | 36 | `0.0@0, 0.3529412@35` |

`0.78431374` is 200/255 and `0.3529412` is 90/255 — the amplitudes the app sets. `repeat = -1` on all
of them, so each plays once. Each Wrong cue supersedes the Check-answer button's own `Prebaked TICK`
(`cancelled_superseded`, 23–64 ms into its ~102 ms): the cue does not wait for the tap's tick, it
replaces it.

### The two traces, tied together

`feedback-logcat.txt` carries the app-side line for one cue on each screen, and each line is paired
with the vibrator row that carries the same millisecond:

| Screen | `Feedback` line | Platform row |
| --- | --- | --- |
| review | `20:29:53.693 … Feedback pattern Wrong played` | `createTime: 11-05 20:29:53.693 … durationMs: 161, finished` |
| lesson | `20:31:23.092 … Feedback pattern Wrong played` | `createTime: 11-05 20:31:23.092 … durationMs: 161, finished` |

The two recorded runs' lines were seen live at `20:22:21.382` and `20:25:39.441`, but `logcat -c`
between captures cleared the buffer before either could be preserved as a file; their platform rows
did not roll, and those are in `vibrator-trace.txt`. `feedback-logcat.txt` says exactly this.

## Method

1. Merged `origin/dev` (`c329631`) into the branch, resolved the hunks in `WaniKaniReview.tsx` and
   `review-store.ts` by hand, rebuilt and re-hashed the APK.
2. Retargeted the shake from the feedback line to the answer field, rebuilt, and asserted the new build
   (`npx tsc --noEmit`, `node --test tests/*.test.cjs`, `./gradlew :app:assembleDebug
   :app:testDebugUnitTest`, then the packaged bundle's symbols).
3. Installed that APK with `agent-device install`, then re-hashed the installed `base.apk` on the device
   and compared with the host hash.
4. Served the JS from this worktree's Metro on 8081 with `adb reverse`, then asserted the served
   bundle's size, sha256 and symbol counts before driving anything.
5. Drove the app with agent-device. Coordinates were read from screenshots (`uiautomator` cannot see
   this app's RN text on this image), and the badge and task pill were read off each screenshot before
   any answer was typed: on the lesson the subject was a **Kanji** (two parts, so a wrong meaning
   cannot complete it).
6. Recorded the wrong answer on each screen with `screenrecord` at 1080p, decoded the frames, and
   measured the moving band and the still control band rather than describing the motion.
7. Re-fired one cue per screen with the logcat buffer clear beforehand, so each cue's app-side line and
   its platform row could both be preserved.
8. Read the account after leaving: review count, hub counters and the paused batch.

## Account state — what the capture touched and what it did not

- **Zero subjects were started.** The lesson's start (`PUT /assignments/<id>/start`) is issued by
  `startWaniKaniLesson()`, which runs only when a **quiz is complete** — not on entering the batch and
  not on a wrong answer. The counters agree: `Learned today · 2 of 15` and `Lessons learned 2` read the
  same before and after, and the hub shows the batch paused at `Progress · 5 of 15 parts · 5 kanji`.
- **Nothing was submitted.** The review count read **5 reviews due** before the cue runs and **5 reviews
  due** after leaving, and the hub's `Today` counters did not move. The `1` in `Items reviewed` and the
  `2` in `Lessons learned` pre-date this capture — they belong to earlier work on this device and are
  daily totals, so a non-zero read is expected and is not evidence of a submission here.
- **Wrong answers were recorded locally, on two subjects, and never sent.** The review subject (先端,
  meaning) took two wrong attempts, at `20:22` and `20:29`; the lesson subject (妙, meaning) took two,
  at `20:25` and `20:31`. Both sit in the app's **paused** session state — `5 reviews left in session ·
  Progress · 0 of 10 parts` and `5 lessons left in batch · Progress · 5 of 15 parts`. If the owner
  later resumes and submits either, the wrong attempts **may count toward those subjects' incorrect
  answers** on WaniKani, which is the one SRS consequence of this evidence run. Nothing was sent by
  this capture.
- **No right answer was produced anywhere in this session**, deliberately: on the lesson quiz a correct
  meaning advances or completes a part, and completing it is what starts and reports the subject.
- **A restored wrong answer does not shake.** Re-entering the review resumed its retained wrong state
  (field empty, `Incorrect` showing) and the field arrived still, with no cue — the documented
  behaviour for a session resumed as it is, observed rather than assumed.
- **The hub's Home card read 5 reviews due throughout this session**, unchanged from the first read to
  the last, so this run moved no queue counts at all.

## What this evidence does not establish

- **How either cue feels.** A vibration is invisible. `vibrator-trace.txt` and the dumpsys rows prove
  the app asked the platform for a specific wave form and that the platform played it to completion;
  they cannot prove the pattern feels right or distinct in a hand. That judgement needs real hardware.
- **The shake's character as perceived.** The travel is measured (±28 px ≈ ±10.2 dp, ~200–220 ms);
  whether it reads as a shake rather than a jitter is a design judgement, not something these frames
  settle.
- **The lesson holding its wrong text.** The lesson's field is empty when it shakes, because the store
  clears the answer on a wrong lesson answer. The measurement covers the field's motion, not red text
  moving inside it.
- **Reduced motion.** The code short-circuits the shake when the OS asks for reduced motion; that path
  was not exercised here (the emulator runs with animations on).
- **Light theme.** All captures are in system dark mode; the error colour and the contrast of the cue
  line in light theme were not driven.
- **A second device or form factor.** One emulator, one resolution, one font scale.
- **Landscape.** Portrait only.
- **The Right cue on this build.** No right answer was produced in this session, so the only Right row
  in `vibrator-trace.txt` is from the earlier run and is labelled as such. The right-answer path was
  not touched by the retarget, but that row is not this build's.
