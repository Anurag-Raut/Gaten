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
            ├─ AppShell: 4 tabs (Home/Search/Review/Settings), per-tab stacks,
            │  custom BottomBar (hidden during review sessions / pushed pages)
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
| Bottom bar selection | One travelling indicator pill: `translateX` interpolated from the pager's own `position` (native-driven), plus per-item glyph and label cross-fades of the two tints | The pager's `position` is continuous while a finger drags it and while a non-adjacent tap walks the pill through the middle tabs, so one node keeps the pill and the pages in step. A colour interpolation would have forced the JS driver, so the tint change is two stacked copies cross-fading |

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
