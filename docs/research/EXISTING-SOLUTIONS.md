# Existing Android solutions: "circle Japanese text on YouTube → OCR → word + reading + English → Anki"

Desk research (no on-device testing). Scope: **Android only**; iOS deliberately ignored.
Question asked: does an app already do this whole workflow, so we don't rebuild it?

> **Update — the starting repo has since migrated.** Gaten no longer uses an accessibility service at
> all: capture now comes from a MediaProjection session the reader switches on (*lookup mode*,
> mirroring **Dokuen Japanese Reader**), and the overlay is a `TYPE_APPLICATION_OVERLAY` window behind
> the ordinary "display over other apps" permission. No accessibility service is enabled for it, which
> is what apps that refuse to run beside one — most banking and payment apps — check for. Everything
> below about "the starting repo's accessibility path" describes the version this study was written
> against; README.md (*Setup on a phone*, *Privacy*) is the current description.

The intended workflow, as 5 steps (referenced by number throughout):

1. **Trigger** a selection overlay **over another app** (here: the YouTube app) without leaving it.
2. **Region-select** (circle / drag box) the area containing Japanese text.
3. **Capture + on-device Japanese OCR** of that region.
4. **Popup** showing the Japanese word + kana reading + English meaning.
5. *(Optional)* **Add to Anki**.

---

## 1. Summary / verdict (read this first)

**No single existing Android app is confirmed to do all 5 steps for *another app's pixels* (YouTube) end-to-end.** Every step is individually solved somewhere, by several apps — but the "overlay over an arbitrary app" world and the "dictionary + Anki" world are largely separate projects.

| Question | Short answer |
|---|---|
| Whole 5-step workflow in one app, over YouTube? | **No confirmed app.** Closest single app is **Chimahon** (screen-OCR over any app + Yomitan dictionaries + Anki mining + a built-in YouTube extension with frame OCR) — but its screen OCR is full-screen (not drag-select) and the "over any app" path with Anki is **[unverified]**. |
| Steps 1–3 (overlay over any app, region select, on-device Japanese OCR) | **Already solved, multiple times**: Kaku, meikidroid, Mangayaku, Chimahon, and every generic "screen translator". |
| Step 4 (word + reading + English popup) | **Solved**: Kaku (JMdict: expression/reading/gloss + deinflection), meikidroid (jpdb.io), Yomihon/Chimahon (Yomitan dictionaries), OCR Manga Reader (EDICT). |
| Step 5 (Anki) | **Solved only inside reader apps** (Yomihon, Chimahon, OCR Manga Reader, TachiyomiOCR fork, Migaku) or for **web text** (Yomitan + AnkiconnectAndroid + AnkiDroid). No overlay-OCR app besides Chimahon(likely) is confirmed to mine to Anki. |
| Google Circle to Search / Lens | **Explicitly excludes video**: translate mode "works for most content types … but not videos and GIFs" (Google help). Not a dictionary, no readings, no Anki. |
| Reading YouTube's caption text via accessibility (no OCR) | **Not viable as the primary path.** YouTube does not document exposing caption text to other apps; treat video text as **pixels**. |
| Reuse advice | **Extend the existing CircleToSearch repo**, reusing proven components from the projects below (OCR model, dictionary format, AnkiDroid bridge) rather than inventing them. See §4. |

Key correctness facts that constrain the design (verified, §6 has links):

- `AccessibilityService.takeScreenshot()` (API 30+) returns `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` when the target sets `FLAG_SECURE` — you cannot capture DRM/protected surfaces, by design.
- `MediaProjection` (used by Kaku/meikidroid/Mangayaku/Chimahon) requires **per-session user consent on Android 14+**, and also respects content protection.
- Google's own Circle-to-Search **translate** feature is documented as **not working on video/GIF** — so it does not satisfy this use case even where available.

---

## 2. Comparison table — strongest candidates vs the 5 steps

