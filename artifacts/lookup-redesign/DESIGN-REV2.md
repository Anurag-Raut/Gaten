# Ink — lookup revision 2

This is the implementation decision record for the revision to the shipped lookup redesign. It supersedes the specifically named portions of [DESIGN.md](DESIGN.md); everything not explicitly replaced remains in force. Design output only: this document and the two original SVG sources. No Kotlin, TypeScript, resources or shared-token changes are part of this deliverable.

**The revision:** a 13 dp neutral rail without an accent rule; two centred, illustrated empty states; and a Search action in the sheet that opens **full-screen search within the same overlay window**. There is no inline query field in the result sheet and no second activity or dialog. The capture/admission/media ordering and explicit-only Anki write rule do not change. **New shared tokens requested: none.**

## 1. Rail, revised form

### One quiet surface

Nothing decorative replaces the accent leading rule. The opaque paper, rounded silhouette, consistent inset and small functional glyph identify the tool. **Remove the leading rule completely**, including its 2 dp reserved width; do not turn it into an underline, accent perimeter, accent glyph plate or accent-tinted fill.

| Property | Floating | Inline |
| --- | --- | --- |
| Shape | 13 dp on all four corners | 13 dp on all four corners |
| Fill | `surface`, alpha 1.00 | `surface`, alpha 1.00, identical to its parent sheet |
| Perimeter | 1 dp `outlineVariant`, inside the rounded contour | 1 dp `outlineVariant`, inside the rounded contour |
| Elevation/effects | 0 dp; no shadow, blur, gradient or glow | The same; this is a notice boundary, not a nested entry card |
| Padding | 12 dp on all four sides, measured inside the perimeter | The same; action is inside this padding |
| Height | Minimum 48 dp, grows with text | Minimum 48 dp without an action, grows with text and action |
| Width | Content-sized up to 360 dp, at least 16 dp from safe left/right edges | Full available content-column width, capped by the existing 560 dp column including its insets; never a 360 dp cap inside a wider column |
| Position | Top centre, 16 dp below the union of status-bars-ignoring-visibility and display-cutout top bounds; respect horizontal safe insets | In the body scroller with 16 dp above/below; it never floats over body text |

The inline perimeter is a deliberate change from the borderless shipped inline recipe: with no leading rule, a thin neutral enclosure makes an error or reference notice legible as a notice. It is not permission to outline dictionary entries or other reading blocks. Both inline and floating placements use the same 13 dp geometry in a bottom dock, left dock and right dock; do not square a side-docked rail or mirror its content order. Text follows locale direction, not dock direction.

Text stays `bodyMedium` 14/20 sp, `onSurface`, alpha 1.00, with no fixed line count. Causes stay `bodySmall` 13/19 sp, `onSurfaceVariant`, 4 dp below. Failure title and glyph use `error`, not `primary`; cause remains secondary neutral. A real busy task uses the existing native 16 dp circular indicator, 2 dp stroke, `primary`, 8 dp before the text. A settled instruction/notice uses an 18 dp outline gesture/info/error glyph in `onSurfaceVariant` or `error`, with the same 8 dp gap. The loading spinner is the rail's only accent and only animation. Do not add an accent to the static instruction glyph. Glyphs are decorative to accessibility because the text states the status.

An inline action starts 8 dp below the message, aligns to its text start, uses `labelLarge`/`primary`, and has a 48 dp minimum target that grows and wraps. Dictionary failure keeps **Select again** here; Search remains in the sheet header. The failure is never illustrated as an empty state.

### An edge over somebody else's pixels

Keep the existing 40% fixed-black veil behind the rail, the opaque neutral fill and the hard 1 dp perimeter. Rounded paper silhouettes and the glyph/text remain readable without borrowing screenshot pixels as a backing. The hairline is a low-emphasis boundary, **not** a universal contrast guarantee; where its value coincides with a foreign pixel, the opaque surface and ink still carry the message. Do not sample screenshot luminance, add a second rim, increase opacity past 1.00, or restore an accent edge to compensate. This is a deliberately quieter status instrument, not a prominent action.

### Scope of replacement and lifetimes

This replaces DESIGN.md §4's floating/inline form rows, the leading-rule references in its copy row, its square-geometry identity call, and the claim that the rail carries selected-crop empty states. It also replaces the accent-rule sentence in §1's arming step 3. Dimensions, type, glyph sizes, honest busy indication, real safe insets, polite once-per-status announcements, opacity motion and accessibility-timeout policy otherwise stand.

- **Reading screen…** and **Reading selection…** persist only for their real work. Usable content is never replaced by a background spinner.
- Ready instruction, tap miss and unavailable-index guidance retain the §4 sequences, `NOTICE_MS = 2000 ms` minimum, recommended accessibility extension, focus retention and immediate hide on drawing. `NOTICE_MS` is a lifetime, not a motion duration.
- **Selected-crop No text found** and **No dictionary matches** now use §2, persist until a reader action, and never also appear in a floating rail.
- A **confirmed zero-line full-screen index**, while idle in selecting with no gesture/committed crop/result, opens the same No text found shell. It no longer spends two seconds in a no-text rail followed by a timed hint. No fabricated selection hole is drawn. In portrait use the bottom dock; with no source anchor in landscape use the right 45% dock. Select again restores selection and the circle-only instruction; it does not immediately reopen the same empty-index shell. A fresh selection can still recover text.
- A zero-line background index must not interrupt a gesture, crop reading, existing result or search. An unavailable/failed index is not a confirmed empty index and retains **Circle Japanese text** guidance. Tap misses still do not open a sheet.
- Dictionary failure retains the persistent inline error rail and actual sanitized readable cause. There is no timeout, invented retry or empty-state artwork for that failure.

## 2. Empty states, centred

### One component, two facts

Use a single `LookupEmptyState` composition with an artwork resource, message and two recovery actions. It is not a rail, card or illustrated alert. The sheet stays opaque `surface`, with its existing 20 dp dock corners, exposed-edge hairline, grip and OCR/control header. Empty shells use the existing full **60% window-height cap** in portrait, rather than collapsing to a small notice-height sheet; side shells remain full-height at **45% width**. The bounded body, not the entire screen or sheet including controls, is the centring rectangle.

