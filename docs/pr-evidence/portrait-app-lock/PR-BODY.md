## Changes

Gaten's own UI is portrait only; the lookup sheet stays landscape-capable.

`MainActivity` gains `android:screenOrientation="portrait"`. One attribute in one file — the app had
no orientation declaration at all, so it followed the sensor and rotated into a landscape layout that
none of its screens are designed for (the baseline still below shows Home stretched across 2340×1080).

The lock is deliberately on the activity and nowhere else. The lookup sheet is not the activity's UI:
it is mounted by the accessibility service into its own `TYPE_ACCESSIBILITY_OVERLAY` window
(`OverlayWindowController`), which follows the display rather than this activity's request. That
window already has a landscape form (`LookupSheetApp`'s `landscape` branch; held sideways the sheet is
one full-height panel). So "app portrait, modal landscape" is what the architecture already gives —
no runtime toggle, no second orientation declaration, and no global rotation change.

Not changed: `LauncherActivity`, the three launcher activity-aliases, `configChanges`, and every JS
and Kotlin source file. The bundled JavaScript hash is byte-identical before and after, which is how
the manifest-only claim is checked.

## Visual evidence

- **UI changed:** Yes — the app no longer rotates, and the lookup overlay keeps its landscape form.
- **Screenshots:**
  - App rotation, before/after: `before-01-app-portrait.png` and `before-02-app-landscape.png` (baseline
    build, before and after a landscape request — it rotated) against `after-01-app-portrait.png` and
    `after-02-app-portrait-after-rotate.png` (this build, same request — it stays portrait).
  - The lookup overlay in landscape: `after-03-overlay-landscape.png` — the frozen landscape frame
    (Chrome on ja.wikipedia.org/wiki/ネコ) with the overlay over it, rail and screen-edge outline laid
    out in the landscape window.
  - A real result in that landscape panel: `after-04-overlay-landscape-sheet.png` — word tabs
    肉食 / 動物, headword 肉食, reading にくしょく・にくじき, `Common · noun`, `Meanings 2 of 2` and the
    first gloss, with the selected source line outlined in the frozen frame.
  All six are in `docs/pr-evidence/portrait-app-lock/`.
- **Interaction, navigation or motion:** `after-05-overlay-landscape.mp4` — a short in-panel
  interaction in landscape, switching the word tab to 動物 and back. Rotation itself is a state change
  rather than motion, so the stills carry it; the framework state behind them is in the README
  (`requestedOrientation=SCREEN_ORIENTATION_PORTRAIT`, and `mDisplayRotation=ROTATION_0` after the
  request against `ROTATION_90` on the baseline; `ROTATION_90` with `mCurrentFocus` on Gaten's own
  overlay window while the lookup was up).
- **Source:** based on `dev` `b8a4b40` plus this branch's manifest change; AVD `Gaten_Lookup_Review`
  (API 34, x86_64, 1080×2340 @ 440 dpi, font scale 1.0, night mode);
  `./gradlew :app:assembleDebug --console=plain`; APK x86_64 debug sha256
  `86e5d19a9d46ad843ebc351d69520ac32ba953cf395a4d8028e7ac18b63164ca` — read back **on the device**
  before the captures — and baseline APK
  `e3e7d5c4a3ad921cca0cbd3faf85af3e6e718484539d5d8dfabd4e5d7f2ef983`. The overlay captures ran with no
  automation session open (see the README: agent-device's UiAutomation suppresses accessibility
  services), the packaged bundle isolated from any dev server by logcat proof, and the service
  confirmed bound before the trigger.
- **Assets:** `docs/pr-evidence/portrait-app-lock/` — the six captures, a README with the device, build,
  APK and bundle hashes, the framework state, the tool constraint and the limitations, and
  `capture-overlay.sh`, the adb-only runbook the overlay captures came from.

## Verification

- **Steps and result:** installed the baseline APK, opened Home, requested landscape-left → the app
  rotated (2340×1080). Installed this build, opened Home, requested landscape-left → the app stayed
  portrait (1080×2340, `mDisplayRotation=ROTATION_0`, `requestedOrientation=SCREEN_ORIENTATION_PORTRAIT`).
  Then, with the device in landscape on a Japanese page, the accessibility service was enabled and
  confirmed bound, the app's own trigger was broadcast, and the overlay plus a real dictionary result
  were captured in the landscape window.
- **Checks:** `./gradlew :app:assembleDebug --console=plain` — BUILD SUCCESSFUL (281 tasks).
  `node_modules/.bin/tsc --noEmit -p tsconfig.json` — clean. APK-level manifest delta read back with
  `aapt2 dump xmltree` on all three ABI APKs: `screenOrientation=1` on `MainActivity` only, absent in
  the baseline APK. On-device APK hash checked before the overlay captures. Not run: unit and
  instrumented tests (a manifest attribute changes no behaviour those tests cover).

## Problems

- Android cannot rotate a dialog independently of its activity, so the in-app React Native `Modal`
  dialogs (`DialogShell`, the Anki deck `ChoiceDialog`) are portrait with the app. If the request
  meant those dialogs rather than the lookup overlay, it is not satisfiable on Android.
- The overlay captures were taken with adb rather than agent-device: agent-device's UiAutomation
  connection suppresses accessibility services, so the service never binds while a session is open.
  That is a tool constraint, documented in the README; no production code was changed for it.
- Debug APK bytes are not reproducible between builds, so the hash pins the installed APK, not the
  recipe.