Legend: ✅ yes · 🟡 partial / with caveats · ❌ no · ❓ unverified · n/a not applicable.
"Over other apps" = works as an overlay on top of an app it does not own.

| App | Platform / status | 1. Overlay over other apps | 2. Region/drag select | 3. On-device JP OCR (vs cloud) | 4. Reading + English popup | 5. Anki | License / cost | Root / FLAG_SECURE |
|---|---|---|---|---|---|---|---|---|
| **Kaku** (画) | Android 4.4+; F-Droid + Play; **code last updated 2022-07** (stale); free | ✅ "stays on top of all your apps" | ✅ draggable/resizable capture window | ✅ on-device **Tesseract** `jpn` + `jpn_vert` (vertical) | ✅ JMdict + KanjiDict2: expression, **reading**, English gloss, **deinflection**, furigana handling | ❌ (can send text to Google Translate only) | **BSD-3-Clause** (open source) | No root; uses MediaProjection → respects FLAG_SECURE |
| **meikidroid** | Android; GitHub releases; **new (Jun 2025), single author, 36★** | ✅ background service + toggle overlay | ✅ pre-selected OCR region | ✅ "Google Lens OCR" **[engine unverified]** | ✅ jpdb.io: vocab, **readings**, frequency | ❌ | **No license file → all rights reserved** (cannot legally reuse) | No root; MediaProjection |
| **Mangayaku** | Android 10+; APK from GitHub; **new (Jul 2026), 5★**; GPL-3.0 | ✅ "floats over *any* app" | 🟡 adjustable capture guides (not per-bubble drag) | ✅ on-device **manga-ocr** (ONNX, 228 MB download) — vertical/furigana/SFX | ✅ reading + gloss + pitch accent + JLPT tag (translation via ML Kit on-device or your LLM) | ❌ **no Anki in README/source** | **GPL-3.0** | No root; MediaProjection (per-session consent) |
| **Chimahon** | Android 8+; GitHub; **active** (221★, pushed Sep 2026); GPL-3.0 | ✅ "Screen OCR Lookup — **Any App**"; **built-in YouTube extension** + video-frame OCR | ❌ **whole-screen/page** OCR (open issue #94 asks for drag-select box) | ✅ on-device OCR (local, no internet needed) | ✅ native **Yomitan** dictionaries (expression/reading/gloss) + pitch | ✅ "Direct Mining" to AnkiDroid from lookups | **GPL-3.0** | No root; MediaProjection |
| **Yomihon** | Android 8+; GitHub; active (131★); Apache-2.0 | ❌ **in-app reader only** (Mihon fork) | ❌ (reads its own manga pages) | ✅ on-device OCR (fast) + optional online models | ✅ Yomitan dictionaries | ✅ one-click Anki cards | **Apache-2.0** | No root |
| **OCR Manga Reader** | Android 4.0+; SourceForge; **old (v9.8)**; free/OSS (license unverified) | ❌ in-app CBZ/CBR/PDF viewer | ✅ draw a box over the word in its own viewer | ✅ offline OCR; vertical + horizontal; ignores furigana | ✅ EDICT/ENAMDICT/kanji + optional EPWING; expression, reading, gloss, conjugation, frequency | ✅ **export to AnkiDroid** (app installed) | OSS (license unverified) | No root |
| **Rattlehead15/tachiyomiOCR** | Android; GitHub; **abandoned** (Tachiyomi fork); Apache-2.0 | ❌ in-app Tachiyomi reader | ✅ region select in reader | ✅ Tesseract jpn (bundled) | ✅ popup dictionary + deinflection | ✅ AnkiDroid export button | **Apache-2.0** | No root |
| **Migaku** | Android app (Play); **paid**, closed source | ❌ mobile app + browser extension; "click words in YouTube subtitles" (its own player/extension) | ❓ | ❓ (likely OCR/subs in its own player) | ✅ dictionary card, readings | ✅ flashcards | Proprietary / subscription | No root |
| **Google Circle to Search / Lens** | Pixel 6+ and select OEM devices; built-in | ✅ overlay over any app | ✅ circle/highlight/tap | ✅ (Lens OCR, mostly cloud) | 🟡 translation + search results; **no kana reading + English dictionary entry** | ❌ | Proprietary | n/a |
| **Google Translate (app)** | Android; free | ❌ overlay only on *copyable* text (Tap to Translate); can OCR a **photo/screenshot imported into the app** | ❌ | ✅ OCR (photo/camera), offline packs | ✅ translation; ❌ no kana/English dictionary entry | ❌ | Proprietary | n/a |
| **Live Caption** | Android system; Pixel + selected devices | ✅ captions anything playing | n/a | ✅ on-device speech→text (JA captions) | ❌ caption text only; **no word/reading/English dictionary**, translation is Pixel-only Live Translate | ❌ | Proprietary | n/a |
| **Yomitan / 10ten** | **Browser extension** (Firefox Android, Kiwi/Chromium); free/OSS | ❌ only web page text, **not arbitrary screen pixels** | ❌ | n/a (reads DOM text) | ✅ full Yomitan dictionaries | ✅ via **AnkiconnectAndroid + AnkiDroid** (see §3) | GPL-3.0 / MIT | n/a |
| **EZ Screen Translator / Translate On Screen / Tap To Translate Screen** | Android Play; 5–10M+ installs; freemium, closed source | ✅ floating overlay over any app | 🟡 whole screen or region | ✅ OCR (ML Kit / engine-dependent), some offline | 🟡 translation overlay; **usually no kana reading / dictionary entry** | ❌ | Proprietary (ads/IAP) | No root; respects FLAG_SECURE |
| **overlay-translator / screen-trans / android-screen-translator** (OSS) | Android; GitHub; free/OSS | ✅ floating overlay | 🟡 full-screen or word-pick | ✅ on-device ML Kit / PaddleOCR / manga-ocr | 🟡 word-lookup card with pronunciation/POS (LLM engines) | ❌ (no first-class Anki) | Apache-2.0 / other | No root |

Notes:
- **"Overlay over YouTube"** requires either an accessibility overlay + `AccessibilityService.takeScreenshot()` (what the starting repo does) or an overlay + `MediaProjection` (what Kaku/meikidroid/Mangayaku/Chimahon do). Both are blocked by `FLAG_SECURE`/DRM.
- **Normal YouTube playback is not `FLAG_SECURE`** (ordinary screenshots of YouTube work), so capture should succeed for regular videos; **purchased/DRM movies and some protected content may differ** — verify on device. *(This last point is [INFERENCE], not a cited fact.)*

---

## 3. What is already solved, and by what

- **Trigger + overlay over any app**: solved many times (Kaku, meikidroid, Mangayaku, Chimahon, all the screen-translator apps). The starting repo's accessibility-service + `takeScreenshot()` path is a legitimate, modern approach and avoids the MediaProjection consent dialog.
- **Region selection**: solved in Kaku, meikidroid, TachiyomiOCR, OCR Manga Reader, the starting repo, and most screen translators. Chimahon notably lacks drag-select (its issue #94).
- **On-device Japanese OCR of screen pixels**: solved with several engines —
  - **Tesseract** `jpn` / `jpn_vert` (Kaku, TachiyomiOCR, the starting repo's chosen engine). Works, weakest on manga-style vertical/ruby text.
  - **manga-ocr** (Apache-2.0) ONNX — purpose-built for Japanese manga: vertical text, furigana, SFX. Used by Mangayaku and Chimahon; also in `overlay-translator`.
  - **Google ML Kit** text recognition (CJK) — on-device, used by many translators.
  - **PaddleOCR / PP-OCRv5/v6** on-device — multilingual, used by `overlay-translator`.
- **Word + reading + English dictionary**: solved by **Yomitan-format dictionaries** (expression/reading/gloss/pitch/frequency) — JMdict/KANJIDIC ports exist as Yomitan dictionaries (`yomidevs/jmdict-yomitan`, `MarvNC/yomitan-dictionaries`). Native Android consumers: Chimahon and Yomihon (via `hoshidicts`), Kaku (JMdict/KanjiDict2 SQLite + deinflector), OCR Manga Reader (EDICT). A Japanese tokenizer (**kuromoji** / **janome** / **Sudachi**) is the standard component for splitting OCR text into words.
- **Anki integration on Android**: two proven paths —
  1. **In-app mining directly to AnkiDroid** (Yomihon, Chimahon, OCR Manga Reader, TachiyomiOCR fork) — likely via `AnkiDroid` intents / the AnkiDroid API.
  2. **Yomitan (browser) → `AnkiconnectAndroid` → AnkiDroid** — a reimplementation of AnkiConnect that speaks Yomitan's API, so the standard desktop mining workflow works on Android **for web text** (not screen pixels).
- **The Android equivalent of a desktop "hover dictionary over any screen"**: **Kaku** is explicitly described as "the closest you can get to browser extensions like Rikaikun/Rikaisama/Yomichan on Android", i.e. the exact concept, but with a 2019/2022-era implementation, no Anki, and no JLPT/frequency layer.

**Desktop prior art** (out of scope, but shows the workflow is well-trodden; don't rebuild these): YomiNinja, KanjiTomo, Textractor, LunaTranslator, asbPipe/asbplayer, Migaku extension. These confirm the pipeline "capture window → OCR → tokenize → Yomitan dictionary → Anki", but none run as an Android overlay over another app.

---

## 4. Recommendation

**Extend the existing `CircleToSearch` repo, but first reuse the solved pieces; do not start the OCR/dictionary/Anki layers from scratch.**

Reasoning:

1. **The only thing missing in the overlay world is steps 4–5.** Kaku/meikidroid/Mangayaku already prove steps 1–3 over arbitrary apps; the "Japanese word + kana reading + English meaning + Anki" layer is missing from the overlay apps (Kaku has the dictionary but no Anki and is stale; meikidroid has the dictionary but no Anki and no licence; Mangayaku has readings but no Anki; Chimahon has Anki but no drag-select and is a reader-first app).
2. **The starting repo already owns the hard, fragile part** — accessibility-service capture + region selection over another app, with `takeScreenshot()` (no MediaProjection consent popup, GPLv3).
3. **Prefer reusing an existing app if it passes a 30-minute on-device test.** Before writing any code, install and test **Chimahon** (screen OCR over any app + Yomitan + Anki + YouTube extension) and **Mangayaku** (overlay OCR + readings) on the target phone. If either satisfies the workflow on YouTube, extending is unnecessary.
4. **Concretely reuse these components** when extending:
   - OCR: **manga-ocr** (Apache-2.0) for vertical/ruby video text; keep Tesseract `jpn` as fallback.
   - Dictionary: **Yomitan-format JMdict/KANJIDIC** (already the lingua franca; Chimahon/Yomihon consume it) or Kaku's JMdict+deinflector code (**BSD-3-Clause**, GPLv3-compatible with attribution).
   - Tokenizer: **kuromoji** / **janome** (Apache-2.0).
   - Anki: reuse the **AnkiDroid** intent/API path (as Yomihon/Chimahon do) or the **AnkiconnectAndroid** approach for a local AnkiConnect endpoint Yomitan-style.

**Licence watch-outs for reuse (the starting repo is GPLv3):**

- **GPL-3.0** (Chimahon, Mangayaku) — safe to link/copy into a GPLv3 project; obligations stay GPLv3.
- **Apache-2.0** (Yomihon, TachiyomiOCR, manga-ocr, kuromoji) — one-way compatible with GPLv3; keep notices.
- **BSD-3-Clause** (Kaku) — permissive, compatible; keep the notice.
- **No licence** (meikidroid) — **cannot be reused** without permission.
- `comic-text-detector` models (used by Mangayaku) are **GPL-3.0**; the code that bundles them must be GPL-3.0.

**When "build from scratch" would be justified:** only if the on-device test shows that (a) YouTube's video surface cannot be captured (FLAG_SECURE/DRM), or (b) every existing overlay app fails on the specific dictionary/Anki need. Even then, most of the pixels→word→reading→Anki pipeline can be assembled from the OSS components above.

---

## 5. Links and last-known status

### Overlay OCR / Japanese screen dictionaries
- **Kaku (画) — Japanese OCR Dictionary** — GitHub `0xbad1d3a5/Kaku` (BSD-3-Clause, 242★, last code push **2022-07-28**); project page https://kaku.fuwafuwa.ca/ ; F-Droid `ca.fuwafuwa.kaku` (latest 1.3.78); Play listing linked from project page. Status: **functional but stale**.
- **meikidroid** — https://github.com/rtr46/meikidroid (36★, created 2025-06-09, last push 2025-06-25, **no licence**, requires jpdb.io API key). Status: **new, single-author**.
- **Mangayaku** — https://github.com/ChiuKuanHsun/Mangayaku (GPL-3.0, 5★, created 2026-07-19, pushed 2026-07-26; Android 10+, APK from releases, not on Play). Status: **very new**.
- **Chimahon** — https://github.com/Chimahon/chimahon (GPL-3.0, 221★, created 2026-03-27, last push 2026-09-28). Screen-OCR issue: #94. Status: **active**. (Earlier fork: `Sirush/chimahon-anime`.)

### Manga / VN readers with OCR + dictionary (+ Anki)
- **Yomihon** — https://github.com/yomihon/yomihon (Apache-2.0, 131★, pushed 2026-09-20). Status: **active**.
- **OCR Manga Reader for Android** — https://ocrmangareaderforandroid.sourceforge.net/ (SourceForge; v9.8; free/OSS; **licence unverified**). Status: **old but downloadable**.
- **Rattlehead15/tachiyomiOCR** — https://github.com/Rattlehead15/tachiyomiOCR (Apache-2.0, 88★). Status: **abandoned (Tachiyomi fork)**.
- **Mangatan** (Yomitan lookup + OCR + Anki; desktop-first, Flutter) — https://github.com/1Selxo/Mangatan (GPL-3.0, 96★); its **Android version is Chimahon**.
- **Kuro Reader+** — Play `br.com.kurotoshiro.kuro_reader` (reader; OCR/dictionary feature **unverified**). Status: **active**.

### Browser / web-text dictionaries (not arbitrary pixels)
- **Yomitan** — https://yomitan.wiki/ (Firefox Android + Chromium desktop). Works on **web text only**.
- **10ten Japanese Reader** — Firefox extension (`addons.mozilla.org/.../10ten-ja-reader`). Web text only.
- **AnkiconnectAndroid** — https://github.com/KamWithK/AnkiconnectAndroid (Yomitan → AnkiDroid bridge; IzzyOnDroid). This is the Android Anki bridge for web mining.

### Google / OEM
- **Circle to Search** help — https://support.google.com/android/answer/14508957 (states translate "not videos and GIFs"; feature on select devices).
- **Circle to Search continuous translate** — https://blog.google/products-and-platforms/products/search/real-time-translate-circle-to-search-android/ (Sep 2025).
- **Google Translate** — https://play.google.com/store/apps/details?id=com.google.android.apps.translate ; image translation help https://support.google.com/translate/answer/6142483 .
- **Live Caption** — https://support.google.com/accessibility/android/answer/9350862 (Japanese captions on Pixel 4/5 + selected Android phones).
- **Pixel Live Translate** — https://support.google.com/pixelphone/answer/11209263 (Pixel-only; media-language list limited).
- **Gemini Live screen sharing** — https://www.android.com/articles/gemini-on-android (conversational, not a dictionary popup).
- **Samsung Bixby Vision / OEM equivalents** — **[not independently verified in this pass]**; Bixby Vision offers text/translate on Samsung devices but is not a general "reading + Anki" tool.

### Generic on-screen OCR/translate overlays
- **EZ Screen Translator / Tap To Translate Screen** — Play `com.tap_to_translate.snap_translate` (5M+ installs, ads/IAP).
- **Translate On Screen** — Play `com.recognize_text.translate.screen`.
- **overlay-translator (Screen Translator)** — https://github.com/ciddwd/overlay-translator (Apache-2.0; on-device ML Kit/PaddleOCR/manga-ocr; word-lookup card).
- **screen-trans** — https://github.com/Yellow4Submarine7/screen-trans ; **android-screen-translator** — https://github.com/AidanPark/android-screen-translator ; **screen-translator** — https://github.com/ervareza/screen-translator .

### Anki
- **AnkiDroid** — https://github.com/ankidroid/Anki-Android (AGPL-3.0). No screen OCR; receives cards from other apps.
- **AnkiConnect** (desktop) — https://ankiweb.net/shared/info/2055492159 ; **Yomitan Anki docs** — https://yomitan.wiki/anki .

### Platform / API facts cited
- `AccessibilityService` screenshot error codes incl. `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` — https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- MediaProjection per-session consent + FGS type on Android 14+ — https://developer.android.com/media/grow/media-projection
- `FLAG_SECURE` blocks OS screenshots/recording/casting — https://ptkd.com/journal/android-flag-secure-prevent-screenshots
- Assist APIs — https://developer.android.com/reference/android/app/assist/package-summary
- Yomitan dictionary formats — https://yomitan.wiki/dictionaries ; JMdict port https://github.com/yomidevs/jmdict-yomitan ; https://github.com/MarvNC/yomitan-dictionaries
- manga-ocr — https://github.com/kha-white/manga-ocr

---

## 6. Explicitly unverified / caveats

Flagged rather than guessed:

1. **Chimahon "screen OCR over any app" + Anki together** — both are claimed in its README, but I could not confirm from source that the *overlay-over-another-app* capture path feeds Anki mining (the README's Anki bullets are framed around in-reader lookups). **Verify on device.**
2. **YouTube caption text as accessibility nodes** — Google's YouTube accessibility page does not state that caption text is exposed to other apps' accessibility services. **Working assumption: treat video text as pixels.** Verify with an accessibility-node dump on device.
3. **meikidroid's OCR engine** — README says "Google Lens OCR"; whether that is on-device ML Kit or a cloud call is **unconfirmed**. jpdb.io lookups are definitely cloud (API key required).
4. **Yomiwa** — Play description confirms camera/gallery OCR (offline, >4000 chars); **no documented system-wide overlay over other apps and no documented Anki export**. "No overlay / no Anki" is **[INFERENCE]** from the listing.
5. **Kaku on Android 14/15/16** — last code update 2022; whether the Play listing is still live and whether it installs/runs correctly on the target OxygenOS build is **unconfirmed**.
6. **Circle to Search availability on the OnePlus Nord 6 (OxygenOS 16)** — OxygenOS 15/16 marketing says Circle to Search is coming/supported, but the specific device list I could read did not enumerate the Nord 6. **Verify on device.** Regardless, CTS **translate excludes video** per Google's help page.
7. **Kuro Reader+** — I could not confirm a built-in Japanese OCR/dictionary feature from primary sources.
8. **Kantan Manga** — I only found an **iOS App Store** listing; an **Android version is unverified** (no Play listing found).
9. **OCR Manga Reader licence** — "free and open source" per its site, but the exact licence was not confirmed from a primary source; the GitHub repo I tried did not resolve.
10. **Migaku Android** — paid/closed source; its mobile workflow is described around its own player/browser extension ("click words in your phone's YouTube subtitles"), not a general screen-pixel overlay. Details **unverified**.
11. **"OxygenOS/Bixby Vision text OCR + translate"** — not independently tested in this pass.
12. **App-store "last updated" dates** are not all verified; GitHub `pushed_at`/`created_at` values were read from the GitHub API at the time of this research (Oct 2026) and are the most reliable status signals here.
