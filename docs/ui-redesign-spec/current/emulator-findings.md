# Gaten — current-UI evidence (emulator-5554, x86_64 debug APK)

Scope: **current** UI/interaction evidence only, for the redesign brief. No app source, build,
commit or collection change was made. Captures 01–06 (pre-existing baseline) were not touched.

Device: `emulator-5554` (`com.gaten.app`, versionName `0.1.0`), 1080×2340 @ 440 dpi, portrait,
font scale 1.0, dark device theme, accent Indigo / theme System at start and at end.

---

## 1. Summary

| Area | Status | What the evidence is |
|---|---|---|
| Review: picker → question → answer → leave-confirm → picker | ✅ complete | 07–12 + dumps; counts unchanged after leaving (nothing graded) |
| WaniKani kanji tile, missing-token state (in review + word detail) | ✅ complete | 10, also visible in 44 (overlay sheet) |
| Settings hub (both scroll positions) | ✅ complete | 13, 14 |
| Screen lookup page + accessibility disclosure dialog | ✅ complete | 15, 16, 17; disclosure checked-state verified |
| Text recognition catalog + download confirm (no download) | ✅ complete for reachable states | 18, 19; progress/remove states unreachable (§7) |
| Appearance: theme × palette × accent, light/violet samples | ✅ complete | 20, 21, 22, 23; restored to System·Indigo |
| Anki settings: basics, note-type menu, mapping, templates, dirty-discard | ✅ complete | 24, 25, 26, 27, 28, 29, 30, 31, 32 |
| WaniKani connection (no token entered) | ✅ complete | 33 |
| About (both scroll positions) | ✅ complete | 34, 35 |
| Search: results, empty state, word detail with long definitions | ✅ complete | 36, 37, 38, 39, 40 |
| Screen access ON (hub + lookup page), floating trigger window | ✅ complete | 41, 42, 43 |
| Overlay lookup: trigger, OCR, lasso select, result sheet | ✅ partially | 44 + logtimings; **floating-bubble tap did nothing**, tap-to-select reported misses (§6) |
| Overlay: copy / select-again / close | ⚠️ partial | close observed via log (`Overlay window removed`); copy/select-again not confirmed |
| Overlay in landscape | ❌ not tested | overlay unstable in this shared session (§6) |
| Never attempted (by constraint) | — | grading, exports, collection edits, credentials, model download/import/remove, account changes |

**Top issues found (details in §5–§6):** (1) the floating trigger's tap does nothing while its overlay
window exists; (2) taps on the frozen overlay that visually contain Japanese report *“no text box
within 66 px”* and then the lookup closes; (3) several full-width actions sit inside the bottom
gesture-bar band; (4) Settings detail pages (Anki/WaniKani/Recognition) are long ungrouped body text
with flat hierarchy; (5) the review question card leaves ~70 % of the screen empty.

**Environment warning (read before using the screenshots):** this emulator was in use by another
actor during the session. Two *foreign* accessibility overlays were active and their bubbles appear
in the captures: `com.akslabs.circletosearch` (service label "YomiLens", bubble clipped at the top
left edge) and `com.yomilens.app` (service label **also** "Gaten" — an app clone; bubble at the right
edge, lower down). Gaten's own bubble is the indigo circle with the concentric-ring icon (43). The
platform accessibility enable-list changed at 11:09:15 mid-session (another actor enabled YomiLens).
The two "Gaten" rows in the system Accessibility list are indistinguishable by label; always confirm
via `dumpsys accessibility`, never by label.

---

## 2. Screenshot index

All under `docs/ui-redesign-spec/current/`.

