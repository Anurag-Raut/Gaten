# Ink — screen lookup design specification

All ten reference PNGs in BRIEF §4 were received and viewed, including the light result sheet, live lasso, bubble, and Ink sibling screens. This is a design decision document, not an implementation or a verification report.

This specification extends Ink v1.3 to an instrument drawn over somebody else's content. The result is **a frozen page, a precise two-tone selection mark, and one flat reading surface**. Remove the screen-edge ring, gradient, bloom, tinted entry cards and metadata capsules. Keep the external sheet's existing 20 dp dock corners; a floating window is not an in-page row card.

## 1. Entry moment

### Fixed foundations

- Resolve the user's System/Light/Dark preference and all seven accents exactly as `ThemePreferences`, `accentRoles()` and `GatenTheme` do. Never infer the overlay theme from screenshot brightness. The Black preference remains Black in storage and resolves to white primary in dark mode.
- Opaque UI surfaces use alpha **1.00**, elevation **0 dp**, tonal elevation **0 dp**, no shadow, blur, glow or gradient. Text alpha is **1.00** unless explicitly disabled. A missing screenshot region remains opaque black; it never reveals the live app.
- Shared colors: `surface` = #FFFFFF / #17181C, `background` = #FDFDFD / #101114, `onSurface` = #17171A / #F5F5F6, `onSurfaceVariant` = #6C6C75 / #A7A7B0, `outlineVariant` = #E9E9ED / #303136, light/dark respectively. `outline` is the same rule color. All actions and selection cores use resolved `primary`; pair a primary fill with `onPrimary` (#FFFFFF / #111111). No independently chosen indigo, tertiary color or service-brand fill enters this workflow.
- Kotlin type roles are authoritative here: `displaySmall` 34/44 sp, medium, native Japanese; `titleLarge` 17/21 sp, bold Inter; `titleMedium` 15/20 sp, semibold Inter; `bodyLarge` and `bodyMedium` 14/20 sp, regular Inter; `bodySmall` 13/19 sp; `labelLarge` 15/20 sp, semibold; `labelMedium` 12/18 sp, semibold; `labelSmall` 12/16 sp, medium. Japanese uses platform fallback. Do not reproduce the RN word detail's screen-specific 29 sp bold glosses inside the capped sheet.
- Insets are real window insets, not painted system chrome. Gaps use 4, 8, 12, 16, 20 and 24 dp; the existing house content inset is 22 dp and the existing house column cap is 560 dp including padding. Focus is 2 dp `primary` with 2 dp offset. Every action has a minimum 48 × 48 dp effective target.

**Rationale — neutral foundation / native behavior:** carry the same paper, ink, scale and insets from the app into the overlay rather than creating a second theme.

### Freeze and arming

1. The first visible frame is only the opaque frozen capture, unaltered. Preserve the admission gate and the existing frame-commit → additional vsync → media-pause scheduling → lookup-UI ordering, including existing safety timeouts. Do not delay capture or OCR for a visual animation, and do not replace the opaque `TYPE_ACCESSIBILITY_OVERLAY` with a translucent window.
2. Once the controller permits lookup UI, fade the dim **from alpha 0 to 0.40 over `SHORT`, 150 ms, `emphasized`**. Its color is fixed black (`Color.Black`, the existing scrim color), identical in both themes. The capture itself does not fade. No warm/cool tint and no accent wash.
3. In the same 150 ms, show the status rail specified in §4. While the index is loading, it says **Reading screen…**; when selection is ready it says **Circle or tap Japanese text**. The rail has a 2 dp accent leading rule and a neutral opaque backing, not a white lozenge.
4. Delete the edge ring entirely: **0 dp**, alpha **0**, no reveal, drift or retraction. There is no permanent screen frame in selecting, scanning or result states.
5. In the first 300 ms after UI admission the dim and rail settle; no other entrance animation plays. This is not a promise that capture, media or OCR completes within 300 ms of the trigger. The live lasso remains available while the full-screen index is being built; tap lookup becomes useful as soon as its index exists, without an artificial wait for background transcription.

**Call:** keep a modest 40% black veil in both modes rather than brighten dark screenshots. The clear selection must still show the source faithfully. Opaque controls and the two-tone stroke provide contrast; the veil is context separation, not a purported universal contrast guarantee.

**Rationale — purposeful artwork / honest state:** the frozen content and a literal instruction communicate arming without ornamental screen-edge activity.

**Design-language amendment:** approve a black 40% freeze veil and a flat status rail over foreign content. They are overlay-specific recipes, not new palette tokens. **New tokens requested: none.** All new component dimensions and alpha recipes below use existing roles and spacing; no new shared color, typography, motion or radius rung is required.

## 2. The drawn gesture

| Part | Exact decision | Ink rationale |
| --- | --- | --- |
| Path | Retain midpoint quadratic smoothing and the sampled bounds. Two solid passes on the same path; no trailing fade, particles or velocity response. | A direct mark is an instrument, not artwork. |
| Outer stroke | **5 dp**, `onPrimary`, alpha **1.00**, round cap and round join. | Paired ink protects the action color from foreign backgrounds. |
| Inner stroke | **3 dp**, `primary`, alpha **1.00**, round cap and round join. | Selected accent names the user's current action. |
| Removed effects | Bloom **0 dp / alpha 0**; gradient removed; fixed black casing removed. The outer pass remains as a hard-edged contrasting keyline, never a shadow. | No glow or gradient; no theme-independent dark outline masquerading as decoration. |
| Short and long travel | At **60 px** and **600 px** of travel, use the exact same 5/3 dp widths, alpha and color. Travel length does not change thickness or hue. At 2.75 px/dp these widths are 13.75/8.25 px; distance is not a stroke token. | One clear hierarchy stays stable with gesture size. |
| Single sample | Concentric disks centered on the first drag sample: outer diameter **5 dp** `onPrimary`, inner diameter **3 dp** `primary`, both alpha **1.00**. No pulse or delayed appearance. A plain tap does not leave a persistent dot. | Immediate feedback answers the first real input. |
| Lifetime | Finger-following drawing is immediate, **0 ms**. Remove the freehand trail immediately on accepted lift or cancel; the committed box remains after accepted lift. | Motion follows input, not a parallel animation. |

The two colors are always an existing contrast pair: deep accent with white keyline in light theme; pale accent with #111111 keyline in dark theme. With Black/White the pair becomes nearly black/white. Against a white page the dark member remains visible; against a dark frame the light member remains visible; busy captures still have a hard continuous boundary between the two. Do not claim an unmeasured contrast ratio for arbitrary pixels. No screenshot sampling or adaptive palette is introduced.

Keep the existing **24 view-px minimum in both selection dimensions**, existing drag/tap classification and **24 dp tap-hit tolerance mapped into capture pixels**. Do not change these thresholds into a new gesture model. A rejected sliver returns to selecting with the instruction, not an empty result.

**Design-language amendment:** a two-pass, opaque paired-color stroke is approved solely for selection over foreign pixels. It is a contrast keyline, not an elevation, halo or general card border. **New token: none.**

## 3. Selection rendering

