# Primary reading lettering — matches the hero character

Captured for the primary-reading style fix (`fix/heading-kanji-style`). **No picture was captured.**
The capture was blocked before it could start: the change is only visible inside the WaniKani review
detail panel, and the account's review queue was exhausted before a reading panel could be opened.

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

## Why no capture

`ReviewDetails` in `WaniKaniReview.tsx` is the only renderer of `detailJapanese`, and it is reachable
only from the WaniKani review flow's answer details (Reading tab). During the capture window the
connected account's hub went from `68 reviews due` to `0 · All caught up · Next in 1 hour 42 min`, so
no review prompt existed to open the panel against. This pull request is a draft for that reason; it
becomes ready for review once a queue exists and the before/after captures land here.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `a2f05b3` — `fix(wanikani): style the primary reading like the hero character`, on top of `origin/dev` `b38ff19` |
| App | `com.gaten` 0.1.0 debug build (`./gradlew :app:assembleDebug`, BUILD SUCCESSFUL); arm64 APK sha256 `395b2663f6e9e77da112407abcc6f550ca553af07290078206957ceb4328f7e0` |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080x2340, Android 14 (held, not driven — nothing to capture) |

## Checks actually run

- `npx tsc --noEmit -p tsconfig.json` — clean.
- `node --test tests/wanikani-romaji.test.cjs tests/wanikani-review-prompt-order.test.cjs` — 6/6 pass.
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
