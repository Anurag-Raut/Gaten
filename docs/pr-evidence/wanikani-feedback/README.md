# WaniKani answer cues and the leave question — device evidence

Captured on a live connected WaniKani account, from this tree's own debug build on the shared
emulator, **after merging `origin/dev` into the branch** (so this is the merged code, not the tree the
branch was cut from).

Two changes are shown:

1. **A judged answer carries its own touch cue.** A right answer and a wrong answer are different
   wave forms, and a wrong answer also **shakes the answer the reader typed** — the text moves, the
   line it sits on does not, and neither does the verdict under it.
2. **Backing out of the WaniKani review page and the lesson page now asks first** — through the
   header's Back and through the system Back, with **Stay** keeping the page and **Leave** exiting it.

Both halves of the answer cue are captured on this build: the **review** page (`review-wrong-field.mp4`)
and the **lesson quiz** (`lesson-quiz-wrong.mp4`), each with its own frame strip, its own platform
wave-form row and its own `Feedback` logcat line. The leave question is captured on both screens too.

> **What moves, and what does not.** An earlier pass at this change shook the `Incorrect` line; the
> owner corrected that, and then the whole field — text *and* the stroke under it, which was the
> input's own `borderBottom`. The stroke now lives on `AnswerShake`'s static wrapper and the input is
> translated inside it, so the text moves and the frame does not. The artefacts from both earlier
> passes were **replaced, not left beside these**: the recordings, strips, GIFs and stills here are all
> from the build below, and no clip in this directory shows the stroke travelling.