- The hole is the existing sampled/tapped bounding rectangle, with **4 dp corner radius**, clamped to half its smaller dimension. Do not add crop padding, expand OCR bounds to match cosmetic corners, or move text into a magnifier. Remove the 12 dp soft-window appearance.
- Outside it, black dim remains **0.40**. Inside it, dim is **0.00**: the unmodified frozen pixels remain visible. The hole is cut from the scrim, never from the opaque capture/window.
- While drawing, show the growing hole once both dimensions are nonzero. Its boundary is two solid centered strokes: **4 dp `onPrimary` outer**, then **2 dp `primary` inner**, both alpha **1.00**, following the same 4 dp rounded rectangle. Clip at display bounds. No bloom.
- On accepted lift or a tap hit, freeze the bounds immediately. While the crop is being read, keep that exact 4/2 dp boundary and show **Reading selection…** in the rail. No marching ants, pulsing edge or animated rectangle.
- With a result up, keep the hole and scrim unchanged. Change the boundary to **3 dp `onPrimary` outer + 1 dp `primary` inner**, both alpha **1.00**, with an immediate state change. **Remove the current ×0.4 alpha fade.** Lower line weight, not transparency, makes the source secondary without destroying contrast on arbitrary content.
- Draw the selection below the sheet. It is a source marker, not another floating card. No connector, arrow, duplicate source thumbnail or accent line connects it to the sheet. The plain OCR line is the textual bridge. If the bottom sheet covers part of the source, accept that occlusion rather than move/scale the frozen frame. Landscape retains the existing least-overlap dock choice.
- Selecting another word in the sheet does **not** pretend that a narrower source region is known: the committed source box remains the lookup's original region. Select again clears the hole and trail, then restores the instruction as the old sheet exits.
- Capture-px ↔ view-px remapping remains authoritative after any layout change. Reproject source bounds and redraw; do not treat view coordinates as capture coordinates or animate a box between stale spaces.

**Call:** a smaller 4 dp source corner is allowed without adding a shared shape token; it is the existing micro spacing used as an overlay geometry recipe. Keep the external sheet's 20 dp corners instead of making every part square.

**Rationale — content before containers / native behavior:** show the actual selected source and its provenance with a precise rule, not a glowing miniature panel.

## 4. Status and notice surfaces

Use one **Lookup status rail**, with floating and inline placements of the same visual component. Hint, screen-reading, selection-reading, no-text, no-words, dictionary failure and tap-miss all use it; there is never a second simultaneous copy of the same message.

| Property | Specification | Ink rationale |
| --- | --- | --- |
| Floating placement | Top-center within status-bars-ignoring-visibility + display-cutout + horizontal safe insets; **16 dp** below that top boundary; **16 dp** minimum side margins; width capped at **360 dp**. No fixed line count. | Real insets and wrapping, not a centered pill sized to one English string. |
| Floating form | `surface` alpha **1.00**, **0 dp** radius, **1 dp `outlineVariant`** perimeter, **2 dp `primary`** leading rule drawn inside the border. **12 dp** horizontal and **12 dp** vertical content padding. No shadow. Minimum height **48 dp**. | Rules and opaque neutral paper identify a tool over foreign content. |
| Inline placement | Full available body width, same **0 dp** radius, same **2 dp** leading rule and **12 dp** padding, transparent fill over the sheet's opaque `surface`, no outer perimeter. **16 dp** above/below it. | A notice inside a reading surface does not need another container. |
| Copy | `bodyMedium` **14/20 sp**, `onSurface`. Dictionary or service failure text uses `error`, including a 18 dp error glyph; the leading rule remains `primary`. Supporting cause, only if needed, is `bodySmall` **13/19 sp**, `onSurfaceVariant`, **4 dp** below. | Honest state is readable without color alone. |
| Busy indicator | A **16 dp**, **2 dp** stroke native indeterminate circular indicator, `primary`, alpha **1.00**, leading with **8 dp** gap. Only while real foreground work is pending. Otherwise a static **18 dp** outline glyph: gesture, search-off, info or error as appropriate. | A functional progress indicator is acceptable; ambient animation is not. |
| Announcements | Polite live-region announcements once per changed status; no repeated announcements from background line completion or spinner ticks. | State is semantic, not just visual. |

**Lifecycle decisions:**

- Initial loading persists for the actual full-screen index task. Once ready, the instruction remains for **2000 ms**, then fades out. Starting a drag hides the floating instruction immediately so it cannot obscure the ongoing mark. Select again restarts the instruction lifetime.
- A tap miss shows **No text here** for **2000 ms**, then **Circle or tap Japanese text** for **2000 ms**. A tap before the index is ready says **Reading screen…** while that work continues; with an unavailable index, say **Circle Japanese text** for 2000 ms. A miss never dismisses the overlay.
- A full-screen index with zero lines shows **No text found** for 2000 ms, then **Circle Japanese text** for 2000 ms. This does not disable either input path; exact-crop lasso can still recover text the index missed.
- During the committed crop, **Reading selection…** persists until the first usable result or terminal result. Once usable content exists, background refinement does not cover it with a spinner or reset its scroll/selection state.
- Selected-crop no-text, no-words and dictionary failures appear **inline in the result shell**, persist until select again/dismiss, and have one text action, **Select again**, target ≥48 dp, `labelLarge`, `primary`, **8 dp** below the message. Do not repeat that notice in a top rail.
- Floating transient lifetimes use Android's recommended accessibility timeout, with 2000 ms as the minimum. Any focused notice remains while focused. Persistent failures have no timeout.
- Enter/exit and text replacement: **`SHORT` 150 ms, `emphasized`, opacity only**. Never animate rail size through clipped text; relayout height immediately.

**Call:** retain a spinner, but only as a small truthful loading glyph. The rail's square geometry and accent rule, not a new palette or a screen ring, identify the workflow.

**Rationale — honest state / one clear hierarchy:** the same component names work, absence and failure without conflating a failed index, a missed tap and an empty dictionary result.

## 5. Result sheet

### Outer shell and nesting

**Bottom:** full window width, content-sized up to the existing **60% window-height cap**, docked to physical bottom. Use `surface` alpha 1.00, existing `SheetShape` **20 dp top corners**, 0 dp bottom corners, no elevation or independent scrim. Paint behind the navigation inset, but inset interactive content above it. Add a **1 dp `outlineVariant` rule on the exposed top boundary**, following the rounded corners; no perimeter border against physical display edges.

**Left/right:** existing **45% window-width**, full window height. Left has 20 dp top-right/bottom-right corners; right has 20 dp top-left/bottom-left corners. Use the same `surface`, alpha and zero elevation; a **1 dp `outlineVariant` rule only on the inner/free boundary**. Preserve the existing least-overlap selection rule, center-distance tie-break, then right-side final tie-break. Safe drawing insets apply at top/bottom and the docked outer edge; a cutout on the opposite screen edge must not shift this panel. Do not widen the panel at large type or introduce a floating middle-sheet variant.

**Call:** retain current dock fractions and external corner token. Remove nested rounded cards, not the one window that genuinely contains the reading task.

**Rationale — content before containers:** one flat surface is sufficient; its contents are typography, spacing and rules.

Exact nesting, with no intermediate filled card:

1. `Opaque sheet shell`
2. `Grip` (bottom's horizontal band or side's inner vertical gutter)
3. `Content column` (maximum 560 dp including insets)
   - pinned `OCR/control header`, then 1 dp rule;
   - **one vertical body scroller**, containing source text when reflowed, word-selector strip, and the entry **or** inner WaniKani body **or** status rail;
   - pinned `Anki footer` on dictionary entries only.

