# Gaten wordmark and launcher icon — rendered evidence

Rendered evidence for the finalized Gaten branding, taken from the **flat-background revision** of
the approved package (`gaten-brand-svg-flat-background-final.zip`, committed verbatim into
`src/rn/assets/branding/`). Home's heading is the approved wordmark artwork, and the launcher's
adaptive icon is the approved mark on the flat ground that revision names: one full-canvas solid
ground — no tile, border, bevel, shadow or graded backdrop — with the launcher's own mask applied on
top of it. The wordmark files are byte-identical to the earlier package; the icon files are not
(they previously drew a rounded tile on a page), so every launcher capture here was retaken against
this revision rather than carried over.

| Item | Value |
|---|---|
| Head under test | `74368d0` (`feat(branding): the approved Gaten wordmark on Home and the approved launcher icon`), on `2ca51e3` (the `dev` tip it was cut from) |
| Change | `src/rn/assets/branding/` (the revision), `src/rn/ui/home/GatenWordmark.tsx`, the five launcher vectors under `app/src/main/res/drawable*`, the four mipmaps' `<monochrome>` layers, the three `ic_launcher*` colour resources, `docs/Gaten_Ink_Design_Language_v1.md` |
| Build | `./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64`, `BUILD SUCCESSFUL` (266 tasks); the CI test task `./gradlew :app:testDebugUnitTest`, `BUILD SUCCESSFUL`; `npx tsc --noEmit` clean |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 299,869,532 B, SHA-256 `8ff17cfebb63c38e49138c283cd355e0de96f6b629c0f1f1185ef6ba123991db` |
| Device | AVD `Pixel_Safe` → `emulator-5554`, Android 14 (`sdk_gphone64_x86_64`), x86_64, 1080×2340 @ 440 dpi (393 dp wide), font scale 1.0 — `1 dp = 2.75 px` |
| Services | AnkiDroid not installed, WaniKani not connected — both in their default state |
| Driver | agent-device 0.21.31 — `open`, `find`, `press`, `swipe`, `home`, `screenshot`, `record`; taps go through UiAutomation |
| Settling | Every capture below passes a gate before it is kept: the screenshot is retaken until the flame beside *Daily streak* holds the selected accent's own primary **and** the wordmark holds the mode's ink (≥ 8,000 exact-ink pixels — a half-faded mark fails it) **and** the page holds the mode's background. No capture is a frame mid-transition. |

## What the captures show

**Header geometry is unchanged.** Comparing the pre-change capture
(`docs/pr-evidence/added-color-and-lookup-gloss/after-02-recent-lookups.png`, Inter 35/38 text) with
`02-home-light-indigo.png`: the search field's top edge is at **y = 360 px (130.9 dp)** in both. The
mark's ink box is 35.3 dp tall × 144.0 dp wide starting at x = 65 px (23.6 dp), against 26.2 dp ×
91.3 dp at x = 62 px (22.5 dp) for the text it replaces — the supplied file's own 16-unit
sidebearing, 1.29 dp at this size, so the mark's left edge sits 1.1 dp further in than the text's
did, still inside the 22 dp content inset and aligned with the search field below it.

**The mark takes the mode, never the accent.** In all fourteen captures the ink box holds exactly
`#17171A` on a `#FDFDFD` page (light) or `#F5F5F6` on `#101114` (dark), at the same pixel (y 203,
x 65) in every one, with 17,587 exact-ink pixels each. The accent really is different in each of
those same captures — the flame and due count carry the accent's own primary: `#4338CA`, `#0B4FB8`,
`#6D28D9`, `#115E59`, `#166534`, `#9F1239`, `#000000` in light and `#A5B4FC`, `#93C5FD`, `#C4B5FD`,
`#5EEAD4`, `#6EE7B7`, `#FDA4AF`, `#FFFFFF` in dark, 1,315–1,316 flame pixels each — so the mark's
constancy is measured, not assumed. `accents-contact-sheet.png` is rebuilt from these captures and
each of its fourteen cells was re-measured on the composite itself: 0 mismatches.

**The launcher icon is the approved mark on the flat ground.** In the app drawer the disc holds
exactly two colours: `#FFFFFF` (15,766 px) with `#000000` ink (5,067 px) for the app's Light entry,
and `#101114` (15,763 px) with `#F5F5F6` ink (5,067 px) for its Dark entry. Nothing else is drawn —
no tile edge, border, ring or shadow; the only other colours in the box are the launcher's own
`#D9DADD` drawer background showing outside the mask and its antialiasing at the disc's rim.
Switching Appearance ▸ Theme from Light to Dark moved the enabled component from
`com.gaten.app.LauncherIconLight` to `com.gaten.app.LauncherIconDark` (`dumpsys package com.gaten`),
so `LauncherIcon.kt`'s alias switch still drives the new art. The same two-colour check on the
supplied files rendered directly (512 px): light `#FFFFFF` 225,196 px + `#000000` 35,002 px with all
four canvas corners the ground, dark `#101114` + `#F5F5F6` likewise — no border radius anywhere.

