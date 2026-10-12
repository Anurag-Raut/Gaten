# Gaten

Gaten is an Android app for reading Japanese on screen. When you hit a word you cannot read —
in a game, a manga viewer, a website, a chat — you fire a trigger, lasso the text, and Gaten
shows the words it recognises, their kana readings and English meanings from an offline
dictionary. Everything runs on the device: there is no account, no server and no network
permission, so it works in airplane mode.

The internal package id remains `com.yomilens.app` for compatibility with existing trigger
bindings (Key Mapper broadcasts and the Quick Settings tile).

## How it works

1. **Trigger** — the OnePlus Plus Key (remapped with Key Mapper), or the Quick Settings tile. Either
   way a lookup is requested from outside the app.
2. **Capture** — the accessibility service calls `takeScreenshot()` and freezes the current screen.
   Surfaces marked `FLAG_SECURE` (DRM video) come back blank; Gaten never tries to bypass them.
3. **Scan** — as soon as the overlay opens, the whole screen is recognised once in the background
   (ML Kit Japanese) and every line is indexed with a box per character. Faint outlines fade in
   when it is done.
4. **Tap or circle** — once the scan is ready, tap a word (a tap mid-word still finds the whole
   word) or circle some text; both are answered from the index with no further OCR, and a circle
   keeps any word the loop only grazed. Before the scan is ready, or when Tesseract is the selected
   engine, a circle falls back to recognising the circled region at that moment.
5. **Repair** — common OCR confusions are fixed against the dictionary before lookup (ー/一,
   カ/力, ロ/口, 猫/描 and similar), only where that makes the dictionary match more of the line.
6. **Dictionary** — the text is scanned for words (with deinflection) against a bundled SQLite
   database built from JMdict and JMnedict, and the overlay lists the matching entries.
7. **Add to Anki** — when AnkiDroid is installed, a button on the result adds the word to a
   "Gaten" deck (word, reading, meaning and the full line it came from) through AnkiDroid's
   instant-add API. The first add asks for AnkiDroid's database permission; no network is used.

Steps 2–6 are fully offline.

## Setup on a phone

1. **Install the APK** for your ABI (`arm64-v8a` on modern phones).
2. **Enable the accessibility service**: Settings → Accessibility → Gaten → On. The main screen
   has a button that opens the right settings page.
3. **Add the Quick Settings tile**: open the Quick Settings panel, tap edit, and drag Gaten in.
   Tapping the tile starts a lookup; if the accessibility service is not enabled, it opens
   accessibility settings instead.
4. **Optional — bind the Plus Key.** The OnePlus Plus Key is intercepted by OxygenOS before apps
   see it, so it needs Key Mapper running in Expert Mode on Shizuku:
   1. Developer options → **Disable permission monitoring** → ON (Shizuku's documented
      ColorOS/OnePlus fix).
   2. Start Shizuku over adb. It must be started again after every reboot.
   3. Key Mapper → Settings → **Expert Mode**: allow Shizuku all the time, start Expert Mode, and
      turn on auto-start/keep-alive.
   4. Key map: Trigger → **Other** → *Record with Expert Mode* → press the Plus Key (records
      `Scan code 735`, device `gpio-keys`). Actions → **Send intent** → **Broadcast receiver**,
      Action `com.yomilens.app.action.TRIGGER_LOOKUP`, Package `com.yomilens.app`.
   5. Set the OxygenOS Plus Key function to **`nothing`** so no stock action fires as well.

   The trigger is a toggle: firing it while the panel is open closes the panel instead of starting
   another lookup.

## Building from source

Requirements: JDK 17 or newer and an Android SDK with `compileSdk 36` (set `ANDROID_HOME` or
`local.properties`), plus Python 3 for the dictionary builder.

```sh
# 1. Generate the dictionary asset (stdlib only; downloads JMdict/JMnedict and verifies SHA-256).
python3 tools/build_jmdict_db.py

# 2. Build the debug APK.
./gradlew :app:assembleDebug
```

The dictionary asset (`app/src/main/assets/db/jmdict.sqlite`) is generated and gitignored, so the
build fails early with a clear message if step 1 was skipped.

## Project layout

- `.` (root package) — app entry points: main screen, lookup overlay, trigger receiver, Quick
  Settings tile, accessibility service.
- `ocr/` — on-device OCR: ML Kit Japanese, the Tesseract fallback (`TesseractEngine`), the engine
  preference, selection snapping, the full-screen text index (`ScreenText*`, `ScreenHits`) and
  `OcrRepair`.
- `anki/` — the AnkiDroid instant-add export.
- `dict/` — offline dictionary: SQLite access (`JmdictRepository`), word scanning (`WordScanner`)
  and deinflection (`Deinflector`).
- `data/` — `BitmapRepository`, the in-memory hand-off of the captured screenshot.
- `ui/` — Jetpack Compose UI: `home/` (setup screen), `lookup/` (lasso layer, result sheet and the
  OCR + dictionary pipeline), `components/` (accessibility disclosure) and `theme/`.
- `tools/` — `build_jmdict_db.py`, which builds the dictionary asset.
- `docs/` — the historical feasibility study (see `docs/README.md`).

## Privacy

Gaten has no `INTERNET` permission — it is explicitly removed from the merged manifest, because
ML Kit's transport library would otherwise request it. The screenshot stays in memory: it is never
uploaded, never shared and never written to disk. There is no analytics, no account and no server.

## Credits and licences

- **Gaten** is licensed under the **GNU GPL v3.0 or later** (see `LICENSE`), and is derived from
  [CircleToSearch](https://github.com/AKS-Labs/CircleToSearch) by AKS-Labs. See `NOTICE` for the
  full third-party attribution list.
- **Dictionary data** — JMdict and JMnedict © Electronic Dictionary Research and Development Group
  (EDRDG), used under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); see
  <https://www.edrdg.org/>.
- **OCR** — [ML Kit](https://developers.google.com/ml-kit/terms) (Google, ML Kit terms) and
  [Tesseract](https://github.com/tesseract-ocr/tesseract) (Apache-2.0) via
  [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) (Apache-2.0), using the
  [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast) models (Apache-2.0).
