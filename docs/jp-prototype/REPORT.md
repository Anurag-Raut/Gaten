# YomiLens — Japanese screen lookup: build report

Date: 2026-10-11 · App id `com.akslabs.circletosearch` (upstream package kept) · debug APK, `versionName 0.5`

## Summary

| # | Stage | Verdict | Proof |
|---|---|---|---|
| 1 | Compile + package (3 ABIs) | ✅ WORKS | `BUILD SUCCESSFUL`, APKs on disk |
| 2 | Unit tests (deinflection, scanning, ranking) | ✅ WORKS | 28 tests, 0 failures |
| 3 | Accessibility activation | ✅ WORKS | service bound on emulator + moto g35 |
| 4 | Screen capture via `takeScreenshot()` | ✅ WORKS | 1080×2340/2400 bitmap, overlay focused |
| 5 | Freehand lasso region select | ✅ WORKS | trail + crop, `capture-region.png` |
| 6 | Japanese OCR (ML Kit on-device) | ✅ WORKS, with accuracy limits | `猫が水を飲む` → `描が水を飲む` |
| 7 | Word identification | ✅ WORKS | garbage + lone kana filtered, kanji words first |
| 8 | Dictionary panel (real JMdict) | ✅ WORKS | glosses/POS/kana + alternates rendered |
| 9 | **Re-trigger after closing** | ✅ FIXED | second capture opens a fresh lookup, not a web search |
| 10 | **Near-miss selection (partial lasso)** | ✅ WORKS | a lasso stopping short still returns the whole line |
| 11 | Google Lens / reverse search / uploads | ✅ REMOVED | deleted, not just disabled |
| 12 | OnePlus Nord 6 (Android 16 / OxygenOS) | ✅ core path verified | capture → lasso → OCR → dictionary on-device, see `nord6/` |
| 13 | Nord 6 panel screenshot + dismissal timing | ⚠️ NOT CAPTURED | phone was in use; overlay was gone before the shot |

Verified on the Android emulator (API 34, x86_64, cold boot), a physical **moto g35 5G (Android 15,
arm64)** and a physical **OnePlus Nord 6 (Android 16, OxygenOS `CPH2793_16.0.5.1200`, arm64)**.

## The workflow (and nothing else)

Bubble or QS tile → full-screen capture → **freehand lasso** → on-device OCR → dictionary-driven
word identification → panel with real JMdict entries. Tap a word for kanji / kana / POS / meanings;
homographs appear under **Other matches**.

* **OCR**: ML Kit on-device Japanese (`text-recognition-japanese:16.0.1`), model bundled in the app
  (`Jpan_ctc` + `Hani_ctc` loaded locally — measured in logcat). Bundled Tesseract `jpn+eng`
  (`PSM_SINGLE_BLOCK`) is the fallback. Fully offline, no API key.
* **Dictionary**: JMdict (EDRDG, CC BY-SA 4.0) compiled offline into `assets/db/jmdict.sqlite` —
  218,863 entries / 499,343 terms. Provenance: `dictionary/DATA.md`.
* **Forgiving selection**: the lasso is not treated as a crop box. The app recognises a wider region
  around it, keeps the per-line bounds the OCR engine reports, and snaps to the line(s) the gesture
  actually touched — so circling 猫が水を飲む a character short still yields the whole line instead
  of silently losing the verb. A gesture that lands between lines snaps to the nearest one.
* **Word identification**: longest-match scan + deinflection (godan/ichidan/する/来る/い-adjectives,
  passive/potential/causative/negative/polite/past/te/…), katakana→hiragana folding. Only real
  dictionary words are emitted, which is what filters OCR garbage. Ranking: kanji-bearing words
  first, then text order; single-kana surfaces (が, を, ん) are dropped as noise.

## Fixed: the "second time it searches Google" bug

Root cause was two interacting upstream behaviours, not the OCR path:

1. `OverlayActivity` is declared `singleInstance`, so a re-trigger arrives via `onNewIntent` — but
   the old panel's dismiss only set `isJpLookupMode = false` and left the activity alive. The second
   bubble tap therefore re-showed the **upstream Circle-to-Search UI**, whose drag handler uploads
   the crop to an image host and opens Google/Bing/Yandex/TinEye.
