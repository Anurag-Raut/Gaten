# Redesign implementation status

## Summary

Approved by the user's “continue” on 2026-10-16. Work is on
`revamp-app-holistically`; earlier uncommitted work is preserved. No commit or push
is authorized.

| Workstream | Status |
|---|---|
| W1 — theme and shared components | Done; `assembleDebug` passed |
| W2 — page and review motion | Done; build/unit gate passed before later waves |
| W3 — haptics and optional sounds | Done; `assembleDebug` passed |
| W4 — Home and Settings | Done; owned build and string/inset audits passed |
| W5 — autofocus and romaji search | Done; core and UI slices both `assembleDebug` green |
| W6 — review and lookup fixes | Done; trigger emulator-verified, review layout/feedback implemented and smoke-checked on the emulator |
| Final build, unit tests, emulator walkthrough, APK transfer | Done; gate + 4/4 instrumented green, walkthrough 7/7 PASS |

## Execution notes

- Specification: [PLAN.md](PLAN.md). Implementation uses the configured Paseo
  `Impementer` profile (`omp/openrouter/deepseek/deepseek-v4.1-flash`, full, high).
- Android SDK: `/home/Qui/Android/Sdk`. Existing frozen dictionary source archives
  are cached in `/tmp/jmdict-build-cache`.
- Only `emulator-5554` is available through ADB. The OnePlus Nord 6 was paired and
  reachable through KDE Connect at the initial check; recheck before transfer.
- The final emulator pass is explicitly included in the approved specification.
  Preserve its starting settings, avoid grading/exporting to the user's Anki
  collection, and record any verification that needs a disposable fixture.
- Lookup tap misses explicitly call `close()` in `LookupOverlay.onTap`; replace
  that behavior with a retry hint. The floating trigger needs separate diagnosis.
- WaniKani storage-failure handling already has a client unit test. Physical
  Keystore/file failure and rotation checks must be reported separately from
  client-policy tests.
- Prepared additional client coverage for partial disconnect, plus instrumented
  tests for a refused token-file write and token-field saved-state restoration.
  Not executed yet; instrumented tests skip existing credentials/key aliases.

## Validation

- W1: `./gradlew :app:assembleDebug` passed (15 s), log `/tmp/gaten-redesign-w1-build.log`.
- Manrope weight-axis metadata checked: min/default/max = 200/200/800; explicit typography instances select 400/500/600.

- Lookup tap misses now show a retry hint and keep selection mode open; unavailable/incomplete scans also keep the overlay open. The final emulator pass will check this path.

- Overlay work is split from review to keep ownership separate: parent owns `LookupOverlay.kt`, `LassoLayer.kt`, `ResultSheet.kt` and shared chip visuals. The review/floating-trigger worker will own `AnkiReviewScreen.kt` after W2 and `FloatingTriggerButton.kt`.
- Overlay/shared-feedback integration: `:app:compileDebugKotlin` passed after retry-hint, selection animation, press scaling, shared sheet radius, and success-feedback changes. Final combined build remains pending.
- `KanjiStrip` was already shared before this work. Its implementation was moved to `ui/components`; existing word/overlay destination navigation is retained rather than introducing duplicate panel state.

- W3 `assembleDebug` passed; generated Vorbis cues are 3,957 + 4,615 = 8,572 bytes. `Feedback` API is ready for screen owners. Sounds off allocates no pool; disposal and preference changes gate deferred playback.

- W2 combined build/unit gate passed (`/tmp/gaten-redesign-w2-gate.log`); Android-test assembly initially found two missing extension imports, now fixed. A subsequent attempt encountered W4 edits in progress; rerun after screen work settles.
- All workers now serialize Gradle through `flock /tmp/gaten-redesign-gradle.lock`; shared output directories must not be compiled concurrently.

- Later W2 gate retries were blocked by W4 screen edits and W5 schema4 staging; the earlier green gate preceded these independent changes. The final serial build/unit run will be the authoritative combined result.

- W2 ownership released: W6 now owns review layout/feedback. Shared `theme.pageSlide(forward)` is used by navigation, review stages and lookup reference pages.

- W5 split into core/index/tests (`cd0eb121…`) and search UI/focus routing (`f73575e0…`) to finish independently. The latter owns only navigation tests; existing SearchState/ViewModel tests stay with core.

- Packaged DB checked read-only: schema4, 250,816 loose readings; SQLite query plan uses `idx_loose_reading` (indexed SEARCH). Direct loose queries reach 東京, ラーメン and 柔道.

- Combined `assembleDebug` + `assembleDebugAndroidTest` passed after public Wanakana facade/import fixes. Android-test methods use camelCase because API29 DEX disallows spaces in method names. Instrumented tests are compiled/packaged, not run while W6 owns the emulator.

