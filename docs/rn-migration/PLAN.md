# React Native Frontend Migration — Plan

## Summary

| Question | Answer |
|---|---|
| Goal | Replace every Compose app-screen with React Native; keep Kotlin for OCR, dictionary lookup, Anki IPC, WaniKani network, accessibility overlay and floating trigger |
| Approach | Brownfield: RN embedded directly in the existing `:app` Gradle module; `MainActivity` becomes a `ReactActivity`; the JS app renders the whole in-app UI |
| RN version | 0.86.3 (prebuilt maven artifacts, stable library ecosystem; 0.87 has no Android prebuilts yet, 0.88.0 released same day — too fresh) |
| Expo | Dropped: pure RN 0.86.3 with `@react-native/gradle-plugin`, `@react-native/metro-config`, `@react-native/babel-preset` |

## Architecture

```
MainActivity (ReactActivity, component "Gaten")
  └─ MainApplication (ReactApplication → DefaultReactHost, bridgeless, newArchEnabled)
       └─ JS app (index.js → App.js → src/rn/ui/)
            ├─ AppShell: 3 tabs (Home/Analytics/Settings), per-tab stacks,
            │  custom BottomBar (hidden under pushed pages, the review flow included)
            ├─ Home dashboard, Settings hub + 6 pages, Search + word detail,
            │  WaniKani panel, Anki review — rebuilt per SCREEN-SPECS.md
            └─ Kotlin bridge modules (app/src/main/java/com/gaten/app/rn/):
                 GatenDict, GatenAnki, GatenWaniKani, GatenTheme, GatenHaptics,
                 GatenAppInfo, GatenShell, GatenTrigger, GatenPlatform,
                 GatenRecognition, GatenCardWebView (view manager)
```

Lookup overlay (`ui/lookup/`), overlay window controller (`ui/overlay/`) and floating
trigger (`trigger/`) stay fully Kotlin/Compose in their own service-owned window —
they are not part of the activity UI.

## What stays native (Kotlin)

`ocr/`, `dict/`, `anki/`, `wanikani/`, `GatenAccessibilityService`,
`ui/lookup/`, `ui/overlay/`, `trigger/`, `ui/theme/` (ThemePreferences + color
math reused by the bridge; `GatenTheme` composable kept for the overlay window),
`ui/components/{KanjiStrip,Pressable}` (used by the overlay result sheet).

## JS structure

- `index.js` — entry; `App.js` — root (GestureHandlerRootView → SafeAreaProvider → ThemeProvider → AppShell)
- `src/rn/native/` — typed wrappers over the classic `NativeModules` bridge
- `src/rn/ui/` — theme.tsx, tokens.ts, navigation.ts, AppShell, BottomBar, per-tab screens
- `src/rn/ui/review/review-store.ts`, `src/rn/ui/search/searchStore.ts`,
  `src/rn/ui/settings/recognition-store.ts` — module singletons holding shell-level
  state (equivalents of SearchViewModel / AnkiReviewViewModel / RecognitionViewModel)

## Notes / deviations

- JVM target raised 11 → 17 (RN Gradle plugin pins Java 17).
- Repositories mode `FAIL_ON_PROJECT_REPOS` → `PREFER_SETTINGS` (RNGP requirement).
- `react-native.config.js` added — pins `project.android` because the repo has no `android/` subfolder.
- Compose tests of removed screens deleted (`HomeStateTest`, `SearchStateTest`,
  `GatenNavigationTest`, `AnkiReviewSessionActiveTest`) — their subjects no longer exist.
- Process-death restore is approximated by the module-level JS stores (no SavedStateHandle equivalent).
- Activity restarts: `MainActivity` installs react-native-screens' `RNScreensFragmentFactory` before
  `super.onCreate`, and the activity declares the RN template's `configChanges` so a rotation or a
  dark-mode switch does not rebuild it. Exercised on device ("Don't keep activities" left on:
  background then relaunch) — no crash.
