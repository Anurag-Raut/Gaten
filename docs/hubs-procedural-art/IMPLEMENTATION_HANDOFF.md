# IMPLEMENTATION TASK — Gaten Ink: Anki and WaniKani Hub Completion States

**Priority:** Implement these two existing hubs faithfully, not a new dashboard. **This is a visual + state-behavior change, not permission to rewrite the scheduler, authentication, API client, or unrelated app screens.**

**NEW MANDATORY REQUIREMENT: PROCEDURAL ANKI DECK ART.** The user specifically approved the tasteful soft abstract tile **to the LEFT of each Anki deck title** but requires artwork to be created dynamically and deterministically, using a curated set of repeating pastel palettes. Do NOT ship stock thumbnails, identical static logos, or per-render random artwork. Read `docs/hubs-procedural-art/PROCEDURAL_DECK_ART.md` and inspect the existing project implementation before making changes. **This requirement overrides earlier guidance saying not to add decorative deck images**: these small subdued illustrations are expressly approved.

**Read this file fully and inspect the app code before making changes.** Follow the actual repository structure, APIs, and existing theme system. File paths and component names below are conceptual unless verified in the repository.

## 0. What the user approved, precisely

The user approved **Variation A / the first phone screenshot** from BOTH provided comparison boards:

- `references/ANKI-A-normal-selected.png`: existing `Anki → Choose a deck` layout, clear top title + short subtitle + `Available decks` + vertical rows, compact right-aligned due numbers. **Preserve this layout**.
- `references/WANIKANI-A-normal-selected.png`: `WaniKani → Your study` with **two borderless, vertically stacked sections**. `Reviews` and `New cards`, large due count on the left, timing/batch data on the right separated by a small vertical hairline, a neutral black Start button for each, and a thin horizontal separator between sections.
- The other full-board panels in `references/` are **state inspiration**, not the new default baseline: completed deck, all clear, reviews clear, lessons clear, both clear, loading.
- `references/ANKI-existing-screen.png` and `references/WANIKANI-existing-screen.png` are photographs/screenshots of the actual prior app. Use to preserve navigation, scale, and original alignment.
- `references/SESSION-completion-selected.png` is the **separate session completion screen** the user already liked. Do not automatically replace it; this task is primarily **hub-level persistence** after leaving a session.

**Do not**:
- Introduce tabs in WaniKani, add a Coming Up section, add colorful dashboards, gradients/glows/confetti, or enclose the WaniKani sections in rounded cards.
- Replace Anki's deck picker with a new screen, row sorting that hides finished decks, photographic/remote deck images, new brand mark designs, or a primary start button on the Anki picker. **The curated procedural thumbnails at the left of each deck are explicitly required.**
- Render `Done for now` as a disabled button. Render a concise **status line** with checkmark and next time when known.
- Assume `0 available` means `completed today`; it might be zero at initial load. See distinctions below.
- Submit live reviews/lessons while only testing the UI. Use mock fixtures, read-only smoke tests, and existing test doubles.

## 1. First, inspect repository and produce a short map

Before coding, discover:
1. Existing React Native/JS routes or screens for WaniKani home, WaniKani reviews, lessons, Anki deck selection, session completion, Android back navigation. The old `wkHome`, `wkDone`, `WaniKaniLesson`, and `WaniKaniPanel` names are historical guidance only; verify actual names.
2. Existing Kotlin/Android native bridges and AnkiDroid permission APIs; exact deck query and count semantics (`new`, `learning`, `review`) and whether decks can be listed while offline.
3. WaniKani summary, assignments, subject collection and existing lesson-start/review-submit methods. Confirm source of lessons and next review time. WaniKani lesson scheduling and review writes must remain unchanged.
4. Theme tokens, accent selection (Indigo / Blue / Violet / Teal / Green / Rose / Graphite), font scaling, Android status/navigation WindowInsets, loading/refresh scaffolding.
5. Existing session completion event/snapshot, return route, review/deck progress counts and persistence. Distinguish UX celebration triggered by a completed session from generic zero due.
6. Existing tests, fixtures and screenshot workflow; note any unavailable data or unsupported capabilities instead of inventing them.

Only then modify the smallest responsible screens and shared UI components. In your PR summary include a before/after route and data-flow map.

## 2. Canonical design tokens and geometry

Read `docs/Gaten_Ink_Design_Language_v1.md` first. In case of conflict: reuse production app theme tokens; apply the two **selected A** images for page hierarchy; use Ink design language for colors/typography/accessibility. All dimensions below are visual **dp/sp starting points** (not fixed screenshot px, not forced height):

