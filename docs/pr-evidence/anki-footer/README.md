# Add to Anki — approved spacing and inset divider

| What changed | Current rendered evidence |
|---|---|
| Keep the existing filled accent button below Deck | `01-word-light.png`, `02-word-dark.png` |
| Match the surrounding divider inset and row spacing | All four screenshots |
| Apply the same shared controls to the actual overlay | `09-overlay-dark.png` (portrait), `10-overlay-landscape-dark.png` (landscape) |
| Preserve centered landscape modal sizing | `10-overlay-landscape-dark.png`: 560 dp panel with side gutters |
| Existing deck picker opens and closes | `08-overlay-picker.mp4`, landscape, two open/close cycles |

`reference-variant-3.png` is the user's supplied design reference, not an app capture.
The deck row uses the existing Other spellings row's 58 dp minimum height and 16 dp vertical
padding. There is no extra section padding above it, and the gap before the filled action is 8 dp.
The modal's sizing rules were not changed.

## Source and build

- Captured from the real installed Android debug app after the final spacing change, before committing.
- Branch: `restyle-anki-deck-bottom-sheet`, based on dev `3f9d621`.
- Device: `emulator-5554`, AVD Gaten_Lookup_Review, API 34 / x86_64, 1080×2340 portrait and
  2340×1080 landscape, 440 dpi, font scale 0.94; Indigo accent. AnkiDroid 2.25.2, Gaten deck.
- `./gradlew :app:assembleDebug --console=plain`: BUILD SUCCESSFUL (281 tasks).
- `node_modules/.bin/tsc --noEmit -p tsconfig.json`: passed. `git diff --check`: passed.
- APK SHA-256: `0a66093ee5b8b852f67f9b72ce4a1364b5dcf0b762d8f1b3165fc4ce498fc000`.
  The installed APK was read back and matched this hash.
- Bundled JavaScript SHA-256: `a63a763a23475654378a15333b4030d8de1b1c0404848edc48916de0fb89c7e6`.
  Debug embeds the bundle in the APK; no Metro server or browser was needed.

| Source file | SHA-256 |
|---|---|
| `src/rn/ui/search/WordDetailScreen.tsx` | `58341920474cf341cd3cd98b72e5983c21b356ce39f74d883e280ca11eae8491` |
| `src/rn/ui/lookup/LookupSheetApp.tsx` | `98c86cf8b5fa6dba66c38e4103df66bd4c19354309043ced6ba0e9806613fed5` |

## Capture steps

1. Open the recent 穫る entry in Word details; capture light and dark after theme transitions settle.
2. Close the automation session, enable `com.gaten/com.gaten.app.GatenAccessibilityService`,
   broadcast `com.gaten.action.TRIGGER_LOOKUP` to `com.gaten`, and tap the indexed headword.
3. Capture the actual dark overlay in portrait; repeat after rotating to landscape and scroll the
   panel to the Anki controls. Capture the full screen, including both side gutters.
4. Record the landscape picker opening/closing twice using native `adb shell screenrecord`.
   The original clip has 59 video frames and a 4.434 s timeline, with no interpolation or retiming.
5. Restore portrait, auto-rotation, font scale 0.94, normal screen size, night mode off and the
   original disabled lookup service. Remove the temporary native recording from the device.

## Before images

Earlier repository captures show the old full-width footer boundary:
[Word details](../worddetail-anki-inflow/02-entry-end-anki-inline.png) and
[landscape overlay](../lookup-landscape-fullheight/04-landscape-anki-inline.png).
Those are historical captures with their own build provenance in the adjacent READMEs, not a
new baseline binary captured during this task.

## Limits

- The emulator encodes few static frames; the recorder ran for 8 s while its encoded timeline is
  4.434 s. The clip establishes the picker interaction, not animation frame-rate quality.
- This final capture set does not exercise card export, a changed deck, forced busy/duplicate/error
  states, a physical device, long deck names, many-deck scrolling, or WaniKani-connected content.
  Existing Anki logic is retained. The optional kanji section's matching divider was compiled and
  reviewed in code, but that configured state was not captured.
- A narrow / larger-text / Teal check preceded the final spacing refinement; it is not presented as
  current-build evidence here. Further capture attempts were stopped at the user's request.
- No new tests were added for this small layout change.
