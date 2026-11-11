# Lookup redesign — brief, specification, renders and device evidence

The screen-lookup workflow (floating button → freeze → circle or tap → result sheet) brought into the
Ink design language. The rules that came out of it are now §8 of
[the design language](../../docs/Gaten_Ink_Design_Language_v1.md); this directory holds the inputs,
the decision record and the evidence.

## What is here

| Path | What it is |
| --- | --- |
| `BRIEF.md` | The brief handed to the design lead: measured current implementation, tokens, invariants, the twelve decisions required. |
| `DESIGN.md` | **The specification.** Every value, state, string and motion timing the implementation follows. Written by the design lead (Codex `gpt-6.1-sol`) against the brief, the source and the stills in `before/`. |
| `before/` | The workflow as it was, captured on `emulator-5554` before any code changed: the sheet, the lasso, the rails, the bubble, plus the Ink screens that set the context. |
| `after/` | The same states after the change, same device, same source screen, same capture path. |
| `after-mockups/` | Eight design renders of the specified states (light/dark, both side docks, 160 % text, all seven accents across the set). |
| `mockups/` | The HTML sources for those renders, plus `tokens.css` (the Ink tokens as CSS) and `fixture-reader.html`, the Japanese page used as foreign content on the device. |
| `capture_stills.py` | The tool that produced `before/` and `after/` — it drives the app over adb, freezes a real screen through the overlay, draws a lasso and saves a PNG per state. |

## How the device stills were made

`python3 artifacts/lookup-redesign/capture_stills.py after` (and `before`, before the change).
Assumes one attached device, the debug APK installed and the app's accessibility service enabled
(the script enables it). It writes only into the directory it is given.

Renders: each `mockups/*.html` is authored in dp inside a 393 × 851 frame and captured with
`google-chrome-stable --headless=new --force-device-scale-factor=2.75 --window-size=393,851
--screenshot=…`, then cropped to 1080 × 2340 (2340 × 1080 for the landscape stills).

The `after/` set was also used for the states the script cannot reach deterministically: the rail
while the full-screen index is still running (a frame burst timed against the overlay window), the
dark sheet and lasso (app theme switched in Appearance), the landscape dock, the 160 % sheet over a
browser page, and the floating button at rest.

## Notes on the evidence

- The 160 % still in `after-mockups/05-large-text-dictionary.png` renders the sheet taller than the
  60 % window cap so that the row's required evidence fits one frame; the real sheet at 160 % scrolls
  its body instead. The device still `after/overlay-sheet-160.png` is the unretouched case.
- `after/anki-hub.png` and the `Already in Anki` footer in `after/overlay-sheet-160.png` are not part
  of the design evidence: a stray tap during verification hit the word-detail **Add to Anki** button
  at 10:15 and created one card (猫) plus the `Gaten` deck and note type in the emulator's AnkiDroid
  collection. The user chose to keep it.
- The emulator was restored to its as-found state afterwards: theme Light, accent Indigo, floating
  button off, font scale 1.0, portrait, and only the pre-existing foreign accessibility service
  enabled.
