# Gaten

Gaten is an Android app for reading Japanese on screen. When you hit a word you cannot read —
in a game, a manga viewer, a website, a chat — you fire a trigger, lasso the text, and Gaten
shows the words it recognises, their kana readings and English meanings from an offline
dictionary. You can also search by kanji, kana, or romaji directly in Gaten, explore an
entry, and add it to Anki without capturing a screen. Reading runs entirely on the device: there is no account and no server, and it works
in airplane mode. Gaten uses the network for exactly two things, and only after the reader asks for
them: downloading the optional stylized-text model (see *Optional stylized-text model*), and WaniKani
— connecting a personal access token, reading the reference page for a kanji the reader taps, and
reviewing what the account has due (see *Optional WaniKani reference pages* and *Reviewing in
Gaten*).

## How it works

1. **Trigger** — the floating bubble on screen, the Quick Settings tile, or the OnePlus Plus Key
   (remapped with Key Mapper). Either way a lookup is requested from outside the app.
2. **Capture** — the accessibility service calls `takeScreenshot()` and freezes the current screen.
   Surfaces marked `FLAG_SECURE` (DRM video) come back blank; Gaten never tries to bypass them.
3. **Detect** — as soon as the overlay opens, ML Kit finds the lines on the whole screen and indexes
   each one with a box per character for hit testing. Those boxes are not drawn: only your own
   selection is highlighted, so the frozen screen stays uncluttered.
4. **Read** — with the optional manga-ocr model installed, it then reads those lines in the
   background and replaces each one's text as it lands, biggest boxes first (see *Optional
   stylized-text model* below). ML Kit's own reading stays for any line it cannot read: no model, a
   failure, or an answer slower than 2.5 s.
5. **Tap or circle** — tap text to select its entire indexed line and all its words, or circle some text.
   A tap highlights the whole box immediately and answers from the index, reading only that line.
   The lasso trail is visible only while drawing and disappears on finger lift; the selected region
   stays marked while the lookup runs and the result is shown.
   A circle always crops the exact rectangle that was drawn and attempts manga-ocr on it — index or no index,
   Japanese in the index or not — and the index lines the loop actually covered then add the words
   they hold behind that reading, deduplicated. With no model installed the crop yields no reading,
   and the answer is those index lines' text — or, before the index exists, ML Kit's own reading of
   the crop. The rest of the screen keeps being read behind the result.
6. **Repair** — common OCR confusions are fixed against the dictionary before lookup (ー/一,
   カ/力, ロ/口, 猫/描 and similar), only where that makes the dictionary match more of the line.
7. **Dictionary** — the text is scanned for words (with deinflection) against a bundled SQLite
   database built from JMdict and JMnedict, and the overlay lists the matching entries.
8. **WaniKani** (optional) — with a token connected, the kanji of the looked-up word appear as tiles
   under its entry. Tapping one shows WaniKani's page for that kanji inside the sheet — meanings,
   on'yomi and kun'yomi, the radicals it is made of, and WaniKani's mnemonics — and tapping one of
   those radicals opens that radical's own page. Each page is cached, so it stays readable offline,
   and a button opens the real page on wanikani.com (see *Optional WaniKani reference pages*).
9. **Add to Anki** — when AnkiDroid is installed, a button on the result adds the word to your
   configured deck and note type through AnkiDroid's instant-add API. Open **Settings → Anki cards**
   to choose the deck, tags, field mapping and card layout. Export stays on-device.

Steps 2–7 are fully offline. Gaten makes no network request of any kind until — and unless — the
reader downloads the optional stylized-text model, or connects WaniKani (which checks the token) and
then opens a kanji, looks at the Analytics tab, or reviews what is due.

**Settings → Appearance** lets you choose the light/dark theme and accent
independently. Gaten ships a single neutral ladder, so the accent picker changes the one
colour in the app while the paper and text stay as designed.

## Inside the app

The bottom navigation separates four tasks:

- **Home** — a dictionary-search entry point, the configured Anki deck's review counts when access
  is already granted, and screen-lookup status and controls. Opening Home never requests Anki access
  just to populate counts.
