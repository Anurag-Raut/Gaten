# Shared pushed-page bar — settings, search and tab-root evidence

Captured for PR #39 (`fix-ankidroid-install-page-header-download-button`): the settings pages, the
word-detail page and the two tab roots now take their bar and their horizontal content inset from the
shared implementation (`ScreenHeader`, `contentInsetFor`/`CONTENT_COLUMN`) instead of each screen
carrying its own copy of that layout.

| Item | Value |
|---|---|
| Head under test | `5ce39fb` (`fix: unify app UI and add AnkiDroid download action`) |
| App | `com.gaten` 0.1.0 (debug, debuggable), `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `eb00d7578862068afb175b92ae4176ff9434053ffc9e491db693895403469b7c` |
| JS actually running | the head bundle embedded in that APK; Metro on host `:8081` served the same worktree throughout |
| Device | AVD `Pixel_Safe` → `emulator-5558`, Android 14, x86_64, 1080x2340 @ 440 dpi, font scale 1.0 |
| Services | AnkiDroid not permitted, WaniKani not connected — both captured in their default state, no setting value changed |

## What these captures show

- Pushed pages (`02`–`05`) draw the shared bar: back control and a left-aligned title whose glyph box
  starts at **x = 132**, immediately at the right edge of the back hitbox `[0,158][132,290]`, not on the
  screen's centre line (x = 540). `02` also shows the shared bar's trailing controls (Help, Save).
- The tab roots (`01`, `07`) keep their own page headings and correctly have no back control; their
  content shares the same left content edge (x = 61 for the heading inset).
- No alignment regressed on the settings pages against the pre-change stills
  (`docs/pr-evidence/wanikani-settings/after-connected-top.png` already shows a left-aligned
  `WaniKani` title); what changed there is that the bar, its hitbox and the inset now come from one
  shared component rather than per-screen code.

| File | Screen | Header node | Bounds (width 1080) |
|---|---|---|---|
| `01-settings.png` | Settings tab root | `Settings` (page heading, tab root — no back) | `[61,199][1020,320]` |
| `02-anki-settings.png` | Settings → Anki cards | `Anki cards`, `heading="true"` | `[132,193][740,254]` |
| `03-wanikani-settings.png` | Settings → WaniKani | `WaniKani`, `heading="true"` | `[132,193][1036,254]` |
| `04-appearance-settings.png` | Settings → Appearance | `Appearance`, `heading="true"` | `[132,193][1036,254]` |
| `05-word-detail.png` | Home → search `猫` → first result | `Word details`, `heading="true"` | `[132,192][1036,253]` |
| `07-analytics.png` | Analytics tab root | `Analytics` (page heading, tab root — no back) | `[61,202][859,323]` |

Measurement note: stock `adb shell uiautomator dump` on this build does not surface React Native text
nodes for these screens (it returns the native bottom-bar node only, e.g. one `text="Settings"`), so
the bounds above come from the UiAutomator2 accessibility dump of the same settled screen
(`agent-device snapshot --raw`, one rect per node), reported as `[x1,y1][x2,y2]`. The
`heading="true"` node is the one `ScreenHeader` emits with `accessibilityRole="header"`, which is
also how the shared bar is identified on the review screens.

## Steps

1. Cold boot the AVD headless, `adb -s emulator-5558 install -r -d` the APK above, launch `com.gaten`.
2. Bottom navigation → **Settings**: capture `01`; then **Anki cards** → `02`, **WaniKani** → `03`,
   **Appearance** → `04` (Back between each).
3. Home → tap the search field (`猫, ねこ, neko...`) → type `猫` → open the first result: capture `05`.
4. Bottom navigation → **Analytics**, top of the page: capture `07`.

## Blocker — the full-page kanji detail page (`06`) was not captured

`ReferenceScreen` (title `Kanji details`) is reachable only through the kanji strip on the word page,
which renders only when `GatenWaniKani.isConfigured()` is true
(`src/rn/ui/search/WordDetailScreen.tsx`); the external lookup sheet's strip is gated the same way, the
review flow's kanji strip is the *inline* `WaniKaniPanel` and not this page, and `AndroidManifest.xml`
declares no deep link, `ACTION_PROCESS_TEXT` or share intent that could open it directly. WaniKani is
not connected on this device and no token is available, so that screen has **no rendered evidence** in
this change; its edit is a spacing fix inside the same shared `ScreenHeader`.

## What was not exercised

- No setting value was changed (no theme, accent, haptics, token or deck choice), no AnkiDroid access
  was granted and no card was exported through the word page's "Add to Anki" bar.
- Anki/WaniKani service states, the deck picker, the card-layout editor and the analytics refresh were
  not driven here; the pushed pages other than the settings/search ones are covered in
  `docs/pr-evidence/shared-headers-review/`.
- One device, one locale, default font scale; no dark theme, large text or landscape captures.
