# Lookup sheet UI — device evidence

Real captures from the lookup **overlay** (the accessibility-service window over a frozen screen), not
from the app's own word-detail page, for the ticketed UI report and its follow-up.

| Item | What this set shows |
| --- | --- |
| Bottom padding under the action | `01` — 24.0 dp (66 px) of air under the Add to Anki button |
| Distinct section separators | `01` — section rule `#D0D0D3` against row rule `#E9E9ED`, measured in the same frame |
| Change the export deck | `02` (picker open), `03` (destination in AnkiDroid), `09` (Add flow) |
| Circle-text guidance | `04` — "No text found" followed by "Circle the text on screen to look it up." |
| Follow-up: animated open/close | `06`, `07`, `08` — 10–11 real intermediate frames per transition |
| Follow-up: 16 dp arrow | `01`, `02` (closed and open, current build) |

## Environment

| | |
| --- | --- |
| Device | `emulator-5554` (AVD `Gaten_Lookup_Review`, headless), 1080×2340 @ 440 dpi, `font_scale 0.94`, SDK 34, x86_64 |
| App | `com.gaten` debug, `versionCode 10`, `versionName 0.1.0` |
| Build (current) | `b82b923` — `app-x86_64-debug.apk`, 298,001,225 B, SHA-256 `b3821c4a…3459`; the installed `/data/app/.../base.apk` hashed to the same value |
| Build (earlier) | `6991f8a` — 294,649,954 B, SHA-256 `3a3953cd…9c2d`; source of `03`, `04`, `09` |
| JS | debug builds bundle `assets/index.android.bundle` (`debuggableVariants.set(emptyList())`), so no Metro server was involved |
| AnkiDroid | 2.25.2, database access granted; the collection's own `Gaten` deck (2 due) was left untouched |

## Steps that produced these frames

```bash
D=emulator-5554
adb -s $D install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
# lookup mode: the app reads Android's own accessibility-service list
adb -s $D shell settings put secure enabled_accessibility_services com.gaten/com.gaten.app.GatenAccessibilityService
adb -s $D shell settings put secure accessibility_enabled 1

# a real frozen source with Japanese: Home -> search "neko" -> 猫 word detail
adb -s $D shell input tap 300 417 && adb -s $D shell input text neko
adb -s $D shell input keyevent KEYCODE_BACK && adb -s $D shell input tap 300 551

# the overlay, then a lasso across 猫 + ねこ・ネコ
adb -s $D shell am broadcast -a com.gaten.app.action.TRIGGER_LOOKUP -p com.gaten   # package id is com.gaten, not com.gaten.app
adb -s $D shell input swipe 95 375 360 615 1400

# 01 (closed footer) and 02 (picker open) are stills of the live sheet
adb -s $D exec-out screencap -p > 01-sheet-footer-padding-and-section-rules.png
adb -s $D shell input tap 894 2037 && adb -s $D exec-out screencap -p > 02-deck-selector-open.png

# 06/07/08: the animation, recorded while the deck row was tapped open/select/close twice
agent-device record start /tmp/pr37-select.mp4 --scope device --quality high
adb -s $D shell input tap 894 1985   # open
adb -s $D shell input tap 894 1985   # select Gaten UI Check -> animated close
adb -s $D shell input tap 894 1985   # open
adb -s $D shell input tap 894 1850   # select Gaten -> animated close (restores the export deck)
agent-device record stop
```

## The animation, measured

`06-deck-picker-animation.mp4` is the recording as captured (8.08 s, native timing, no speed change);
`07-deck-picker-animation.gif` is the same clip sampled at 20 fps — the same wall-clock speed.
`08-deck-picker-open-frames.png` is a contact sheet of twelve consecutive decoded frames of one open
transition, cropped to the footer.

The footer's top rule was located in every decoded frame (the lowest full-width rule of the house
tone). One open transition walks it **1919 → 1866 → 1789 → 1741 → 1687 → 1671 → 1659 → 1643 → 1638 →
1634 → 1633 → 1633** px, and the matching close walks it back **1643 → 1780 → 1850 → 1871 → 1886 →
1897 → 1905 → 1911 → 1915 → 1918 → 1919**. Those frames carry timestamps 16.7–25 ms apart
(60 fps): the open spans **1.250 → 1.500 s (250 ms)** and the close **3.242 → 3.409 s (167 ms)** — the
220 ms `TOGGLE_MS` token, plus a frame of latency. Two further transitions in the same clip (open at
5.0 s, close at 6.3 s) behave the same. Nothing was interpolated, retimed or tweened.

## AnkiDroid fixture (created, then removed)

`Gaten UI Check` was created in AnkiDroid 2.25.2 for the deck-change checks (`00` shows both decks;
`03` shows the fixture deck at 2 due after the `6991f8a` run's adds). It was deleted again at the end
of each run — the collection is back to its own `Gaten` deck at 2 due — so the collection is as it was
found. No due card was graded, and no account was edited.

## Limitations, stated plainly

- **The capture rate is not steady.** `agent-device record` encodes on change, and this headless
  emulator renders ~4.3 fps on average, so *static* stretches contribute few frames. The smoothness
  claim rests on the 10–11 real intermediate frames captured *during* each transition (60 fps
  timestamps), not on a steady full-session capture.
- **`adb shell screenrecord` is unusable here**: 3 encoded frames for 19.9 s, even with repeated
  motion. `agent-device record --scope device` is what produced `06`.
- **`04` used a different engine.** Manga OCR invents text on a blank crop (two blank lassos returned
  `それは`), so the no-text state was reached with Text recognition set to Google ML Kit, which read the
  same blank crop as empty; the engine was switched back to Manga OCR afterwards (verified by radio
  state). That one crop was read by ML Kit, not Manga OCR.
- **`03` and `09` are from `6991f8a`**, before the follow-up: `09` still shows the 20 dp arrow. They are
  kept because the Add flow and the deck destination are unaffected by the arrow size and the animation.
- **The duplicate verdict is collection-wide**, so the button label does not change per deck; the deck
  destination is proven by what AnkiDroid received (`03`), not by the label.
- **No automated test covers this.** The verification is a device run of the exact PR binaries; the repo
  has no UI test harness for the overlay.