- **Search** — type a Japanese word, kana reading, or romaji. Exact matches and dictionary forms of inflected
  words come before related prefix matches. Open an entry to read its definitions, inspect its kanji,
  or add that specific entry to Anki using your saved deck and card settings. Search works without
  screen-access permission or either optional integration.
- **Review** — the landing page offers the two places you can study: AnkiDroid's decks, and WaniKani
  when a token is connected. Choosing a deck starts its session immediately; WaniKani connects first
  if it has to, then opens a page showing what is due for review and which lessons are waiting to be
  learned. An active session keeps the reveal, the rating controls or the answer field in focus, with
  bottom navigation hidden until you leave it (see *Reviewing in Gaten*).
- **Settings** — focused pages for Screen lookup, Text recognition, Anki cards, WaniKani, Appearance,
  and About. Hardware-key setup and model management live here rather than on Home.

Search is Japanese-to-English dictionary lookup: enter kanji, hiragana, katakana, or romaji,
including inflected Japanese forms. Common shortened spellings such as `tokyo` and `ramen`
use a loose reading index after exact matches. English definition search is not supported.
No search query is sent to a server. Kanji reference pages still require the optional WaniKani
connection described below.

In-app navigation uses AndroidX Navigation 3, with a saved back stack for each tab. Word details,
kanji and radical reference pages, and settings use the same destination transitions and Back
behavior. Settings opened from a word return to that word. Review reveals and ratings stay inside
the current session, and leaving an edited Anki configuration still asks before discarding it.

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

The media the lookup paused is left **paused** for all of these — and so is a pause that was still
landing when the lookup ended, because the frozen frame had already asked for it. The reader should
not hear a video play on under a call, and a player that has already raised its controls must not be
started again behind them. The reader's own dismissals — back, the scrim, the trigger toggle — play
it back as before, and a close that beats the pause cancels it.

Foreground app changes cancel a capture that is still on its way to the screen. Once the frozen frame
is up, the app beneath it is behind an opaque window, so a window event from it no longer ends the
lookup. Each callback is tied to its capture, so a late success or failure cannot reopen a closed
lookup or alter a newer request.

What this does **not** promise: the power menu, an OEM dialer's in-call screen, or a clock's alarm
screen each have their own window class names, and only the ones listed above are recognised; an
OEM that names them differently may only be covered by the window focus the overlay loses anyway.
The same is true of whether OxygenOS delivers any given accessibility event — that is the part most
likely to need a real device to confirm.

## Setup on a phone

1. **Install the APK** for your ABI (`arm64-v8a` on modern phones).
2. **Turn lookup mode on** — *lookup mode* is the app's name for Gaten's accessibility service
   being enabled: open **Settings → Lookup mode** and tap **Turn on** (the same button sits on
   **Settings → Screen lookup**), read the disclosure and accept. Android's Accessibility screen
   opens; enable **Gaten** there. Manual dictionary search does not need this permission.
3. **Add the Quick Settings tile**: on Android 13+, tap **Add tile** under **Settings → Screen lookup** and confirm
   Android's dialog. On older versions, add Gaten through the Quick Settings editor. The home row
   reflects **Added** and returns to **Add tile** when Android reports removal. Removal itself
   belongs to the Quick Settings editor; Android provides no app-side remove-tile API.
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
      Action `com.gaten.app.action.TRIGGER_LOOKUP`, Package `com.gaten`.
   5. Set the OxygenOS Plus Key function to **`nothing`** so no stock action fires as well.

   The trigger is a toggle: firing it while the panel is open closes the panel instead of starting
   another lookup.

