# Gaten / Ink — Design Language

**Version 1.0 · 21 October 2026 · Mobile product design system**

**Status:** Approved visual direction, implementation specification and accessibility refinements. Reference screens: Home (Ink rows) and Settings (short-copy).

**North star:** A quiet study instrument: Japanese lookup and review first; no decoration competing with the learning task.

# 1. Design philosophy

**Ink is a deliberate, minimal interface—not a card kit.** The visual system gives hierarchy to Japanese search, today’s study needs, and fast access to connected tools. It should feel closer to a focused reader or productivity workspace than to a playful learning game.

- **Content over containers.** Use simple rows and 1 px rules as the default. Cards are reserved for groups that benefit from a bounded surface.
- **Nearly monochrome.** White/graphite in light mode; deep charcoal/off-white in dark mode. Violet is a signal, not a background wash.
- **Dense, never cramped.** Keep the scan path short, with predictable 8/12/16/22/24 px intervals and generous touch targets.
- **Brand color belongs to the brands.** Keep Anki blue and WaniKani pink confined to their existing app marks; adjacent UI stays neutral.
- **One strong action per screen.** “Start studying” is the only dominant filled action on Home.
- **Quiet motion and language.** Avoid glow, glass effects, gradient fields, playful microcopy, arbitrary illustrations, animated counters.

Signature element: the **Ink integration row**—real brand mark, bold name, quiet status, right-aligned outcome, hairline separator. The rest of the screen supports this structure.

# 2. Source-of-truth and product rules

**Canonical references**: (1) Home in “Ink rows” mode (not split cards by default); (2) Settings in Ink with short descriptions; (3) the original floating rounded bottom bar with dark selected pill. The later edge-to-edge black dock is **rejected** and must not be used.

The standalone HTML was a static visual mockup: system status bar, gesture bar, Anki/WaniKani logo paths, and 380 × 770 preview shell are not production assets or platform APIs. The native app’s existing icons, routes, real data, and Android insets take priority.

# 3. Color system

## 3.1 Light mode — approved visual tokens

| Role | CSS variable | Hex | Usage |
| --- | --- | --- | --- |
| canvas | --ink-bg | #FDFDFD | Main page/background |
| primary text | --ink-fg | #17171A | Headlines and body copy |
| secondary text¹ | --ink-muted | #83838C | Approved visual baseline; small text needs contrast correction |
| fine detail | --ink-fine | #A1A1AB | Unselected markers / decorative icons |
| separator | --ink-border | #E9E9ED | Hairline outlines / rules |
| subtle surface | --ink-surface | #F6F6F8 | Search field, muted icon chips |
| raised surface | --ink-surface-2 | #FFFFFF | Settings rows / optional split cards |
| violet accent | --ink-accent | #7564D9 | Selected day, flame, optional focus states |
| active fill | --ink-active-bg | #18181B | Primary CTA, selected nav item |
| active on-fill | --ink-active-fg | #FFFFFF | Selected navigation label / CTA |
| success | --ink-success | #16A476 | Connected status dot |
| warning | --ink-warning | #DC6673 | Limited use: failures or actions requiring attention |
| bottom nav | --ink-bottom | #F5F5F6 | Original floating bottom navigation container |

## 3.2 Dark mode — paired tokens

| Role | CSS variable | Hex | Usage |
| --- | --- | --- | --- |
| canvas | --ink-bg | #101114 | Main page/background |
| primary text | --ink-fg | #F5F5F6 | Headlines and body copy |
| secondary text | --ink-muted | #909099 | Secondary information |
| fine detail | --ink-fine | #777781 | Decorative details |
| separator | --ink-border | #303136 | Hairlines/outlines |
| subtle surface | --ink-surface | #1B1C21 | Search field and chips |
| raised surface | --ink-surface-2 | #17181C | Settings rows / optional cards |
| violet accent | --ink-accent | #B0A0FF | Selected / highlighted meaning |
| active fill | --ink-active-bg | #F5F5F7 | Primary CTA, selected nav item |
| active on-fill | --ink-active-fg | #17171A | Label / icon on selected fill |
| success | --ink-success | #53C99B | Connected status dot |
| warning | --ink-warning | #EF8A96 | Warnings/attention |
| bottom nav | --ink-bottom | #1C1D21 | Original floating bottom navigation |

## 3.3 Accessible text refinements

