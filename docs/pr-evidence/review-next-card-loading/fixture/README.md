# Fixture: production review store, synthetic submit outcome

This fixture photographs the production `WaniKaniReview` and the production `reviewStore` while a
review save is pending. Only the WaniKani submission bridge is synthetic. The store's own
`submitWaniKani` sets `submitting`, the accepted result advances through `advanceWaniKani` to the next
prompt, and the failed result lands on the existing `submitFailed` state. No store member or phase is
replaced by the fixture.

## Why a bridge hook

The first attempt patched the bridge module's exports at runtime. The store's call reached Kotlin
instead, so the fixture was not deterministically controlling the outcome. The capture build
therefore uses one temporary branch in the JS bridge wrapper (`bridge-hook.patch`), installed before
the store is loaded. The hook is absent from the committed source and from the restored production
APK.

Apply it only for the fixture build:

```bash
patch -p1 < docs/pr-evidence/review-next-card-loading/fixture/bridge-hook.patch
ENTRY_FILE=docs/pr-evidence/review-next-card-loading/fixture/index.js \
  ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64
git checkout -- src/rn/native/GatenWaniKani.ts
```

The entry installs the fixture hook before requiring the component, so the store closes over the
patched bridge from its first evaluation.

## Synthetic data

- Two fixture subjects (`仮`, `語`) with ids `9001`/`9002` and meanings `fixture one`/`fixture two`.
  They are fixture labels and are never sent anywhere.
- The session is installed in the store's retained-review slot; `startReviews()` publishes it and
  reads no queue.
- The bridge answers after a fixture-only 1500 ms delay. This is not app timing. The fixture bar on
  screen states the pacing, the active bridge target, and the next scheduled call.
- The schedule is call 1 accepted, call 2 failed (`OFFLINE`), so one run covers both transitions.
  "Reset: fail first" starts a shorter failure-only run.

## Scenes

| Scene | Action | Expected |
|---|---|---|
| accepted | type `fixture one`, tap Gboard Done (check), tap Done again (continue) | `Saving to WaniKani…` above the keyboard, then `2 of 2` with `語` |
| failed | on `語`, type `fixture two`, Done, open `Show answer`, scroll, Done | spinner stays pinned above the keyboard, then `submitFailed` / `Send again` with `no connection` |
| baseline | repeat accepted on a build with `WaniKaniReview.tsx` at `HEAD` | no indicator is visible; the footer copy stays under the keyboard |

The review screen is mounted directly with the production component and store. The fixture bar is
outside the card and does not cover the pending row.
