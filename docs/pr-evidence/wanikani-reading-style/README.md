# Primary reading lettering — matches the hero character

Captured for the primary-reading style fix (`fix/heading-kanji-style`): the "Primary reading" value in
the WaniKani review's Reading detail tab now renders with the card's big hero character's lettering
(medium weight) instead of regular.

## What changed

One style property, verifiable by diff on `src/rn/ui/review/WaniKaniReview.tsx`:

- `detailJapanese` (the "Primary reading" value in the review's Reading detail tab) gains
  `fontWeight: "500"`. Before it set no weight and rendered at regular (400); the hero character
  (`promptSingle`/`promptWord`) was already medium (500), same face (IBM Plex Sans JP). Size
  (27/34) is unchanged, and nothing else changes.
- The lettering is now expressed once as `HERO_LETTERING = {fontWeight: "500", fontFamily: APP_FONT}`,
  and `promptSingle`, `promptWord` and `detailJapanese` all derive from it, so the hero and the
  reading can no longer drift apart.

## Scope

`WaniKaniLesson.tsx` renders readings with a third, different style (`answerLine`, weight 600) and
was deliberately **not** restyled: the owner's screenshot covers the review's Reading tab only.
`wanikani-card-ui.tsx` renders no Japanese reading text.

## Captures

| File | Screen |
|---|---|
| `before-reading-tab.png` | Review answer details, **Reading** tab, base style — the reading `せんたん` at regular (400) weight |
| `after-reading-tab.png` | The same panel with the fix — `せんたん` at medium (500) weight, visibly matching the hero 先端 above it |

Both shots: same subject (Vocabulary 先端, Level 27), same Reading detail panel, same device, taken
from one Metro dev server in this worktree, before/after by reverting only `detailJapanese`'s
`...HERO_LETTERING` spread (Fast Refresh equivalent; a clean restart of Metro per pass, see below).
The weight difference is visible: the after capture's reading ink is heavier than the before
capture's, while every other text on the panel is unchanged.

**Bundle assertion:** before each capture the served bundle was fetched
(`index.bundle?platform=android&dev=true&minify=false`) and inspected: the fix pass showed
`...HERO_LETTERING` inside `detailJapanese`, the base pass showed it absent (plain
`fontFamily: APP_FONT`). This mattered — this host has no watchman, and Metro otherwise served a
stale bundle until restarted.

**Nothing was submitted:** the prompt was a meaning prompt; a deliberately wrong meaning
(`zzzzz wrong answer`) was typed and checked, which only sets the `Incorrect` feedback that makes
`Show answer` appear. No answer was graded, no review advanced — the queue read `3 reviews due`
before and after the capture, and `Items reviewed` is unchanged.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `a2f05b3` — `fix(wanikani): style the primary reading like the hero character`, on top of `origin/dev` `b38ff19` |
| App | `com.gaten` 0.1.0 debug build (`./gradlew :app:assembleDebug`, BUILD SUCCESSFUL); arm64 APK sha256 `395b2663f6e9e77da112407abcc6f550ca553af07290078206957ceb4328f7e0` |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080x2340, Android 14 |
| Account state | 3 reviews due before and after; nothing answered, nothing submitted |
