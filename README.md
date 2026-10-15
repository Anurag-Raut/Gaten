# Gaten

Gaten is an Android app for reading Japanese on screen. When you hit a word you cannot read —
in a game, a manga viewer, a website, a chat — you fire a trigger, lasso the text, and Gaten
shows the words it recognises, their kana readings and English meanings from an offline
dictionary. Reading runs entirely on the device: there is no account and no server, and it works
in airplane mode. The only thing Gaten ever uses the network for is downloading an optional model —
the stylized-text reader, or the sentence-translation models — and only when the reader taps
Download (see *Optional stylized-text model* and *Optional sentence translation*).

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
5. **Tap or circle** — tap a text box to select the entire box and all its words, or circle some text.
   A tap highlights the whole box immediately and answers from the index, reading only that line.
   A circle always crops the exact rectangle that was drawn and attempts manga-ocr on it — index or no index,
   Japanese in the index or not — and the index lines the loop actually covered then add the words
   they hold behind that reading, deduplicated. With no model installed the crop yields no reading,
   and the answer is those index lines' text — or, before the index exists, ML Kit's own reading of
   the crop. The rest of the screen keeps being read behind the result.
6. **Repair** — common OCR confusions are fixed against the dictionary before lookup (ー/一,
   カ/力, ロ/口, 猫/描 and similar), only where that makes the dictionary match more of the line.
7. **Dictionary** — the text is scanned for words (with deinflection) against a bundled SQLite
   database built from JMdict and JMnedict, and the overlay lists the matching entries.
8. **Add to Anki** — when AnkiDroid is installed, a button on the result adds the word to a
   deck of your choice (word, reading, meaning and the full line it came from) through AnkiDroid's
   instant-add API. Configure it under **Anki → Customize cards** on the main screen; no network
   is used for Anki export.

Steps 2–7 are fully offline, and the app makes no network request of any kind until — and unless —
the reader downloads an optional model.

### Closing the lookup

A lookup is only useful on the screen the reader is looking at, so the overlay closes itself when
that stops being true, and a trigger that cannot start one is refused:

- **The screen goes off, the phone locks, or an always-on display comes up.** A pending capture is
  dropped and a trigger while locked is ignored. Unlocking (or the screen coming back on) restores
  the floating button, never the old lookup.
- **An incoming call or a firing alarm.** These arrive two ways: as a posted notification
  (`TYPE_NOTIFICATION_STATE_CHANGED`, no extra permission needed) and as the dialer's or clock's own
  window. A *missed* call and an *upcoming* alarm reminder do not close a lookup: the notification
  has to be an incoming call (`CallStyle` metadata, incoming call type), a legacy full-screen CALL,
  or an ALARM-category notification
  with a full-screen intent, which is how the ring and the alert are shown.
- **The power menu or another SystemUI dialog.** These are recognised from the window event,
  including framework dialogs whose class does not identify the power menu specifically.
- **The display rotates (including a half-turn), unfolds or is otherwise reshaped** — while a capture is still pending or
  while the frame is on screen. The frozen frame is in the pixels of the screen it was taken from,
  so it is closed rather than rescaled; ordinary layout changes — the keyboard, a split-screen
  divider, a font change — are ignored.
- **The accessibility service is unbound or destroyed, or the app's task is finished** (including
  being taken off recents, where the platform chooses whether to deliver the callback).

The media that was paused for the lookup is left **paused** for all of these: the reader should not
hear a video resume under a call, and a player that has already raised its controls must not be
started again behind them. The reader's own dismissals — back, the scrim, the trigger toggle — play
it back as before.

Foreground app changes during capture cancel the pending lookup. Each callback is tied to its
capture, so a late success or failure cannot reopen a closed lookup or alter a newer request.

What this does **not** promise: the power menu, an OEM dialer's in-call screen, or a clock's alarm
screen each have their own window class names, and only the ones listed above are recognised; an
OEM that names them differently may only be covered by the window focus the overlay loses anyway.
The same is true of whether OxygenOS delivers any given accessibility event — that is the part most
likely to need a real device to confirm.

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

