## Changes

Three related UI changes on `fix/tab-heading-brand-typography` (sources `60aaa5a`, then `2b9d009`):

1. **IBM Plex Sans JP everywhere.** One bundled family now covers Latin and Japanese in both React
   Native (`tokens.ts`'s `APP_FONT`, every `typeScale` role, the navigation theme, and the 46 local
   text styles that previously named no family) and the Compose scale (`ui/theme/Type.kt`, display
   steps included). Inter and Manrope are removed, with their licences and `NOTICE` entries. The
   rendered Anki card is covered too: `cardDocument` declares the face per weight over a reserved
   host and `GatenCardWebView` answers those requests from the bundled resources, leaving saved
   templates and exported CSS untouched.
2. **Branded tab headings.** `TabHeading` draws Analytics and Settings as the approved wordmark's own
   lettering instead of type: `a`, `e`, `n`, `t` are the wordmark's own contours, the letters it does
   not carry are matching outlines, the row scales with the reader's font scale and wraps between
   letters, and the title stays one accessibility heading. Home's wordmark is unchanged.
3. **Streak row.** `styles.streakTotal` is now a row with baseline alignment, so `0` and `days` sit
   together at the trailing edge on the same line as "Daily streak"; the number draws in the same
   `typeScale.titleMedium` role as that title (accent coloured, `days` stays smaller), and the flame's
   wrapper carries a 2 dp lift so it reads centred with the title.

## Visual evidence

- **UI changed:** Yes — every screen's type, the two tab titles, and Home's streak row.
- **Screenshots:**

![Home, light](docs/pr-evidence/font-ibm-plex/home-light.png)
![Home, dark](docs/pr-evidence/font-ibm-plex/home-dark.png)
![Analytics, light](docs/pr-evidence/font-ibm-plex/analytics-light.png)
![Analytics, dark](docs/pr-evidence/font-ibm-plex/analytics-dark.png)
![Settings, light](docs/pr-evidence/font-ibm-plex/settings-light.png)
![Settings, dark](docs/pr-evidence/font-ibm-plex/settings-dark.png)

The mixed-script case — Japanese headwords and readings beside Latin glosses, one face:

![Dictionary lookup for 猫](docs/pr-evidence/font-ibm-plex/lookup-japanese-dark.png)

Earlier SVG-only stress captures at large font scale and narrow width. The heading artwork is unchanged; the surrounding text in these three captures predates the IBM Plex migration:

![Analytics, 320 dp at font scale 1.5](docs/pr-evidence/tab-heading-typography/analytics-large-narrow.png)
![Settings, 320 dp at font scale 1.5](docs/pr-evidence/tab-heading-typography/settings-large-narrow.png)
![Settings wrapped, 196 dp at font scale 1.5](docs/pr-evidence/tab-heading-typography/settings-large-wrap.png)

The lettering against the approved wordmark (specimen, not an app capture):

![Lettering specimen, light](docs/pr-evidence/tab-heading-typography/heading-lettering-light.png)

- **Interaction, navigation or motion:** No interaction, navigation or motion behaviour changed.
- **Source:** Branch `fix/tab-heading-brand-typography`, sources `60aaa5a` (typography, headings,
  streak row) then `2b9d009` (streak count size, flame lift), based on `dev` `188608c`. AVD
  `Gaten_Analytics_Capture` (Pixel 7, Android 34 `google_apis` x86_64, 1080x2340 @ 440 dpi, `-gpu
  host`; `-gpu swiftshader_indirect` returns black screencaps on this host). Built with
  `ANDROID_HOME=/home/Qui/Android/Sdk ./gradlew :app:assembleDebug`. The Home captures came from
  `app/build/outputs/apk/debug/app-x86_64-debug.apk`,
  `sha256 18f8fbb6cbfea613c64b9bc5287291d39e501f625d6810fe9fee7e3d3a1ab64a`; the Analytics, Settings and
  lookup captures came from the earlier `60aaa5a` build of the same tree,
  `sha256 fe9b7ba2e7d11b1ff10d4fa046d61df0fb25133cc8cce5089ee2ec518dcb0a5f` (`2b9d009` touches only
  Home's streak row). Not exercised: physical devices, tablets, and any screen other than Home,
  Analytics, Settings and a dictionary lookup.
- **Assets:** `docs/pr-evidence/font-ibm-plex/` — the six screen captures and the lookup;
  `docs/pr-evidence/tab-heading-typography/` — the lettering specimens and narrow/large-font runs, and
  `build-glyphs.py`, which regenerates the heading glyph module.
- **Blocked capture:** The rendered Anki card could not be exercised — the emulator has no AnkiDroid,
  so no note and no card could be rendered. Its font injection and the interception are in the APK,
  but there is no on-device confirmation that a card loads the bundled face. This is why the change is
  a draft.

## Verification

- **Steps and result:** Built and installed the APK, then opened Home, Analytics and Settings and the
  dictionary, and captured each screen in light and dark (the lookup in dark). The Home captures — the
  later `2b9d009` build — show `0` at the same size as "Daily streak" with the smaller `days` beside it
  on one baseline row at the trailing edge, and the flame lifted so it reads centred with the title.
  The lookup shows Japanese and Latin in one face. The heading lettering was separately exercised at
  320 dp and 196 dp with font scale 1.5. Device overrides used for those runs were restored
  afterwards.
- **Checks:** `npx tsc --noEmit` passed; `node --test tests/analytics.test.cjs` passed (23/23);
  `git diff --check` clean; `:app:assembleDebug` BUILD SUCCESSFUL. The built bundle carries
  `"IBM Plex Sans JP"` and the card font host and no `Manrope`; the dex carries the reserved host and
  the CORS header.
- **Not run:** instrumented/UI tests, the release build, and the Anki card path (no AnkiDroid on the
  emulator).

## Problems

- Eight of the twelve heading glyphs are derived from Poppins SemiBold (OFL 1.1, licensed in
  `assets/licenses/Poppins-OFL.txt` and recorded in `NOTICE`) rather than drawn by the logo's author:
  they match the wordmark's cap/x-height and stem width, but they are not the designer's own outlines.
- `TabHeading` supports exactly `Analytics` and `Settings`; a third title needs new glyphs.
- IBM Plex Sans JP does not carry every symbol the app writes: `✓` ("Added ✓", "✓ Correct") and `≈`
  fall back to the platform's own face for those glyphs alone.
- The JP faces' Latin is about 5 % larger on the em than IBM Plex Sans's own.
- The rendered Anki card's font path has no on-device confirmation.
