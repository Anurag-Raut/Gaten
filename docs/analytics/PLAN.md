# Analytics, inline search and the tab shell — plan and decisions

Scope: the Analytics tab (AnkiDroid + WaniKani), the inline search on Home, and the three-tab shell
(`Home · Analytics · Settings`, with Review a pushed flow). Bare React Native 0.86.3 with the New
Architecture — no Expo Router, no framework migration.

## Summary

| # | Decision | Where it lives |
|---|---|---|
| 1 | **One Analytics page, five sections, no source switcher.** `Anki` → `WaniKani` → `Study activity` → `Accuracy` → `Level progress`, in that order, each naming its own source and scope in its meta line. The two source summaries lead because they are what the page is opened for; the twelve-week heatmap follows them, then the two WaniKani detail sections. (The approved package had the heatmap first; the reader asked for the two summaries to lead, and the order was changed on 22 October.) Home already answers "what is due now", so this page never repeats a due count, a streak or a service row. | `src/rn/ui/analytics/AnalyticsScreen.tsx` |
| 2 | **Read-only, always.** Counts only; no grade, no export, no insert/update, no token crossing the bridge. Writing is not merely unused, it is absent from the code path. | `analytics/*`, `anki/AnkiStats.kt`, `wanikani/WaniKaniStats.kt`, `rn/GatenAnkiModule.kt`, `rn/GatenWaniKaniModule.kt` |
| 3 | **Accent-only colour, and no success green.** Every analytical hue is the reader's selected accent at one of a few strengths; nothing on the page invents a second colour language. A reader who picks the green accent gets green charts — the rule bans an *independent* success colour, not a hue. | `AnalyticsScreen.tsx`, `analytics/ink.tsx` |
| 4 | **Study activity is Anki-only and twelve weeks long.** Distinct cards per AnkiDroid study day, from exact-day `prop:rated` searches: 84 searches, batched twelve to a bridge call, cached five minutes. A day the provider refuses stays unknown — outlined, never a zero. | `useAnkiStats.ts`, `model.ts` |
| 5 | **Anki is the collection's shape.** Total cards, the mature share as an accent ring, and the New / Learning / Young / Mature split as one bar in four strengths of the accent. Mature means an interval of 21 days or more, and the counts are cards, not notes or words. | `AnalyticsScreen.tsx`, `useAnkiStats.ts` |
| 6 | **WaniKani is the curriculum's shape.** Level as an accent ring (an index of the plan's levels, never a course percentage), active subjects, and the five SRS stages as one bar in five strengths of the accent. The population is the assignments the account has; the locked curriculum has no assignment, so it is not in these numbers at all, and the section states no exclusion of its own. | `AnalyticsScreen.tsx`, `wanikani/WaniKaniStats.kt` |
| 7 | **Accuracy is cumulative, and says so.** One weighted percentage over every answer the account has ever given: meaning and reading correct and total counts summed, never an average of percentages, with the answer total and how many of them were wrong beside it, and Meaning and Reading as the two tracks under it. The snapshot's per-kind figures — `radical`, `kanji`, `vocabulary`, kana vocabulary included — are a three-column `By subject` group when it carries them. It carries no date range, because the API has no answer history to scope it to. | `wanikani/WaniKaniStats.kt`, `useWaniKaniStats.ts`, `AnalyticsScreen.tsx` |
| 8 | **Level progress is the account's own level history, paced.** `GET /level_progressions` is a row per attempt at a level — a reset leaves its abandoned attempt behind and a level reached again is a second row — and the page reads the latest attempt per level that was not abandoned, so no abandoned or superseded attempt is charted or averaged. A duration is `passedAt − startedAt` — a level is passed when 90 % of its kanji are, and `completedAt` is the burn, not the pass — and the current level is measured from its own `startedAt` to now, labelled "so far". The section states the level, its elapsed time, the average of the completed durations (the level the reader is on is left out of it) and the five most recent durations, drawn as rows against one shared axis. | `wanikani/WaniKaniStats.kt`, `model.ts`, `AnalyticsScreen.tsx` |
| 9 | **Charts without a charting dependency, and without motion.** Bars and the heatmap are plain views; the two rings are `react-native-svg` circles. Nothing on Analytics animates — the screen's motion budget belongs to the pager. Colour is never the only key: every bar and legend entry carries a swatch in the exact shade its segment is drawn in, beside its own text label and value, and the heatmap's unknown cells are outlined as well as unfilled. | `analytics/ink.tsx` |
| 10 | **The bar is the accepted design.** Capsule bar, 68 dp content box, themed colours, the pager's own motion, Analytics selected with the reader's accent. | `src/rn/ui/BottomBar.tsx`, `AppShell.tsx` |
| 11 | **Privacy copy names the read in broad terms.** The lookup-mode disclosure says a connected token also reads "your own study progress — the level you are on, the reviews due now and in the next 24 hours, and how your items are spread across its review stages" for the analytics screen, and that no lesson or review is ever answered or changed. It does not name the review-statistics or level-progression reads behind `Accuracy` and `Level progress`; "study progress" is what the copy says today. | `settings/settings-components.tsx`, `app/src/main/AndroidManifest.xml`, `README.md` |

