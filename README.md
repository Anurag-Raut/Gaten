# Gaten

Gaten is an Android app for reading Japanese on screen. When you hit a word you cannot read —
in a game, a manga viewer, a website, a chat — you fire a trigger, lasso the text, and Gaten
shows the words it recognises, their kana readings and English meanings from an offline
dictionary. Reading runs entirely on the device: there is no account and no server, and it works
in airplane mode. The only thing Gaten ever uses the network for is downloading the optional
stylized-text model, and only when the reader taps Download (see *Optional stylized-text model*).

## How it works

1. **Trigger** — the OnePlus Plus Key (remapped with Key Mapper), or the Quick Settings tile. Either
   way a lookup is requested from outside the app.
2. **Capture** — the accessibility service calls `takeScreenshot()` and freezes the current screen.
   Surfaces marked `FLAG_SECURE` (DRM video) come back blank; Gaten never tries to bypass them.
3. **Detect** — as soon as the overlay opens, ML Kit finds the lines on the whole screen and indexes
   each one with a box per character. That index is what makes the overlay usable; the faint
   outlines fade in as soon as it exists, usually in well under a second.
4. **Read** — with the optional manga-ocr model installed, it then reads those lines in the
   background and replaces each one's text as it lands, biggest boxes first (see *Optional
   stylized-text model* below). ML Kit's own reading stays for any line it cannot read: no model, a
   failure, or an answer slower than 2.5 s.
5. **Tap or circle** — tap a word (a tap mid-word still finds the whole word) or circle some text.
   The lines the gesture touched are read first, and only those, so a tap usually waits for one
   line; the rest of the screen keeps being read behind the result. Words come from the index with
   no further OCR, and a circle keeps any word the loop only grazed. Before the index is ready — or
   when ML Kit read nothing Japanese in the circled region — a circle falls back to recognising the
   circled region at that moment, and when ML Kit read nothing Japanese there the selection box
   itself goes to manga-ocr.
6. **Repair** — common OCR confusions are fixed against the dictionary before lookup (ー/一,
   カ/力, ロ/口, 猫/描 and similar), only where that makes the dictionary match more of the line.
7. **Dictionary** — the text is scanned for words (with deinflection) against a bundled SQLite
   database built from JMdict and JMnedict, and the overlay lists the matching entries.
8. **Add to Anki** — when AnkiDroid is installed, a button on the result adds the word to a
   "Gaten" deck (word, reading, meaning and the full line it came from) through AnkiDroid's
   instant-add API. The first add asks for AnkiDroid's database permission; no network is used.

Steps 2–7 are fully offline, and the app makes no network request of any kind until — and unless —
the reader downloads the optional stylized-text model.

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
      Action `com.gaten.app.action.TRIGGER_LOOKUP`, Package `com.gaten.app`.
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

## Optional stylized-text model (manga-ocr)

