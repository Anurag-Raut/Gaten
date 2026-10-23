# Theme-aware launcher icon and light/dark fade — device captures

**Historical captures:** these screenshots show the earlier, reversed icon colors. The current
mapping is Dark → black background / white G, Light → white background / black G, and System →
the device's light or dark theme. New icon screenshots have not been captured for this correction.

Captured on `emulator-5556` (Android 14 / API 34, 1080×2340, density 440, font scale 1.0) from
`app/build/outputs/apk/debug/app-x86_64-debug.apk` built from this branch
(`64c5316`, "Add theme-aware launcher icons and fade transitions").

Two things were verified: the home-screen icon follows the **in-app** theme, and switching
light/dark fades instead of jumping. No review card was graded and no account was connected.

| File | What it shows |
|---|---|
| `launcher-icon-matrix-day.png` | Launcher icon with the **device in day mode** — app set to Dark, System, Light |
| `launcher-icon-matrix-night.png` | Launcher icon with the **device in night mode** — app set to Dark, System, Light |
| `app-appearance-light-vs-dark.png` | The Appearance screen itself in both themes (accent `Indigo`, each swatch resolved in its own mode) |
| `fade-light-to-dark.gif` | The light↔dark switch, animated (~10 s, 12 fps) |
| `fade-light-to-dark.mp4` | The same clip at full quality (h264, 340 px wide) |
| `fade-filmstrip-light-to-dark.png` | Eight frames across one light→dark switch, from 0 ms to +300 ms |

## What the captures verify

**The icon follows the app's choice, not only the phone's.** The icon shots are cropped from the
Pixel launcher's app drawer, which reads each entry's own `android:icon`:

| App theme | Device day | Device night |
|---|---|---|
| `Dark` | white ground, black G | white ground, black G |
| `System` | black ground, white G | **white ground, black G** |
| `Light` | black ground, white G | **black ground, white G** |

The two bold cells are the point of the change. With the phone in night mode, `System` follows the
phone (white ground), while an explicit `Light` keeps the black ground — the app's own choice wins
over the device setting. Before this change the icon only ever followed the phone.

**Exactly one launcher entry is enabled at a time.** `cmd package query-activities --brief -a
android.intent.action.MAIN -c android.intent.category.LAUNCHER` returns one Gaten component, and it
matches the stored theme (`theme_mode` in `shared_prefs/gaten_settings.xml`):

| App theme | Enabled launcher component |
|---|---|
| `Light` | `com.gaten/.app.LauncherIconLight` |
| `Dark` | `com.gaten/.app.LauncherIconDark` |
| `System` | `com.gaten/.app.LauncherIconSystem` |

**The switch fades.** `fade-filmstrip-light-to-dark.png` steps one light→dark switch at 60 fps
sampling: the full-screen mean luminance walks 246 → 236 → 217 → 190 → 115 → 88 → 68 → 51 → 34 → 22
rather than stepping 246 → 21, and the +100 ms frame shows both palettes blended in the same frame.
The fade completes in roughly 200–260 ms. The `Appearance` screen's own rows recolor with it,
including the `Black` swatch relabelled `White` in dark mode.

## Caveats

- The launcher caches the rendered icon. Changing the device's night mode does **not** repaint an
  already-rendered `System` icon, and re-tapping `System` while it is already selected changes
  nothing, so no repaint happens. The night-mode `System` capture above needed a real entry change
  (Dark → System) to force the re-render. On a device, `System` therefore catches up when the
  launcher reloads its icon cache — not necessarily the instant the phone's theme changes.
  `Light`/`Dark` do repaint immediately, because they swap to a different component.
- Not covered: other launchers (moto/Redmi/OEM), the reduced-motion path (it was not exercised —
  the fade above is the normal path), and the lookup overlay / Quick Settings tile.
- The Pixel launcher draws its own ring around the focused/recent dock icon; it is launcher chrome,
  unrelated to the app's icon layers. The drawer crops are free of it.

## How it was captured

```bash
# Debug builds embed the JS bundle, so no Metro server is needed. Install without streaming,
# otherwise the installer skips extracting libworklets.so / libreanimated.so / libgesturehandler.so
# / librnscreens.so and the app dies on startup with SoLoaderDSONotFoundError.
adb -s emulator-5556 push app/build/outputs/apk/debug/app-x86_64-debug.apk /data/local/tmp/g.apk
adb -s emulator-5556 shell pm install -r -t /data/local/tmp/g.apk
adb -s emulator-5556 shell am start -n com.gaten/com.gaten.app.MainActivity

# Day / night for the matrix.
adb -s emulator-5556 shell cmd uimode night no    # or: yes

# The theme was set through the UI (Settings → Appearance → System/Light/Dark), which is what
# writes shared_prefs/gaten_settings.xml and re-enables the matching launcher alias.

# Recording the fade.
adb -s emulator-5556 shell screenrecord --size 720x1560 --bit-rate 12000000 /sdcard/transition.mp4

# Where each state lived at capture time.
adb -s emulator-5556 shell run-as com.gaten cat shared_prefs/gaten_settings.xml
adb -s emulator-5556 shell cmd package query-activities --brief \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -i gaten
```

The shipped ground colours come from the packaged resources, which `aapt2 dump resources` confirms
are the intended ones:

```
resource 0x7f05006c color/ic_launcher_background
  () #ff000000          # System, device in day mode
  (night) #ffffffff     # System, device in night mode
resource 0x7f05006d color/ic_launcher_dark_background
  () #ffffffff          # explicit Dark, fixed in both modes
resource 0x7f05006e color/ic_launcher_light_background
  () #ff000000          # explicit Light, fixed in both modes
```
