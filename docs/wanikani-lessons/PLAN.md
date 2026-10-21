# WaniKani: Reviews and New cards

Status: proposed · branch `codex/consistent-design-language` · owner: UI + WaniKani integration

## 1. What this adds

The WaniKani page today drops the reader straight into the review queue. This plan turns that page
into a two-tab hub — **Reviews** and **New cards** — and adds a lesson flow behind the second tab:
teach a batch of new subjects, quiz them, and mark each one started so WaniKani moves it into the
review queue.

Nothing about the existing review submission changes: reviews keep sending `POST /reviews`, one per
finished subject. Lessons are separate: they are completed with `PUT /assignments/{id}/start`, the
only documented way to finish a lesson.

## 2. API facts (verified against docs.api.wanikani.com/20170710)

| Purpose | Endpoint | Notes |
| --- | --- | --- |
| What is available now | `GET /summary` | `data.lessons[].subject_ids`, `data.reviews[].subject_ids`, `data.next_reviews_at`. Already fetched by the stats read and currently discarded. |
| The lesson queue | `GET /assignments?immediately_available_for_lessons=true&hidden=false` | Each assignment has `id` (assignment id), `data.subject_id`, `data.srs_stage` (0), `data.unlocked_at`, `data.started_at` (null). |
| The lesson content | `GET /subjects?ids=…` | Radical, kanji, vocabulary, kana_vocabulary. Carries `characters`, `meanings`, `readings`, `meaning_mnemonic`, `reading_mnemonic`, `component_subject_ids`, `context_sentences`, `pronunciation_audios`, `level`, `lesson_position`, `document_url`. |
| Finish a lesson | `PUT /assignments/{assignment_id}/start` | "Mark the assignment as started, moving the assignment from the lessons queue to the review queue." Empty body. |

Two constraints the API states explicitly:

- **The lesson quiz is never `POST /reviews`.** "Reviews are not to be submitted for the quizzes in
  lessons, instead the Start an Assignment endpoint should be used." So the quiz is scored on the
  device and completion is one `PUT …/start`.
- **Entitlement gates lessons too.** `max_level_granted` (3 free / 60 paid) is the ceiling; lessons
  and reviews above it "shouldn't be available at all". The app already reads and respects it.

`PUT` does not exist in `WaniKaniApi` yet (only `get`/`getImage`/`getWithMeta`/`post`), and the
lesson content needs **vocabulary and kana vocabulary**, which `WaniKaniSubject`/`decodeSubject`
deliberately do not decode today.

## 3. Where the code is now

- **Native client** `app/src/main/java/com/gaten/app/wanikani/`
  - `WaniKaniApi.kt` — HTTP; `post` at line ~197 is the template for the new `put`.
  - `WaniKaniClient.kt` — `reviewQueue()` (assignments → subjects → sort by `available_at,id`),
    `submitReview()`, `entitlementLevel()`.
  - `WaniKaniDecode.kt` — `decodeAssignmentCollection`, `decodeReviewSubjectCollection`,
    `decodeReviewMeanings`, `decodeReviewReadings`, `plainWaniKaniText`, `decodeIds`,
    `decodeDocumentUrl`. `decodeSubject` (reference) is kanji/radical only.
  - `WaniKaniReview.kt` — `WaniKaniReviewItem`, `WaniKaniReviewQueue`, `WaniKaniReviewSubmission`,
    `readingAnswerHiragana`.
  - `WaniKaniStats.kt` — already calls `GET /summary`; `data.lessons` is parsed past.
- **Bridge** `app/src/main/java/com/gaten/app/rn/GatenWaniKaniModule.kt` — `reviewQueue`,
  `readingAnswer`, `submitReview`, `stats`, `refreshStats`, lookup, connect/disconnect.
- **JS** `src/rn/native/GatenWaniKani.ts` — the same surface, typed.
- **Store** `src/rn/ui/review/review-store.ts` — `WaniKaniSession`, `wkReview`/`wkDone`/`wkFailed`
  steps, `openWaniKani`, `enterWaniKaniReview`, `wkEntry`, `wkCheck`, `submitWaniKani`,
  `wkRetrySubmit`, `advanceWaniKani`, `finishWaniKani`, `retryWaniKani`.
- **UI** `src/rn/ui/review/WaniKaniReview.tsx` (the quiz), `WaniKaniConnect.tsx`,
  `review-ui.tsx` (the shared primitives), and `ReviewScreen.tsx` which switches on the step.