The 23 October revision changed the page's last two sections. `Recall` became **Accuracy**: one
weighted percentage over every answer the account has given — meaning and reading counts summed, never
an average of percentages — with the answer total and the wrong total beside it, Meaning and Reading
tracks under it, and the per-kind rows (Radicals, Kanji, Vocabulary, kana included) when the snapshot
carries them. `Ahead` became **Level progress**: the level the reader
is on with its elapsed time, the average completed duration, and the five most recent durations with
the current one labelled "so far". The WaniKani summary's exclusion note is gone, and every legend
swatch now repeats the shade of the segment it names.

## Routes

| Tab | Stack (bottom → top) | Back from root | Back from a pushed page |
|---|---|---|---|
| Home (0) | `HomeTab` · `Word` · `Kanji` · `Radical` · `SettingsPage` · review session | leaves the app (system) | pops one entry |
| Analytics (1) | `AnalyticsTab` · `SettingsPage` | Home | pops the page → dashboard |
| Settings (2) | `SettingsTab` · `SettingsPage` | Home | pops the page → hub |

A pushed page or a live session gates the pager (`SwipeGate` via `setOptions`, plus the declarative
`screenOptions`), and the bottom bar hides at stack depth > 0 and during a session.

## Data contract (what the dashboard consumes)

- Anki — `useAnkiStats()` → `{status: loading|ready|unavailable, failure, refreshing, data, refresh}`,
  where `data` is `{collection: {new, learning, young, mature}, activity: {daysAgo, cards|null}[],
  updatedAt}`. The reads are positional: one call of four card-state searches (`is:new`, `is:learn`,
  `is:review prop:ivl<21`, `is:review prop:ivl>=21`) and seven calls of twelve day searches
  (`prop:rated=0` … `prop:rated=-83`). Eight bridge calls, 88 searches, cached for five minutes.
- WaniKani — `useWaniKaniStats()` → `{snapshot, status: idle|loading|ready|refreshing|needsToken|
  authRejected|failed, failure, stale, retryAfterSeconds, cacheFailure, message}`, where `snapshot`
  carries `username`, `level`, `maxLevelGranted`, `subscriptionType`, `dueNow`, `lessonsNow`,
  `nextReviewAt`, `forecast` (25 hourly buckets), `stages` (six buckets, one of which is
  not-started), `recall` and `levelHistory`. `dueNow` and `forecast` are still read and still have
  consumers — Home's due-in-24-hours figure and the review page's next-hour count — but the Analytics
  page itself no longer draws them.
