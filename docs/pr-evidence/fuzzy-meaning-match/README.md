# WaniKani lesson quiz: a near-miss meaning is refused by the base and accepted by this branch

Captured for PR #70 (`fix/fuzzy-meaning-match`): a meaning answer one edit away from an accepted
meaning is judged **Incorrect** by `origin/dev` and **accepted** by this branch. The same subject, the
same prompt and the same typed string are used for both stills, so the pair is a controlled A/B of the
judgement and nothing else.

The PR says "No UI changes"; that is wrong, and this directory is the correction. The judgement is
rendered — on the base the lesson quiz stops on the meaning prompt with **Incorrect**, on this branch
it does not, and the quiz moves on to the reading prompt.

Captured on a live connected WaniKani account. The personal access token is never shown, written into
this repo, or included in any capture.

## Provenance

| Field | Value |
| --- | --- |
| Code under test | branch `fix/fuzzy-meaning-match`, head `1a81acb` — *fix(wanikani): accept meaning answers that are close enough* |
| Base | `4f5ef1e` (`origin/dev`); its `src/rn/ui/review/review-store.ts` was checked out over the branch file for the "before" stills and then restored |
| JS actually running | Metro dev server started from this worktree on port 8081, `adb reverse tcp:8081 tcp:8081`. The served `index.bundle` was checked on both builds: the base bundle contains no `isCloseMiss` / `meaningMatches` / `>= 12 ? 2 : 1`, the branch bundle contains all three |
| App | `com.gaten` 0.1.0, debug build, JS from Metro |
| Device | serial `emulator-5554`, AVD `Gaten_Lookup_Review` — Android 14 (API 34), `x86_64`, 1080×2340 @ 440 dpi, headless (`-no-window`), software GPU |
| Theme | system dark mode |
| Driver | `agent-device` CLI: `press` by screenshot-derived coordinates, `type`, `back`, `screenshot`. `uiautomator` exposes no text nodes for this app's React Native content |
| Subject | 妙 — Kanji, Level 28; accepted meanings **Peculiar, Unusual, Odd, Strange**; reading みょう |
| Typed string | `peculiarr` — identical in both stills |

## What the captures show

| File | Build | Shows |
| --- | --- | --- |
| `01-hub-before.png` | — | WaniKani hub at session start: **Items reviewed 0 · Lessons learned 2 · Learned today 2 of 15**, Lessons row `47 lessons available`. |
| `02-lesson-teaching-card.png` | branch | The batch's teaching card for 妙 (`1 of 5`, Level 28) listing the accepted meanings — **Peculiar, Unusual, Odd, Strange** — and reading みょう. This is where the right answer comes from. |
| `03-before-typed-near-miss.png` | `4f5ef1e` | The lesson quiz's meaning prompt for 妙 with `peculiarr` typed and **Check answer** enabled. |
| `04-before-incorrect.png` | `4f5ef1e` | **The base judgement.** After **Check answer**: **Incorrect** under the field, the field cleared, still `1 of 5` on the **Meaning** prompt. |
| `05-after-typed-near-miss.png` | `1a81acb` | The same prompt, the same `peculiarr`, the same enabled **Check answer**. |
| `06-after-accepted-reading-prompt.png` | `1a81acb` | **This branch's judgement.** After **Check answer**: no `Incorrect` — the quiz has moved to the **Reading** prompt (placeholder こたえ) for the same 妙, `1 of 5`. |
| `07-hub-after.png` | — | WaniKani hub after both runs: **Items reviewed 0 · Lessons learned 2 · Learned today 2 of 15** — identical to `01`. The Lessons row reads `5 lessons left in batch · Progress · 5 of 15 parts · 5 kanji`. |

## The rule under test, and the exact distance

`meaningMatches` (`src/rn/ui/review/meaning-match.ts`) normalises both sides, then accepts an exact
meaning, the accepted meanings joined as one answer, or a near miss. The near-miss test is
`isCloseMiss`: both sides at least 4 characters, the same first character, and Levenshtein distance
within 1 — or within 2 when the longer side is 12 characters or more.

For this pair, `peculiarr` against `peculiar`:

- normalised: `peculiarr` and `peculiar` (nothing to strip, already lower-case);
- lengths 9 and 8 — both ≥ 4;
- first character `p` on both sides;
- Levenshtein distance **1** (a single insertion of `r` at the end), and the longer side is 9, so the
  budget is 1. 1 ≤ 1 → accepted.

It cannot match the other three meanings: `unusual`, `odd` and `strange` all fail the first-character
guard.

