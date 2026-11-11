# Design brief — Gaten lookup workflow, "Ink" redesign (v1)

You are the design lead on this task. The implementing engineer will follow your decisions literally.
**Do not write or edit implementation code.** Your deliverable is a design specification in markdown.

## 1. Product and task

Gaten is an offline Japanese reading companion for Android. Its main workflow is **screen lookup**:
the user triggers a lookup over any app (a manga page, an article, a video frame), the screen freezes,
they circle Japanese text with a finger, and a result panel shows dictionary entries, WaniKani kanji
data and an "Add to Anki" action.

The in-app UI (Home, Search, Review, Settings, Analytics) has already been redesigned into the house
**Ink** design language. The lookup workflow has **not** — it still carries the previous "Dokuen" look
(rounded tinted surfaces, filled pill buttons, chip capsules, generous radii). Your job is the design
that brings the whole lookup workflow into the Ink language, end to end.

Resolve every open question below into a concrete decision. Where the Ink document is silent or
ambiguous for a surface that only exists in this workflow (the lasso, the freeze scrim, floating
bubbles over foreign apps), you decide — and your decision becomes an extension of the design language.

## 2. Read these first

| What | Path |
|---|---|
| The house design language (authoritative, 533 lines) | `docs/Gaten_Ink_Design_Language_v1.md` |
| Kotlin theme tokens — colour, type, shape, motion, spacing | `app/src/main/java/com/gaten/app/ui/theme/{Color.kt,Theme.kt,Type.kt,Motion.kt,Spacing.kt,ThemePreferences.kt}` |
| The workflow's UI code | `app/src/main/java/com/gaten/app/ui/lookup/{LookupOverlay.kt,LassoLayer.kt,EdgeOutline.kt,ResultSheet.kt,SheetPlacement.kt}` |
| Trigger bubble (plain View, not Compose) | `app/src/main/java/com/gaten/app/trigger/FloatingTriggerButton.kt` |
| Overlay window / freeze / media pause | `app/src/main/java/com/gaten/app/ui/overlay/OverlayWindowController.kt` |
| Shared sheet components | `app/src/main/java/com/gaten/app/ui/components/{KanjiStrip.kt,Pressable.kt}`, `app/src/main/java/com/gaten/app/ui/wanikani/WaniKaniDetail.kt` |
| An Ink sibling screen to match (closest analogue of the result sheet content) | `src/rn/ui/search/WordDetailScreen.tsx` |
| Strings | `app/src/main/res/values/{strings_lookup.xml,strings_wanikani.xml,strings_anki.xml}` |
| In-app lookup settings page (RN, already Ink) | `src/rn/ui/settings/LookupSettingsScreen.tsx` |

## 3. Current implementation, measured (this is what you are redesigning)

Device: 1080×2340 px @ 440 dpi (2.75 px/dp), portrait; landscape supported (sheet docks to a side edge).

### 3.1 Lasso + selection + scrim (`LassoLayer.kt`)
- Freehand path: one quadratic per sampled point through segment midpoints; round cap, round join.
- Trail drawn as 3 stacked strokes: bloom `primary` α0.14 @14 dp → casing `Color.Black` α0.75 @5.5 dp → core gradient `primary→tertiary→primary` @3 dp. **This is the only "glow" in the app and it exists nowhere in the Ink language.**
- Scrim: `Color.Black` α0.35 even-odd over the whole canvas minus a rounded-rect hole (radius 12 dp) around the selection; once a result is up the hole's edge alpha fades to ×0.4 (`tween 220`).
- Selection edge: `primary` α0.18 @10 dp + `primary` @2 dp.
- Gesture: drag = lasso (needs ≥24 px in both axes or it is ignored); tap = select the text box under the finger (66 px tolerance) — a miss shows a notice pill instead of dismissing.
- Edge ring (`EdgeOutline.kt`): a 2 dp `primary` ring with a 10 dp α0.18 bloom hugging every display edge, animated in over 900 ms / out over 420 ms, plus a 2400 ms linear "drift" while the OCR is busy. Indigo glow around the whole screen.

### 3.2 Status pills (`LookupOverlay.kt` ~L216-240, L521-529, L588)
- Top-centre pill, `shapes.large` (20 dp), `surface`, text 14 sp: "Reading…" + spinner while scanning, "Circle Japanese text" as the hint / tap-miss notice, "No text found" / "Dictionary unavailable: …" for failures. Auto-hides after 2000 ms. Enter/exit fade 160/150 ms.
- On the dark scrim it reads as a plain white lozenge with no hairline, no accent, and a different type face feel from the rest of the app.