Inside an entry the order is **headword → reading → Common / short POS summary → optional spelling and inflection notes → rule → Meaning / glosses → full part-of-speech list → WaniKani kanji → rule / Other matches / runner-up rows**. Hide absent blocks and their gap; no empty placeholder card. Keep access to every kanji the overlay currently offers, including those WaniKani may not cover.

**Rationale — one clear hierarchy:** the dictionary meaning comes before optional reference navigation; metadata stops competing with the word.

### Grip and column

- Bottom grip band is **48 dp high**, full width. Its painted handle is **32 × 4 dp**, radius **2 dp**, `onSurfaceVariant` at alpha **0.60**, centered. Keep its width fixed during drag: no stretch-to-48 animation. The band is a drag target with accessible dismiss action; the explicit Close button remains available.
- Side inner grip gutter is **48 dp wide**, full safe height, with centered **4 × 32 dp** handle, same radius/color/alpha. Mirror only the dock geometry, not icon semantics. The gutter is the reliable horizontal dismissal affordance; it is not a navigation rail.
- Bottom horizontal content inset **22 dp**, reducing to **16 dp when sheet content width is below 360 dp**. Side inset **16 dp**, not the former 12 dp. Center the column if wider than 560 dp. Gutter width and native insets are outside this content width calculation.
- Vertical order: grip → header with no extra top gap; header rule → **12 dp** to selectors; selectors → **16 dp** to headword. Entry groups use **20 dp** separation and **8 dp** internal gaps. End the body with **16 dp** padding above the footer rule.

**Rationale — native behavior / direct actions:** preserve a generous deliberate drag affordance while making the reading measure consistent with the app.

### OCR-line header and three controls

- Transparent header on the sheet, minimum **48 dp**. Source text uses `bodyMedium` **14/20 sp**, `onSurfaceVariant`, one-line ellipsis at normal layout. Copy uses the **complete** recognized string, not the ellipsized presentation. TalkBack exposes the complete string.
- Trailing controls in order: **Copy text**, **Select again**, **Close lookup**, each **48 × 48 dp** target with **20 dp** neutral `onSurfaceVariant` outline glyph; no circle/pill backing. Use copy, gesture and close glyphs already available. All remain in the same order for both side docks.
- When **usable content width / fontScale < 300 dp**, remove source text from the pinned controls row and put the complete wrapping source line at the beginning of the body scroller, `bodyMedium`, **12 dp bottom gap**. Right-align the same three controls in a 48 dp header. Do not add another pinned source row; do not shrink targets. Empty source has no Copy action (retain its 48 dp slot, visibly disabled at alpha **0.38**) and no fabricated ellipsis source label.
- Normal pinned source ellipsis is intentional; the responsive full line and accessibility string preserve its information. Copy confirmation is **Copied**, using the native toast rather than a second custom overlay notice.

**Rationale — content before containers / scalable text:** the OCR source is a toolbar datum, not a tinted 17 dp-radius card, and reflow yields space to actual reading.

### Word selection, formerly chips

- Keep one horizontal scroller, now **unfilled word tabs**. Each target has minimum **48 dp** height and **48 dp** width; **12 dp** horizontal / **12 dp** vertical padding, **8 dp** inter-tab gap; height grows for system-scaled text.
- Text is `labelLarge` **15/20 sp**. Selected text = `primary`; unselected = `onSurfaceVariant`. All backgrounds transparent, radius **0 dp**. Selected has a **2 dp primary bottom rule**, alpha 1.00. Reserve a **16 dp** check slot plus **4 dp** gap in every tab; paint the selected check only. No fill, capsule or width jump when switching.
- Keep earlier/more paging arrows as **48 dp** targets with **20 dp** glyphs, `onSurfaceVariant`, opaque `surface` masks and **0 dp** radius. They overlay only when scrolling is possible; no gradient edge masks and no measurement change. Arrow press pages **85% of visible strip width**. Swipe continues to work; selected semantics and focused-item visibility remain.
- Place the strip in the body scroller, not another permanently pinned band. Omit it when there is only one selectable word; this is not loss of selection access.

**Rationale — meaningful accent / direct actions:** a rule and check identify the current word without turning the sentence into a row of filled pills.

### Entry block

| Element | Exact treatment | Ink rationale |
| --- | --- | --- |
| Headword | `displaySmall` **34/44 sp**, `onSurface`, alpha 1.00, own wrapping line; same type role in every dock. | The word is content, not a colored action. |
| Reading | **8 dp** below, `titleMedium` **15/20 sp**, `onSurfaceVariant`; join actual readings with `・`; omit a duplicate of the headword. Wrap, no badge on this line. | Secondary information stays secondary. |
| Common / short POS | **8 dp** below reading (or word if no reading), `bodyMedium` **14/20 sp**, `onSurfaceVariant`, plain **Common · {short POS}**; no badge. Use the existing compact POS mapping from the RN sibling for this summary only. | Flat metadata replaces a misleadingly actionable capsule. |
| Spelling and inflection | Each optional line **4 dp** after metadata, `bodySmall` **13/19 sp**, `onSurfaceVariant`. **Also written: {forms}** and **From {surface}**. Keep actual forms and full source; do not invent inflections. | Provenance is real information, not decoration. |
| Meaning section | **20 dp** below headword group, 1 dp `outlineVariant` rule, then **20 dp** top gap. **Meaning** in `labelMedium` **12/18 sp**, `onSurfaceVariant`; **8 dp** to first gloss. | Rules distinguish sections without nested surfaces. |
| Glosses | All supplied glosses, `bodyLarge` **14/20 sp**, `onSurface`, one numbered row per gloss. Index uses `labelMedium` **12/18 sp**, `onSurfaceVariant`, not accent; number gutter is max(**24 dp**, widest measured index + **8 dp**). **12 dp** between rows. No maximum lines or truncation. | Quiet neutral numbering makes prose, not labels, the focus. |
| Full POS | If present, **16 dp** after glosses: **Part of speech** `labelSmall` **12/16 sp**, then **4 dp** to unshortened POS values in `bodySmall` **13/19 sp**, `onSurfaceVariant`, joined with ` / ` and allowed to wrap. No pills. | A short summary does not erase dictionary precision. |
| Kanji strip | **20 dp** after full POS/glosses. **WaniKani kanji** `labelMedium` **12/18 sp**, `onSurfaceVariant`, **8 dp** to a wrapping row of bare character actions. Minimum **48 × 48 dp**, **8 dp** gap, glyph `displaySmall` **34/44 sp**, `primary`; size grows to glyph line-height + **8 dp** at large text. No tile fill, border or radius. | These characters really are actions, so accent is meaningful here. |

**Call:** use the existing 14/20 body role for glosses in both docks rather than preserve the old 18/26 and 16/22 local overrides. The compact overlay is a reading tool, not the RN full-page display; common scale, complete prose and large-text reflow beat oversized content that hides its own meaning.

### Other matches and runner-ups

- **20 dp** after the preceding content, a full-content-width **1 dp `outlineVariant` rule**. Expander row minimum **48 dp**, **12 dp** vertical padding, **Other matches ({n})** in `bodyMedium` 14/20, `onSurfaceVariant`, trailing **20 dp** neutral chevron. Preserve expanded/collapsed semantics and the actual count.
- Expanded entries are flat read-only rows, **16 dp top/bottom padding**, separated by **1 dp `outlineVariant`**, no outer border, fill, inner card or 17 dp corners. Headword `titleMedium` 15/20 `onSurface`; reading `bodySmall` 13/19 `onSurfaceVariant` on its own line **4 dp** below; existing first **two glosses**, `bodyMedium` 14/20 `onSurface`, **8 dp** below reading and **4 dp** between glosses. Both glosses wrap fully; they are still a preview of other dictionary matches, not a complete switched entry.
- Do not add row click, export, ranking claims or automatic runner-up selection. The primary Anki action continues to refer to the selected scanned word's primary entry, never to a runner-up merely brought into view.