The approved light `--ink-muted` (#83838C) on #FDFDFD measures approximately **3.69:1**, below the 4.5:1 WCAG AA threshold for normal-sized text. Keep it for noncritical/large decoration if desired; use **#6C6C75 (~5.11:1)** for small explanatory text, field hints and section labels. Recommended variable: `--ink-muted-readable`.

For dark mode, #909099 on #101114 is approximately **5.97:1** and meets the normal-text threshold. Low-contrast fine-detail icons should not be the only way to communicate status. Colored success/warning dots must be accompanied by words.

Never use brand pink to color an entire WaniKani card or brand blue for an entire Anki card; use those colors only in the marks. The violet accent is for active/selected state and may be omitted if it adds noise.

# 4. Typography

Preferred Latin: **Inter** with native fallback (`-apple-system`, `BlinkMacSystemFont`, `Segoe UI`, `Arial`, sans-serif). Japanese: use platform-native Japanese glyph fallback (e.g. Noto Sans CJK JP / Noto Sans JP where installed). Avoid faux condensed, decorative, or serif headline styles. Weight 745 in HTML prototypes is a variable-font value; use 700–750 where supported, otherwise 700.

| Role | Reference CSS size | Weight | Tracking | Line height / notes |
| --- | --- | --- | --- | --- |
| App/page title | 35 px | 745 | -1.85 px | 1.0–1.08; single line |
| Section heading | 17 px | 710 | -0.57 px | 1.25 |
| Row title | 15 px | 680 | -0.45 px | 1.2–1.35 |
| Primary CTA | 15 px | 660 | -0.20 px | 1.2; centered |
| General / input text | 14 px | 400–500 | 0 | 1.4 |
| Page description | 13 px | 400 | 0 | 1.45–1.5 |
| Row meta/status | 11 px | 400–550 | 0 | 1.3 |
| Section label | 12 px | 650 | -0.10 px | 1.3 |
| Weekday label | 10 px | 400–670 | 0 | 1.2 |
| Primary numeric due | 25 px | 710 | -1.20 px | 1.0 |
| Nav active label | 11 px | 650 | 0 | 1.2 |

**Production accessibility adjustment:** the small 10/11/12 px labels match the snapshot but should be treated as visual starting points—not fixed text sizes on Android. Use scalable sp sizes, test system font scaling 100%, 130%, 160% and increase compact descriptions to 12–14 sp if needed. Truncate only genuinely nonessential copy.

# 5. Layout & spacing

A single-column mobile layout. Content aligns to a shared left edge. The original prototype uses **22 px** horizontal content insets within a **380 px** preview shell (20 px for narrow previews). Use platform dp units for the native layout; do not scale the entire mockup as an image.

| Token / dimension | Value | Application |
| --- | --- | --- |
| space-1 | 4 | Icon / micro-gap only |
| space-2 | 8 | Small internal gap |
| space-3 | 12 | Between icon and text / settings |
| space-4 | 16 | Default component padding |
| space-5 | 20 | Compact section separation |
| space-6 | 24 | Major section separation |
| content inline inset | 22 | Home and Settings shared left/right grid |
| hairline | 1 | Rule and understated border |
| icon touch area | ≥48 dp | Regardless of icon painted size |
| phone shell | 380 × 770 px | Reference mockup only; never hardcode viewport height |

Default sequence on Home: system bar → 23 px gap → Gaten heading → 21 px gap → search → 25 px gap → streak → integration rows → 22 px gap → primary CTA → 34 px gap → recent lookups → anchored nav. When the viewport is short, body scrolls; the bottom navigation remains visible and must not overlap content.

## 5.1 Shape and elevation

| Element | Radius | Border / elevation |
| --- | --- | --- |
| Search field | 14 px | No visible border; muted neutral fill |
| Ink integration rows | 0 | No card container; 1 px bottom separator |
| Brand logo holder | 10 px | Neutral brand-adjacent surface; no drop shadow |
| Primary CTA | 13 px | Flat dark/light fill; no gradient |
| Settings row | 17 px | 1 px hairline, white/dark surface; no regular shadow |
| Floating bottom bar | 20 px | Neutral background |
| Selected nav item | 15 px | Dark-on-light / light-on-dark filled pill |
| Optional split integration tile | 15 px | 1 px border; only if the product needs equal destinations |

Shadows are intentionally absent inside the app. A soft shadow around a marketing mockup device is presentation-only. Use separators, whitespace and filled active states for hierarchy.

# 6. Home screen specification

## 6.1 Search

49 px tall; 14 px radius; surface fill; 15 px inline padding; 12 px gap; ~19 px search icon. Placeholder `猫, ねこ, neko...`. Use the real input keyboard action and focus semantics. The full field is tappable, not just the icon.

## 6.2 Daily streak

This is a **section**, not a tinted feature card. Top row: 23 × 27 px single-color flame, `Daily streak` (15 px bold), one short supporting line, `—` and `total days` right-aligned. Do not add inspirational copy. Below: seven evenly spaced weekday markers (29 px visual circles, 10 px weekday names), with 20 px top space and 8 px marker-to-label space. A 1 px line separates streak from integrations.

State semantics: completed = small violet solid fill or check, current day = violet outline, missed = muted non-filled marker, future = subtle neutral outline. Never indicate state through color alone; expose `completed`, `today`, `missed`, or `upcoming` to accessibility. A missed day is not labeled complete.

## 6.3 Ink integration rows — default

Rows for **Anki** and **WaniKani**, full width; 14 px vertical padding; 13 px gap; 1 px bottom divider. Left: 37 × 37 px rounded brand-mark holder. Center: 15 px/680 brand name and 11 px status. Right: key action or count and 15 px chevron. The entire row is a minimum 48 dp touch target.

| Feature | Connected example | Disconnected example |
| --- | --- | --- |
| Anki | Anki / Connected; `9` and `reviews due` on right | Anki / Not connected; `Connect` on right |
| WaniKani | WaniKani / Connected; appropriate upcoming count | WaniKani / Not connected; `Connect` on right |

Status is **not** an additional button or decorative pill; show a small success dot plus label. A disconnected state uses a neutral dot and the label `Not connected`. Do not duplicate a brand mark as an illustration or watermark in the row. Prefer real in-app logos; the sample SVGs were rough stand-ins.

## 6.4 Optional split-card arrangement

Alternative only. Two equal-width columns with 11 px gap; tiles about 152 px tall; 12 px padding; 15 px radius and 1 px outline. Useful if destination parity becomes a product requirement. **Do not switch to it by default**, because Ink rows are the preferred selection and use less vertical space.

## 6.5 Main action

`Start studying` is 52 px tall, 13 px radius, flat `active fill`, 15 px/660 centered label, and 18 px chevron near the trailing edge (16 px inset). Only one primary filled CTA per screen. Disabled, loading, focus and pressed states must be implemented; avoid glows, gradients, or bounce motion.

## 6.6 Recently looked up

17 px/710 heading; when empty, show a quiet 39 × 39 px search symbol plus **No recent lookups** and **Words you search will appear here.** Align icon/text in a compact horizontal row, rather than a large vertically centered blank-state illustration. With history, show recent words as simple linked rows, not large cards.

# 7. Settings screen specification

Use the **same font, ink colors, content inset, hairlines and nav** as Home. Header: `Settings` at 35 px/745, with a single 13 px muted sentence (`Manage your Gaten preferences.`). Avoid paragraphs before every control.

Groups: **Lookup / Study / App**. Group label 12 px/650, about 10 px above group. Settings items are 78 px minimum visual height, 17 px radius, 1 px border, approximately 12–13 px vertical/horizontal padding, 38 px neutral icon tile, 14 px title, optional 11 px micro-description, right-aligned value, chevron. Item separation 10 px; group separation 23 px.

| Group | Setting | Approved concise text | Right-side value |
| --- | --- | --- | --- |
| Lookup | Screen access | Allow screen reading | Off |
| Lookup | Screen lookup | Button, tile & shortcut | Chevron |
| Lookup | Text recognition | Stylized text model | None |
| Study | Anki cards | Deck & card layout | Gaten |
| Study | WaniKani | Kanji & vocabulary | Not connected |
| App | Appearance | Theme preferences | System |
| App | Account | Profile & sign-in | Chevron |

**Editorial guidance:** Some second lines can be removed entirely when the title/value already explains the destination. Keep status and important limitations explicit; never hide an actual security permission behind vague copy. Use `Screen access` with `Off` in the closed state; full permission details belong to the drill-in screen, not this list.

Scrollable settings list is separate from the anchored bottom navigation. On small screens do not crop the Study or App groups behind the nav. Use actual accessibility labels for each row: title + current value + action.

# 8. Floating bottom navigation — approved / do not replace

The **approved bottom bar** is the earlier neutral floating capsule. Not the later black full-width dock. Home, Stats, Profile and Settings use the same bar; only selected state changes.

| Part | Reference value | Behavior |
| --- | --- | --- |
| Track | 63 px height, 20 px radius | Background = `--ink-bottom`; horizontal content width |
| Track inner padding | 6 px | Balanced inset on every side |
| Tabs | 46 px visual height | Unselected icon only; center aligned |
| Selected pill | 15 px radius | `--ink-active-bg` fill, `--ink-active-fg` icon/text |
| Selected width | ~1.55× regular tab flex | Enough room for icon + active label |
| Icon | 20 px, 1.8 px stroke | Matching set on all pages |
| Selected label | 11 px, 650 | Home/Stats/Profile/Settings; no truncation |
| Gesture indicator | System-provided | Preview drew a 94 × 4 px mock bar; do NOT duplicate natively |

For Android, use actual **WindowInsets**. Keep touchable navigation above the gesture/system-nav area. On focus, font scaling or narrow devices, allow the active label to remain readable; increase tap targets to ≥48 dp even if icon/pill visuals are 46 px tall.

# 9. States, feedback and motion

| State | Treatment | Content behavior |
| --- | --- | --- |
| Rest | Ink text / neutral surfaces | No decorative animation |
| Pressed | Subtle opacity or overlay change | Immediate response, 100–180 ms recommended |
| Keyboard focus | 2 px accent outline with visible offset | Never remove focus indicator |
| Selected | Filled pill or meaningful violet marker | Also accessible selected state |
| Disabled | Reduced prominence and semantic disabled | Maintain enough contrast to communicate state |
| Loading | Stable dimensions, one compact progress affordance | Prevent repeated submission |
| Error | Plain-language issue + next action | Do not rely solely on red |
| Empty | One reason + next useful action | No mascot/large illustration |

Favor zero ambient animation. Animate transitions only to explain the result of an action. Respect `prefers-reduced-motion` for web and reduce motion settings in native implementations.

# 10. Accessibility, density & native implementation

- **Contrast:** WCAG AA minimum 4.5:1 for typical text; 3:1 for large text. Use `--ink-muted-readable` for small light-mode descriptions. Evaluate text contrast after every palette change.
- **Touch:** ≥48 × 48 dp effective targets, optionally 8 dp spacing when adjacent. Tiny 20 px icons may sit inside larger accessible hit zones.
- **Scaling:** layout in dp (React Native logical layout units), font sizes in sp / scalable text; do not assume 1 image pixel = 1 device pixel.
- **Insets:** account for status bar, display cutout, bottom gestures, keyboard and landscape; fixed bottom tabs must never obscure content.
- **Semantics:** label non-text icons, current tab, streak status, connection state and review due count for screen readers.
- **Color:** differentiate completed/ missed/ current using shape, icon or label in addition to color.
- **Text:** reserve two lines for optional setting descriptions at high font scale; no permanent fixed-height clipping.
- **Translations:** Japanese text must wrap sensibly; test mixed script fonts and glyph fallback.

Recommended breakpoint behavior: at narrow widths (<360 dp) use 16–20 dp content insets and shorter metadata; on wide devices cap content width at approximately 480–560 dp instead of stretching rows edge-to-edge. Reference mockup sizes are not hardcoded constraints.

# 11. Implementation-ready tokens

The following variables are extracted from the approved HTML prototypes. See the companion `gaten-ink-tokens.css` for a copy-paste version.

```css
/* Gaten Ink — design tokens v1.0 (2026-10-21)
   Reference implementation: CSS px are MOCKUP specs, not native Android px.
   In React Native use dp for layout and sp for text via platform conventions.
*/
:root, [data-gaten-theme="light"] {
  --ink-bg: #fdfdfd;
  --ink-fg: #17171a;
  --ink-muted: #83838c;
  --ink-fine: #a1a1ab;
  --ink-border: #e9e9ed;
  --ink-surface: #f6f6f8;
  --ink-surface-2: #ffffff;
  --ink-accent: #7564d9;
  --ink-active-bg: #18181b;
  --ink-active-fg: #ffffff;
  --ink-success: #16a476;
  --ink-warning: #dc6673;
  --ink-bottom: #f5f5f6;
  /* Recommended accessible body/secondary text — overrides only where needed */
  --ink-muted-readable: #6c6c75;
  --ink-content-inset: 22px;
  --ink-hairline: 1px;
  --ink-radius-search: 14px;
  --ink-radius-button: 13px;
  --ink-radius-settings: 17px;
  --ink-radius-nav: 20px;
  --ink-radius-nav-active: 15px;
  --ink-focus-ring: 2px;
}
[data-gaten-theme="dark"] {
  --ink-bg: #101114;
  --ink-fg: #f5f5f6;
  --ink-muted: #909099;
  --ink-fine: #777781;
  --ink-border: #303136;
  --ink-surface: #1b1c21;
  --ink-surface-2: #17181c;
  --ink-accent: #b0a0ff;
  --ink-active-bg: #f5f5f7;
  --ink-active-fg: #17171a;
  --ink-success: #53c99b;
  --ink-warning: #ef8a96;
  --ink-bottom: #1c1d21;
  --ink-muted-readable: #b0b0ba;
}
/* Layout & type reference, not a ready-made app */
.gaten-ink-root {
  background: var(--ink-bg);
  color: var(--ink-fg);
  font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif;
}
.gaten-ink-divider { border: 0; border-bottom: 1px solid var(--ink-border); }
.gaten-ink-bottom-nav {
  height: 63px; padding: 6px; border-radius: var(--ink-radius-nav);
  background: var(--ink-bottom);
}
.gaten-ink-tab-active {
  height: 46px; border-radius: var(--ink-radius-nav-active);
  background: var(--ink-active-bg); color: var(--ink-active-fg);
}
@media (prefers-reduced-motion: reduce) {
  .gaten-ink-root *, .gaten-ink-root *::before, .gaten-ink-root *::after {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

**Note:** `--ink-muted-readable` is a **production accessibility refinement** and is deliberately distinct from the current visual token `--ink-muted`. Use it wherever tiny text requires WCAG AA.

# 12. Copy rules and microcopy

Use sentence case. Prefer titles of 1–3 words and descriptions of 2–6 words. Status/value sits at the trailing edge when available. Never repeat a setting title verbatim in its description or include technical platform details on a top-level settings list.

| Avoid | Prefer |
| --- | --- |
| Gaten cannot read the screen until you turn it on. | Screen access · Off |
| Floating button, Quick Settings tile and hardware key | Button, tile & shortcut |
| Optional stylized-text model | Stylized text model |
| Deck, note type and card layout | Deck & card layout |
| Click here to connect to WaniKani and get started! | Connect |
| There are no recently searched terms at the moment. | No recent lookups |

# 13. QA checklist for implementation

- Light and dark use exactly the paired semantic tokens; dark is not a re-imagined layout.
- Home integration list uses hairline **rows** unless explicitly overridden.
- Anki and WaniKani use existing app logo assets; never show a second watermarked duplicate.
- There are no gradients, glow, thick shadows, pastel full-card fills or decorative badges.
- One clear primary CTA: Start studying.
- Streak circles, counters and connection states are all data-driven.
- All settings text is short; every row has a discoverable destination and meaningful value.
- Floating neutral bottom bar with selected filled tab; no black edge-to-edge dock.
- Every tappable component has effective ≥48 dp target; keyboard/screen reader paths work.
- All content scrolls correctly at 320/360/393/440 dp, 130% and 160% text scale.
- System status bar, nav gesture bar and keyboard insets are native, not HTML art.
- No “Settings” ellipsis/truncation in the active bottom pill.
- Review contrast in both themes, especially secondary labels and brand colored text.

# 14. Sources & decisions

**Internal visual baseline:** Gaten Home Ink rows HTML and Gaten Settings Ink preview from the 21 October 2026 design iterations. This document records their tokens; it does not treat speculative/generated imagery as the specification.

**Aesthetic guidance:** Anthropic, *Frontend Design Skill* — https://github.com/anthropics/skills/blob/main/skills/frontend-design/SKILL.md. Applied principles: intentional product-specific direction, purposeful typography, no generic “card kit,” restraint and concise UI copy.

**Accessibility / platform:** W3C WCAG 2.2 Understanding 1.4.3 Contrast — https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html ; Android Accessibility Help (touch target size) — https://support.google.com/accessibility/android/answer/7101858?hl=en ; Android Developer insets — https://developer.android.com/develop/ui/views/layout/insets .

**Change control:** approved values are defaults, not unchangeable law. Any divergence should be intentional: record the changed component, user-visible reason, affected light/dark tokens, accessibility impact and before/after snapshot.
