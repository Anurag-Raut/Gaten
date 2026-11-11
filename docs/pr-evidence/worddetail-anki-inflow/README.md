# Word details — the Anki action ends the entry content

Rendered evidence for `src/rn/ui/search/WordDetailScreen.tsx`, captured from **this branch's own
build**. The Word details screen now reads like the lookup sheet: the Anki action is the entry
content's **last item**, reached by scrolling the entry it belongs to — never a bar pinned to the
screen's bottom. `AddToAnkiBar` itself is unchanged.

| Item | Value |
|---|---|
| Branch / head | `fix/worddetail-anki-inflow` @ `afcf06b` (the fix commit; this evidence is added on top), base `dev` (`origin/dev` `1163685`) |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 294,653,826 B, SHA-256 `3dbb93bae638fe6dd92ba58ac047213c8ecb2d356bde2ff71dc33802fb7c8449` — installed with `adb install -r` (app data preserved); the on-device `/data/app/…/base.apk` re-hashed to the **same** value in the same session |
| JS inside that APK | `assets/index.android.bundle` SHA-256 `5d5b11e9d44f4f716d30241398ee909875b4c94e0767f723df9086c79c46ca2a`, carrying the source change |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, font scale 0.94, light theme |
| Method | `adb` only — `input tap`/`swipe`, `screencap`, `screenrecord`. No agent-device/UiAutomation helper attached; `enabled_accessibility_services` stayed on `com.gaten/com.gaten.app.GatenAccessibilityService` throughout |

## The fixture

Gaten's Home **search** for `toru` (romaji) → the **取る** result. AnkiDroid `com.ichi2.anki` is
installed and `com.ichi2.anki.permission.READ_WRITE_DATABASE` is granted to `com.gaten`, so
`ankiStatus === "ready"` and the bar draws (deck `Gaten`). The entry is expanded to all **18
off-rendered meanings** (`View all meanings (18)`), so the content is unambiguously taller than the
viewport and the Anki action's position is readable against the screen edge.

## What each capture shows

| File | What it shows |
|---|---|
| `01-entry-top.png` | the **top** of the entry: the last visible line is a meaning row running off the bottom edge — there is **no Anki deck row or action pinned** at the screen's bottom. The action lives at the end of the entry scroller, off-screen below |
| `02-entry-end-anki-inline.png` | the same entry after scrolling to the end: the content finishes with **Part of speech → Anki deck row → `Add to Anki` action as the last content**, with the bar's own bottom gap clear below the button |
| `03-scroll-to-end.mp4` | the recording behind `01` → `02` (1080x2340, 9.1 s): the Anki row and action travel **up with the content** as it scrolls, never fixed to the screen bottom |

## A detail that separates in-flow from pinned

On this screen the pinned composition of the old code laid the bar out as a sibling below a
`flex: 1` scroller, so the bar — and its `insets.bottom + 24 dp` bottom padding — was always fully
visible. Here, at the top of the long entry (`01`), the bar is **off-screen**; in the earlier, still
shorter 取る state the button was clipped by the screen's bottom edge, and scrolling moved the whole
bar up by the scroll distance (the `03` recording). Both are only possible when the bar is inside the
scroller.

Notes on `01`/`02`: the two stills differ by the scroll; the `Add to Anki` button rather than
`Already in Anki` shows this fixture's word was not in the deck, while the earlier 猫 lookup showed
`Already in Anki` — the label is read from Anki as before and is unaffected by this change.

## Gates that passed

- `./gradlew :app:assembleDebug` — `BUILD SUCCESSFUL` (281 tasks), then `adb install -r` → `Success`.
- `node_modules/.bin/tsc --noEmit -p tsconfig.json` — clean.
- Bundle provenance: the installed `base.apk` SHA-256 equals the built APK's, and that APK's
  `assets/index.android.bundle` hash is the one above.
- No crash: `adb -s emulator-5554 logcat -d | grep -i "FATAL EXCEPTION"` is empty for the whole
  session; the only `AndroidRuntime` lines are `uiautomator.Launcher` starting and shutting down,
  and no `com.gaten` process-level exception accompanies the navigation (see `capture-log.txt`).

## What these captures do not establish

- Not exercised: dark theme; a text scale other than 0.94; narrow widths (`< 360 dp`) where
  `contentInsetFor` returns the smaller inset; a physical device; landscape (Home is portrait only).
- The WaniKani kanji section is absent here (WaniKani is not connected on this emulator), so the
  bar-follows-kanji ordering is only shown with the spellings/Part-of-speech tail above it.
- The "Anki unavailable" branch (`ankiStatus !== "ready"` → scroller pads `insets.bottom + 24 dp`
  itself) is not re-captured: AnkiDroid is ready on this emulator, so the bar always draws.
- `03` is an `adb screenrecord` clip, which encodes a frame only when the screen changes, so it holds
  relatively few frames; the stills carry the claims.
