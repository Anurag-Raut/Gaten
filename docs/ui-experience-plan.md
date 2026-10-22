# Gaten UI and interaction redesign proposal

Status: planning only, 2026-10-16. No application changes are part of this document.

## Summary

| Area | Proposed change |
| --- | --- |
| Visual identity | A more expressive Japanese reading companion: distinctive headings, prominent Japanese words, a bookmark motif, richer surfaces and one accent feature panel. |
| Copy | Short labels and current values; reveal guidance when needed. Remove repeated explanations from everyday screens. |
| Navigation | One horizontal slide throughout app-owned page navigation, including tabs, review stages and lookup reference pages. |
| Interaction polish | Deliberate press, focus, selection, reveal and success feedback, coordinated with haptics and optional sound. |
| Search focus | Tapping Home's search affordance opens Search with its field focused and keyboard ready, without a second tap. |
| Romaji | Japanese words written in Latin letters work in the same search field, offline, alongside kanji and kana. |
| WaniKani | Preserve the existing encrypted storage; check persistence, failure states, disconnect cleanup and truthful connection status. |
| Delivery | Establish the visual and motion specification first, then implement in bounded steps and validate both behavior and appearance. |

## 1. Research and design guidance

The skills were researched and read as references, not installed globally. Popularity below was observed during this research and is not proof of design quality.