| State | Artwork | Message | Context |
| --- | --- | --- | --- |
| No readable text in the accepted crop, or confirmed idle zero-line index | `empty-no-text.svg`: empty reading brackets | **No text found** | No invented OCR line; Copy is absent/disabled according to header space policy. A full-screen zero has no committed mark. |
| Recognized source but no dictionary entry | `empty-no-matches.svg`: open dictionary and empty lens | **No dictionary matches** | Preserve the actual OCR line, Copy and committed selection mark. Do not imply OCR was empty. |

Both have **Search dictionary** as the obvious first recovery and **Select again** as the secondary recovery. Search is a full-content-width filled button, maximum 280 dp, minimum 52 dp, 13 dp radius, `primary/onPrimary`, 15/20 sp `labelLarge`, 16 dp horizontal and 12 dp vertical padding. It opens §4's full-screen mode. Select again is a centred text action in `primary`, minimum 48 dp high, same maximum width. Stack these actions in every dock; do not squeeze two labels beside each other in a narrow side panel. The sheet-header search icon is also available; these are two entry affordances, not two search fields. No Anki footer or word tabs appear in either empty state.

### Measured rhythm and text growth

- Horizontally centre every element in the existing content measure: bottom inset 22 dp, or 16 dp below 360 dp panel width; side inset 16 dp after the 48 dp grip gutter. No additional illustrated card inset.
- Artwork box: **140 × 140 dp** normally. Use **96 × 96 dp** when the available body content width is under 240 dp or its viewport height is under 344 dp. At the physically narrower limit, cap it further at the available content width; never overflow. It does not scale with font size. Use the same selection rule for either mark.
- Artwork → message: **16 dp**. Message is the caption, `bodyMedium` **14/20 sp**, `onSurfaceVariant`, alpha 1.00, centred, complete wrapping text, not a large heading or error colour. Its text measure caps at **280 dp** or available width, whichever is smaller.
- Message → primary action: **16 dp**. Primary action → secondary action: **4 dp**. The group's top/bottom breathing room is at least **24 dp** each. No extra supporting paragraph or third action.
- Let `V` be the body viewport left after chrome and any reflowed OCR source block; let `G` be the naturally measured artwork/message/action group. Allocate a minimum-height content region of `V`. When `G + 48 dp ≤ V`, put `(V − G) / 2` above and below the group. When it does not fit, use **24 dp** top/bottom padding and let the **one body scroller** scroll the entire group. Never use an unbounded vertical centre that makes the top of an overflowing group unreachable.
- If OCR must reflow, its complete wrapping line sits at the beginning of that same scroller, with 12 dp after it; subtract its measured block from the centring region when it fits. If source plus group exceeds the viewport, the whole body becomes the top-aligned, padded scroll flow. Do not centre the source text with the artwork.
- A 45% side dock uses precisely the same group, spacing and type roles, usually the 96 dp artwork and wrapping labels. Do not widen it to accommodate the picture.
- At **130% and 160% text**, use uncapped system sp, measure all labels at their real heights, wrap the caption and both button labels, and preserve target floors. The artwork remains 140/96 dp by viewport rule; type growth is absorbed by wrapping and then scrolling, not smaller text or clipped actions. The header stays reachable independently of body scroll.

The illustrated group is announced as the message, then its two actions; artwork is accessibility-decorative. Announce the changed state once, politely. It has no countdown and never steals focus repeatedly when OCR refinement completes.

## 3. Artwork — complete source and Android mapping

These are original, sparse line drawings: a reading frame with a missing middle, and a dictionary whose lens has found no entry. The shared restrained vocabulary is rounded open strokes and blank paper, not a face, mascot, washed panel, gradient or shadow. The blank lens is intentional; do not add a failure cross, accent sparkle or fake Japanese character. The existing search illustration's book/lens subject is a precedent, not an asset copied with its transforms, opacity washes or decorative scatter. Legacy artwork blobs in `HomeSvgAssets.ts` do not override the sparse-artwork rule.

Both sources are standalone XML SVG with integer `viewBox="0 0 140 140"`, integer path coordinates, no transforms, CSS, styles, clipping, external fonts, embedded images or effects. Every path has a unique stable `id`. Paths have `fill="none"` inherited from the root. The **only stroke literal, `#6C6C75`, maps to `onSurfaceVariant`**: #6C6C75 light / #A7A7B0 dark, alpha 1.00. Root transparency reveals the **sheet**, never the foreign capture through the sheet. No part takes `primary`, even with the Black/White accent. The standalone files use the light literal; the Android vector conversion replaces it with the role at render time, without redrawing geometry or baking a dark asset.

Main strokes are **2 viewBox units**; internal short lines are **1.5 units**; round caps and joins. At **140 dp** these are 2/1.5 dp, within the app's 1–2 dp instrument weights. At **96 dp**, uniform scaling gives about **1.37/1.03 dp**, so no line becomes a sub-1 dp decorative hairline. Do not use non-scaling strokes: the same source must scale uniformly. Native vector rasterization may antialias those widths; do not snap different paths independently and disturb the geometry. Main contours have generous separation, no detail dependent on low-alpha marks, and substantial internal whitespace at both sizes in light and dark.

### `mockups/artwork/empty-no-text.svg`

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="140" height="140" viewBox="0 0 140 140" fill="none">
  <title>No text found</title>
  <path id="capture-top-left" d="M32 54V36Q32 32 36 32H54" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="capture-top-right" d="M86 32H104Q108 32 108 36V54" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="capture-bottom-left" d="M32 86V104Q32 108 36 108H54" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="capture-bottom-right" d="M86 108H104Q108 108 108 104V86" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="empty-line-left" d="M52 70H62" stroke="#6C6C75" stroke-width="1.5" stroke-linecap="round" />
  <path id="empty-line-right" d="M78 70H88" stroke="#6C6C75" stroke-width="1.5" stroke-linecap="round" />
