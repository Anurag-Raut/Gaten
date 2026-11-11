# Portrait app, landscape-capable lookup overlay

The app's own UI is portrait only. The lookup sheet is **not** the app's UI: it is drawn in the
accessibility service's own `TYPE_ACCESSIBILITY_OVERLAY` window, so the activity lock does not reach
it and it keeps the landscape form it already had.

| Claim | Evidence |
|---|---|
| The app rotates today (baseline) | `before-02-app-landscape.png` — Home laid out across 2340×1080 |
| The app no longer rotates | `after-02-app-portrait-after-rotate.png` — same Home at 1080×2340 after a landscape request |
| The lookup overlay still renders landscape | `after-03-overlay-landscape.png` — the overlay over a frozen landscape frame |
| A real lookup result, in that landscape panel | `after-04-overlay-landscape-sheet.png` — 肉食 / 動物 with glosses |

## The change

One attribute, one file:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:label="@string/app_name"
    android:theme="@style/Theme.Gaten"
    android:screenOrientation="portrait"   <!-- added -->
    android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize|uiMode"
    android:windowSoftInputMode="adjustResize" />
```

Declared in the manifest rather than toggled at runtime: there is nothing to toggle. The lookup
overlay is a different window owned by a different component, so the lock cannot reach it by
construction — which is the whole reason this is a one-line change.

## Source and build

- Branch `fix/portrait-app-modal-landscape`, based on dev `b8a4b40`; working tree carries only the
  manifest edit (`AndroidManifest.xml` sha256 `ec3c2cacabee77eff03885ecf28a6881b0b7a81208401c2f547786628706d8d4`).
- Device: AVD `Gaten_Lookup_Review`, API 34 / x86_64, 1080×2340 @ 440 dpi, font scale 1.0,
  night mode on, Gaten accent.
- `./gradlew :app:assembleDebug --console=plain` — BUILD SUCCESSFUL (281 tasks).
  `node_modules/.bin/tsc --noEmit -p tsconfig.json` — clean (manifest-only change; run for completeness).
- APK (x86_64 debug) SHA-256: `86e5d19a9d46ad843ebc351d69520ac32ba953cf395a4d8028e7ac18b63164ca`.
  Debug embeds the bundle, so no Metro server was needed.
- Bundled JavaScript SHA-256: `226d3201e1f0ad047146d2be3326d56ad35588e5af95bf36ec90efccd732e333` —
  **identical before and after**, i.e. the change is manifest-only.
- Baseline APK (same tree with the attribute reverted) SHA-256:
  `e3e7d5c4a3ad921cca0cbd3faf85af3e6e718484539d5d8dfabd4e5d7f2ef983`.
- APK-level delta, read back with `aapt2 dump xmltree --file AndroidManifest.xml`:
  baseline `MainActivity` has **no** `screenOrientation`; the after APK has
  `screenOrientation(0x0101001e)=1` (`SCREEN_ORIENTATION_PORTRAIT`) on `MainActivity` only —
  `LauncherActivity` and the three activity-aliases are unchanged. The same holds for all three ABI
  APKs.

## Captures

| File | What it shows | SHA-256 (first 16) |
|---|---|---|
| `before-01-app-portrait.png` | baseline build, Home, portrait | `a3694557f865be98` |
| `before-02-app-landscape.png` | baseline build, Home, after a landscape request — **the app rotated** | `3bff02cb3587baaa` |
| `after-01-app-portrait.png` | locked build, Home, portrait | `bb97483b44c497bb` |
| `after-02-app-portrait-after-rotate.png` | locked build, after the same landscape request — **still portrait** | `0ff7f4f21b196b3e` |

Both pairs were captured the same way: `agent-device open com.gaten`, screenshot, then
`agent-device orientation landscape-left`, wait for the UI to settle, screenshot.

Corroborating framework state read on the locked build (terminal, not a screenshot):

- `dumpsys activity activities` → `MainActivity` `requestedOrientation=SCREEN_ORIENTATION_PORTRAIT`.
- After the landscape request, `dumpsys window displays` → `mDisplayRotation=ROTATION_0`, and the
  after screenshot is 1080×2340. On the baseline build the same sequence reached `ROTATION_90` and a
  2340×1080 screenshot.

## The lookup overlay in landscape

Captured on the second lease, with **no agent-device session open** — the adb-only runbook below.

| File | What it shows | SHA-256 (first 16) |
|---|---|---|
| `after-03-overlay-landscape.png` | the frozen landscape frame (Chrome on ja.wikipedia.org/wiki/ネコ) with the lookup overlay over it: the app's own **Circle or tap Japanese text** rail, the blue screen-edge outline and the freeze veil, laid out in the landscape window | `6648c4990a975113` |
| `after-04-overlay-landscape-sheet.png` | the **real result panel** in that window — word tabs 肉食 / 動物, headword 肉食, reading にくしょく・にくじき, `Common · noun`, `Meanings 2 of 2` and the first gloss | `4417443ca0d7bc9e` |
| `after-05-overlay-landscape.mp4` | a short in-panel interaction: switching the word tab to 動物 and back | `9409ae7eeebdb452` |

Same run, from the framework: `mDisplayRotation=ROTATION_90`; `mCurrentFocus=Window{…u0 com.gaten}`
(the overlay's own window) while it was up; `Enabled services` exactly
`com.gaten/com.gaten.app.GatenAccessibilityService` with `Bound services:{Service[label=Gaten, …]}` —
the service bound immediately once no automation session was open. The lookup itself is in the log:
`GatenOcr: Indexed 7 ML Kit lines in 482 ms`, then the sheet mounting with `words` for 肉食 and 動物.

Earlier on the first lease the same enable, driven through agent-device, never bound at all: the
accessibility service stayed in `Enabled services` while `Bound services`, `Binding services` and
`Crashed services` were all empty, with no service log line. That is the tool constraint below.

### Why the first lease could not take it: agent-device suppresses the service

Confirmed from primary sources, not inferred from behaviour:

1. agent-device drives Android through an **instrumentation APK**,
   `agent-device-android-snapshot-helper-0.21.31.apk` (`SnapshotInstrumentation`), whose disassembly
   connects `android.app.UiAutomation` (`getConnectedUiAutomation` → `getUiAutomation`).
2. That call resolves to the **no-argument** `Instrumentation.getUiAutomation()`
   (`()Landroid/app/UiAutomation;` in the helper's `classes.dex`), i.e. flags = `0`.
3. Android's own documentation for `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`
   (API 24, value `1`) reads: *"UiAutomation suppresses accessibility services by default. This flag
   specifies that existing accessibility services should continue to run, and that new ones may
   start."* Without it, an enabled service is not bound.
4. agent-device exposes no flag or mode for this: its `dist` has no Android-side handling of
   accessibility suppression (its only `suppress` hits are Apple-side), and no command in its help
   changes the UiAutomation flags.

That is exactly what was observed on the same AVD, with an agent-device session open:
`Enabled services:{com.gaten/com.gaten.app.GatenAccessibilityService}` while
`Bound services:{}`, `Binding services:{}` and `Crashed services:{}` stayed empty, with no service
log line and no crash. It is a tool constraint, not a Gaten defect — the production service was not
changed for it.

Corroboration: the repository's own successful overlay captures were taken with the automation session
**closed** — `docs/pr-evidence/anki-footer/README.md` step 2 begins *"Close the automation session,
enable `com.gaten/com.gaten.app.GatenAccessibilityService`, broadcast …"*.

### The adb-only runbook these captures came from

This is the route that has produced every overlay still in this repository, and the one used here. It
needs no agent-device session while the service must be bound. It is written out, ready to run, as
[`capture-overlay.sh`](capture-overlay.sh) — `sh capture-overlay.sh <serial> <absolute-apk-path>` —
with the state snapshot and the restore built in. The essentials, and the two checks that matter most
(the on-device APK hash, and `Bound services` being non-empty **before** the trigger):

```sh
D="adb -s <serial>"   # Gaten_Lookup_Review
# 0. record prior state: enabled_accessibility_services, accelerometer_rotation, user_rotation,
#    ui_night_mode, font_scale, shared_prefs/com.gaten_preferences.xml, files/BridgelessReactNativeDevBundle.js
# 1. install the after APK and verify it on device
$D install -r /abs/path/app/build/outputs/apk/debug/app-x86_64-debug.apk
$D shell sha256sum "$($D shell pm path com.gaten | sed 's/package://')"   # expect 86e5d19a…
# 2. isolate the bundle from any foreign Metro, then relaunch and confirm in logcat
#    (edit ONLY debug_http_host in com.gaten_preferences.xml to a dead port; set aside the cached
#     files/BridgelessReactNativeDevBundle.js) → "isMetroRunning(): Async result = false", "Loading JS Bundle"
# 3. enable the service and raise the trigger
$D shell settings put secure enabled_accessibility_services com.gaten/com.gaten.app.GatenAccessibilityService
$D shell settings put secure accessibility_enabled 1
$D shell dumpsys accessibility | grep -E "Bound services|Enabled services"
#    the Bound line must name OUR component — com.gaten/com.gaten.app.GatenAccessibilityService.
#    `Enabled services` proving it is listed proves nothing: the trigger is answered by a receiver
#    the service registers, so an unbound service ignores it silently.
#    floating button: app prefs gaten_settings.xml → <boolean name="floating_button" value="true"/>
#    (or raise the Quick Settings tile instead)
# 4. a landscape source screen with Japanese on it, then the trigger
$D shell am start -n com.android.chrome/com.google.android.apps.chrome.Main
#    type Japanese into the omnibox BEFORE step 3 — a UiAutomation connection suppresses existing
#    services too, so any agent-device session must be closed before the service is enabled, and
#    `dumpsys accessibility` must show a non-empty "Bound services" again after it is closed.
$D shell settings put system accelerometer_rotation 0 && $D shell settings put system user_rotation 1
$D shell am broadcast -a com.gaten.action.TRIGGER_LOOKUP -p com.gaten
$D exec-out screencap -p > docs/pr-evidence/portrait-app-lock/after-03-overlay-landscape.png
#    after-04-overlay-landscape-sheet.png is written ONLY when a real result sheet is raised — inspect
#    after-03, pass the tap coordinates of recognized Japanese, and the still is taken after the tap.
#    Without them the frozen-frame still is the whole capture and no file claims to be a sheet.
# 5. restore every value recorded in step 0, delete the trigger pref, and release
```

Two things make this fallback work where the agent-device route did not: the service is enabled and
bound with **no** UiAutomation connection in the process, and the trigger is the app's own broadcast
(`LookupTriggerReceiver`), which is a supported entry point — not an improvised one.

### Known risks that remain

- **The overlay window may be invisible to snapshots** — it is a `TYPE_ACCESSIBILITY_OVERLAY` window,
  so `snapshot`/`find` may report a sparse or unavailable tree. Screenshot-and-coordinate is the
  fallback, and it is the only route once the automation session is closed.
- **The trigger has two prerequisites** (Lookup mode *and* Floating button, or the Quick Settings
  tile). If nothing appears, check `dumpsys accessibility` for a non-empty `Bound services` first.
- **Chrome may raise a first-run dialog** before the omnibox is usable.
- **Re-enabling the service after a `force-stop` is not enough** — the process must be started
  *before* the service can bind, and the enable is cleared by force-stopping Gaten.

## Limits

- The first two stills establish the app-level lock; the last three establish the overlay's landscape
  form on this build. The result panel is one state (a meaning prompt with two matched words); the
  reading prompt, the search cover, the failure rail and the empty states were not re-captured here.
- The panel still is from the meaning prompt for 肉食 on a Wikipedia page. The dev-build LogBox banner
  that appears on a first warning was dismissed before the still, so it is clean.
- Nothing here exercises a physical device, other text scales, dark/light switching during the
  rotation, or the Quick Settings tile.
- No new tests were added: a manifest attribute is a config change, and the repo adds tests for
  behaviour, not for configuration.
- The debug APK's bytes are not reproducible between builds (zip/signing), so the hash above pins the
  APK that was installed, not the build recipe.
