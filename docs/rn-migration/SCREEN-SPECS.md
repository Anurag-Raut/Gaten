# Gaten Compose → React Native Screen Spec

Global tokens: Spacing = xs 4 / s 8 / m 12 / l 16 / xl 24 / xxl 32 dp. Max content width 840dp (all scrolling pages center a column at widthIn(max=840)). Two-column layouts split at 640dp. Card elevation everywhere is exactly 1dp, no borders. Press feedback on cards/rows/buttons = `pressScale` (scale-down tied to the press's own interaction source; curve `pressEasing` 0.4,0,0.2,1; 0.98 over `Motion.SHORT` = 150 ms). Page transition = `pageSlide`: horizontal slide, 380ms, emphasized easing (0.2,0,0,1), no fade; forward = incoming full width over outgoing at 25% parallax, z=1; back = mirrored, incoming starts 25% out, z=-1 — for a push, a pop, a previewed back gesture and a review stage alike. **Tab switch is a page move in the port**: the tab level is `@react-navigation/material-top-tabs` over the `react-native-pager-view` native pager (the FitMedik reference), so a tap and a finger drag alike slide the real pages horizontally under a bar that stays put — one indicator pill travels between item centres, driven by the pager's own `position` value. (Compose's `tabShift` — a 50dp slide plus a fade over `Motion.SHORT` — and the port's first full-width 380 ms slide are both **superseded**.) RTL mirrors automatically.

Haptics (Feedback.kt): Tick = CONTEXT_CLICK, Lookup = CLOCK_TICK, Confirm = LONG_PRESS; delivered via the on-screen view (`performHapticFeedback`, respects system haptics setting — never force). Sounds (FeedbackSound.Added, .Done) only when `feedback_sounds` pref is true; Added plays when a note is written (word detail), Done when a review session finishes.

---

## 1. App shell (GatenApp.kt + GatenNavigation.kt + GatenRootScene.kt + GatenKeys.kt)

**Inputs/props:** themeMode, palette, accent + their change callbacks; `settingsPageRequest: String?` + `onSettingsPageRequestHandled()`. Three VMs owned here (outlive destinations): SearchViewModel, AnkiReviewViewModel, RecognitionViewModel.

**Structure:** four tabs `Home | Search | Review | Settings` (bar labels verbatim: "Home", "Search", "Review", "Settings"; icons outlined Home/Search/Style/Settings, no icon descriptions). Each tab owns an independent back stack seeded with its root key. Displayed entries = Home's stack plus the selected tab's stack (Home only when Home selected) — so Back from any tab's root exits to Home, and at Home's root Back belongs to the system.

**Bottom bar (GatenRootScene at `3750d95`; **superseded** by `GatenBottomBar.kt` at `ac594f2`: one bar drawn once *under* the display rather than inside each scene — the port renders it once from `AppShell`, and a tab switch is then a 50 dp shift under a stationary bar, see the header):** NavigationBar rendered *inside each scene* (not above the display), so a page pushed over a tab is full-screen with no bar, and during transitions each scene keeps its own bar. The lit tab is the scene's own tab (during a predictive-back preview the underlying screen's bar shows its own selection). Bar hidden while a review session is active (`barVisible = !step.holdsSession(deck)` — session, failed-in-deck, or first-card-wait of a chosen deck). While the bar is shown the content box consumes the navigationBars inset (`consumeWindowInsets`) so screens don't pad twice; with the bar away screens pad it themselves. Tab tap on an already-selected tab = no-op; selecting a different tab emits one Tick haptic (only when actually changing).

**Routes/keys:** HomeKey, SearchKey, ReviewKey, SettingsKey (roots); WordKey(entryId), KanjiKey(character), RadicalKey(id), SettingsPageKey(page, instance) pushed above. Content key = `"tag@TAB"` so the same settings page can be open on two tabs with separate saved state. SettingsPageKey always gets a fresh UUID instance — the same page can be open twice on one stack (a second copy opens over the first; the lower one is untouched).

**searchFocusRequested — exact semantics (do not misstate):**

**Superseded in the port (user decision, round 2):** nothing takes the cursor for the reader — not a tab
tap, not a swipe, not Home's hero card, not a mount, not a return from a word. The keyboard opens only on
the reader's own tap on the field, and the request/consume contract described below therefore describes the
Compose app only: `searchFocus.ts` is deleted and the pager's own keyboard dismissal is back.
- It is state owned by the *shell/navigator*, not the screen, because the search screen composition is torn down on tab switches while the intent to type must outlive it.
- Set true ONLY by: (a) `selectTab(Search)` when the search stack size == 1 (tapping the Search tab while its root/results are on top — a tap on Search with a detail page open does NOT set it: the page is shown as-is with keyboard down); (b) `openSearch()` (Home's hero card): pops the search stack back to its root (closing any open word page), switches tab, sets the flag.
- Reset false: by `consumeSearchFocus()` after the field takes the cursor; AND a rotation/rebuild constructs a fresh navigator with the flag false — so a cursor request is never replayed on config change or on returning from a word.
- Screen contract: `LaunchedEffect(focusRequested) { if (!focusRequested) return; fieldFocus.requestFocus(); keyboard.show(); onFocusConsumed() }` — request exactly once, then hand back. Restores, returns from a word, rotations and ordinary recompositions never raise the keyboard.

**Other navigator behaviors:** `push(WordKey/KanjiKey/RadicalKey)` always lands on the Search tab's stack (a page reached from search belongs to search); SettingsPageKey pushes onto the *current* tab. `openKanji(c)`: closes any trailing Kanji/Radical page then pushes the kanji (no-op if it's already the top — toggle; chain never exceeds one kanji + one radical). `openRadical(id)`: closes a trailing radical then pushes (or closes if same id). `goBack()`: pop top of selected tab's stack, else switch to Home. `exitToHome()` = selectTab(Home) (used by review Finish). `isCurrent(tab,key)` = selectedTab==tab AND top of that stack == key — used as a guard prop so an exiting-but-still-composed screen (Anki settings discard prompt, review BackHandler) stops answering.

