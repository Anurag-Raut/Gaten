# Lookup sheet — landscape full height, in-flow Anki action, dismissal

Rendered evidence for the landscape changes in `src/rn/ui/lookup/LookupSheetApp.tsx`, captured from
**this branch's own build**.

| Item | Value |
|---|---|
| Branch / head | `fix/lookup-landscape-fullheight` @ `1b7b2b6b99bc262e7db487c4f6d631aa875fa5c7` (base `dev` `7e02486e346a164d38443f61f1055ce6e96d811b`) |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 294,648,986 B, SHA-256 `d24568eabbf66253fc0a2a40f8df99d8d262101b802b724ca23e9e45fb570c46` — installed with `adb install -r` (app data preserved); the on-device `/data/app/…/base.apk` re-hashed to the same value in the same session |
| JS inside that APK | `assets/index.android.bundle` SHA-256 `493e14d9…`, carrying this change (the `inlineFooter` marker it adds is present here and absent from the pre-fix baseline bundle) |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, font scale 0.94, light theme, portrait start |
| Method | `adb` only — broadcast trigger, `input tap`/`swipe`, `screencap`, `screenrecord`, `logcat -s Gaten`. No agent-device/UiAutomation helper attached, and `dumpsys accessibility` showed the service bound throughout |

## The fixture

Gaten's own Home → **Recently looked up** → 猫 word detail: no typing, so no search-history write.
That screen is the frozen frame; the sheet was opened by tapping the indexed readings line
`ねこ・ネコ`.

Note for anyone re-running this: on this fixture ML Kit indexed 7 lines in landscape and the
**isolated 猫 glyph was not among them**, so a tap on the glyph itself reports "No text here" and the
readings line is the tap target. (In portrait the same screen indexes the 猫 line, which is what the
portrait still used.)

## Measured panel geometry (from these captures, 2.75 px/dp)

| | Portrait (`01`) | Landscape (`02`, `03`) |
|---|---|---|
| Panel x | 0 … 1079 (full width) | 400 … 1939 — 1540 px = 560 dp, centred |
| Panel y | 701 … 2339 (the 70 % portrait snap) | **0 … 1079 — the full window height** |
| Screen beside the panel | none | 145 dp gutters, showing the frozen frame |

The landscape panel starting at y=0 is the measured consequence of this window reporting
`insets.top = 0`: the panel fills the height it is given and there is no inset to reserve — exactly
what `docs/Gaten_Ink_Design_Language_v1.md` §8.5 now states.

## What each capture shows

| File | What it shows |
|---|---|
| `01-portrait-pinned-anki.png` | portrait, entry 猫: the Anki deck row and the action are pinned at the panel's bottom without scrolling — portrait behaviour, unchanged by this change |
| `02-landscape-fullheight.png` | landscape: the panel fills the window height, keeping the centred 560 dp column; word tabs, 猫, readings and meanings sit inside the scroller |
| `03-landscape-anki-inline.png` | the same sheet after two swipes: the content ends with the **Anki deck row + action as the last content**, with the panel's bottom clear below them |
| `04-landscape-scroll.mp4` | the recording behind `02` → `03` (2340x1080): trigger, tap, then the swipes |
| `05-landscape-dismiss.mp4` | the dismissal pass: a press **inside** the panel (nothing happens) then a press in the **side gutter** (dismisses) |
| `capture-log.txt` | the commands, coordinates, geometry and `logcat` gates behind all of the above |

## Gates that passed

- `01`: `Tap hit line 2` → sheet up; a press above the panel then dismissed it
  (`Media resume: sending a media play key event` + `Overlay window removed (resumed=true)`).
- `02`/`03`: `Screen scan ready … (7 lines)`, `Tap hit line 2`, `Tap complete in 387 ms`; the two
  stills differ only by the scroll.
- `05`: after the inside-panel press there were **0 × `Overlay window removed`** lines and the
  post-press still was byte-identical to `03` (both md5 `aee6cd2213830fed52b59e2c413a7762`); after the
  gutter press, `Media resume…` + `Overlay window removed (resumed=true)`.

## What these captures do not establish

- Not exercised: dark theme; a text scale other than 0.94; a physical device; a landscape display
  cutout (the emulator has none); the landscape search layer with the keyboard up (the Android
  full-screen extract IME covers the panel); and the in-flow Anki bar's deck list scrolling while it
  is nested inside the entry scroller.
- Dismissal is covered in landscape here. Portrait dismissal was verified earlier on the combined
  TickTick build (same source for this file) and is not re-captured; the portrait still covers the
  portrait layout claim.
- `04`/`05` are `adb screenrecord` clips, which encode a frame only when the screen changes, so they
  hold few frames; the stills and the log carry the claims.
