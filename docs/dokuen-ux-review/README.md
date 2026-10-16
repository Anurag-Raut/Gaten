# Dokuen UI/UX review — notes for the Gaten redesign

Reference study of **Dokuen Japanese Reader** (`io.github.dokuendev.dokuenreader`, v1.5.0),
installed and captured on an Android emulator (Pixel_Safe, Android 14, x86_64 with ARM
translation) on 2026-10-16. Purpose: the user likes how Dokuen looks and wants Gaten to feel
modern and rich rather than minimal. This file records what Dokuen does and what we should change
in Gaten.

Screenshots are in `shots/`. The live captures are numbered in capture order; the two `00-*.png`
files are Dokuen's own marketing images.

## How the app was obtained

- APK from APKPure (XAPK: base + `config.arm64_v8a` + `config.hdpi`).
- The base APK is protected by Google's **PairIP** license check
  (`com.pairip.licensecheck.LicenseActivity`). A plain `adb install` shows
  *"Something went wrong — Check that Google Play is enabled…"*.
- Working install (emulator has Play services + ARM translation):

  ```bash
  adb install-multiple -r -g -i com.android.vending \
    io.github.dokuendev.dokuenreader.apk config.arm64_v8a.apk config.hdpi.apk
  ```

  The `-i com.android.vending` install-source spoof is what satisfies PairIP.
- Screen capture used `adb shell screencap`. Dokuen deliberately marks its dictionary popup text
  as non-capturable, so the definition body appears blank in `34-home-session.png`; the card
  chrome, icons, and toolbar are visible.

## What Dokuen is

A system-overlay immersion reader: furigana rendered over kanji in **any** app, tap-to-look-up
dictionary, camera OCR, modular OCR/dictionary plugins (Yomitan, JPDB), video auto-pause, and
Anki export. Very close in scope to Gaten.

---

## Screen-by-screen observations

### Onboarding (`02`–`06`)

- Welcome screen: big centered logo, one sentence, one **Next** button. No clutter.
- Furigana style step (`03`): segmented control (ひらがな / カタカナ / Romaji) with a **live
  Japanese preview** updating above a **Next** button.
- Text direction step (`04`): two large illustrated cards (horizontal/vertical) with the actual
  Japanese word inside each card, selected state = tinted card + border.
- "What's New" page (`05`, `06`): changelog with a star icon, bolded feature headings, a "NEW"
  accent, example Anki-card images, and **interactive color-theme swatches** that repaint the UI.
- Every step is one decision, stated as a question ("How do you want your furigana to appear?",
  "What will we be reading today?"), with "You can change this later in Settings" reassurance.

### Home (`08`)

- Brand calligraphy logo centered, settings gear top-right.
- **screen / camera** mode toggle as two rounded square buttons.
- One huge circular gradient **Start** button — the single primary action.
- Bottom grid of ~12 small icon tiles: theme, visibility, text direction, あ, scan, dictionary,
  tap gestures, Anki (star), video, language flag, かな/かな toggle, link, play.
- A first-run coachmark highlights the text-direction control with a sentence of explanation.

Reading: this is a **launcher/control panel**, dense but every control is a recognizable icon in
a consistent tile. It is not minimal. Its weakness is that nothing is labelled — the tiles rely on
icon literacy.

### Settings hub (`10`, `11`, `12`)

- Grouped sections with small accent-colored headers: **Account**, **Appearance & Display**,
  **Scanning**, **Lookup**, **Help**, **About**.
- Every row is a large rounded card: a **colored circular icon container**, a title, a one-line
  current value as subtitle (e.g. "ひらがな", "Show All", "On-Device", "Enabled"), and a chevron.
- The icon container carries semantic color (pink Pro star, blue Anki star, orange bug, pink
  heart), while the card surface stays neutral.
- Rows are generous: ~72dp tall, lots of side padding, clear tap targets.

This is the strongest screen in the app and the clearest model for Gaten.

### Anki Integration (`13`)

- A full-width **purple info banner** with a "NEW" pill, a bolded sentence, a **Install Yomitan
  Plugin** text action, and a footnote.
- Then a plain toggle row, a labeled text field with a small **PRO** lock badge, and radio groups.
- Long explanatory text is allowed to breathe; it is never crammed into a subtitle.

