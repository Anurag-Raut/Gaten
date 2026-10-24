# WaniKani review/lesson improvements — device evidence

APK under test: `app-x86_64-debug.apk` (debug, self-contained; JS embedded, no Metro)
SHA-256: `f922e7cd349b384389f1978bdf64bcf2fd9609bc9f012ffc57799e7682d63f8f`

Device: Android emulator `emulator-5554`. Account data is the pre-existing connected test
account; **no real review was submitted, no lesson was started, and no note was saved.**

## Files

| File | Shows |
| --- | --- |
| `01-review-card.png` | Review card: subject badge + Level, Meaning pill, bottom-only input (accent underline, no focus box), full-width Check answer. |
| `02-review-wrong-feedback.png` | Wrong answer: typed text in red, red "Incorrect — the answer is …", auto-expanded answer with Meaning/Reading/Context tabs; still "1 of 19". |
| `03-review-continue-same-card.png` | After **Continue**: same subject, same part, still "1 of 19", input cleared to placeholder, hint retained — the card did **not** advance. |
| `04-review-reading-tab.png` | Reading tab content (primary reading + mnemonic). |
| `05-review-note-editor.png` | Review note editor loaded (Meaning note / Reading note, Cancel / Save note). |
| `06-review-note-draft.png` | A local draft typed into the Meaning note field. |
| `07-review-note-cancel-discarded.png` | After **Cancel** and reopen: fields empty — the draft was discarded (no save). |
| `08-lesson-teaching-note.png` | Lesson teaching card: badge + Level, Meaning/Reading sections, **Note** section with `+ Add note`, full-width Next. |
| `09-lesson-note-editor.png` | Lesson teaching-card note editor loaded. |
| `10-lesson-quiz-input.png` | Lesson quiz: same bottom-only input + full-width Check answer. |
| `11-lesson-quiz-wrong.png` | Lesson quiz wrong answer: red "Incorrect — the answer is …"; the field clears for retyping (the existing quiz keeps its real behavior, per the kit state matrix). |
| `wrong-continue-no-advance.mp4` | Short recording of the review wrong → Continue → same-card sequence. |

## Not exercised on device

- **Note save** (POST/PUT to `study_materials`) — writing to the real account was out of scope for
  QA. The save path (create vs update, synonyms preserved, draft frozen while saving, save refused
  unless the read succeeded) is covered by the Kotlin study-material tests and code review.
- **Note read-failure / retry** state — would require manipulating device networking, which was
  avoided. Covered by the read-retry code path and review.
- **Correct-answer retry submission** — intentionally stopped before submitting a real review. The
  wrong → Continue → retry-submits-once-with-original-miss transition is covered by the targeted
  `tests/wanikani-hub.test.cjs` cases over the fake transport.

This is a sample of the acceptance states, not a claim that every state in the kit matrix was
device-tested.