</svg>
```

### `mockups/artwork/empty-no-matches.svg`

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="140" height="140" viewBox="0 0 140 140" fill="none">
  <title>No dictionary matches</title>
  <path id="book-left" d="M70 42Q54 34 30 38V96Q54 92 70 100" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="book-right" d="M70 42Q86 34 110 38V62M70 100V42" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="book-lines" d="M42 54Q50 53 58 56M42 66Q50 65 58 68M42 78Q50 77 58 80" stroke="#6C6C75" stroke-width="1.5" stroke-linecap="round" />
  <path id="lens-ring" d="M110 84A18 18 0 1 1 74 84A18 18 0 1 1 110 84Z" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
  <path id="lens-handle" d="M105 97L118 110" stroke="#6C6C75" stroke-width="2" stroke-linecap="round" />
</svg>
```

**Artwork check performed:** both saved standalone files were parsed as XML and opened through `rsvg-convert`, rendered at 96 px and 140 px to represent those dp sizes at mdpi, with no external dependencies. Their six/five addressable paths rendered successfully. I viewed the combined light/dark contact sheet: light on #FFFFFF, dark with the single stroke literal mapped to #A7A7B0 on #17181C. Both complete marks are visible without clipping or lost interior strokes at either size. This is source/render inspection, not an Android implementation or runtime accessibility test. No preview raster is added to the repository.

## 4. In-modal search — a full-screen mode of the overlay

### 4.1 Entry point and the constrained sheet header

Search is always offered on every result/empty/failure/reference sheet, not only after an OCR error. It is a **20 dp neutral outline search glyph**, accessible name **Search dictionary**, in a **48 × 48 dp** target. It never writes to Anki. The header remains a single **48 dp-high control band**, with no tiny fourth icon, dropdown menu or second pinned toolbar.

Normal physical order, left to right after any source text: **Copy text → Search dictionary → Select again → Close lookup**. Search belongs to the top-right cluster but Close stays at its far edge for stable escape. All glyphs use `onSurfaceVariant`, with the existing 48 dp targets and no backing. Source text gets only the space left after the controls; its full accessible value and Copy payload remain the real OCR string.

Use measured content width **after** safe insets, side grip and column insets:

1. Four controls require **192 dp**. Keep the source on that row only if `availableWidth / fontScale ≥ 300 dp` **and** at least 96 dp remains after those controls. Otherwise put the complete source line at the beginning of the body, as already specified for large type.
2. Below **192 dp**, Copy yields first: remove its header slot and put a 48 dp **Copy text** text action immediately after the reflowed source in the body. If source is blank, omit that fallback action. Search / Select again / Close remain in the header, right-aligned, requiring 144 dp. Copy is less urgent than recovery or exit, and its context already lives in the body.
3. Below **144 dp**, Select again yields to a 48 dp body text action with the source/context controls; Search / Close stay pinned in 96 dp. Empty states already contain Select again, so do not duplicate that body action there. A failure rail already contains it; do not duplicate it there either.
4. Only below **96 dp** of physical content measure does Search also move to a 48 dp body action, labelled **Search dictionary**, while Close alone remains pinned. Empty states' prominent Search dictionary button is that fallback; do not add another. This last-resort physical fit rule applies equally at either side dock and is not triggered just by font scale. Never shrink a target to preserve icon density.

Where four slots fit but OCR is blank, retain the disabled Copy slot at 0.38 alpha as shipped. Where slots do not fit, absent Copy does not reserve width. Text actions in the body wrap and grow; the pinned band never grows for labels. The body fallback order is source → Copy if usable → Search if relocated → Select again if relocated → body content, with 12 dp after the block. All controls remain available through the one body scroller. At ordinary 360 dp panel width, and at 130/160% text, the four neutral icon targets fit; the source, not the controls, reflows.

Empty states also have §2's prominent Search dictionary button. A dictionary failure still offers the header Search action, but opening search does not pretend a failed repository has recovered. There is no separate Help, permission prompt or new route to configure the database inside search.

### 4.2 Cover, top bar and query

Activating Search opens a **full-screen, opaque `surface` cover in the existing accessibility overlay**, above the sheet and capture. It is not a sheet expansion, second overlay window, translucent panel, Gaten activity, or side-docked search. It uses the entire window in portrait and landscape, even if entered from a 45% dock. The frozen capture, veil, committed mark, sheet dock, selected word, body scroll, Anki state and WaniKani navigation state stay underneath unchanged; their UI is not focusable or actionable while covered. A full-screen opaque search necessarily occludes the capture visually while open; the capture remains rendered and stationary behind it and becomes visible unchanged on return. Never expose the live app around or through search.

The cover uses flat paper, no rounded outer corners, extra dim, shadow or extra boundary. Content observes top/side system and cutout insets. A **48 dp top bar** has a leading 48 dp back-chevron target (**Back to lookup**), a `titleLarge` 17/21 sp **Search dictionary** title and a trailing 48 dp Close target (**Close lookup**). At a measure too narrow for the scaled title between the targets, omit the painted title, not its mode/pane accessibility title. These are two distinct actions: chevron returns without dismissing the lookup; Close ends lookup even with the IME up. They do not mirror based on the former dock. Locale back direction still applies.

Below the top bar, use the house column capped at **560 dp including padding**, centred in the full-screen width; 22 dp side inset, 16 dp below 360 dp. Query block: **12 dp** below the bar, persistent visible label **Dictionary query** in `labelMedium`/`onSurfaceVariant`, **8 dp** to the field. The field is **minimum 49 dp**, **14 dp radius**, **1 dp `outlineVariant` outline**, **opaque `surface` fill**, **14/20 sp** text in `onSurface`, 16 dp horizontal / 12 dp vertical padding, with a **2 dp `primary` focus outline and 2 dp offset**. These shared-field rules hold without alteration: both the cover and field have owned, opaque paper even though the frozen screenshot is underneath. No translucent screenshot-specific field fill or Home search capsule is needed.

Placeholder/hint: **Japanese or romaji**. Do not put a leading search icon inside the field: the top bar and label already state the task. A nonempty field has one trailing **Clear query** glyph target, 48 dp, neutral, which empties it and retains editing focus. Reserve space for the target; do not let text run beneath it. The field is a single logical line with horizontal cursor scrolling; its minimum height grows to scaled line height + 24 dp vertical padding. No autocorrect, automatic capitalisation, or forced Latin keyboard; accept Japanese IME, kana, kanji and romaji, with IME Search action.