| Part | Target | Notes |
|---|---|---|
| Page canvas | light `#FDFDFD`, dark `#101114` | Native background, no gradient |
| Primary text | light `#17171A`, dark `#F5F5F6` | Main headings and numbers |
| Muted text | light **accessible small** `#6C6C75`, dark `#909099` | `#83838C` only noncritical large visual copy |
| Dividers | light `#E9E9ED`, dark `#303136` | 1dp lines; no elevation |
| Soft surface | light `#F6F6F8`, dark `#1B1C21` | Optional neutral icon holder, status acknowledgment |
| Accent | theme-controlled; default light `#7564D9`, dark `#B0A0FF` | **Never hardcode violet into components** |
| Primary CTA | light `#18181B` white text; dark `#F5F5F7` dark text | Flat filled, 52dp min height, radius ~13dp |
| Insets | ~22dp horizontal, ~16–20dp if very narrow | Same leading edge across headers/rows/buttons |
| Page headline | ~35sp, weight 700–750, tight tracking | Scale responsibly at 130%/160% system font |
| Section header | ~17sp, weight 700 | WaniKani Reviews / New cards |
| Row title | ~15sp, weight ~680 | Anki deck name |
| Due count | ~25sp Anki; ~46–52sp WaniKani | Numeric tabular, accessible label |
| Primary button | 52dp min height | ≥48dp effective target; focus visible |
| Motion | 150–220ms, ease-out | No looping/sparkle/forced celebration; obey reduce motion |

Use **app-selected accent** in small icons, due numbers, and completed check marks; neutral action buttons. Brand pink/blue only for actual brand marks where currently used. Do not introduce green success as a default colored pill; accent-colored checks are preferred for cross-provider visual consistency. Do not use a fixed-height top-level phone shell in native.

## 3. Anki Hub — stay faithful to `Choose a deck`

**Header**: native back arrow + `Anki` / title `Choose a deck`. Under title: total *actionable* due cards from the selected deck scope, e.g. `5 cards ready to study` with the number in accent. Don't silently sum cards from unselected decks if underlying Anki API listing is scoped; compute correctly from actual provider response. `Available decks` label then simple vertical deck rows, with subtle separators; keep existing ordering and navigation.

**Due deck row**: left-side 44dp clipped procedural SVG tile (generated by stable deck ID, theme-aware palette, see `docs/hubs-procedural-art/PROCEDURAL_DECK_ART.md`), name, truthful detail (e.g. `1 new · 0 learning · 4 reviews`), right accent numeric due count + `due`, chevron; entire row tappable and opens the actual deck review route immediately. No extra per-row CTA.

**Clear deck row**: same unchanged procedural SVG artwork on left, same location, same row height/alignment; `Clear for now` as muted subline; replace numeric 0 emphasis with a small accent check icon; keep navigable if existing behavior supports browsing or reopening a deck. If no supported action, row may be informational with appropriate semantics, but never open an empty study session.

**One deck complete**: no huge banner at top. If user just finished that deck, optional compact 1-time row/banner acknowledgment directly above `Available decks` e.g. `Deck complete · Default`. On subsequent visits, only the persistent quiet completed row. Other decks with due cards must still be selectable.

**All decks clear**: compact success acknowledgment under page header: accent check icon, `You're all caught up`, `No cards are due in any deck right now.` Keep the deck list visible with each row in `Clear for now` state. Show this acknowledgment only after a successful authoritative refresh confirms zero due; cached zero counts during refresh or after a failed refresh are not confirmation. Do NOT show `Completed today` unless a tracked completion timestamp supports that assertion. If next due data is available and accurate, a small `Next due …` line is optional; don't request expensive extra data merely to fill it.

**No decks**: different from all clear; say `No decks found` and show a helpful AnkiDroid action. An explicitly empty study-deck selection is a separate state and should direct the user to choose decks in Settings. AnkiDroid unavailable/permission denied: short actionable message instead of zeros.

**Anki notes**: Respect hierarchy/parent-child deck counts and real AnkiDroid semantics. Treat cards available as actionable counts using the same established logic; don't count decks or subjects as cards. Account for collection locking, permission revocation, read timeouts. Do not alter scheduling or pull private deck content into logs.

## 4. WaniKani Hub — selected A, two open sections

**Header**: native back arrow + `WaniKani`. Large `Your study` heading + *one* short subtitle `Choose what to work on next.` **NO** `23 reviews due · 77 lessons available · Synced just now` row. **NO** Coming up standalone section and **NO** segmented tabs.