**Rationale — honest state:** JMdict does not supply a reliable homograph ranking; preserve alternatives without inventing a second interaction model.

### Pinned primary action

- Footer on `surface`, alpha **1.00**, **1 dp `outlineVariant` top rule**, **12 dp** top padding, **8 dp** bottom padding **plus native bottom inset**, same horizontal inset as content. No elevation or footer shadow.
- A deck row has minimum **24 dp** height: **Anki deck** `bodySmall` 13/19 `onSurfaceVariant`; actual configured deck `labelMedium` 12/18 `onSurface`, trailing with wrapping. Above **1.2 font scale**, put deck value on its own left-aligned line. **8 dp** from deck row to button. In the no-database-permission share route omit this deck row: Gaten cannot claim the add screen will use that destination.
- Button fills available width, minimum **52 dp** height, **13 dp** radius, `primary / onPrimary`, `labelLarge` **15/20 sp**, **16 dp** horizontal / **12 dp** vertical padding. Leading plus **18 dp**, **8 dp** gap. The label wraps when needed; do not reduce it to the old compact 12 sp label.
- Direct-write idle label **Add to Anki**; checking **Checking Anki…**; pending write **Adding…**; success **Added** plus check; duplicate **Already in Anki**. Keep completion labels for the remainder of that entry session. Do not revert to an apparently actionable Add label on a disabled completed button.
- Before database access is granted, label the button **Send to AnkiDroid** with an external/open glyph instead of plus. Tap opens AnkiDroid's add screen. Do not request database permission or imply a direct write inside this service overlay. If AnkiDroid is not installed, omit the button and show **Install AnkiDroid to add cards** in `bodySmall`, `onSurfaceVariant`.
- Checking/adding are semantically disabled with a **16 dp / 2 dp** spinner; retain the label. Disabled fill = `onSurface` alpha **0.12** over `surface`, text/glyph = `onSurface` alpha **0.38**; completed read-only state uses the same disabled treatment with its honest label. Error = persistent `bodySmall`, `error`, **8 dp** above the footer button; re-enable the existing explicit add action only after failure. No automatic retry.
- Only a fresh, explicit button activation may write. Opening the sheet, selecting a word, OCR refinement, WaniKani navigation, dismiss drags and restoring layout may perform duplicate checks, **never an add**. Preserve the current single-flight write guard and entry-keyed state. Dragging onto/releasing over a button is cancellation, not activation. Keep existing haptics/sounds subject to user settings; no new celebration animation.

**Rationale — honest state / direct actions:** one stable, correctly named commit control makes accidental Anki writes and false completion receipts less likely.

### WaniKani inner page

Keep the **same outer sheet, grip, OCR controls and selected-word context**, replacing the dictionary body/footer area, not opening a second sheet or dialog. The selector strip stays in the body above the reference content; switching words clears the inner stack as today. Its own Back target, Refresh target and external-link target are **48 × 48 dp**, glyphs **20 dp**; header uses a wrapping `titleLarge` **17/21 sp** title or neutral `displaySmall` **34/44 sp** character. At constrained measure, title and controls use separate rows within the same body, not smaller targets.

The WaniKani body shares the one body scroller; do not nest a second vertical scroller inside it. Meanings, on'yomi, kun'yomi, radicals and mnemonics use **20 dp between sections**, labels `labelMedium` 12/18 `onSurfaceVariant`, **8 dp** below labels, prose `bodyLarge` 14/20 `onSurface`; readings `titleMedium` 15/20 `onSurfaceVariant`. Neutral glyphs, no primary-colored reference headings. Radical links are unfilled `primary` actions, ≥48 dp targets, **8 dp** gaps; image-only radicals keep their real transparent **72 dp** artwork box (**56 dp** side dock), inverted in dark theme as today. Artwork is content, not a tinted holder.

Keep static `surfaceVariant` skeleton blocks, radius **0 dp**, alpha **1.00**, **14 dp** line height, **8 dp** line gaps; no shimmer. Retain the existing WaniKani loading semantics and all distinct no-token, rejected-token, out-of-subscription, no-entry, unreachable, cached/stale and cache-save-failure states. Render notices with §4's inline rail, existing Try again/Open Gaten actions only when meaningful. Keep cached content available on refresh failure. The Anki footer is **absent** on every inner reference page; no WaniKani prose is exported. Back unwinds radical → kanji → dictionary before overlay dismissal. Existing PAGE slide and parallax remain, with clipping to the sheet and no elevation effect.

**Rationale — content before containers / honest state:** reference pages are another reading depth within the same instrument, never a card stack or a new export target.

## 6. States and responsive behavior

| State | Exact rendering and behavior | Ink rationale |
| --- | --- | --- |
| Initial loading | Frozen capture + 40% veil + floating **Reading screen…** rail and 16 dp spinner. No fake detected boxes. Keep crop lasso usable. | Name the actual work without inventing results. |
| Selection loading | Clear committed hole with full 4/2 dp edge + **Reading selection…**. Do not show a blank result scaffold; open the shell only with content or a terminal state. | Stable source precedes the answer. |
| No text in selected crop | Normal shell and header (Copy disabled if blank), inline **No text found**, Select again text action. No word tabs, Anki footer or empty-state card. | Absence is not dictionary failure. |
| No words | Recognized source retained, Copy enabled, inline **No dictionary matches**, Select again; no export button. | Recognized text and a dictionary match are different facts. |
| Dictionary failure | Retain recognized source and hole, inline **Dictionary unavailable** with real sanitized user-readable cause below when supplied; `error` text. Select again remains the sole lookup recovery action. No invented success or retry mechanism. | Errors retain useful context. |
| Tap miss | No sheet; **No text here** then instruction per §4; no dismissal, no fabricated hole. | A failed target is recoverable. |
| Empty full-screen scan | **No text found**, then circle instruction per §4. Index unavailability instead says **Circle Japanese text**; do not relabel unavailable as confirmed zero. Tap can still be attempted; a miss remains non-destructive. | Honest zero and unknown are not the same state. |
| Empty gloss array | Keep actual headword/reading/POS; Meaning section says **No meaning supplied** in `bodyMedium`, `onSurfaceVariant`. Never manufacture a translation. | Missing dictionary content is explicit. |
| Long gloss / many matches | One vertical body scroller; all primary glosses wrap; selectors scroll horizontally; fixed footer never overlays body text. Last row can scroll **16 dp** clear of footer. No fading text or clipped bottom line. | Content before containers includes complete readable prose. |
| 130% and 160% text | Uncapped sp scaling, flexible heights; trigger responsive header at width/fontScale <300; stack deck value above 1.2; wrap readings, full POS, errors and metadata. Minimum 48/52 dp targets are floors, not ceilings. No ellipsis on entry prose or primary labels. | Native text scale is not a density preference to override. |
| Landscape, either dock | Keep 45% width and the same type roles. Body gets all remaining height after the 48 dp controls and footer; no nested vertical scroll. Word tabs, source and reference header are body content when measure is constrained. Preserve dock-specific safe areas and 80 dp dismissal. | A dock changes geometry, not the meaning or scale of the text. |