**No OCR prefill.** The first search in a lookup session starts blank, even if OCR exists: the owner is escaping potentially wrong or sentence-length source. Do not make them erase that error. Cancelled search preserves its draft, selection/composition and list scroll for this overlay session; opening Search again restores the draft. Choosing a result also retains the last query for the next Search activation. A new capture/session or Select again clears this search draft. No search history, example chips, network suggestions or persisted recent terms are introduced.

### 4.3 Query execution and flat result rows

Use **`DictionarySearch.search(raw, repo::lookup, repo::lookupPrefix, repo::lookupLoose, limit = 24)` verbatim**, against the repository already opened for the overlay. Do not feed typed input into `WordScanner`, run OCR on it, or implement a second normalisation, romaji conversion, deinflection or ranking engine. Its bounded order, deduplication, maximum 24 hits and prefix budget (at most 8) are the result contract, not a claim of dictionary frequency rank.

Search committed edits locally as they change, and on IME Search. During Japanese IME composition keep the last committed-query results; do not label a composing fragment unsupported or announce intermediate counts. A new committed query resets list scroll to top. The operation is synchronous and offline: **no visible spinner, skeleton, timed debounce, artificial loading frame or minimum wait**. Publish the new list and count together when the bounded call returns; never flash No dictionary matches before calling it. IME Search commits the current edit, runs the same search and hides the keyboard without picking a row. It does not add a card.

The field accepts up to **32 code points**, the search helper's query bound. Reject a whole over-limit edit/paste, retain the previous field/results and show **Use 32 characters or fewer** below the field in `bodySmall`/`onSurfaceVariant`; do not silently search a truncated pasted sentence. Remove that helper after the next accepted edit. Keep raw typed text visible; normalization belongs to the existing helper. For empty/unsupported-state classification reuse `DictionarySearch.normalize` and `isSearchable`, including its complete romaji-conversion requirement. Do not introduce an English-translation mode or claim every Latin sequence is invalid.

Below the query block: **12 dp** to a `labelMedium`/`onSurfaceVariant` count, then **8 dp** to the list. Use **{count} results** (singular **1 result**). If 24 are returned, say **Showing up to 24 results**, not a false total or a next-page promise. There is no pagination or infinite scroll. Show every returned row in the one vertical results scroller. Do not group them into nested sections that would rerank the helper output.

Each result is one full-width clickable flat row, minimum **80 dp** high, **16 dp** vertical padding, with a **1 dp `outlineVariant` bottom rule**; no card, thumbnail, kana tile, index, checkmark, star or leading icon. Use:

1. Actual entry headword, `titleMedium` 15/20 sp, `onSurface`, wrapping.
2. Distinct actual readings joined with `・`, `bodySmall` 13/19 sp, `onSurfaceVariant`, **4 dp** below; omit if identical to headword or absent.
3. The first supplied gloss, `bodyMedium` 14/20 sp, `onSurface`, **8 dp** below the last present line, wrapping completely. If absent, use existing **No meaning supplied**. Full senses appear after selection, not in the preview.
4. Match provenance, `bodySmall` 13/19 sp, `onSurfaceVariant`, **4 dp** below: **Exact match** for `EXACT`; **Matched form: {form}** for `NORMALIZED`; **Approximate reading: {form}** for `LOOSE`; **Starts with: {form}** for `PREFIX`.

Do **not** invent separate Kana match and Base form badges. `SearchHit.match == NORMALIZED` does not expose whether it came from kana folding or deinflection; both honestly use **Matched form: {form}**, with the returned `form` making the correspondence visible (ネコ → ねこ, 食べた → 食べる). EXACT means exact after the helper's normalization/romaji conversion, not necessarily literal raw Latin text. Above the count, when romaji conversion is applicable, show **Searching: {kana}** in secondary `bodySmall`, with 4 dp to the count. This explains why `neko` can have an exact ねこ match. `LOOSE` is explicitly approximate; prefix rows are suggestions, not exact answers. Accent never ranks or categorises matches.

At 160% every line and row grows naturally; no two-column headword/reading layout, capped two-line gloss, fixed row height or trailing chevron is allowed. A row remains one 48 dp-minimum target (normally much taller) with all its text in one semantic node.

### 4.4 Empty, unsupported and failed search

- **Empty query:** below the field show **Enter Japanese or romaji** as centred `bodyMedium`/`onSurfaceVariant` in the remaining list viewport, with 24 dp minimum top/bottom space and overflow scrolling. No results count, fake recents, recommendations or artwork. The field is the immediate next action.
- **Nonempty searchable query, zero hits:** use §2's no-matches artwork and caption centred in the remaining results viewport, with the same 140/96 dp size and overflow rule. Do not repeat Search dictionary or Select again buttons inside search; the active field is the recovery, Clear query is available, and Back returns to lookup. Announce **No dictionary matches** once for that committed query. Do not imply the captured source is missing.
- **Nonempty unsupported input:** show **Enter Japanese or romaji**, followed after 4 dp by **English definitions are not searchable**, centred secondary body/small text. No artwork, error colour, result count or row list. This state is based on the existing helper, not a crude Latin-character rejection.
- **Repository unavailable/query failure:** show §1's inline **Dictionary unavailable** error rail and sanitized readable cause in the results area, not the no-matches art. Field and Back/Close remain available. Do not silently open another repository, introduce a retry/download/settings workflow, or show a false zero. If the overlay's repository is already unavailable, entering or editing search cannot make a query succeed; the error remains until the reader returns or closes. A query error is not an Anki error.

### 4.5 Choosing a result and keeping the source honest

**Choosing a result closes the full-screen search and restores the result sheet carrying that exact chosen dictionary entry.** There is no full-screen reading-detail page inside search. Hide the IME, clear editing focus and reveal the sheet using §6; the cover return must not wait indefinitely for a keyboard animation. Keep the existing dock and committed screenshot bounds, not a freshly chosen least-overlap dock based on the typed word.

The returned sheet has the normal complete dictionary entry, including all meanings, readings, POS and WaniKani kanji actions, and the normal reachable Anki footer. Above the entry, in the body, show **Searched: {raw accepted query}**, `bodySmall`/`onSurfaceVariant`, complete wrapping text, with **Original lookup** as a 48 dp text action below it and 16 dp to the entry. Original lookup restores the original OCR result/empty/error state, selected OCR word, tab position and body scroll without rescanning or dismissing. It is not Select again. Hide that action when already showing the original lookup.