5. **Optional — customize Anki cards.** Open **Anki → Customize cards**. The default is the
   existing Gaten deck and four-field note type. Enter a deck name (including nested names such
   as `Japanese::Reading`) or allow AnkiDroid access to choose an existing deck. Add space-separated
   tags if wanted. New decks and Gaten note types are created only when you export a word.
   - Choose **Gaten cards (customizable)** to edit the front/back HTML and CSS. Available fields
     are `{{Expression}}`, `{{Reading}}`, `{{Meaning}}`, and `{{Sentence}}`; the back can use
     `{{FrontSide}}`. Conditional sections such as `{{#Reading}}…{{/Reading}}` are supported.
     Changed layouts use a separate note type for future exports, leaving existing cards and
     AnkiDroid template edits intact. Restoring defaults uses the original Gaten note type.
   - Or choose an existing standard note type and map each of its fields to Expression, Reading,
     Meaning, Sentence, or Leave empty. Its templates and styling remain managed in AnkiDroid.
     Expression must appear somewhere, and the first field must have a value for each exported
     word. Cloze note types are not supported. If fields change in AnkiDroid, select the note type
     again and save the updated mapping.
   - Duplicates are checked against the mapped first field within the selected note type across
     all decks. A new layout creates a different note type, so its duplicate history is separate.
   - Tap **Save** to apply your settings. Without database access, the lookup uses AnkiDroid's
     share screen; choose the deck and note type there. Gaten settings apply to direct export.

   The deck/note-type/field-mapping workflow follows established patterns in
   [Yomitan](https://yomitan.wiki/anki/) and
   [asbplayer](https://docs.asbplayer.dev/docs/reference/settings/). Gaten uses AnkiDroid's local
   provider instead of their AnkiConnect transport; no code was copied from these projects.

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
[ONNX Runtime](https://onnxruntime.ai/)) is trained on. With the model installed it is offered every
line ML Kit found, whatever script ML Kit read it in — a line it read as Latin can still be stylized
Japanese — and the line's final text decides whether it stays: a line with no Japanese in it is
dropped, so Latin and digit lines do not stay on screen as text. A circle does not depend on any of
that: it crops the rectangle it was drawn around and attempts manga-ocr on that, whatever the index
found elsewhere on the screen.

Nothing waits for it. The overlay is usable as soon as ML Kit's index exists; the lines are then read
in the background, biggest boxes first, and a tap waits only for the line it touched — one, usually,
at 2.5 s at the very worst. A circle's crop is read before the lines it covered are, so the answer
never queues behind them. Any line it cannot read (no model, a failure, a timeout, a runaway answer)
keeps ML Kit's text, and the rest of the screen keeps being read behind the result sheet, so the next
gesture usually needs no wait at all.

The engines are not kept for the life of the process. Once no lookup has used them for 90 seconds —
or when the system asks for memory, or when the accessibility service is torn down — Gaten releases
them (manga-ocr's ONNX sessions, ML Kit's Japanese recognizer and the meiki detector). The next lookup
builds them again from local files or APK assets, with no download; this first lookup may take longer.
Cleanup waits for active recognition, including ML Kit tasks whose awaiting lookup was cancelled.
The memory reduction has not been measured on the current version.

The model is ~117 MB, so it is never part of a release APK. There are three ways to get it onto a
phone, and the app only reaches the network for the first one:

- **Download it in the app** — Gaten → *Stylized-text model (manga-ocr, optional)* → **Download**.
  The size is shown first and has to be confirmed (with a warning when the phone is on mobile data),
  the download runs with a progress bar and a Cancel button, and the only hosts it talks to are
  `huggingface.co` and the CDN it redirects to, over HTTPS. This request is one of the app's only
  two, and nothing is fetched until that button is tapped. The files come from
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

Without the model, ML Kit both finds and reads the lines, which is exactly what the app did before
the model existed; a circle is then answered from the index lines it covered, or — before the index
exists — from ML Kit's own reading of the crop.

The catalog also lists `encoder_model_fp16.onnx` / `decoder_model_fp16.onnx` from the same
repository — they are **not** offered, because ONNX Runtime refuses to load the fp16 decoder (its
Cast node is typed float16 where its consumer expects float32: `Type Error: Type (tensor(float16))
of output arg ... does not match expected type (tensor(float))`). The fp16 encoder does load, and the
4-bit pair (`encoder_model_q4.onnx` / `decoder_model_q4.onnx`, ~93 MB) runs and reads the reference
image identically to int8, but its accuracy on real manga has not been measured, so it is not offered
either. Adding one is a new entry in `ocr/MangaOcrCatalog.kt`.

## Optional sentence translation (ML Kit)

The result sheet has two halves, switched by a **Words / Sentence** control above the body. *Words* is
what Gaten has always shown: the dictionary entries for the words the OCR found. *Sentence* shows the
whole lines the gesture selected, as one text, and — when the translation models are installed — their
English translation underneath.

The sentence is not the same string as the header's. A tap's header text is ML Kit's line with the
model's reading of the tapped phrase corrected inside it, and a circle's is the model's reading of the
drawn crop; the sentence is built from **whole lines of the screen index**, in reading order (rows top
to bottom, tategaki columns right to left), read by manga-ocr where it could read them and ML Kit's own
text otherwise. Nothing is stitched and nothing is fragmented: the lines a gesture covered are taken
entire, joined with newlines, and that is what is translated. Both halves are independent — a
dictionary that will not open, or a kana-only sentence with no dictionary word in it, leaves the other
half showing.

The English comes from **ML Kit's on-device translation** (`com.google.mlkit:translate`,
`translate/SentenceTranslator.kt`), which is the same set of models Google Translate's offline mode
uses. Nothing is sent anywhere: the recognised text is translated on the phone, in airplane mode
included. The models are not in the APK — Gaten → *Sentence translation (optional)* → **Download**
fetches them, and that is the app's second and only other network request. The models are about
30 MB per language, and Japanese → English needs both, so the settings screen warns about ~60 MB
before anything is fetched, with the same mobile-data warning the manga-ocr download has. ML Kit's
download reports no progress and cannot be cancelled, which the settings screen says in as many words
rather than showing a bar that would be a lie. **Remove the models** deletes them again; translation
then degrades to the sentence text alone, with no error and no request.

Directions beyond Japanese → English, and any language other than English on either side, are not
offered: ML Kit's models are trained to and from English, so a pair like Japanese → Spanish pivots
through English and loses quality, and Gaten is a Japanese reading aid.

Two obligations come with the API, and the app meets both visibly:

- **Attribution.** ML Kit's [usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms)
  require the Cloud Translation [attribution requirements](https://cloud.google.com/translate/attribution),
  so every translation in the sheet carries *Translated with Google Translate* beneath it, and the
  settings screen carries the Google disclaimer in full. Gaten's name does not use Google's marks, and
  the app claims no affiliation.
- **Restriction.** The API may only be used on personal computing devices — phones, tablets and
  computers — without Google's written permission, which is what this is.

## Project layout

- `.` (root package) — app entry points: main screen, lookup overlay, trigger receiver, Quick
  Settings tile, accessibility service.
- `ocr/` — on-device OCR: ML Kit Japanese as detector and fallback reader, the optional meiki
  text-line detector (`meiki.text.detect.v0`, shipped in the APK alongside ML Kit and merged into
  ML Kit's boxes by `DetectionMerge`), the full-screen text index (`ScreenText*`, `ScreenHits`) and
  `OcrRepair`. The reader is `ScreenLineReader` (the per-session cache that replaces each line's text
  as manga-ocr reads it, and drops the lines whose final text has no Japanese), `MangaOcrModel`
  (where the model lives), `MangaOcrEngine` (running it), `OcrPreprocess` (the crop), `OcrModels`
  (when the three OCR engines are released) and `OcrLinePolicy` (when an answer is kept).
  `MangaOcrCatalog` is the list of downloadable models and `MangaOcrDownloader` the app's own network
  code — Gaten's one other request is ML Kit's, made from `translate/`. `SelectionText` is the sentence
  contract: the whole lines a gesture selected, in reading order, with the offsets that address each
  of them.
- `translate/` — the optional sentence translation: `SentenceTranslator` (the ML Kit client, the
  models' presence, their download and deletion) and the `SentenceTranslation` state the sheet shows.
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

Reading is offline and stays offline. Gaten holds `INTERNET` for exactly two requests — the optional
stylized-text model and the optional sentence-translation models, each downloaded only by tapping
Download in its own section. The first goes to `huggingface.co` and the CDN it redirects to, over
HTTPS, and nowhere else (`ocr/MangaOcrDownloader.kt` enforces that allow-list, redirects included);
the second is ML Kit's own model download, which fetches the Japanese and English translation models
and nothing else. Nothing is fetched in the background, and the screenshot never leaves the device:
it stays in memory, is never uploaded, never shared and never written to disk. There is no analytics,
no account and no server. Translation runs on the device once those models are in place, so a lookup
in airplane mode is translated like any other.

The only notifications Gaten looks at are the accessibility events Android already sends it for a
posted notification, and it reads **metadata only**: whether an incoming call (`CallStyle`, incoming
call type) or a firing alarm (ALARM category with a full-screen intent) was posted, so the lookup
closes for those before their own window appears. No notification title, text or other content is
read, kept, logged or stored. This needs no extra permission; it is a side effect of the
accessibility service's event subscription.

The optional notification-listener access the home screen offers is separate and is used for one
thing only: reading the active media sessions so the video behind the overlay can be paused and
resumed. It deliberately reads nothing from the notifications it is handed, and declining it leaves
every lookup feature working (only the session-based pause gives way to a media key press).

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
- **Sentence translation** — [ML Kit on-device translation](https://developers.google.com/ml-kit/language/translation)
  (Google, ML Kit terms), the models Google Translate's offline mode uses. They are downloaded by the
  user, never shipped in the APK, and the app attributes translations to Google Translate as its
  [usage guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms)
  require.
- **Text-line detection** — [meiki.text.detect.v0](https://huggingface.co/rtr46/meiki.text.detect.v0)
  (rtr46 and contributors, LGPL-3.0), a second line finder shipped in the APK alongside ML Kit for
  the lettering ML Kit's detector misses.
- **Optional stylized-text OCR** — [manga-ocr](https://github.com/kha-white/manga-ocr) (kha-white,
  Apache-2.0) through the ONNX export at
  [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX),
  run on [ONNX Runtime](https://onnxruntime.ai/) (Microsoft, MIT). The model files are downloaded by
  the user or by a debug build, never shipped.
