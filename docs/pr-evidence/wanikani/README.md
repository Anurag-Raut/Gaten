# WaniKani review/lesson improvements — device evidence

APK under test: `app-x86_64-debug.apk` (debug, self-contained; JS embedded, no Metro)
SHA-256 at capture: `f922e7cd349b384389f1978bdf64bcf2fd9609bc9f012ffc57799e7682d63f8f`

Device: Android emulator `emulator-5554`. Account data is the pre-existing connected test
account; **no real review was submitted and no lesson was started.**

> **Stale notice.** After these screenshots were captured, the Meaning/Reading indicator was changed
> from a coloured pill to plain black text (and a later notes-editor prototype was removed). The
> screenshots below predate that adjustment, so the label colour they show is no longer current;
> everything else (subject badge + Level, bottom-only input, full-width footer, wrong → Continue,
> lesson shell) still reflects the app. Fresh device capture of the label change is pending a lease.

## Files

| File | Shows |
| --- | --- |
| `01-review-card.png` | Review card: subject badge + Level, task indicator, bottom-only input (accent underline, no focus box), full-width Check answer. |
| `02-review-wrong-feedback.png` | Wrong answer: typed text in red, red "Incorrect — the answer is …", auto-expanded answer with Meaning/Reading/Context tabs; still "1 of 19". |
| `03-review-continue-same-card.png` | After **Continue**: same subject, same part, still "1 of 19", input cleared to placeholder, hint retained — the card did **not** advance. |
| `04-review-reading-tab.png` | Reading tab content (primary reading + mnemonic). |
| `08-lesson-teaching-note.png` | Lesson teaching card: badge + Level, Meaning/Reading sections, full-width Next. |
| `10-lesson-quiz-input.png` | Lesson quiz: same bottom-only input + full-width Check answer. |
| `11-lesson-quiz-wrong.png` | Lesson quiz wrong answer: red "Incorrect — the answer is …"; the field clears for retyping (the existing quiz keeps its real behavior, per the kit state matrix). |
| `wrong-continue-no-advance.mp4` | Short recording of the review wrong → Continue → same-card sequence. |
| `wrong-continue-no-advance.gif` | The same sequence as an inline animated GIF (sped up ~3×): typed answer → Check → red wrong feedback + Continue → Continue pressed → same card 人格, still "1 of 19", input cleared. |

## Not exercised on device

- **Correct-answer retry submission** — intentionally stopped before submitting a real review. The
  wrong → Continue → retry-submits-once-with-original-miss transition is covered by the targeted
  `tests/wanikani-hub.test.cjs` cases over the fake transport.
- **The plain-black task-label change** and the removal of the notes-editor prototype were made after
  the device session; they are covered by type-check, the hub tests, and the Kotlin tests, but not yet
  by a device screenshot.

This is a sample of the acceptance states, not a claim that every state in the kit matrix was
device-tested.
