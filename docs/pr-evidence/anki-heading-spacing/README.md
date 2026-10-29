# Anki deck picker — heading → caught-up notice spacing

TickTick task `6ae331d2717ab2bce9f1ac08`.

One style value in the all-clear state of the Anki deck picker:
`src/rn/ui/review/AnkiDeckPicker.tsx` — `caughtUp.marginTop: -6` → `spacing.m` (12). The heading,
the notice's own bottom margin, the type scale and the artwork are untouched.

## Provenance of the captures in this directory — read this first

| | |
|---|---|
| Source | commit `27009d2` — the fix, on `origin/dev` `7e02486` |
| Build | `./gradlew :app:assembleDebug` from that commit, `BUILD SUCCESSFUL in 3m 5s` (bundled JS, no Metro); `tsc --noEmit -p tsconfig.json` exit 0 |
| APK | `app-x86_64-debug.apk`, 294,648,526 B, SHA-256 `2b06c34a0f74d17dfc29de0382fc36893f1836619dbe583988d50e00974d2be2`; installed with `adb install -r` and the on-device `base.apk` hashed to the same value |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, system font scale 0.94, Gaten theme `System` (light) |

`dev` reworked this screen's header and insets (a shared, left-aligned app bar replacing the locally
wrapped `SubHeader`), so the capture below is the first one taken on a build that has both that
header and this fix.

Copies in this directory, SHA-256:

| File | SHA-256 |
|---|---|
| `anki-all-clear-fixed.png` | `f43384c1d2de0091eff75806fd8d467a7e13c0650aa78b7bd7f4160aa90d4678` |
| `anki-all-clear-arrival.mp4` | `35c3a42d4d539ab3347ed78f59cedd5798cfe8b3eebbe24765d8de8b03fc52d6` |
| `fixture-state-before.txt` | `459046d6442b9974e2012e66be5f548075913c27e35567c280534e42f56e6f1c` |
| `fixture-state-after-restore.txt` | `53fd9397b1ccf412598b392fb4e3c6cd3ffe1589cbefad2b4f84c81ffdfe92da` |

## The fix, measured

Ink-band measurement of the gap from the `Choose a deck` baseline to the top of the notice's first
line, on `anki-all-clear-fixed.png` (1080 px wide at 440 dpi = 2.75 px/dp):

| | Gap |
|---|---|
| Reporter's screenshot of the defect (1272 px wide, 3.5 px/dp) | 24 px = **6.9 dp** |
| This head — `27009d2`, APK `2b06c34a…` | 68 px = **24.7 dp** |

The earlier combined build measured the same 68 px, so dev's header rework did not move this gap: the
change is still worth 24.7 dp on the rebased head. The header band → heading band reads 181 px here
against 184 px on that build, which is the new shared app bar's own rhythm rather than anything this
change touches; nothing else on the screen changed shape.

`-6` was also off the spacing ladder (`docs/Gaten_Ink_Design_Language_v1.md` §4). `spacing.m` (12)
lands the gap at ≈ 25 dp, inside the app's own title→content rhythm (the shared `ScreenSubtitle` uses
`marginTop: 11`).

`anki-all-clear-arrival.mp4` is Home → Anki card → deck picker arriving at the fixed state.
`adb screenrecord` encodes a frame only when the screen changes, so the clip has few frames; the still
carries the measurement.

## Fixture and restore proof

The emulator's AnkiDroid collection had nothing in the all-clear state (2 new cards due), so the two
synthetic fixture cards were suspended for the capture and then unsuspended.

| File | Content |
|---|---|
| `fixture-state-before.txt` | cards `1792817301586`, `1792820578043`, deck `Gaten`, type 0, queue 0, due 1 and 2; revlog 0 |
| `fixture-state-after-restore.txt` | after restore: the same rows, revlog 0 |

The two files' card lines (`id, did, ord, type, queue, due, ivl, reps, lapses`) are identical, so the
card scheduling and review state is restored. No full-file hash pair was taken — AnkiDroid rewrites
the collection in place, so the recorded rows and the revlog are the evidence. The app itself
confirmed the restore: Home read `Anki, Connected, 2 reviews due` again.

The two dumps are the ones written by the earlier combined-build run (the collection is the same on
either build); the suspension and restore were repeated for this head's capture and pulled again,
returning the same rows and the same revlog count, with `2 reviews due` back on Home.

The fixture deck is named `Gaten` where the reporter's own screenshot showed `Default`; the
composition (heading, notice, `Available decks`, one cleared row) is the same.

## Not exercised

- The picker's other states (decks with cards due, loading skeleton, filtered-empty, failures); only
  the all-clear branch renders the element this change touches.
- Other densities, display sizes and font scales; landscape.