Nothing was submitted. The review count read **27 reviews due** before the cue runs and **27 reviews
due** after, and the hub's `Today` counters (`Items reviewed` 1, `Lessons learned 2`, `Learned today ·
2 of 15`) were identical before and after. See *Account state* below for the exact reads and what the
"1" and the "2" are.

The personal access token is never shown, written into this repo, or included in any capture.

## Provenance

| Field | Value |
| --- | --- |
| Code under test | branch `feat/wanikani-wrong-answer-feedback`; item 1 `efb535f`, item 2 `149a0c1`, evidence `b2dd6b0`…`3b0e932`, merge `5de971c`, harness fix `f056c31`, **answer-field retarget `18275df`**, **stroke-without-height `d2ae5ee`**; **code tree** `bf1427e9d6acc8359c85fe2ba0fc3ee68eb58554` — the tree this APK and these captures were made from. Commits after it touch `docs/pr-evidence/` only |
| Base merged in | `c329631` (`origin/dev`, carrying #71, #69 and #70) |
| Device | serial `emulator-5554`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, `font_scale` 0.94, headless; the one shared emulator, not a second AVD |
| Theme | system dark mode throughout |
| Haptics | `haptic_feedback_enabled 1`; a vibrator service with `AMPLITUDE_CONTROL` and a `Previous vibrations for usage TOUCH` history — see `vibrator-trace.txt` |
| Build | `./gradlew :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL` on that tree |
| APK under test | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — 208,911,195 bytes, sha256 `fb4436a7d1d03b0f14c54d5e52f2726c2f2a5b2743e0b2cf197d45ee310ffb06`; installed with `agent-device install` (no uninstall, so the connected account survived); the installed `/data/app/…/base.apk` was re-hashed **on the device** with `sha256sum` to the same value |
| JS in the APK | the packaged `assets/index.android.bundle` — 3,411,108 bytes, sha256 `cdef1d117a69a5748269635699caacdb54e40f0a6a9ac0bb7b5d5193d2ed8242`, carrying `answerStroke`, `answerShake`, `AnswerShake` and `shouldConfirmLeaving` |
| JS served by Metro | this worktree's Metro on port 8081 with `adb reverse tcp:8081 tcp:8081`: HTTP 200, 10,448,313 bytes, sha256 `6c1d0344cb06b9ddc3ba03e98c558e0bb050420a2299fdd64800b20456808946`, carrying `answerStroke` (4), `answerShake` (6), `AnswerShake` (6) and `shouldConfirmLeaving` (7). Both JS paths are this revision |
| Which build each capture came from | The **cue** captures — `review-wrong-field.*`, `lesson-quiz-wrong.*`, `review-b3-wrong.png`, `hub-after-cues.png` — are from the build above. The **leave** captures — `review-leave.mp4`, `lesson-leave.mp4`, their stills and their GIFs — are from the earlier merged build (`e1bd848`, APK `bb19d177…`): the retargets touched the shake's target only (`review-ui.tsx`, the two screens' JSX, `wanikani-cue.ts`), not the leave path those clips exercise |
| Account | WaniKani **connected**; 27 reviews due before and after; `Today · Items reviewed 1 · Lessons learned 2`; `Learned today · 2 of 15` |
| Driver | agent-device (`install`, `open`, `press`, `type`, `screenshot`, `close`), taps placed by coordinate after reading a screenshot — `uiautomator` sees no text nodes of this app's RN content on this image |

## What the captures show

### Review page — the cue on the answer's text

| File | Shows |
| --- | --- |
| `review-b1-prompt.png` | The review page: `1 of 10`, `Vocabulary`, Level 27, the **Meaning** prompt, "Type the meaning", empty field, **Check answer** |
| `review-b2-typed.png` | A wrong answer typed before checking |
| `review-b3-wrong.png` | **The wrong answer**, on this build: the typed text held in the field in the error colour, the stroke under it, and **Incorrect** below — the two rows that now stay still |
| `review-wrong-field.mp4` | **The wrong answer and its shake**, recorded from the check press (1080×2340, 6.40 s, 384 frames at 60 fps) |
| `review-wrong-field-shake.gif` | The same window as a looping 620×126 crop, 37 frames at 50 ms, so the shake can be watched in place — GitHub plays a GIF inline and will not play a repository MP4 |
| `review-wrong-field-shake-frames.png` | Frame strip cut from that clip, frames 264–281, cropped to the typed answer row and the `Incorrect` line |
| `review-b7-right.png` | **The right answer.** Checking the known answer gives **Correct** and **Continue** — the frame the right cue fires on |
| `review-leave.mp4` | **The leave interaction, end to end.** Back → dialog → `Stay` → Back → `Leave` → Home, as one clip |
| `review-leave-question.gif` | The same 60.3 s clip as a 5 fps, 380 px-wide looping GIF (204 KB) |
| `review-a2-dialog-back.png`, `review-a3-stay.png`, `review-a4-dialog-back2.png`, `review-a5-after-leave.png` | The question, `Stay` keeping the prompt, the question re-armed, and `Leave` returning to Home |
| `review-c2-hub-after.png`, `review-d1-hub-final.png` | After leaving: Home, then the hub reopened for the account check |

### Lesson page — the leave question and the cue

| File | Shows |
| --- | --- |
| `lesson-a2-dialog-back.png`, `lesson-a3-stay.png`, `lesson-a4-dialog-back2.png`, `lesson-a5-after-leave.png` | **The question** on the lesson page, `Stay` keeping the card, the question re-armed, and `Leave` returning to Home |
| `lesson-leave.mp4` | **The lesson leave interaction, end to end** (1080×2340, 38.5 s) |
| `lesson-leave-question.gif` | The same clip as a 5 fps, 380 px-wide looping GIF (433 KB) |
| `lesson-quiz-wrong.png` | **The lesson quiz wrong answer.** `Kanji` badge, Level 28, 妙, **Meaning** prompt, **Incorrect** under the field. The field is empty — a wrong lesson answer clears the typed text (`review-store.ts`) — so the placeholder is what moves |
| `lesson-quiz-wrong.mp4` | **The lesson cue and its shake**, recorded from the check press (1080×2340, 14.40 s, 864 frames at 60 fps) |
| `lesson-quiz-wrong-shake.gif` | The same window as a looping 620×132 crop, 33 frames at 50 ms |
| `lesson-quiz-wrong-shake-frames.png` | Frame strip cut from that clip, frames 262–279, cropped to the field's placeholder, its stroke and the `Incorrect` line |
| `hub-after-cues.png` | The hub after both cue runs: `Today · Items reviewed 1 · Lessons learned 2`, `Learned today · 2 of 15`, `27 reviews left in session`, `5 lessons left in batch · Progress · 5 of 15 parts · 5 kanji` |

### Traces

| File | Shows |
| --- | --- |
| `vibrator-trace.txt` | The Wrong cue as the platform's own vibration history recorded it, on both screens — the runs the recordings come from, and the re-fire whose app-side line is preserved |
| `feedback-logcat.txt` | The app's own `Feedback pattern Wrong played` lines, one per screen, each paired with the vibrator row carrying the same millisecond |

## Measurements

### What moves: the text. What does not: the stroke, and the verdict.

`SHAKE_TRAVEL` is 10 dp and the animation is `withSequence(−10 dp, +10 dp, 0)` over 50/100/50 ms,
skipped when the system asks for reduced motion. Measured per frame, in each clip, on three bands: the
answer's own text, the field's stroke, and the `Incorrect` line.

**Review page**, `review-wrong-field.mp4` — 384 frames over 6.40 s (60 fps). Text band `y 901–930`,
stroke `y 993–998`, `Incorrect` `y 1057–1079`.

| Frame | Time (s) | Typed text | Stroke | `Incorrect` |
| --- | --- | --- | --- | --- |
| 264–265 | 4.383–4.400 | — (check pressed) | 62 | — |
| 266–267 | 4.417–4.433 | 66 (rest) | 62 | 62 |
| 268 | 4.450 | 54 (−12) | 62 | 62 |
| 269 | 4.467 | 42 (−24) | 62 | 62 |
| 270–271 | 4.483–4.500 | **38 (−28)** | 62 | 62 |
| 272 | 4.517 | 78 (+12) | 62 | 62 |
| 273 | 4.533 | 86 (+20) | 62 | 62 |
| 274 | 4.550 | 90 (+24) | 62 | 62 |
| 275 | 4.567 | 92 (+26) | 62 | 62 |
| 276–277 | 4.583–4.600 | **94 (+28)** | 62 | 62 |
| 278 | 4.617 | 72 (+6) | 62 | 62 |
| 279–384 | 4.633–6.400 | 66 (rest) | 62 | 62 |

**Lesson quiz**, `lesson-quiz-wrong.mp4` — 864 frames over 14.40 s (60 fps). Placeholder band
`y 953–1006`, stroke `y 1055–1060`, `Incorrect` `y 1119–1141`.

| Frame | Time (s) | Placeholder | Stroke | `Incorrect` |
| --- | --- | --- | --- | --- |
| 262–263 | 4.350–4.367 | 65 (typing) | 62 | — |
| 264 | 4.383 | 63 (wrong state appears) | 62 | 62 |
| 265 | 4.400 | 41 (−22) | 62 | 62 |
| 266 | 4.417 | 36 (−27) | 62 | 62 |
| 267 | 4.433 | **35 (−28)** | 62 | 62 |
| 268 | 4.450 | 58 (−5) | 62 | 62 |
| 269 | 4.467 | 76 (+13) | 62 | 62 |
| 270–271 | 4.483–4.500 | 84 (+21) | 62 | 62 |
| 272–274 | 4.517–4.550 | **90 (+27)** | 62 | 62 |
| 275 | 4.567 | 70 (+7) | 62 | 62 |
| 276 | 4.583 | 64 (+1) | 62 | 62 |
| 277–864 | 4.600–14.400 | 63 (rest) | 62 | 62 |

- **The text moves ±28 px, once.** At 440 dpi, 1 dp = 2.75 px, so ±28 px is ≈ **±10.2 dp** about rest
  — the animation's own travel. The motion runs 267 → 279 on the review page (12 frames ≈ **200 ms**)
  and 265 → 276 on the lesson (11 frames ≈ **183 ms**), left first and then right, then flat for the
  remaining seconds of each clip. One shake, not a loop.
- **The stroke is at 62 px in all 384 review frames and all 864 lesson frames.** It is the field's own
  line, and it does not move.
- **`Incorrect` is at 62 px in every frame it is on screen** — from 266 on the review page, from 264 on
  the lesson — and absent before that. The verdict does not move.
- The lesson's placeholder reads 63–64 px at rest rather than one value: the glyph's antialiased left
  edge lands on either side of a pixel. The extremes are unaffected (35 and 90).
- On the lesson the field is **empty** when it shakes, because a wrong lesson answer clears the answer
  (`entry: ""` in `review-store.ts`). That is the store's existing behaviour, not something the retarget
  changed; the lesson's moving band is therefore its placeholder rather than red text.

### The field itself, before and after the retarget — checked, not assumed

The shake change moved where the stroke is drawn, so its geometry was measured against the earlier
build's stills of the same screen (`review-b1-prompt.png`, `review-a1-prompt.png`) rather than claimed:

| | prompt text top | stroke rows | `Incorrect` rows |
| --- | --- | --- | --- |
| earlier build (stroke was the input's own border) | 891 | 996–1000 | 1057–1079 |
| this build (stroke on the static wrapper) | 890 | 993–998 | 1057–1079 |

The block below the field sits on the same rows as before, the prompt text within 1 px, and the field's
internal height reads 108 px against 109 px — rounding at a 2 dp feature across 440 dpi, with no layout
shift. The first cut of this change *did* push that block up (the wrapper's border added 2 dp of
height); `d2ae5ee` put the line back to occupying no layout at all.

### The two wave forms — as the platform played them

From `vibrator-trace.txt` (`dumpsys vibrator_manager`, `Previous vibrations for usage TOUCH`,
`opPkg=com.gaten`, `status: finished`):

| Cue | Screen | Timestamp | durationMs | Segments |
| --- | --- | --- | --- | --- |
| Wrong (in `review-wrong-field.mp4`) | review | `20:54:00.280` | 161 | `0.0@0, 0.78431374@45, 0.0@70, 0.78431374@45` |
| Wrong (in `lesson-quiz-wrong.mp4`) | lesson | `20:56:30.095` | 160 | same |
| Wrong (logcat pair) | review | `21:00:29.632` | 162 | same |
| Right (earlier build, see the file) | review | `19:21:27.229` | 36 | `0.0@0, 0.3529412@35` |

`0.78431374` is 200/255 and `0.3529412` is 90/255 — the amplitudes the app sets. `repeat = -1` on all
of them, so each plays once. Each Wrong cue supersedes the Check-answer button's own `Prebaked TICK`
(`cancelled_superseded`, 62 ms and 27 ms into a 102 ms tick): the cue does not wait for the tap's tick,
it replaces it.

### The two traces, tied together

`feedback-logcat.txt` carries the app-side line for one cue on each screen from a single dump, and each
line is paired with the vibrator row that carries the same millisecond:

| Screen | `Feedback` line | Platform row |
| --- | --- | --- |
| lesson | `20:56:30.095 … Feedback pattern Wrong played` | `createTime: 11-05 20:56:30.095 … durationMs: 160, finished` |
| review | `21:00:29.632 … Feedback pattern Wrong played` | `createTime: 11-05 21:00:29.632 … durationMs: 162, finished` |

The lesson pair is the recorded run's own cue. The review recording's line (`20:54:00.280`) was read
live as its platform row was written but had rolled out of the buffer by the time both lines were
dumped, so the preserved review pair is a third Wrong cue on the same screen; the file says exactly
this. The platform rows for both recorded runs are preserved either way.

## Method

1. Merged `origin/dev` (`c329631`) into the branch, resolved the hunks in `WaniKaniReview.tsx` and
   `review-store.ts` by hand, rebuilt and re-hashed the APK.
2. Retargeted the shake from the feedback line to the answer field, then moved the stroke out of the
   moving node and made it occupy no layout — rebuilding and re-measuring the field's geometry against
   the earlier build's stills at each step.
3. Installed the final APK with `agent-device install`, then re-hashed the installed `base.apk` on the
   device and compared with the host hash.
4. Served the JS from this worktree's Metro on 8081 with `adb reverse`, then asserted the served
   bundle's size, sha256 and symbol counts before driving anything.
5. Drove the app with agent-device. Coordinates were read from screenshots (`uiautomator` cannot see
   this app's RN text on this image), and the badge and task pill were read off each screenshot before
   any answer was typed: on the lesson the subject was a **Kanji** (two parts, so a wrong meaning
   cannot complete it).
6. Recorded the wrong answer on each screen with `screenrecord` at 1080p, decoded the frames, and
   measured three bands per clip — the text that should move, and the stroke and verdict that should
   not.
7. Re-fired one cue per screen with the logcat buffer left intact, so both app-side lines could be
   preserved in one dump, each paired to its platform row by millisecond.
8. Read the account after leaving: review count, hub counters and the paused batches.

## Account state — what the capture touched and what it did not

- **Zero subjects were started.** The lesson's start (`PUT /assignments/<id>/start`) is issued by
  `startWaniKaniLesson()`, which runs only when a **quiz is complete** — not on entering the batch and
  not on a wrong answer. The counters agree: `Learned today · 2 of 15` and `Lessons learned 2` read the
  same before and after, and the hub shows the batch paused at `Progress · 5 of 15 parts · 5 kanji`.
- **Nothing was submitted.** The review count read **27 reviews due** before the cue runs and **27
  reviews due** after leaving, and the hub's `Today` counters did not move. The `1` in `Items reviewed`
  and the `2` in `Lessons learned` pre-date this capture — they belong to earlier work on this device
  and are daily totals, so a non-zero read is expected and is not evidence of a submission here.
- **Three wrong answers were recorded locally and never sent.** The review subject (先端, meaning) took
  two — the recorded one at `20:54` and the re-fire at `21:00`; the lesson subject (妙, meaning) took
  one, at `20:56`. All three sit in the app's **paused** session state (`27 reviews left in session ·
  0 of 50 parts`, `5 lessons left in batch`). If the owner later resumes and submits either session,
  those attempts **may count toward those subjects' incorrect answers** on WaniKani, which is the one
  SRS consequence of this evidence run. Nothing was sent by this capture.
- **No right answer was produced anywhere in this session**, deliberately: on the lesson quiz a correct
  meaning advances or completes a part, and completing it is what starts and reports the subject.
- **A wrong answer restored from a paused session does not shake.** Re-entering the review brought its
  retained wrong state back with the field still, and no cue — the documented behaviour for a session
  resumed as it is, observed rather than assumed. Answering again on that same prompt did fire a cue.
- **The hub's Home card read 27 reviews due throughout**, unchanged from the first read to the last, so
  this run moved no queue counts at all.

## What this evidence does not establish

- **How either cue feels.** A vibration is invisible. `vibrator-trace.txt` and the dumpsys rows prove
  the app asked the platform for a specific wave form and that the platform played it to completion;
  they cannot prove the pattern feels right or distinct in a hand. That judgement needs real hardware.
- **The shake's character as perceived.** The travel is measured (±28 px ≈ ±10.2 dp, ~200 ms); whether
  it reads as a shake rather than a jitter is a design judgement, not something these frames settle.
- **The lesson holding its wrong text.** The lesson's field is empty when it shakes, so the measurement
  covers its placeholder moving, not red text inside it.
- **Reduced motion.** The code short-circuits the shake when the OS asks for reduced motion; that path
  was not exercised here (the emulator runs with animations on).
- **Light theme.** All captures are in system dark mode; the error colour and the contrast of the cue
  line in light theme were not driven.
- **A second device or form factor.** One emulator, one resolution, one font scale.
- **Landscape.** Portrait only.
- **The Right cue on this build.** No right answer was produced in this session, so the only Right row
  in `vibrator-trace.txt` is from an earlier run and is labelled as such.