| # | File | State captured |
|---|---|---|
| 07 | 07-review-deck-picker.png | Review deck picker, `Default` 2 new · 2 learning · 5 to review |
| 08 | 08-review-question.png | Question card `勘弁`, “Show answer” |
| 09 | 09-review-answer.png | Answer: 勘弁 / かんべん / gloss, WaniKani strip, Again 1m · Hard 10m · Good 1d · Easy 3d |
| 10 | 10-wk-kanji-no-token.png | Kanji tile expanded in review, missing-token panel + “Open Gaten” |
| 11 | 11-review-leave-confirm.png | “Leave the review session?” Stay / Leave |
| 12 | 12-review-back-to-deck-picker.png | Back on the picker after Leave (counts unchanged) |
| 13 | 13-settings-hub.png | Hub top: access-off error card, Look up group, Study group |
| 14 | 14-settings-hub-app-group.png | Hub scrolled: App group (Appearance `System · Indigo`, About `Gaten 0.1.0`) |
| 15 | 15-settings-screen-lookup.png | Screen lookup, access off, floating/tile/hardware/media rows |
| 16 | 16-lookup-access-disclosure.png | “Grant Accessibility Permission” dialog, unchecked, Turn on disabled |
| 17 | 17-lookup-disclosure-agreed.png | Same dialog, checkbox checked, Turn on enabled |
| 18 | 18-settings-text-recognition.png | Text recognition, “No model installed”, one model, Download / Import |
| 19 | 19-recognition-download-confirm.png | “Download manga-ocr (int8)?” ~117 MB, Cancel / Download |
| 20 | 20-appearance-settings.png | Appearance, dark: Theme/Palette/Accent, Indigo selected |
| 21 | 21-appearance-theme-light.png | Appearance in Light theme |
| 22 | 22-appearance-accent-violet.png | Violet accent selected (light theme) |
| 23 | 23-home-light-theme-violet-accent.png | Home in light theme + violet accent |
| 24 | 24-settings-anki-cards.png | Customize cards: refresh card, Deck `Gaten`, Tags, Note type, templates toggle |
| 25 | 25-settings-anki-templates.png | Templates expanded (Front editor first visible) |
| 26 | 26-settings-anki-templates-bottom.png | Front / Back (HTML) / Styling (CSS) editors + Restore default layout |
| 27 | 27-settings-anki-notetype-menu.png | Note-type dropdown open (managed + Basic ×3 variants + LegacyGaten) |
| 28 | 28-settings-anki-field-mapping.png | `Basic` selected → mapping help + Front / Back selects (Leave empty) |
| 29 | 29-settings-anki-mapping-menu.png | Field-source menu: Expression / Reading / Meaning… / Leave empty |
| 30 | 30-settings-anki-mapping-expression.png | Front → Expression mapped, Back empty |
| 31 | 31-settings-anki-dirty-tags.png | Tags edited (`dummy`), IME up, field scrolled above the keyboard |
| 32 | 32-settings-anki-discard-dialog.png | “Discard changes?” Keep editing / Discard |
| 33 | 33-settings-wanikani-not-connected.png | WaniKani, not connected, masked token field, Save and connect (disabled) |
| 34 | 34-settings-about.png | About top: tagline, version, two “why” cards, attributions |
| 35 | 35-settings-about-bottom.png | About bottom: WaniKani attribution, licence, two links |
| 36 | 36-search-results-cat.png | Results for 猫 (10 results), exact card + “Words starting with 猫” |
| 37 | 37-search-empty.png | Search empty state: placeholder + 6 example chips |
| 38 | 38-search-results-school.png | Results for 学校 (9 results) |
| 39 | 39-search-results-omoshiroi.png | Results for 面白い (2 results, long gloss) |
| 40 | 40-search-word-detail.png | Word detail 面白い: headword card, Meaning card, Add to Anki, kanji strip, footer links |
| 41 | 41-settings-hub-access-on.png | Hub with access **on**: “Circle any Japanese text on screen to look it up.” |
| 42 | 42-lookup-settings-access-on.png | Screen lookup with access on; floating button switch available |
| 43 | 43-overlay-floating-button-enabled.png | Floating button on: Gaten bubble (indigo, ring icon) **plus** the two foreign bubbles |
| 44 | 44-overlay-lookup-result-sheet.png | Lookup result sheet over the frozen screen: selection chip, word card, kanji strip, Add to Anki |

---

## 3. Reproducible actions and observed outcomes

Helper used: `uiautomator dump` + `input tap/swipe/keyevent` + `exec-out screencap` against
`emulator-5554` only.

