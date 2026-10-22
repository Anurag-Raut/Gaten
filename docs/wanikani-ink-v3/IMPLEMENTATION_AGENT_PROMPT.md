# Gaten / Ink — WaniKani Hub (Editorial V3)

## Agent implementation prompt (paste this into your coding agent)

You are implementing the **WaniKani Hub** in the existing Gaten Android app. First inspect existing Gaten Ink theme tokens, navigation, WaniKani review route, WaniKani lesson plan, native bridge, WaniKani API client and data models. DO NOT assume file paths. Do not overwrite existing review logic or replace real Android system chrome with demo HTML.

**Exact approved visual reference**: `index.html` in this package, default state `01 Both available`, plus reference screenshots in `screenshots/`. The style is the **third concept from the design exploration**: a borderless editorial two-section layout, not rounded card panels. Mobile screenshot is a visual guide; native layout uses dp/sp and actual Android insets. Gaten_Ink_Design_Language_v1 is the product's design authority; reuse existing tokens rather than duplicating hardcoded colors.

### Required changes
1. On WaniKani navigation open one hub page titled `Your study` with subtitle `Choose what to work on next.`. **Remove the previous inline `23 reviews due · 77 lessons available · Synced just now` status/summary line entirely.** Do not replace it with any other third line. Header uses back arrow and WaniKani title.
2. Show **exactly two stacked modules**: Reviews, New cards. No tabs, outer cards, floating cards, feature banners, separate Coming up section, or extra analytics rows. Each module is bounded by a fine top hairline only.
3. Module anatomy: icon + heading; below that 2-column data row; left: prominent queue number and supporting label, right: vertical hairline + next-review or batch timing and secondary hint; below: full-width neutral rounded action button when the queue is actionable.
4. Data shown: Reviews queue count from existing review source; next review timestamp / `Due now` only when supported by actual API; next-hour review count only when it can be calculated from real schedule. New cards: available lesson count; batch size based on WaniKani `lessons_batch_size` or app policy fallback 5; breakdown by radicals/kanji/vocab only when lesson subjects have been fetched and resolved. If unavailable, **omit hint rather than inventing numbers**.
5. Review CTA routes into the EXISTING WaniKani review implementation, preserving meaning/reading validation, progress persistence, review submission semantics, rate limits and error handling. Lessons CTA routes into the lesson teaching + quiz flow specified by the repo plan. Starting lesson assignments uses PUT /assignments/{id}/start only at the supported stage. Do not substitute POST /reviews for lesson starts. Preserve retries and guard against double submissions.
6. For a zero queue, retain its full module but replace the oversized numeral/CTA with an intentional compact completion design: calm outlined check-circle icon, `Reviews complete` or `Lessons complete`, single sentence `Nothing due at the moment.` or `You’ve learned every available card.`; optional genuinely known next time. Do not say `completed today` unless there is evidence of completion, as zero may mean nothing ever unlocked. Do not use confetti, mascots or reward animations. Keep the other module actionable if it has work.
7. Support all states in `STATE_MATRIX.md`: ready, independent completion, both complete, loading, cached refresh, disconnected, expired auth, offline with stale data, offline no cache, server error, lessons locked, partial data and paused sessions. **Unknown is never 0**. Maintain stale badges/wording if data is cached; do not imply live availability when offline. Distinguish zero items from unavailable items in state model.
8. Use theme tokens. Light reference: bg #FDFDFD, fg #17171A, readable secondary #6C6C75, line #E9E9ED, CTA #18181B / #FFFFFF. Dark: bg #101114, fg #F5F5F6, readable secondary #B5B5BD, line #303136, CTA #F5F5F7 / #17171A. Accent is user's selected Appearance accent, not hardcoded violet (screenshots show indigo default); icons only, do not use accent for CTA backgrounds. Use current native Gaten typography and icons first. Avoid gradient, glass and glow.
9. Layout targets: body horizontal padding 22–24dp; compact back header; heading ~35sp, weight ~700–750; subtitle 13–14sp; module title ~20–22sp; metric ~50–54sp; label 12–13sp; small metadata 11–12sp; action 50–52dp tall / 13dp radius; 1dp separators; space modules ~24dp. Adjust for larger font scales and small screens rather than fixing pixels. Touch targets >=48dp. Screen reader labels must specify counts, available actions, whether values are stale, and completion. Respect reduced motion.
10. Cache and async: Use independently modeled review and lesson states. Show skeleton shapes during initial loading instead of zeros. During refresh, retain previously confirmed values (mark refreshing subtly), avoid content jumps. Errors must allow retry. On successful review/lesson completion refresh counts on return; remain consistent if one endpoint succeeds and another fails. Race/stale-result guards; cancel/ignore old requests on auth changes/unmounts.

### Agent workflow
A. Read current repo design components and API capabilities. Report any mismatches between this specification and actual API fields BEFORE inventing fallbacks. B. Implement hub UI with existing components/token system and typed view-model/state. C. Wire actual review/lesson navigation. D. Add deterministic tests for state transitions, completion, partial failure and cached offline data. E. Capture and compare emulator screenshots for every reference state in light/dark at 100% and large font scale. F. Provide changed file list, tested API paths, and remaining risks.

### Acceptance
- Screenshot matches **borderless two-module** reference.
- No inline summary/synced row beneath subtitle.
- No extra section below modules.
- All 14 demo states have meaningful, accurate native equivalents.
- Accurate counts and next time; never fabricated.
- CTA always routes to real functionality or explicitly disabled/error handling.
- Completed state is pleasant, concise and non-patronizing.
- Appearance accent and theme changes automatically update screen.
- Back, accessibility, keyboard/navigation insets, offline and network retry tested.

### Caveat
`index.html` is a state-and-style demonstration with dummy counts; its buttons simulate state changes and are not connected to WaniKani. Native app must use actual data.