### Furigana Appearance (`15`) and Display Mode (`16`)

- Segmented control + sliders with a large **live preview on real Japanese text**.
- Sliders are framed by min/max glyphs (small あ … large あ) so the meaning is visible.
- Display Mode adds a **Known Words** section with an empty state that explains how to populate it
  ("In Show All mode, long-press a word in the reader to add it here.").

The live preview is the single best pattern in the app: every visual setting shows its own result
before you leave the screen.

### Scan Mode (`17`)

- Three full cards (On-Device, Cloud, Custom). The selected card is tinted; PRO cards are locked
  with a padlock and a PRO badge.
- Each card lists capability icons on the left and a "Best for:" bullet list on the right.
- Choosing is a comparison, not a dropdown.

### Lookup Gestures (`19`)

- Radio groups whose options are explained with a concrete Japanese example
  ("Tap 「族」in 「水族館」to look up 水族館.").
- This is how configuration for an abstract behavior should read.

### Plugin Manager (`20`)

- Tab row (OCR Plugins / Dictionary Plugins), plugin card with icon, name, version/author,
  description, and two outline buttons (Info / Configure), then a filled **Get more plugins**.

### Permission education (`23`)

- Before the system MediaProjection dialog, an in-app card explains **"select Share one app, not
  Share entire screen"**, with a "Don't show again" checkbox and an **I Understand** button.
- After granting, home switches to a **Stop** state with a one-line instruction: "Switch to the
  app you want to read then tap the floating button to activate furigana overlay." (`24`).

### Live overlay (`26`, `27`, `34`)

- A draggable purple circular **読** floating button.
- Furigana rendered as small ryaku-tone badges above each kanji across a third-party app.
- The dictionary result is a **floating rounded card anchored near the tapped word** with:
  a drag handle, the definition body (non-capturable), a divider, and a bottom action toolbar:
  pronounce, star (Anki), copy, share, and a wrench (settings). Close **X** top-right.
- The word panel is a card, not a full-height bottom sheet.

### Dokuen Pro (`35`)

- Hero logo + pink **PRO** pill, a "Benefits" card of icon rows, and two side-by-side plan cards
  (Subscription / Lifetime) with outline/selected treatment.

---

## What Gaten should improve (working list)

Gaten's current design is deliberately near-monochrome with a single accent and flat surface
containers (`HomeDashboard.kt`, `SettingsScreen.kt`, `ui/theme/Color.kt`). It is coherent and
accessible but visually quiet. The gap to Dokuen is **richness of surface and component**, not a
lack of features.

### 1. Give settings rows the Dokuen card treatment (highest value, low risk)

Gaten's settings rows are flat; Dokuen's are large rounded cards with a **colored circular icon
container**, a title, a current-value subtitle, and a chevron. Adopt:

- `Surface`/`Card` with `surfaceContainerHigh` and `shapes.large`, one card per row (not dividers).
- A 40dp circular icon chip tinted by **semantic** color (feature category), not the single accent.
- A real subtitle that shows the **current value** ("On-Device", "Deck: Core 2k", "ひらがな").
- Generous vertical padding (~72dp rows) and full-row tap targets.

This is the change that most directly answers "modern and easy to use, not minimalistic".

### 2. Add live previews to appearance/settings screens

Every visual preference (font size, furigana style, theme, overlay density) should render a
**sample Japanese sentence** that updates immediately, plus min/max framing on sliders. Gaten's
Appearance page currently relies on abstract labels.

### 3. Introduce a component vocabulary beyond the accent

- **Banners**: full-width tinted info cards for "what's new" / important setup (Dokuen's Anki
  banner), with a small **NEW** badge.
- **Badges/pills**: PRO/lock/status badges on rows.
- **Segmented controls**: for small mutually exclusive choices.
- **Comparison cards**: for choosing OCR/model engines, with capability bullets and "Best for".
- **Richer count tiles** on the review card (already present) — keep and enlarge.

Keep the existing accent + palette system; add *surface depth* (container tones, subtle borders,
elevation) rather than more hues.

### 4. Home: keep the structure, raise the visual weight

Gaten's home is already search hero + review card + lookup card, which is the right information
architecture — better than Dokuen's unlabelled tile grid. Improvements:

- Make the search hero feel like a real search field (filled, taller, icon + placeholder).
- Make the review card the visual anchor (larger numbers, tinted when due — already partly done).
- Do **not** copy Dokuen's 12-icon tile grid; instead keep 3 clear labelled cards.
- Consider a single clear primary CTA for the most common action.

### 5. Lookup overlay / result sheet

- Keep the card/panel anchored near the selection.
- Adopt an explicit **action toolbar** row (pronounce / add-to-Anki / copy / share / settings)
  with icon + short label, separated from the definition by a divider.
- Strengthen the headword hierarchy: large Japanese headword, reading, then meanings.
- Keep the non-capturable/privacy behavior in mind; make the panel readable at large text sizes.

### 6. Permission and onboarding education

- Add a **pre-permission explainer** before the accessibility/overlay system dialog, in Gaten's
  own voice, with "Don't show again".
- Turn first-run into a short question-based flow (what will you read? how should furigana look?)
  with live previews.
- Add an in-app "What's New" page for releases; it doubles as feature discovery.

### 7. Things to avoid

- The unlabelled icon tile grid — icon guessing hurts discoverability.
- Very long explanatory paragraphs inside small rows.
- Copying Dokuen's exact purple/rainbow theming; Gaten has a better principled palette system.
- Overloading home with one-time setup actions.
- Locking core function behind PRO without explanation; Gaten should keep its free/paid line clear.

### Open questions

- How far to push color: Dokuen tints whole cards (purple banners, pink badges). Gaten's system is
  monochrome + one accent. A middle path is semantic tinting of icon chips/badges only, keeping
  card surfaces neutral.
- Whether to adopt a floating-card lookup or keep the bottom sheet; the floating card reads better
  on wide/landscape but is harder to keep accessible.


---

## Advisor review (GLM-5.3-Flash via OpenRouter)

_A second opinion from a fresh GLM-5.3-Flash agent, given the Dokuen screenshots and Gaten's UI source. Analysis only; no files were changed by the advisor._

## Overall verdict

Gaten's theme system (measured luminance, retinted palettes, one accent, no Bold on CJK) is better engineering than Dokuen's, and you should not trade it away. Dokuen's "richness" is not actually richer color — it's **more depth in the surface ladder, louder icon treatment, gradients on 2–3 hero elements, badges/banners, live previews, and a bigger type scale**. Every one of those can be achieved *inside* Gaten's monochrome+accent system using tokens you already have (`AccentRoles.primary` + `tertiary` is already a gradient pair). The work is additive: new reusable components, not a retheme.

---

## 1. Visual language — what Dokuen has that Gaten lacks

Reading your code against the screenshots, Gaten is flat in five specific ways:

**a. Icon containers are heavy but not rich** (`SettingsRowIcon`). A 40dp *square* with full-strength `primary` fill on every row reads as noise *and* flatness at once — the accent is at maximum saturation everywhere, so nothing stands out. Dokuen uses **circles**, slightly larger, with varied tint per section. Change: `CircleShape`, 44–48dp, and consider `primaryContainer` fill with `onPrimaryContainer` icon for most rows, reserving solid `primary` for the one row you want lit (Screen Access when off, Anki when needing permission). Icon containers that *carry state* is the single cheapest "rich" win.

**b. No gradient anywhere.** Dokuen's Start circle and floating button are the two loudest things in the app, and both are just `linearGradient(primary → tertiary)`. Your `AccentRoles.tertiary` is already luminance-corrected per accent — so a `Brush.linearGradient(listOf(primary, tertiary))` token inherits your contrast guarantee for free. Use it on **at most two** elements (see Home below and the overlay bubble). Gradients everywhere is how you get Dokuen's dated look; gradients on heroes is how you get its appeal.

**c. No badge/banner components.** You have `LabelChip` and that's it. Dokuen leans hard on: PRO/NEW pills, a purple info banner with icon + bold spans + inline action + footnote. Build two primitives once and reuse everywhere:
- `GatenBadge(text, icon?)` — pill, `primary` or `errorContainer` fill, `labelSmall`.
- `InfoBanner(icon, title, badge?, body: AnnotatedString, action?, footnote?)` — `primaryContainer` card, icon + title row, bold-highlighted spans (`.boldSpanStyle(SpanStyle(color = primary))` in dark mode), footnote below a hairline. Your Anki settings, model-download state, and disclosure all become users of it.

