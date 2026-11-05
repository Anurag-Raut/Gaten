# WaniKani answer cues and the leave question — device evidence

Captured on a live connected WaniKani account, from this tree's own debug build on the shared
emulator, **after merging `origin/dev` into the branch** (so this is the merged code, not the tree the
branch was cut from).

Two changes are shown:

1. **A judged answer carries its own touch cue.** A right answer and a wrong answer are different
   wave forms, and a wrong answer also shakes the feedback line once.
2. **Backing out of the WaniKani review page and the lesson page now asks first** — through the
   header's Back and through the system Back, with **Stay** keeping the page and **Leave** exiting it.

The leave question was driven on **both** screens on this build. The answer cue was measured on the
review screen, because that is the screen where a wrong answer and then the right answer could both be
produced without submitting anything; the lesson screen renders the same `Feedback` component and its
leave question is captured here, but its own cue was not re-recorded on this build — stated under
*What this evidence does not establish* rather than glossed over. The review queue had **3 reviews
due**, so the review page — the half that was blocked in the earlier capture — now carries its own
recordings.

Nothing was submitted. The review count read **3 reviews due** before entering and **3 reviews due**
after leaving, and the hub's `Today` counters (`Items reviewed` 1, `Lessons learned` 2) were identical
before and after. See *Account state* below for the exact reads and what the "1" is.

The personal access token is never shown, written into this repo, or included in any capture.

## Provenance