**Review (07→12)**
1. Settings/Review tab → picker lists `Default` with chips `2 new`, `2 learning`, `5 to review`; `Start reviewing` at the bottom (07).
2. Tap `Start reviewing` → question card, chip `Question`, `勘弁` centred, `Show answer` (08).
3. Tap `Show answer` → chip `Answer`, headword + かんべん + `pardon, forgiveness`, WaniKani kanji strip (勘/弁), `How did that go?` with `Again 1m`, `Hard 10m`, `Good 1d`, `Easy 3d` (09). **No rating tapped.**
4. Tap a kanji tile → panel expands inline (“Connect a WaniKani personal access token…” + `Open Gaten`); tapping the same tile again collapses it (verified by a dump before/after: the panel nodes disappear) (10).
5. System Back → `Leave the review session?` / body “Nothing is graded by leaving. The card you are on stays unanswered, and you go back to the deck list.” / `Stay`, `Leave` (right-aligned) (11).
6. `Stay` → dialog dismissed, still on the card. Back again → dialog; `Leave` → deck picker, **same counts** (12) → nothing was graded.
7. Race observed once: system Back issued immediately (~0.3 s) after dismissing the dialog with `Stay` did not re-open the dialog (dump showed the card); with a ~1 s gap it appeared. Not a still-image claim — measured by re-dumping.

**Settings hub (13, 14)**
- Rows and their summaries as rendered: Screen lookup / Text recognition `Built-in reader, no extra model` / Anki cards `Cards go to the “Gaten” deck` / WaniKani `Not connected` / Appearance `System · Indigo` / About `Offline, privacy, licences · Gaten 0.1.0`.
- The bottom nav (Home/Search/Review/Settings) is present on the hub and hidden on every settings detail page (Screen lookup, Text recognition, Appearance, Customize cards, WaniKani, About).
- The hub’s Appearance row does **not** surface the palette (only `System · Indigo`).

**Screen lookup + disclosure (15–17)**
- Access-off card: `Screen access is off` + `Gaten cannot read the screen until you turn it on.` + `Turn on`.
- With access on the same card becomes `Screen access is on` + `Circle any Japanese text on screen to look it up.`; the floating-button row changes from `Appears once screen access is on.` to `Tap the bubble on screen to look up.` (41, 42).
- `Turn on` → in-app disclosure “Grant Accessibility Permission” (16): ~20 lines of body copy, checkbox `I understand how this permission will be used and agree to proceed`, `Cancel` / `Turn on` (disabled until checked → enabled after checking, 17).
- Real path to actually enabling, measured: app disclosure → system Accessibility list → service page → `Use Gaten` toggle → system dialog “Allow Gaten to have full control of your device?” (Allow/Deny/Uninstall) → Allow. Three screens and two confirmations away from one in-app button.
- The floating-button switch exposes **no** label/role in the a11y tree: a `checkable` `android.view.View` at `[827,786][970,918]` with empty `content-desc`.
- `Hardware key (Key Mapper)` row: `Set up` only until expanded — its inline action text and `Copy the trigger action` were **not** expanded (untested).
- Media row: `Pause video while looking up` / `Not allowed` / long explanation / `Turn on` for notification access, whose button sits at `[319,2185][463,2240]` — inside the bottom gesture band.

**Text recognition (18, 19)**
- Status card `No model installed` + `The built-in reader (ML Kit) is reading your screen…`; long help paragraph; `Models` section with the single shipped entry `manga-ocr (int8)` / `~117 MB` / description / `Download`; `Import model files` outlined button; network note.
- `Download` → `Download manga-ocr (int8)?` / `About 117 MB will be downloaded from huggingface.co. Use Wi-Fi if you can.` / `Cancel`, `Download`. **Cancelled — no download started.**

**Appearance (20–23)**
- Segmented `System` (checked, accent-filled) / `Light` / `Dark`; `Palette` tiles `Gaten` (selected, ring) / `Mono` / `Cool` / `Warm` each with an `Aa` sample; `Accent` circles Indigo, Blue, Violet, Teal, Green, Rose, Black.
- Accent circles wrap **6 + 1** (Black alone on a second row, left-aligned) and carry no visible text labels — names exist only in the a11y tree.
- Light theme sample (21) and Violet accent sample (22) captured; palette differences are subtle in light (Cool slightly cool-white, Warm cream) and near-identical against dark cards in dark theme.
- Restored: `System` + `Indigo` (hub summary verified again at the end).