Original OCR word tabs are **hidden, not rewritten into search results**, while the searched entry is displayed. Search results are alternatives chosen deliberately, not inferred source tokens. Keep the original recognized source in the header/reflowed source block, and Copy continues to copy that source, not the query. With an originally blank source, do not fabricate an OCR line or enable Copy. The **Searched:** provenance prevents the unchanged committed rectangle or source line from falsely claiming the typed word was read there. If no source box existed, none appears on selection. Never draw a narrower box around a searched word.

Search activation saves any WaniKani back stack. Returning without choosing restores that exact page and scroll. Choosing a result clears the inner reference stack before revealing its dictionary entry. Original lookup then restores the original dictionary-level result and word context, **not** an obsolete reference stack. A subsequent WaniKani visit starts from the newly displayed entry and retains the existing back-first behavior.

The Anki footer is **absent throughout the search list** and appears only for a picked entry in the sheet. It uses the existing configured mappings and write/share routes, now keyed to that entry; the word/reading/meaning are the chosen dictionary data, while any original OCR sentence used by an existing mapping remains real source context, not evidence that the chosen word occurred in it. No query string replaces a sentence mapping. A duplicate check may run, but opening search, typing, IME Search, picking a row, returning, restoring a completed Anki state or visiting WaniKani **never writes**. Only a fresh explicit Add to Anki/Send to AnkiDroid activation commits its existing route. Preserve single-flight and entry-keyed pending/completed state across cover/return and Original lookup; never clear Added to make it appear newly actionable.

**Select again** remains available after return in its normal header/body location. It discards the searched entry, saved original context, search draft and WaniKani stack, then clears the source hole and returns to circle/tap selection on the same frozen capture. Search itself does not offer Select again: Back restores the sheet, where that operation is explicit. Search does not change the frozen capture or restart media.

### 4.6 Keyboard, focus and physical window behavior

Keep the **opaque, focusable, MATCH_PARENT `TYPE_ACCESSIBILITY_OVERLAY`**, without `FLAG_NOT_FOCUSABLE` or `FLAG_ALT_FOCUSABLE_IM`. Keep `fitInsetsTypes = 0`, `FLAG_LAYOUT_IN_SCREEN` and `FLAG_LAYOUT_NO_LIMITS`; do not switch this window to translucency, launch an activity for text entry or ask Android to resize the screenshot. The renderer must use **`WindowInsets.ime` in composition**. `adjustResize` is not the layout solution for this window.

For the cover in **both portrait and landscape**:

- Paint the full-screen opaque surface at its full bounds. Its top bar stays below top safe insets and **does not shift upward** when the IME arrives. The cover never becomes a 45% side panel for typing.
- Inset the cover's interactive content bottom by **max(IME bottom inset, navigation-bar bottom inset)**, not their sum. Use actual IME occlusion on each reported edge plus existing cutout/side-safe insets for a side/landscape keyboard; do not assume a fixed keyboard height or pad the capture. Apply this inset once, not at every nested scroller.
- The results viewport shrinks to the space between query/count chrome and that bottom boundary; it scrolls independently of the fixed top bar/query block. First results begin immediately under the query block, above the IME. Last row can scroll 16 dp clear of that boundary. The field does not ride at the physical bottom or get covered by the keyboard.
- In short landscape, or at large type, if top bar + query block + count + **48 dp readable list** cannot fit above the IME, keep only the **48 dp Back/Close bar** pinned; put query block, helper/count and results in **one vertical scroller** in the remaining space. On focus/IME arrival bring the field's cursor and its full field box into view; then scroll the first result immediately below it into view when space allows. Do not create nested vertical lists or shrink fonts/targets. If even the field and one row cannot physically coexist, the field wins while editing; IME Search/hide reveals the results. Do not promise a first row fits in impossible height.
- Insets track the platform IME transition directly; no independent spring, delayed keyboard padding, expansion of the original sheet or screenshot translation. In the exceptional scroller, normal bring-into-view scrolling moves content only.
- A floating IME may report no docked bottom inset. Leave the viewport otherwise unshifted, retain bring-into-view for the focused cursor, and allow scrolling/keyboard dismissal rather than inventing a full-width inset from guessed keyboard dimensions.

**Focus loss to this session's input method is not dismissal.** Replace the unconditional `closeIfFocusStayedAway` rule, not just the field's Back handler. An IME handoff is a session-bound controller state: the active search editor requested/owns the input connection, and the recipient is its IME (including its candidate/composition window). Cover the IME-opening handoff before its inset becomes nonzero and the closing handoff until root focus returns; a visibility-only `imeBottom > 0` exception is insufficient. The controller must positively attribute this handoff using input-method/window ownership signals; the root's `onWindowFocusChanged(false)` alone does not identify the recipient. This revision therefore requires focus-attribution plumbing, not merely removal of the 200 ms check.

Keep **`FocusBounceMs = 200 ms`** for genuine/non-IME focus losses. Home, recents, another application, notification shade, unrelated system dialog, lock or display interruption still ends lookup under the existing automatic-dismiss/media policy, **even while the IME is visible**. Do not exempt every loss while a field is focused or search is open; external-app departure invalidates the editor/IME handoff immediately. An unclassified loss still uses the existing 200 ms bounce rather than retaining a stale overlay forever. Focus returning within that interval cancels that pending dismissal. Every callback is scoped to the current root/session; an old IME callback cannot restore a closed lookup.

The reader can dismiss the IME with Android Back/keyboard hide without losing the search draft or lookup. Close lookup and the cover's Back chevron do not require a preliminary keyboard-dismiss tap: Close tears down lookup, while the chevron hides the IME and returns to the saved sheet in one activation.

### 4.7 Back, interruption and accessibility

**System Back order:** while search is open, first dismiss an open IME (including a pending open request) and stay in search; with IME hidden, leave search and restore the saved sheet without changing its entry. Outside search, the existing WaniKani radical → kanji → dictionary → overlay dismissal order stands. Back never substitutes for Add or selects the first result. The search list has no toward-dock drag-dismiss gesture; only the restored sheet/grip keeps the existing strict-past-80 dp rule. Search is full-screen, so there is no outside-search scrim tap target. The explicit Close is always reachable.