2. `AndroidManifest` had no guard between the two drag consumers, so a lasso also reached that
   handler even in lookup mode.

Fix: the overlay is now a single-purpose screen — `OverlayActivity` renders only the frozen capture
plus `JpLookupOverlay`, every new capture bumps a composition key so it always starts in selection
mode, and closing it finishes the activity. The search handlers, WebView, uploader and Lens helper
were deleted outright.

Regression-tested on both the emulator and the phone: capture → lasso → panel, Back to close,
capture → lasso → panel again. Logcat contains **zero** Lens/WebView/upload activity.

## Removed (this is a different project from upstream, so the rest is gone)

Deleted outright, not hidden behind flags: the reverse-image-search uploader, the multi-engine
WebView, the Google Lens helper and its chooser, the search-engine/search-method preferences, the
QR scanner and its result sheet, the voice-interaction + recognition + assist services, the
configurable screen-edge gesture zones and their action/gesture model, the pinned-actions menu and
share sheet, flashlight/rotation/media-key actions, the copy-text overlay and its re-scanning, the
overlay-appearance settings screen, and the promo/donate/"friendly message" nudges.

Also gone: 66 dead string resources, and the accessibility declaration no longer requests
window-content, key-filter or gesture injection — the service reads pixels via `takeScreenshot()`
only. Kotlin sources: **8,061 → 3,478 lines**; `strings.xml`: **137 → 71 entries**.

## Known limitations

1. **Thin-stroke kanji are misread** (猫→描). Both ML Kit and Tesseract confuse 犭/扌 on thin CJK
   fonts; the crop itself is clean (652×144, ~130 px tall). One word is lost, the rest still resolves.
2. **`takeScreenshot()` fails on a snapshot-restored emulator** (`error code: 1`). Cold-booting the
   AVD fixes it; the same APK captures fine on the phone.
3. **Nord 6 / OxygenOS unverified** — no such device was ever attached.
4. **JMdict ships no frequency ranks** in this release, so homographs of one surface cannot be
   ranked; they are listed under "Other matches" instead of being guessed at.
5. **Nord 6 panel screenshot not captured.** The core loop is verified there (`nord6/`), but the
   device was in active use while I drove it over adb — KeyMapper was foreground for part of it and
   the notification shade kept taking focus — so the result panel was already gone by the time the
   screenshot fired. The code has **no timer** that closes the panel (only the 160 ms exit
   animation), so a panel that vanishes on its own would be a real bug worth chasing; the log shows
   the overlay's *task* being removed while another app took focus, which is consistent with an
   outside touch/system focus change rather than the app dismissing itself.

## Install and use