**d. No live previews.** This is Dokuen's best idea. Every appearance decision (furigana style, size, capture button, display mode) is shown *rendered for real*, in a labeled preview card, before you commit. Gaten's appearance/furigana settings (I read the hub; the pages presumably show swatches) should each end in a `PreviewCard(label = "Preview") { actual rendering }` — for you that's furigana over sample text, the overlay bubble, the result sheet. It's also honest in your codebase's sense: the preview *is* the real composables at real values.

**e. Type scale stops too early.** Page titles use `titleLarge` (24sp). Dokuen's hub titles are ~34sp. You already have `headlineSmall` (26sp) — use it for hub/page titles (Home, Settings, detail pages), keep `titleLarge` for cards. Section headers in Settings go from `labelMedium` → `titleSmall` with `color = primary` and a touch more top spacing. Zero risk, immediate presence.

Where Gaten is *not* flat and shouldn't be "fixed": the numbered glosses in primary, the word-chip selection (fill + shadow), the due-state accent on ReviewCard, `heightIn`-not-`height` discipline, merged semantics. Dokuen would fail your own review.

## 2. Home

Gaten's content model (Search hero, Review, Lookup) is better than Dokuen's because every card states real state. What to steal is *treatment*, not structure:

- **Brand moment.** Dokuen opens with a big calligraphy mark. You open with 24sp "Gaten". Add a lockup: a kanji-style glyph mark (your own asset, not calligraphy imitation) or the app name in `displaySmall`/`headlineSmall`, plus a subtle accent element (gradient underline bar or tinted glyph). Low effort, changes the first impression most of anything.
- **SearchHeroCard**: keep as one button. Make the inner fake-field pill taller and give the search icon a gradient-filled circular container — it's your "Start button equivalent."
- **ReviewCard**: keep the container-swap-on-due logic. Add the gradient only here, on the *due* state (gradient container or a gradient-filled primary Button for "Review"), and bump the count tiles (`displaySmall` numbers). A glance should now answer "is there work" from across the room.
- **LookupCard**: replace the 10dp dot with a status pill ("ON"/"OFF" badge) and give the card an icon container like Settings rows, for family resemblance.
- **Don't add the 12-tile grid.** Tiles that duplicate settings without showing state are Dokuen's worst habit (see §6). If you ever want a quick row, cap it at 3–4 *stateful* tiles (floating button toggle, theme, deck) with labels — and I'd defer it entirely; it's medium impact for real effort.

## 3. Settings

Your hub is structurally close to Dokuen's already (icon + title + subtitle + chevron, grouped). Highest-value changes in order:

1. **Circle icon containers with state-tinting** (as above). Solid `primary` square → circle; error-container fill when a row's feature is broken/unconfigured (Anki missing, model missing, WaniKani off). That's *information*, not decoration.
2. **Colored, larger section headers** (`titleSmall`, accent).
3. **Row breathing room**: ListItem is dense; add `Modifier.padding(vertical = Spacing.xs)`-ish inside rows so the hub reads as Dokuen's tall cards without adopting one-card-per-row (which wastes vertical space across your 8+ rows and loses the inset-divider craft you already have — keep grouped cards).
4. **Trailing status badges** on 2–3 rows where a pill beats a sentence: e.g. a small "5.4 MB" chip on Recognition, "ON"/"OFF" on WaniKani. Keep summaries for everything else.
5. Keep `ScreenAccessCard` as the first-class status card; give it the icon-container + banner treatment.

## 4. Lookup overlay / ResultSheet

Your sheet is functionally ahead of Dokuen's card (chips, other matches, WaniKani stack, pinned Add). Borrow three things:

- **Float feel.** The sheet is painted on flat `surface` with no shadow. Add `shadowElevation = 12.dp` (and optionally `surfaceContainerLow`-ish container) so it reads as a card floating over the scanned app, like Dokuen's. One-line change, big perceived-quality delta.
- **Header/toolbar split.** Dokuen separates *identity* (drag handle + close X) from *actions* (toolbar row). Yours crams OCR text + copy + select-again + close into one row. Restructure: handle bar (you have it), then header row = OCR text + close X at the end, then a compact icon action row (copy, select again, and — new — TTS pronounce if you ever add it), then chips. Keep the 40dp close target.
- **Pronounce/star affordances**: Dokuen's toolbar (speaker, star, copy, share) is a good mental model. Star already exists as the pinned Add button — don't duplicate it in the toolbar. Speaker (TTS) is the one genuinely missing action; it belongs in the header row.