The base build has no near-miss step at all — `review-store.ts` on `4f5ef1e` compares
`normalizeMeaning(typed)` to each meaning for equality only — so `peculiarr` is refused.

Both builds judge the **lesson quiz** through the second call site, `meaningMatches(typed,
item.meanings)` at `src/rn/ui/review/review-store.ts:1497` (base: the inline equality at the same
place), which is the same rule the review screen uses at `:1259`.

## Answers typed and subjects started

**Two meaning answers were typed in total, both `peculiarr`, both on the same subject 妙 — one per
build. No subject was started or completed.**

- `Lessons learned` is **2** and `Learned today` is **2 of 15** in both `01-hub-before.png` and
  `07-hub-after.png`. Starting a lesson subject would have moved both (a started subject is a learned
  one), so the unchanged counters are the proof that `PUT /assignments/<id>/start` never fired.
- The deliberately wrong base answer is safe for the same reason: the lesson is only reported to
  WaniKani when the subject's quiz is passed, and neither run reached the reading prompt's answer, let
  alone the end of the quiz.
- Each run was backed out from the meaning/reading prompt with the system back gesture, not by
  answering further. Nothing else was typed.
- No reviews were submitted: `Items reviewed` stayed **0** and the hub still reads
  `All caught up · No reviews due`.

The Lessons row's *label* differs between `01` and `07` (`47 lessons available` vs
`5 lessons left in batch · Progress · 5 of 15 parts`) only because entering Lessons resumes the paused
batch locally, and the app process was restarted between the two runs so the local batch progress
counter re-began. It is not account progress — the two counters above are identical.

## Why the "after" still shows the reading prompt, not a "✓ Correct" label

This is deliberate, and worth stating plainly because the PR brief expected a `✓ Correct` still.

In `lessonCheck()` (`src/rn/ui/review/review-store.ts`):

- a wrong meaning sets `feedback: {tone: "wrong", text: "Incorrect"}` and stays on the meaning part
  (`:1515`) — this is `04-before-incorrect.png`;
- a correct meaning on a subject **that has a reading** sets `part: "reading"` and `feedback: null`
  (`:1527`), i.e. it advances silently — this is `06-after-accepted-reading-prompt.png`.

So on the lesson quiz, reaching the reading prompt from the meaning prompt is only possible through
`correct === true`, which makes the advance itself the rendered evidence of acceptance.

The literal `✓ Correct` string exists on the lesson path only in `startWaniKaniLesson()` (`:1572`),
which immediately issues `WaniKani.startLesson(item.assignmentId)` — `PUT /assignments/<id>/start`,
i.e. it **starts the subject**. The capture brief forbids starting a subject, so that state was not
entered. The review screen does show `✓ Correct` for a correct answer (`:1294`), but the review queue
on this account is empty (`0` due, `Next in 38 min`), so the review screen was not reachable at all.

Every lesson subject in this batch is a Level 28 kanji with a reading, so no meaning answer in the
batch can produce the `✓ Correct` label without starting that subject.

## Not exercised / limitations

- **This is the lesson quiz, not the review screen.** `review-store.ts` calls the same
  `meaningMatches` from both, but the review path was unreachable: the account has **0 reviews due**.
  The review screen's `✓ Correct`/`Incorrect` rendering of a near miss is covered by
  `tests/meaning-match.test.cjs`, not by a still.
- **No literal `✓ Correct` still on the lesson path** — see the section above; producing one would
  have started a subject on the live account.
- **One subject, one string.** 妙 with `peculiarr` is a single accepted near miss (distance 1, budget
  1). The distance-2 budget for ≥ 12-character meanings is only covered by
  `tests/meaning-match.test.cjs`. Also untested here: the joined-answer form (`:1259`/`:1497` path),
  punctuation-insensitive matching, and rejections just outside the budget.
- **The batch was resumed twice.** Entering Lessons resumes the paused batch locally, and the app was
  restarted between the base and branch runs, so the local batch progress counter moved (6 → 5 parts)
  while the account counters did not. `Lessons learned` and `Learned today` are the reliable
  comparators, and both are identical in `01` and `07`.
- **No accessibility-tree evidence** — the React Native views expose no text nodes — and **no physical
  device**; one x86_64 emulator with a software GPU. Coordinates were read off the screenshots.
- The emulator process exited twice during the session (after each long run) and was restarted from
  the same AVD; the captures above come from the runs that completed, and the device details are
  unchanged across both boots.
