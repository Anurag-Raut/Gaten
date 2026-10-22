# Gaten UI & interaction implementation specification

Status: approved for implementation, 2026-10-16; progress and validation are recorded in
[`IMPLEMENTATION.md`](IMPLEMENTATION.md). This document supersedes
`docs/ui-experience-plan.md` as the buildable version; the proposal remains as background.

Inputs: four read-only code inventories (settings/home, nav/review, overlay, search), an
emulator inspection pass with 30+ screenshots (`docs/ui-redesign-spec/current/`), design
guidance (Anthropic frontend-design, Android mobile-design skill, Impeccable motion notes),
the Dokuen UX review (`docs/dokuen-ux-review/`), and the romaji study
(`research/romaji.md`). Every decision below is fixed before implementation starts.

## Summary (read this first)

| Area | Change | Where |
|---|---|---|
| Motion | One horizontal slide for every in-app page change — open, close, tabs, review stages. All crossfades/fades removed | `GatenMotion.kt`, all screens |
| Visual language | Dokuen-style: rounder shapes (12/20/28), bolder headings, larger Japanese display type, bundled Manrope for Latin text | `Theme.kt`, `Type.kt`, `Color.kt` |
| Home | Real search input card (tap = focused field, keyboard up), shorter copy, tighter review/lookup cards | `HomeDashboard.kt`, `strings_navigation.xml` |
| Search | Autofocus on open; romaji input (tokyo → 東京) via wanakana-kt + loose index | `SearchScreen.kt`, `DictionarySearch.kt`, `build_jmdict_db.py` |
| Review | Stage changes slide like pages; grading haptics; less instruction text | `AnkiReviewScreen.kt` |
| Settings | Denser grouped rows with current-value labels; long explanations move behind Help | `SettingsScreen.kt` + 6 pages |
| Feedback | Haptic map (select vs confirm) + optional sounds (add-to-Anki, session complete), off by default | new `Feedback.kt` |
| Lookup overlay | Same rounded visual language, sheet slides from dock edge (kept), press feedback on actions | `ResultSheet.kt`, `LookupOverlay.kt` |
| WaniKani token | Storage already AES-GCM/Keystore/noBackup — keep as-is; add verification only | `WaniKaniCredentials.kt` |
| Copy | Cut ~40% of visible explanatory strings across Home/Search/Settings | `strings_*.xml` |
| Bugs found on emulator | Floating-bubble tap is dead; tap-miss closes the overlay; actions sit in the gesture bar; Anki Save enabled when invalid; a11y labels missing | §3.4, §3.5, §3.7 |

Out of scope: new features not requested above. Offline behavior, OCR pipeline, AnkiDroid
write path, media-pause semantics all stay untouched.

Evidence base: 44 emulator screenshots + measured findings in
`docs/ui-redesign-spec/current/` (`emulator-findings.md`, 07–44; captures 01–06 from the
earlier pass). Timing claims there are logcat-measured; motion timings in this plan come
from source, not screenshots.

---

## 1. Design language (fixed decisions)

### 1.1 Shape
`Theme.kt` `GatenShapes`: `small = 12.dp`, `medium = 20.dp`, `large = 28.dp`
(today 8/12/20). Add one shared constant `SheetShape = 28.dp` for the lookup result sheet,
home cards use `large`, settings groups use `medium`, chips/buttons use `small`.

### 1.2 Color
Keep the existing `AccentColor` system (7 accents, light/dark schemes already generated
from `tones()`). Changes only:
- Darken/raise saturation of the default Indigo light primary to match Dokuen's weight:
  `IndigoLight` primary `0xFF3F51B5` → `0xFF3949AB` (still AA on white for large text).
- `LightBackground` `0xFFFAFAF9` stays; cards keep white surfaces so the bold accents land
  on neutral ground. No new palette roles.

### 1.3 Typography
- Bundle **Manrope variable** (OFL, ~300 KB, `app/src/main/res/font/manrope_variable.ttf`)
  as `FontFamily(Font(R.font.manrope_variable))` for Latin text. Japanese glyphs keep
  `FontFamily.Default` — bundling a CJK font is megabytes and unnecessary.