**External launch:** a non-null `settingsPageRequest` is matched against `SettingsRoutes.PAGES = [lookup, recognition, anki, wanikani, appearance, about]` (hub order); unknown names change nothing (no fallback navigation); a match selects the Settings tab and pushes that page; then `onSettingsPageRequestHandled()` fires once per intent. The intent extra is `settings_page` (WaniKani detail's `openApp` sends `"wanikani"`).

**Back mapping for RN:** system Back → navigator.goBack(); on Review during a session the screen intercepts (see review spec).

---

## 2. Home dashboard (HomeDashboard.kt + HomeState.kt)

**Data inputs:** everything re-read on every entry to composition AND every Activity ON_RESUME (never trusted from last visit):
- `isAccessibilityServiceEnabled(context)` (platform enabled-services setting).
- `TriggerPreferences.floatingButtonEnabled(context)`.
- `deckStatus = readDeckStatus(context)` — pure read, NEVER raises a permission dialog: NotInstalled→AnkiDroidMissing; NeedsPermission→PermissionNeeded; Ready→read configured deck name + deck counts by name (exact match first, then case-insensitive). Counts are AnkiDroid's own for that one deck (already include subdecks — never sum). Outcomes: `Counts(deckName,newCount,learnCount,reviewCount)` (due=sum), `DeckMissing(configuredName)`, `Unreadable(AnkiReviewFailure)`, `AnkiDroidMissing`, `PermissionNeeded`, `NotRead`.
- TriggerPreferences live in SharedPreferences file `gaten_settings`; broadcast action `ACTION_FLOATING_BUTTON_CHANGED`.

**Layout (vertical scroll, top-center, max 840dp, horizontal padding 24):**
1. Title "Gaten" (titleLarge) + subtitle "Your Japanese reading toolkit".
2. Adaptive pair at >=640dp (side-by-side, each self-sized — no equal heights): SearchHeroCard | column(ReviewCard + LookupCard).
- **SearchHeroCard**: whole card is one button (a11y label "Open search", role Button, pressScale). Content: search icon (primary, 24dp) + hint "猫, ねこ, neko…" (one line, ellipsized) + right chevron. Looks like a search field; nothing typed here. Tap → `onOpenSearch` = navigator.openSearch().
- **ReviewCard** (HomeCard): container = primaryContainer when due>0 (heading/body onPrimaryContainer), else surface. Header: Style icon 20dp + title (deck name when Counts, else "Review", 1 line) + refresh IconButton (a11y "Check AnkiDroid again") → `reads++` (fresh read). Counts state: three CountTiles (number titleLarge / label labelSmall) "New" / "Learning" / "Review"; when due==0 add line "Nothing due right now.". Non-count states get one honest sentence: NotRead "Checking AnkiDroid…"; AnkiDroidMissing "AnkiDroid is not installed."; PermissionNeeded "AnkiDroid has not given Gaten access yet."; DeckMissing "No deck named “%name” in AnkiDroid."; Unreadable "Could not read AnkiDroid: %failure" where failure = not installed / access has not been granted / AnkiDroid has no collection folder yet / the deck is no longer there / AnkiDroid could not open the collection / AnkiDroid answered something Gaten could not read.
  - Actions per state (filled Button primary + optional TextButton secondary): NotRead → none. AnkiDroidMissing → [Card settings]→onOpenSettings("anki"). PermissionNeeded → [Review]→onOpenReview (review tab is where permission is asked). Counts → [Review] + [Card settings]. DeckMissing → [Choose deck]→anki settings. Unreadable → [Try again]→refresh.
- **LookupCard** (HomeCard, surface): Row 1: 10dp status dot (primary when screen access on, error color when off) + "Screen lookup" titleMedium + status line "Screen access is on." / "Screen access is off.". Row 2: "Floating button" (bodyLarge) + support "Tap the bubble on screen to look up." (access on) / "The bubble appears once screen access is on." (off) + Switch. Row 3: TextButton "Set up screen lookup" + chevron → settings page "lookup".

**Actions:** floating Switch toggle → Tick haptic FIRST, then optimistic local state, `TriggerPreferences.setFloatingButtonEnabled(ctx, enabled)`, and an explicit-package broadcast of `ACTION_FLOATING_BUTTON_CHANGED` (manifest receiver on API 26+ needs the package set). Only user-made changes tick; page navigation never does.

**Insets:** root takes safeDrawing Top+Horizontal only (bar owns bottom), then vertical scroll.

---

## 3. Settings hub (SettingsScreen.kt)

**Data inputs (all re-read on every arrival + every ON_RESUME, since all can change while away):** screenAccess (platform setting), MangaOcrModel.isInstalled/installedCatalogId, AnkiExporter.status, AnkiPreferences.load().deckName, WaniKaniRepository.isConfigured, app versionName. Nothing here asks for anything — missing things are stated; the owning page asks.

**Layout (vertical scroll, Top+Horizontal insets only — bar owns bottom):**
1. "Settings" (titleLarge) + "What Gaten reads, where it sends it, and how it looks."
2. **ScreenAccessCard**: on → surface colors, dot primary, "Screen access is on" / "Circle any Japanese text on screen to look it up."; off → errorContainer colors, dot error, "Screen access is off" / "Gaten cannot read the screen until you turn it on." + filled Button "Turn on".
3. Group **"Look up"**: row "Screen lookup" (support "Floating button, Quick Settings tile and hardware key", value "On"/"Off") → page `lookup`; row "Text recognition" (support "Optional stylized-text model", value = installed model label or "None") → `recognition`.
4. Group **"Study"**: row "Anki cards" (summary = "Deck, note type and card layout" when Ready, "AnkiDroid is not installed" when NotInstalled, "Allow Gaten to add cards to AnkiDroid" when NeedsPermission; value = deck name only when Ready) → `anki`; row "WaniKani" (support "Kanji and vocabulary reference", value "Connected"/"Not connected") → `wanikani`.
5. Group **"App"**: row "Appearance" (support "Theme, palette and accent", value "%mode · %accent") → `appearance`; row "About" (support "Offline, privacy, licences", value = version) → `about`.

**Row anatomy (SettingsRow):** ListItem: 40dp rounded-square icon badge filled with `primary`, icon tinted onPrimary (22dp); headline = row title; supporting = summary; trailing = one-line value (labelMedium, max width 160dp, ellipsized) + chevron *only when the row navigates*. Rows merge descendants semantically. Dividers inset 72dp from start.

**Screen access request flow (rememberScreenAccessRequest):** "Turn on" → first the AccessibilityDisclosureDialog (title "Grant Accessibility Permission", lead + detailed body, agreement label "I understand how this permission will be used and agree to proceed"), then only on accept `startActivity(ACTION_ACCESSIBILITY_SETTINGS)`; failure → toast "Could not open Accessibility Settings".

**SettingsPageScaffold (shared by most pages; Anki/WaniKani re-implement the same pattern):** Scaffold containerColor = background; contentWindowInsets = systemBars Horizontal+Top only (the scroller owns bottom); TopAppBar(title, back arrow a11y "Back" → onClose, optional help action). Body: centered Column max 840dp, horizontal padding 24, navigationBarsPadding + 16dp bottom, vertical scroll.

**Help dialog (SettingsHelpAction/Dialog):** help icon (a11y "Open help") opens AlertDialog titled "Help", body = the page's paragraphs stacked (max height 360dp, scrollable), confirm "Close". Used by Recognition (3 paragraphs) and Anki settings (5 paragraphs).

**Routing:** `SettingsDetailScreen(page, ...)` dispatches: lookup→LookupSettingsScreen, recognition→RecognitionSettingsScreen(onClose, recognitionViewModel), anki→AnkiSettingsScreen(onClose, isCurrentDestination), wanikani→WaniKaniSettingsScreen(onClose), appearance→AppearanceSettingsScreen(theme callbacks), else→AboutSettingsScreen.

---

## 4. Lookup settings (LookupSettingsScreen.kt)

**Inputs:** screenAccess, notificationAccess (hasNotificationAccess), `TriggerPreferences.floatingButtonEnabled`, `TriggerPreferences.tileAdded` — all re-read on every ON_RESUME; tileAdded ALSO observed live via a SharedPreferences change listener (tile callbacks can arrive while the page sits under the QS shade).

**Layout (SettingsPageScaffold "Screen lookup"):**
1. ScreenAccessCard (same as hub; "Turn on" runs the same disclosure flow).
2. Group "Start a lookup":
   - **FloatingButtonRow**: TouchApp icon + title "Floating button" + support "Tap the bubble on screen to look up." (access on) / "Appears once screen access is on." (off) + Switch (a11y = "Floating button"). Toggle → Tick haptic, set pref, broadcast ACTION_FLOATING_BUTTON_CHANGED explicit-package.
   - **TileRow** (Widgets icon): title "Quick Settings tile"; right value "Added to Quick Settings" when added. Support: not added → "Add Gaten to Quick Settings to look up from the notification shade."; added → "To remove it, open the Quick Settings editor and drag Gaten out.". Not added & API<33: extra line "Open the Quick Settings editor and drag Gaten into your tiles.". Not added & API>=33: OutlinedButton "Add tile" → `StatusBarManager.requestAddTileService(...)` with label "Gaten"; result Added/AlreadyAdded → onAdded() (sets tileAdded pref); NotAdded → silent; anything else → toast "Could not add the tile. Try the Quick Settings editor."
   - **KeyMapperRow** (Key icon): title "Hardware key (Key Mapper)" + TextButton toggling "Set up"/"Hide" (collapsed by default). Expanded: "Send a broadcast with this action:" + monospace `LookupTriggerReceiver.ACTION_TRIGGER_LOOKUP` + copy IconButton (a11y "Copy the trigger action") → clipboard.
3. Group "While a lookup is open": **MediaPauseRow** (PauseCircle icon): "Pause video while looking up" + right value "Allowed"/"Not allowed"; support "Gaten pauses what is playing when a lookup opens, and plays it again when it closes." (granted) / "Without notification access Gaten sends a media pause key instead, which stops some players and not others." (denied); not granted → OutlinedButton "Turn on" → `ACTION_NOTIFICATION_LISTENER_SETTINGS`, failure toast "Could not open Notification Access settings".

---

## 5. Recognition settings (RecognitionSettingsScreen.kt + RecognitionViewModel)

**Inputs:** RecognitionViewModel owned by the shell (download survives leaving the page/rotation). State: `installed: Bool`, `installedId: String?`, `sizeBytes: Long`, `downloading: Entry?`, `progress: Progress?` (fraction 0..1 + fileName), `busy: Bool`. Re-read installed on every arrival + ON_RESUME (skipped while busy). Catalog = static MangaOcrCatalog entries (label, id, description, approxSizeMb).

**Layout (SettingsPageScaffold "Text recognition" + Help with 3 paragraphs: status_none_help, recognition_help, network_note — verbatim in strings_settings.xml):**
1. **RecognitionStatusCard**: primaryContainer when installed (content onPrimaryContainer) else surface; Memory icon + "Text recognition" + right-aligned value: installed+known id → "%label installed (%size)"; installed unknown id → "Installed model (%size)"; none → "No model installed". Size = formatted short file size.
2. Group "Models": one ModelRow per catalog entry (dividers between): Download icon badge, label, right value "~%d MB", description line, then chip "Installed" (primary labelLarge) when installed, else OutlinedButton "Download" (disabled while busy) → confirm dialog.
3. **DownloadingCard** (in place, while downloading): "Downloading %label…" + LinearProgressIndicator (indeterminate when no fraction yet, else fraction) + "%d%% · %file" (or just fileName, or "Starting…") + TextButton "Cancel" → viewModel.cancelDownload().
4. Row of OutlinedButtons: "Import model files" (OpenMultipleDocuments "*/*") always; "Remove" only when installed; both disabled while busy.

**Confirm dialog:** title "Download %label?", body metered? "You are on mobile data. About %d MB will be downloaded from huggingface.co — use Wi-Fi if you can." : "About %d MB will be downloaded from huggingface.co. Use Wi-Fi if you can.", confirm "Download" → viewModel.download(entry), dismiss "Cancel".

**VM calls:** download(entry) (guarded by busy; on completion toasts "%label installed" / "Download cancelled" / "Download failed: %reason", re-reads installed), importFiles(uris) (dropped if busy or empty; toasts "manga-ocr model installed" / "Import failed: encoder, decoder and vocab files are all needed"), removeModel() (toasts "Model removed" / "Could not remove the model files"), cancelDownload(), refreshInstalled(). One mutation at a time (`busy`); work in viewModelScope.

---

## 6. Anki settings (AnkiSettingsScreen.kt)

**Inputs:** local draft of AnkiSettings (deckName, tags, modelId, front, back, css, modelFields[], fieldSources[]) seeded from AnkiPreferences.load; draft + `showAdvanced` saved across process death (listSaver preserving field order and mapping). `saved` snapshot taken once at composition; `dirty = draft != saved`. `status = AnkiExporter.status` re-read on every ON_RESUME (+ after permission result); collection (decks, models) read when status==Ready and on a "refresh" counter change; readFailed flag; `loading`/`loadingFields` gates. Permission = AnkiDroid READ_WRITE_PERMISSION; denial toast "AnkiDroid did not allow writing to its database".

**Leave guard:** BackHandler and the top-bar arrow both call `leave()`: dirty → AlertDialog "Discard changes?" / "Your deck, tags, field mapping and template edits are not saved yet." / [Discard]→onClose / [Keep editing]; not dirty → close immediately. BackHandler enabled only while dirty AND isCurrentDestination. Save: `AnkiPreferences.save(ctx, draft)` + toast "Anki settings saved" + onClose.

**Layout (own Scaffold, "Customize cards", imePadding, bottom owned by scroller):** TopAppBar actions: help "?" (paragraphs: deck_help, tags_help, mapping_help, existing_help, template_help) + "Save" TextButton enabled = dirty && no validation error && !loading && !loadingFields.
1. **Connection card**: NotInstalled → "Install AnkiDroid to add cards. You can configure Gaten cards here first."; NeedsPermission → explanatory paragraph + Button "Allow AnkiDroid access"; Ready → OutlinedButton "Refresh from AnkiDroid" (disabled while loading/loadingFields). Loading → line "Reading AnkiDroid…"; readFailed → error "Could not read AnkiDroid. Open AnkiDroid, then try Refresh.".
2. **Deck card**: OutlinedTextField label "Deck" (singleLine) + dropdown "Choose existing deck" listing collection decks (writes draft.deckName).
3. **Tags card**: OutlinedTextField label "Tags" (singleLine).
4. **Advanced card**: fold row "Edit front, back, and styling" (chevron up/down, saveable expansion). When expanded:
   - **Note type** dropdown: options = [null → "Gaten cards (customizable)"] + collection models (id→name); current value: null→"Gaten cards (customizable)", unknown id→"Saved note type (unavailable)". Selecting null clears modelFields+fieldSources. Selecting a model loads its fields (loadingFields): previous mapping by field name reused (case-insensitive match against AnkiField enum except EMPTY), else EMPTY; failure toast "Could not read AnkiDroid. Open AnkiDroid, then try Refresh.".
   - With a model selected: one dropdown per model field, label = field name, value = mapped source label; options = all AnkiField entries (Expression / Reading / "Meaning and part of speech" / "Leave empty").
   - With managed (null) note type: three monospace multiline editors "Front (HTML)", "Back (HTML)", "Styling (CSS)" (minLines 3, max 8) + TextButton "Restore default layout" → DEFAULT_FRONT/BACK/CSS.
5. Validation: shown under the form only when dirty; per-error strings (deck/tags/front/back/css/mapping) verbatim in strings_anki.xml (e.g. "Enter a deck name of up to 200 characters, without empty :: sections or control characters.").

---

## 7. WaniKani settings (WaniKaniSettingsScreen.kt)

**Inputs:** `token` in plain remember (NOT saveable — a rotation clears it so a secret never lands in saved instance state; also cleared on successful connect and on disconnect); `connected` from WaniKaniRepository.isConfigured (re-read after every op); `busy`; `answer: WaniKaniConnection?`; `cleared: Bool?` (null = nothing disconnected, true = fully cleared, false = partial).

**Layout (own Scaffold "WaniKani", imePadding, scroller owns bottom):**
1. Card: paragraph `wk_settings_explain` + row: left status line "Connected: a token is saved on this device." / "Not connected. Kanji pages say what to do until you connect.", right value "On"/"None".
2. Card: password OutlinedTextField (label "Personal access token", placeholder "Paste your token", singleLine, disabled while busy, KeyboardType.Password) + help paragraph (token kept on device only, encrypted, sent only to api.wanikani.com, never shown again) + TextButton "Open WaniKani token settings" → browser https://www.wanikani.com/settings/personal_access_tokens.
3. Filled Button full-width: "Checking…" while busy else "Save and connect"; enabled = !busy && token.isNotBlank() → `WaniKaniRepository.connect(appContext, token)` (verifies before storing); on Connected clears the field; then `connected` re-read from storage (not assumed); message set.
4. Row (shown while connected OR after a partial disconnect): "Recheck connection" (enabled !busy && connected → reconnect(), re-reads subscription level) | "Disconnect" (enabled !busy → disconnect() returns Bool fully-cleared, sets cleared; connected re-read; token cleared).
5. Message area (bodyMedium): Connected & maxLevelGranted<60 → "Connected. Your account covers WaniKani's first %d levels; kanji beyond them are shown as outside your subscription."; Connected >=60 → "Connected. Gaten can read WaniKani's reference content."; InvalidToken → "WaniKani rejected that token. Check it was copied in full."; Unavailable(failure) → "Could not connect to WaniKani: %failure"; disconnected → "Disconnected. The saved token and every cached page were removed."; partial → "Some of the saved token or cached pages could not be removed. Disconnect again.".
6. Final card: coverage paragraph + attribution paragraph (verbatim in strings_wanikani.xml).

---

## 8. Appearance settings (AppearanceSettingsScreen.kt)

**Inputs:** themeMode/palette/accent + change callbacks (state owned by the shell — a tap recolours this very page immediately; stored by ThemePreferences in `gaten_settings`: keys theme_mode/palette/accent, defaults System/Gaten/Indigo). `dark = themeMode.isDark(isSystemInDarkTheme())` — previews use the reader's choice, not the device's.

**Layout (SettingsPageScaffold "Appearance"):**
1. **Theme** card (support "Follow your device, or keep Gaten light or dark on its own."): single-choice segmented row System / Light / Dark.
2. **Palette** card (support "The colour of Gaten's paper. Your selected accent stays unchanged."): FlowRow of 4 palettes Gaten/Mono/Cool/Warm; each item = 64×48dp preview box (border 2dp onSurface when selected else 1dp outline, inner 4dp padding filled with the palette's neutral background for the current mode) containing "Aa" (titleMedium) + name label below. Role RadioButton. No accent in palette previews.
3. **Accent** card (support "The one colour in Gaten: buttons, the edge outline and the lasso."): FlowRow max 4 per row of 7 accents Indigo/Blue/Violet/Teal/Green/Rose/Graphite; each = 48dp tap target containing a 40dp circle: 2dp onSurface ring when selected, 5dp inset, inner circle filled with the accent's primary for the current mode; name below. Role RadioButton + pressScale. Tick haptic ONLY when selecting a different accent (tapping the selected one changes nothing, gets nothing).
4. Group "Feedback": row "Haptics" (summary "Follows your phone's settings.") — informational, no control; divider; **SoundsRow**: VolumeUp icon + "Sounds" + "A short cue when a word is added to Anki, and when a review session ends." + Switch (a11y "Sounds") reading/writing `feedback_sounds` (default false). Tick only when the value actually moved.

---

## 9. About (AboutSettingsScreen.kt)

**Inputs:** versionName only. **Layout (SettingsPageScaffold "About"):**
1. "Gaten" titleLarge + tagline "Circle Japanese on screen. Read it offline." + "Gaten %version" (labelMedium, primary).
2. AboutCard(Shield) "Offline by default" / "The dictionary, the recognition and the lookup all run on this device, and the screenshot never leaves it."; AboutCard(CloudOff) "Two network uses, both optional" / "Downloading a text-recognition model from huggingface.co, and WaniKani if you connect a personal access token. Nothing else is ever sent.".
3. Group "Attributions" — rows open browser URLs (ACTION_VIEW; silent no-op without a browser): EDRDG dictionary → edrdg.org/edrdg/licence.html; manga-ocr → github.com/kha-white/manga-ocr; ML Kit → developers.google.com/ml-kit/terms; WaniKani → wanikani.com; Manrope typeface → github.com/googlefonts/manrope; wanakana → github.com/esnaultdev/wanakana-kt. Each with a value label on the right (e.g. "edrdg.org") and a support sentence (verbatim in strings_settings.xml).
4. Group "Licence": GroupNote "Gaten is free software under GPL-3.0-or-later, derived from CircleToSearch by AKS-Labs." + rows "Read the licence" (gnu.org/licenses/gpl-3.0) and "CircleToSearch on GitHub" (github.com/AKS-Labs/CircleToSearch).

---

## 10. Search tab (SearchScreen.kt + SearchViewModel.kt)

**State (SearchState, one object):** query, queryLimited, status (Idle|Searching|Results|Failed|Unsupported), hits[], error?, generation, ankiStatus, ankiSettings, exports: Map<entryId, ExportProgress>. `hitFor(entryId)` resolves a detail's row (null while the search carrying it is still running). `export(entryId)`: Busy always wins; otherwise if the stored attempt's (settings,status) != current ones, read as Idle — a stale answer under changed config shows untouched.

**VM behaviors:**
- Query bounded to DictionarySearch.QUERY_LIMIT chars (cut never splits a surrogate pair); `queryLimited` true when text had to be cut.
- `statusOf(query)`: normalized empty → Idle; normalized not searchable (romaji included — it IS transliterated and searched like kana) → Unsupported; else Searching.
- onQueryChange: cancel prior job, save query + reset scroll to 0 in savedState, clear hits, generation++, search after 250ms debounce. searchNow() (IME search action / example tap): no debounce. retry(): re-run failed query. Stale results dropped (state.query must match). Repository opened once, kept warm, closed onCleared; searches on IO dispatcher.
- Scroll persistence: VM exposes scrollIndex/scrollOffset (savedState keys `search.scroll.index/offset`); screen initializes LazyListState from them and writes them back on dispose (tab switch or navigating into a word). New typed query (generation change) → scrollToItem(0); a restored screen does NOT reset.
- Process restore: query re-read from savedState (`search.query`); if it should be Searching, search runs again immediately (no debounce) — this also brings back the open entry's row for a restored word page.
- Exports: prepareExport(entry) on detail resume — re-reads AnkiExporter.status + AnkiPreferences (reader may have changed them), skips while a check is under way; marks Checking, then duplicate-check → Done(DUPLICATE) or Idle. addToAnki(entry): guarded against Busy and Done under current config; runs in viewModelScope against app context (finishes however the reader navigates); Added→Done(ADDED), Duplicate→Done(DUPLICATE), Failed(msg)→Failed; re-reads status/settings after so a mid-flight config change re-arms the button. shareToAnkiDroid(entry) → AnkiDroid's own add screen (fallback when permission ungranted). refreshAnkiStatus() after a permission grant (nothing is exported on grant).

**Layout:** Scaffold, TopAppBar "Search" (no back — it's a tab root). Column: SearchField + body area (imePadding so keyboard doesn't cover results).
- **SearchField:** OutlinedTextField singleLine, large shape, placeholder "猫, ねこ, neko…", leading search icon, trailing clear IconButton (a11y "Clear the query") only when query non-empty; supporting text when queryLimited: "Searches look at the first %d characters."; IME action Search → clearFocus + viewModel.searchNow().
- **Body states:** Idle → EmptyPrompt (scrollable): "Look up a word" + hint "Kanji, kana, a romaji reading such as neko or tokyo, or an inflected form such as 食べた. Gaten looks up words offline, from the dictionary on this device." + FlowRow of example chips 猫 / たべる / 食べた / ありがとう / 学校 / 面白い (surfaceVariant, pressScale, tap → onQueryChange(example)). Unsupported → Notice "Search in Japanese" / "Type kanji or kana, or a reading in romaji such as neko or tokyo.". Failed → Notice "The dictionary could not be read" / error detail / Button "Try again". Searching → centered spinner + "Searching the dictionary…". Empty hits → Notice "No matches for %query" / "Check the spelling, or try a shorter word. A dictionary form matches best — 食べる rather than 食べた.".
- **Results (LazyColumn, contentPadding h16 v8, gap 8):** first item (key "count"): "%d results" (labelLarge, onSurfaceVariant). Then non-prefix hits (key = entry.id) as ResultCards. If any PREFIX hits: header item (key "suggestions") "Words starting with %normalizedQuery" (titleSmall) + those cards. Exact-vs-loose grouping: hits whose match != PREFIX (EXACT / NORMALIZED / LOOSE) come first in dictionary order; PREFIX hits are demoted behind the header.
- **ResultCard:** 1dp-elevation card, pressScale, a11y label "Open %primary", whole card clickable. Inside (Row, padding h16 v12): Column: primary headword (titleLarge, 1 line) + optional "common" chip (small shape, primaryContainer/onPrimaryContainer, labelSmall) when entry.common; kana joined "・" (1 line); first 3 glosses joined "; " (max 2 lines); first pos (1 line, bodySmall); when match is NORMALIZED or LOOSE: "from %form" (labelMedium, primary) — the spelling that actually reached the entry (kana-fold, deinflection, or collapsed loose reading e.g. tokyo→ときょ→東京). Trailing chevron.
- Tapping a result: hide keyboard, clearFocus, onOpenWord(entryId) → shell pushes WordKey on the Search stack.

---

## 11. Word detail (SearchWordDetail.kt — SearchWordScreen)

**Inputs:** entryId from route; resolves `state.hitFor(entryId)` from the shared SearchViewModel (never carries the entry object, so process restore works). While hit==null: if state.status==Searching show the search Loading; else Notice "This entry is no longer in the results" / "The search was run again and no longer has it. Go back for the results you have.". TopAppBar title = entry.primary (fallback "Search"), back arrow ("Back") → navigator.goBack.

**Insets:** Scaffold takes systemBars Horizontal+Top only; the pinned action bar owns the bottom (navigationBarsPadding), so no double padding.

**Body (scrolling column, horizontal 16):**
1. **Headword card:** FlowRow: primary (displaySmall) + "common" chip; kana joined "・" (1 line, full list in a11y); if other kanji spellings exist: "Also written: %list" (kanji.drop(1) joined "・" — e.g. ネコ for 猫); if match NORMALIZED/LOOSE: "from %form" (primary).
2. **Meanings card:** caption "Meaning"; empty glosses → "The dictionary has no gloss for this entry."; else numbered glosses — number "%d." in a fixed 28dp gutter (hanging indent, wrapping gloss stays under itself), bodyLarge; then caption "Part of speech" + outlined PosChips (1dp outlineVariant border, surface bg).
3. **KanjiStrip** (components/KanjiStrip): label "WaniKani kanji" (small) + horizontally scrolling chips of the distinct ideographs of entry.primary (code-point iteration so 𠮷 survives; kana-only words render nothing). The whole section is absent while no WaniKani token is stored — the chips' only purpose is a WaniKani page — and the token's presence is read on every arrival, since it is stored or forgotten on the WaniKani settings page this screen returns from. Chip: shape small, surfaceVariant (primaryContainer + 1dp primary border + check icon 18dp with overshoot spring when selected); text at headword size; a11y "WaniKani details for %char" + selected state. Tap → Tick haptic + onKanji(character) (shell decides: open or toggle kanji page).
4. FlowRow of TextButtons: "Anki settings" → openSettingsPage("anki"); "WaniKani settings" → openSettingsPage("wanikani").
5. **Pinned AddToAnki bar** (Surface background color, NOT scrolling; navigationBarsPadding + padding h16 v16):
   - Line "Deck: %deckName" (labelMedium, 1 line).
   - Button (when ankiStatus != NotInstalled), full width, small shape, enabled = export is Idle || Failed (never tappable while Checking/Busy/Done — a second tap must not write a second note): onClick = Ready→addToAnki, NeedsPermission→launch READ_WRITE_PERMISSION request. Content: celebrating (1600ms after ADDED) → "Added ✓"; Checking/Busy → 18dp spinner; Done → "Added" (ADDED) / "Already in Anki" (DUPLICATE); else → + icon + "Add to Anki".
   - NeedsPermission → TextButton "Send to AnkiDroid instead" → shareToAnkiDroid; false → toast "AnkiDroid cannot be opened for sharing".
   - NotInstalled → bodySmall "Install AnkiDroid to add cards. You can configure Gaten cards here first." (no button).
   - Failed → error-colored message from the VM.

**Lifecycle/tricky:** ON_RESUME of the entry → viewModel.prepareExport(entry) (re-check duplicates/settings after any absence). Celebration cue: fires only on the transition writing→Done(ADDED) (Confirm haptic + Added sound); duplicates, restores and revisits stay silent; `celebrated` auto-clears after 1600ms. Uses sheetType(compact=false) type scale shared with the lookup sheet.

---

## 12. Search reference screen (SearchReferenceScreen.kt)

Full-screen WaniKani page for KanjiKey(character) / RadicalKey(id). Plain Scaffold (no top bar — the panel brings its own heading + back arrow, since the real title isn't known until the lookup answers). Panel params: showBack=true, scrollable=true, compact=false, sheetType(compact=false). onRadical wraps: Tick haptic once for choosing a component, then navigator.openRadical(id) (toggle semantics). onSettings → openSettingsPage("wanikani") — used by the panel's NeedsToken/AuthRejected notices.

---

## 13. WaniKani detail panel (WaniKaniDetail.kt — WaniKaniDetailPanel)

Shared panel drawn by the search reference screen, the review screen (inline) and the overlay sheet.

**Data:** `WaniKaniRepository.lookup(context, target, refresh)` returns WaniKaniLookup: loading (UI-held null until the single-shot answer), Available(subject, cached, stale, cacheFailure), NoEntry, Restricted, NeedsToken, AuthRejected, Unreachable(failure). `target` is the whole key — remember(target) restarts state on change; retry counter re-requests with refresh=true (a retry goes to WaniKani itself; the cached page already on screen is replaced).

**Header row:** optional back IconButton (a11y "Close these details"; 36dp touch target when compact) — hidden in the review screen (the opening chip closes there). Heading: kanji → its character (headword style, primary color); radical → primaryMeaning (reading style, onSurfaceVariant); pre-answer fallback → target character or "Radical". Right side (only when subject exists): refresh IconButton (a11y "Refresh this page from WaniKani") → retry++; OpenInNew IconButton (a11y "Open this page on WaniKani") only when subject.documentUrl is an https wanikani.com URL → openWanikaniPage (host must be wanikani.com or a subdomain; FLAG_ACTIVITY_NEW_TASK; silent no-op without a browser).

**Body (scrollable per caller; compact=false uses Spacing.s/xl rhythm):**
- **Skeleton while loading:** static surfaceVariant blocks matching the page's shape (label 72dp, full-width line 14dp, 4 chips 28dp, paragraph of 3 lines with a 160dp tail, paragraph of 2) — no shimmer/animation; whole skeleton carries a11y "Looking up WaniKani…" so the state is still announced.
- **Available:**
  - Kanji: Meanings (caption "Meaning", primary meaning at gloss size, alternates joined " · " below) → On'yomi / Kun'yomi readings sections (joined "・", reading style, primary) → "Radicals" caption + horizontal row of ComponentChips (surfaceVariant, small shape, label = character glyph, else WaniKani name, else "…"; a11y "WaniKani details for the radical %label"; tap → onRadical(id)) → "WaniKani's meaning mnemonic" + "WaniKani's reading mnemonic" paragraphs in a SelectionContainer (markup already stripped by the repository; selectable text).
  - Radical: 72dp (56 compact) radical image — see WebView below — else its character, else its primaryMeaning, at headword style/primary; then Meanings + meaning mnemonic only.
  - Notes at the bottom (order: cacheFailure warning first, then cached, then stale): "Some details could not be saved for offline use." (error color) / "Saved on this device: readable offline." / "Showing the saved copy — WaniKani could not be reached just now." (muted). Distinct meanings: cacheFailure = page couldn't be written for this session; stale = saved copy shown because refresh failed.
- **NoEntry:** "WaniKani has no entry for %char." / "WaniKani has no entry for this radical." in a Notice card (surfaceVariant, Info icon; no action — nothing to retry).
- **Restricted:** "%char is above the levels your WaniKani account includes." / "This radical is above the levels your WaniKani account includes." — no retry button.
- **NeedsToken / AuthRejected:** "Connect a WaniKani personal access token in the Gaten app to see kanji and radical pages here." / "WaniKani rejected the saved token. Open Gaten to connect again." with filled Button "Open Gaten" → onOpenSettings (shell passes openSettingsPage("wanikani"); without a route, openApp() launches MainActivity with extra `settings_page="wanikani"` and FLAG_ACTIVITY_NEW_TASK).
- **Unreachable:** "Could not load this page from WaniKani: %failure." + Button "Retry" → retry++. Failure strings: no connection / the request timed out / WaniKani is limiting requests just now / WaniKani reported a server error / the answer was too large to keep / WaniKani sent something Gaten could not read / this device could not save the answer.

**Radical SVG WebView (must-copy behavior):** WebView with javaScriptEnabled=false, allowFileAccess=false, allowContentAccess=false, domStorageEnabled=false, blockNetworkLoads=true, zoom off, transparent background, no scrollbars, not clickable/focusable, `isEnabled=false`, WebViewClient that always overrides URL loading (cannot navigate). Document = `<!doctype html><meta viewport…><style>html,body{margin:0;padding:0;height:100%;background:transparent}body{display:flex;align-items:center;justify-content:center}svg{width:100%;height:100%}` + `svg{filter:invert(1)}` only in dark theme + `</style></head><body>` + raw SVG. Loaded via loadDataWithBaseURL(null, …, "text/html", "utf-8") only when the document differs from the view's cached `tag` (recomposing never reloads). On release: removeView from parent BEFORE view.destroy(). a11y = character or primaryMeaning.

---

## 14. Anki review screen (AnkiReviewScreen.kt + AnkiReviewViewModel.kt)

**Inputs (all from the shell-owned VM):** step: StateFlow<Step>, deck: AnkiDeck?, busy: Bool, expandedKanji: String?, expandedRadical: Int?. Step = Starting | Blocked(failure) | Decks(decks) | Session(card, token, buttonLabels[], mediaFiles[], startedAt, revealed) | Done(deckName) | Failed(failure, detail?, afterGrade). depth(): Starting/Blocked 0, Decks 1, Session 2, Failed 2, Done 3. `holdsSession(deck)`: Session always true; Failed true iff deck != null; Decks true iff deck != null (first-card wait); Done/Starting/Blocked false. Drives: bar visibility in the shell, onSessionActiveChange (fired only on change), and BackHandler enablement.

**Startup & lifecycle:**
- On first composition: if the current step doesn't hold a session → viewModel.refresh() (= clear deck, re-read status → decks; everything pre-session is stale).
- Activity ON_PAUSE → vm.onPaused() (records pausedAt during a Session); ON_RESUME → vm.onResumed(): if !busy and in a Session, re-read the queue (the card may have been answered in AnkiDroid meanwhile — nothing is ever re-sent) with `keep` = same noteId+ord → preserved revealed state + startedAt shifted by time away. The observer's onDispose also calls onPaused() (the screen can uncompose without a pause — e.g. contextual jump to Settings; destination transitions must NOT trigger refreshes, only the Activity's lifecycle).
- Permission request: granted → openDecks(); denied → permissionDenied() (Step.Blocked(PERMISSION)).

**Top bar:** title = deck name ?? "Review cards" (1 line); back arrow ("Back") → leave(); action TextButton "Change deck" (when deck != null, enabled !busy) → viewModel.changeDeck().

**leave() / Back:** if busy && sessionActive → do nothing (wait; the "Saving to AnkiDroid…" row explains). Session → confirmLeave dialog first: "Leave the review session?" / "Nothing is graded by leaving. The card you are on stays unanswered, and you go back to the deck list." / [Leave]→viewModel.changeDeck() (back to the picker, not out of the flow) / [Stay]. Failed&&sessionActive → changeDeck() directly. Otherwise → onClose() (= exitToHome). BackHandler registered only while sessionActive && isCurrentDestination (an outgoing scene must not guard Back).

**Step transitions:** AnimatedContent keyed on step with contentKey for Session = "noteId:ord:token:revealed" (so reveal and next-card are distinct page turns; keying on type alone would swap them silently). Transition: pageSlide(forward) where forward = (both Session → true) else targetState.depth() > initialState.depth(); plus a SizeTransform (clip=false, 380ms slideEasing) so the pinned actions don't jump.

**Step views:**
- **Starting:** BusyRow — surfaceVariant card, 18dp spinner + "Reading AnkiDroid…".
- **Blocked:** scrollable column: "Review unavailable" (titleLarge) + surfaceVariant info card with the failure sentence (not installed / permission / not set up / not found / malformed / provider) + one action: PERMISSION → filled "Allow AnkiDroid access" (min height 52); NOT_SET_UP → outlined "Open AnkiDroid"; else none.
- **Decks (picker):** "Choose a deck" + note "AnkiDroid keeps the schedule. Choose a deck to review what is due now." Empty → "AnkiDroid has no decks yet.". LazyColumn (selectableGroup) of DeckRows: whole card is the radio target (Surface selected, role RadioButton, pressScale; RadioButton is decoration with onClick=null); selected → primaryContainer; name (2 lines); FlowRow of CountTags "%d new" / "%d learning" / "%d to review" (surface bg) + "filtered" tag when deck.filtered; configured deck shows "Gaten exports here" (labelSmall, primary). Selection: preselected = configured deck ?? first; tapping the already-selected deck changes nothing (no tick); changing selection = Tick. Start button (filled, min 52, pressScale, enabled selected!=null && !busy) "Start reviewing" → viewModel.startDeck(deck) — an ordinary button, no tick.
- **Session:** see below.
- **Done:** centered column: 72dp circle primaryContainer with CheckCircle 36dp; "All caught up" (headlineSmall); "Nothing is due in %deck."; filled "Finish" → onClose; outlined "Change deck" (disabled busy) → changeDeck(). Cue: Confirm haptic + Done sound fire exactly on the transition into Done (seenStep guard — rotation/restore silent).
- **Failed:** "Something went wrong" + errorContainer card: message (PROVIDER with detail → "AnkiDroid could not do that: %detail" else failure sentence) + when afterGrade: "AnkiDroid may or may not have saved that answer, so Gaten will not send it again.". Buttons: filled "Retry"/"Next card" (retry when !afterGrade, next_card when afterGrade — the answer is never re-sent) → viewModel.retry(); PROVIDER → outlined "Open AnkiDroid"; always outlined "Change deck".

**SessionView layout:**
- Scrolling deck space (weight 1): verticalScroll, horizontal padding 16, top 16, min height = viewport−16, wrapContentHeight center (short question centers; tall card scrolls). Content order: CardSideTag ("Question" surfaceVariant / "Answer" primaryContainer chip) → Box: CardHtml + flash overlay (matchParentSize, animated alpha to 0.18 over FLASH_MS=250ms, half in/half out; tint = error for ease 1, primary for 2–4) → after reveal only: KanjiStrip(characters = distinct kanji of ALL card.fields, selected=expandedKanji, with viewport position capture) + nested AnimatedContent pages: kanji panel (pageSlide + pageResize SizeTransform so the strip never jumps; showBack=false, scrollable=false) then a nested radical panel (padding start 16 top 8; also showBack=false scrollable=false; its Back = onRadical(id) toggle-close).
- Scroll-into-view effect: keyed on `expandedKanji != null` (NOT which kanji — switching kanji keeps the scroll position while the skeleton holds layout). delta = stripViewportY − surfaceViewportY − 8dp; if > 0 animateScrollBy(delta, 300ms emphasizedDecelerate).
- Pinned action column (outside the scroller; navigationBarsPadding + padding h16 v16):
  1. mediaFiles non-empty → NoteCard "This card uses %d images or sounds. AnkiDroid does not share media files with other apps, so they are not shown here." + TextButton "Open AnkiDroid" (launch intent for AnkiDroid's package; silent failure).
  2. revealed && card.typedAnswer → NoteCard "This card expects typed input. Gaten shows the answer as AnkiDroid renders it, but the interactive comparison is not supported here, so rate it yourself.".
  3. !revealed → filled Button (min 52, pressScale, disabled busy) "Show answer" → viewModel.reveal() — NO haptic (the page slide + flash are the feedback).
     revealed → "How did that go?" (titleSmall) + RatingBar.
  4. busy → BusyRow "Saving to AnkiDroid…".

**Haptics semantics (exact):** Tick on deck selection change, on kanji/radical chip selection ONLY when it opens something new (tapping the open one closes it — no tick), and on submitting a rating (same Tick as a chip selection, no firmer variant). NO tick for Show answer. Confirm + Done sound on transition into Done.

**RatingBar:** button count = AnkiDroid's reported buttonLabels.size coerced 2..4; positional ease = index+1: 2 → Again(1)/Good(2); 3 → +Easy(3); 4 → Again/Hard/Good/Easy. Each FilledTonalButton weight(1), min height 64 (two lines may wrap per label), label (rating name, max 2 lines) over the interval string AnkiDroid computed for that position (labels[ease−1]). "Again" uses errorContainer/onErrorContainer; the rest the default tonal pair (accent container per theme). Tap → set flash tint (Again=error, others=primary) + onGrade(ease) → Tick + viewModel.grade(session, ease).

**Card WebView + CSS bridge order (exact — don't misstate):**
- View: javaScriptEnabled=false, allowFileAccess=false, allowContentAccess=false, domStorageEnabled=false, blockNetworkLoads=true, zoom off, useWideViewPort=true, background = theme surface argb (re-set on every update so theme changes repaint), no scrollbars, not clickable/focusable, isEnabled=false (the deck space's scroller owns the drag), a11y "Card", client always overrides URL loading. Document cached in `tag`; reload only when it changes; onRelease: removeView then destroy.
- Document (cardDocument): AnkiDroid shell `<body class="card cardN">` (N = ord+1) around `<div id="content" dir="auto">` with the card HTML, no scripts, no file:// stylesheets. THREE style blocks in this order: (1) app defaults FIRST — `html,body{background:transparent !important;margin:0;padding:0}`, `body{color:$onSurface;overflow-wrap:break-word}`, `#content{padding:1em}` + frontRule, `img,video{max-width:100%}`; (2) THEN the note type's own CSS (`<style>$css</style>`); (3) AFTER it, `#content{color:$onSurface}` again. So the note's CSS sits between the two app blocks: defaults it doesn't touch fall through to ours, its own colors win over block 1, and the re-stated #content color after it wins over the note type's base `.card{color:#111}` (a rule on the body is outranked by one on the content root), while anything the note styles *inside* #content (spans, `small{font-size}`) still wins over inheritance. Backgrounds are forced outright transparent so the Compose surface behind shows.
- frontRule (question side only): `#content{font-size:<displayMedium size>px;line-height:<lineHeight/fontSize ratio>}` — dp units in this viewport, theme's own sp-derived number (WebView already applies the system font scale; no second factor, nothing caps it), unitless line-height so it follows text scaling. Applied on #content (not body) so a note type's `.card{font-size:24px}` can't outrank it.

**VM calls (all guarded by ifIdle/busy):** refresh(), openDecks(), startDeck(deck) (sets deck, nextCard()), changeDeck() (clears deck, openDecks()), retry() (no deck → openDecks; else nextCard()), reveal() (pure state copy revealed=true), grade(session, ease) (elapsed = now − startedAt; AnkiReview.grade(token, ease, taken); Submitted/NotFound/Ignored → nextCard() with busy held through the hand-off; Failed → Step.Failed(afterGrade=true), busy false), toggleKanji(c) (toggles; resets expandedRadical=null), toggleRadical(id), closeDetails(), onPaused()/onResumed(). configuredDeckName read through on every ask (a changed export deck preselects correctly). nextCard(): fresh queue query → Due → card read → same noteId+ord as `keep` preserves startedAt/revealed; Empty → Done(deck.name); card read or queue failure → Failed(afterGrade=false). Each rating is sent once and only once.

---

## 15. Preference/settings keys each screen reads (for the RN bridge slices)

All in SharedPreferences file **"gaten_settings"**:
- ThemePreferences: `theme_mode` (System|Light|Dark, default System), `palette` (Gaten|Mono|Cool|Warm, default Gaten), `accent` (Indigo|Blue|Violet|Teal|Green|Rose|Graphite, default Indigo). Read by the activity theme + overlay + bubble.
- FeedbackPreferences: `feedback_sounds` (Bool, default false).
- TriggerPreferences: floating-button enabled flag, `tileAdded`, broadcast action `ACTION_FLOATING_BUTTON_CHANGED` (explicit-package broadcast required).
- AnkiPreferences (AnkiSettings): deckName, tags, modelId, front, back, css, modelFields, fieldSources.
- RecognitionSettings (RecognitionViewModel state, not prefs): installed/installedId/sizeBytes + download state.
- WaniKani: isConfigured/token via WaniKaniRepository (token verified before stored; cache cleared on disconnect).
- Platform reads (re-read every resume, never cached): lookup mode active (a live media projection, `GatenCaptureService.isLookupModeActive()`), notification listener access, AnkiDroid status/counts, app version.