### 3.3 Result sheet (`ResultSheet.kt`, 1249 lines)
- Bottom dock (portrait): `fillMaxWidth`, max height 60 % of screen, `SheetShape` = 20 dp on the top corners only, `surface`, **no elevation, no scrim of its own**; slides in 260 ms from the dock edge, out 200 ms; drag-to-dismiss at 80 dp; content padded 16 dp; paints under the nav strip.
- Side dock (landscape): 45 % of screen width, 20 dp on the two inner corners, handle on the inner edge.
- Structure, top to bottom: drag handle (4×32 dp pill, `outline`) → "scanned line" card (`surfaceContainer`, 17 dp, one line of OCR text + three 48 dp icon buttons: copy, select-again, close) → chips row (horizontal scroller: selection chips, selected = `primary` fill + check, else `surfaceVariant`; 13 dp radius, 40 dp tall) → entry: headword (`displaySmall` 34/44 sp **in `primary` colour**), reading (`titleMedium`, also `primary`, joined by `・`), "common" badge (`primaryContainer`), WaniKani kanji strip (rounded-square tiles `surfaceVariant`), "Also written: …", POS tag pills (`surfaceVariant`, 12 sp), gloss rows (index in `primary`, gloss `bodyLarge` 18/26) → hairline (1 dp `outline`) → "Other matches (n)" expander row → runner-up cards (`surfaceVariant`, 17 dp) → pinned full-width "Add to Anki" button (13 dp, `primary`/`onPrimary`, 52 dp min).
- WaniKani kanji detail is a pushed page inside the same sheet (`ui/wanikani/WaniKaniDetail.kt`, 726 lines) with its own header, skeletons, mnemonics and radical artwork.
- The sheet is the biggest surface in the app and currently the most "card-like": it uses `surfaceContainer`, `surfaceVariant` fills and 17/20 dp radii for many nested elements, which the Ink language explicitly avoids ("content before containers", "no row cards or tinted section containers").

### 3.4 Floating trigger bubble (`FloatingTriggerButton.kt`)
- Own `TYPE_ACCESSIBILITY_OVERLAY` window, `WRAP_CONTENT`, plain `ImageView` + `GradientDrawable` oval — **it never enters Compose**, so it reads `ThemePreferences` + `accentRoles()` directly.
- 48 dp circle, `primary` at α0.90, no stroke, 24 dp glyph, no shadow; drag to reposition (8 dp slop = tap/drag split), position clamped to the display, default at 45 % height on the right edge, fade-out 150 ms when disabled. It floats over other apps' content with no container.

### 3.5 States of the workflow
`Selecting` (scrim + hint pill, lasso armed) → `Scanning` ("Reading…") → `Result` (sheet) → back to `Selecting` via "select again", or dismissed. Tap-to-select is an alternative to the lasso in every selecting state. Failure/empty paths: "No text found", "No dictionary words found", "Dictionary unavailable: …". In-app entry surfaces: the Screen lookup settings page, the Quick Settings tile, a hardware-key (Key Mapper) action, and the floating bubble switch. The accessibility disclosure dialog is a wall of ~20 lines of prose before a checkbox.

## 4. Reference stills (read them; they are 1080×2340)

All under `artifacts/lookup-redesign/before/`:

| File | What it shows |
|---|---|
| `before-home.png` | Ink Home — the language at rest |
| `before-word-detail.png` | Ink word detail — the closest sibling of the result sheet's content (flat, hairlines, accent only on the action) |
| `before-settings-hub.png`, `before-lookup-settings.png` | Ink settings rows and the in-app Screen lookup page |
| `before-overlay-opened.png` | Freeze scrim + indigo edge ring over a real screen, no sheet |
| `before-overlay-scan-pill.png` | "Reading…" status pill |
| `before-overlay-notice.png` | "Circle Japanese text" notice pill after a tap miss |
| `before-overlay-lasso.png` | Mid-drag: the lasso trail, its halo, and the cleared rounded selection hole |
| `before-overlay-sheet.png` | The result sheet in full (light theme) — **the main redesign target** |
| `before-bubble-enabled.png` | The floating bubble over a real screen |

## 5. Constraints you must design inside

