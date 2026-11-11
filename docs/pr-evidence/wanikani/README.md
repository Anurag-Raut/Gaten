# WaniKani review/lesson improvements — device evidence

APK under test: `app-x86_64-debug.apk` (debug, self-contained; JS embedded, no Metro)
SHA-256 at capture: `1ebee538af9520d7845a8ebcf225eadc5b04596e26e66c15b93997b16c8fe296`

Device: Android emulator `emulator-5554`. Account data is the pre-existing connected test
account; **no real review was submitted and no lesson was started.**

## Files

| File | Shows |
| --- | --- |
| `01-review-card.png` | Review card: subject badge + Level, a single plain black Meaning label above the field, bottom-only input, full-width Check answer — no helper label under the input. |
| `02-review-wrong-feedback.png` | Wrong answer: the typed answer stays in red, feedback is **only** "Incorrect" (no canonical answer), `Show answer` is closed, and the footer offers **Continue**. |
| `03-review-continue-same-card.png` | After **Continue**: same subject and part, still "1 of 74", input cleared for the retry, answer still hidden. The card did **not** advance. |
| `wrong-continue-no-advance.mp4` | Short recording of the wrong → Continue → same-card sequence. |
| `wrong-continue-no-advance.gif` | The same sequence as an inline animated GIF. |

## Not captured in this refresh

- The lesson teaching card and lesson quiz were not re-captured after the follow-up edits; the lesson
  uses the same shared pieces (plain label, no helper label, feedback string "Incorrect"), but its
  screenshots were removed rather than left stale.
- **Correct-answer retry submission** — intentionally stopped before submitting a real review. The
  wrong → Continue → retry-submits-once-with-original-miss transition is covered by the targeted
  `tests/wanikani-hub.test.cjs` cases over the fake transport.

This is a sample of the acceptance states, not a claim that every state in the kit matrix was
device-tested.