| Field | Value |
| --- | --- |
| Code under test | branch `feat/wanikani-wrong-answer-feedback`; item 1 `efb535f`, item 2 `149a0c1`, evidence `b2dd6b0`, merge `5de971c`, harness fix `f056c31`; `HEAD^{tree}` `129ec864dff6d0b039f41791b572b7bfe8544054` — the tree this APK and these captures were made from (every commit after it touches `docs/pr-evidence/` only) |
| Base merged in | `c329631` (`origin/dev`, carrying #71, #69 and #70) |
| Device | serial `emulator-5554`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, `font_scale` 0.94, headless; the one shared emulator, not a second AVD |
| Theme | system dark mode throughout |
| Haptics | `haptic_feedback_enabled 1`; a vibrator service with `AMPLITUDE_CONTROL` and a `Previous vibrations for usage TOUCH` history — see `vibrator-trace.txt` |
| Build | `./gradlew :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL` on the merged tree |
| APK under test | `app/build/outputs/apk/debug/app-x86_64-debug.apk` — 208,910,467 bytes, sha256 `bb19d17734b4ad9282f3e6b46cbf080e80a73dcc3ca6ac21c97107c4b75e20a7`; installed with `agent-device install` (`-r`, no uninstall, so the connected account survived); the installed `/data/app/…/base.apk` was re-hashed **on the device** (`run-as com.gaten sha256sum`) to the same value |
| JS actually running | Metro from this worktree on port 8081 with `adb reverse tcp:8081 tcp:8081`; the served `index.bundle?platform=android&dev=true&minify=false` is HTTP 200, 10,445,720 bytes, sha256 `0dbc0cba7b04a4257ac5bf220eef98ece3d09b7458ac25611e53a8e293dab317`, and it contains `shouldConfirmLeaving` (6), `Leave the review session?` (2), `Leave the lesson?` (1), `meaningMatches` (4) and `defaultDetailTab` (3) — so the running JS is this merged revision. (`Feedback pattern` appears 0 times in it because the cue is Kotlin, in the APK.) |
| Account | WaniKani **connected**; 3 reviews due at the start and at the end; `Today · Items reviewed 1 · Lessons learned 2`; `Learned today · 2 of 15`; 47 lessons available |
| Driver | agent-device (`install`, `open`, `press`, `back`, `screenshot`, `close`), taps placed by coordinate after reading a screenshot — `uiautomator` sees no text nodes of this app's RN content on this image |

## What the captures show

### Review page — the half that was blocked before

| File | Shows |
| --- | --- |
| `review-b0-hub.png` | The WaniKani hub before entering: **3 reviews left in session**, `Progress · 0 of 6 parts left`, 47 lessons available, `Learned today · 2 of 15` |
| `review-a1-prompt.png` | The review page: **1 of 6**, `Vocabulary`, Level 27, the **Meaning** prompt, "Type the meaning", empty field, **Check answer** |
| `review-a2-dialog-back.png` | **The question.** Header Back on the review page raises `Leave the review session?` — *"Nothing is sent to WaniKani by leaving. The subject you are on stays unanswered and your place in the queue is kept, so you can pick the review up again."* — with `Stay` and `Leave` |
| `review-a3-stay.png` | After `Stay`: the dialog is gone and the same prompt is showing — nothing was left |
| `review-a4-dialog-back2.png` | The question raised a second time, so the guard is re-armed rather than one-shot |
| `review-a5-after-leave.png` | After `Leave`: the page is gone and Gaten's Home is showing, which is where Back already went |
| `review-leave.mp4` | **The interaction, end to end.** Back → dialog → `Stay` → Back → `Leave` → Home, as one clip |
| `review-b1-prompt.png` | Re-entered for the cue capture: the review page again, `1 of 6`, the Meaning prompt |
| `review-b2-typed.png` | A wrong answer typed (`banana`) before checking |
| `review-b3-wrong.png` | **The wrong answer.** After checking: **Incorrect** in the error colour, **Show answer**, and the card re-armed |
| `review-wrong.mp4` | **The wrong answer and its shake**, recorded from the check press |
| `review-wrong-shake-frames.png` | Frame strip cut from that clip at the feedback line, frames 15–30 |
| `review-b4-answer.png` | The answer revealed: `tip`, **Correct** |
| `review-b5-hide.png` | The answer hidden again |
| `review-b6-next.png` | The card re-armed with an empty field |
| `review-b7-right.png` | **The right answer.** Typing the known answer and checking gives **Correct** and **Continue** — this is the frame the right cue fires on |
| `review-b8-reading.png` | After **Continue**: the session moved on to **2 of 6** with a Meaning prompt. `Continue` on a meaning does not submit; it advances the batch |
| `review-c1-dialog.png` | The leave question raised again after the answered card, in the post-answer state |
| `review-c2-hub-after.png` | After leaving: Gaten's Home, WaniKani card reading **3 reviews due** — unchanged |
| `review-d1-hub-final.png` | The hub reopened for the account check: `Reviews 3 · 3 reviews left in session`, `Progress · 1 of 6 parts left`, and `Today · Items reviewed 1 · Lessons learned 2` |

### Lesson page

| File | Shows |
| --- | --- |
| `lesson-a2-dialog-back.png` | **The question.** Header Back on the lesson page raises `Leave the lesson?` with `Stay` and `Leave` |
| `lesson-a3-stay.png` | After `Stay`: the dialog is gone and the lesson is where it was |
| `lesson-a4-dialog-back2.png` | The question raised a second time |
| `lesson-a5-after-leave.png` | After `Leave`: the page is gone and Gaten's Home is showing |
| `lesson-b0-entered.png` | Re-entered for the recorded clip: the lesson's first teaching card (Kanji, Level 28, meaning `Peculiar, Unusual, Odd, Strange`) — no teaching card advanced, so nothing was started |
| `lesson-b1-dialog.png` | **The question** on the merged build: `Leave the lesson?` — *"Nothing is sent to WaniKani by leaving. The subjects you have not finished stay in your lessons, and the ones already started stay started."* — with `Stay` and `Leave` |
| `lesson-b2-stay.png` | After `Stay`: dialog gone, the same teaching card showing |
| `lesson-b3-dialog2.png` | The question raised a second time, so the guard is re-armed rather than one-shot |
| `lesson-b4-hub-after.png` | After `Leave`: Gaten's Home |
| `lesson-b5-hub-final.png` | Home with the WaniKani card reading **5 reviews due** |
| `lesson-b6-hub.png` | The hub after the lesson exit: `3 reviews left in session`, `Progress · 1 of 6 parts left`, `5 lessons left in batch`, `Progress · 0 of 15 parts · 5 kanji`, `Today · Items reviewed 1 · Lessons learned 2` |
| `lesson-leave.mp4` | **The lesson interaction, end to end.** Back → dialog → `Stay` → Back → dialog → `Leave` → Home, as one clip (1080×2340, 38.5 s) |

### Traces

| File | Shows |
| --- | --- |
| `vibrator-trace.txt` | The two answer cues as the platform's own vibration history recorded them, plus the TICK rows around them, and an honest note about the logcat lines |

## Measurements

### The shake — measured, not asserted

The feedback line (`Incorrect`) is what shakes. Its left edge per frame, taken from
`review-wrong.mp4` (1080×2340, 31 frames over 7.17 s, variable frame rate), band `y 1040–1095`:

| Frame | Time (s) | Left edge (px) | Displacement |
| --- | --- | --- | --- |
| 15 | 4.416 | 64 | rest |
| 16 | 4.432 | 66 | +2 |
| 17 | 4.449 | 42 | −22 |
| 18 | 4.466 | 36 | −28 |
| 19 | 4.482 | 34 | **−30** (left extreme) |
| 20 | 4.499 | 62 | −2 |
| 21 | 4.515 | 78 | +14 |
| 22 | 4.533 | 86 | +22 |
| 23 | 4.550 | 88 | +24 |
| 24 | 4.565 | 90 | +26 |
| 25 | 4.583 | 95 | **+31** (right extreme) |
| 26 | 4.599 | 95 | +31 |
| 27 | 4.616 | 70 | +6 |
| 28 | 4.633 | 64 | rest |
| 29–30 | 4.649–4.666 | 64 | rest |

- Rest 64 px, left extreme 34 px (**−30 px**), right extreme 95 px (**+31 px**).
- At 440 dpi, 1 dp = 2.75 px, so the extremes are ≈ **−10.9 dp** and **+11.3 dp** about rest — the
  animation's own travel.
- The motion runs from **4.416 s to 4.666 s = 250 ms**, once, and the line is flat at 64 px for the
  remaining 2.3 s of the clip. It goes left first, then right: one shake, not a loop.
- The typed answer row (`y 875–940`, the text `banana`) stays at left 75 px throughout — the shake is
  on the feedback line only, which is what the component is built to do.

### The two wave forms — as the platform played them

From `vibrator-trace.txt` (`dumpsys vibrator_manager`, `Previous vibrations for usage TOUCH`,
`opPkg=com.gaten`, `status: finished`):

| Cue | Timestamp | durationMs | Segments | Shape |
| --- | --- | --- | --- | --- |
| Wrong | `11-05 19:11:05.330` | 161 | `0.0@0, 0.78431374@45, 0.0@70, 0.78431374@45` | 45 ms buzz, 70 ms gap, 45 ms buzz |
| Right | `11-05 19:21:27.229` | 36 | `0.0@0, 0.3529412@35` | 35 ms light pulse |

`0.78431374` is 200/255 and `0.3529412` is 90/255 — the amplitudes the app sets. `repeat = -1` on
both, so each plays once. The 25 `Prebaked{effect=TICK, strength=MEDIUM}` rows in the same history are
the Check-answer button's own platform tick on each press, not the cues; the nearest ones before each
cue are `19:10:18.801` (before Wrong) and `19:20:43.168` (before Right).

## Method

1. Merged `origin/dev` (`c329631`) into the branch, resolved the hunks in `WaniKaniReview.tsx` and
   `review-store.ts` by hand, rebuilt and re-hashed the APK.
2. Installed that APK with `agent-device install -r`, then re-hashed the installed `base.apk` on the
   device and compared with the host hash.
3. Served the JS from this worktree's Metro on 8081 with `adb reverse`, then asserted the served
   bundle's size, sha256 and symbol counts before driving anything.
4. Drove the app with agent-device. Coordinates were read from screenshots (`uiautomator` cannot see
   this app's RN text on this image), and the badge and task pill were read off each screenshot before
   any answer was typed: only a **Vocabulary** subject on a **Meaning** prompt was answered.
5. Recorded the leave sequence and the wrong answer with `screenrecord` at 1080p, then measured the
   shake from the decoded frames rather than describing it.
6. Read the two cues from the platform's own vibration history, and the leave dialog's copy from the
   screenshot.
7. Verified the account after leaving: review count and hub counters.

## Account state — what the capture touched and what it did not

- **Nothing was submitted.** The review count read **3 reviews due** before entering and **3 reviews
  due** after leaving. The leave dialog states the same thing in its own words: *"Nothing is sent to
  WaniKani by leaving. The subject you are on stays unanswered and your place in the queue is kept."*
- **The hub's `Today` counters did not move:** `Items reviewed 1` and `Lessons learned 2` before and
  after. The `1` is a review the previous agent on this device submitted (the radical), not this
  capture; it is a daily total, so it is expected to be non-zero and is not evidence of a submission
  here. `Learned today · 2 of 15` likewise pre-dates this capture — those 2 lessons were already
  started before the first screenshot.
- **One wrong answer and one correct answer were produced locally**, on the same Vocabulary subject's
  Meaning prompt (`banana`, then `tip`). Both are held only in the app's paused session: the session
  advanced to `2 of 6` and `Progress · 1 of 6 parts left` when `Continue` was pressed, but nothing was
  sent. If the owner resumes and submits that subject, the wrong attempt may count toward
  `incorrect_meaning_answers` on WaniKani's next review — that is the one SRS consequence of this
  capture, and it was not triggered by the submission path.
- **No lessons were started by this capture.** The lesson page was entered from the paused batch and
  left through the question without advancing a teaching card; the batch is still `5 lessons left in
  batch`, `0 of 15 parts left`. The dialog's own copy confirms the lesson was already started before
  this capture — *"the ones already started stay started"* — so entering and leaving it issued no new
  `PUT /assignments/<id>/start`.
- **Home's due count did move, 3 → 5, while the capture ran.** It read `3 reviews due` when the review
  half was driven and `5 reviews due` after the lesson half, roughly 30 minutes later. The hub's own
  session count stayed at `3 reviews left in session` and the `Today` counters stayed at
  `Items reviewed 1` / `Lessons learned 2`, so nothing was graded or submitted; the extra two are
  WaniKani's schedule bringing items due during the window, not an effect of this capture. It is
  stated here rather than smoothed over because a reviewer comparing the two Home screenshots will see
  the difference.

## What this evidence does not establish

- **How either cue feels.** A vibration is invisible. `vibrator-trace.txt` and the dumpsys rows prove
  the app asked the platform for a specific wave form and that the platform played it to completion;
  they cannot prove the pattern feels right or distinct in a hand. That judgement needs real hardware.
- **The shake's character as perceived.** The travel is measured (±11 dp about rest, 250 ms); whether
  it reads as a shake rather than a jitter is a design judgement, not something these frames settle.
- **The cue on the lesson screen.** The shake and both wave forms were measured on the review screen.
  The lesson screen renders the same `Feedback` component and its leave question is captured here, but
  reaching a lesson quiz means passing teaching cards, which issues `PUT /assignments/<id>/start` on a
  live account — so the lesson cue was not re-recorded on this build. The review-screen measurement is
  the evidence for the component, not for the lesson screen's wiring.
- **Reduced motion.** The code short-circuits the shake when the OS asks for reduced motion; that path
  was not exercised here (the emulator runs with animations on).
- **Light theme.** All captures are in system dark mode; the error colour and the contrast of the cue
  line in light theme were not driven.
- **A second device or form factor.** One emulator, one resolution, one font scale.
- **Landscape.** Portrait only.
- **The logcat `Feedback` lines as an artifact.** The app logs one line per cue
  (`Feedback pattern Wrong played` / `… Right played`), and both were observed live during the run
  (Wrong at 19:11:05, Right at 19:21:27), but the ring buffer was cleared immediately before the leave
  capture, so the lines are not preserved as a file. The dumpsys history rows in `vibrator-trace.txt`
  are the preserved trace, and they are the stronger evidence because they record what the platform
  actually played rather than what the app requested.
