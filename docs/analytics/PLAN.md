# Analytics, inline search and the tab shell — plan and decisions

Scope: the Analytics tab (AnkiDroid + WaniKani on one page), the inline search on Home, and the
four-tab shell (`Home · Analytics · Review · Settings`). Bare React Native 0.86.3 with the New
Architecture — no Expo Router, no framework migration. Worktree `0q5q5xdm/ugly-dog`, branch
`revamp-app-holistically-2`.

## Summary

| # | Decision | Where it lives |
|---|---|---|
| 1 | **One Analytics page, no source switch, no blended score.** Anki and WaniKani cards sit on one scroll; every figure is labelled with the service it comes from. | `src/rn/ui/analytics/AnalyticsScreen.tsx` |
| 2 | **Read-only, always.** The dashboard reads counts, never cards; no grade, no export, no insert/update, no token crossing the bridge. Writing is not merely unused, it is absent from the code path. | `analytics/*`, `anki/AnkiStats.kt`, `wanikani/WaniKaniStats.kt`, `rn/GatenAnkiModule.kt`, `rn/GatenWaniKaniModule.kt` |
| 3 | **Anki numbers are AnkiDroid's own.** Distinct cards (`rated:`/`prop:rated`), AnkiDroid's study day (not local midnight), deck remaining with limits and subdecks, mature = interval ≥ 21 days, global remaining summed over top-level decks only. A refused search renders as *unavailable*, never as zero. | `anki/AnkiStats.kt`, `useAnkiStats.ts` |
| 4 | **WaniKani numbers are the account's own, count-only.** Eight reads (`/user`, `/summary`, six `assignments` count reads, `hidden=false`); SRS spread is six real buckets in WaniKani's own group names. No history, retention, streak or trend is invented — the API has none. | `wanikani/WaniKaniStats.kt`, `useWaniKaniStats.ts` |
| 5 | **One vertical scroller on Home.** The dashboard is the list's header while the query is empty; the results are its rows while it is not. No `FlatList` inside a `ScrollView`. | `src/rn/ui/HomeScreen.tsx` |
| 6 | **Search state is the store's, not the screen's.** Query, hits and reading position live in a module-level store, so a pushed word, a tab switch and a remount all come back to what the reader left. 250 ms debounce, `hitsQuery` coherence, no autofocus. | `src/rn/ui/search/searchStore.ts`, `HomeScreen.tsx` |
| 7 | **A settings page opens on the tab the reader is on.** `openSettingsPage()` pushes (with a same-page guard and a fresh instance) onto the *current* stack; only an outside `settings_page` intent selects the Settings tab. | `src/rn/ui/navigation.ts`, `AppShell.tsx` |
| 8 | **The bar is the accepted design.** Capsule bar, 68 dp content box, themed colours, the pager's own motion — the user's accepted horizontal-pager/capsule structure wins over the generic "never slide tabs" guidance. | `src/rn/ui/BottomBar.tsx`, `AppShell.tsx` |
| 9 | **Privacy copy names the new read.** The WaniKani settings page and the accessibility disclosure say that a connected token also reads the account's study progress for Analytics — level, due now, next 24 h, SRS spread — and that lessons and reviews are never touched. | `settings/WaniKaniSettingsScreen.tsx`, `settings/settings-components.tsx`, `README.md` |
| 10 | **Charts without a charting dependency.** Bars are plain animated views (width only, reduced-motion aware); the SRS ring is `react-native-svg` 15.16, already installed. Colour is never the only key — bars, arcs and legend all carry text. | `AnalyticsScreen.tsx` |

## Routes

| Tab | Stack (bottom → top) | Back from root | Back from a pushed page |
|---|---|---|---|
| Home (0) | `HomeTab` · `Word` · `Kanji` · `Radical` · `SettingsPage` | leaves the app (system) | pops one entry |
| Analytics (1) | `AnalyticsTab` · `SettingsPage` | Home | pops the page → dashboard |
| Review (2) | `ReviewTab` | Home | — (a session is state, not a stack entry) |
| Settings (3) | `SettingsTab` · `SettingsPage` | Home | pops the page → hub |

A pushed page or a live session gates the pager (`SwipeGate` via `setOptions`, plus the declarative
`screenOptions`), and the bottom bar hides at stack depth > 0 and during a session.

## Data contract (what the dashboard consumes)

- Anki — `useAnkiStats(active)` → `{status: loading|ready|unavailable, failure, refreshing,
  data: {selectedDeck, decks[], globalRemaining, totalCards, matureCards, studiedToday,
  daily: {daysAgo, cards|null}[], updatedAt}, refresh, selectDeck}`.
- WaniKani — `useWaniKaniStats(active)` → `{snapshot, status: idle|loading|ready|refreshing|
  needsToken|authRejected|failed, failure, stale, retryAfterSeconds, cacheFailure, message}`.
- Both refresh while the tab is focused, hold the last complete snapshot across a failed refresh,
  respect a 60 s / 5 min TTL, and refresh again when the app returns to the foreground.

## Privacy

- **Anki**: counts only, through the provider's `_id`-only fast path — no card is fetched or
  rendered by the dashboard, and the review flow never reads the queue for Analytics.
- **WaniKani**: the token is read inside Kotlin from the Keystore and passed only to
  `api.wanikani.com`; it never crosses the bridge, is never logged, and the cache is keyed by its
  SHA-256 digest. Only the eight count reads above are made; no lesson, no review, no write.
- **Device**: the analytics view performs no writes to either service, and this pass made none.

## Limits (stated, not hidden)

- No WaniKani history, retention, streak or trend: `GET /reviews` has been empty since 2023.
- No Anki history past ~7 days here; no heatmap, no time, no per-card ease/FSRS.
- An older AnkiDroid backend can refuse a search term; that metric renders as unavailable (`—`), not 0.
- The freshness line is computed at render; it does not tick by itself.
- The dashboard cannot be exercised end-to-end without a real AnkiDroid collection and, for the
  WaniKani half, a real token — neither is created here.

## Verification

See `/tmp/gaten-analytics-final-acceptance.md` (device walkthrough, build, tests, evidence paths).
