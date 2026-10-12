# YomiLens — frozen interfaces for the build-out

Project: `/home/Qui/Projects/YomiLens` (Kotlin, Jetpack Compose, AGP 8.13.1, namespace
`com.akslabs.circletosearch`, minSdk 29, targetSdk 36, ABI splits arm64-v8a/armeabi-v7a/x86_64).

Product: on-screen Japanese text → **circle (lasso) select** → **OCR** → **word identification**
→ **dictionary entries**. Free/offline. Anki integration is **out of scope** (deferred by the user).

Existing state you must not break:
- `ui/components/JpLookupOverlay.kt` — prototype: rect-drag → Tesseract `jpn+eng` → MOCK popup.
  Signature `JpLookupOverlay(screenshot: Bitmap?, onDismiss: () -> Unit)` is called from
  `ui/CircleToSearchScreen.kt:1695`. **Keep that signature.**
- `ocr/TesseractEngine.kt` — bundled Tesseract (jpn+eng), still used as fallback. Do not delete.
- `CircleToSearchScreen.kt` — `isJpLookupMode` state + "Lookup JP" bottom-bar button.

## Global rules
- Kotlin/Compose only, Android only. No new dependencies except the one ML Kit artifact in S1.
- GPL-3.0 headers: new Kotlin files start with the same header block used by
  `ui/components/JpLookupOverlay.kt` (`Copyright (C) 2025 AKS-Labs`, `SPDX-License-Identifier: GPL-3.0-or-later`).
- Do **not** run `./gradlew` (the orchestrator compiles once after all slices land; parallel Gradle
  invocations on one project deadlock on the file lock). Python/sqlite3 checks are fine.
- No dead code, no TODOs, no stubs, no placeholder fallbacks. If something can't work, say so in your report.
- User-visible strings go in `app/src/main/res/values/strings.xml` (prefix `jp_`). No hardcoded UI text.
- Never bypass FLAG_SECURE/DRM.

## F1 — OCR  (owner: slice S1)
`app/src/main/java/com/akslabs/circletosearch/ocr/JapaneseOcr.kt`, package `...circletosearch.ocr`:
```kotlin
object JapaneseOcr {
    /** OCR the bitmap. Returns recognized text, "" when nothing found. Never throws. */
    suspend fun recognize(context: Context, bitmap: Bitmap): String
}
```
- Primary engine: **ML Kit on-device text recognition, Japanese** —
  `implementation("com.google.mlkit:text-recognition-japanese:16.0.1")`.
  Use `TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())`
  and bridge `Task` → `suspendCancellableCoroutine` (or `kotlinx-coroutines-play-services` — **do not add it**, hand-roll the bridge).
- Fallback: existing `TesseractEngine` (`jpn+eng`), used when ML Kit throws / returns blank.
- Do not modify `TesseractEngine.kt`.

## F2 — dictionary data  (owner: slice S2)
Build `app/src/main/assets/db/jmdict.sqlite` + the script that produces it (`tools/build_jmdict_db.py`).
Shipped uncompressed on purpose: AGP expands `.gz` assets during asset merge, so the packaged name
must be the plain one.
Sources, both from `jmdict-simplified` release `3.6.2+20261005200550`, both SHA-256 pinned:
- `jmdict-eng-3.6.2+20261005200550.json.tgz` (11.5 MB) — words
- `jmnedict-all-3.6.2+20261005200550.json.tgz` (13.5 MB) — proper nouns, which JMdict almost
  entirely lacks (池袋, 新宿, 秋葉原 are all absent there)
`PRAGMA user_version` is stamped with the dictionary date; the app compares it (and the rest of the
SQLite header) to decide whether its extracted copy is still current.
Frozen schema (S3 queries exactly this):
```sql
CREATE TABLE entries(
  id     INTEGER PRIMARY KEY,
  kanji  TEXT NOT NULL DEFAULT '',   -- ' ' joined kanji forms, '' when kana-only
  kana   TEXT NOT NULL DEFAULT '',   -- ' ' joined kana forms
  gloss  TEXT NOT NULL DEFAULT '',   -- ' | ' joined, EVERY sense, each sense's glosses ', ' joined
  pos    TEXT NOT NULL DEFAULT '',   -- ' | ' joined human-readable POS, deduped
  common INTEGER NOT NULL DEFAULT 0  -- 1 if any form carries a common tag (news1/ichi1/spec1/gai1)
);
CREATE TABLE terms(
  term     TEXT NOT NULL,   -- one row per (surface form)
  entry_id INTEGER NOT NULL,
  is_kanji INTEGER NOT NULL, -- 1 kanji form, 0 kana form
  common   INTEGER NOT NULL
);
CREATE INDEX idx_terms_term ON terms(term);
CREATE TABLE names(          -- JMnedict proper nouns: one row per (kanji form, name type)
  term  TEXT NOT NULL,       -- kanji form
  rank  INTEGER NOT NULL,    -- sort key: entries carrying several types first, then type, then id
  kind  TEXT NOT NULL,       -- 'place name' / 'surname' / 'given name' / 'name', ' / ' joined
  gloss TEXT NOT NULL        -- romanised name, <= 40 chars
);
CREATE INDEX idx_names_term ON names(term);
```
Also write `docs/jp-prototype/dictionary/DATA.md`: exact source URLs, release tag, SHA-256 of both
downloaded archives and of the produced asset, row counts, uncompressed size, license
(JMdict + JMnedict = CC BY-SA 4.0, EDRDG).