A configuration/layout change retains this live session's search draft, picked/original context, list position and mode, recalculates safe/IME constraints, and reprojects the unchanged committed source via the authoritative capture-to-view mapping. It does not rerun capture, auto-select a row, add a card or revive an interrupted lookup. If the controller destroys the overlay/session, discard this ephemeral state; do not reopen search after teardown. An external lookup trigger while this session is open still obeys the existing single-flight gate and does not start a second capture or steal editing focus. Genuine focus loss/lock/interruption tears down without waiting for the cover's decorative fade and keeps existing media-resume policy.

Accessibility contract:

- Cover pane/mode title is **Search dictionary**; announce that transition once. Hide the underlying sheet/capture controls from the search traversal tree.
- On entry, keyboard input focus goes to **Dictionary query**, restoring a saved cursor or starting at an empty field. Request the IME for direct touch/keyboard entry; with touch exploration, put accessibility focus on the field and let its normal edit activation request the IME rather than force an unsolicited keyboard. Announce label, hint **Japanese or romaji**, current value and editing semantics; placeholder never substitutes for the persistent label.
- Traversal order: Back to lookup → mode title if painted → Close lookup → field (including Clear query as a separate labelled target) → helper/conversion/count → results or empty/error message. From the field, normal next/swipe reaches count and first result; the IME Search action hides the keyboard and leaves focus on the field, not a silently selected row. A hardware Tab can enter the list; arrows and Enter use normal list/button focus semantics.
- Each result exposes one button node: headword, distinct reading, first gloss, match provenance, then **Open dictionary entry** as its action label. Do not announce visual separators, row numbers or a rank claim. No nested clickable word inside a result row. The semantics remain complete at 160%.
- Count/empty/error changes are a polite live region **once per committed query**, never per composition keystroke, spinner tick or repaint. Coalesce changes within the existing `Motion.SHORT` interval for announcements only; do not delay visible results. Preserve field input/accessibility focus while results update. For a cap, announce **Showing up to 24 results**, not an invented total. A changed error announces the actual message/cause once.
- On cancel/back return, restore accessibility focus to the exact Search affordance that opened the mode (header or empty-state button); if it moved after reflow, use its relocated equivalent. Restore body/tab scroll and original input-free reading state. On choosing a row, focus the chosen sheet entry's headword and announce **Dictionary entry opened** once, then expose provenance and normal reading order. Do not focus or auto-activate Anki.
- Every button/icon target is at least **48 × 48 dp**; filled recovery/footer buttons are at least 52 dp high. Expanded text is uncapped, rows wrap, the focused row/field scrolls into view, and Back/Close remain available above the IME. The field uses the house 2 dp focus outline with 2 dp offset; all other keyboard targets use the same focus grammar.

## 5. Copy — exact additions and changes

Each line below is one UI clause. Dynamic dictionary/OCR data remain verbatim; line wrapping is allowed and is not a second clause. Keys here describe contexts, not new resource naming conventions.

### Search controls and field

| Context | Exact wording |
| --- | --- |
| Header icon / empty-state primary / cover pane and title | Search dictionary |
| Cover back-chevron description | Back to lookup |
| Cover close description | Close lookup |
| Persistent field label | Dictionary query |
| Field placeholder and accessibility hint | Japanese or romaji |
| Clear-field description | Clear query |
| IME action | Search |
| Over-limit edit helper | Use 32 characters or fewer |
| Empty-query prompt | Enter Japanese or romaji |
| Unsupported-input prompt | Enter Japanese or romaji |
| Unsupported-input supporting line | English definitions are not searchable |
| Romaji conversion explanation | Searching: {kana} |
| Results count, one | 1 result |
| Results count, 0 or 2–23 | {count} results |
| Results cap | Showing up to 24 results |
| Exact provenance | Exact match |
| Folded/deinflected provenance | Matched form: {form} |
| Loose-reading provenance | Approximate reading: {form} |
| Prefix provenance | Starts with: {form} |
| Row semantic activation label | Open dictionary entry |
| Successful selection announcement | Dictionary entry opened |
| Returned-sheet provenance | Searched: {query} |
| Restore captured result action | Original lookup |

Zero-hit search displays/announces **No dictionary matches**, not the numeric zero count; `{count} results` is the count grammar, not an additional line under the empty illustration. There is no Search loading string because there is no designed latency state. There is no new retry, search history, English mode, menu or settings string.

### Revised states and relocated controls

| Context | Exact wording | Change from DESIGN.md §9 |
| --- | --- | --- |
| No text caption | No text found | Wording unchanged; now centred artwork state, also used for confirmed idle zero-line index |
| No match caption | No dictionary matches | Wording unchanged; now centred artwork state and zero-hit search |
| Primary empty-state recovery | Search dictionary | New |
| Secondary empty-state recovery | Select again | Wording unchanged; now paired with search |
| Failure rail | Dictionary unavailable | Wording unchanged; remains error, never artwork |
| Failure cause | {actual readable cause} | Existing sanitized-data rule unchanged; omit if none |
| Relocated Copy action | Copy text | Existing accessibility wording becomes visible when relocated |
| Relocated reselection action | Select again | Wording unchanged |
| Search/lookup close | Close lookup | Wording unchanged |

No existing status string is renamed. Reading screen…, Reading selection…, Circle or tap Japanese text, Circle Japanese text, No text here, Copied and the existing Anki/WaniKani copy retain their exact wording. The changed zero-index sequence is behavioral, not a new message. Do not add instructional paragraphs to the empty group.

## 6. Motion — additions only

Use existing `Motion.SHORT = 150 ms`, `Motion.MEDIUM = 300 ms`, `Motion.PAGE = 380 ms`, and `Motion.emphasized = (0.2, 0, 0, 1)`; this revision only needs **SHORT**. No new shared timing, easing or spring. Existing sheet/dim/lasso motion from DESIGN.md §11 remains in force.

