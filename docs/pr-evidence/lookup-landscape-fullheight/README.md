# Lookup sheet — full-height landscape and the Anki action in the entry content

Rendered evidence for `src/rn/ui/lookup/LookupSheetApp.tsx`, captured from **this branch's own
build**. The Anki action is the entry content's last item in **both orientations**: it is reached by
scrolling the entry it belongs to, never a bar pinned to the panel's bottom.

| Item | Value |
|---|---|
| Branch / head | `fix/lookup-landscape-fullheight` @ `368f51d53084436d8e5aa0c901f8e74f32c4c135` (base `dev` `0ac0e403b9020a5e0f56e156dda2aa02900e0db2`) |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 294,648,986 B, SHA-256 `dc9b975bd94debeee374c3685bc942a593acdaf27e7a0c0c137716e4c6537ebb` — installed with `adb install -r` (app data preserved); the on-device `/data/app/…/base.apk` re-hashed to the same value in the same session |
| JS inside that APK | `assets/index.android.bundle` SHA-256 `6734c79959fe9fff0482f8db8d234e1877c28d9ddd2b3c1042d5ab59d81e8208`, carrying this change (the `inlineFooter` marker it adds is present) |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, font scale 0.94, light theme, portrait start and finish |
| Method | `adb` only — broadcast trigger, `input tap`/`swipe`, `screencap`, `screenrecord`, `logcat -s Gaten`. No agent-device/UiAutomation helper attached, and `enabled_accessibility_services` stayed on `com.gaten/com.gaten.app.GatenAccessibilityService` throughout |

## The fixture

Gaten's own Home → **Recently looked up** → 猫 word detail: no typing, so no search-history write.
That screen is the frozen frame; the sheet was opened by tapping the indexed readings line
`ねこ・ネコ`.

Note for anyone re-running this: on the landscape capture ML Kit indexed 7 lines and the isolated 猫
glyph was **not** among them, so the readings line is the tap target. In portrait the same screen
indexed 18 lines.

## Measured panel geometry (from these captures, 2.75 px/dp)

| | Portrait (`01`, `02`) | Landscape (`03`, `04`) |
|---|---|---|
| Panel x | 0 … 1079 (full width) | 400 … 1939 — 1540 px = 560 dp, centred |
| Panel y | 701 … 2339 (the 70 % portrait snap) | **0 … 1079 — the full window height** |
| Screen beside the panel | none | 145 dp gutters, showing the frozen frame |

The landscape panel starting at y=0 is the measured consequence of this window reporting
`insets.top = 0`: the panel fills the height it is given and there is no inset to reserve — exactly
what `docs/Gaten_Ink_Design_Language_v1.md` §8.5 now states. Portrait keeps its 70/92 % snaps.

## What each capture shows

| File | What it shows |
|---|---|
| `01-portrait-entry-top.png` | portrait, entry 猫, at the top of the content: the Anki action is **not** at the panel's bottom — it lives at the end of the entry scroller |
| `02-portrait-entry-anki-inline.png` | the same portrait sheet after scrolling: the content ends with the **Anki deck row + action as the last content**, with the panel's bottom clear below them |
| `03-landscape-fullheight.png` | landscape: the panel fills the window height, keeping the centred 560 dp column; word tabs, 猫, readings and meanings sit inside the scroller |
| `04-landscape-anki-inline.png` | the same landscape sheet after swipes: content ends with the Anki deck row + action as the last content, panel bottom clear below |
| `05-portrait-scroll.mp4` | the recording behind `01` → `02` (1080x2340): trigger, tap, then the swipes |
| `06-landscape-inside-tap-content.png` | a press **inside** the panel (1714,298 dp → the deck row) opens the deck selector instead of dismissing; the sheet stays up |
| `capture-log.txt` | the commands, coordinates, geometry and `logcat` gates behind all of the above |

## Gates that passed

- `01`/`02` (portrait): `Overlay window added for a 1080x2340 capture (+98 ms since the trigger)`,
  `Screen scan ready in 845 ms (18 lines)`, `Tap hit line 3`, `Tap complete in 732 ms`; the two
  stills differ only by the scroll.
- `03`/`04` (landscape): `Overlay window added for a 2340x1080 capture (+90 ms since the trigger)`,
  `Screen scan ready in 347 ms (7 lines)`, `Tap hit line 2`, `Tap complete in 346 ms`; the two stills
  differ only by the scroll.
- Dismissal (landscape): an inside-panel press logged **0 × `Overlay window removed`** and opened the
  deck selector; a press in the **side gutter** then logged `Media resume…` +
  `Overlay window removed (resumed=true)`.

## What these captures do not establish

- Not exercised: dark theme; a text scale other than 0.94; a physical device; a landscape display
  cutout (the emulator has none); the landscape search layer with the keyboard up (the Android
  full-screen extract IME covers the panel); and the in-flow Anki bar's own deck list scrolling while
  nested inside the entry scroller.
- Portrait dismissal is not re-captured here; this change only moved the footer, and landscape
  dismissal (above) covers the backdrop path that both orientations share.
- `05` is an `adb screenrecord` clip, which encodes a frame only when the screen changes, so it holds
  relatively few frames; the stills and the log carry the claims.
