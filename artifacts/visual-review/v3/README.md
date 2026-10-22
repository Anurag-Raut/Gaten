# WaniKani V3 Android visual review

| Capture set | Contents |
|---|---|
| 56 state captures | All 14 hub states in light and dark themes at 100% and 160% font scale |
| 28 scroll captures | Scrolled continuation for each large-text state and theme |
| 6 daily-limit captures | Daily-limit completion at both font scales and themes, with large-text scroll captures |
| Production checks | Live WaniKani hub, compact WaniKani settings, and Anki deck selection behavior |

All fixture images are in [`fixtures/`](fixtures/). File names use `{state}-{theme}-{scale}.png`; the large-text continuation adds `-bottom`. The 14 state keys are `ready`, `reviewsDone`, `lessonsDone`, `bothDone`, `loading`, `refresh`, `disconnected`, `expired`, `offlineCached`, `offlineEmpty`, `error`, `locked`, `partial`, and `paused`. The separate `dailyLimit` captures show the daily-limit completion state.

The fixture mounts the production `WaniKaniHome` with fixed in-memory summaries and no-op action callbacks. It does not submit WaniKani reviews or start lessons. After a large-text scroll exposed content beneath the status bar, the root view was given the safe-area top inset; the corrected light and dark scroll captures show the system status area clear. Initial screen geometry is unchanged.

Production screenshots live in [`production/`](production/). The most useful references are [`wanikani-hub-final.png`](production/wanikani-hub-final.png), [`wanikani-settings-final.png`](production/wanikani-settings-final.png), [`anki-empty-hub.png`](production/anki-empty-hub.png), and [`anki-hub-final.png`](production/anki-hub-final.png). `anki-one-hub.png` records the earlier direct-open behavior.

The original WaniKani lesson limit was Unlimited. It was changed to 10, confirmed after reopening, then restored to Unlimited and confirmed. The original Anki study selection was the explicit Default deck ID. None and Include all were saved and reloaded; the explicit Default-only selection was restored. With no deck selected, the production hub showed the empty-deck message. With Default selected, opening Anki shows the deck hub and does not read a card queue until a deck is started. No answer was revealed or submitted. No WaniKani review or lesson submission was made.

The emulator's original font scale (1.0), Light theme, palette, accent, density, WaniKani lesson limit, and explicit Anki selection are restored. The temporary entry point was replaced with the exact original `index.js`, then the final production APKs were rebuilt and the x86_64 APK was installed. The emulator is left on the Anki deck hub; [`anki-hub-final.png`](production/anki-hub-final.png) captures the live app.

Final production APKs:

- [`app-x86_64-debug.apk`](../../../app/build/outputs/apk/debug/app-x86_64-debug.apk)
- [`app-arm64-v8a-debug.apk`](../../../app/build/outputs/apk/debug/app-arm64-v8a-debug.apk)
- [`app-armeabi-v7a-debug.apk`](../../../app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk)

Coordinator checks passed: TypeScript, `git diff --check`, 35 JavaScript tests, native unit tests, and final Gradle assemble. Native local-day rollover behavior was not exercised on the emulator.