```bash
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb shell settings put secure enabled_accessibility_services \
  com.akslabs.circletosearch/.CircleToSearchAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Open any app → tap the **YomiLens bubble** → **draw a loop** around the Japanese text → read the
panel. Evidence for each lookup lands in
`/sdcard/Android/data/com.akslabs.circletosearch/files/jp-prototype/`
(`capture-full.png`, `capture-region.png`, `ocr-output.txt`).

## Evidence files

| File | Shows |
|---|---|
| `nord6/05-after-bubble.png` | Nord 6: lasso overlay open over the page |
| `nord6/crop.png` | Nord 6: the snapped two-line crop the app actually OCR'd |
| `nord6/ocr-short.txt` | Nord 6: OCR text + words (`日本語`, `勉強`, `楽しい`, `です`) |
| `emulator/v3-01-pass1.png` | emulator, pass 1: word list, `水` ranked first, alternates |
| `emulator/v4-01-short-lasso.png` | short lasso recovering the full line — `飲む` found |
| `device/v4-01-short-lasso-phone.png` | same near-miss test on the moto g35 |
| `device/v4-ocr-short.txt` | phone OCR text + emitted words for that near miss |
| `emulator/v3-03-pass2b.png` | emulator, pass 2 after closing: fresh selection → OCR → panel |
| `emulator/v2/07-jp-only.png` | no search activity during a lookup |
| `device/v2-04-result.png` | moto g35: bubble → capture → lasso → panel |
| `device/v3-03-pass1.png`, `device/v3-04-pass2.png` | phone, two-pass regression |
| `dictionary/DATA.md` | JMdict provenance, hashes, row counts, probe lookups |

## Source changes (high level)

| Area | Change |
|---|---|
| `OverlayActivity.kt` | JP-only overlay; per-capture composition key; dismiss finishes the activity |
| `CircleToSearchAccessibilityService.kt` | Reduced to capture + launch (1,196 → 132 lines); the floating bubble was deleted once the Plus Key became the trigger |
| `CircleToSearchScreen.kt` | **deleted** (2,127 lines: the upstream search UI) |
| `ui/components/JpLookupOverlay.kt` | Lasso, animated panel, ranked word list, alternates, evidence dump |
| `ocr/JapaneseOcr.kt` | ML Kit Japanese primary + Tesseract fallback |
| `dict/*` | JMdict repository, deinflector, complexity-ranked scanner (+ 28 unit tests) |
| `tools/build_jmdict_db.py` | Builds the bundled dictionary asset and `DATA.md` |
| `res/xml/accessibility_service_config.xml` | Screenshot-only capabilities |
| deleted | Lens/upload/WebView, voice+assist services, QR, gesture zones, copy-text, promo, settings screens |

Deferred by request: Anki integration (no stub left behind).

## Dictionary coverage (JMdict + JMnedict)

JMdict alone is not enough for screen text: it carries almost no proper nouns (東京/大阪/京都/横浜/
名古屋 are there, but 池袋, 新宿 and 秋葉原 are absent — verified against the produced database), so
「池袋弁」 used to resolve to the bare kanji 池 ("pond") because that was the longest match the
dictionary had. The asset now contains both dictionaries from the same frozen release:

| Table | Rows | Source |
|---|---|---|
| `entries` / `terms` | 218,863 / 499,343 | JMdict eng, **every** sense of every entry |
| `names` | 623,999 | JMnedict proper nouns (kanji forms, deduped against JMdict) |

Runtime cost: asset 55,791,616 → **94,584,832 bytes**; APK 104 MB → **140.1 MB**. Name rows are
labelled by what JMnedict says they are (`place name`, `surname`, `given name`; `' / '` joined when
a spelling is several) and are appended after ordinary words, so a name never outranks the word the
user probably wants. Ordering inside a spelling: entries carrying several types first (JMnedict
keeps the established reading as the merged entry — 新宿 しんじゅく = Shinjuku carries place+surname
while the rare あらじゅく does not), then type, then id. Measured: 池袋 → place name Ikebukuro,
新宿 → Shinjuku, 秋葉原 → Akihabara, 田中 → Tanaka.

On-device check (`device/v11-names-page.png`, lasso over `「池袋弁」を調べる`):

```
「池袋井                 ← OCR (弁 misread as 井; thin-stroke model limit)
池袋  池袋  池袋  Ikebukuro   ← the compound, not the single kanji
井    井    井    well
```

**Bug found and fixed while doing this:** `JmdictRepository` copied the asset to `filesDir` once and
then trusted that copy forever, so the update kept the old 55.8 MB database and the new query died
with `SQLiteException: no such table: names`. The copy is now re-extracted whenever the packaged
asset differs — compared from the 100-byte SQLite header both share (page size, page count, change
counter, `user_version`) instead of `openFd().length`, which fails for a deflated asset.


## Plus Key binding (OnePlus Nord 6, OxygenOS 16 / Android 16)

Facts established on the device (not assumptions):

| Observation | Evidence |
|---|---|
| The key is `/dev/input/event0` (`gpio-keys`), Linux scan code **735** (`BTN_TRIGGER_HAPPY32`) | `getevent -pl` lists exactly one key on that node |
| OxygenOS maps it to its own `KEYCODE_ACTION_BUTTON_CLICK` and intercepts it in the policy layer | `KEYLOG_Notify: INTERCEPT … interceptor = com.android.server.policy.StrategyActionButtonKeyLaunchApp` on every press |
| Apps never receive it | Our accessibility service with `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` logged nothing across a real press |
| Stock functions are presets only, none launches an app | `settings get secure action_button_support_all_fun` = `flash_memory;ring_mode;no_disturb;camera;flash_light;recording;translate;screen_shot;nothing`; `translate` renders a SystemUI card (`com.oplus.gesture.actionbuttonkey.ActionkeyCardWidgetProvider`) |
| App-uid log reading is impossible on this ROM | `run-as` + `logcat -s KEYLOG_Notify` produced no output even with `READ_LOGS: granted=true` |

**Dead ends, don't retry:** in-app accessibility key listener (proven dead), app-uid logcat
detection (proven dead), intent hijack (nothing to claim — the stock actions are a SystemUI card or
vendor-launched), screenshot detection (implemented, rejected), Tasker/Reddit project (paid, and it
needs the same logcat read this ROM blocks).

**Working binding — Key Mapper 4.5 Expert Mode + Shizuku, reading the raw input device:**

1. Developer options → **Disable permission monitoring** → ON. This is Shizuku's documented
   ColorOS/OnePlus fix; without it `pm grant` throws `SecurityException` and Shizuku reports
   *"The permission of adb is limited"*.
2. Start Shizuku (13.x) over adb; re-run after every reboot.
3. Key Mapper → Settings → **Expert Mode**: Shizuku → **Allow all the time** → *Start Expert Mode*;
   turn on **Auto start and keep alive**; allow it to run in the background.
4. Key map: Trigger → **Other** → *Record with Expert Mode* → press the Plus Key
   (records `Scan code 735`, device `gpio-keys`); Actions → **Send intent** → **Broadcast receiver**
   → Action `com.akslabs.circletosearch.action.TRIGGER_LOOKUP`, Package `com.akslabs.circletosearch`.
5. OxygenOS Plus Key function: **`nothing`** (so no stock action fires as well).

App side: `LookupTriggerReceiver` (exported, that action) → `CircleToSearchAccessibilityService.triggerCapture()`
→ screenshot → overlay. Verified via Key Mapper's action **Test**: `triggerCapture static called` →
`performCapture` → `OverlayActivity` focused (`device/v8-keymapper-test.png`).

The floating bubble is gone: with the Plus Key as the trigger it was redundant, so the service lost
its overlay window entirely (132 lines, no window of its own when idle). Re-verified after that
change — Key Mapper's Test still opens the overlay (`device/v9-no-bubble-test.png`), i.e. the
background-activity start never depended on the bubble. Triggers now: Plus Key, Quick Settings tile.

**Working press, and the one failure mode seen** (`device/v10-pluskey-works.png`):

```
KEYLOG_Notify … scanCode=735 …            ← before the fix: OxygenOS still received the key
(now that it works, these vendor lines disappear — Key Mapper holds the evdev grab)

D CircleToSearch: triggerCapture static called. instance=true
D CircleToSearch: performCapture called
D CircleToSearch: AccessibilityService launching OverlayActivity
```

Symptom: Key Mapper's **Test** fires the action, but a physical press does nothing. Cause (from
Key Mapper's own log): `Grabbing devices failed in system bridge: Disconnected` — the System Bridge
process is a leftover from before the app/service was restarted and is no longer bound to Key Mapper,
so grab requests go nowhere and no key is ever seen. The bridge's log shows the same desync from the
other side — `EvdevCallback binder died. Stopping evdev event loop` followed by
`Setting grab targets: []`, i.e. it is told to grab nothing at all. Key Mapper then logs
`Auto starting system bridge with Shizuku … ConnectionManager: System bridge connected`, after which
presses work again. Force it with `adb shell pkill -f keymapper_sysbridge` (auto-start rebuilds and
re-handshakes it) or by stopping/starting Expert Mode inside Key Mapper.

The trigger is a **toggle**: if the panel is already up, a trigger closes it instead of starting
another lookup (`triggerCapture` → `OverlayActivity.closeIfShowing()`). Verified on device:
trigger → `OverlayActivity` focused, trigger → `Lookup panel was open - closing it` → back to the
previous app, trigger → opens again. This applies to both the Plus Key and the Quick Settings tile.

Per-reboot: Shizuku itself must be started again over adb (its documented limitation); Key Mapper's
Expert Mode then auto-starts and re-handshakes on its own.

