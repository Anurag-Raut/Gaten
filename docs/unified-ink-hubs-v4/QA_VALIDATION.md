# QA and preview validation

- Built a self-contained browser HTML preview (no network requests, no remote fonts).
- Chromium static-content rendering used because direct local-file navigation is blocked in this execution environment.
- Rendered 16 combinations: 8 paired state examples in both light and dark mode.
- Browser JavaScript page errors: 0.
- Verified exactly two `WaniKani` navigation rows and five example Anki deck rows when ready.
- Verified neither `Start reviews` nor `Start lessons` appears in the new HTML.
- Buttons are prototype-only interactions; real provider writes and actual deep navigation are intentionally not simulated.
- App developer still must run Android emulator checks at 320/340/390/430dp and large type, check true AnkiDroid/WaniKani data and accessibility labels, and confirm stable artwork mapping in native implementation.
