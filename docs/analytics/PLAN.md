# Analytics, inline search and the tab shell — plan and decisions

Scope: the Analytics tab (AnkiDroid + WaniKani), the inline search on Home, and the three-tab shell
(`Home · Analytics · Settings`, with Review a pushed flow). Bare React Native 0.86.3 with the New
Architecture — no Expo Router, no framework migration.

## Summary

| # | Decision | Where it lives |
|---|---|---|
| 1 | **One Analytics page, five sections, no source switcher.** `Anki` → `WaniKani` → `Study activity` → `Recall` → `Ahead`, in that order, each naming its own source and scope in its meta line. The two source summaries lead because they are what the page is opened for; the twelve-week heatmap follows them, then the two WaniKani detail sections. (The approved package had the heatmap first; the reader asked for the two summaries to lead, and the order was changed on 22 October.) Home already answers "what is due now", so this page never repeats a due count, a streak or a service row. | `src/rn/ui/analytics/AnalyticsScreen.tsx` |
| 2 | **Read-only, always.** Counts only; no grade, no export, no insert/update, no token crossing the bridge. Writing is not merely unused, it is absent from the code path. | `analytics/*`, `anki/AnkiStats.kt`, `wanikani/WaniKaniStats.kt`, `rn/GatenAnkiModule.kt`, `rn/GatenWaniKaniModule.kt` |
| 3 | **Accent-only colour, and no success green.** Every analytical hue is the reader's selected accent at one of a few strengths; nothing on the page invents a second colour language. A reader who picks the green accent gets green charts — the rule bans an *independent* success colour, not a hue. | `AnalyticsScreen.tsx`, `analytics/ink.tsx` |
| 4 | **Study activity is Anki-only and twelve weeks long.** Distinct cards per AnkiDroid study day, from exact-day `prop:rated` searches: 84 searches, batched twelve to a bridge call, cached five minutes. A day the provider refuses stays unknown — outlined, never a zero. | `useAnkiStats.ts`, `model.ts` |
| 5 | **Anki is the collection's shape.** Total cards, the mature share as an accent ring, and the New / Learning / Young / Mature split as one bar in four strengths of the accent. Mature means an interval of 21 days or more, and the counts are cards, not notes or words. | `AnalyticsScreen.tsx`, `useAnkiStats.ts` |
| 6 | **WaniKani is the curriculum's shape.** Level as an accent ring (an index of the plan's levels, never a course percentage), active subjects, and the five SRS stages as one bar in five strengths of the accent. The population is the assignments the account has; locked curriculum has no assignment and is excluded out loud. | `AnalyticsScreen.tsx`, `wanikani/WaniKaniStats.kt` |
| 7 | **Recall is cumulative and says so.** Reading and meaning accuracy aggregated from WaniKani's review statistics (correct and total counts summed, never an average of percentages), plus the three worst subjects with enough attempts. It carries no date range, because the API has no answer history to scope it to. | `wanikani/WaniKaniStats.kt`, `useWaniKaniStats.ts` |
| 8 | **Ahead is the schedule WaniKani already holds.** The summary's 25 hourly buckets grouped into eight bars and one total, described as the next 24 hours of current scheduling — not a forecast of the repeats that failures and new lessons will create. | `AnalyticsScreen.tsx`, `model.ts` |
| 9 | **Charts without a charting dependency, and without motion.** Bars and the heatmap are plain views; the two rings are `react-native-svg` circles. Nothing on Analytics animates — the screen's motion budget belongs to the pager. Colour is never the only key: every bar, cell and stage carries its own text label and value, and the heatmap's unknown cells are outlined as well as unfilled. | `analytics/ink.tsx` |
| 10 | **The bar is the accepted design.** Capsule bar, 68 dp content box, themed colours, the pager's own motion, Analytics selected with the reader's accent. | `src/rn/ui/BottomBar.tsx`, `AppShell.tsx` |
| 11 | **Privacy copy names the new read.** The WaniKani settings page and the accessibility disclosure say that a connected token also reads the account's study progress for Analytics — level, due now, SRS spread, review statistics for accuracy — and that lessons and reviews are never touched. | `settings/WaniKaniSettingsScreen.tsx`, `settings/settings-components.tsx`, `README.md` |

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
  not-started) and `recall`.
- `recall` is `{reading: {correct, total}|null, meaning: {correct, total}|null, difficult:
  {subjectId, characters, percentage, attempts}[]}`, or `null` when the read could not be made. It
  comes from `GET /review_statistics?hidden=false` (paged, bounded to ten pages of 500), with one
  `GET /subjects?ids=…` for the characters of the worst three. A failure of this read degrades to
  `null` and never fails the level, queue or SRS numbers beside it.