- W5 split into core/index/tests and search UI/focus routing; both slices report done. Core: schema 4 dictionary rebuilt (250,816 loose rows, `idx_loose_reading`), probes tokyo/ramen/ohayo/gakko/arigato/kirei/saikou/byouin all match pinned spellings, unit suite 556 tests / 0 failures, `assembleDebug`+`assembleDebugAndroidTest` green (`/tmp/gaten-redesign-w5-build.log`). UI: navigator-flag focus routing (`searchFocusRequested`/`openSearch`/`consumeSearchFocus`), word-detail pinned Add-to-Anki footer with navigationBarsPadding+16dp, LOOSE hit labels, nav tests 19/19 green (`/tmp/gaten-redesign-w5-ui-build.log`). Instrumented romaji test compiled but not run (no emulator).
- Parent removed the dead `SearchViewModel` focus API (`focusPending`/`requestFocus`/`consumeFocusRequest`) — the UI slice settled on the navigator-flag design; grep confirmed no remaining references.
- W6 trigger root cause recorded in `docs/ui-redesign-spec/after/floating-bubble-rootcause.md`: the bubble window fit system bars, so it laid out inside an inset-shifted parent frame — the touch rect sat one status-bar height (136 px) below the reported position and taps fell through; the 250 ms tap/drag timer also ate stationary presses. Fix: no fit-insets, positions clamped to display metrics, re-clamp on configuration change, slop-only tap/drag split. Verified on the emulator (tap at drawn centre opens the overlay; 400 ms and 1500 ms presses trigger; drag clamps); emulator state restored to the as-found snapshot. Landscape verification deferred to the final walkthrough.
- W6 review screen (`AnkiReviewScreen.kt`, `AnkiReviewViewModel.kt`, `strings_anki_review.xml`): card centred in the scrollable viewport (min height = viewport, `wrapContentHeight(CenterVertically)`); question side rendered at the theme's `displayMedium` through the WebView CSS bridge (40 px/1.2 in the document, on `#content`, because the note's own `.card{font-size:24px}` outranks a body default); pinned actions take `navigationBarsPadding()` with the Scaffold's content insets narrowed to top+sides (verified: rating buttons end at y 2230 = 2340 − 66 inset − 16 dp; no double padding with the bar visible either); rating labels/interval captions and the 2–4 button mapping untouched; stage slides left to `theme.pageSlide`; the inline WaniKani kanji/radical panels now slide as pages (`AnimatedContent` + `pageSlide` + a shared `SizeTransform`), replacing the fade+expand, with the VM's now-unused `lastKanji` state removed; press scale added to buttons and deck rows, each sharing its own interaction source; haptics routed through `ui.rememberFeedback` (the old private `View.tapFeedback`/`commitFeedback` deleted) — measured with `dumpsys vibrator_manager`: one `TICK` per chip *selection*, none when closing it, none on a deck row already selected, on `Start reviewing` or on the reveal; the answer flash (250 ms tint drawn over the WebView, so the note HTML is untouched) and the session-complete `Confirm` + optional `Done` cue keyed on the step *transition*, so a rotation or a return to a finished session stays silent; `strings_anki_review.xml` 41 → 38 (three strings verified unreferenced).
- W6 review measurements (emulator-5554, font scale 1.0): card `y 1076-1427` (h=352) with a 102 px glyph for 勘弁 → 40 sp-equivalent; at font scale 1.5 that glyph measures 153 px (×1.5, uncapped, no double scaling). Screenshots: `after/review-question-centered.png`, `after/review-answer-graderow.png`. Nothing was graded — the deck still reports 2 new · 2 learning · 5 to review after the pass.
- W6 gaps (need a disposable fixture or an explicit decision): the grade `Tick`, the correct/incorrect tint sweep and the session-complete cue cannot be exercised without grading a card of the user's collection; the radical-inside-kanji page needs a WaniKani token (this emulator shows the missing-token panel, so only the kanji level was seen to open and close).
- Final combined gate (authoritative): `assembleDebug` + `testDebugUnitTest` + `assembleDebugAndroidTest` BUILD SUCCESSFUL, 45 suites / 556 unit tests / 0 failures, whitespace check clean (`/tmp/gaten-redesign-final-gate.log`).
- Instrumented suite: 4/4 OK on emulator-5554 with the x86_64 APK (RomajiDictionarySearchTest 2 — one inverted ordering assertion in the test itself fixed by the parent, WaniKaniCredentialsStorageTest 1, WaniKaniSettingsRotationTest 1). AGP's `connectedDebugAndroidTest` installs the arm64 split on this x86_64 emulator and the arm64 `libonnxruntime.so` segfaults at startup (tombstone evidence); the manual `adb install` + `am instrument` run over the identical Gradle-produced APKs is the authoritative connected result. Arm64/onnxruntime startup is a pre-existing native issue outside the redesign's scope.
- Final walkthrough (inspector agent, read-only): 7/7 PASS with screenshots in `docs/ui-redesign-spec/after/verify-*.png`. Verified fixed on device: Anki Save gating, gesture-band insets, floating-switch a11y label, dead bubble tap (requested position == window frame, `ty=2032`), tap-miss keeps the overlay open with the retry pill, search autofocus/return-with-keyboard-closed, romaji probes (`tokyo`→東京, `ramen`→拉麺, `ohayo` exact-then-collapsed), review centring + 40sp front word + grade row clear of the band (bottom 2226 vs band 2274), haptics via `dumpsys vibrator_manager`, landscape bubble re-clamp + right-edge dock, motion is a horizontal slide everywhere (screenrecord frame analysis, no crossfade frames). Emulator state restored (before/after state files under `/tmp/w7-state`).
- Walkthrough notes: the reinstall for testing wiped app data (granted AnkiDroid permission temporarily, revoked at the end); the early "dead" taps coincided with the foreign SystemUI accessibility-button bubble eating touches above Gaten's window (matches W6's inference); one unreproducible one-off overlay self-close on the session's first trigger (matches PLAN §3.5's recorded flakiness); two cosmetic observations — Anki section header reads "Edit front, back, and styling" vs PLAN's "Advanced", and the screen-lookup "off" status card is full-bleed error red. Deck counts after grading: 2 new · 0 learning · 3 to review (grades not rolled back).