- `Type.kt`: headings jump to `FontWeight.SemiBold` (Manrope renders SemiBold cleanly;
  the current "no Bold because CJK smears" rule is kept by applying SemiBold only where
  text is Latin or a large Japanese display word rendered in the system face at Medium).
- New steps: `displayMedium = 40.sp/48.sp` (review card front word), `displaySmall` stays
  34.sp. `titleMedium` 18→19.sp for scannability.
- No font-size cap anywhere (already the rule; keep).

### 1.4 Elevation & depth
Cards: `1.dp` tonal shadow, no borders, background `surface` on `background` ground.
Pressed state: scale to `0.98` with `Motion.SHORT` (see 1.5) instead of elevation bumps.

### 1.5 Motion tokens (this is the consistency fix)
`Motion.kt` gains:

```kotlin
const val PAGE = 380          // one page slide, replaces all LONG/MEDIUM page transitions
val slideEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)   // = emphasized, keep
val pressEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
val overshoot = SpringSpec(dampingRatio = 0.8f, stiffness = 380f) // selection/confirm pops
```

**The one animation rule:** every destination change slides right-to-left on push and
left-to-right on pop, `PAGE` ms, `slideEasing`, with a 0.9→1.0 parallax on the outgoing
page (page moves −25% of its width, not off-screen — cheap, and reads as depth). Applies to:
- tab switches (direction = target tab index vs current index),
- push/pop of Search → word detail, Settings → any page, Home → Review,
- review **stage changes** (question → reveal → next card slides like a page turn),
- returning from review (slide back out).

Removed: `GatenRootScene`/tab fades, review `AnimatedContent` crossfades, WaniKani inline
detail fade+expand (it becomes a push), result-sheet `Crossfade` between entries (kept —
that is content switching inside one surface, not a page change; it uses 150 ms fade, this
exception is deliberate and documented).

Unchanged (different surface class, same language): lookup result sheet keeps its
dock-direction slide (bottom/left/right) because a sheet is not a page; overlay scrim fade
stays.

Predictive back: the pop direction matches the system gesture everywhere because
`NavDisplay`'s `transitionSpec` handles it in one place.

Current state to replace, verified in `GatenMotion.kt`: `crossesTab()` fades tabs at
`Motion.SHORT` (150 ms) and slides same-tab steps at `Motion.MEDIUM` (300 ms) with an
added fade on both sides. W2 deletes the `crossesTab()` branch and both fade pairings so
every case takes the same slide with parallax; `SlideDirection.Start/End` stays for RTL.

### 1.6 Press/selection micro-animations
- All tappable cards/rows: `scale 1.0→0.98` on press, `Motion.SHORT`, `pressEasing`.
- Chips: selection animates background + a leading check icon with `overshoot`.
- Add-to-Anki button: on success morphs label → "Added ✓" for 1.6 s then reverts (state,
  not navigation).
- Review grade buttons: pressed scale; correct/incorrect answer flash on the word
  (primary/tertiary tint sweep, 250 ms).

## 2. Feedback map (haptics + sound)

New `ui/Feedback.kt`: two functions — `haptic(kind)` and `sound(kind)` — backed by
`HapticFeedbackType` and a lazily-built `SoundPool` with two tiny bundled OGGs
(`< 15 KB` total, generated tones, `res/raw/feedback_added.ogg`, `feedback_done.ogg`).

| Action | Haptic | Sound (default off) |
|---|---|---|
| Tab switch | tick (`CONTEXT_CLICK`) | — |
| Row/button press | none (visual scale only) | — |
| Toggle/selection change | tick | — |
| Lookup → dictionary hit | light (`CONTEXT_TICK`) | — |
| Add to Anki success | confirm (`LONG_PRESS`) | `feedback_added` |
| Review grade submitted | tick | — |
| Review session complete | confirm | `feedback_done` |
| Destructive/confirm dialog | none | — |