Don't copy Dokuen's tiny floating card near the word: your docked sheet with scrim keeps selection context and avoids covering the word being looked up.

## 5. Onboarding & permission education

- **Pre-permission explainer**: you already have `AccessibilityDisclosureDialog`, but it's a stock AlertDialog. Dokuen shows a rich card *over the home screen* before the system dialog, with the key instruction bolded ("Share one app"). Restyle yours into an `InfoBanner`-style centered card: icon in a circular tinted container, title, body with bolded key phrases, "Don't show again" checkbox retained (you're already better than Dokuen here by requiring consent — keep that). Same component reused for the overlay's first-run state.
- **What's New**: worth building as a page (About → What's New, auto-shown once per version via `versionName` in prefs). Structure per Dokuen: version in header, per-feature section = icon + title + bullets with bold/accent spans. Skip the embedded screenshots (effort, and they date quickly).
- **First-run onboarding**: nice-to-have, do last — a 2–3 step pager (what Gaten does → enable screen access → connect AnkiDroid/WaniKani, each step ending in the real action, all skippable). Your current "honest cards state what's missing" model already onboards implicitly; a light explicit pass on top is polish, not a need.

## 6. Things to avoid in Dokuen

1. **The 12-tile grid**: tiny unlabeled icon soup (あ, a flag emoji, a kanji), inconsistent labels, no state shown, poor a11y, hierarchy drowned by one giant Start button.
2. **Flag emoji as a language switch** — reads as clip-art; use a proper locale/globe icon.
3. **Multi-hue icon fills** (pink Pro circle, teal star) — it breaks the one-accent discipline that Gaten already has and that makes Dokuen's screenshots look slightly marketplace-y. Rich ≠ many hues.
4. **Bold-accent overload**: the What's New / Anki banner bolds and colors half the words in every sentence; emphasis that frequent stops meaning anything. Bold sparingly.
5. **PRO lock badges inside settings fields** — dark-pattern monetization; not applicable to a GPL app, and the visual language of locks-as-decoration shouldn't leak in.
6. **The cyan furigana preview** (#00E5FF-ish on dark) — harsh, questionable contrast; your measured-luminance rule is the correct instinct. Don't import their preview colors.
7. **Calligraphy brand mark** — it's Dokuen's identity asset; make your own mark rather than imitating brush lettering.
8. **Segmented control with a checkmark inside the selected segment** — mildly redundant but harmless; adopt `SingleChoiceSegmentedButtonRow` (M3 has it) if you add segmented pickers, with the checkmark optional.

## Prioritized roadmap (impact vs. effort)

| # | Change | Impact | Effort |
|---|--------|--------|--------|
| 1 | Gradient accent token (`primary→tertiary` brush); apply to ReviewCard-due + SearchHero icon + (later) overlay bubble | High | XS |
| 2 | Settings: circular icon containers w/ state tinting; `titleSmall` colored group headers; extra row padding | High | S |
| 3 | `GatenBadge` + `InfoBanner` primitives; adopt in Anki settings, disclosure | High | S |
| 4 | Hub/page titles → `headlineSmall`; Home brand lockup | Med-High | XS |
| 5 | ResultSheet: shadow elevation + header/toolbar split | High | S |
| 6 | Live `PreviewCard`s in appearance/furigana/overlay settings | High | M |
| 7 | Status pills (ON/OFF, MB) on home lookup card + settings rows | Med | S |
| 8 | Restyled pre-permission explainer card (reuse #3) | Med-High | S |
| 9 | What's New page + version trigger | Med | M |
| 10 | First-run onboarding pager; home quick-tile row | Med | M–L |

**Recommendation**: do 1–5 first as a single "visual language" pass — they're all small, they compound (gradient + badges + circles + bigger titles together produce the "modern and rich" feel the user is asking for), and none of them touches your contrast or semantics contracts. Then 6–8, where the real UX wins live (previews and permission education). Keep 9–10 in the backlog. The end state is Dokuen's warmth and depth with Gaten's discipline — which is a strictly better app than either Dokuen or today's Gaten.