- Keyboard: the activity declares `adjustResize`, which is the documented precondition on Android,
  but on Android 15 + targetSdk 36 the window is **not** resized for the IME (measured: the app's
  root view still spans 1080×2400 with the keyboard up). The search screen therefore turns the inset
  into layout itself: its body is wrapped in `KeyboardAvoidingView behavior="padding"` with no
  `keyboardVerticalOffset` (it is the scene's top child). Measured: with the keyboard up (top edge
  y=1591) the results list's bounds are `[0,448][1080,1591]` — it ends at the keyboard's top edge —
  and with the keyboard hidden they are `[0,448][1080,2200]`, the full scene, so nothing is padded
  twice. One tap on an example chip and one tap on a result both work with the keyboard up, and the
  word detail is pushed and popped with the layout intact.
- Button labels are declared single-line (`numberOfLines={1}` on Home's filled/text actions). Without
  it, an intrinsic-width button measured "Choose deck" as two wrapped lines and painted only the
  first one ("Choose") — reproduced on the device, fixed and re-observed with the flag.
- Tab navigation runs on `@react-navigation/material-top-tabs` (7.8.1) + `react-native-pager-view`
  (9.0.9, **native**, autolinked — no Gradle or manifest edit), the pair the FitMedik reference uses.
  `react-native-tab-view` arrives as material-top-tabs' own dependency; `@react-navigation/bottom-tabs`
  was removed once its last importer (a `HomeScreen` prop type) was switched to the top-tab type.
  pager-view supports RN 0.86–0.88 and the New Architecture only — which is what this app builds.
  The old bottom-tabs fade (`animation: 'shift'`) and the full-width 380 ms slide it replaced are both
  superseded; the motion table above states what runs now.

## Motion

One motion language, three mechanisms, each where it belongs:

| Surface | Mechanism | Why |
|---|---|---|
| Tab switch | `@react-navigation/material-top-tabs` (7.8.1) over `react-native-pager-view` (9.0.9), its bar at the bottom — the pager the FitMedik reference uses. `swipeEnabled` drives a drag, `animationEnabled` drives a tap, and both move the real pages horizontally | The user's requirement, and the reason for it: the tab level was a *fade* (`bottom-tabs` `animation: 'shift'` = 50 dp plus a cross-fade) and before that a full-width 380 ms slide. A pager is a page move for a tap and for a finger alike, and it is the maintained primitive rather than a hand-made animation. **Supersedes both earlier rows.** `swipeEnabled` is gated to a tab's own ground (a pushed page or a live review session leaves the pager still); `animationEnabled` is off under reduced motion |
| Stack push/pop | `@react-navigation/native-stack` `animation: 'slide_from_right'` | Runs on the platform animator (no JS frames). Duration/easing are the platform's — `animationDuration` is accepted and ignored on Android. The design's own push is `pageSlide` (380 ms, 25 % parallax); reproducing it exactly would mean the JS-driven stack navigator instead |
| Review steps | `react-native-reanimated` shared value + `withTiming` in `review/StepTransition.tsx` (260 ms, 25 % parallax, z-order by direction) — **entering and leaving a session only** | Two pages are on screen at once with asymmetric z-order (incoming above going forward, below coming back) and must be retargetable mid-slide — none of which `entering`/`exiting` layout animations can express. The question→answer reveal is **not** a transition: the step is rendered directly in place (no slide, no height tween), which is what the user asked for |
| Press feedback | Reanimated `useAnimatedStyle` scale in `PressableScale.tsx` — 0.98 over 100 ms on `pressEasing` | The Kotlin `pressScale` exactly; interruption is free (assigning a new value cancels the running one) and it stays off the JS thread |
| List entrances | **none anywhere** | Every stagger is gone: the search results and word-detail sections never had one in Compose, and the review deck picker's index-based `FadeInDown` stagger was removed in round 2. The Compose screens animated nothing on list entry — content simply arrives |
| Bottom bar selection | One travelling indicator pill: `translateX` interpolated from the pager's own `position` (native-driven), plus per-item glyph and label cross-fades of the two tints — and, on a tap only, one horizontal spring: the pill keeps the speed its own travel was carrying, runs ≈ 7 dp past the tab it is aiming at and settles back onto it. A drag injects nothing | The pager's `position` is continuous while a finger drags it and while a non-adjacent tap walks the pill through the middle tabs, so one node keeps the pill and the pages in step. A colour interpolation would have forced the JS driver, so the tint change is two stacked copies cross-fading. The travel is the pager's and must not be timed from the bar, so the spring sits at the *end* of it — and only at the end a tap makes: a swipe's travel ends under the reader's own finger and gets nothing after it. It is injected as velocity rather than as a target position, so the pill is never moved to a place the pager did not put it — it is just given the speed it already had. Horizontal only (≈ 7 dp of a ≈ 66 dp slot), and the glyph gestures are untouched |

Deliberately **not** used: `entering`/`exiting` for the review step swap (z-order and retargeting, see above), and any Reanimated wrapper around Home's `flex: 1` card columns — a layout-animated wrapper there collapsed the column's measured height and overlapped the cards.

## Fonts

`Type.kt` draws every Latin glyph in Manrope (`res/font/manrope_variable.ttf`) and the two display
steps — always a Japanese word — in the system face. The bundled file is a *variable* font whose own
default instance is ExtraLight 200, and React Native has no variation-axis setting: it resolves a
text's weight against the family's declared entries (`ReactFontManager.getTypeface`), so the variable
file named directly as an asset family would draw the whole app at 200.

- `app/src/main/res/font/manrope.xml` publishes the family with the three weights the scale asks for
  (400/500/600), each entry pinned to that `wght` value. It references the file the Compose theme
  already uses, so the APK grows by no bytes.
- `tokens.ts` names that family on every role except `displayMedium`/`displaySmall`.
- The family is registered with React Native once, in `MainApplication.onCreate`:
  `ReactFontManager.getInstance().addCustomFont(this, "Manrope", R.font.manrope)`
  (`com.facebook.react.common.assets.ReactFontManager`, the class `ReactTypefaceUtils` resolves a
  text's family through). Without it the name resolves to nothing and text falls back to the system
  face — Roboto, never a crash.

## Round 2 — modern pass

The visual language was modernised without touching the feature set: one accent used with intent, layered
charcoal surfaces in dark and a warm neutral canvas in light, 24 dp gutters, capsule chips, a per-row icon
badge, dialogs and fields shared, and one travelling indicator inside a dark bar whose light capsule holds
the active tab's icon and label (inactive tabs are muted icons alone). The tab level stays the pager
described above.

Behaviour changes, each a user decision:

- **No programmatic search focus.** The keyboard opens only on an explicit tap on the field — not on a tab
  tap, a swipe, Home's hero card, a mount or a return. `searchFocus.ts` is deleted and the pager's own
  `keyboardDismissMode` dismissal is back (no exception has to be held for it).
- **The review media note was removed** (it was a pinned `NoteCard`, never a gate), so Start goes straight
  into the session; the typed-input notice stays, and "Open in AnkiDroid" became a quiet header icon.
- **A swipe is locked while a page is pushed.** `AppShell`'s `SwipeGate` applies `swipeEnabled` through the
  focused tab screen's own `setOptions`, because the declarative `screenOptions` form was measured not to
  hold (a drag on the pushed word detail moved the pager). Verified on the device after the fix.

Retained limitation: card media is still not rendered. AnkiDroid does not share its image and sound files
with other apps, and the app draws none of them — the round-2 pass added no media support, and the pinned
note that used to explain it is gone (the reader is told nothing about media for such a card now).

## Refine pass

- **System Back was dead on devices below Android 16.** The manifest opted into
  `android:enableOnBackInvokedCallback="true"`, while React Native 0.86 registers its own back callback
  only when the *device* is API 36+ (`ReactActivity.onCreate:62` with `AndroidVersion.kt:51`). On the
  moto g35 (API 35) the system therefore treated every back as unhandled and backgrounded the task: no
  `BackHandler` in JS ran, and Back from a Settings subpage read as "the app exited". The flag is now
  `false` — Android's own documented opt-out — which puts back on the legacy `onBackPressed()` path the
  JS handlers hang off; on API 36+ devices the platform enforces predictive back and React Native
  registers its callback there, so the same JS path serves both. Verified on device: Settings subpage →
  Back (hardware **and** toolbar) → the Settings hub with the app alive; word detail → kanji reference →
  Back pops exactly one level; a non-Home tab root → Back goes Home; the Home root → Back leaves the app.
- Visual refinement (user-driven, reference-led): the Settings hub is individual rounded rows with plain
  icons, a `displaySmall` heading and no red banner (the screen-access row states its state calmly);
  surfaces are softer; the bottom bar's palette now comes from the theme's own roles
  (`surfaceContainer` / `primaryContainer` / `onPrimaryContainer` / `onSurfaceVariant`) with its geometry
  and movement unchanged.
- Review startup: `AnkiReview.readQueue` stops at the first due card instead of probing every queued one
  (up to eight provider round trips saved when the head is due; none added in the worst case). Measured
  on the device: the session shell (question chip, controls) ~0.7 s after the Start tap, the card's own
  rendered content a moment later — one sample, no speed-up percentage claimed.
- Search coalesces: nothing reaches the native dictionary while typing; one classifier plus one lookup
  run 250 ms after the last keystroke; chips, submit and retry act immediately; the previous results stay
  on screen while a new query runs. No programmatic search focus anywhere.

## Verification

- `npx tsc --noEmit` — 0 errors.
- `:app:compileDebugKotlin`, `:app:assembleDebug` — BUILD SUCCESSFUL; APKs for all three ABIs produced; debug builds carry `assets/index.android.bundle`, so Metro is not needed to launch.
- `:app:testDebugUnitTest` — 519 tests, 0 failures.
- On device (moto g35 5G, Android 15, 1080×2400): installed, cold-launched, all four tabs exercised, a real romaji search (`neko` → 猫, exact hits then prefix group), a word detail page, and a full review session (deck picker → question → answer → rating bar with AnkiDroid's own intervals). No crashes; insets verified against the device's 108 px status bar.
- Fixed during that pass: `[sound:…]`/`[anki:play:…]` tags no longer print their own source text on a card (AnkiDroid renders them as players; the app cannot play them).

## Ink review workflow (2026-10-21)

The Review tab was rebuilt to the approved Gaten Ink review workflow (`interactive-preview.html` and
the five snapshots in the handoff kit), and WaniKani gained a real review session. Home, Analytics
and Settings were not touched.

### What the tab was, and why it is gone (same day)

The workflow first landed as a fourth tab with one root — a landing page offering the two services —
and every other step inside it. The landing page was removed the same day it shipped: it restated
what Home's cards and Analytics' metric cards already say, and studying is something the reader does,
not a place they browse. The shell now has **three tabs** (Home / Analytics / Settings) and the flow
is a page pushed onto Home's stack, opened by the two service cards.

| Step | Notes |
|---|---|
| `opening` | the read that precedes a step: `Anki` / `WaniKani` sub-header, one quiet busy row |
| `blocked` | what stops AnkiDroid, and the one thing that fixes it |
| `decks` | `Anki` sub-header, `Choose a deck`, one row per deck, tap starts it |
| `session` | compact header, thin progress line, pinned reveal/rating |
| `done`, `failed` | quiet states, no cards |
| `wkConnect` | connection state, token field, pinned action |
| `wkReview` | topic, prompt, answer field, pinned check |
| `wkDone`, `wkFailed` | caught-up and failure states |

Every step is full-screen with no bottom bar: a pushed page hides the bar by the shell's own rule
(`BottomBar`'s `depth === 0`), so no step has to ask for it, and `session.ts` — the `ownsScreen` /
`SessionContext` plumbing that existed only to hide the bar for review steps — is gone, along with
`review-store`'s `ownsScreen` / `onOwnsScreen` and `AppShell`'s subscription to them.

`review-store.ts` owns all of it; `useReview.ts` (was `useAnkiReview.ts`) subscribes and drives the
lifecycle.

### One way in from Home

Home's two service cards used to lead elsewhere — the Anki card to the Review tab's root, the WaniKani
card to its settings page. Both now push the matching screen of the flow: the card moves the store to
that service's step (`openAnki`, `openWaniKani`) and then pushes the page, so a tap lands on the deck
list (or on the state that stops it) or on the connection state (or the queue behind it) rather than
on a landing page. The Settings pages are untouched and still hold the configuration.

Home's **Start studying** button went with the tab: once the Anki row opened the deck list itself, the
button was a second way to the same screen, and it was the only filled primary action on Home. That
is a deliberate deviation from the supplied Ink document ("one strong action per screen"), recorded
in `docs/design-language.md`; `home.canReview` — which existed only to disable it — went too.

A step is still not a route, and now it is not a tab either: `HomeStackParamList` gained a single
`Review: undefined` entry, and Back is that page's pop. `openAnki` / `openWaniKani` are the only ways
in, so the old `refresh()` — what entering the tab asked for, which left a live session and a
deliberate `blocked` state alone — was deleted with the tab: re-entering resumes a live card session
or a live WaniKani queue, and reads the collection again for everything else.

### Native additions (the app is no longer read-only on WaniKani)

| File | Change |
|---|---|
| `WaniKaniApi.kt` | `post(path, body, token)` — same allow-list, timeouts, caps and redirect refusal; a body is the only thing that makes a request a POST |
| `WaniKaniReview.kt` | new: the queue/submission shapes, `readingAnswerHiragana` (NFKC, whitespace, katakana fold, romaji via the vendored WaniKana) |
| `WaniKaniDecode.kt` | assignment and review-subject decoders; accepted meanings (`accepted_answer` + whitelist auxiliaries, slug fallback) and accepted readings folded to hiragana |
| `WaniKaniClient.kt` | `reviewQueue()` (assignments due now, then one `GET /subjects?ids=…`, ordered by `available_at`) and `submitReview()` (`POST /reviews`) |
| `GatenWaniKaniModule.kt` | `reviewQueue`, `readingAnswer`, `submitReview` |

`src/rn/native/GatenWaniKani.ts` carries the same three calls; the review outcomes resolve rather
than reject so a rate limit's `retryAfterSeconds` can travel with them, matching the stats precedent.

### Verification

- `npx tsc --noEmit` — 0 errors.
- `:app:testDebugUnitTest` — 45 suites, 0 failures (15 of them new in `WaniKaniReviewTest`).
- `:app:installDebug` (x86_64) on `emulator-5554`, Android 14, 1080×2340 @ 440 dpi.
- Exercised on the device, light and dark: the landing page with AnkiDroid's real counts
  (`2 new · 5 to review`, `7 due now`) and `Not connected`/`Connect to use`; the deck picker;
  a real card session (question → reveal → a real interval-labelled rating row → the next card, the
  header and progress line advancing 1 of 7 → 2 of 7); the connection screen against the live API
  (a bogus token came back `invalidToken` and the screen said so); the permission-missing hub row and
  the AnkiDroid-access state; the tab switch that keeps the picker and its fresh counts.
- Enter motion measured from `screenrecord` frames: the `Choose a deck` heading travels 22 px (8 dp)
  and settles over ~120 ms of captured frames, with the fade visible in the frame luminance. With
  `animator_duration_scale=0` and a fresh launch the same navigation shows the heading at its final
  position in the first frame — the reduced-motion path skips the transition.
- 130 % font scale: the hub and a card session grow without clipping; the pinned action stays above
  the keyboard on the connection screen.
- **The WaniKani review screen itself was verified against a stubbed queue**, because there is no
  WaniKani account on this emulator: `reviewQueue()`/`submitReview()`/`isConfigured()` were replaced
  for one build only (real `readingAnswer`), the flow was walked (wrong meaning → the answer shown →
  retyped → the reading prompt → `yama` accepted as やま → the next subject → the completion state),
  and the file was restored and rebuilt. The four screenshots taken that way are
  `after/verify-review-wanikani-review-light.png`, `…-dark.png`, `…-wrong-answer.png` and
  `…-complete.png`; every other screenshot above is the unmodified app.
- Screenshots: `after/verify-review-*.png` (light and dark for each screen, plus the wrong-answer,
  completion, permission and blocked states and the 130 % hub).

### Limitations worth knowing

- A queue deeper than 500 due assignments is read one page deep; the reader can finish it and open
  the queue again.
- An image-only radical is drawn from WaniKani's cached SVG through the reference lookup; if that
  image cannot be fetched the prompt is empty rather than showing the subject's name, which is the
  answer.
- WaniKani's own accepted-answer set is the subject's own plus its whitelisted auxiliary meanings;
  a reader's personal synonyms (study materials) are not read, so an answer that only their account
  accepts is marked wrong here.

## The Review tab removed (2026-10-21, later the same day)

Three tabs (Home / Analytics / Settings), and the review flow is a page pushed onto Home's stack.

| File | Change |
|---|---|
| `navigation.ts` | `RootTabParamList` loses `Review`; `HomeStackParamList` gains `Review: undefined`; `ReviewStackParamList` deleted |
| `AppShell.tsx` | `ReviewStack` / `ReviewTabStack` / the fourth `Tab.Screen` deleted; `Review` registered on `HomeStack`; `openReview(navigation, ask)` asks the store and pushes, guarded against a second copy; `screenOwned` state, its `reviewStore.onOwnsScreen` subscription, the `SessionContext.Provider` and `SwipeGate`'s prop all deleted |
| `ReviewScreen.tsx` | `ReviewTab` → `ReviewScreen({onExit})`; the `hub` case gone, `opening` added; `leave()` pops instead of returning to a hub; the Back handler now registers only for a card session or a failure inside a deck; `stateBody` no longer reserves room for a bar |
| `review-store.ts` | `hub` step and state, `HubAnki`, `readHub()`, `openHub()`, `refresh()`, `ownsScreen()` / `onOwnsScreen()` / `screenListeners` deleted; `openAnki` / `openWaniKani` are the only ways in and resume a live session or queue |
| `review/useReview.ts` | `openHub` / `refresh` gone from the actions; the focus-driven entry read gone; `ownsScreen` gone from the return |
| `review/ReviewHub.tsx`, `session.ts` | deleted |
| `BottomBar.tsx` | `Review`/`school` icon and its arrival motion gone; `visible = depth === 0 && !searchActive` |
| `HomeScreen.tsx` | both fallback paths push the `Review` route |
| `home/HomeDashboard.tsx` | the `Start studying` button and its three styles deleted; the `react-native-svg` import went with it |
| `home/useHomeDashboard.ts` | `canReview` deleted — the button was its only reader |

### Bottom bar

The bar is no longer a fixed 380 dp with the tabs spread across it: it is as wide as the tabs it
holds — the widest capsule, one `SLOT_WIDTH` per other tab, and the inner padding — capped by the
display. It is a function of the measured labels alone, never of the selection, so the surface stays
put while the capsule travels inside it. The one-layout-pass placeholder is now just the invisible
measuring row, because the bar's own width cannot be known before the labels are measured.

Measured on the emulator (1080×2340 @ 440 dpi, so 392.7 dp wide): the bar went from **348.7 dp
(953 px, 88 % of the display)** to **256.7 dp (706 px, 65 %)**; the capsule lands at a 6.2 dp inset
with `Home` selected and 72.0 dp with `Analytics` selected, both matching `layoutAt`'s own arithmetic
(6 + 0 × inactiveSlot, and 6 + 1 × 66).

### Verification

- `npx tsc --noEmit` — 0 errors.
- `:app:assembleDebug` — BUILD SUCCESSFUL (APKs for all three ABIs).
- `react-native bundle` (android, dev false) — bundles clean; the output contains `Opening AnkiDroid`
  and `Reading WaniKani`, and contains neither `Choose where to study` nor any `school` glyph entry,
  so the removed screens are gone from the graph rather than merely unreferenced.
- Device walkthrough below — every path this change touches was exercised, including the bar's own
  geometry, which was measured from the screenshots rather than eyeballed.

### Device walkthrough

`app-x86_64-debug.apk` installed on `emulator-5554` (the debug build bundles `index.android.bundle`,
so no Metro is needed; `strings -e l` on the APK's bundle confirms `Opening AnkiDroid` and
`Reading WaniKani` are in it and `Choose where to study` is not).

- Cold launch → Home with a **three-item bar** (Home / Analytics / Settings); the Review glyph and its
  slot are gone, and the capsule's travel still lands each label on its own tab.
- Tap Home's **Anki** card → the deck picker directly (`Anki` / `Choose a deck`, `6 cards ready to
  study`, `Default — 1 new · 1 learning · 4 reviews`), full-screen with no bar. No landing page.
- Tap `Default` → the card session (`Default`, `1 of 6`, 条, pinned `Show answer`).
- System Back → `Leave the review session?` (Stay / Leave); `Leave` → the deck list; Back → Home.
- Tap Home's **WaniKani** card → the connection screen directly (`WaniKani` / `Connection`, token
  field, pinned `Connect WaniKani`); Back → Home.
- Analytics and Settings tabs: unchanged and intact.
- Home has no `Start studying` button (no filled action band above `Recently looked up`, checked by
  scanning those rows for the accent fill), and Home's **Anki** row still opens the deck list.
- The bar's geometry, read off the screenshots pixel by pixel: 256.7 dp wide and 62.9 dp tall, the
  capsule 6.2 dp from its leading edge with `Home` selected and 72.0 dp with `Analytics` selected.

## Bottom bar spring, on the pill's travel (2026-10-21)

Asked for: a spring in the bottom bar, horizontal, on taps only — not the button-press kind of
motion. The bar therefore gained exactly one spring; nothing else moved — not a glyph gesture, not a
piece of geometry, not the pager's own timing.

- **What it is.** On a tap, `BottomBar`'s press path hands the capsule's shared value the speed its
  travel was carrying, in the direction it was going, 160 ms after the tap
  (`withSpring(0, {mass: 1, stiffness: 500, damping: 20, velocity: ±270 dp/s})`). That value is a
  horizontal `translateX` offset added on top of the pager's interpolated position, so the pill
  arrives still carrying speed, runs ≈ 7 dp past the tab it is aiming at, swings ≈ 1.4 dp the other
  way and settles: peak 55 ms after the injection (≈ 215 ms after the tap), motion gone by ≈ 0.4 s.
- **Why velocity rather than a target position.** The pill is a function of the pager's `position`,
  which is what keeps it in step with the pages, drag included. A spring that *moved* the pill to its
  own target would be a second animation disagreeing with the first; giving it speed leaves its
  position untouched at the instant of injection and only shapes what happens after.
- **Why never on a swipe.** A drag's travel ends where the reader's finger leaves it, and a spring
  after that is the bar arguing with the hand that moved it. The injection lives in `BarItem`'s
  `onPress` and nowhere else — a drag never reaches it. `swipeStart` also cancels any pending
  or running tap spring and resets its offset, so an interrupted tap cannot affect a manual swipe.
- **Both capsule nodes carry the offset.** The fill and its icon-and-label content are two views over
  one box (the fill paints *under* the plain glyphs, the content *over* them), so the offset is
  applied to both: same box, same value, no drift between the pill and its own label.
- **Not a button.** The glyphs' arrival gestures (Home's bounce, the chart's growth, the gear's turn)
  are unchanged — still `withTiming` at 200/400 ms — and so is every press-scale in the app. Reduced
  motion skips the injection entirely, and the offset is transform-only, on the UI thread.

Not verified in this pass: how the spring *feels* — 7 dp may read as too little or too much on a real
screen — is a judgement only a device can settle, and no build was installed here.
`npx tsc --noEmit` — 0 errors; `react-native bundle` (android, dev false) — builds clean.