## 4. Native design

### 4.1 One HTTP verb

Add to `WaniKaniApi`:

```
open suspend fun put(path: String, body: String, token: String): WaniKaniFetch
```

A near-copy of `post` with `requestMethod = "PUT"`. Same policy checks (`WaniKaniPolicy`), same
`maxBytes` body bound, same `waitFor`/rate-limit handling, same status mapping
(200/201 → `Body`, 401/403 → `Unauthorized`, 404 → `NotFound`, else `Failed`). `PUT …/start`
answers with the updated assignment, which we do not need, so no new decode.

### 4.2 The lesson shapes — new file `WaniKaniLesson.kt`

Deliberately **separate from `WaniKaniSubject`**. The reference model and its cache are kanji/radical
only, and adding vocabulary there would force `WaniKaniPanel` and the lookup paths to handle a kind
they never receive. The lesson decode instead reuses the shared decode helpers.

```kotlin
data class WaniKaniLessonItem(
    val assignmentId: Int,        // what PUT …/start takes
    val subjectId: Int,
    val type: String,             // radical | kanji | vocabulary | kana_vocabulary
    val characters: String?,      // null for an image-only radical
    val level: Int,
    val lessonPosition: Int,      // for ordering the queue
    val meanings: List<String>,   // accepted answers, primary first (decodeReviewMeanings)
    val primaryMeaning: String,
    val readings: List<String>,   // hiragana-folded, for the quiz (decodeReviewReadings)
    val primaryReading: String?,
    val readingMnemonic: String,  // plainWaniKaniText
    val meaningMnemonic: String,
    val components: List<WaniKaniComponent>,       // ids only, client fills names
    val contextSentences: List<WaniKaniContextSentence>, // vocabulary only, may be empty
    val documentUrl: String?
)

data class WaniKaniContextSentence(val japanese: String, val english: String)

sealed interface WaniKaniLessonQueue {
    data class Available(val items: List<WaniKaniLessonItem>) : WaniKaniLessonQueue
    data object Empty : WaniKaniLessonQueue
    data object NeedsToken : WaniKaniLessonQueue
    data object AuthRejected : WaniKaniLessonQueue
    data class Unreachable(val failure: WaniKaniFailure, val retryAfterSeconds: Long? = null) : WaniKaniLessonQueue
}

sealed interface WaniKaniLessonStart {   // mirrors WaniKaniReviewSubmission
    data object Started : WaniKaniLessonStart
    data object NeedsToken : WaniKaniLessonStart
    data object AuthRejected : WaniKaniLessonStart
    data class Unreachable(val failure: WaniKaniFailure, val retryAfterSeconds: Long? = null) : WaniKaniLessonStart
}
```

`meanings`/`readings` use the **same** `decodeReviewMeanings`/`decodeReviewReadings`, so the lesson
quiz judges answers exactly like the review quiz (whitelist accepted, blacklist rejected, readings
folded to hiragana). This is what lets the two flows share one comparison path.

### 4.3 Decode — extend `WaniKaniDecode.kt`

- Make `decodeReviewMeanings` / `decodeReviewReadings` internal (or keep private and call them from
  the new decoder in the same file).
- Add `decodeLessonSubjectCollection(text): List<WaniKaniLessonItem>?` — walks `data`, keeps
  `resource.id`, `resource.object`, and reads `characters`, `level`, `lesson_position`,
  `meaning_mnemonic`, `reading_mnemonic`, `component_subject_ids`, `context_sentences` (`ja`/`en`),
  `document_url`; folds in the shared meanings/readings; drops items with no accepted meaning.
- Add a small `DecodedLessonAssignment` carrying `id` + `subjectId` from `decodeAssignmentCollection`
  (it already returns both — reuse it as-is).

### 4.4 Client — extend `WaniKaniClient.kt`

```
suspend fun lessonsQueue(): WaniKaniLessonQueue
suspend fun startLesson(assignmentId: Int): WaniKaniLessonStart
```

`lessonsQueue` mirrors `reviewQueue`:

1. read token, else `NeedsToken`;
2. `GET /assignments?immediately_available_for_lessons=true&hidden=false`; malformed → `MALFORMED`;
   empty → `Empty`;
3. `GET /subjects?ids=…`;
4. drop subjects above `entitlementLevel()`;
5. join assignments to subjects by `subjectId`, **order by `level` then `lessonPosition`** (WaniKani's
   presentation order), `Available(items)`.

