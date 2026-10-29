# Settings switch on-state contrast — evidence

Rendered evidence for the checked switch colours in `SwitchRow`
(`src/rn/ui/settings/settings-components.tsx`), the shared row every settings switch uses.

## Build and source under test

| Item | Value |
|---|---|
| Source commit (code) | `10e5aae` — `fix: give the settings switch a visible on-state thumb` |
| Build tree | `a9fe20f6f7121cb026b96ec2eaa4a66f54ef9700`, rebased on `dev` `7e02486`; the later evidence commit changes no code |
| Build | `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL in 3m 6s, 2026-10-29 18:41 |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 294,648,866 B |
| APK SHA-256 | `c92b95463ac02df68b54ebcd82f7af73da2730eb9429ee8718007a690a042726` |
| Installed | `adb install -r` 2026-10-29 18:47:21, app data preserved; on-device `/data/app/…/base.apk` SHA-256 is the same value |
| Embedded bundle | `assets/index.android.bundle` in the APK is byte-identical to the one Gradle generated from this tree (`a6e753de13f0ea3a4e6ef81e8aa2922ddf8cf85831e95b12327fcd95695819d1`); the debug variant bundles its own JS (`debuggableVariants.set(emptyList())`), so no Metro server was used |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, system font scale 0.94 |
| Theme | Gaten `System`; the emulator's own night mode was switched under the app (`adb shell cmd uimode night yes|no`) |
| State setup | Lookup mode follows Gaten's accessibility service being connected: On while the service is bound, Off while it is not |

## Files

| File | State |
|---|---|
| `05-lookup-toggle-on-light.png` | Screen lookup, light — **Lookup mode On** |
| `06-lookup-toggle-off-light.png` | Screen lookup, light — **Lookup mode Off** |
| `07-lookup-toggle-off-dark.png` | Screen lookup, dark — **Lookup mode Off** |
| `08-lookup-toggle-on-dark.png` | Screen lookup, dark — **Lookup mode On** |

Every still carries three switch rows at once: Lookup mode (the row under test), Floating button
(**On**) and Quick Settings tile (**Off**), so the on and off treatments of the same component sit
in one frame.

## Measured on the stills (1080 px wide, 2.75 px/dp)

Sampled on each row's own centre line:

| Still | Lookup mode thumb | Lookup mode track | Page behind |
|---|---|---|---|
| `05` light On | `rgb(66,55,198)` ≈ `#4338CA` `accent` | `rgb(238,242,255)` = `#EEF2FF` `accentSubtle` | `#FDFDFD` |
| `06` light Off | `rgb(128,128,137)` = `#83838C` `iconSecondary` | `rgb(245,245,247)` ≈ `#F6F6F8` `surfaceVariant` | `#FDFDFD` |
| `07` dark Off | `rgb(141,141,150)` = `#909099` `iconSecondary` | `rgb(27,28,33)` = `#1B1C21` `surfaceVariant` | `#101114` |
| `08` dark On | `rgb(162,176,247)` ≈ `#A5B4FC` `accent` | `rgb(49,46,129)` = `#312E81` `accentSubtle` | `#101114` |

The reported bug is `08`: the thumb that used to be near-black on a lavender track is now the accent
on the accent's quiet container, and it reads against the `#101114` page it reaches over. The off
states are the shipped behaviour, unchanged: the secondary-icon grey thumb on `surfaceVariant`.

## Superseded

The four files here replace the copies taken earlier from the combined dirty build on the old base
`7d12ac4`; those are no longer the evidence for this branch.

## Not exercised

- The disabled state (the Quick Settings tile row while a tile request is in flight is not shown).
- The other six accents (Blue, Violet, Teal, Green, Rose, Black).
- The Appearance (Sounds) and Anki (deck row) switches; they share `SwitchRow` with these rows.
- Landscape and large-type rendering of switch rows.
