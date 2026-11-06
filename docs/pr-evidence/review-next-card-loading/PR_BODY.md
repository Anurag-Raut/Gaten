## Changes

TickTick: [review loading after a card and before the next](https://ticktick.com/webapp/#/p/inbox125722853/tasks/6aedbc0a2b7a1f64be9d9178).

- **Cause:** the WaniKani review's `"Saving to WaniKani…"` indicator lived only in `ActionFooter`.
  That footer is intentionally left beneath the keyboard so the IME's Done key continues the review;
  with the keyboard up, the indicator existed but was covered.
- **Fix:** the same `BusyRow` and message now render in a fixed slot between the progress line and
  the scrolling card, inside `KeyboardAvoidingView` and outside the `ScrollView`. The row is gated
  on the real `session.phase === "submitting"` and is replaced by the next prompt or the existing
  failure state. The duplicate row is removed from `ActionFooter`.
- No store, provider, retry, timing, or optimistic-advance change. `Check answer` and `Continue`
  keep their existing `busy`/`submitting` guards. Expanding the answer details and scrolling the
  card does not move the row out of view.
- **Anki is unchanged.** Its review has no IME, its pinned footer is visible, and `busy` is held
  from `Anki.grade` through `nextCard`; the existing `"Saving to AnkiDroid…"` row already covers
  the same wait.

## Visual evidence

- **UI changed:** Yes.
- **Screenshots:** Raw branch URLs on `fix/review-next-card-loading`. The before and after builds are
  the same fixture, synthetic session and recipe; the only difference is `WaniKaniReview.tsx`.

  Baseline (`HEAD` UI): the save is pending with the keyboard up and no indicator is visible — the
  footer row is under the IME.

  ![Baseline: no visible pending indicator](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/before-01-pending-next.png)

  Fixed: `Saving to WaniKani…` above the keyboard.

  ![Fixed: pending row above the keyboard](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-01-pending-next.png)

  The accepted result advances to the next real prompt:

  ![Next prompt after the accepted save](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-02-next-prompt.png)

  Expanded answer details and a scrolled card before continuing; the row stays pinned above the
  keyboard:

  ![Details expanded and scrolled](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-03-details-scrolled.png)

  ![Pending over scrolled details](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-04-pending-failure.png)

  The existing failure state after the synthetic offline result:

  ![Submit failed](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-05-submit-failed.png)

- **Interaction, navigation or motion:** the real installed app on `emulator-5554`.
  [Accepted run: Done → pending → next prompt](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-accepted-next.mp4)
  (5.9 s) and
  [failed run: scrolled details → Done → pending → `submitFailed`](https://raw.githubusercontent.com/Anurag-Raut/Gaten/fix/review-next-card-loading/docs/pr-evidence/review-next-card-loading/after-pending-failure.mp4)
  (3.5 s). The fixture bar names the synthetic bridge and 1500 ms fixture delay; it is above the card
  and never covers the pending row.
- **Source:** base `d4977a6` (`d4977a6a35f7dd15da77d846debdff7e39d959ba`), branch
  `fix/review-next-card-loading`; the production code captured here was committed unchanged.
  Device: AVD **Gaten Lookup Review**, `emulator-5554`,
  Android 14 `sdk_gphone64_x86_64`, 1080×2340, dark theme, real Gboard. Fixture APK sha256
  `27fcc3db313d99def0b752539e01fe645001972fe925ea080c484675bb39811a`; baseline fixture APK
  `7af61ba193688147f3d88409a7edbcc37581ce2d84edeed1671646e1fb853d37`; restored production APK
  `7c40414b1467738b69950815d8e07a967ee00d13ae6747a1524255c9331a6261`. The captures exercise the
  pending row and the existing submit states, not a live WaniKani request. No account or study
  history was changed; the fixture ids and meanings are never sent.
- **Assets:** `docs/pr-evidence/review-next-card-loading/` — stills, both recordings, this body, a
  README with the full provenance, and reproducible fixture source plus the temporary bridge hook
  patch. The emulator was left with the ordinary production build and the session was closed.

## Verification

- **Steps and result:** Typecheck and the Android debug x86_64 build were run on the working tree
  later committed unchanged. On the device, the pending row appeared above the open keyboard and stayed pinned while the
  details were expanded and the card scrolled; the accepted run reached the next prompt and the
  failed run reached the existing `submitFailed` state. The fixture was then removed from the
  source, the production APK was rebuilt, installed, and opened to Home (`WaniKani · Not connected`,
  `Anki · Permission needed`).
- **Checks:**
  - `./node_modules/.bin/tsc --noEmit -p tsconfig.json` — passed; fixture typecheck with
    `docs/.../fixture/tsconfig.json` — passed.
  - `git diff --check` — passed.
  - `./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` (under
    `/tmp/gaten-build.lock`) — `BUILD SUCCESSFUL`; production Hermes bundle sha256
    `a5e848c8fa9145e2efdb2855ce4397346f4a284343d924c76a67cb5b8fd7ae2a`.
  - `node --test --test-reporter=tap tests/*.test.cjs` — 157 tests, 113 pass, 44 fail. All 44 are in
    `tests/wanikani-hub.test.cjs`, whose in-VM require map cannot load the store's
    `./wanikani-romaji` import; the same baseline is on `origin/dev`. Every other suite passes. No
    test was added for this UI-only relocation.
  - Not run: lint, release build, a live WaniKani submission, retry, lessons, Anki, landscape.

## Problems

- The evidence is synthetic at the bridge only: the 1500 ms delay and the accepted/offline outcomes
  are fixture constants, while the store's `submitting`, `advanceWaniKani` and `submitFailed`
  transitions and the screen are production code. The capture does not prove server acceptance or a
  real account's schedule change.
- The baseline and fixed fixture APKs are build artifacts, not committed; their sha256 values and
  the exact fixture build recipe are above. The reproducible fixture source and bridge hook patch are
  in `docs/pr-evidence/review-next-card-loading/fixture/`.
- `tests/wanikani-hub.test.cjs` is red on the base for the unrelated harness gap and is reported
  rather than repaired here.
- This changes only the WaniKani review surface. Anki's existing footer indication is retained by
  design.