`startLesson` validates `assignmentId > 0` (else `MALFORMED`, no request), reads the token, then
`PUT /assignments/{id}/start` with an empty body, mapping exactly like `submitReview`.

### 4.5 Stats — count the lessons WaniKani already sent

In `WaniKaniStats.kt`, where `data.lessons` is currently ignored, parse
`data.lessons[0].subject_ids.length` into a new `WaniKaniStatsSnapshot.lessonsNow` (0 when absent).
This gives the hub's tab counts from a read it already performs, with no extra request. (When the
snapshot is cached/stale, the count is the cached one, which is the same honesty rule the other
numbers follow.)

### 4.6 Bridge

`GatenWaniKaniModule.kt`:

- `@ReactMethod lessonsQueue(promise)` → `{status:"available", items:[…]}` / `empty` / `needsToken`
  / `authRejected` / `{status:"failed", failure, retryAfterSeconds?}`.
- `@ReactMethod startLesson(assignmentId, promise)` → `{status:"started"}` / `needsToken` /
  `authRejected` / failed.
- `WaniKaniLessonItem.toJsMap()` — same field names as `WaniKaniReviewItem` plus `assignmentId`,
  `level`, `readingMnemonic`, `meaningMnemonic`, `components`, `contextSentences`, `documentUrl`.
- Add `lessonsNow` to the stats map.

`src/rn/native/GatenWaniKani.ts`: matching types (`WaniKaniLessonItem`, `WaniKaniLessonQueueResult`,
`WaniKaniLessonStartResult`) and functions `lessonsQueue()` / `startLesson(assignmentId)`; add
`lessonsNow: number` to `WaniKaniStatsSnapshot`.

## 5. App design

### 5.1 Flow and steps

`ReviewStep` gains a hub and a lesson session; `wkDone` folds into the hub's caught-up state.

```
opening(waniKani)
  → wkConnect            (no token, or rejected)
  → wkHome               (token works)          ← new
       tab "reviews":  Start reviews → wkReview → done → back to wkHome
       tab "lessons":  Start lessons → wkLesson → done → back to wkHome
  → wkFailed             (a queue/summary read that could not answer)
```

- `openWaniKani()` now lands on `wkHome` after the token check instead of jumping into `wkReview`.
- `wkReview` and its submit/retry/advance machinery are unchanged.
- When a review session finishes, return to `wkHome` (reviews tab); the hub re-reads the summary and
  shows the remaining count or the caught-up state. `wkDone` is removed; its "next review at" content
  becomes the hub's empty state, sourced from `WaniKani.stats().snapshot.nextReviewAt`.
- New `wkLesson: { session: WaniKaniLessonSession }`.

`stepDepth`: `wkHome` at 1 (same as `wkConnect`), `wkLesson` at 2 (same as `wkReview`).

### 5.2 The lesson session (store)

```ts
interface WaniKaniLessonSession {
  batch: WaniKaniLessonItem[];     // the subjects being taught/quizzed now
  remaining: number;               // lessons still queued after this batch
  phase: "teaching" | "quizzing" | "starting" | "startFailed";
  teachIndex: number;              // during "teaching"
  quizIndex: number;               // during "quizzing"
  part: "meaning" | "reading";
  entry: string;
  incorrectMeaning: number;
  incorrectReading: number;
  feedback: {tone: "right" | "wrong"; text: string} | null;
  retrying: boolean;
  failure: WaniKaniFailure | null;
  authRejected: boolean;
  taughtCount: number;             // for the progress line across the batch
  quizzedCount: number;
}
```

Handlers (parallel to the review ones, sharing helpers):

