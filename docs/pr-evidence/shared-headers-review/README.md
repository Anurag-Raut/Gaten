# Shared pushed-page bar — review flow evidence

Captured for PR #39 (`fix-ankidroid-install-page-header-download-button`): the review flow's pushed
pages (deck picker, Anki question and answer, the WaniKani entry screen) no longer draw their own
centred bar. They render the app's shared `ScreenHeader` — back control, **left-aligned** title and an
optional trailing control (count) — with the shared horizontal safe areas.

| Item | Value |
|---|---|
| Head under test | `5ce39fb` (`fix: unify app UI and add AnkiDroid download action`) |
| App | `com.gaten` 0.1.0 (debug, debuggable), `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `eb00d7578862068afb175b92ae4176ff9434053ffc9e491db693895403469b7c` |
| JS actually running | the head bundle embedded in that APK; Metro on host `:8081` served the same worktree throughout |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, Android 14, x86_64, **1080x2340 @ 440 dpi**, font scale 1.0 |
| Fixtures | AnkiDroid installed with deck **Gaten** (2 new cards due, permission granted during the run); WaniKani **not connected**. Nothing was rated or answered |
| Baseline stills | the same screens at the pre-change revision, on this same AVD: `docs/pr-evidence/review-header/` (captured for PR #35, base `d6364ee`/`3c51ea7`, where the title was deliberately centred) |

## Before / after

| Screen | Before (centred title) | After (shared bar) |
|---|---|---|
| Anki question — deck `Gaten`, `1 of 2` | [`review-header/01-anki-question.png`](../review-header/01-anki-question.png) | [`02-anki-question.png`](02-anki-question.png) |
| Anki answer — `Answer`, `1 of 2` | [`review-header/02-anki-answer.png`](../review-header/02-anki-answer.png) | [`03-anki-answer.png`](03-anki-answer.png) |
| Anki deck picker — `Anki` | [`review-header/03-anki-deck-picker.png`](../review-header/03-anki-deck-picker.png) | [`01-anki-deck-picker.png`](01-anki-deck-picker.png) |
| WaniKani entry — `WaniKani` | [`review-header/07-wanikani-review-question.png`](../review-header/07-wanikani-review-question.png) | [`04-wanikani-entry.png`](04-wanikani-entry.png) |

In the before column the title ink sits on the screen's centre line (the PR #35 README measures it at
−0.5 px from centre). In the after column the title starts at the shared bar's own content edge.

## Measured geometry (after, `uiautomator dump` on the live screen, width 1080)

| Screen | File | Header title | Title bounds | Trailing control |
|---|---|---|---|---|
| Deck picker | `01-anki-deck-picker.png` | `Anki` | `[132,195][1036,252]` | — |
| Question | `02-anki-question.png` | `Gaten` | `[132,195][963,252]` | `1 of 2` at `[963,204][1036,243]` |
| Answer | `03-anki-answer.png` | `Answer` | `[132,195][963,252]` | `1 of 2` at `[963,204][1036,243]` |
| WaniKani entry | `04-wanikani-entry.png` | `WaniKani` | `[132,195][1036,252]` | — |

Every bar's back control is `content-desc="Back"` at `[0,158][132,290]`, so each title starts
immediately at the back hitbox's right edge (x = 132) rather than at the screen centre (x = 540).

## Steps

1. Cold boot the AVD headless, `adb -s emulator-5554 install -r -d` the APK above, launch
   `com.gaten`, dismiss the React Native LogBox banner.
2. Home → **Anki** card → the flow's access state → **Allow AnkiDroid access** → system dialog
   accepted → deck picker (`Choose a deck`, deck `Gaten`): capture `01`, dump nodes.
3. Start the `Gaten` deck → question card (`猫`, `Show answer`): capture `02`.
4. Press **Show answer** → answer side (`猫 / ねこ`, senses, Example sentences, WaniKani kanji,
   Again/Hard/Good/Easy): capture `03`. **No rating was pressed**; the card was left unanswered and
   the flow was exited with `Back` → `Leave`.
5. Home → **WaniKani** card (no token) → the connect screen: capture `04`.

## What was not exercised

- The rating buttons were deliberately not pressed, so no card was graded and the collection is
  unchanged; the answer fonts, the example-sentence sheet, `WaniKani details` and the deck options
  menu were not opened.
- WaniKani is not connected on this device, so only the connect state of that flow exists here; the
  WaniKani hub, lesson and review screens share the same bar but were not driven.
- One device, one locale, default font size and font scale; no dark-theme or large-text captures.