Rules: everything goes through `LocalHapticFeedback` / `performHapticFeedback` (respects
the phone's haptic settings); sound gated by one new preference
`FeedbackPreferences.soundsEnabled` (default false) with a row in Appearance → Feedback.
No vibration on dialogs, no sound on ordinary taps.

## 3. Per-screen specification

### 3.1 Home (`HomeDashboard.kt`, `strings_navigation.xml`, `strings_home.xml`)
Current state verified on emulator: search card is huge, repeats three explanations, opens
Search without focusing.

Changes:
1. **Search card becomes an actual field**: a `TextField`-styled card (leading search icon,
   hint "猫, ねこ, neko…"). Tap navigates to Search with `focus=true`; Search autofocuses.
   Card copy: one line — remove `dash_search_hero_body` explanation entirely.
2. Review card: deck name + counts stay; "AnkiDroid missing/permission" states collapse to
   one line + primary button (detail lives in Settings → Anki). Remove the second
   explanatory sentence from each error state.
3. Lookup card: one status row (on/off) + floating-button switch; remove the setup
   explanation sentence; keep "Set up" link.
4. Header: title stays; subtitle shortened to "Your Japanese reading toolkit" (one line).
5. Spacing: card gap 16→12.dp; search card height −40%.

### 3.2 Search (`SearchScreen.kt`, `SearchViewModel.kt`, `SearchWordDetail.kt`, `DictionarySearch.kt`)
1. **Autofocus**: route key `focus`; `LaunchedEffect(focus) { field.requestFocus() }`
   (with `FocusRequester`), plus `WindowCompat` IME already open. Returning from a word
   detail passes `focus=false` — results preserved, keyboard stays closed.
2. **Romaji** (per `research/romaji.md`):
   - `gradle/libs.versions.toml`: add `wanakana-core:1.1.1` (MIT; note in About/licenses).
   - `DictionarySearch.kt`: if query is ASCII romaji → `Wanakana.toHiragana` first; run
     existing kana search on it; then run a second pass on `kana_loose` with the loose
     form; merge (exact wins, deduped, loose results only appended when exact hits < N).
   - `tools/build_jmdict_db.py`: emit `kana_loose` column (per-reading loose form,
     space-separated like `kana`). Rebuild the shipped DB. Tests:
     `tokyo→東京`, `ramen→ラーメン`, `ohayo`, `gakko`, `arigato`, `kirei`, plus ordering
     (exact before loose).
   - Search field hint text becomes "猫, ねこ, neko…" (shows romaji is accepted).
3. Word detail: headword `displaySmall` (34 sp), reading on one line, POS badges as
   outline chips, glosses numbered with hanging indent, "Add to Anki" as a full-width
   primary button pinned at bottom **above the gesture band** (emulator: the current
   footer links sit at y 2212–2267, ~75 px above the bar). Remove the explanatory
   paragraph under the button. Footer links adopt the shared `KanjiStrip`.

### 3.3 Review (`AnkiReviewScreen.kt`, `AnkiReviewViewModel.kt`)
Measured on emulator: the question card occupies 192 px of a 2340 px screen (~70% of the
screen below it is empty), and the rating row sits at `y 2099–2186`, hard against the
gesture band. Fixes:
1. Question card: `displayMedium` (40 sp) front word, card vertically centered in the
   content area instead of top-anchored.
2. Grade row: raise it off the gesture band (`navigationBarsPadding` + 16 dp); grade
   labels become single words (`Again · Hard · Good · Easy` with intervals as small
   captions).
3. Stage transitions (`question → revealed → next`): replace `AnimatedContent` crossfade
   with the 1.5 slide (forward on reveal/next — next card always pushes). Uses
   `Motion.PAGE`.
4. Session entry/exit already handled by 1.5 (Home→Review pushes; back pops).
5. Copy: the long leave-session dialog body stays (it is doing real work — verified the
   counts are unchanged after Leave), but the first-card instruction paragraph is removed.
6. Reveal: definition card slides up 24 dp + fade 150 ms (content reveal inside a page —
   the slide rule is for page changes).
7. Haptics per §2.
8. The kanji-tile expand/collapse (verified working inline in review) moves to the shared
   `KanjiStrip` component (§3.7) so all three framings become one.

### 3.4 Settings (`SettingsScreen.kt` hub + `Lookup/Recognition/Anki/WaniKani/Appearance/AboutSettingsScreen.kt`)
Emulator findings folded in: detail pages read as one flat column of body copy (fields are
bare outlined fields on the page background, unlike the hub's grouped cards), several
full-width actions sit inside the gesture band (Anki `Restore default layout` y 2049–2104,
WaniKani `Save and connect` y 1708–1818, word-detail footer links y 2212–2267, Screen-lookup
media `Turn on` y 2185–2240), Anki `Save` is enabled even when the draft is clean or the
mapping invalid, and the accent picker wraps 6+1 with unlabelled circles.
1. Hub rows: keep `ListItem` groups; every row gains a **current-value label**
   (right-aligned `labelMedium`, e.g. Anki row → deck name, Recognition → model name,
   Appearance → "Light · Violet" — already working, keep).
2. Detail pages adopt the hub's group-card structure: every field/section lives in a
   rounded group card; long `bodySmall` paragraphs move out of the flow.
3. Insets: every detail page ends with `navigationBarsPadding()` content so no primary
   action sits in the gesture band (measured collisions listed above).
4. Copy cuts: `strings_settings.xml` (119 strings) — every row's supporting text is one
   clause; multi-sentence explanations move into a trailing `?` Help dialog per page
   (dialog reuses the existing text, zero information loss).
5. Anki page: template/mapping sections collapse under one "Advanced" item (collapsed by
   default); field-mapping help becomes the Help dialog. Draft/discard invariants stay
   exactly as inventoried (`rememberSaveable` saver, dirty guard, current-page-only
   BackHandler — discard flow verified working on emulator, nothing saved on Discard).
6. Anki `Save` reflects state: enabled only when `dirty` and validation passes.
7. Recognition page: status card shrinks to one row; download confirm keeps metered copy.
   `busy`-serialization and refresh-on-resume behavior unchanged.
8. Appearance page: accent circles relabelled (7 in a 4+3 grid with names or content-desc,
   ring + filled selected state); adds the Feedback sounds toggle; previews keep live
   theme preview. Palette tiles are gone — the shipped neutral ladder is the only one, so
   only the accent stays selectable.
9. Accessibility disclosure dialog: same required content, restructured — short lead
   sentence, checkbox, and the full explanation behind "Read the details" expansion, so
   it stops being a 20-line wall before the checkbox.
10. WaniKani page: visual only (rows + value labels). Token behavior untouched.
11. A11y: the floating-button switch gets a `content-desc`/role (currently an unlabeled
    `checkable` View).

### 3.5 Lookup overlay (`LookupOverlay.kt`, `ResultSheet.kt`, `LassoLayer.kt`, `FloatingTriggerButton.kt`)
Emulator findings folded in — two functional defects found and fixed here:
1. **Floating bubble tap is dead.** Taps at the bubble's measured centre (and a 1.2 s
   long-press) produced no trigger; the touch even fell through to the app underneath.
   The drawn circle also sits ~145 px below the window's reported frame origin. Fix the
   touch handling/positioning in `FloatingTriggerButton.kt`; acceptance: a tap on the
   bubble opens the overlay, verified on the emulator.
2. **Tap-to-select closes the overlay on a miss.** Tapping visible Japanese logged
   `Tap missed: no text box within 66 px` and in two runs the overlay closed immediately.
   A tap miss must keep the overlay open (scrim-pulse or hint instead of dismissal);
   investigate the tap→`ViewMapping` coordinate rule while fixing.
3. Visual pass only: sheet `SheetShape` 28 dp, headword `displaySmall`, chips restyled
   (§1.6), header card one rounded surface instead of nested cards.
4. Press feedback + copy/success haptics per §2 (overlay currently has zero feedback).
5. **Invariants that must not change** (from inventory): screenshot freeze before media
   pause; opaque `TYPE_ACCESSIBILITY_OVERLAY`; `ViewMapping` re-mapping on layout;
   user-dismiss resumes media / external interruption discards; single-flight triggers;
   drag-to-dismiss thresholds; WaniKani local back stack; JMdict single open per overlay.
6. Overlay fade in/out stays; sheet dock slide stays (documented exception in 1.5).

Emulator caveat recorded for the verification pass: the overlay in the shared emulator
session was flaky with another agent active (closed itself within ~2 s twice); lasso
selection and result rendering (44) and the Key Mapper broadcast trigger were verified,
bubble-tap verification must happen in a quiet session. Landscape overlay remains
untested — W6's emulator pass includes it.

### 3.6 WaniKani token security — verification only
Code review confirmed: plain `remember` field, cleared after connect, verify-before-store,
AES-256/GCM Keystore key, `noBackupFilesDir`, atomic rename, never re-displayed, token
only in Authorization header, disconnect clears cache. **No redesign changes.**
Verification tasks: storage-failure path (read-only dir), disconnect partial-failure
reporting, no token in saved instance state after rotation (instrumented or log-inspection
check).

### 3.7 Shared `KanjiStrip` component (new)
The same kanji strip + expandable tile pattern exists in three framings (review question
card, word detail, lookup sheet — verified on emulator). Extract one
`ui/components/KanjiStrip.kt` used by all three: strip of kanji chips, tap expands the
inline missing-token/connected panel, Back or second tap collapses. Review, word detail,
and sheet each pass their token-availability state; the component owns nothing global.

## 4. Copy reduction targets

| File | Now | After |
|---|---|---|
| `strings_home.xml` | 72 | 72 → ~40; 30+ legacy `home_*` strings verified unused (grep audit 2026-10-16) — delete |
| `strings_settings.xml` | 119 | ~95 (supporting text shortened, help dialogs reuse removed lines) |
| `strings_navigation.xml` | 37 | ~34 (home `dash_search_hero_body` + lookup explanation sentences removed) |
| `strings_anki_review.xml` | 41 | ~34 (instruction paragraph, captions) |
| `strings_search.xml` | 31 | +1 hint, −1 paragraph |
| `strings_experience.xml` | 12 | unchanged (verified fully used) |

Every removed explanatory line moves into the page's Help dialog when it carries real
information; pure filler is deleted.

## 5. Workstreams for DeepSeek implementation (after approval)

Sequenced; each ends with `:app:assembleDebug` compiling before the next wave.

| # | Workstream | Files | Depends on |
|---|---|---|---|
| W1 | Theme foundation: shapes, Manrope font, Type/Color edits, `Motion` tokens, press-scale modifier, shared `KanjiStrip` | `Theme.kt`, `Type.kt`, `Color.kt`, `Motion.kt`, new `ui/components/Pressable.kt`, `ui/components/KanjiStrip.kt`, `res/font/` | — |
| W2 | Motion cutover: `GatenMotion.kt` single spec, remove fades, review stage slides, predictive-back check | `GatenMotion.kt`, `GatenApp.kt`, `GatenRootScene.kt`, `AnkiReviewScreen.kt`, `WaniKaniDetail.kt` | W1 |
| W3 | Feedback system: `Feedback.kt`, sounds preference, hook all §2 call sites | new `ui/Feedback.kt`, `AppearanceSettingsScreen.kt`, call sites | W1 |
| W4 | Home + Settings redesign (copy, value labels, group cards on detail pages, gesture-band insets, Anki Save state, accent relabel, disclosure restructure, Help dialogs, a11y labels) | `HomeDashboard.kt`, `SettingsScreen.kt`, 6 pages, strings | W1 |
| W5 | Search: autofocus, romaji + `kana_loose` DB rebuild, detail polish, footer inset | `SearchScreen.kt`, `SearchViewModel.kt`, `DictionarySearch.kt`, `build_jmdict_db.py`, `libs.versions.toml`, `SearchWordDetail.kt` | W1 |
| W6 | Review screen redesign, `KanjiStrip` adoption, overlay visual pass + the two overlay fixes (bubble tap, tap-miss) | `AnkiReviewScreen.kt`, `ResultSheet.kt`, `LookupOverlay.kt`, `FloatingTriggerButton.kt` | W1, W2 |

Integration gates: combined build + full unit suite after W2 and after W6; DB rebuild
checked by a test that `tokyo` matches 東京; final emulator walkthrough by the inspection
agent (same scripted pass, before/after screenshots — including landscape overlay and
bubble-tap checks that could not be verified this session); APK sent via KDE Connect.

## 6. Risks

- `kana_loose` rebuild touches the shipped DB — keep a test pinning the 7 verified matches
  so a rebuild regression is loud.
- Removing fades changes perceived tab speed; `PAGE = 380 ms` is the compromise between
  the current 450/300 mix and snappiness. Tunable in one constant.
- Copy cuts must not delete in-app disclosures (accessibility disclosure, network-use
  notes) — those are listed as protected strings.
- Manrope adds ~300 KB; acceptable for a debug-track APK, revisit before release builds.