| Reference | Evidence checked | What applies to Gaten |
| --- | --- | --- |
| [Anthropic frontend-design](https://skills.sh/anthropics/skills/frontend-design) | Official Anthropic repository; approximately 992.6K listed installs, repository approximately 181K stars. Read the source SKILL.md. | Choose a specific visual identity, intentional type and hierarchy, and user-centered copy. |
| [wshobson mobile-android-design](https://skills.sh/wshobson/agents/mobile-android-design) | Approximately 24.3K listed installs; repository approximately 40.9K stars. Read SKILL.md and its detailed reference. | Native touch targets, Compose components, adaptive layout and accessibility. Its older Navigation Compose examples do not replace our Navigation 3 architecture. |
| [Impeccable](https://github.com/pbakaus/impeccable) | Repository approximately 81.7K stars. Read current skill and Android, motion, planning and product-interface references. | Plan motion by interaction, match the native runtime, and make task screens easy to scan. The old frontend-design alias is deprecated. |

Optional installation commands for the two shortlisted standalone skills:

```sh
npx skills add anthropics/skills --skill frontend-design
npx skills add wshobson/agents --skill mobile-android-design
```

Android's official [animation guide](https://developer.android.com/develop/ui/compose/animation/quick-guide), [focus guide](https://developer.android.com/develop/ui/compose/touch-input/focus/request-focus), and [haptic principles](https://developer.android.com/develop/ui/views/haptics/haptics-principles) ground implementation decisions. Generic skill examples are guidance; this project's behavior and the user's preferences take priority.

### Dokuen review: selected ideas

Read the supplied agent's findings and [local report](dokuen-ux-review/README.md), and inspected the Home, Settings and appearance-preview captures.

Adopt its clear current-value settings rows, generous icon treatment, live visual previews, strong Japanese typography, and purposeful depth. A subtle accent gradient can give the Review panel character.

Keep Gaten's labeled navigation and task hierarchy. Defer unrelated proposals such as a release feed, subscription badges, new camera/furigana controls and pronunciation features. Optional integrations should look optional; being disconnected is not an error. Color contrast must be measured across gradients rather than assumed from their endpoints.

## 2. Visual direction

Design for an everyday Japanese learner: inviting, tactile, expressive, and quick to understand. Personality comes from the Japanese content, Gaten's own mark, typography and composition.

- **Brand and type:** use the existing Gaten mark with a stronger wordmark. Proposed Latin heading family: Manrope, bundled locally with its [OFL license](https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/OFL.txt). Retain reliable platform Japanese font fallback and regular-weight Japanese headwords. Start with 30–34sp main headings, 28–36sp headwords, 16sp body and 13–14sp supporting text; scale with system text settings.
- **Palette:** build on the existing default indigo: light canvas `#FAFAF9`, card `#FFFFFF`, text `#1A1A1A`, accent `#3F51B5`, tint `#E8EAF6`; dark canvas `#111111`, card `#1A1A1A`, text `#EDEDED`, accent `#9FA8DA`. The palette choice is gone — Gaten ships one neutral ladder; only the accent remains user-selectable.
- **Composition:** left-aligned content, 20–24dp page margins, 16dp internal spacing and clear separation between tasks. Give the review panel more presence than secondary status rows. Use a small bookmark/ribbon detail associated with studying rather than invented decorative statistics.
- **Surfaces:** distinguish page, card and selected control with tonal depth. Use a large radius for feature panels, a smaller radius for controls, and circular icon containers for settings. Reserve elevation for floating content and important interactive surfaces.
- **Accent:** concentrate visual richness on the Review panel and active actions. Neutral settings rows remain easy to scan. Use the accent gradient sparingly and keep text on a contrast-safe area.

### Home composition

A structural sketch; counts below are labels, not invented activity data:

```text
Gaten                                  [Settings]

[ Search Japanese or romaji              🔍 ]
  猫 / neko

╭ Review ──────────────────────────────────────╮
│ [Configured deck name]                       │
│ [New count] [Learning count] [Review count]   │
│                         [ Start review ]    │
╰─────────────────────────────────────────────╯

[Screen lookup]       [On / Set up]
Floating button                       [switch]

       Home     Search     Review     Settings
```

The search affordance needs no explanatory paragraph. The Review panel shows real AnkiDroid data or one clear setup action. Screen access is a compact optional capability, not a dominant warning over a working dictionary.

### Screen changes

| Screen | Planned presentation |
| --- | --- |
| Home | Stronger brand, immediate search, distinctive review panel, compact screen-lookup controls. |
| Search | A real focused search field, a few useful examples when empty, and readable result rows with headword, reading and a short meaning. Show the interpreted kana discreetly for romaji. |
| Word | Prominent Japanese headword, reading directly beneath, clear definitions, secondary metadata and an obvious Add to Anki action. Keep word/kanji/radical navigation consistent. |
| Review | A clear deck choice and Start review action; active sessions prioritize the card and stable reveal/rating controls. End state gives a concise completion acknowledgment. |
| Settings | Generous grouped rows, circular tonal icons, short current-value summaries and clear section headings. Put technical Anki customization and trigger instructions behind Advanced/Help disclosure where appropriate. |
| Appearance | A real Japanese word/result preview that updates with theme and accent choices, alongside existing controls. Preview only settings Gaten actually implements. |
| WaniKani | A concise saved/connected state, masked token entry, a clear Connect or Recheck action, and optional help. |
| Lookup overlay | Better separation of selected text, word identity and actions; clearer floating surface and compact controls. Keep the frozen screenshot, selection, pinned actions and service-hosted behavior intact. |

Copy budget: a title, a current value, and at most one short supporting sentence for routine controls. Long explanations belong behind Help or on the step that needs them. Keep meaningful dictionary definitions, actionable errors, download size/consent and required accessibility disclosure intact.

Examples:

| Current wording | Proposed everyday wording |
| --- | --- |
| “A word, a sentence or a whole page…” | “Search Japanese or romaji” |
| “Word, sentence or phrase” | “猫 / neko” |
| Long screen-access status paragraph | “Screen lookup · Off” with “Set up” |
| Long Anki permission explanation on Home | “Connect AnkiDroid” with a setup action |

## 3. One navigation animation, with purposeful local motion

### Page and stage transitions

Use the horizontal slide the user liked in Settings throughout app-owned navigation. Starting point: **300ms**, one shared easing policy, opaque surfaces, and no crossfade layered onto the slide.

- Opening a detail or moving into a task: new content enters from the right; the previous content moves left.
- Back, including leaving a review session for its picker: reverse the same motion.
- Tabs use the fixed Home → Search → Review → Settings order to choose slide direction. No separate tab fade.
- Deck picker → active review, active review → picker, and review completion use this same slide treatment. Grading to the next card moves forward; revealing an answer remains an in-place content reveal.
- Word → kanji → radical uses the same policy in both Activity screens and lookup content. The service keeps its own host but shares motion values.
- Inventory the service panel entrance/exit and align app-owned panel movement to the same horizontal policy without moving the frozen screenshot or selection.
- Android keyboard and system permission dialogs remain system-controlled. Follow the phone's animation settings.

Keep Navigation 3 as the Activity navigation owner. Review cards and reveal state stay local session state; they do not become separate back-stack entries. Share a small Compose transition implementation, not a new animation framework.

### Smaller interaction animations

| Interaction | Intended feedback |
| --- | --- |
| Press a primary action | Native ripple plus a small, quick press compression, returning smoothly on release/cancel. |
| Focus search | Focus border/container emphasis and keyboard-coordinated layout. |
| Select a deck, chip or theme | A short color/border/indicator transition. |
| Reveal an answer | Controlled expansion/reveal within the card; pinned rating area stays stable. |
| Add a word to Anki | Busy state becomes a checkmark and “Added” only after success; keep dimensions stable. |
| Finish review | A brief completion-mark animation, coordinated with the optional sound/haptic cue. |
| Download a model | Real progress movement and a clear installed state, with no invented progress. |

Start local feedback around 100–160ms and content expansion around 180–240ms. Tune by the final device result. Preserve responsiveness under rapid taps, interrupted Back gestures and repeated use. Stop decorative animation offscreen; honor system motion settings. Do not animate every result arrival or every paragraph.

## 4. Search interaction and romaji

### Focus behavior

Tapping the Home search affordance opens the actual Search destination, focuses its text field and presents the keyboard. Tapping the empty Search tab offers the same immediate typing flow. Keep one query and result model.

Make this an explicit, consumed focus request. Apply it only when the target entry is current and ready, so predictive Back previews and recomposition cannot summon the keyboard. Returning from a word restores query, results and scroll without stealing focus. Opening a word dismisses the keyboard. Back dismisses the keyboard before leaving Search. Clearing the query keeps focus. Preserve Japanese IME composition and avoid autocorrect/capitalization that changes romaji.

### Romaji behavior

Treat romaji as Japanese written in Latin letters; English-definition search is outside this pass. Detect input automatically, preserve the user's visible text, and convert/search offline.

Required examples include `neko` → 猫, `taberu` → 食べる, `tabeta` → 食べる, `gakkou` → 学校, `sushi` → 寿司, and katakana vocabulary such as `raamen`. Cover upper/lowercase, common `shi/si`, `chi/ti`, `tsu/tu` spellings, doubled consonants, syllabic n/apostrophes, macrons and common long-vowel spellings. Define bounded handling for ambiguous forms such as `tokyo`; never silently select one kanji from homophones.

First evaluate Android's existing [ICU Transliterator](https://developer.android.com/reference/android/icu/text/Transliterator) against this corpus. Its API exists; these particular conversions have not been verified in this planning pass. Select it only if it meets the required behavior, otherwise use a small maintained compatible converter. Check both hiragana and katakana against the actual dictionary spellings. Retain exact/deinflection/prefix ranking, bounded indexed queries, cancellation and entry deduplication. Add indexed romaji reading keys only if required spelling coverage cannot be met reliably by bounded kana conversion; do not scan every dictionary reading per keystroke.

## 5. Haptics and optional sound

Design touch, motion and audio together. Existing review feedback uses native tap/long-press effects; extend a consistent policy to meaningful actions throughout the app.

| Action | Haptic | Sound when enabled |
| --- | --- | --- |
| Choose deck/theme or change a switch | Light selection tick | None |
| Start review or finish an overlay selection | Brief acknowledgment | None |
| Reveal an answer | Light acknowledgment | None |
| Submit a grade | Light press feedback; no duplicate confirmation from recomposition | None |
| Add to Anki successfully | Distinct short confirmation | Quiet success cue |
| Finish a review session | Short completion confirmation | Quiet completion cue |
| An action fails | Clear visual message; optional brief rejection feedback | None |
| Typing, scrolling, loading or opening every page | Existing keyboard/system behavior | None |

Use Android action-oriented feedback with API-appropriate fallbacks; do not force raw long vibration patterns. A success cue must mean confirmed success, especially for Anki writes. An uncertain grade must never produce a success cue or be retried automatically.

Add a compact **Feedback** group: Haptics, enabled subject to system settings; Sounds, off by default. When sounds are enabled, use brief local cues that respect silent/DND and user volume, and do not interrupt reading media or request disruptive audio focus. Prefer platform feedback and existing Compose APIs; add only the small local audio handling needed for the two cues. Verify behavior on the target phone before claiming consistent sound policy across devices.

## 6. WaniKani token storage and connection UX

Source review found an existing sound foundation in `WaniKaniCredentials.kt` and `WaniKaniClient.kt`: AES-256-GCM, Android Keystore key, ciphertext in `noBackupFilesDir`, temporary-file writes, validation before saving, and cleanup paths for unreadable credentials. The settings field is masked, is not `rememberSaveable`, and clears after successful connection. This is a code finding, not a device security certification. Android's [Keystore documentation](https://developer.android.com/privacy-and-security/keystore) describes the platform protection.

Keep this design and check:

- A successful connection persists across app restart and can be read back correctly.
- Storage failure never shows a successful connection; API rejection and offline failure have different actionable messages.
- Token-file existence is not presented as proof that the server currently accepts the token. Saved state and a successful recheck are distinct facts.
- Token corruption/key loss leads to a recoverable reconnect state. Disconnect removes credentials and cached account content and reports partial failure.
- No plaintext preference, saved navigation state, log, analytics event, backup, clipboard write or screenshot artifact receives the token. Clear transient input appropriately without exposing it in history.
- Requests send the token only to the intended API host; redirect and image-host handling remain restricted.

No credentials are needed from the user during planning. Real persistence and device-specific Keystore behavior need a separately authorized device pass; use test credentials and never capture their contents.

## 7. Implementation sequence after approval

1. **Lock the visual specification.** Create concrete Home, Search/word and Review compositions in light/dark, with actual copy and common empty/setup states. Record the chosen type, spacing, surface and feedback tokens. Use the existing screenshots as reference, then compare the implemented result when a visual pass is authorized.
2. **Fix interaction consistency.** Replace tab/review fades, unify forward/back slides, implement the Home search focus request, and preserve current navigation/session/draft guards.
3. **Implement romaji.** Resolve converter compatibility against the example corpus, integrate with indexed dictionary search and add regression tests for conversion, ranking and original Japanese behavior.
4. **Apply the visual redesign screen by screen.** Home → Search/word → Review → Settings/Appearance → lookup overlay. Shorten copy in the same pass so the layout is designed around final content.
5. **Integrate feedback and token checks.** Add the small haptic/sound policy and preferences, verify success/error timing, and address concrete credential defects only if found.
6. **Build and focused tests.** Cover navigation return paths, retained drafts/sessions, search focus request consumption, romaji, single Anki grading and credential failure behavior. No mechanical tests for every style change.
7. **Visual/device acceptance when authorized.** Check the slide direction and interruptions, keyboard entry, Japanese IME, large text, light/dark, small phones/landscape, haptic strength, silent-mode sound behavior and token persistence on the Nord 6. A green build alone cannot establish visual polish.

Expected file areas: `ui/nav`, `ui/theme`, `ui/home`, `ui/search`, `ui/anki`, lookup/reference UI and strings; dictionary search/converter and the database builder only if needed; narrowly scoped WaniKani changes if the audit finds a defect. Retain Kotlin/Compose, stable Material 3, Navigation 3 and the existing service integration. Preserve all current uncommitted work; no commits or pushes are included.

## Acceptance criteria

- Navigating into or out of Review feels like opening/closing Settings: the same horizontal movement and timing, with no app-owned navigation fades.
- One tap on Home search is enough to type; normal Back and predictive previews never reopen the keyboard unexpectedly.
- Supported romaji finds Japanese entries offline while existing kanji/kana and selected-entry Anki export still work.
- Home and Settings communicate actions/status at a glance; optional setup does not dominate working features.
- Added depth, typography, preview and interaction detail produce a coherent Gaten identity across light/dark themes.
- Motion, haptics and sound agree on the result, respect device preferences and do not duplicate effects or provider writes.
- WaniKani token persistence, failures and deletion are verified to the stated scope; secret contents stay out of artifacts.

Approval of this proposal is required before application implementation, following the user's planning instructions.