ML Kit finds the lines; manga-ocr reads them. ML Kit's own reading is good on plain text but misreads
stylized fonts — outlined video-thumbnail text, hand-lettered manga lettering — which is exactly what
[manga-ocr](https://github.com/kha-white/manga-ocr) (a ViT encoder plus a BERT decoder, run on
[ONNX Runtime](https://onnxruntime.ai/)) is trained on. With the model installed and the settings
toggle on ("Read Japanese text with manga-ocr", the default), it becomes the reader of every line
worth reading: the Japanese lines, and lines too short for ML Kit to have had anything to go on.
Latin and digit lines keep ML Kit's text, because manga-ocr widens ASCII to full width and there is
nothing to gain.

Nothing waits for it. The overlay is usable as soon as ML Kit's index exists; the lines are then read
in the background, biggest boxes first, and a tap or a lasso waits only for the lines it touched —
one, usually, at 2.5 s each at the very worst. Any line it cannot read (no model, a failure, a
timeout, a runaway answer) keeps ML Kit's text, and the rest of the screen keeps being read behind
the result sheet, so the next gesture usually needs no wait at all.

The model is ~117 MB, so it is never part of a release APK. There are three ways to get it onto a
phone, and the app only reaches the network for the first one:

- **Download it in the app** — Gaten → *Stylized-text model (manga-ocr, optional)* → **Download**.
  The size is shown first and has to be confirmed (with a warning when the phone is on mobile data),
  the download runs with a progress bar and a Cancel button, and the only hosts it talks to are
  `huggingface.co` and the CDN it redirects to, over HTTPS. This is the app's only network request:
  nothing is fetched until that button is tapped. The files come from
  [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX)
  (`encoder_model_int8.onnx`, `decoder_model_int8.onnx`) and
  [kha-white/manga-ocr-base](https://huggingface.co/kha-white/manga-ocr-base) (`vocab.txt`).
- **Import files you already have** — the same section's **Import model files** takes
  `encoder_model_int8.onnx`, `decoder_model_int8.onnx` and `vocab.txt` from device storage; they are
  recognised by name, so nothing has to be renamed. `tools/fetch_manga_ocr.sh [dir]` downloads them
  on a computer and prints the matching `adb push` command.
- **For a debug build.** `./gradlew :app:assembleDebug -PbundleMangaOcr=true` has Gradle fetch the
  three files into `app/src/debug/assets/manga-ocr/` (gitignored), so the debug APK carries them;
  the app copies them into its own storage the first time the second pass runs. Without the property
  nothing is downloaded, and a release build never sees the files.

Without the model — or with the toggle off — ML Kit both finds and reads the lines, which is exactly
what the app did before the model existed.

The catalog also lists `encoder_model_fp16.onnx` / `decoder_model_fp16.onnx` from the same
repository — they are **not** offered, because ONNX Runtime refuses to load the fp16 decoder (its
Cast node is typed float16 where its consumer expects float32: `Type Error: Type (tensor(float16))
of output arg ... does not match expected type (tensor(float))`). The fp16 encoder does load, and the
4-bit pair (`encoder_model_q4.onnx` / `decoder_model_q4.onnx`, ~93 MB) runs and reads the reference
image identically to int8, but its accuracy on real manga has not been measured, so it is not offered
either. Adding one is a new entry in `ocr/MangaOcrCatalog.kt`.

## Project layout

- `.` (root package) — app entry points: main screen, lookup overlay, trigger receiver, Quick
  Settings tile, accessibility service.
- `ocr/` — on-device OCR: ML Kit Japanese as detector and fallback reader, the optional meiki
  text-line detector (`meiki.text.detect.v0`, shipped in the APK alongside ML Kit and merged into
  ML Kit's boxes by `DetectionMerge`), selection snapping, the
  full-screen text index (`ScreenText*`, `ScreenHits`) and `OcrRepair`. The reader is
  `ScreenLineReader` (the per-session cache that replaces each line's text as manga-ocr reads it, and
  drops the lines whose final text has no Japanese), `MangaOcrModel` (where the model lives),
  `MangaOcrEngine` (running it), `OcrPreprocess` (the crop), `OcrLinePolicy` (when an answer is
  kept) and `RegionReadPolicy` (what a circled region reads as). `MangaOcrCatalog` is the list of
  downloadable models and `MangaOcrDownloader` the only network code in the app.
- `anki/` — the AnkiDroid instant-add export.
- `dict/` — offline dictionary: SQLite access (`JmdictRepository`), word scanning (`WordScanner`)
  and deinflection (`Deinflector`).
- `data/` — `BitmapRepository`, the in-memory hand-off of the captured screenshot.
- `ui/` — Jetpack Compose UI: `home/` (setup screen), `lookup/` (lasso layer, result sheet and the
  OCR + dictionary pipeline), `components/` (accessibility disclosure) and `theme/`.
- `tools/` — `build_jmdict_db.py`, which builds the dictionary asset, and `fetch_manga_ocr.sh`,
  which downloads the optional manga-ocr model for pushing to a device.
- `docs/` — the historical feasibility study (see `docs/README.md`).

## Privacy

Reading is offline and stays offline. Gaten holds `INTERNET` for exactly one request — the optional
stylized-text model the reader downloads by tapping Download — and that request goes to
`huggingface.co` and the CDN it redirects to, over HTTPS, and nowhere else (`ocr/MangaOcrDownloader.kt`
enforces that allow-list, redirects included). Nothing is fetched in the background, and the
screenshot never leaves the device: it stays in memory, is never uploaded, never shared and never
written to disk. There is no analytics, no account and no server.

ML Kit's transport library and ONNX Runtime also declare `INTERNET` for their own analytics and
telemetry backends. Gaten's code never calls those, but while the permission is in the merged
manifest the OS no longer blocks them — removing it would block them and the model download with it.
That is the trade the download costs, and it is why the permission is documented here rather than
left implicit.

## Credits and licences

- **Gaten** is licensed under the **GNU GPL v3.0 or later** (see `LICENSE`), and is derived from
  [CircleToSearch](https://github.com/AKS-Labs/CircleToSearch) by AKS-Labs. See `NOTICE` for the
  full third-party attribution list.
- **Dictionary data** — JMdict and JMnedict © Electronic Dictionary Research and Development Group
  (EDRDG), used under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); see
  <https://www.edrdg.org/>.
- **OCR** — [ML Kit](https://developers.google.com/ml-kit/terms) (Google, ML Kit terms) detects the
  lines and reads them when no manga-ocr model is installed.
- **Text-line detection** — [meiki.text.detect.v0](https://huggingface.co/rtr46/meiki.text.detect.v0)
  (rtr46 and contributors, LGPL-3.0), a second line finder shipped in the APK alongside ML Kit for
  the lettering ML Kit's detector misses.
- **Optional stylized-text OCR** — [manga-ocr](https://github.com/kha-white/manga-ocr) (kha-white,
  Apache-2.0) through the ONNX export at
  [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX),
  run on [ONNX Runtime](https://onnxruntime.ai/) (Microsoft, MIT). The model files are downloaded by
  the user or by a debug build, never shipped.
