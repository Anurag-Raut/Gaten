# Review hub visual QA

The four current Anki reference captures are `anki-reference-A-ready-light-100.png`, `anki-reference-B-completed-light-100.png`, `anki-reference-C-all-clear-light-100.png`, and `anki-reference-D-loading-light-100.png`. They were taken from the inert fixture on the Pixel emulator at system font scale 1.0, default 440 dpi, and Light theme. The fixture supplies synthetic deck counts and no-op callbacks; it did not submit grades, launch reviews, or modify a connected collection.

`anki-production-installed.png` is the installed production app opened to the actual Anki deck hub. It shows the emulator's live AnkiDroid summary and was captured without starting a deck.

The directory contains 39 PNG captures: these five current Anki captures plus 34 WaniKani captures from an earlier QA pass. The WaniKani captures were retained but were not rechecked during this Anki visual revision. The deck artwork palette and motif are selected from the stable account/deck ID, so colors vary across decks by design and are not expected to match the fixture art pixel-for-pixel.

Validation for this revision:

- `npx tsc --noEmit` passed.
- `./gradlew :app:assembleDebug` passed after restoring the production `index.js` entry.
- The x86_64 debug APK was installed and opened to the live Anki hub; debug APKs for arm64-v8a and armeabi-v7a were also produced.
- The root agent reports 48/48 JavaScript tests passing and the focused Anki native unit test passing.

This pass did not include large-font or alternate-width capture sweeps, TalkBack testing, or manual accent-palette testing. No live review grades or WaniKani lessons were submitted.

Changed implementation files for the hub handoff: `AnkiDeckPicker.tsx`, `review-ui.tsx`, `DeckThumbnail.tsx`, `deck-art.ts`, `WaniKaniHome.tsx`, `wanikani-hub-model.ts`, `review-store.ts`, `useReview.ts`, and `ReviewScreen.tsx`; native count validation is in `AnkiReviewPolicy.kt`. Regression coverage is in `tests/wanikani-hub.test.cjs`, `tests/deck-art.test.cjs`, and `AnkiReviewPolicyTest.kt`. The design map and handoff index record the latest Anki board's precedence over the earlier hub treatment.