For a very short window where **header + footer + 48 dp readable body** cannot fit in the available safe height, retain the grip and a **48 dp Close/Select again/Copy toolbar**, but put the entire dictionary body **and footer in one vertical scroller**, with the action at its true end. This is the only condition that unpins the footer. Do not overflow a fixed footer into the source or shrink text; the normal 60%/45% shell limits remain. Give the scroller at least the remaining available space and keep Close accessible. Empty-result shells can size to their notice; successful-result shells may grow to the cap without animating through a clipped intermediate height.

**Call:** sacrifice permanently pinned selectors and, only in physically insufficient height, a pinned export footer before sacrificing readable system text. The action remains explicit and reachable.

**Invariant ledger:** preserve freeze-then-pause ordering and opaque accessibility window; remap on every layout change; single-flight trigger/admission; **80 dp** toward-dock drag-dismiss threshold (strictly past it); WaniKani back-first order; focus-loss dismissal with the current **200 ms** bounce handling; both lasso and tap; explicit-only Anki writes. Dismissal by a true focus loss keeps existing media-resume policy. UI motion must not postpone teardown after lock/interruption or resurrect a stale session.

## 7. Floating bubble

- Keep a **48 dp diameter** circular visual/target, **24 dp** existing `ic_qs_lookup` glyph centered. Use **opaque `primary` fill (alpha 1.00)**, paired **`onPrimary` glyph (alpha 1.00)**, and a hard **2 dp `onPrimary` perimeter stroke drawn inside the 48 dp circle**. No transparent center, shadow, gradient, bloom or secondary ring. A `GradientDrawable` may remain only as the Android solid-oval drawable implementation; it must not render a gradient.
- The accent-filled action is retained, not replaced with a glyph-only or white bubble: arbitrary foreign pixels cannot supply an icon's background. The onPrimary rim keeps the deep light-theme disk distinguishable on dark content and the pale dark-theme disk distinguishable on bright content. Where the perimeter shares a local pixel value, the opposite-color disk/glyph still names the action. Black/White follows the same recipe; do not sample content or invent adaptive colors.
- Resolve the plain View's colors directly from `ThemePreferences.themeMode(...).isDark(systemDark)` and `accentRoles(...)`. This window never reads Compose's `MaterialTheme`. Re-show resolves fresh preferences; do not leave a stale default Indigo asset.
- Preserve **8 dp** initial right-edge margin and **45%** initial screen-height position; restore the real saved position. Preserve **8 dp movement slop**, finger-following **0 ms** drag, no magnetic snap or extra edge docking. Clamp the entire target to current display bounds on layout change, and persist final position. Normal press changes content opacity to **0.72** using `SHORT`/`pressEasing`; crossing drag slop restores alpha **1.00**. No scale/size inflation, long-press menu or ambient bobbing. Accessibility label: **Screen lookup**; hint: **Tap to look up; drag to move**. Preserve tap activation.
- While lookup is being admitted/captured/open, the bubble is **absent**, not merely disabled or sitting above the sheet. Keep the existing clear-to-transparent → committed frame → additional vsync → capture sequencing and its 150 ms safety timeout. This is an immediate capture-safety hide, not a decorative fade. On a normal permitted return to foreign content, show it at its saved position with a **SHORT 150 ms `emphasized` opacity entrance**; disabling it can fade out over the same token. It must not reappear on a locked screen, during admission, or while lookup remains open. External re-triggers still obey the existing single-flight gate.

**Call:** the bubble stays a filled circle because it is an isolated primary action, not a content container. The new opaque fill and paired keyline trade a little occlusion for dependable identification.

**Rationale — direct actions / neutral foundation:** keep the one intentional accent control identifiable over another app without importing elevation.

**Design-language amendment:** approve a 2 dp paired-color perimeter on an isolated foreign-content action. No new token or parallel palette.

## 8. In-app lookup surfaces

### Screen lookup settings