5. **Optional — customize Anki cards.** Open **Settings → Anki cards**. The default is the
   existing Gaten deck and three-field note type. Enter a deck name (including nested names such
   as `Japanese::Reading`) or allow AnkiDroid access to choose an existing deck. Add space-separated
   tags if wanted. New decks and Gaten note types are created only when you export a word.
   - Choose **Gaten cards (customizable)** to edit the front/back HTML and CSS. Available fields
     are `{{Expression}}`, `{{Reading}}`, and `{{Meaning}}`; the back can use `{{FrontSide}}`.
     Conditional sections such as `{{#Reading}}…{{/Reading}}` are supported. Changed layouts use a
     separate note type for future exports, leaving existing cards and AnkiDroid template edits
     intact. Restoring defaults uses the original Gaten note type.
   - The card is the word, its reading and its meaning — the glosses over their parts of speech. No
     sentence is exported: neither a dictionary example nor the line the word was read from.
   - Or choose an existing standard note type and map each of its fields to Expression, Reading,
     Meaning, or Leave empty. Its templates and styling remain managed in AnkiDroid.
     Expression must appear somewhere, and the first field must have a value for each exported
     word. Cloze note types are not supported. If fields change in AnkiDroid, select the note type
     again and save the updated mapping.
   - Duplicates are checked against the mapped first field within the selected note type across
     all decks. A new layout creates a different note type, so its duplicate history is separate.
   - Tap **Save** to apply your settings. Without database access, the lookup uses AnkiDroid's
     share screen; choose the deck and note type there. Gaten settings apply to direct export.
   - **Review** in the bottom navigation or **Review cards** on Home reviews these decks inside Gaten, with
     AnkiDroid doing the scheduling (see *Reviewing Anki cards in Gaten*).

   The deck/note-type/field-mapping workflow follows established patterns in
   [Yomitan](https://yomitan.wiki/anki/) and
   [asbplayer](https://docs.asbplayer.dev/docs/reference/settings/). Gaten uses AnkiDroid's local
   provider instead of their AnkiConnect transport; no code was copied from these projects.

6. **Optional — connect WaniKani.** Open **Settings → WaniKani**, create
   a personal access token on WaniKani (read access alone is enough for the reference pages; reviewing
   what is due also needs the permission WaniKani lists for creating reviews), and paste it in. Gaten
   checks the token against WaniKani before storing it, and keeps it outside the backup set.
   **Disconnect** removes the token and every cached page. See *Optional WaniKani reference pages*.

## UI stack

The in-app UI is React Native (0.86.3, Fabric / new architecture, Hermes). `MainActivity` is a
`ReactActivity` and renders the JS app from `index.js` → `App.js` → `src/rn/ui/`: three tabs
(Home / Analytics / Settings) with per-tab stacks via React Navigation, a custom bottom bar
(hidden under pushed pages, which is what the review flow is), and a theme resolved on the Kotlin
side.
JS talks to the Kotlin logic through bridge modules in `app/src/main/java/com/gaten/app/rn/`
(dictionary search, Anki review/export, WaniKani, theme, haptics, recognition downloads, platform
utilities) — no business logic is duplicated in JavaScript.

The accessibility lookup overlay retains its independent service window and navigation, still
drawn in Compose inside `OverlayWindowController` — it never touches the activity UI.

Dependencies: Kotlin `2.2.21`, AGP `8.11.0`, RN `0.86.3` (New Architecture) with
`react-native-reanimated` `4.7` + `react-native-worklets` `0.13`, React Navigation `7`.
Motion: a 380 ms emphasized horizontal page slide for tabs (bottom-tabs `transitionSpec` + a
custom scene interpolator) and for pushed pages (native-stack, platform animator), the review's
step swaps on a Reanimated shared value with parallax, staggered list entrances and a Material
tab indicator in the bottom bar — see `docs/rn-migration/PLAN.md` § Motion. The overlay keeps its
own sheet slide. Appearance settings include optional feedback sounds, off by default; haptics
respect the phone's settings.

## Building from source

Requirements: JDK 17 or newer and an Android SDK with `compileSdk 36` (set `ANDROID_HOME` or
`local.properties`), plus Python 3 for the dictionary builder and Node.js 22+ for the React
Native toolchain.

```sh
# 1. Generate the dictionary asset (stdlib only; downloads JMdict/JMnedict and verifies SHA-256).
python3 tools/build_jmdict_db.py

# 2. Generate the example-sentence asset (stdlib only; downloads the Tanaka Corpus, SHA-256 pinned).
python3 tools/build_examples_db.py

# 3. Install the JavaScript dependencies.
npm install

# 4. Build the debug APK (debug builds load JS from Metro: run `npm start` before launching).
./gradlew :app:assembleDebug
```

Both database assets (`app/src/main/assets/db/jmdict.sqlite` and
`app/src/main/assets/db/examples.sqlite`) are generated and gitignored, so the build fails early with
a clear message if either builder was skipped.

## Stylized-text model (manga-ocr)

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
them (manga-ocr's ONNX sessions and ML Kit's Japanese recognizer). The next lookup builds them again
from local files or APK assets, with no download; this first lookup may take longer.
Cleanup waits for active recognition, including ML Kit tasks whose awaiting lookup was cancelled.
The memory reduction has not been measured on the current version.

The model is ~117 MB and is **bundled in every build**: Gradle fetches the three weights into
`app/src/main/assets/manga-ocr/` (gitignored) before the assets are merged, the APK carries them,
and the app copies them into its own storage the first time the second pass runs. A fresh install
therefore has manga-ocr with no download and no network. There are three ways the model can reach
`filesDir/manga-ocr/`:

- **The app's own bundle** — the default. **Settings → Text recognition** shows the model as
  **Bundled** and offers **Install bundled model** for copying the weights out early; the first
  lookup does it on its own, offline. **Remove model** takes them off and keeps them off, until
  **Install bundled model** is tapped again.
- **Download it in the app** — only for a build made without the bundle
  (`-PbundleMangaOcr=false`): **Settings → Text recognition → Download**. The size is shown first and
  has to be confirmed (with a warning when the phone is on mobile data), the download runs with a
  progress bar and a Cancel button, and the only hosts it talks to are `huggingface.co` and the CDN
  it redirects to, over HTTPS. Nothing is fetched until that button is tapped. WaniKani is the app's
  only other network use, and only with a token connected: a kanji page when one is tapped, and the
  account's own study progress when the Analytics tab is open. The files come from
  [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX)
  (`encoder_model_int8.onnx`, `decoder_model_int8.onnx`) and
  [kha-white/manga-ocr-base](https://huggingface.co/kha-white/manga-ocr-base) (`vocab.txt`).
- **Import files you already have** — the same section's **Import model files** takes
  `encoder_model_int8.onnx`, `decoder_model_int8.onnx` and `vocab.txt` from device storage; they are
  recognised by name, so nothing has to be renamed. `tools/fetch_manga_ocr.sh [dir]` downloads them
  on a computer and prints the matching `adb push` command.

`-PbundleMangaOcr=false` skips the fetch and produces an APK with no model, where Download is the
only way in.

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

## Optional WaniKani reference pages

Gaten is an offline reader, so none of this is on by default. Connect a WaniKani personal access
token and the kanji of a looked-up word get a second layer: WaniKani's own reference content, fetched
for the one character that was tapped and kept on the device.

**What it shows.** Under the entry for a looked-up word, each distinct kanji of its headword is a
tile. Tapping one opens WaniKani's page for that kanji inside the sheet — its meanings, its on'yomi
and kun'yomi, the radicals it is made of, and WaniKani's meaning and reading mnemonics — with the
dictionary entry one Back away. Tapping a radical in that composition opens the radical's own page,
drawing included: the radicals no font has are rendered from the SVG WaniKani serves for them. A
button on either page opens the real page on `wanikani.com` in the browser.

**What it reads, and what it writes.** Three things here are the account's own. `GET /user` is read
for the subscription's entitlement, so content above the account's levels is marked as such rather
than shown (a free account covers levels 1–3). And with a token connected, the **Analytics** tab reads
the account's study progress: the level, the reviews available now and over the next 24 hours
(`GET /summary`), and how many items sit in each of WaniKani's review stages — six count-only
`GET /assignments` requests that ask for the count of a filtered scope and throw the page away. The
**Review** page reads the review queue itself — `GET /assignments?immediately_available_for_review=true`
and the `GET /subjects` behind its ids — and sends each finished subject back with one `POST /reviews`.
It also reads the lesson queue (`GET /assignments?immediately_available_for_lessons=true`) and starts
a finished lesson with `PUT /assignments/<id>/start` — the only documented way to complete one, since
a lesson quiz is never sent as a review (see *Reviewing WaniKani subjects in Gaten*). No other account
data is read, and the only writes are the review or lesson the reader has just finished. The Anki
export is untouched: nothing from WaniKani goes into a note, and no Anki card gains a WaniKani view.

**Connecting.** Create a token on WaniKani's *Personal access tokens* page — read access alone is
enough for these pages, and reviewing what is due also needs the permission WaniKani lists for
creating reviews — and paste it into **Settings → WaniKani**. Gaten checks it against `/v2/user`
before storing it, so a token on the device is always one WaniKani accepted, and the field is cleared
the moment it is saved: the token is never shown or logged again, and it is sent only to
`api.wanikani.com` — the image requests to `cdn.wanikani.com` carry no token at all. It is stored in
app-private storage outside the backup set, encrypted with an Android Keystore key, so it is in
neither a cloud backup nor a device-to-device transfer. **Disconnect** deletes the token and every
cached page.

**Offline and caching.** A page is cached as it is fetched; from then on the saved copy answers
without touching the network at all, and the sheet says so. A miss while offline says exactly that,
and **Retry** asks WaniKani again. Nothing is prefetched: a request is made only when you connect,
recheck, or open a kanji or radical.

**Coverage limits.** WaniKani's content covers its own curriculum — large, but not every kanji. A
kanji outside it has no page, and Gaten says so rather than inventing meanings, readings or
mnemonics; a radical above the account's level says that too. Radical names are WaniKani's own
mnemonics, not linguistic definitions. Every kanji of a word stays tappable either way, and each of
these outcomes is stated in the sheet instead of leaving an empty page.

**The token cannot be typed in the overlay.** The lookup overlay belongs to the accessibility
service and cannot offer a keyboard reliably, so a kanji page with no token connected — or with a
token WaniKani has rejected — explains that and offers **Open Gaten**, which brings this app up over
the overlay.

**Requests.** Connecting and rechecking ask WaniKani for the account (`GET /user`), sending the token
in an `Authorization` header; opening a kanji or one of its radicals sends that character or that
subject id, with a kanji's composition adding its own radicals' ids. All of it goes to
`api.wanikani.com` over HTTPS, plus `cdn.wanikani.com` for the SVG of a radical that has no
character. Redirects are refused outright and the token is never logged. The scanned sentence, the
other words of the lookup and the screenshot are never sent. The account's own study progress — the
level, the review availability and the per-stage counts the Analytics tab shows — is requested only
for that tab, from `api.wanikani.com` alone, and kept on this device; the queues the **Review** page
opens are requested only for that page and kept nowhere. Nothing else about the account is read, and
the only writes are the review or lesson the reader has just finished — see *Reviewing WaniKani
subjects in Gaten*.

**Attribution.** WaniKani and its content belong to [Tofugu LLC](https://www.wanikani.com/). Gaten's
WaniKani pages are an unofficial third-party reference: Gaten is not affiliated with or endorsed by
WaniKani, and no WaniKani content is bundled in the APK or exported to Anki — each page is fetched
through the public API when the reader asks, and cached on that device.

## Reviewing in Gaten

There is no Review tab: the bottom navigation is **Home / Analytics / Settings**, and studying is a
page Home opens. Home's **Anki** and **WaniKani** cards are the two ways in — the Anki card opens the
deck list directly (or the state that stops it: no AnkiDroid, no permission, no collection), and the
WaniKani card opens the connection state, or the WaniKani page once a token works. There is no
landing page and no separate start step: choosing a deck opens its session, and WaniKani opens a page
showing what is due for review and which lessons are waiting; each row opens what it holds. The page
is full-screen, so the floating bottom navigation is away for all
of it and Back is one step out.

### Reviewing Anki cards

AnkiDroid still does everything that decides when a card comes back: Gaten asks it for the next due
card, shows the question, reveals the answer, and sends the rating you pick back to it through the
same content provider AnkiDroid shares with other apps. Nothing is scheduled, counted or remembered
on Gaten's side, and no network is involved — this is on-device provider access, like the export.

- **Decks.** Every deck AnkiDroid reports is listed with its own counts, and tapping one starts it —
  there is no separate start step and no selection to set first. The deck's queue, its limits and its
  scheduler are AnkiDroid's; Gaten never invents a card order.
- **Cards.** The question and answer are rendered by AnkiDroid, styling included, in a WebView that
  runs no JavaScript and has no file, content or network access. Gaten's own chrome is the compact
  header — the deck's name and the reader's place in it, *x* of *y* — the progress line under it, the
  reveal button, the rating buttons (each labelled with the interval AnkiDroid reports for it), and,
  on the answer side, the WaniKani kanji of the note's own fields — the question side never shows
  them, so the answer cannot leak through a kanji page.
- **Ratings.** One tap sends one rating: Again, Hard, Good or Easy, in the order AnkiDroid's intervals
  arrive. The buttons are disabled while a rating is in flight, and a rating whose outcome AnkiDroid
  did not confirm is never sent a second time (the provider applies every update it receives and
  offers no undo), so the screen then only offers the next card.
- **Nothing due** is a finished deck, shown as such, and kept apart from any failure.
- **What Gaten cannot show.** AnkiDroid serves media files to other apps for *insertion* only, so
  images and sounds in a card do not appear here; the screen says how many the card uses and offers
  **Open AnkiDroid** for cards whose media really matters. A card with a typed answer shows the answer
  as AnkiDroid renders it, without the interactive comparison, so the rating stays the reader's own —
  which is how Anki grades anyway. MathJax and any other script-driven rendering do not run either,
  for the same reason: no JavaScript.

### Reviewing WaniKani subjects

With a token connected, Home's WaniKani row opens one page with two rows: what is due for review, and
the lessons that are waiting. Without a token it opens the connection screen first.
Each row is the whole action — the count it holds with its caption on the right, and a green check with
`All caught up` when that queue is verified empty; a locked account says so honestly instead. Both rows
stay visible whatever their state, and the next review time is shown when WaniKani gives one.

- **The queue** is WaniKani's: `GET /assignments?immediately_available_for_review=true&hidden=false`,
  then one `GET /subjects?ids=…` for the subjects behind those assignments, ordered by when each came
  up. It is read fresh every time and kept nowhere — an item answered a minute ago is a stale
  question.
- **The prompts** are the subject's own. A kanji or a vocabulary asks for its meaning and then its
  reading; a radical and a kana vocabulary ask for their meaning alone, because that is all WaniKani
  records for them. A radical WaniKani has no character for is drawn from the same cached image the
  reference pages use — its name is the answer, so its name cannot be the prompt.
- **Answering** compares against the answers WaniKani itself accepts: the meanings it marks
  `accepted_answer` plus its whitelisted auxiliary meanings, and its accepted readings folded to
  hiragana. A reading typed in romaji is converted by the same WaniKana port the dictionary search
  uses, so `yama` and `やま` are the same answer. A wrong answer is counted, shows the right one, and
  has to be typed before the subject moves on — WaniKani's own rule.
- **Submitting.** The review is sent once, when every part of that subject has been answered, with the
  number of wrong meaning and reading answers: `POST /reviews` with the subject id. A submission that
  fails keeps the finished answer and offers **Send again**; a token WaniKani refuses asks for a
  reconnect instead, because retrying a refused token would only fail again. Nothing is ever sent
  because an animation finished, and a review whose answer is still in the field is not sent at all.
- **Lessons.** The page's second row reads the lesson queue
  (`GET /assignments?immediately_available_for_lessons=true&hidden=false`) and teaches a batch of five
  one card at a time — the character or its image, the meanings and readings with their mnemonics, and
  example sentences for vocabulary — then quizzes each one exactly like a review. A subject is finished
  with `PUT /assignments/<id>/start`, which moves it into the review queue; WaniKani's own reference is
  explicit that a lesson quiz is not submitted as a review. Each subject is started the moment its quiz
  is passed, so leaving mid-batch keeps what was finished, and a failed start keeps the answer and
  offers **Send again** — starting an assignment twice is the same as starting it once.

## Project layout

- `.` (root package) — app entry points: main screen, lookup overlay, trigger receiver, Quick
  Settings tile, accessibility service.
- `ocr/` — on-device OCR: ML Kit Japanese as detector and fallback reader, the full-screen text index
  (`ScreenText*`, `ScreenHits`) and
  `OcrRepair`. The reader is `ScreenLineReader` (the per-session cache that replaces each line's text
  as manga-ocr reads it, and drops the lines whose final text has no Japanese), `MangaOcrModel`
  (where the model lives), `MangaOcrEngine` (running it), `OcrPreprocess` (the crop), `OcrModels`
  (when the OCR engines are released) and `OcrLinePolicy` (when an answer is kept).
  `MangaOcrCatalog` is the list of downloadable models and `MangaOcrDownloader` is the model
  download itself; the app's only other network code is the optional WaniKani client in `wanikani/`.
- `anki/` — AnkiDroid export plus deck, tags, field-mapping and card-template preferences, and the
  provider reader behind the in-app review screen (queue, rendered card HTML, rating).
- `wanikani/` — the optional WaniKani client: the token (Keystore-encrypted, stored off the backup
  set), the API calls to `api.wanikani.com` under the account's level entitlement, and the on-device
  cache of subjects and radical images.
- `dict/` — offline dictionary: SQLite access (`JmdictRepository`), typed-word search
  (`DictionarySearch`), OCR word scanning (`WordScanner`) and deinflection (`Deinflector`).
- `data/` — `BitmapRepository`, the in-memory hand-off of the captured screenshot.
- `rn/` — the React Native bridge: native modules exposing the dictionary, Anki, WaniKani, theme,
  feedback, recognition downloads and platform utilities to JS, plus the `GatenCardWebView`
  view manager used by the review card and the radical image.
- `src/rn/` — the React Native app: `native/` (typed bridge wrappers) and `ui/` (shell, tabs,
  screens). See `docs/rn-migration/`.
- `ui/lookup/` — the service-owned overlay: lasso layer, result sheet and the
  OCR + dictionary pipeline (still Compose, hosted in its own window).
- `ui/wanikani/` — the kanji and radical detail panel drawn inside the overlay sheet
  (`WaniKaniDetail.kt`).
- `ui/overlay/` — `OverlayWindowController`, the accessibility-overlay window host.
- `ui/components/` — `KanjiStrip` and `pressScale` used by the overlay result sheet.
- `ui/theme/` — the palette and `ThemePreferences` (the stored dark/light and accent
  choices the overlay, the floating bubble and the RN theme bridge read).
- `tools/` — `build_jmdict_db.py`, which builds the dictionary asset, and `fetch_manga_ocr.sh`,
  which downloads the optional manga-ocr model for pushing to a device.
- `docs/` — the historical feasibility study (see `docs/README.md`).

## Privacy

Reading is offline and stays offline. Gaten makes network requests for exactly two things, both of
them started by the reader and neither of them in the background:

- **The stylized-text model**, downloaded by tapping Download in a build made without the bundle,
  goes to `huggingface.co` and the CDN it redirects to, over HTTPS, and nowhere else
  (`ocr/MangaOcrDownloader.kt` enforces that allow-list, redirects included). The default build
  carries the weights and never fetches them.
- **WaniKani**, once a token is connected: connecting and rechecking send the token to
  `api.wanikani.com` for `GET /user` (the one account call, read only for the subscription's level
  entitlement), and opening a kanji sends that character — a radical, its id, a kanji's composition
  the ids of its own radicals — in the request for that one page. `cdn.wanikani.com` is asked only
  for the SVG of a radical that has no character. All over HTTPS, with redirects refused. The scanned
  sentence, the other words, the screenshot and everything else looked up stay on the device. The other
  account data read is the study progress the Analytics tab shows — the level, the reviews available
  now and over the next 24 hours, and the counts per review stage — and, when the reader reviews,
  what is due now: `GET /assignments?immediately_available_for_review=true` and the `GET /subjects`
  behind its ids. All of it is from `api.wanikani.com` and all of it is kept on this device. The one
  thing ever written back is a review the reader has just finished, sent as `POST /reviews` with the
  subject's id and the number of wrong meaning and reading answers; no lesson is submitted and nothing
  else about the account is read. Nothing is
  prefetched: with the network gone the saved pages still answer, and nothing else happens.

The WaniKani personal access token lives in app-private storage outside the backup set
(`noBackupFilesDir`), encrypted with an Android Keystore key: it is not in a cloud backup and not in
a device-to-device transfer. It is never logged, never shown again after it is saved, and Disconnect
deletes it along with every cached page — reporting it if any of that could not be removed.

Of the account itself Gaten reads three things. One is the subscription's level entitlement — plan,
level and expiry — used only to refuse a subject above the account's levels. Another is the study
progress the **Analytics** tab shows: the level, the review availability over the next 24 hours and
the counts per review stage, as described above. The third is the review queue, read only when the
reader opens it. The cache holds the first two: the entitlement, and one small snapshot per connected
account (keyed by a digest of that account's token, never the token). The queue is never cached at
all. What the Analytics tab reads of them is only the aggregate — the count of a filtered scope, from
six `GET /assignments` requests whose rows are read past and discarded, plus the review availability
above. The only write is a review the reader has just answered: one `POST /reviews` carrying the
subject's id and how many times its meaning and reading were answered wrongly. No lesson is
submitted, and no assignment is changed in any other way.

Nothing else is fetched, and the screenshot never leaves the device: it stays in memory, is never
uploaded, never shared and never written to disk. Reviewing Anki cards changes nothing here either:
it reads and answers cards through AnkiDroid's own local content provider, on the device, and asks
AnkiDroid for every scheduling decision. There is no telemetry and no server of Gaten's own: nothing
about the reader is collected, profiled or sent anywhere, and the Analytics tab draws only the
numbers described above, from WaniKani and from AnkiDroid on this device.

The only notifications Gaten looks at are the accessibility events Android already sends it for a
posted notification, and it reads **metadata only**: whether an incoming call (`CallStyle`, incoming
call type) or a firing alarm (ALARM category with a full-screen intent) was posted, so the lookup
closes for those before their own window appears. No notification title, text or other content is
read, kept, logged or stored. This needs no extra permission; it is a side effect of the
accessibility service's event subscription.

The optional notification-listener access offered under **Settings → Screen lookup** is separate and is used for one
thing only: reading the active media sessions so the video behind the overlay can be paused and
resumed. It deliberately reads nothing from the notifications it is handed, and declining it leaves
every lookup feature working (only the session-based pause gives way to a media key press).

ML Kit's transport library and ONNX Runtime also declare `INTERNET` for their own analytics and
telemetry backends. Gaten's code never calls those, but while the permission is in the merged
manifest the OS no longer blocks them — removing it would block them, the model download and the
WaniKani client with it. That is the trade the two features cost, and it is why the permission is
documented here rather than left implicit.

## Credits and licences

- **Gaten** is licensed under the **GNU GPL v3.0 or later** (see `LICENSE`), and is derived from
  [CircleToSearch](https://github.com/AKS-Labs/CircleToSearch) by AKS-Labs. See `NOTICE` for the
  full third-party attribution list.
- **Latin typeface** — [Manrope](https://github.com/googlefonts/manrope), SIL Open Font License 1.1;
  the font and licence are bundled with the app.
- **Romaji conversion** — [wanakana-kt](https://github.com/esnaultdev/wanakana-kt) 1.1.1, MIT.
- **Dictionary data** — JMdict and JMnedict © Electronic Dictionary Research and Development Group
  (EDRDG), used under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); see
  <https://www.edrdg.org/>.
- **Example sentences** — the Tanaka Corpus, © Electronic Dictionary Research and Development Group
  (EDRDG), used under
  [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); bundled with the app as an
  offline SQLite asset and read with no network.
- **WaniKani reference content** — [WaniKani](https://www.wanikani.com/) (Tofugu LLC), read through
  its [public API](https://docs.api.wanikani.com/20170710) under WaniKani's own terms when the reader
  connects a token. Nothing from WaniKani is bundled with the app: each kanji or radical page is
  fetched on request and cached on the reader's device, and Gaten is not affiliated with WaniKani.
- **OCR** — [ML Kit](https://developers.google.com/ml-kit/terms) (Google, ML Kit terms) detects the
  lines and reads them when no manga-ocr model is installed.
- **Stylized-text OCR** — [manga-ocr](https://github.com/kha-white/manga-ocr) (kha-white,
  Apache-2.0) through the ONNX export at
  [onnx-community/manga-ocr-base-ONNX](https://huggingface.co/onnx-community/manga-ocr-base-ONNX),
  run on [ONNX Runtime](https://onnxruntime.ai/) (Microsoft, MIT). The model files are fetched by the
  build and bundled with the app.