| New transition | Motion decision |
| --- | --- |
| Empty-state component replacing terminal inline notice | Immediate layout, **0 ms**; its first appearance rides the existing sheet entrance. Artwork never draws itself, bounces or loops. |
| Open full-screen search | **SHORT / emphasized**, cover opacity 0→1 over the stationary sheet/capture. No sheet morph, expansion, push, scale or lateral travel. Activate the editor once cover is mounted; do not postpone input for animation completion. |
| Back/cancel from search | **SHORT / emphasized**, cover opacity 1→0; the saved sheet is already laid out beneath. Hide IME and clear editor focus at activation, not at fade completion. |
| Pick a search result | Commit selected entry and its provenance beneath the opaque cover with **0 ms** layout; cover fades out over **SHORT / emphasized**. Do not also slide/re-enter the sheet or crossfade two different headwords. |
| Search edits, count/row/empty/error replacement, clear query | **0 ms** visible layout. No animated list reordering, shimmer or art entrance. SHORT coalescing applies only to polite announcements, not query execution. |
| Original lookup | **0 ms** state/layout restoration with saved scroll; no page travel and no source-box animation. |
| Keyboard appears/disappears | Follow platform IME inset progress directly; no second `Motion` tween or spring. Bring-into-view is native scroll behavior, not cover travel. |

System reduced motion/animation scale governs the cover fade exactly as other Compose transitions; zero scale means immediate. Interruption, lock and teardown do not wait for any animation. No sounds or haptics are added.

## 7. Invariants ledger delta

Replace DESIGN.md §6's ledger focus clause and add the search clauses below; all other invariants remain binding.

| Ledger item | Revision decision |
| --- | --- |
| Focus-loss dismissal | Genuine/non-IME loss retains **200 ms** bounce handling and existing automatic-dismiss/media policy. A positively attributed input-method handoff for the active overlay editor, including opening/closing, is not departure. IME visibility or field focus alone never exempts another-app departure. Session-scoped ownership must invalidate stale callbacks. |
| Anki write rule | Explicit-only, single-flight, entry-keyed rule is unchanged and now applies to searched entries. Searching, conversion, row selection, return, source restoration, duplicate checks and configuration change never add. Only the explicit footer activation writes/opens the existing share route. No Anki action appears in the search list. |
| Capture/window contract | Freeze → frame commit/vsync → media-pause ordering unchanged. Same opaque MATCH_PARENT accessibility window, no focus-blocking flags, no translucent holes, activity or second capture. Full-screen search is an opaque compositional cover; capture remains behind it and is never resized/moved for the IME. Insets are composed, not assumed to resize the window. |
| Source provenance | Committed source bounds stay fixed across search. A chosen typed entry is labelled Searched, never presented as newly recognized OCR. Original lookup restores source context. Mapping is recalculated on every layout change; no stale-coordinate animation. |
| Navigation | Search adds an IME-first System Back layer, then returns to the saved sheet; underlying WaniKani back-first order survives cancel. Choosing clears the old reference stack. Cover chevron exits search in one activation; Close ends lookup. |
| Admission/interruption | Existing single-flight trigger and stale-session teardown remain. Genuine departure while typing still dismisses, including while the keyboard remains visible. No fade may postpone lock/interruption teardown or resurrect a capture. |
| Input methods and selection | Both lasso and tap still exist on the frozen capture. Empty-index sheet is recoverable with Select again. Search adds a separate typed-query path and never changes OCR/tap thresholds or media timing. Sheet's strict-past-80 dp dismissal remains; full-screen search does not inherit dock drags. |

State-table delta: replace the No text/No words renderings in DESIGN.md §6 with §2. Replace its empty-full-screen-scan timed rail behavior with §1's idle-only empty shell. Dictionary failure remains a persistent inline rail, but Search is now another available header route; it does not magically repair the repository. Add `Search cover (draft/results/unsupported/empty/error)` and `Searched entry in original sheet` states with §4's navigation, keyboard and provenance contract. DESIGN.md §10's statement that its five proposals are the complete interaction changes and its blanket no-editing scope no longer exclude this explicitly approved dictionary-query mode; OCR text editing is still not introduced.

## 8. Mockup brief — six proof stills

These are the six required review compositions, not claims of implemented screenshots. Use real system insets and real keyboard chrome where visible, not painted fake bars. Labels may be annotations outside the viewport; no annotation becomes production UI.

| Still | State / theme / scale | Must prove |
| --- | --- | --- |
| 1 | Floating **Reading screen…**, portrait, light, 100%; mixed bright/dark foreign capture | 13 dp rounded rail, opaque neutral paper, 1 dp hairline, 16 dp spinner, no accent rule; fixed 40% veil and no screen-edge ring. Include a detail crop of the static Circle or tap state in the same review board, not a second simultaneous production rail. |
| 2 | **No text found**, bottom sheet, dark, 130% | Centred 140/96 dp reading-bracket art as viewport rule dictates, secondary caption, prominent Search dictionary and secondary Select again, empty source/disabled Copy, Search in top-right cluster, no footer/tabs. Frozen capture visible outside the sheet. |
| 3 | **No dictionary matches**, left 45% dock, light, 160% | Lens/book art, actual wrapping OCR source and Copy fallback if needed, both empty-state actions, 96 dp art, one reachable scroller, 48 dp targets; no widened dock, clipped caption or false missing-text claim. |
| 4 | Full-screen search entered from a right 45% dock, dark, 160%, landscape IME visible | Opaque full-window cover rather than expanded dock, Back/Close above IME, labelled house field, Matched form row and a prefix row, same type scale, exceptional one-scroller layout if height demands it, no Anki footer, no shrunken targets. Annotate that the preserved capture/dock is behind the opaque cover, not live content. |
| 5 | Full-screen search, light, portrait, IME visible, romaji query with results | Japanese or romaji field grammar, actual query, Searching: {kana}, honest count, flat Exact match/Approximate reading rows in helper order, complete first gloss, no spinner; first result and field above keyboard, no nested card or index. |
| 6 | Picked searched entry returned to right 45% sheet, dark, 130% | Original committed mark and source still visible/retained, Searched: provenance and Original lookup action, no OCR tabs, full chosen dictionary entry, reachable explicit Anki footer and Select again, normal Search/Close controls. Add an exterior inset showing Dictionary unavailable as the revised rounded inline error rail, not an illustrated empty state. |

The six stills together cover both side directions, floating/inline rail distinction, both artwork facts, full-screen search, IME geometry, large type and explicit commit. Runtime focus-loss and Back-order behavior require interaction checks; a still cannot prove them.