**The glyph sits inside the adaptive safe circle, whole.** The revision draws the mark as one
flat-rule=evenodd path placed by `translate(206, 238.738) scale(0.771022)`; its ink box on the
1024-unit canvas is x 210.6–796.6, y 243.4–756.9, centred on (503.6, 500.1). The drawable carries
that path with its numbers untouched under a group at 0.064407, moved to (54, 54): the placed mark
is 48.95 × 42.90 dp and its farthest point — the outer dakuten tip — is exactly **32.5 dp** from the
centre of the 108 dp canvas, inside the 66 dp safe circle. `launcher-icon-adaptive-masks.png` draws
that same path under the circle, squircle, rounded-square and square masks: the letterform and both
dakuten survive every one.

**Width.** `03-home-light-indigo-320dp.png`, `-340dp.png` and `-220dp.png` are the same Home at
320 dp, 340 dp (where the content inset drops to 16 dp) and **220 dp** — the narrowest resizeable
task this platform allows (`mMinSizeOfResizeableTaskDp = 220` in `dumpsys window displays`). The mark
is a fixed 144.0 dp wide, so at that floor it still has 220 − 32 = 188 dp of content width to sit
in; no max-width rule is needed and none was added.

## Files

| File | What it shows |
|---|---|
| `before-after-home-header.png` | The Home header before (Inter text) and after (wordmark), cropped to the same band |
| `02-home-light-{indigo,blue,violet,teal,green,rose,black}.png` | Home in light mode with each of the seven accents selected |
| `02-home-dark-{indigo,blue,violet,teal,green,rose,black}.png` | The same seven in dark mode (the colourless accent is named *White* there) |
| `accents-contact-sheet.png` | The fourteen captures as one sheet, light row over dark row, each cell re-measured |
| `03-home-light-indigo-320dp.png`, `-340dp.png`, `-220dp.png` | Home at those widths (`wm size` 880×2340, 935×2340, 605×2340) |
| `narrow-width-headers.png` | The 220 dp, 320 dp, 340 dp and 393 dp headers side by side |
| `04-search-enter-exit.mp4` | Focusing the search field (brand and dashboard leave) and Android Back out of search (they return) |
| `05-launcher-drawer-light.png`, `05-launcher-drawer-dark.png` | The app drawer with the app's Light and Dark launcher entries enabled |
| `launcher-icon-tiles.png` | Those two icons at 4×, next to the colour counts above |
| `launcher-icon-adaptive-masks.png` | Static: the drawable's own path under the four launcher masks |
| `06-launcher-themed-icons-unavailable.png` | The drawer as this emulator leaves themed icons: untinted, whatever the preference says — see below |

## Not exercised

- **Themed (monochrome) icons.** This emulator never renders them: the launcher's *Wallpaper & style*
  page listed *Themed icons* in its accessibility tree but never drew the switch on screen
  (long-press the home screen, then scroll and swipe the page), and the app drawer stayed untinted
  with the preference `themed_icons` absent, `false` and `true`, forcing
  `com.google.android.apps.nexuslauncher` to restart between those states (its own preference file
  already had it `true`). What is covered instead: the `<monochrome>` layers now point at
  `ic_launcher_monochrome.xml`, built from the supplied `gaten-icon-monochrome-transparent.svg`, and
  that same path is shown whole under every launcher mask above.
- **Font scale.** The wordmark is artwork and deliberately does not scale with the system font size,
  so 130 % / 160 % captures are not part of this evidence. Everything around it still scales.
- **The Quick Settings tile, the capture notification and the floating bubble.** They keep the
  earlier squared-stroke *G* (`app/src/main/res/drawable/ic_qs_lookup.xml`); that art was outside
  this change's scope and is left as it was.

## Notes

- The dark ground is `#101114`, the flat ground `gaten-icon-dark.svg` draws, with its `#F5F5F6` ink;
  the light ground is `#FFFFFF` with `#000000` ink. Both are single flat colours in
  `values-night/ic_launcher_background.xml` and `values/ic_launcher_theme.xml` — no bitmap, tile,
  stroke or shadow is part of the icon art.
- Every capture here was taken against the flat-background revision. An earlier pass — the
  previous package's rounded tile, and a contact sheet built from a sweep whose accent taps had not
  applied — was replaced rather than carried over: the stills above are the retake, each gated as
  described, and the composite is rebuilt from them and verified cell by cell.
- `Gaten_Evidence_Overlay` (1.5 GB RAM, `hw.gpu.enabled = no`) cannot carry this capture: the
  296 MB debug build drives it into a system-server ANR that survives a reboot. Its `hw.ramSize` was
  temporarily raised to 4096 while diagnosing that and has been restored to its original values
  (`config.ini` `1536M`, `hardware-qemu.ini` `2048`); the captures above were taken on `Pixel_Safe`,
  the device PR #39's evidence also used. No application setting differs between the two.
- Captures are full-resolution 1080×2340 PNGs (605×2340 at 220 dp); the sheets are composites built
  from them.
