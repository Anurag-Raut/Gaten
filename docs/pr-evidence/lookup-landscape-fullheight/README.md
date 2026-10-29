# Lookup sheet — landscape full height, in-flow Anki action, dismissal

Rendered evidence for the landscape changes in `src/rn/ui/lookup/LookupSheetApp.tsx`.

> **Provenance — these captures are not from this branch's head.** They were taken from the combined
> build prepared for the TickTick UI batch: base `7d12ac4` plus six modified files (this change and
> three unrelated fixes), APK `de73fbf8…`. They show *this change as it behaved merged with others on
> the old base*, not the head of this pull request. The head (`f34619b`, rebased onto `dev` `7e02486`)
> has not been built or captured; a fresh build of the head and its own captures are required before
> this PR is ready for review.

| Item | Value |
|---|---|
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, font scale 0.94, light theme |
| Build | combined TickTick UI build: `app-x86_64-debug.apk`, 298,000,245 B, SHA-256 `de73fbf814023f4559ac571de83e2b8900215295eece8c75466dd95222132b9e` (host file and on-device `base.apk` both re-hashed to this value) |
| Base | `7d12ac44799fd909f897de65b1f11090c8f7596d` + 6 modified files |
| Method | `adb` only — broadcast trigger, `input tap`, `screencap`, `screenrecord`; no agent-device helper attached, and `dumpsys accessibility` showed the service bound throughout |

## What each capture shows

| File | What it shows |
|---|---|
| `09-portrait-pinned-anki.png` | portrait, entry 猫 — the Anki deck row and action are pinned at the panel's bottom without scrolling (portrait behaviour, unchanged here) |
| `10-portrait-search-keyboard.png` | portrait, the sheet's search layer with the IME shown (`dumpsys input_method` → `mInputShown=true`) |
| `11-portrait-search-romaji-result.png` | portrait, `neko` → 9 dictionary results from the bundled DB |
| `12-landscape-fullheight.png` | landscape, the panel fills the window height (portrait's landing snap is replaced) |
| `13-landscape-scroll-inline-anki.mp4` | landscape, swiping the entry content to its end |
| `14-landscape-anki-inline.png` | landscape, after that scroll: the Anki deck row and action are the **last content**, not pinned |
| `16-dismiss-portrait.mp4` | portrait dismissal: a press **inside** the panel does nothing, a press **above** it dismisses |
| `17-dismiss-landscape.mp4` | landscape dismissal: a press in the side gutter clears the sheet |
| `dismiss-check-log.txt` | the commands, coordinates, measured panel bounds and `logcat` excerpts behind `16`/`17` |

## What these captures do not establish

- **They are not this branch's head.** Nothing here was captured from `f34619b`; the APK embeds the
  combined build's JS.
- **The rebase is textual only so far.** No Gradle build, JS bundle or rendered capture has been run
  on the head. (Static check only: `tsc --noEmit` over `src/rn` on the head. `dev` does not touch
  this file or the design guide, and `AddToAnkiBar` is still exported from `WordDetailScreen.tsx`.)
- **The top-inset statement is environment-specific.** This overlay window reports `insets.top = 0`,
  so the panel starts at the display's top edge and its toolbar sits in the status-bar band. No
  device that reports a non-zero top inset was exercised.
- **Not exercised:** dark theme; a text scale other than 0.94; a physical device; a landscape display
  cutout (the emulator has none); the landscape search layer with the keyboard up (the Android
  full-screen extract IME covers the panel); and the in-flow Anki bar's deck list scrolling while it
  is nested inside the entry scroller.
- `13`/`16`/`17` are `adb screenrecord` clips, which encode a frame only when the screen changes.