## 9. Design-language replacement text

Do not edit the language file in this design-only revision. The following is exact replacement/addition copy for the engineer to apply with implementation, rather than instructions to retain obsolete square-rail prose.

### Replace all prose under §8.4, up to §8.5, with:

> One rounded, neutral status rail carries instructions, real progress and notices; empty lookup results have a different centred body treatment. Floating over the frozen screen, the rail is opaque `surface` at alpha 1.00, with 13 dp corners, a 1 dp `outlineVariant` perimeter, 12 dp content padding on every side and a 48 dp minimum height. It has no accent leading rule and no decorative substitute for that rule. It is content-sized up to 360 dp, with 16 dp safe side margins and a position 16 dp below the real top insets. Inline notices use the same opaque fill, 13 dp corners, 1 dp neutral perimeter and padding, at full content-column width with 16 dp of air above and below. Neither placement has elevation, shadow, gradient, glow or screenshot-dependent colour.
>
> The opaque paper, neutral hairline and readable ink identify the rail over foreign content; the existing 40% black veil separates context but is not a universal contrast guarantee. Text is `bodyMedium` in `onSurface`; a failure title and its 18 dp glyph use `error`, with an optional `bodySmall` cause in `onSurfaceVariant` 4 dp below. A real foreground task uses a 16 dp, 2 dp-stroke `primary` spinner with an 8 dp text gap; otherwise use an 18 dp neutral outline glyph. An inline action sits 8 dp below the message, uses `labelLarge` in `primary`, and has a wrapping 48 dp-minimum target. Enter, exit and text replacement are opacity only over `SHORT` on `emphasized`; relayout height immediately and announce each changed status once, politely.
>
> Index loading says Reading screen…; readiness says Circle or tap Japanese text for at least 2000 ms, extended by the accessibility timeout and hidden immediately on drawing. A committed crop says Reading selection… until usable content or a terminal result. A tap miss says No text here and then the applicable instruction without dismissing. An unavailable index says Circle Japanese text, never a false confirmed zero. A confirmed zero-line index while idle opens the No text found sheet without fabricating a selection; it never interrupts an active gesture, crop, result or search. Selecting again returns to selection without reopening that same zero-index notice.
>
> No text found and No dictionary matches use sparse neutral SVG artwork and a secondary caption centred in the bounded modal body, with Search dictionary as the primary recovery and Select again as the secondary action. They persist until the reader acts, reflow and scroll at large type, and have no word tabs or Anki footer. Preserve recognized source for the no-matches state. Dictionary unavailable is a persistent inline error rail with its real readable cause, not an illustrated empty state. No terminal message is duplicated in a floating rail. The exact artwork, centring rules and search behavior are specified in DESIGN-REV2.md §§2–4.

### Replace §8.10 item 2 with:

> 2. A neutral, opaque status rail with **13 dp corners and a 1 dp `outlineVariant` perimeter**, in both floating and inline placements, with **no accent leading rule**. Its boundary is a notice recipe, not a general rounded-card treatment.

Replace §8.10's final two sentences with:

> These are component recipes, not new shared tokens: they use existing roles, the existing 13 dp control radius value and shared spacing without adding a colour, typography, shape or motion rung. Centred lookup artwork and the full-screen dictionary-search cover reuse the app's sparse-artwork, field and focus grammar; they do not authorize translucent fields, tinted content cards or extra windows.

### Place the new search text inside §8.5

Immediately after the WaniKani reference-pages bullet, add the following subheading and paragraphs; do not count search as a fifth independent overlay window in §8.1:

> **Search within lookup.** Search dictionary is a 48 dp neutral search action in the sheet's top-right control cluster, ordered Copy → Search → Select again → Close. Source text reflows before controls shrink; Copy, then Select again, relocate into the body when physical width requires it. Search remains beside Close unless even two 48 dp targets cannot fit, when its labelled body action remains available. Empty states also offer the prominent Search dictionary recovery.
>
> Search opens a full-screen opaque `surface` cover inside the same accessibility overlay, not an inline field, activity, second window or expansion of a side dock. Its Back to lookup chevron restores the saved sheet; Close lookup ends the overlay. A labelled Dictionary query field uses the house 49 dp minimum, 14 dp radius, 1 dp neutral outline, opaque surface fill and 2 dp accent focus outline with 2 dp offset. Start the first query blank, accept Japanese or romaji, and reuse the existing offline DictionarySearch helper and open repository verbatim. Flat, wrapping rows preserve the helper's bounded order and expose their exact, matched-form, approximate-reading or prefix provenance without inventing a distinction the result data do not provide.
>
> Picking a row returns to the original sheet with that chosen entry and an explicit Anki footer. Hide OCR word tabs while a searched entry is shown, preserve the committed capture and original source, label the typed provenance Searched, and offer Original lookup to restore the captured result. Cancel restores the saved reference page; choosing clears its old WaniKani stack. No search operation or result selection writes to Anki; only a fresh explicit footer activation may commit the existing write/share route.
>
> The full-size opaque window does not resize for the keyboard. Compose the cover's content against WindowInsets.ime, keeping Back/Close and the query reachable and reducing the results viewport; in insufficient height, retain the control bar and put query plus results in one scroller. Never resize or shift the frozen capture. An attributed handoff to the active editor's IME is not focus-loss dismissal, but another app or unrelated window taking focus still uses the existing 200 ms bounce and automatic-dismiss policy, even with the keyboard visible. System Back hides the keyboard first, leaves search second, then follows the existing sheet/reference/overlay order. Full interaction and accessibility rules are in DESIGN-REV2.md §4.

As part of that eventual documentation cutover, replace the Summary's “square status rail with a 2 dp accent rule” with “13 dp rounded neutral status rail with no accent rule”; change §8.5's “three 48 dp controls” to “a responsive cluster of four 48 dp controls”; remove its obsolete three-control reflow assumption in favor of the physical-fit rule above; and point §8/§8.8's source-of-truth links to DESIGN.md **and this revision**. Keep historical version notes historical; do not describe the old rail as current. §8.9 may add: “The full-screen search cover enters and leaves by SHORT emphasized opacity; query/results changes are immediate and keyboard geometry follows native IME insets.”