Keep the existing RN detail-page grammar: `background`, **64 dp** app bar below real top inset, **17/22 sp house section-title role** (RN's existing app-bar treatment), 48 dp Back and Help actions, centered **560 dp** column with **22 dp** horizontal padding (**16 dp below 360 dp**). Body scrolls; bottom padding = native inset +16 dp. No tabs, row cards, icon holders or tinted section backgrounds. Add the existing Help affordance to this page, using the copy below.

Groups remain **Lookup**, then **During lookup**, with **24 dp** between groups. Heading `labelMedium` 12/18, `onSurfaceVariant`, **8 dp** to rows. Every row is minimum **60 dp**, **12 dp** vertical padding, **12 dp** content gaps and **1 dp `outlineVariant` bottom rule**; bare **22 dp** icons, no tiles. Titles use the existing RN house row title **15/20 sp bold**; summaries **13/19 sp `onSurfaceVariant`**; trailing values **12/18 sp**, capped at 40% width and wrapping. At width/fontScale <270, move trailing actions/values below the title block; do not clip the title to make room. Buttons remain minimum **52 dp**, **13 dp** radius; text/icon targets 48 dp.

| Row | Decision | Ink rationale |
| --- | --- | --- |
| Lookup mode | Keep real **On / Off** value and existing service-status refresh on arrival/foreground. On: neutral check glyph, no success-green trophy. Off: one filled **Turn on** action opens disclosure, never silently enables access. On does not display a fake app-owned toggle for Android's service switch. | Honest state matches the native permission owner. |
| Floating button | Existing actual preference switch, on track `primary / onPrimary`, title and one summary **Tap to look up** or **Needs lookup mode**. If enabled while lookup mode is off, preserve the preference and show the same disclosure; cancel must not pretend the service is on. | One control, one consequence. |
| Quick Settings tile | Added: **Added** value, no duplicate Add action; move removal instruction to Help. Not added on API 33+: one outlined **Add tile** action; on older Android show **Add in Quick Settings** as the single summary. Preserve genuine system callback outcomes; never infer that the user accepted. | Concise copy does not manufacture setup state. |
| Key Mapper | Title **Key Mapper**, existing **Set up / Hide** text action. Expanded content: **Broadcast action**, complete selectable `com.gaten.app.action.TRIGGER_LOOKUP` in existing monospace body **13/19 sp**, wrapping, and 48 dp Copy action. No tinted code card. | Technical detail appears only where needed and stays exact. |
| Pause media | Title **Pause media**; value **Allowed / Not allowed**, reflecting actual notification-listener access. Allowed summary **Pauses media during lookup**; unallowed **Some players may keep playing** and outlined **Turn on**. This is an access-grant action, not a new on/off preference switch. Permission purpose and fallback/resume behavior stay in Help. | Do not disguise access state as a feature preference. |

### Accessibility disclosure

Keep the explicit consent checkpoint before Android Accessibility settings. **Do not move permission consequences behind Help or an initially collapsed disclosure.** Use the shared dialog: opaque `surface`, **20 dp** radius, **24 dp** padding, maximum **360 dp** width, **50% black backdrop**, elevation 0 dp. Title **Turn on lookup mode**, `titleLarge`; scrollable body `bodyMedium` 14/20, `onSurfaceVariant`. Four unboxed headed blocks, **16 dp** between blocks and **8 dp** between title and body, use exact copy in §9. Headings `labelMedium` 12/18 `onSurface`. No icons, badge grid or illustrations.

Pinned footer: **16 dp** above a minimum **48 dp** checkbox row; **18 dp** checkbox visual, **2 dp** neutral border, **2 dp** radius, selected `primary` fill / `onPrimary` check; **8 dp** text gap. Acknowledgement wraps at **13/19 sp**. Actions **Cancel** and **Open settings**, ≥48 dp targets, `labelLarge` `primary`; Open settings disabled until acknowledged (onSurface alpha 0.38). At 160% use vertical full-width actions if the row would overflow. Keep acknowledgement and actions reachable outside the scrolling explanation; make no claim that a checkbox proves somebody read the text. Android still owns the actual enable switch.

**Rationale — one clear hierarchy / honest state:** headed facts replace one wall of prose without hiding capture, data or permission consequences.

### Quick Settings tile

Use the existing single-color `ic_qs_lookup` glyph, the same silhouette as the bubble. Change the visible tile label from **Gaten** to **Screen lookup**, with app identity retained by Android's attribution. Label and icon are system-tinted and system-sized; do not force the chosen app accent, radius, shadow or custom dp layout into Android's tile. State remains ACTIVE when the service is enabled, INACTIVE otherwise; it describes lookup availability, not a latched screenshot toggle. If supported, subtitle **Ready** / **Setup needed** mirrors that availability. Enabled tap starts the existing shade-collapse/capture pipeline; service-not-ready or collapse failure has its truthful message. Disabled tap opens Gaten's **same disclosure** before any native accessibility prompt, rather than bypassing consent through a direct settings jump. No new capture path, permission shortcut or fake always-ready state.

**Rationale — native behavior / direct actions:** the tile names its action and leaves system chrome to the system.

## 9. Copy inventory

Use sentence case, one clause per UI line wherever possible. Multiple factual lines are deliberate in permission/help/error content, not a license for promotional paragraphs. Source OCR, dictionary glosses, forms, POS, actual deck names, WaniKani mnemonics and subject names are data: preserve them verbatim; do not apply the one-clause rule to truncate or rewrite them. Numbers and placeholders below are real values, never samples.

### Overlay, controls and dictionary

| Key/context | Exact visible/accessibility text |
| --- | --- |
| `lookup_hint` | Circle or tap Japanese text |
| Index still running | Reading screen… |
| Committed crop running | Reading selection… |
| Index unavailable / empty-index recovery hint | Circle Japanese text |
| Tap miss | No text here |
| `lookup_no_text` | No text found |
| `lookup_no_words` | No dictionary matches |
| `lookup_dict_error` title | Dictionary unavailable |
| Dictionary failure supporting line | {actual readable cause}; if no usable cause, omit it rather than expose a class name or invent one |
| `lookup_select_again` | Select again |
| `lookup_other_matches` | Other matches ({count}) |
| `lookup_base_form` | From {recognized surface} |
| `lookup_common` | Common |
| `lookup_also_written` | Also written: {forms} |
| Meaning caption / full POS caption | Meaning / Part of speech |
| Missing gloss | No meaning supplied |
| `lookup_toast_copied` | Copied |
| Close / copy / select descriptions | Close lookup / Copy text / Select again |
| Paging descriptions | Show earlier words / Show more words |
| Tab announcement | {word}, selected (or not selected); use platform selected semantics, not duplicated visible suffixes |
| Grip accessibility action | Dismiss lookup |
| Bubble label / hint | Screen lookup / Tap to look up; drag to move |

Keep the actual diagnostic cause from **Dictionary unavailable: {cause}**, the recognized surface in **From {surface}**, alternative forms and the **Other matches** count. They carry real information; removing their card/badge treatment does not remove them.

### Anki footer and errors

| State | Exact copy |
| --- | --- |
| Destination | Anki deck / {configured deck name} |
| Direct action | Add to Anki |
| Permissionless share action | Send to AnkiDroid |
| Duplicate check / write pending | Checking Anki… / Adding… |
| Completion / duplicate | Added / Already in Anki |
| Not installed | Install AnkiDroid to add cards |
| Permission write failure | AnkiDroid denied access |
| Deck / model creation failure | Could not create the deck / Could not create the note type |
| Add failure | Could not add the word |
| Share failure | Could not open AnkiDroid |
| Invalid saved configuration | Check the note type in Anki cards |
| Empty first field | The first Anki field is empty |
| Empty-first-field support | Check field mappings in Anki cards |

Do not compress configuration/permission failures into “Something went wrong.” Their corrective information remains in the persistent footer message and in Anki cards settings. Duplicate detection still follows the configured first field/note type; a simpler label must not imply deck-only duplicate matching. Gaten's read-only preparation must not create decks or note types; creation belongs to an explicit write.

### WaniKani within lookup

| Context | Exact copy |
| --- | --- |
| Strip / character action | WaniKani kanji / WaniKani details for {character} |
| Radical action | WaniKani details for {radical name} |
| Header controls | Back / Refresh WaniKani page / Open on WaniKani |
| Section labels | Radical / Meaning / On’yomi / Kun’yomi / Radicals / Meaning mnemonic / Reading mnemonic |
| Loading semantics | Loading WaniKani… |
| Cached | Saved for offline use |
| Failed to cache | Not saved for offline use |
| Stale | Showing saved copy |
| Stale supporting line | Could not refresh WaniKani |
| No kanji entry | No WaniKani entry for {character} |
| No radical entry | No WaniKani entry for this radical |
| Restricted kanji | {character} is outside your subscription |
| Restricted radical | This radical is outside your subscription |
| Missing token | Connect WaniKani in Gaten |
| Rejected token | Reconnect WaniKani in Gaten |
| Unreachable | Could not load WaniKani |
| Existing actions | Try again / Open Gaten |
| Offline cause | No connection |
| Timeout cause | Request timed out |
| Rate limit cause | WaniKani is limiting requests |
| Server cause | WaniKani reported an error |
| Oversized cause | WaniKani's answer is too large |
| Malformed cause | Could not read WaniKani's answer |
| Storage cause | Could not save WaniKani's answer |

Failures show the applicable cause below the title; stale and not-saved are separate facts and may both appear. Preserve the actual subscription, cache and token distinctions from current strings. Attribute the mnemonic to WaniKani through the reference-page title/labels; never export it as dictionary meaning.

### Settings, tile and Help

Visible page strings: **Screen lookup**, **Lookup**, **During lookup**, **Lookup mode**, **On**, **Off**, **Floating button**, **Tap to look up**, **Needs lookup mode**, **Quick Settings tile**, **Added**, **Add tile**, **Add in Quick Settings**, **Key Mapper**, **Set up**, **Hide**, **Broadcast action**, **Pause media**, **Allowed**, **Not allowed**, **Pauses media during lookup**, **Some players may keep playing**, **Turn on**. Exact broadcast value: **com.gaten.app.action.TRIGGER_LOOKUP**. Accessibility controls: **Back**, **Open help**, **Copy the trigger action**, **Key Mapper setup**; its expanded-state hints remain **Shows the broadcast action / Hides the broadcast action**. Shared dialog strings: **Help**, **Close**, **Cancel**. Do not introduce a new translation for these shared concepts.

Tile: **Screen lookup**, with **Ready / Setup needed** when subtitles are supported. Short failure messages: **Turn on lookup mode in Gaten**; **Lookup service is not ready** with separate **Try again** guidance; **Could not close Quick Settings** with **Use the floating button or a key** guidance; **Could not add the tile** with **Use the Quick Settings editor** guidance; **Could not open accessibility settings**; **Could not open notification access settings**. Native toasts may combine a failure and its guidance as two short lines rather than one long sentence.

Help dialog, in this order, with `bodyMedium` paragraphs separated by **12 dp**:

1. **Circle text or tap a Japanese line.** **A missed tap keeps lookup open.**
2. **Drag the floating button to move it.** **The button appears while lookup mode is on.**
3. **Add or remove Screen lookup in the Quick Settings editor.** **Use Add tile on supported Android versions.**
4. **Map a key to a broadcast action in Key Mapper.** Show the exact action, with Copy, only in the page's expanded row.
5. **Notification access controls media sessions.** **Without it, Gaten sends a media key.** **Some players ignore that key.** **Gaten resumes media after a normal lookup dismissal.** **Media stays paused after an interruption.**
6. **Japanese recognition and dictionary lookup run on this device.** **The stylized-text model is optional.**
7. **WaniKani is an optional online reference.** **Its content is not included in Anki exports.**
8. **With AnkiDroid access, Add to Anki writes a card.** **Without access, Send to AnkiDroid opens its add screen.** **Card layout and deck settings live in Anki cards.**

The existing media-key fallback, notification-access purpose, normal-versus-interrupted resume behavior, tile editor ownership and exact hardware broadcast action are retained information, not cut copy. No enabled state may promise guaranteed pause for every player.

### Disclosure: exact block copy

Title: **Turn on lookup mode**.

**Screen access**
- Gaten captures the screen when you start a lookup.
- Gaten closes lookup when it is interrupted.

**On this device**
- Screenshots and selected text stay on this device.
- Japanese recognition and dictionary lookup work offline.
- The screenshot is discarded after lookup.
- Nothing is captured between lookups.
- The service does not read notification messages.
- The service does not type for you.

**Optional connections**
- The stylized-text model downloads only when requested.
- WaniKani connects only when you add a token.
- The token is checked with wanikani.com.
- A tapped kanji requests its reference page.
- WaniKani study progress is saved on this device for Analytics.
- Progress includes your level, due reviews and review stages.
- Due reviews cover now and the next 24 hours.
- Gaten never answers or changes WaniKani lessons or reviews.
- Your screenshot, sentence and other words are not sent to WaniKani.

**Next step**
- Enable Gaten in Android accessibility settings.
- Return here to check lookup mode.

Checkbox: **I understand how this permission is used**. Actions: **Cancel / Open settings**.

**Call:** keep every permission/data consequence visible in these grouped, scrollable facts. Move only operational detail such as tile removal and media fallback into Help; do not move privacy disclosure there. Shorter lines are hierarchy, not reduced disclosure.

**Rationale — concise voice / honest state:** each line carries one job, while genuine technical, privacy and corrective information survives.

## 10. Interaction proposals — five decisions only

These are the complete interaction changes; do not add magnifiers, editing, auto-retry, automatic exports, new selection modes or extra gestures.

| Proposal | User problem | New behavior | Risk and mitigation | Invariant touched |
| --- | --- | --- | --- | --- |
| 1. Status explains both inputs | “Circle” alone hides tap lookup; every failure looks like the same hint. | Explicit circle-or-tap readiness, separate tap-miss text and persistent inline terminal results; lifecycle exactly §4. | Rail can cover source: timed hints, immediate hide on drag and opaque bounded placement. | Both lasso and tap remain available; misses do not dismiss. |
| 2. Reading-first responsive shell | Pinned chips, source card and oversized text starve the entry at 160%/landscape. | Unfilled tabs in the single body scroller; responsive OCR reflow; last-resort unpinned footer only at the measured minimum-height failure in §6. | Scrolling must not dismiss or activate export: body scroll owns its gesture, drag remains on existing non-scrolling chrome/grip, canceled press never commits. | Preserve 80 dp toward-dock dismissal, remap and explicit-only Anki writes. |
| 3. Truthful Anki commit states | One label hides a share handoff; disabled Add after success reads as unfinished. | Name direct-write versus send action; retain completion label; persistent contextual failure and explicit-only resubmission. | New label must not create a permission path or implicit export. Keep the existing guarded write/share routes. | Never write by opening, selection, motion, refinement or restoration. |
| 4. Grouped disclosure | A single long paragraph obscures capture and network consequences. | Four visible headed blocks, pinned acknowledgement, explicit Open settings. | Short copy could omit consequences: use every §9 disclosure line; no collapsed privacy section. | Consent remains before native prompts; no change to capture/admission. |
| 5. Tile setup uses the same consent gate | Disabled tile currently jumps straight to Android settings. | Route disabled tile tap to Gaten's existing lookup disclosure; enabled tile keeps the existing shade-collapse trigger. | Activity launch could accidentally trigger capture: setup route never captures, and returning only refreshes permission state. | Single-flight admission, freeze-before-pause and explicit disclosure remain intact. |

**Unchanged interaction contract:** Select again cancels current foreground work and unwinds WaniKani state before restoring selection; the old sheet retains content/dock until exit completes. System Back unwinds WaniKani first, otherwise dismisses. Tapping outside a result dismisses; tapping outside text during selecting is a miss, not dismissal. A focus-loss dismissal uses existing policy and cannot be deferred by design motion. No new sounds/haptics; respect existing feedback preferences.

**Rationale — direct actions / native behavior:** only change interactions that clarify intent or preserve reading access; do not redesign the capture engine.

## 11. Motion

Use `Motion` tokens, not the old 160/220/250/260/900/2400 ms overlay timings. `emphasized` = cubic-bezier **(0.2, 0, 0, 1)**; `slideEasing` aliases it. `pressEasing` = **(0.4, 0, 0.2, 1)**. SHORT = **150 ms**, MEDIUM = **300 ms**, LONG = **450 ms**, PAGE = **380 ms**. `overshoot` = spring damping **0.8**, stiffness **380**. LONG is not needed for this compact workflow. No new motion token.

| Transition | Duration / easing | Exact behavior |
| --- | --- | --- |
| Capture, bubble safety hide, frame commit, admission, media pause | **0 ms visual interpolation**; retain controller's real frame/vsync scheduling and safety timeouts | Never crossfade the capture or delay safety order for animation. |
| Freeze dim and first rail arrival after UI admission | **SHORT 150 ms / emphasized** | Scrim alpha 0→0.40; rail opacity 0→1; capture stays opaque and stationary. |
| Rail notice enter/leave and text replacement | **SHORT / emphasized** | Opacity only; no rail slide, size tween or overlap of two legible messages. |
| User starts drawing / live trail / cancel | **0 ms**, input-driven | Hide hint and update path immediately. |
| Commit box / scanning edge / result edge weight | **0 ms**, state-driven | Freeze bounds, replace freehand trail; change 4/2 dp edge to 3/1 dp on result. No alpha pulse. |
| Result sheet entrance | **MEDIUM 300 ms / emphasized** | Translate from its own bottom/left/right dock by its measured size; frozen backdrop stays fixed. No extra sheet fade. |
| Select again, sheet exit | **SHORT 150 ms / emphasized** | Translate toward its dock; retain content until exit finishes; instruction fades in with SHORT. No ring returns. |
| Full overlay dismissal with result | **SHORT 150 ms / emphasized** | Sheet exits toward dock concurrently with dim/UI fade to zero; remove window after real transition completion. Preserve capture until teardown. |
| Full overlay dismissal without result | **SHORT / emphasized** | Dim and rail opacity to zero; no scene zoom. |
| Forced teardown / lock or disallowed admission | **0 ms** | Safety teardown wins; never wait for a decorative exit. Existing focus-bounce policy remains. |
| Word-tab selection | **SHORT / emphasized** | Text/rule/check opacity/color only; reserved check width; no scale overshoot. Entry crossfade in same duration, existing primary/final session state retained. |
| Paging arrows appear/disappear | **SHORT / emphasized** | Opacity of opaque-masked control only; no width change. |
| Arrow paging | **MEDIUM / emphasized** | Scroll 85% of viewport; next paging input cancels previous paging job as today. |
| Finger scroll/fling | Native scroll behavior | Do not impose a tween on drag/fling; platform deceleration remains. |
| Other matches expand/collapse; source reflow; result height change | **0 ms layout change** | No animateContentSize clipping at large type; existing body scroll position remains unless user changes word. Chevron changes with state, no spin. |
| WaniKani push/pop, including radical stack | **PAGE 380 ms / slideEasing** | Existing pageSlide directions and **0.25** outgoing/incoming parallax, clipped to body viewport; no fade or elevation. |
| WaniKani loading→data/notice | **SHORT / emphasized** opacity | Static skeleton stops when data arrives; no shimmer. Refresh failure leaves cached content in place. |
| Add/Checking/Adding/Added/Duplicate/error label change | **SHORT / emphasized** opacity | Stable action slot; no pop, bounce, count-up or celebratory scale. Error area relayout is immediate. |
| Functional loading spinner | Existing native indeterminate indicator timing | Only foreground work; 16 dp / 2 dp stroke. Do not invent a 2400 ms drift or new custom loop. Under reduced motion, use a static progress glyph plus named busy text. |
| Press feedback, Kotlin and RN affected controls | **SHORT 150 ms / pressEasing**, opacity 1→**0.72**→1 | No row/button scale or elevation. This overlay recipe deliberately does not call Kotlin's current 0.98 pressScale; retain hit geometry. |
| Sheet drag | **0 ms**, finger-driven | Bottom translation follows downward drag, never lifts off dock; preserve existing directional side drag handling. |
| Subthreshold drag return | Existing **overshoot** spring, damping 0.8 / stiffness 380; no fixed duration | Return to dock; dismiss only strictly past 80 dp toward that dock. No independent handle stretch. |
| Bubble normal reappearance / preference disable | **SHORT / emphasized** opacity | Not used for pre-capture safety hide; drag position itself is immediate. |
| Key Mapper expansion / setting value refresh | **0 ms** layout/state | Flat native form update, no reveal card. |
| Disclosure / Help presentation and dismissal | **SHORT / emphasized** opacity | Backdrop 0→0.50 and content 0→1; no dialog scale/slide. Native settings and QS shade transitions stay system-owned. |
| Settings page entry/back | Existing shell **PAGE / slideEasing** | No lookup-specific destination transition. |

Respect the system reduced-motion setting: **all custom timed transitions become 0 ms**, press opacity is immediate, drag return snaps, reference page navigation switches without parallax, and spinners become static named busy states. User-driven geometry still follows input. Recommended reading timeouts and frame-commit/vsync, admission, focus-bounce and media-ordering safeguards are **not animation durations** and must not be zeroed by reduced motion.

**Call:** do not use the available overshoot spring for tab checks or Anki success. Only the existing dismiss return needs physical settling; everything else is quiet opacity or a meaningful destination slide.

**Rationale — purposeful motion:** animation clarifies arrival, departure and navigation; it never advertises background computation.

## 12. Mockup brief

Render **exactly eight native stills**, one capture per numbered item below; no contact sheets or paired alternate captures. Use real status/navigation insets. Portrait target is the reference device's **1080×2340 at 2.75 px/dp**; landscape captures use the rotated viewport. Do not add a prototype device frame. Mock fixtures are render-only; no fabricated production counts or permission states.

| Still | State/theme | Required visible evidence |
| --- | --- | --- |
| 1. Setup and bubble | Light, Violet, portrait, 100% text | The flat Screen lookup settings page with its five row types, Help action, actual On/Off/Allowed values and expanded Key Mapper action; enabled 48 dp opaque bubble at its real saved position. No screenshot scrim, tinted row cards or icon holders. Bubble must show its paired 2 dp rim and 24 dp glyph. |
| 2. Armed | Light, Black accent, bright manga source, portrait, 100% | Frozen content under exactly 40% black veil; square status rail with **Circle or tap Japanese text**, neutral opaque backing and leading rule; no screen-edge ring and no bubble. |
| 3. Live gesture | Light, Green accent, busy mixed black/white source, portrait, 100% | Mid-drag after **600 px** travel: 5/3 dp paired stroke, no glow/gradient, 4 dp-corner clear hole and hard 4/2 dp edge. No status over the live path. Width must be identical along the complete path, including its short initial segment. |
| 4. Committed scan | Dark, White accent (stored Black), dark video with bright subtitles, portrait, 100% | **Reading selection…** rail and 16 dp spinner frozen at a representative phase; 4/2 dp committed edge; unaltered bright source inside hole; no result skeleton or bubble. |
| 5. Large-text dictionary | Light, Indigo, portrait, **160%** | 60%-capped flat sheet, reflowed source and three 48 dp header targets, unfilled word tabs, neutral headword/reading/metadata, wrapping numbered meanings and a pinned footer with stacked actual deck and 52 dp-minimum Add to Anki action. Use a real long entry at its initial body position so the beginning of the first gloss is visible and scrolling is clearly necessary. No tinted cards, clipped text or primary-colored headword. |
| 6. Right dock reference | Dark, Teal, landscape, **130%** | Source on left chooses right dock; correct safe inset and inner 48 dp grip gutter; WaniKani radical page with real transparent art, Back/Refresh/external controls at ≥48 dp, plain mnemonic and cached/stale notice. No Anki footer. Choose a source/subject whose content fits enough of these facts at one real scroll position; do not splice positions. |
| 7. Left dock failure | Dark, Rose, landscape, **160%** | Source on right chooses left dock; dictionary failure in the flat shell. Recognized source survives; inline **Dictionary unavailable**, a genuine readable cause and Select again; no export action or card fill. Narrow measure forces OCR reflow, with all three toolbar targets intact. |
| 8. Permission disclosure | Light, Blue, portrait, **160%** | Accessibility disclosure over the Screen lookup page: 20 dp dialog, 50% black backdrop, grouped facts at the scroll position showing **Optional connections**, pinned acknowledgement and disabled Open settings before consent. Other fact blocks remain reachable by real scrolling, not packed into an illegible miniature. |

The eight stills cover all seven accents, light/dark, busy/bright/dark foreign content, both side docks, reading and failure states, and large text. They are a concrete render brief, **not evidence of completed visual, interaction or contrast testing**. The engineer should separately exercise the 60 px path and initial dot, expanded Other matches, no-text/no-words, tap misses, unavailable versus empty index, all text scales, reduced motion, TalkBack, interruption/focus loss, layout remapping, duplicate write protection and true capture-before-pause sequencing; eight stills cannot prove every state or temporal behavior.

**Rationale — honest state / native behavior:** review evidence must show the tool working at its awkward boundaries, not only an attractive short entry at default scale.