**Anki “Customize cards” (24–32)**
- `Refresh from AnkiDroid` sits alone in a surface card; `Deck` field prefilled `Gaten` (label sits in the outline notch — checked at 2× zoom, the notch is correct; label colour is dim), help line under it; `Choose existing deck` select shows `Gaten`; `Tags` empty; `Note type` = `Gaten cards (customizable)`, help paragraph, `Edit front, back, and styling`.
- Note-type menu options observed: `Gaten cards (customizable)`, `Basic`, `Basic (and reversed card)`, `Basic (optional reversed card)`, `Basic (type in the answer)`, `LegacyGaten`.
- With `Basic` selected the managed-template section is replaced by the existing-note-type help + mapping help and per-field selects (`Front`, `Back`) whose options are `Expression`, `Reading`, `Meaning and part of speech`, `Leave empty`. Selecting Basic leaves both fields `Leave empty` (no auto-match).
- Templates expanded: Front `{{Expression}}`, Back `{{FrontSide}}<hr id=answer>{{Reading}}<br>{{Meaning}}`, Styling with `.card {…}` + `small {…}`, plus `Restore default layout`. Editors are 3–8 lines, monospace.
- Dirty flow: typed `dummy` into Tags (31; the page scrolls so the field stays above the IME — imePadding works), toolbar Back → `Discard changes?` / “Your deck, tags, field mapping and template edits are not saved yet.” / `Keep editing`, `Discard` (32) → `Discard` returns to the hub with **nothing saved** (hub row still `Cards go to the “Gaten” deck`).
- `Save` in the app bar is enabled at all times — including while the mapping is invalid/empty (30) and while the draft is clean; there is no dirty/valid affordance.

**WaniKani (33)**
- Explanation card; `Not connected. Kanji pages say what to do until you connect.`; token field (`EditText`, `password=true`, empty, floating label `Personal access token`); help paragraph; `Open WaniKani token settings`; full-width `Save and connect` rendered disabled with an empty token; coverage + attribution paragraphs run past the bottom edge (page scrolls). No token entered, no network call made.

**About (34, 35)**
- `Gaten` / `Circle Japanese on screen. Read it offline.` / `Gaten 0.1.0`; cards `Offline by default` and `Two network uses, both optional`; `Attributions` rows `EDRDG dictionary`, `manga-ocr`, `ML Kit`, `WaniKani`; `Licence` paragraph, `Read the licence` → `gnu.org/licenses/gpl-3.0`, `CircleToSearch on GitHub` → `github.com/AKS-Labs/CircleToSearch`.

**Search (36–40)**
- Arriving on the Search tab restores the previous query: the first arrival in this session showed 10 results for 猫 (36). `Clear the query` (`content-desc`) → empty state with `Look up a word` and the chips `猫`, `たべる`, `食べた`, `ありがとう`, `学校`, `面白い` (37). Tapping a chip runs the query (that is how every Japanese query in this report was entered — no Japanese keyboard was needed).
- Results screen: large search pill (`[44,334][1036,494]`, 160 px tall), `N results`, the exact entry card (headword, `common` chip, reading, gloss, POS), then `Words starting with 学校` sections (38, 39).
- Word detail (40): headword card (`面白い`, `common`, `おもしろい`), `Meaning` card with four wrapped gloss paragraphs + `adjective (keiyoushi)` chip, indigo `Add to Anki` card (`Deck: Gaten`, full-width `+ Add to Anki`), `WaniKani kanji` strip (面 / 白), footer text links `Anki settings` / `WaniKani settings` at `[77,2212][744,2267]` — ~75 px above the gesture bar.

---

## 4. Motion / timing — observed vs source-inferred

Observed (from `logcat`, i.e. measured, not inferred from stills):

| Event | Value |
|---|---|
| Screen scan ready after trigger | 497–780 ms, “16–19 lines” |
| ML Kit line index | 610–779 ms |
| manga-ocr prefetch finished | immediately after the scan (no model installed → no download) |
| Lasso release → result | “Circle complete in 28 ms” (crop read 0 ms, primary dictionary 0 ms, supplement 8 ms) |
| Media pause | “No notification access - pausing with a media key event instead”; “Media pause ran 100 ms after it was requested” |
| Dismiss (Back) | “Media resume: sending a media play key event” → “Overlay window removed (resumed=true)” |

