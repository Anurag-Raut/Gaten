# Anki cards settings — layout aligned with the sibling settings pages

Evidence for `fix/anki-cards-settings-layout`: the settings page for Anki cards brought onto the same
rhythm as `WaniKaniSettingsScreen` and the shared `SettingsPageScaffold` pages — scroll insets, group
body padding, the permission action's button treatment, and two stacked-text gaps. Style-only: no
state, handler, navigation, permission, persistence or accessibility change.

## Summary

| | Result |
|---|---|
| Bar → first section label | **410 px → 366 px** (149.1 dp → 133.1 dp) — 16 dp higher, matching WaniKani settings exactly |
| Permission button width | **216.0 dp → 348.0 dp**, i.e. the full content column (392 − 2×22), equal to the app's other primary actions |
| Permission button inset | left 22.5 dp unchanged; **right 154.2 dp → 22.2 dp** |
| Paragraph → button gap | **2.9 dp → 14.9 dp** (was flush) |
| Button height | 52.0 dp → 52.0 dp (unchanged, `Button`'s own minimum) |

## Provenance

| Item | Value |
|---|---|
| Base | `b8a4b40` (`origin/dev`, merge of #74) |
| Change | `src/rn/ui/settings/AnkiSettingsScreen.tsx`, +16 / −13, **uncommitted at capture time** |
| Capture head diff hash | `git diff \| sha256sum` = `96edac65eb44811463c333694d8f0a8ed9c652547b37f4908e06d7a33d74217e` |
| Baseline APK | `app-x86_64-debug.apk`, sha256 `b78f000b6c96e64c67696cf05f18f03104e2781ff5d2d69dc3c314b7b5a27324`, built from a pristine detached worktree of `b8a4b40` |
| After APK | `app-x86_64-debug.apk`, sha256 `255c4d24132726bbac176242dd301073f10f868445f15182ed8b0cb0e3d305d1`, built from the modified working tree |
| Build | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64 --offline` — BUILD SUCCESSFUL for both; `tsc --noEmit` exit 0 |
| Device | `emulator-5556` = AVD **`Gaten_Lookup_Review`** (resolved by name, never assumed), Android 14 x86_64, 1080×2340 @ 440 dpi → 392 dp, font scale 1.0 |
| Theme | app theme `System`; captured light and dark via system night mode |

### Which JS actually rendered — the important part

The debug host is created with `useDevSupport = BuildConfig.DEBUG` (`MainApplication.kt`), and
`ReactHostImpl` (RN 0.86, `runtime/ReactHostImpl.kt:1145-1180`) picks the bundle loader in this order:

1. `devSupportManager.bundleFilePath != null` → the **cached dev bundle** (`files/BridgelessReactNativeDevBundle.js`) — wins outright;
2. else, if `useDevSupport && allowPackagerServerAccess`, it probes `isMetroRunning`; **if a dev server answers, it loads from it**;
3. else → the packaged `assets/index.android.bundle`.

So an offline APK only renders its own bundled JS when no dev server answers *and* no stale dev bundle
is cached. Both were eliminated for these captures:

- `debug_http_host` set to a **dead port** (`10.0.2.2:8089`). This key is read from
  `PreferenceManager.getDefaultSharedPreferences` → `shared_prefs/com.gaten_preferences.xml`
  (`PackagerConnectionSettings.kt:22,35,79`) — **not** `ReactNativeDevPrefs.xml`, which is what the JS
  side writes and where an earlier attempt was wrongly written.
- The cached dev bundle was deleted.

Logcat from the captured build:

```
W BridgelessReact: ReactHost{0}.isMetroRunning()
W ReactNative: The packager does not seem to be running … Failed to connect to /10.0.2.2:8089
W BridgelessReact: ReactHost{0}.isMetroRunning(): Async result = false
W BridgelessReact: ReactHost{0}.getOrCreateReactInstanceTask(): Loading JS Bundle
```

with `files/BridgelessReactNativeDevBundle.js` absent afterwards. A Metro dev server belonging to
**another workspace** (`/home/Qui/.paseo/worktrees/1t28llpl/stiff-panther`) was listening on host
`8081` throughout and was deliberately left running and untouched. An earlier capture pass made
before that key was corrected did fetch from that server and was **discarded**, not published.

### On-device install verification

The installed `base.apk` was hashed on the device and compared with the expected APK:

| Phase | On-device `base.apk` sha256 | Expected | |
|---|---|---|---|
| Baseline | `b78f000b6c96e64c67696cf05f18f03104e2781ff5d2d69dc3c314b7b5a27324` | baseline `b78f000b…` | **exact match** |
| After | not recorded at capture time | after `255c4d24…` | audit trail only — see Limitations |

Installs used `adb install -r <absolute path>` (never uninstall, never `pm clear`, never `-d`), and app
data was proven preserved: the `run-as com.gaten find . -type f` inventory and the
`shared_prefs` contents were identical before and after every install.

## Captures

`needsPermission` and `ready`, light and dark, before and after — eight stills, same device, theme and
font scale:

| State | Theme | Before | After |
|---|---|---|---|
| `needsPermission` | dark | `before-01-needs-permission-dark.png` | `after-01-needs-permission-dark.png` |
| `needsPermission` | light | `before-02-needs-permission-light.png` | `after-02-needs-permission-light.png` |
| `ready` | light | `before-03-ready-light.png` | `after-03-ready-light.png` |
| `ready` | dark | `before-04-ready-dark.png` | `after-04-ready-dark.png` |

Steps: `adb install -r` the expected APK by absolute path → launch → Settings tab → **Anki cards** row →
capture. The installed `base.apk` was hashed on device and compared with the expected APK before the
**baseline** phase's captures; for the after phase it was not recorded until after those four stills —
see Limitations. The permission states were produced with `pm grant` / `pm revoke` of
`com.ichi2.anki.permission.READ_WRITE_DATABASE` and the themes with the system night mode. **Nothing was
pressed except navigation** — no Save, no switch toggle, no deck selection, no export, no grading, and
the draft was never committed.

## Restored afterwards

| Setting | Original | Restored |
|---|---|---|
| AnkiDroid DB permission | not granted | not granted (`pm revoke`) |
| System night mode | dark | dark |
| `accelerometer_rotation` / `user_rotation` | `0` / `0` on arrival; ROOT's recorded original `1` / `0` | `1` / `0` |
| `font_scale` | 1.0 | 1.0 |
| `debug_http_host` (both prefs files) | `10.0.2.2:8081` | `10.0.2.2:8081` |

## Limitations

- **The after phase's on-device APK hash was not recorded at capture time.** The instruction to hash
  before each phase arrived after those four stills were taken. Their provenance is an audit trail: the
  after APK was installed with `adb install -r` (Success), **no other install occurred between that and
  the captures** (the next install was the baseline, which was hash-verified), and the logcat above
  proves the packaged bundle was live in that window. Only the baseline phase is hash-verified before
  its captures; no universal claim is made for both phases.
- `files/BridgelessReactNativeDevBundle.js` (RN's dev-bundle cache, not app data) was deleted to force
  the packaged bundle; RN regenerates it on the next fetch from a dev server.
- The `ready` stills carry a faint text-selection highlight under *Edit front, back, and styling* — a
  device selection artefact, not app UI.
- Not captured: the `notInstalled` state, 130 %/160 % text scale, narrow widths, landscape, other
  accents, and TalkBack. No automated tests were added (style-only change); the existing suites were
  not run.