**Reviews when due >0**:
- Thin full-width hairline above the section.
- Title row: ~22dp small accent outline `history` icon, label `Reviews`.
- Body two side-by-side content columns *within the section*: left large due count plus `reviews due`; right `Next review`, `Due now`; optionally next time-window info **only when reliable and useful**. Short vertical divider between columns.
- Neutral high-contrast `Start reviews →` button, full content width, opens existing review route.

**New cards when lessons >0**:
- Thin hairline above section, `New cards` with small outline book/card icon.
- Left: large lessons available; right: `Next batch`, `5 cards` (only if actual batch size/remaining count supports it; don't assert 5 by default in production).
- Neutral `Start lessons →` button. Lesson batch composition only if already available from loaded subjects (avoid extra API calls just to decorate hub).

**Reviews when 0**: *Keep the same section order and overall presence*. Replace numeric + CTA with a compact accent check + `All caught up`; supporting text `No reviews due right now.` and `Next review in …` if fetched from the summary/assignments. Do not show fake disabled `Done for now` button. If the user actually completed a session and the refreshed count is zero, optionally show `23 reviews finished this session` for a short acknowledgment. If it was zero on first load, never imply the user just completed anything.

**Lessons when 0**: same treatment: check + `No new cards available right now.` Any mention of more unlocking after reviews is conditional on actual entitlement/unlock logic; don't promise they will unlock if unknown. A completed lesson session may show `5 new cards learned` if confirmation events are durable; count is the actual number started successfully via the API.

**Both 0**: both sections remain visible; a **single compact** heading-level acknowledgment such as `You're all caught up` directly below subtitle is optional if it does not crowd the sections. No third large card or separate completion page on merely entering the hub. No `Back to hub` CTA inside the hub.

**Locked/entitlement state**: do not confuse with zero completed lessons. Show `Lessons unavailable` and a grounded reason/action based on actual API response. Reviews remain usable if allowed. Also handle partial data (one queue loaded, one failed) independently.

## 5. Completion and session return rules

A successful session completion screen already exists / is separately designed (see `references/SESSION-completion-selected.png`). Keep it a separate destination when user actually completes the session/deck. Its `Back to hub` action should navigate to the appropriate parent and **trigger an up-to-date queue refresh**. Do not celebrate every time the user opens a hub with zero due items.

Differentiate:
- `sessionFinished`: a real just-completed user action; provider writes confirmed; exact count of *this session* known.
- `queueEmpty`: current provider read reports zero due.
- `allClear`: all relevant queues/decks empty **after successful authoritative refresh**.
- `unknown`: loading/network/auth/error/no data. **Never render unknown as zero**.
- `partial`: current session ended, but some due cards still remain — show progress, not `All caught up`.

After WaniKani review/lesson writes, reconcile the authoritative queue; when only a session completion event is available but refresh fails, show a neutral `Session finished · Updating queue…`, not permanent success.

Never use WaniKani lesson count (e.g. 77 available) as `77 lessons studied` without verified study events. Never derive `23 reviews completed` by subtracting the initial count unless retry/requeues/concurrency rules make it valid; use actual confirmed submissions.

## 6. State model / implementation pattern

Use a discriminated union (JS/TypeScript) or equivalent native sealed types. UI must receive explicit states, not 0-filled defaults. Recommended conceptual types:

```ts
type AsyncData<T> =
  | {status:'loading'}
  | {status:'ready'; data:T; fetchedAt:number}
  | {status:'refreshing'; data:T; fetchedAt:number}
  | {status:'stale'; data:T; fetchedAt:number; reason:'offline'|'request-failed'}
  | {status:'error'; reason:'auth'|'permission'|'rate-limit'|'offline'|'server'|'unknown'; retryAt?:number};

type StudyQueue = {due:number; nextDueAt?:string|null};
type LessonQueue = {available:number; nextBatchSize?:number|null; lockedReason?:string|null};
type CompletionReceipt = {
  provider:'anki'|'wanikani';
  scope:'deck'|'reviews'|'lessons';
  scopeId?:string;
  completedCount:number;
  confirmedAt:string;
};
```

`null`/missing next time should omit the row or say `Not available` as appropriate, never invent `Tomorrow morning` / `2 hours` from a fixture. Re-evaluate relative times when app resumes. All demo dates, quantities and string values in HTML/PNG are **fabricated examples for design only**.

## 7. Data integration and APIs

- **Anki**: use the already implemented AnkiDroid bridge/provider API and its existing deck list, due counts and scheduler. Avoid direct local database access/unsupported review writes. Respect permissions and source availability. Keep collection IDs stable while reordering/filtering. Do not expose PII in analytics/logging.
- **WaniKani**: reuse token/summary/assignments endpoints. `GET /summary` is appropriate for available reviews/lessons and next review windows where supplied. If fetching lesson assignments/subjects to derive a batch, do it only when necessary, cached/paginated, and under rate limits. Existing lesson teach/quiz and `PUT /assignments/{id}/start` behavior is authoritative; `POST /reviews` is reserved for reviews, never lesson starts. Existing per-user preferred batch size must be respected if supported; fallback values only after validating settings behavior. Never POST during screenshots/tests.
- **Rate limits, auth and partial refresh**: distinguish each provider and section's independent status. Protect users from double actions during writes; don't convert errors into `0`.
- **Caching**: show `last synced` only in an intentionally exposed stale/cache status; the user specifically removed an always-visible synced line. Background refresh should not cause large layout shifts.
- **No changes to global Review tab/Home/Analytics except updating navigational links where necessary.**

## 8. Accessibility and interaction

- ≥48dp touch targets (buttons, row, back arrow), keyboard navigation and Android TalkBack labels (e.g. `Japanese, 14 cards due, open deck`; `Reviews, all caught up, next review in two hours`).
- Completion check icon **with text**; never communicate by green/violet alone.
- 100%, 130%, 160% font scaling; Japanese deck names, long localized names, split columns on narrow widths (fallback to stacked detail rows under 320dp), screen scroll, safe area, keyboard, cutouts.
- Do not hardcode status bar or gesture bar: HTML only simulates these; use platform WindowInsets.
- All accent colors, dark/light/system modes, contrast ≥4.5:1 for small text. Buttons stay neutral. Prefer theme-derived icons (`currentColor`/native tint).
- Subtle 150–220ms check/number transition only after real state change, respect reduced motion. No permanent animation, confetti, celebratory sound, or mandatory delay.

## 9. Tests and QA that MUST be run

1. Unit tests for both providers: status unknown vs zero, partial failures, count aggregation by deck, per-deck completion, no decks, computed lesson batch, next review time absent.
2. State transitions: active→completed one deck, all decks, review→zero while lessons>0, lessons→zero while reviews>0, both→zero, refreshing→loaded, refreshing→stale/error, session receipt but refresh fails.
3. Real route smoke tests: back navigation, tapped deck launches actual session, lessons and reviews launch their preexisting flows, return to hub refreshes after a real completion; no duplicate writes.
4. Capture light/dark screenshots for ready, partial, all-clear, loading, offline and error states. Compare source A *layout* (not individual fixture text) at 100%/130% font scale and 320/360/390+dp widths.
5. Manually test all seven user accent options. Verified active action colors must have sufficient contrast against the background in both themes; don't assume all accents are equally legible as small text.
6. Accessibility screen reader and action semantics, gesture area, retry behavior with airplane mode, throttling/expired token/permission rejection.
7. No regressions to scheduler submission/lesson-start APIs or other Gaten screens. Existing approved session-completion screen stays unchanged unless explicitly needed for return-route state wiring.

## 10. Deliverables from the coding agent

- Working app code with correct provider data and state handling.
- Before/after screenshots for key states in light and dark, ideally stored in repo under a relevant design/test folder.
- A short changelog listing screens, file paths, behavior, data mapping and any known limitations.
- Test logs and exact commands run; enumerate tests not run and why.
- State matrix checkoff against `docs/hubs-procedural-art/STATE_MATRIX.md` and copy/edge details in a copy and edge-case checklist.
- Link commit/PR when ready. **Do not commit the ZIP, raw mockups, or generated marketing device frames into production asset bundles unless needed**; treat them as references.

**Acceptance criterion:** When launched on device, the default Anki picker reads like reference `ANKI-A-normal-selected.png`; default WaniKani hub reads like `WANIKANI-A-normal-selected.png`; completed states communicate a quiet, rewarding success without changing the page into an unrelated celebration screen. Every shown number is grounded in actual provider state, including failures/unknowns.

## 11. Procedural thumbnail implementation gate (added by user)

Do not mark the Anki work complete without following `docs/hubs-procedural-art/PROCEDURAL_DECK_ART.md`. Ship a deterministic local vector component with the six specified repeatable pastel palettes and five subtle motifs. Art is based on `accountScope + stableDeckId` where both are available; never base it on row index, due count, completion, theme accent, current time or `Math.random()`. The artwork remains exactly the same for a completed row as an available row. The selected app accent applies only to actions, numbers and checkmarks; thumbnails keep their assigned deck palette. Dark mode uses the paired palette tones. No server calls or generative AI are involved.

**Agent must report**: exact DeckThumbnail file path, stable key source, render technology, colors mapped, measured 44dp sizing, screenshot on short/long lists, and determinism test results.