- `recall` is `{reading: {correct, total}|null, meaning: {correct, total}|null, bySubject:
  {key, label, correct, total}[]|null, difficult: {subjectId, characters, percentage, attempts}[]}`,
  or `null` when the read could not be made. It comes from `GET /review_statistics?hidden=false`,
  paged until the collection is exhausted, with one `GET /subjects?ids=…` for the characters of the
  worst-ranked subjects. `difficult` is the worst subjects with at least five answers each, weakest
  first: the read makes it and the snapshot carries it, and the page does not draw it. `bySubject` is
  always the same three rows — `radical`, `kanji`, `vocabulary` — each a
  sum of the running per-subject counts for that kind; it reads as null only when a stored section
  carries no such rows, which is not the same as three rows of zero, and every snapshot this client
  writes carries all three. A failure of this read leaves it `null` and takes nothing else with it.
- `levelHistory` is `{id, level, startedAt, passedAt, abandonedAt}[]|null`, in the API's own
  enumeration order, from the paged `GET /level_progressions`. It is the raw history, resets included:
  a level the account abandoned and reached again is two rows, and `abandonedAt` is what marks the
  attempt a reset left behind. Every timestamp is epoch milliseconds and nullable — a level that is not
  finished yet has no `passedAt`, and the level the reader is on has neither. The page orders and
  filters before it draws — levels sorted, then the latest attempt per level that was not abandoned,
  and the level the reader is on measured from its own `startedAt` to now — so nothing on the page
  depends on the order the API enumerated the rows in. `null` means the read could not be completed; an
  empty list is a real history of nothing — no level's first lesson has been finished. Like `recall`,
  its absence invalidates nothing above it, and an older cached snapshot that predates it reads as null
  rather than as a guess.
- Both paged reads fail closed. A body that does not say how it is paged, a page that fails part-way
  through, a page bound reached with another still offered, or a record the client cannot read — a
  progression row whose timestamps are unusable, a review-statistics record whose counters are missing
  or not numbers — all leave the whole section `null` rather than a sum or a history over only the part
  that arrived: a truncated accuracy would read as the account's overall accuracy, and a truncated
  history as a shorter history. A failed read also replaces the cached copy, so the section is
  unavailable until the next fresh read — it is optional data, and its absence takes nothing else with
  it.
- `model.ts` holds every word and every sum the page puts around those numbers: thousands grouping,
  the em dash, the activity bands and grid, the freshness sentence, and the accuracy and
  level-duration arithmetic. It is pure, so `tests/analytics.test.cjs` pins it.
- Both hooks refresh while the tab is focused, hold the last complete snapshot across a failed refresh,
  respect their TTLs, and refresh again when the app returns to the foreground. The two optional
  WaniKani sections are the one exception: a refresh that completes while their own read failed
  publishes them as unavailable rather than as the older copy.

## Layout

The approved design is a single near-monochrome page: whitespace, 1 dp rules and type size carry the
hierarchy, and no chart sits in a card.

| Element | Geometry / treatment |
|---|---|
| Header | `Analytics` at 35 sp/700; freshness line 13 sp 10 dp beneath (`Updated now`, `Updated 4m ago`, `Updated on 21 Oct`), recomputed every 30 s so it cannot overstate itself; refresh control 43 dp square, radius 14, `surfaceVariant` fill, accent glyph, ≥48 dp target |
| Section | 24 dp above, 22 dp padding, 1 dp `outlineVariant` rule on top; the first section has no rule and starts 22 dp below the header |
| Section header | Title 15 sp/700; scope 11–12 sp right-aligned, capped at 44 % of the width |
| Study activity | Two figures split by one vertical rule (25 sp/700, tracking −1.2), then a 12 × 7 heatmap: 17 dp cell height, 4 dp row gaps, 4 dp radius, only `M`/`W`/`F` row labels, and a `Less → More` legend of five swatches below it |
| Anki / WaniKani | An 84 dp ring (9 dp stroke) beside a column: the major figure, a 9 dp distribution bar, and under it one legend item per segment — its count over a swatch and the segment's name — spread across the bar's own width |
| Bar rows (Accuracy, Level progress) | Every bar row is three fixed columns — label, track, count — plus a reserved note column where a list uses one; the columns are scaled with the reader's text size, so the label and the figure grow together rather than being cut off. The accuracy pair uses a 56 dp label and 34 dp count; the level list a 34 dp label and 52 dp count, with the 56 dp note column every row reserves so the current level's `so far` cannot shorten its track |
| Accuracy | Stacked, one thing under the next: the weighted overall percentage over the answer and wrong-answer totals, then the Meaning and Reading tracks, then `By subject` as three equal columns (a percentage over Radicals / Kanji / Vocabulary, the name allowed two lines) |
| Level progress | The level and its elapsed time beside the average completed duration, then the five most recent durations as bar rows against one shared axis, labelled by level (`L13`) and each stating its days to one decimal (`12.3d`), with the current row's count in the quiet ink and `so far` in its reserved note column; abandoned and superseded attempts are not drawn, and the current level is not in the average |
| Unavailable / skeleton | Rule-fenced 12 sp prose with an accent text action; skeletons are flat `surfaceVariant` bars or a heatmap of unknown cells — neither is a card |

