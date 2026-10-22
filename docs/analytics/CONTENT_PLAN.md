# Analytics content proposal

## Summary for discussion

| Decision | Proposed direction |
|---|---|
| Purpose | Answer: What is due? Am I consistent? Am I progressing? What am I forgetting? What should I focus on? |
| Navigation | Prefer **All · Anki · WaniKani**, as views inside Analytics. Finalize the content before finalizing tabs. |
| Main sections | Workload, activity, progress, recall, difficult items, and content coverage. Each view gets only the sections it can support. |
| All view | A useful cross-source overview: workload together, activity together where coverage permits, progress and recall side by side. |
| Available today | Anki deck workload, inventory, mature-card count, seven days of distinct-card activity; WaniKani level, lessons, due items, next-day schedule, SRS totals. |
| More data we can read | WaniKani cumulative accuracy, difficult items, level history and item milestones; Anki additional card searches and, on compatible versions, richer scheduling metadata. |
| Main limit | Neither current integration supplies raw historical answer events. WaniKani has removed that read API; AnkiDroid's proposed review-log provider is not released. |
| History policy | Show what is actually observed. Any new Gaten-only history must say so; missing days stay unknown. |
| First recommendation | Prioritize useful current-state and recoverable-history sections. Treat comprehensive activity history, time, retention trends, and deduplicated language coverage as separate decisions. |
| Status | **Draft for brainstorming, 22 October 2026. No implementation authorized by this document.** |

This is a content and data feasibility proposal, not a visual design or an implementation checklist.
It revisits the older single-page decision in [PLAN.md](PLAN.md); that file records an earlier
implementation and is not the specification for this proposed revision. Recommendations below
remain open until we refine them together.

## 1. What to borrow from the reference products

