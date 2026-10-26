# Lookup sheet UI — device evidence

Real captures from the lookup **overlay** (the accessibility-service window over a frozen screen), not
from the app's own word-detail page, for the four items of the ticketed UI report:

| Item | What this set shows |
| --- | --- |
| Bottom padding under the action | `01` — 24 dp of air under the Add to Anki button, above the bottom inset |
| Distinct section separators | `01` — section rules measure `#D0D0D3` against row rules at `#E9E9ED` in the same frame |
| Change the export deck | `02` (selector open), `03` (destination), `05` (interaction recording) |
| Circle-text guidance | `04` — "No text found" followed by "Circle the text on screen to look it up." |

## Environment

| | |
| --- | --- |
| Device | `emulator-5554` (AVD `Gaten_Lookup_Review`, headless), 1080×2340 @ 440 dpi, `font_scale 0.94`, SDK 34, x86_64 |
| App | `com.gaten` debug, `versionCode 10`, `versionName 0.1.0` |
| Build | `./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64` at commit `6991f8a` |
| APK | `app-x86_64-debug.apk`, 294,649,954 B, SHA-256 `3a3953cd4539d77340b3b91bb51038d354cd803993e675f49c45c8f62dde9c2d` — the installed `/data/app/.../base.apk` hashed to the same value, so the captures are this PR's code |
| JS | debug builds bundle `assets/index.android.bundle` (`debuggableVariants.set(emptyList())`), so no Metro server was involved |
| AnkiDroid | 2.25.2, database access granted; the collection's own `Gaten` deck (2 due) was left untouched |

## Steps that produced these frames

```bash
D=emulator-5554
adb -s $D install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
# lookup mode: the app reads Android's own accessibility-service list
adb -s $D shell settings put secure enabled_accessibility_services com.gaten/com.gaten.app.GatenAccessibilityService
adb -s $D shell settings put secure accessibility_enabled 1
# settings -> Screen lookup -> Lookup mode -> on (the disclosure dialog must be accepted once)

# a real frozen source with Japanese: Home -> search "neko" -> 猫 word detail
adb -s $D shell input tap 300 417 && adb -s $D shell input text neko
adb -s $D shell input keyevent KEYCODE_BACK && adb -s $D shell input tap 300 551

# the overlay, then a lasso across 猫 + ねこ・ネコ
adb -s $D shell am broadcast -a com.gaten.app.action.TRIGGER_LOOKUP -p com.gaten   # package id is com.gaten, not com.gaten.app
adb -s $D shell input swipe 95 375 360 615 1400

# 01: sheet, 猫 selected (the second chip)
adb -s $D shell input tap 514 918 && adb -s $D exec-out screencap -p > 01-sheet-footer-padding-and-section-rules.png
# 02: the deck row opens the selector (row y=2037)
adb -s $D shell input tap 894 2037 && adb -s $D exec-out screencap -p > 02-deck-selector-open.png
```

`05-deck-switch-and-add.mp4` (and its GIF) was assembled from a burst of `adb exec-out screencap -p`
frames at ~4 fps while the sheet was driven: deck row → `Gaten UI Check` → Add to Anki. `adb shell
screenrecord` was tried first but is unusable on this headless emulator: it wrote 2 encoded frames for
18 s (it encodes only on screen change, and the display is not being composited continuously).

Separator measurement (`#D0D0D3` section vs `#E9E9ED` row) is a pixel read of the captured frame at
y=1435/1941 (section rules under the headword block and above the footer) against y=1696/1839 (rules
between the senses), sampled across x=60..1000.

## AnkiDroid fixture (created, then removed)

To exercise a deck **change**, a second deck is needed. `Gaten UI Check` was created in AnkiDroid 2.25.2
(`+` FAB → Create deck), 0 cards; the recording then switched the sheet's export deck to it and added
`それでも` (`00` shows the two decks side by side, `03` shows the fixture deck at 2 due after the adds).
Afterwards the fixture deck and its 2 cards were deleted again — the list is back to the collection's own
`Gaten` deck at 2 due — so the collection is as it was found. No due card was graded, and no account was
edited.

## Limitations, stated plainly

- **Engine switched for one capture.** `04` was taken with Text recognition set to **Google ML Kit**,
  because Manga OCR **invents** text on a blank crop: two lassos over genuinely blank regions returned
  `それは` and produced an entry instead of the empty state. ML Kit read the same blank crop as empty and
  the sheet's no-text state appeared. The engine was switched back to Manga OCR afterwards (verified by
  radio state). So the no-text *state* and its new guidance line are verified end to end; the crop that
  reached it was read by ML Kit, not Manga OCR.
- **Duplicate semantics are collection-wide.** The button reads "Already in Anki" for a word present in
  the collection whichever deck is selected (the app checks the first mapped field across all decks for
  the note type). The recording therefore proves the destination by what AnkiDroid receives, not by the
  button label changing per deck.
- **No automated test covers this.** The verification is a device run of the exact PR binary; there is no
  UI test harness in the repo for the overlay.
- **The recording is a frame burst**, not device-encoded video (see above). It shows the taps, the row
  updating to `Gaten UI Check` and the final `Added ✓` state at ~4 fps.
- Restored after capture: `enabled_accessibility_services` (deleted), `accessibility_enabled = 0`,
  `global policy_control` (deleted), the recognition engine (Manga OCR), the export deck (`Gaten`).