1. **Tokens are fixed.** Colours come from `Color.kt` (neutral ladder + 7 accents: Indigo, Blue, Violet, Teal, Green, Rose, Black/White; light and dark) and reach the overlay through `MaterialTheme.colorScheme`. Named Ink roles: `background`, `surface`, `surfaceVariant`, `onSurface`, `onSurfaceVariant`, `outline/outlineVariant`, `primary/onPrimary`, `primaryContainer/onPrimaryContainer`, `error`, `success`, `warning`, `fine`. Type roles and motion tokens are in `Type.kt` / `Motion.kt`. If you need a token that does not exist, say exactly what it is and why it cannot be expressed with the existing ladder — do not invent a parallel palette.
2. **The overlay is drawn over foreign content and cannot rely on `MaterialTheme` inside the trigger bubble's window** (plain View).
3. **Flat by default.** No elevation, no drop shadows, no blur/glow, no gradients, no brand-coloured page washes (the design language forbids them; the lasso trail and edge ring are the current violations and are yours to resolve).
4. **Both themes and all seven accents must work**, including over arbitrary user content (bright manga page, dark video, busy screenshot). Say how the scrim and the lasso keep contrast in each case.
5. **Functional invariants that must survive any interaction change you propose** (the engineer will enforce them): freeze-then-pause order (frame commit → vsync → media pause → UI), opaque `TYPE_ACCESSIBILITY_OVERLAY` window, capture-px↔view-px remap on layout change, single-flight trigger/admission, drag-dismiss thresholds, WaniKani inner back stack unwinding before the overlay's own back handler, dismiss-on-focus-loss, lasso and tap both remain available, and the overlay must never write to the user's Anki collection by accident.
6. **Text scale**: everything must survive 130 % and 160 % system font scale; the sheet docks to bottom (portrait) and to a side edge (landscape).

## 6. What to decide (deliverable)

For **each** item, give the decision, the concrete values (dp, colour role, type role, motion token/duration/easing, alpha), and one line of rationale tied to an Ink principle. Flag anything that needs a new token or a design-language amendment.

1. **Entry moment**: freeze scrim treatment (colour/alpha, and whether the edge ring survives at all); what the user sees in the first 300 ms; how "lookup is armed" is communicated without the white pill.
2. **The drawn gesture**: the lasso's stroke — number of strokes, colour, width, alpha, cap/join, whether the gradient and the black casing stay, what it looks like at 60 px vs 600 px of travel, and the single-sample dot.
3. **Selection rendering**: the hole (radius, edge treatment), the "committed" state while scanning, and the "result up" state (today: edge alpha ×0.4). How the selection relates to the sheet.
4. **Status/notice surfaces**: one component for hint / reading / no-text / no-words / dictionary-error / tap-miss, with its Ink form, placement, type, lifecycle, and whether a spinner is acceptable in this language.
5. **Result sheet**: full specification for the bottom dock and both side docks — the surface itself (radius, colour role, hairline?), the handle, the OCR-line header and its three controls, selection chips, the entry block (headword / reading / badges / kanji strip / POS / glosses), the divider and "Other matches" expander, runner-up rows, the pinned primary action, and the WaniKani inner page's relationship to the sheet. Give the vertical rhythm and the exact nesting that replaces today's `surfaceContainer`/`surfaceVariant` cards.
6. **States**: loading, no text, no words, dictionary failure, tap miss, empty scan, long-gloss overflow, 160 % text, landscape side dock.
7. **Floating bubble**: size, fill vs hairline vs glyph-only, alpha, contrast strategy over arbitrary content, drag affordance, and what it does while a lookup is open.
8. **In-app lookup surfaces** (they are in scope): the Screen lookup settings page rows (lookup mode, floating button, QS tile, Key Mapper, pause media), the accessibility disclosure dialog (restructure so it is not a wall of prose), and the Quick Settings tile label/icon story.
9. **Copy**: a revised string list for the workflow — every user-visible line, in the app's voice, cut to the Ink "one clause" standard. Note which current lines carry real information and must survive (in a Help affordance if necessary).
10. **Interaction proposals** (allowed): at most five, each with the user problem it solves, the new behaviour, the risk, and which invariant from §5.5 it touches.
11. **Motion**: a table of every transition in the workflow with duration + easing token, consistent with `Motion.kt` (`SHORT 150`, `MEDIUM 300`, `LONG 450`, `PAGE 380`, `emphasized (0.2,0,0,1)`, `pressEasing`, `overshoot` spring).
12. **Mockup brief**: a short, unambiguous description of 6–8 stills that would prove the design (state, theme, what must be visible), so the engineer can render them for review.

## 7. Output

Write your specification to `artifacts/lookup-redesign/DESIGN.md`, self-contained, in the sections above.
No code. No file edits outside that path. If a decision needs a trade-off call, state the call and the
one-line reason rather than leaving options open.
