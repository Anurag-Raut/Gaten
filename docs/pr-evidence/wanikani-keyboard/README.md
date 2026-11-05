# WaniKani review evidence — the keyboard stays up after a kanji's reading

Captured for the branch `fix/keyboard-after-kanji`: answering a **reading** (the last part of a kanji
or vocabulary subject) dismissed the keyboard, while answering a **meaning** kept it. Both now keep
it, and the keyboard no longer has to be reopened by the reader to continue.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `HEAD` of `fix/keyboard-after-kanji` (cut from `origin/dev`); the fix is the `editable` expression in `WaniKaniReview.tsx` plus the `busy` guard in `wkCheck` |
| JS actually running | Metro started from this worktree on port 8081. The served `index.bundle` was checked before capture: the pre-fix build has `correct || submitting || (!busy && session.phase === "answering")`, the post-fix build has `correct || submitting || session.phase === "answering"` |
| App | `com.gaten` 0.1.0, debug build, launched with `am start` against Metro |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080x2340 @ 440 dpi, Gboard (`LatinIME`) as the only IME for the whole pass |
| Keyboard state | `dumpsys window windows` on the `InputMethod` window (`mViewVisibility`, `mHasSurface`), plus the field's own focus flag in `dumpsys input_method` (`mServedView` → `ReactEditText{... .F......}`) |
| Queue | The account had **0 reviews due** ("All caught up · Next in 1 hour 26 min"), so the review queue could not produce a card |

## Why the screen was seeded

With no reviews due, the review screen is unreachable through the queue. The captures therefore ran
against a **synthetic `WaniKaniSession`** injected into the real store from a temporary, uncommitted
harness (`devSeedReview`, deleted before the commit). It contained one real kanji — 妙, accepted
reading みょう — whose two prompts are its meaning and its reading. The component, the store actions,
the remount key, the native `WaniKani.readingAnswer` conversion and the IME were the real ones; only
the queue entry was fabricated. `git log -1 --stat` on the branch does not contain the harness.

## Mechanism

`wkCheck` sets `busy` and then awaits `WaniKani.readingAnswer` before judging a reading. During that
await `phase` is still `"answering"` while `busy` is true, so the field's predicate
`(!busy && session.phase === "answering")` became false: the field was disabled
(`editable` → `ReactEditText.setEnabled(false)`) and the focused input was blurred, which takes the
keyboard with it. When the check resolved, `phase: "correct"` made it editable again, but nothing
refocused it. A meaning is compared synchronously, so `setBusy(true)` / the result / `setBusy(false)`
commit as one render and the field is never seen disabled — which is why only readings dropped the
keyboard.

## Results

| State | Before the fix | After the fix |
|---|---|---|
| Meaning correct ("peculiar"), held | keyboard up (`mViewVisibility=0x0`, `mHasSurface=true`) — `before-01-meaning-correct-keyboard-up.png` | unchanged |
| Reading correct (みょう), held | **keyboard down** — `mViewVisibility=0x8`, `mHasSurface=false`, field flags `VFED..CL. ........` (not focused) — `before-02-reading-correct-keyboard-down.png` | **keyboard up** — `mViewVisibility=0x0`, `mHasSurface=true`, field flags `VFED..CL. .F......` (focused) — `after-01-reading-correct-keyboard-up.png` |

The two clips drive the same seeded card end to end: `before.mp4` (118.8 s, includes an earlier
wrong-answer attempt in the same session) and `after.mp4` (25.2 s).

## Limitations

- The queue was empty; the session was seeded from a temporary harness (deleted from the commit), so
  the capture proves the field/check path, not a live review pulled from WaniKani.
- The seeded subject has a fabricated id, so the **submit** after the reading's hold was not
  exercised; `submitWaniKani` and the `"submitting"` phase are unchanged by this fix.
- The double-Enter guard (`if (this.state.busy) return;` in `wkCheck`) is a deterministic early
  return on the same `busy` flag the check sets synchronously. A double tap on the IME's Done key was
  attempted on a wrong reading; the visible state did not double-advance, but the stored wrong count
  was not instrumented, so the guard is reasoned rather than measured.
- One emulator, one kanji, portrait only.