- `model.ts` holds every word and every sum the page puts around those numbers: thousands grouping,
  the em dash, the activity bands and grid, the freshness sentence and the Ahead buckets. It is pure,
  so `tests/analytics.test.cjs` pins it.
- Both hooks refresh while the tab is focused, hold the last complete snapshot across a failed refresh,
  respect their TTLs, and refresh again when the app returns to the foreground.

## Layout

The approved design is a single near-monochrome page: whitespace, 1 dp rules and type size carry the
hierarchy, and no chart sits in a card.

| Element | Geometry / treatment |
|---|---|
| Header | `Analytics` at 35 sp/700; freshness line 13 sp 10 dp beneath (`Updated now`, `Updated 4m ago`, `Updated on 21 Oct`), recomputed every 30 s so it cannot overstate itself; refresh control 43 dp square, radius 14, `surfaceVariant` fill, accent glyph, ≥48 dp target |
| Section | 24 dp above, 22 dp padding, 1 dp `outlineVariant` rule on top; the first section has no rule and starts 22 dp below the header |
| Section header | Title 15 sp/700; scope 11–12 sp right-aligned, capped at 44 % of the width |
| Study activity | Two figures split by one vertical rule (25 sp/700, tracking −1.2), then a 12 × 7 heatmap: 17 dp cell height, 4 dp row gaps, 4 dp radius, only `M`/`W`/`F` row labels, and a `Less → More` legend of five swatches below it |
| Anki / WaniKani | An 84 dp ring (9 dp stroke) beside a column: the major figure, a 9 dp distribution bar, and the counts under it |
| Recall | Two labelled tracks on the left (Reading, Meaning) split from the worst three subjects on the right by one vertical rule |
| Ahead | The 24-hour total beside eight 56 dp columns labelled `Now, 2h, 4h, 8h, 12h, 16h, 20h, 24h` |
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
| Rings, bars, text actions, recall tracks | `accent` | selected accent's `primary` | selected accent's `primary` |
| Unknown heatmap day | 1 dp `outlineVariant` outline, no fill | derived | derived |

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
as a day without studying, and a partial activity window says so.

## Deliberately not shown

A combined learning score; WaniKani review history (the API has none); Anki answer events, ratings or
answer time (the released provider exposes no review log); a WaniKani historical activity heatmap; and
JLPT/Jōyō/frequency coverage or deduplicated cross-source vocabulary without a named corpus and
matching rule.

## Privacy

- **Anki**: counts only, through the provider's `_id`-only fast path — no card is fetched or rendered
  by the dashboard, and the review flow never reads the queue for Analytics.
- **WaniKani**: the token is read inside Kotlin from the Keystore and passed only to
  `api.wanikani.com`; it never crosses the bridge, is never logged, and the cache is keyed by its
  SHA-256 digest. Only the count reads above, the paged review statistics and one subject read are
  made; no lesson, no review, no write.
- **Device**: the analytics view performs no writes to either service.

## Limits (stated, not hidden)

- No WaniKani history, retention trend or streak: `GET /reviews` has been empty since 2023, so the
  accuracy figures are cumulative running totals and carry no date range.
- The activity heatmap is AnkiDroid's distinct-card count per study day. It is not answer events, not
  time studied, and not WaniKani activity.
- Twelve weeks is 84 exact-day searches per read, cached five minutes. An older backend can refuse a
  day or the whole `prop:rated=-N` form; refused days render as unknown, and a window where nothing
  answered shows no figures at all rather than zeros.
- The heatmap's weekday labels are the device's calendar dates for AnkiDroid's day offsets, so a read
  between local midnight and AnkiDroid's own day rollover can label today's bucket with the previous
  weekday. The section states that the days are AnkiDroid's.
- The recall list needs at least five answers per subject before a subject can be called difficult, and
  the aggregate is capped at ten pages of review statistics.
- The dashboard cannot be exercised end-to-end without a real AnkiDroid collection and, for the
  WaniKani half, a real token.

## Verification

Checked on a debug build (`:app:assembleDebug`, x86_64) on the Android 14 emulator against a real
AnkiDroid collection and a connected WaniKani account, at 392 dp and 100 % text scale unless noted.
The captures are in [`screenshots/`](screenshots/).

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
- Not exercised in this pass: TalkBack, the reduced-motion preference, and the WaniKani rate-limited
  and offline-cached states (they need the API to fail on demand).