- `startLessons()` — reads `lessonsQueue()`, takes `min(available, batchSize)` items as the batch
  (WaniKani's `lessons_batch_size`, default 5; see §8 open question), enters `teaching` at index 0.
- `lessonNext()` — advance `teachIndex`; after the last card, switch to `quizzing` at index 0.
- `lessonEntry(text)`, `lessonCheck()`, `lessonRetrySubmit()` — the review logic (`normalizeMeaning`,
  `WaniKani.readingAnswer`, retype-on-wrong, count wrongs) with `startLesson(assignmentId)` in place
  of `submitReview`.
- On `started`: advance `quizIndex`; after the last subject, return to `wkHome` (lessons tab).
- Partial batch leaving is safe: each subject is started the moment its quiz is passed, so a reader
  who walks away mid-batch has already banked the ones that were finished, and the rest stay in the
  queue.

Shared helpers to lift out of the review-only code (small, no behaviour change): `partsOf`,
`normalizeMeaning`, and the "was this answer right" check, so the lesson quiz and the review quiz
cannot drift apart.

### 5.3 Hub UI — `src/rn/ui/review/WaniKaniHome.tsx` (new)

- `SubHeader title="WaniKani"` with the back arrow.
- **Tab control**: a two-segment `SegmentedTabs` primitive (added to `review-ui.tsx`): equal halves,
  each `label` + count; the selected half in `colors.onSurface` with a 2 dp accent underline, the
  other in `colors.onSurfaceVariant`; `accessibilityRole="tab"` with `accessibilityState={{selected}}`
  and a `tablist` container. Colour is never the only signal — the label and count change too.
- **Reviews tab**: when due > 0, a heading with the count and a `FilledAction` "Start reviews"; when 0,
  a `StateBlock` "You're caught up" with the next review time when known.
- **New cards tab**: when available > 0, the count and a `FilledAction` "Start lessons"; when 0, a
  `StateBlock` "No new cards right now".
- The tab counts come from the stats snapshot (`dueNow`, the new `lessonsNow`); the hub triggers a
  `refreshStats()` on entry so the numbers are fresh, and renders the last snapshot while it waits.
- The tabs are local UI state on the hub (`useState<"reviews" | "lessons">`), not a route.

### 5.4 Lesson UI — `src/rn/ui/review/WaniKaniLesson.tsx` (new)

Two phases in one screen, same shell as `WaniKaniReview` (safe-area top, `KeyboardAvoidingView` from
`react-native-keyboard-controller`, `SubHeader`, `ProgressLine`, `ActionFooter`).

**Teaching** (one card per subject):

- Caption line `<Kind> · Level <n>` (kind in the neutral ink; lessons are not meaning/reading yet).
- The prompt: the character at display size (single vs. word, as in the review), or the radical image
  via a shared `RadicalDrawing` (extracted from `WaniKaniReview.tsx` so both use it).
- **Meaning** section: the primary meaning in the meaning ink, other accepted meanings after it, then
  the meaning mnemonic as body text.
- **Reading** section (kanji / vocabulary): the readings and the reading mnemonic. Omitted for
  radicals and kana vocabulary.
- **Components** (kanji / radical): the same chips the reference panel draws.
- **Context sentences** (vocabulary): one or two `japanese — english` pairs, the offline-corpus
  presentation.
- Footer `FilledAction` "Next" (last card: "Start quiz").
- A doc link (`documentUrl`) is out of scope for v1; the field is carried for later.

**Quiz** (reuses the review quiz exactly):

- Caption `<Kind> · Reading|Meaning`; the same prompt; `AnswerInput` with the empty-caret overlay; the
  meaning/reading hint; `Feedback`; wrong answer shows the correct one and re-asks; footer "Check
  answer". On a `startFailed`, the same "Send again" footer and message the review shows.

The components the review screen already owns (`AnswerInput`, `Feedback`, `FilledAction`,
`ActionFooter`, `ProgressLine`, `SubHeader`, `BusyRow`, `StateBlock`) are reused; `RadicalDrawing` and
the meaning/reading block are the only things lifted into shared modules.

### 5.5 `ReviewScreen.tsx`

Add the two `switch` arms: `step.kind === "wkHome"` → `WaniKaniHome`, `step.kind === "wkLesson"` →
`WaniKaniLesson`; remove `wkDone`. Wire the new actions through the same `reviewActions` object the
other WaniKani steps use.

## 6. Error and edge cases

| Case | Behaviour |
| --- | --- |
| No token / rejected token | `wkConnect`, as reviews do; after connecting, land on the hub. |
| Offline / timeout / rate limit reading lessons | `wkFailed` with the retry action and `retryAfterSeconds`, as reviews. |
| Lesson subject above `max_level_granted` | Dropped from the queue (never shown, matching the API's rule). |
| `PUT …/start` fails | Subject stays quizzed on screen with "Send again"; retrying is safe because start is idempotent for an already-started assignment. |
| Token rejected during a start | `authRejected` state; the footer offers "Reconnect". |
| Empty queue / nothing due | Hub's per-tab caught-up state, never a failure. |
| Reader leaves mid-batch | Items already started stay started; the rest remain in lessons. |
| Radical with no character | `RadicalDrawing` fetches/caches the SVG via the existing lookup; prompt stays empty until it arrives. |
| Vocabulary with no readings | Meaning-only quiz (kana vocabulary), same rule as reviews. |

## 7. Verification

The reading side can be checked without touching the account; the writing side must not submit
lessons or reviews during verification.

- **Read-only**: with a real token on the emulator, open the hub (reviews + new-card counts match
  wanikani.com), open the New cards tab, and step through the teach cards and the quiz down to the
  "Check answer" screen **without** pressing a submit action. Confirm offline behaviour in airplane
  mode: hub keeps its counts, a lessons read shows `wkFailed` with retry.
- **Fixtures**: unit-test the decode (`decodeLessonSubjectCollection`) and the queue ordering against
  captured `GET /assignments` + `GET /subjects` JSON, no network. Add Kotlin tests beside the
  existing WaniKani decode tests.
- **No real writes**: `startLesson` is exercised only through a mocked/fixture path (or pointed at an
  assignment id we do not own), never against live assignments. The same rule already applies to
  `submitReview`.
- Commands: `npx tsc --noEmit`; `./gradlew testDebugUnitTest`; `git diff --check`;
  `./gradlew :app:assembleDebug`; install `app-x86_64-debug.apk`.
- Screenshots into `artifacts/visual-review/` at 100% and 130% font scale, light and dark: hub (both
  tabs), a teach card (radical, kanji, vocabulary), the quiz.

## 8. Open questions

1. **Batch size.** Use WaniKani's own `lessons_batch_size` from `GET /user` `preferences` (the
   entitlement read already sees `/user`; cache it beside the entitlement), default 5. Alternative:
   teach every available card in one go. Recommendation: honour the stored preference, fall back 5.
2. **Per-item vs. per-batch start.** Plan: `PUT …/start` per subject as its quiz passes (durable
   progress, matches the endpoint's granularity). Alternative: start the whole batch at the end
   (fewer writes, loses progress on abandon). Recommendation: per item.
3. **Vocabulary audio.** `pronunciation_audios` is in the response but has no player in the app yet;
   carry the field and skip playback in v1.
4. **Retiring `wkDone`.** Folding it into the hub is cleaner but touches the review completion path;
   if we want zero risk to reviews, keep `wkDone` as the post-review screen and have it offer a
   "New cards" action into the lessons tab.

## 9. Task breakdown

**Phase 0 — data (no UI)**
- [ ] `WaniKaniApi.put`.
- [ ] `WaniKaniLesson.kt` shapes.
- [ ] `decodeLessonSubjectCollection` + reuse of the review meaning/reading decode.
- [ ] `WaniKaniClient.lessonsQueue` (ordering, entitlement) and `startLesson`.
- [ ] `WaniKaniStatsSnapshot.lessonsNow` from the summary already read.
- [ ] Kotlin decode/queue unit tests with fixtures.

**Phase 1 — bridge**
- [ ] `GatenWaniKaniModule` `lessonsQueue` / `startLesson` + maps; `lessonsNow` on stats.
- [ ] `GatenWaniKani.ts` types and functions.
- [ ] `npx tsc --noEmit`.

**Phase 2 — hub**
- [ ] `SegmentedTabs` in `review-ui.tsx`.
- [ ] `ReviewStep` `wkHome`, `ReviewStore.openWaniKani` → hub, `refreshStats` on entry, `stepDepth`.
- [ ] `WaniKaniHome.tsx` (both tabs, counts, start actions, caught-up states).
- [ ] `ReviewScreen.tsx` arm; `wkDone` decision.

**Phase 3 — lessons**
- [ ] Extract `RadicalDrawing` and the meaning/reading block into shared modules.
- [ ] `WaniKaniLessonSession` + `startLessons`/`lessonNext`/`lessonEntry`/`lessonCheck`/`lessonRetry`.
- [ ] `WaniKaniLesson.tsx` teach + quiz phases.
- [ ] `ReviewScreen.tsx` `wkLesson` arm.

**Phase 4 — verify and document**
- [ ] Emulator pass at 100% and 130%, light/dark, online/offline (no real submissions).
- [ ] `testDebugUnitTest`, `assembleDebug`, install.
- [ ] Extend `docs/design-language.md` with the hub tabs and the lesson teach/quiz language.
- [ ] Update this plan's status and the README's WaniKani note if the surface list changes.