Source-inferred only (not measured — no recording was made): all transition/animation durations
(`ui/theme/Motion.kt`, `ui/nav/GatenMotion.kt`), and the reveal/enter animations of the result sheet.
Still images show discrete states only; **no timing claim is made from any screenshot**.

---

## 5. Layout, density, hierarchy, inset observations

Measured from 1080×2340 dumps (bounds are device pixels).

1. **Bottom-band collisions.** Full-width actions sit inside the gesture band: Screen lookup media `Turn on` `[319,2185][463,2240]`; WaniKani `Save and connect` `[66,1708][1014,1818]`; word-detail footer links `y 2212–2267`; Anki `Restore default layout` `y 2049–2104` when scrolled. System gesture bar ~2274–2300.
2. **Review question leaves the screen empty.** The question card is `[44,453][1036,645]` — 192 px of a 2340 px screen; ~1600 px below it is blank background. The rating row on the answer screen sits at `y 2099–2186`, hard against the bottom.
3. **Flat hierarchy on Settings detail pages.** On Anki/WaniKani/Recognition, section labels, field labels and help text are the same weight/size family; long paragraphs (`bodySmall`) are not inside the group cards used on the hub, so a page reads as one column of body copy. Anki’s deck/tags/note-type fields are bare outlined fields on the page background, unlike every hub group.
4. **Dense disclosure copy.** The accessibility disclosure (16) fills nearly the whole screen with ~20 lines of prose in one unbroken block before a checkbox — the highest-stakes permission in the app, presented as a wall of text.
5. **Search affordance sizes differ by context.** The Search tab’s field is a 160 px-tall pill; the field inside the lookup sheet is a compact 60 px row with three icon actions (copy / select-again / close).
6. **Accent picker geometry.** 7 accents in a 6 + 1 wrap, unlabelled; selection is a ring only. Palette tiles are labelled; accent circles are not.
7. **Kanji strip duplication.** The same `WaniKani kanji` strip + tile-with-panel pattern appears in review (10), word detail (40) and the lookup sheet (44) — three different framings of the same control.
8. **Accessibility tree gaps (observed via uiautomator):** the floating-button toggle is a `checkable` View with an empty `content-desc`; the lookup sheet’s controls are not exposed at all in `uiautomator dump` (the overlay window is `NOT_FOCUSABLE` and not dumped), so nothing in the sheet can be located by text — every sheet interaction in this report had to be done by pixel coordinates.

---

## 6. Overlay (screen lookup) — what worked, what did not

Setup: Gaten’s accessibility service was **disabled** on arrival (`enabled_accessibility_services`
was `null`). It was enabled through the Android UI only (list → row → toggle → Allow); verified with
`settings get secure enabled_accessibility_services` and `dumpsys accessibility`.

Worked:
- With access on, the hub and lookup page switch to the “on” copy (41, 42).
- Toggling the floating button created an accessibility-overlay window owned by `com.gaten.app`
  (`ty=2032`, requested position `(926,962)`, `NOT_FOCUSABLE NOT_TOUCH_MODAL`), and the bubble is
  visible (43). The bubble renders ~145 px **below** the window’s reported top-left (measured by
  pixel scan: visible bubble ≈ x 940–1040, y 1105–1205), so the frame and the drawn circle do not
  coincide.
- The documented Key Mapper action works and is the reliable trigger here:
  `adb shell am broadcast -a com.gaten.app.action.TRIGGER_LOOKUP -p com.gaten.app` → overlay window
  (full-screen, `ty=2032`, package `com.gaten.app`) + OCR scan + media pause (timings in §4).
- A lasso drag over Japanese text completed the lookup: “Circle path: crop read (index ready)” →
  “Circle complete in 28 ms”, and the **result sheet rendered** (44): frozen screen with the selection
  chip, the word card, the kanji strip and `Add to Anki`.
- Dismissal via system Back logged `Media resume…` + `Overlay window removed (resumed=true)`, after
  which the bubble returned and the app was intact.