## F3 — lookup + word identification  (owner: slice S3)
`app/src/main/java/com/akslabs/circletosearch/dict/`, package `...circletosearch.dict`:
```kotlin
data class DictEntry(
    val id: Int,
    val primary: String,        // display form: first kanji form, else first kana form
    val kanji: List<String>,
    val kana: List<String>,
    val glosses: List<String>,
    val pos: List<String>,
    val common: Boolean
)

class JmdictRepository private constructor(private val db: SQLiteDatabase) {
    companion object {
        /** Extracts assets/db/jmdict.sqlite.gz to context.filesDir on first call (idempotent,
         *  safe under concurrent calls), opens read-only. Throws IOException on failure. */
        suspend fun open(context: Context): JmdictRepository
    }
    /** Exact-term lookup over `terms`. Order: common DESC, entry_id ASC. */
    fun lookup(term: String, limit: Int = 8): List<DictEntry>
    fun close()
}

object Deinflector {
    /** Candidate base forms for an inflected surface, most likely first. Pure, no Android deps. */
    fun candidates(surface: String): List<String>
}

data class ScannedWord(
    val surface: String,      // exact substring of the input text
    val start: Int, val end: Int,
    val baseForm: String,     // deinflected form that matched, == surface when direct
    val entries: List<DictEntry>
)

object WordScanner {
    /** Longest-match scan of `text` using `lookup`. Pure, no Android deps.
     *  Must: prefer the longest dictionary match at each position; skip non-matching
     *  characters; never emit overlapping words. Drop surfaces that are a single kana
     *  character (が, を, ん) — particles/auxiliaries carry no lookup value. Rank the
     *  survivors by complexity (kanji count desc, then text order) and cap at maxWords. */
    fun scan(text: String, lookup: (String) -> List<DictEntry>, maxWords: Int = 24): List<ScannedWord>
}
```
Requirements:
- `Deinflector` covers standard JMdict-form rules: ichidan/godan/suru/kuru, て/た/ます/ない/れる/せる/
  たい/そう/ず/ぬ/ば/可能/使役/受身/丁寧/過去 + い-adjective forms. Generate candidates; caller re-looks-up each.
- `WordScanner` is the anti-garbage filter: it only emits words that exist in the dictionary, and it
  handles multi-word text, kanji compounds, hiragana and katakana. Katakana words: also try the
  hiragana-folded form as a candidate (`lookup` is called with candidates, so fold inside `scan`
  via the injected lookup only if you fold before calling it).
- JVM unit tests under `app/src/test/java/com/akslabs/circletosearch/dict/` using a **fake lookup
  lambda** (no Android SQLite in JVM tests): cover deinflection candidates, longest-match
  preference, overlap avoidance, kana-only words, katakana folding, garbage-only input → empty.
  These are cheap pure tests; run them with plain `kotlinc`-free reasoning only — the orchestrator
  will run `./gradlew testDebugUnitTest`.

## F4 — lasso UI  (owner: slice S4)
Rewrite `ui/components/JpLookupOverlay.kt` keeping `JpLookupOverlay(screenshot: Bitmap?, onDismiss: () -> Unit)`.
- **Freehand lasso**: user draws any closed-ish loop with a finger; render the trail live
  (`Path` + `drawPath`, rounded stroke); on release take the **bounding box** of the trail as the
  OCR region (clamped to the bitmap, min ~24 px side; ignore degenerate gestures).
- Crop with `Bitmap.createBitmap(src, x, y, w, h)`.
- OCR via `JapaneseOcr.recognize(context, cropped)`.
- Words via `WordScanner.scan(text, repo::lookup)` with `repo = JmdictRepository.open(context)`
  (open once via `remember`, close in `DisposableEffect`).
- UI: show the cropped strip, then a **scrollable list of found words** as chips/cards; tapping a
  word expands its `DictEntry` fields (kanji / kana / POS / glosses). Show "no dictionary words found"
  when empty. Footer attribution: `Dictionary: JMdict (EDRDG) · CC BY-SA 4.0`.
- **Smooth open/close**: animated entry/exit (fade+scale for the popup, animated lasso trail fade),
  a visible close affordance, and a re-select affordance. Back press / scrim tap dismisses.
- Keep writing evidence to the app external files dir (`Android/data/com.akslabs.circletosearch/files/jp-prototype/`):
  `capture-full.png`, `capture-region.png`, `ocr-output.txt` (now: raw OCR text + the scanned words).
- Do **not** add an Anki button.
- New strings in `strings.xml` with `jp_` prefix; remove the now-unused mock-dictionary strings if any.

## Ownership map (no two slices touch the same file)
| Slice | Creates | Edits |
|---|---|---|
| S1 | `ocr/JapaneseOcr.kt` | `app/build.gradle.kts` |
| S2 | `tools/build_jmdict_db.py`, `app/src/main/assets/db/jmdict.sqlite.gz`, `docs/jp-prototype/dictionary/DATA.md` | — |
| S3 | `dict/*.kt`, `app/src/test/java/.../dict/*.kt` | — |
| S4 | — | `ui/components/JpLookupOverlay.kt`, `res/values/strings.xml` |