## Colour, by element and mode

Nothing hardcodes a hex: every tone is a theme role, so all seven accents and both modes are served by
the same code. `outlineVariant` is the neutral rule colour (`outline`'s own value), and `secondary`
resolves to the accent itself, which is why every multi-part chart is built from the accent at several
strengths rather than from several hue roles.

| Element | Role | Light | Dark |
|---|---|---|---|
| Page canvas | `background` | `#FDFDFD` | `#101114` |
| Rules, hairlines, empty tracks, heatmap zero cells | `outlineVariant` | `#E9E9ED` | `#303136` |
| Headings, figures, row values | `onSurface` | `#17171A` | `#F5F5F6` |
| Scope lines, captions, notes, labels | `onSurfaceVariant` | `#6C6C75` | `#A7A7B0` |
| Refresh control surface, skeleton bars | `surfaceVariant` | `#F6F6F8` | `#1B1C21` |
| Heatmap levels 1–4 | `accent` at 20 % / 42 % / 67 % / 100 % | derived | derived |
| Anki New / Learning / Young / Mature | `accent` at 20 % / 42 % / 67 % / 100 % | derived | derived |
| WaniKani Apprentice → Burned | `accent` at 20 % / 40 % / 60 % / 80 % / 100 % | derived | derived |
| Rings, bars, text actions, accuracy tracks | `accent` | selected accent's `primary` | selected accent's `primary` |
| Unknown heatmap day | 1 dp `outlineVariant` outline, no fill | derived | derived |

Every legend swatch is painted with the same role and strength as the segment it names — the heatmap's
five, the Anki bar's four and the SRS bar's five — so the ladder is the key, not a picture of it.

The accent is the only hue the page spends, and it is the reader's own choice:

| Accent | Light `primary` | Dark `primary` |
|---|---|---|
| Indigo (default) | `#4338CA` | `#A5B4FC` |
| Blue | `#0B4FB8` | `#93C5FD` |
| Violet | `#6D28D9` | `#C4B5FD` |
| Teal | `#115E59` | `#5EEAD4` |
| Green | `#166534` | `#6EE7B7` |
| Rose | `#9F1239` | `#FDA4AF` |
| Black | `#000000` | `#FFFFFF` |

There is no `success` role anywhere in Analytics, and no brand pink or blue in a chart. The three-step
and five-step ladders are what keep the charts readable for the colourless accent, where hue roles
would have collapsed into one another; the heatmap's zero cells use the hairline tone rather than the
subtler surface tone, because a zero has to be visible as a day that happened and counted nothing.

## States

Per source and per section, so one unreachable service never blanks the other's numbers: a first read
draws skeletons (flat bars, and a heatmap whose every cell is unknown), a refused search draws an em
dash and a sentence, a source that is not set up draws its own rule-fenced line with the one action
that fixes it — on the first section of that source only, so a page with nothing connected is not five
copies of the same button — a cached figure is marked once at the foot of the page, and a genuine zero
is drawn as a zero. An API failure is never rendered as zero, a missing chart bucket is never rendered
as a day without studying, a level history the read could not complete says so rather than reading as
an account that never levelled, and a partial activity window says so.

## Deliberately not shown

A combined learning score; WaniKani review history (the API has none); Anki answer events, ratings or
answer time (the released provider exposes no review log); a WaniKani historical activity heatmap and
any WaniKani daily activity — the level history is not a record of answers, and no per-day WaniKani
figure can be built from running totals; WaniKani's next-24-hour schedule, which Home and the review
page already answer; the subjects the account gets wrong most often — the read carries them and the
snapshot keeps them, and the page draws no list of them; and JLPT/Jōyō/frequency coverage or
deduplicated cross-source vocabulary without a named corpus and matching rule.

## Privacy

- **Anki**: counts only, through the provider's `_id`-only fast path — no card is fetched or rendered
  by the dashboard, and the review flow never reads the queue for Analytics.
- **WaniKani**: the token is read inside Kotlin from the Keystore and passed only to
  `api.wanikani.com`; it never crosses the bridge, is never logged, and the cache is keyed by its
  SHA-256 digest. Only the count reads above, the paged review statistics, the paged level
  progressions and one subject read are made; no lesson, no review, no write.
- **Device**: the analytics view performs no writes to either service.

## Limits (stated, not hidden)

- No WaniKani answer history, retention trend or streak: `GET /reviews` has been empty since 2023, so
  the accuracy figures are cumulative running totals and carry no date range. The level history is the
  one dated record the account keeps, and it is a record of levels, not of answers.
- The activity heatmap is AnkiDroid's distinct-card count per study day. It is not answer events, not
  time studied, and not WaniKani activity.
- Twelve weeks is 84 exact-day searches per read, cached five minutes. An older backend can refuse a
  day or the whole `prop:rated=-N` form; refused days render as unknown, and a window where nothing
  answered shows no figures at all rather than zeros.
- The heatmap's weekday labels are the device's calendar dates for AnkiDroid's day offsets, so a read
  between local midnight and AnkiDroid's own day rollover can label today's bucket with the previous
  weekday. The section states that the days are AnkiDroid's.
- Each accuracy figure is a sum of WaniKani's own running per-subject counts rather than an average of them.
  Percentages are whole numbers rounded to the nearest, so a nearly perfect record can read `100%`
  beside the one answer it got wrong; the answer and wrong totals are the sample that belies it.
- A level duration is elapsed time between two timestamps — `passedAt − startedAt` — not time studied.
  A level still in progress has no passed time, so the current row reads `so far`. The history the API
  returns is raw: a reset leaves its abandoned attempt in it and a level reached again is a second row.
  The page reads the latest non-abandoned attempt per level, so no abandoned or superseded attempt is
  charted or averaged. The read validates what WaniKani documents — an id, a level inside the API's own
  1–60 range, and timestamps that are either an explicit null or a parseable instant — so a progression
  row that contradicts any of that leaves the history unavailable rather than being read as a level the
  reader is still on.
- Both paged reads answer nothing rather than a partial figure: a page bound reached with another page
  still offered, or a record whose counters or timestamps cannot be read, leaves the accuracy or the
  level history `null`, because a truncated accuracy would read as the account's overall accuracy and a
  truncated history as a shorter history.
- The dashboard cannot be exercised end-to-end without a real AnkiDroid collection and, for the
  WaniKani half, a real token.

## Verification

This file records two revisions: the pass that shipped the page with `Recall` and `Ahead` (22
October), and the 23 October revision that replaced them with `Accuracy` and `Level progress`. The
evidence below is labelled by revision, and every entry says what it actually covers.

### Shipped revision (22 October) — verified

Checked on a debug build (`:app:assembleDebug`, x86_64) on the Android 14 emulator against a real
AnkiDroid collection and a connected WaniKani account, at 392 dp and 100 % text scale unless noted.
The captures are in [`screenshots/`](screenshots/); they show the `Recall` and `Ahead` sections, so
they are the record of that pass and not of this one.

| What | Evidence |
|---|---|
| The page, top and scrolled, light | [top](screenshots/page-light-top.png) · [bottom](screenshots/page-light-bottom.png) |
| The same, dark | [top](screenshots/page-dark-top.png) · [bottom](screenshots/page-dark-bottom.png) |
| Every accent, light and dark | [accents-light](screenshots/accents-light.png) · [accents-dark](screenshots/accents-dark.png) |
| Narrow and wide | [320 dp](screenshots/width-320.png) · [340 dp](screenshots/width-340.png) · [430 dp](screenshots/width-430.png) |
| Larger text | [130 %](screenshots/text-130.png) · [160 %](screenshots/text-160.png) |
| A source that cannot be read | [Anki unavailable](screenshots/anki-unavailable.png) — the WaniKani sections carry on, and the action appears once for the source |

- The refresh control re-reads both sources: the freshness line moves from `Updated now` to the age of
  the previous read, with the last numbers kept on screen while the read runs.
- `npx tsc --noEmit` passes; the three node test files pass (70 tests, including the 15 that pin
  `model.ts`, the heatmap bands, the freshness sentence, the Ahead buckets and the query sets); the
  Kotlin unit suite passes (15 tests, including the recall aggregation and its cache round-trip).
- Not exercised in that pass: TalkBack, the reduced-motion preference, and the WaniKani rate-limited
  and offline-cached states (they need the API to fail on demand).

### 23 October revision (`Accuracy`, `Level progress`) — verified

Captured at 1080 × 2340, 440 dpi (≈ 393 dp wide) at font scale 1.0, from a debug build whose only
differences from the tree are the capture application id and a packaged JS bundle: the Analytics source
is the working tree's, and the WaniKani numbers are a real account's. The AnkiDroid collection on that
device is empty, so the Anki half shows confirmed zeros rather than unknown days.

| What | Evidence |
|---|---|
| `Accuracy` and `Level progress`, light | [top](screenshots/accuracy-level-2026-10-23-verified-light-top.png) · [details](screenshots/accuracy-level-2026-10-23-verified-light-details.png) |
| The same, dark | [top](screenshots/accuracy-level-2026-10-23-verified-dark-top.png) · [details](screenshots/accuracy-level-2026-10-23-verified-dark-details.png) |

- **Accuracy, filled**: 97 % overall over 78,680 answers with 2,526 wrong; Meaning 97 % and Reading
  97 % as the two tracks; `By subject` 98 % Radicals, 96 % Kanji, 97 % Vocabulary.
- **Level progress, filled**: level 27 of 60 with "2 days on this level", 17.1 days as the average, and
  the five rows L23 10.5d, L24 11.1d, L25 18.9d, L26 97.3d, L27 2.5d `so far` — the current row in the
  quiet ink with its note column reserved, so every track keeps the same span.
- **WaniKani summary, filled**: 4,524 active subjects over five stages (65 Apprentice, 417 Guru, 438
  Master, 727 Enlightened, 2,877 Burned), each legend item carrying its own swatch.
- **Anki, empty collection**: 0 cards, a dash in the mature ring and a zero legend, and Study activity
  at 0 active days and 0 cards — a real zero, drawn as one.
- The native reads the two sections rest on are covered by the Kotlin stats suite: complete pagination,
  a page bound or an unusable record failing the section rather than shortening it, the per-kind sums,
  reset attempts, and the cache round-trip.
- `npx tsc --noEmit` passes; the node analytics suite passes (21 tests, plus 66 related node tests); the
  Kotlin WaniKani stats tests pass (28, in a 123-test module suite); the Android debug build passes.
- Not verified in this pass: either section at 320/340/430 dp or at 130 %/160 % text, TalkBack, and the
  WaniKani rate-limited and offline-cached states.