Did **not** work / not confirmed (this is the blocker to be aware of):
- **Floating bubble tap did nothing.** Three taps at the bubble’s measured centre — including one at
  the window’s reported position and one 1.2 s long-press (to check the press/drag path) — produced
  no log line, no overlay, and no movement. The first attempt at the window’s reported coordinates
  fell through to the list *under* the overlay and opened a word detail, which shows the touch was
  not captured by the bubble.
- **Tap-to-select reported misses over visible Japanese:** tapping the frozen screen logged
  `Tap missed: no text box within 66 px` both at an empty area and at a point that visually sits on
  Japanese text; in two runs that tap was immediately followed by
  `Media resume…` + `Overlay window removed (resumed=true)`, i.e. the lookup closed. Either the
  tap→box mapping differs from frozen-screenshot coordinates or the proximity rule is stricter than
  it looks — worth confirming visually before designing around tap-to-select.
- **Select again / copy** were not confirmed: after the drag-selection run the sheet was up (44), but
  my next tap dismissed the overlay instead of hitting the sheet’s copy control (the sheet’s controls
  are invisible to `uiautomator`, so they can only be hit by pixel guesswork).
- **Landscape/panel-in-landscape: not tested.** The overlay’s open/dismiss behaviour was already
  flaky (it closed on its own within ~2 s in two runs while another agent was active on this
  emulator), so I stopped that path rather than add more rotation churn to a shared device.
- **Word → kanji missing-token inside the overlay** was not captured; the equivalent state is
  captured **in-app** (10, and the word-detail kanji strip in 40) and the kanji tiles are visible in
  the sheet (44).

---

## 7. States that are implemented but were **not** reachable here (from source, not fabricated)

| State | Why not reachable |
|---|---|
| Recognition download progress (`Downloading %s…`, `%d%% · %s`, `Cancel`), `Installed` chip, `Remove`, import success/failure toasts | Only exist while a download/import runs; downloads/imports were out of scope |
| Anki `AnkiDroid is not installed` / `Allow AnkiDroid access` / `Could not read AnkiDroid` | AnkiDroid is installed, permission granted, collection readable (`AnkiStatus.Ready`) |
| Review blocked states: not installed, needs permission, not set up, `Nothing is due in %s`, provider error, media-note, typed-note, `All caught up` | Collection has due cards in `Default`; reaching the others needs grading or collection changes |
| Search `No matches for %s`, `Search in Japanese`, `The dictionary could not be read` | Not exercised (chips only); an ASCII query would reach the “not Japanese” hint |
| Word detail `This entry is no longer in the results` | Requires a re-run that drops the entry |
| WaniKani connected / rejected token / unreachable / disconnect / restricted levels / no entry | Needs a token + network to WaniKani |
| Anki export, dedup, note-type creation, `LegacyGaten` behaviour | Would write to the collection |
| Quick Settings tile add; hardware-key trigger via Key Mapper; Key Mapper not installed | Out of scope / app not present |
| Landscape review, landscape settings, overlay landscape | Not tested (orientation was never changed; auto-rotate left as found) |

---

## 8. Constraints honoured, and emulator state restored

- Only `adb -s emulator-5554` was used; the physical device was never touched.
- No app source edits, no Gradle build, no commit/push, no delegation, no new app tooling.
- Untouched by design: no grading (no rating tapped), no Anki export, no AnkiDroid collection or
  note-type change, no credentials entered, no WaniKani call, no model download/import/removal, no
  account changes, no real token text captured anywhere.
- Restored: Gaten accessibility service **disabled** (as found; verified via `dumpsys accessibility`),
  floating button **off** (verified `checked=false` and no `com.gaten.app` overlay window), theme
  `System` + accent `Indigo` (verified on the hub), font scale `1.0`, auto-rotate `1`, rotation `0`
  (never modified). The app is left on the Home tab; its in-app navigation/query state necessarily
  differs from the start (it began on the review deck picker).
- Disclosed: while toggling the two indistinguishable “Gaten” rows in the system Accessibility list I
  briefly stopped `com.yomilens.app` (the clone, whose service label is also “Gaten”); it was
  re-enabled immediately and the enabled-list was verified to again contain it. Both
  `com.akslabs.circletosearch` and `com.yomilens.app` are left enabled, as they were during the
  session — they were enabled by another actor at 11:09:15, not by me.