The public [WKStats dashboard component](https://www.wkstats.com/components/progress/dashboard-1.0.1.html)
shows current level, typical level-up time, time on level, estimated milestones, Guru+ items,
and reading/meaning accuracy split by item type. Its public navigation also separates level-up
history, item lists and JLPT/Jōyō/frequency charts. These are useful content categories. The public
projections component currently displays an under-construction message, so a navigation label
alone is not evidence of a working feature. No personal account was accessed.

Anki's statistics suggest a complementary emphasis: activity, workload, collection composition,
recall, answer buttons and time. Its definition of a review counts each answer, including repeated
answers to the same card. That distinction matters when designing our labels. See the
[Anki statistics manual](https://docs.ankiweb.net/stats.html) and
[AnkiDroid manual](https://ankidroid.org/docs/manual.html#statistics).

The proposed product should borrow those questions and useful breakdowns, without assuming
that everything visible inside either source app is available through its integration API.

## 2. What Gaten actually has access to

Availability labels used throughout:

- **Now** — the current Gaten bridge already returns the required data.
- **Read more** — additional supported reads or calculations can supply it; not implemented here.
- **Version-dependent** — supported by a newer released provider contract; check capability and fall back on older installations.
- **Track going forward** — needs durable new tracking; existing history cannot simply be fetched.
- **External data** — needs a separate mapping/corpus and a defined matching rule.
- **Blocked for full history** — no supported released source for the historical events needed.

### Anki data inventory

| Data / metric | Availability | Meaning and constraints |
|---|---|---|
| Deck names and identifiers | Now | Deck hierarchy and filtered-deck flag are available. |
| Remaining new, learning and review cards | Now | AnkiDroid's scheduler counts after daily limits; parent counts include subdecks. |
| Total cards and cards with intervals ≥21 days | Now | Current inventory for the selected deck, including its subdecks. These are cards, not notes or unique words. |
| Distinct cards answered today / each of the last seven study days | Now | Repeating one card on a day still counts once. Current activity searches are collection-wide. |
| Longer distinct-card activity history | Read more | Exact-day searches can supply additional days on compatible backends. This does not require a raw review log and still does not count answer events. |
| New, learning, review, suspended, buried and tagged-leech card counts | Read more | Use supported card searches. Define mutually exclusive base states; suspended/buried are overlays, not extra cards to add to the total. |
| Per-card interval, repetitions, lapses, type/queue and last-review time | Version-dependent | Richer public card projections exist in AnkiDroid 2.25.3. Gaten does not currently request them. Last-review time is not a complete review history. |
| Per-card FSRS stability, difficulty, desired retention and decay | Version-dependent | Nullable scheduling state, useful only where the collection/card actually has it. These are model values, not measured historical retention. |
| Scheduled future workload / overdue backlog | Read more or version-dependent | Search counts can support basic due-date buckets; richer card metadata can improve them. Separate overdue, daily limits, and scheduled due dates. |
| All historical answer events and their ratings/timing | Blocked for full history | The released public provider does not expose a review-log endpoint. |
| Reviews, ratings and timing performed inside Gaten | Track going forward | Gaten already submits live grades and timing, but does not retain an analytics history. This would miss reviews performed elsewhere. |
| Unique vocabulary / kanji represented by Anki cards | External data + content matching | Requires intentional Japanese deck/note-field mapping. Card count cannot stand in for vocabulary knowledge. |

Evidence in this repository: [AnkiStats.kt](../../app/src/main/java/com/gaten/app/anki/AnkiStats.kt),
[useAnkiStats.ts](../../src/rn/ui/analytics/useAnkiStats.ts),
[AnkiReview.kt](../../app/src/main/java/com/gaten/app/anki/AnkiReview.kt), and
[GatenAnkiModule.kt](../../app/src/main/java/com/gaten/app/rn/GatenAnkiModule.kt).

The current dependency is `api-v1.1.0`. A newer provider's available fields do not mean that
Gaten already reads them. The released
[2.25.3 contract](https://github.com/ankidroid/Anki-Android/blob/v2.25.3/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt)
is the evidence for richer card metadata; the
[release changelog](https://ankidroid.org/docs/changelog.html) establishes the release.
An [API expansion discussed in issue #20773](https://github.com/ankidroid/Anki-Android/issues/20773)
mentions review-log access in a development-branch patch; that discussion is not a released contract.
Use supported provider access, not AnkiDroid's private database.

That issue also identifies a scheduling constraint: raw review-card due values are collection-relative
day numbers, not Unix timestamps. Prefer supported due-date searches for basic schedule buckets;
do not promise a precise calendar from raw values without the necessary collection/day metadata.

The official [answered-card search documentation](https://docs.ankiweb.net/searching.html#answered)
supports exact-day `prop:rated` searches. There is no general modern 31-day ceiling on `rated:`
searches; Gaten's current seven-day window is its own choice. Extending the range still needs
reasonable query cost and graceful handling of unsupported searches.

### WaniKani data inventory

| Data / metric | Availability | Meaning and constraints |
|---|---|---|
| Level, due items, lessons and next-day schedule | Now | Account-wide current snapshot. |
| SRS stage totals | Now | Includes not-started assignments; this is not the whole locked curriculum. |
| Item-type and level breakdowns | Read more | Additional assignment/subject reads. |
| Cumulative reading / meaning accuracy and difficult items | Read more | Additional review-statistics reads; no date-bucketed answer history. |
| Level durations, pace and reset eras | Read more | Additional progression/reset reads; older history can be incomplete. |
| Lesson-start, pass and burn milestones | Read more | Assignment timestamps; resets/resurrections need explicit handling. |
| Current-level blockers and longer due-date schedule | Read more | Derived from assignments, subjects and prerequisites. |
| Future milestone dates | Read more / estimate | Derived assumptions, not reported promises. |
| Historical review heatmap, answer-time totals and daily accuracy | Blocked for full history | Not recoverable from the current snapshot API. |
| Gaten review activity, first-try results and active time | Track going forward | Would cover Gaten sessions only. |
| JLPT, Jōyō, frequency and text coverage | External data | Requires named mappings/corpora. |

The [official API reference](https://docs.api.wanikani.com/20170710/) documents these resource
families and the deprecated review-history read. The
[staff announcement](https://community.wanikani.com/t/api-changes-get-all-reviews/61617?page=14)
confirms the history limitation. Additional API reads are feasible, but are not present in
the current count-only analytics path.

Repository evidence: [WaniKaniStats.kt](../../app/src/main/java/com/gaten/app/wanikani/WaniKaniStats.kt)
and [GatenWaniKani.ts](../../src/rn/native/GatenWaniKani.ts).
[WaniKaniLearningPreferences.kt](../../app/src/main/java/com/gaten/app/wanikani/WaniKaniLearningPreferences.kt)
also stores successful lesson starts for the current local day on this device. That counter is
neither account-wide lesson history nor a durable daily series.

## 3. Proposed content for each view

### All — “How is my studying going overall?”

| Section | What we would show | Recommendation |
|---|---|---|
| Workload at a glance | Anki remaining new/learning/review cards alongside WaniKani due items and available lessons; source freshness. | Core. Put both sources together, retaining their units. |
| Activity | Shared date axis or calendar with separate source contributions, active days and consistency. | Useful, but only over known coverage. Anki can initially show distinct-card activity; WaniKani review activity needs tracking. |
| Progress | Anki collection/maturity summary beside WaniKani level and SRS summary. | Core. Give each source an appropriate progress measure. |
| Recall and focus | Anki repeated-lapse/tagged-leech summary beside WaniKani reading/meaning accuracy and difficult-item summary. | Core after additional reads. Keep definitions and scopes visible. |
| Workload ahead | Near-term source schedules on the same date axis, with overdue work separately stated. | Add once Anki due-date buckets are supported. |
| Shared language coverage | Unique mapped words/kanji studied in either source, source overlap, and coverage against a chosen list. | Later. Requires reliable mapping and deduplication. |

An “All” view does not need a universal learning score to be useful. For the first version,
source contributions can sit together without converting cards and WaniKani subjects into
one invented unit of knowledge.

Combined active days can eventually use the union of days with recorded activity, counting a
day once even if both sources were used. Combined active time is useful only if both time
measurements use compatible definitions and coverage. Neither should silently treat missing
source history as inactivity.

### Anki — “How are my decks and recall doing?”

| Section | Information to present | Initial position |
|---|---|---|
| Today / workload | Remaining new, learning and review cards; distinct cards studied; overdue cards separately; daily-limit context. | Core, partly Now and partly Read more. |
| Activity and consistency | Distinct cards studied by day; active days in the displayed period; calendar/heatmap; longest/current observed streak when coverage is complete. | Start with current seven days; consider 30/90 days through additional reads. |
| Collection health | Total cards; new/learning/review composition; young/mature split; suspended/buried overlays; mature share. | Core after additional count reads. |
| Difficult cards | Tagged leeches; high-lapse cards; repetitions versus lapses; a short list that explains why each card appears. | Core candidate with compatible metadata reads. Avoid labelling every lapse a leech. |
| Upcoming workload | Overdue count, scheduled due cards over 7/30 days, busiest scheduled day. | Next priority. A scheduled queue is not a simulation of all future repeats. |
| Scheduler details | Interval distribution; FSRS stability/difficulty distributions and desired retention where available. | Optional detail; useful to experienced Anki users. |
| Review-event analysis | Actual answer counts; Again/Hard/Good/Easy distribution; true retention; recorded answer time; answer speed; hourly patterns; learning/review/relearning event split. | Defer full-history claims. Could be offered as explicitly Gaten-only tracking if wanted. |

The deck selector should consistently scope the relevant counts, activity and difficult-card
lists. A collection-wide figure should explicitly say “All decks.” Recommend Japanese decks
for cross-source language comparisons; let the Anki view still inspect any selected deck.

The optional recall section must distinguish observed true retention from FSRS desired retention
and model state. The former needs eligible historical answer events, which current card metadata
cannot reconstruct. Follow Anki's own inclusion rules if a supported history source becomes
available; do not estimate it from unique-card searches.

### WaniKani — “How is my curriculum progress and recall developing?”

| Section | Information to present | Initial position |
|---|---|---|
| Current workload | Reviews due, lessons available, next review, next-day schedule. | Core, Now. |
| Progress and SRS | Current level; item counts by type; not started versus active versus advanced stages; current stage distribution; drill-down item lists. | Core. More detailed breakdowns need reads. |
| Current-level progress | Progress toward the next level, relevant remaining kanji and prerequisite blockers, with links to the items. | Core candidate after more reads. |
| Level history and pace | Time on current level; completed-level durations; typical and recent pace; reset markers; incomplete-history notice. | Core candidate after more reads. Elapsed days are not study time. |
| Recall | Cumulative reading, meaning and overall answer accuracy; breakdown by item type and level; answer-count sample sizes. | Core after more reads. Keep cumulative figures outside date filters. |
| Difficult items | Low-accuracy items with enough attempts; meaning-versus-reading weakness; error counts and current stage; item details. | Core after more reads. Rank transparently. |
| Learning milestones | Lessons started, first-pass/burn milestones where timestamps remain available; current counts versus attained milestones. | Useful secondary content; do not call this review activity. |
| Curriculum coverage | Item grid plus optional JLPT/Jōyō/frequency coverage with a selected progress criterion. | Curriculum grid first; external lists later. |
| Projection | Estimated next-level or target-level date using recent pace and explicit assumptions. | Later. Show a range/scenario and handle sparse history. |
| Activity and time trends | Gaten-completed reviews, first-try success, active time and recorded-day streaks. | Only if we choose to start Gaten-only tracking. |

## 4. Definitions that make the figures trustworthy

These are proposed product rules to agree before design:

1. **Name the unit.** Anki cards, notes, answer events and unique words differ. WaniKani subjects,
   meaning/reading answers and completed subject reviews also differ. “Studied 100” is too vague.
2. **Name the population.** Every section should make its source, deck/item scope, date scope and
   history coverage clear. Some figures are current state, some cumulative, and some recorded locally.
3. **Do not blend accuracy percentages.** Present the source measures separately. Within a source,
   aggregate underlying correct/total counts rather than averaging item percentages. Zero attempts
   means insufficient data, not 0% accuracy.
4. **Define progress without claiming mastery.** Prefer explicit criteria such as “interval ≥21 days,”
   “currently Guru+,” or “currently Burned.” A card's scheduling state is evidence of study progress,
   not proof that the user knows the word in every context.
5. **Keep history and state distinct.** An item previously passed can have a lower current stage.
   Existing timestamps do not constitute a complete history of repeated promotions or resets.
6. **Resolve day boundaries explicitly.** Anki's study day can start after midnight; WaniKani's
   existing local lesson counter uses calendar days. Preserve source conventions initially. A combined
   calendar needs a shared rule and compatible timestamps; do not silently shift aggregate buckets.
7. **Treat unknown as unknown.** Unsupported reads, disconnected sources, missed observation days,
   stale data and a genuine zero need distinct states. An All view with one source connected still works.
8. **Call estimates estimates.** Future due-date buckets describe current scheduling. Failures, new
   learning and repeated short-interval reviews can change actual workload. A date estimate should
   explain its pace assumptions.
9. **Keep coverage claims narrow.** Frequency/text coverage measures representation against a named
   corpus or list, not reading comprehension or a guaranteed JLPT pass.
10. **Make focus lists explainable.** Proposed starting rule: require a meaningful sample before
    ranking low accuracy, show the attempt/error counts, and retain the source's own leech tag where
    available. Set the actual threshold during refinement rather than inventing a universal score.

For Anki's timing and retention semantics, use its
[statistics documentation](https://docs.ankiweb.net/stats.html): answer time is recorded time,
which can be capped; true retention uses a defined event population rather than all repeated
learning answers. Deleted-card history may remain in collection statistics but lose deck attribution.
Our current card-search counts cover existing cards, so they should not be presented as identical
to Anki's historical collection statistics.

## 5. Coverage and history choices that need their own decision

### A. Is history account-wide, or only study done through Gaten?

Recommend account-wide current state wherever supported. If we add local event tracking, label
the resulting charts **“Studied in Gaten”**, with the start date and device coverage. Connecting an
account does not grant retroactive event history. Existing seven-day Anki searches can backfill
distinct-card activity without claiming answer-level history.

Daily snapshots can preserve observed stage totals or cumulative-counter changes. They cannot
reliably place activity on exact days when Gaten was not collecting. A change observed after a
week away belongs to an interval, not necessarily the day the app reopened. Avoid promising a
complete background tracker as part of a dashboard.

### B. What does shared Japanese coverage mean?

Possible later views: words/kanji present in either source, overlapping items, and progress against
a chosen JLPT/Jōyō/frequency list. First define:

- Which Anki decks and expression/reading fields qualify, including kana-only words and ambiguous spellings.
- Whether coverage means present, introduced, or meeting a source-specific progress threshold.
- How one word on multiple cards or in both services is counted once.
- Which list/corpus, version and license supplies the denominator, including items absent from WaniKani.

JLPT does not publish the modern test's exhaustive vocabulary/kanji specifications. Any JLPT
overlay should identify its third-party list and avoid “N2-ready” claims. See the
[official JLPT FAQ](https://www.jlpt.jp/e/faq/) on the discontinued Test Content Specifications.
Gaten's existing dictionary bridge exposes a common-word flag; that is not a numerical corpus
frequency rank or a validated JLPT mapping.

### C. How much detail belongs on the page?

Recommend a short overview per view, then optional detailed sections or item lists. Offer only
time ranges supported by the selected metric. A “30 days” filter must not relabel cumulative
WaniKani accuracy or current inventory as 30-day data. Individual sections can clearly say
“Current,” “Cumulative,” “Last 30 days,” or “Recorded in Gaten since …”.

## 6. Proposed priorities to refine

| Priority | Content | Why |
|---|---|---|
| First | Existing workload and source status; Anki activity and inventory; WaniKani stage/progress summary. | Immediate utility with data we already read. |
| Next | WaniKani reading/meaning accuracy, difficult items and level history; Anki longer distinct-card activity, state counts, leeches/lapses and basic schedule. | Richer insight through supported reads, without depending on missing event history. |
| Optional advanced detail | Anki FSRS/interval distributions; WaniKani level projections; milestone histories and item grids. | Valuable for interested users, less essential for the overview. |
| Separate scope decision | Gaten-only review/time/retention tracking; external coverage lists; deduplicated cross-source vocabulary. | Needs a product choice about coverage or meaning, beyond adding charts. |

Anki compatibility and search cost may move individual “Next” metrics later. This ordering is
for discussion, not a commitment to implement every listed chart.

## 7. Decisions for our next refinement

| Question | Recommended starting point | Alternative |
|---|---|---|
| Which questions deserve the top of Analytics? | Consistency, workload and progress, followed by recall and difficult items. | Make detailed recall analysis or language coverage the central purpose. |
| Which Anki content belongs in All? | Selected Japanese decks, explicitly named. | Entire collection, including non-Japanese material. |
| Is Gaten-only history useful enough to track? | Keep it optional and explicitly separate from account-wide data. | Exclude it and show only supported source data. |
| Are external coverage lists essential in the first revision? | Later, after workload/progress/recall are settled. | Promote one named list, such as JLPT kanji, into the core scope. |
| Do we keep three views? | All · Anki · WaniKani if All provides a meaningful summary. | Two source views with a compact shared overview above them. |

## 8. Follow-up constraints for a future implementation

- Agree on the content and definitions first, then map them to screen sections and data reads.
- Scope Anki filters consistently: current activity is collection-wide while inventory is deck-scoped.
- Audit collection totals: current code sums top-level deck rows, including filtered decks. Avoid
  counting the same underlying work via a filtered view and its home deck, or via parent and child.
- Cache/paginate additional source reads and retain source freshness. Read only metadata needed
  for the chosen metrics; keep credentials on the native side and respect source access limits.
- Do not change scheduling, grade cards, or start lessons to collect analytics.
- If local history is selected, decide its account identity, retention and device coverage before
  presenting it as durable history. Count only confirmed results and define undo/retry handling.
- No app launch, browser verification, smoke tests, builds or tests were run for this document-only
  research task. No application code was changed.
