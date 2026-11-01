# Final visuals: Plex typography, branded tab headings, single-line streak

Source commit **`60aaa5a`** — `feat(ui): unify Plex typography and branded tab headings` (branch
`fix/tab-heading-brand-typography`, based on `dev` at `188608c`). Every capture here was taken from
that commit's build.

## Captures

| File | Screen | Conditions |
| --- | --- | --- |
| `home-light.png` / `home-dark.png` | Home | light / dark |
| `analytics-light.png` / `analytics-dark.png` | Analytics | light / dark |
| `settings-light.png` / `settings-dark.png` | Settings | light / dark |
| `lookup-japanese-dark.png` | Dictionary lookup for 猫 | dark |

What they show:

- **Home** — the approved wordmark with both accent marks, unchanged; and the streak's `0` and `days`
  on one row, right-aligned on the same line as "Daily streak".
- **Analytics / Settings** — the two titles drawn as the wordmark's own lettering, not type.
- **Lookup** — the mixed-script case: Japanese headwords and readings (`猫`, `ねこ・ネコ`,
  `猫可愛がり`) beside Latin glosses and part-of-speech labels, all in IBM Plex Sans JP.

## What the commit contains

1. **IBM Plex Sans JP everywhere.** One bundled family covers Latin and Japanese, in both React Native
   (`APP_FONT` on every `typeScale` role, the navigation theme, and the 46 local styles that named no
   family) and the Compose scale. Inter and Manrope are removed. The rendered Anki card is covered
   too — `cardDocument` declares the face per weight over a reserved host and `GatenCardWebView`
   answers those requests from the bundled resources, leaving saved templates and exported CSS alone.
2. **Branded tab headings.** `TabHeading` draws Analytics and Settings as the wordmark's own lettering:
   `a`, `e`, `n`, `t` are the wordmark's contours, the letters it lacks are matching outlines, and the
   row scales with the reader's font scale and wraps between letters.
3. **Streak on one line.** `styles.streakTotal` is a `row` with `baseline` alignment and `flex-end`
   justification, so the number and `days` share the trailing column's row.

## Source

- **APK:** `app/build/outputs/apk/debug/app-x86_64-debug.apk`,
  `sha256 fe9b7ba2e7d11b1ff10d4fa046d61df0fb25133cc8cce5089ee2ec518dcb0a5f`, 304 MB, installed with
  `adb install -r` (`lastUpdateTime=2026-11-01 21:54:25`).
- **Build:** `ANDROID_HOME=/home/Qui/Android/Sdk ./gradlew :app:assembleDebug` — BUILD SUCCESSFUL.
- **Device:** AVD `Gaten_Analytics_Capture` — Pixel 7 profile, Android 34 (`google_apis`, x86_64),
  1080x2340 @ 440 dpi, launched with `-gpu host`.
- **Capture:** `agent-device` 0.21.31, package `com.gaten`.

## Checks

`npx tsc --noEmit` passed, `node --test tests/analytics.test.cjs` 23/23, `git diff --check` clean, and
`:app:assembleDebug` BUILD SUCCESSFUL — all on `60aaa5a`.

## Limits

- **The rendered Anki card path is not verified on device.** The emulator has no AnkiDroid, so no note
  and no card could be rendered; the injected `@font-face` and the intercepted responses are in the
  APK but have no loaded-font confirmation. This is why the change is a draft.
- IBM Plex Sans JP does not carry every symbol the app writes: `✓` ("Added ✓", "✓ Correct") and `≈`
  fall back to the platform's own face for those glyphs alone.
- The JP faces' Latin is about 5 % larger on the em than IBM Plex Sans's own.
- One emulator, one density, font scale 1.0; no physical device or tablet.

## Related evidence

`docs/pr-evidence/tab-heading-typography/` carries the lettering specimens and the large-font /
narrow-width stress captures (320 dp and 196 dp at font scale 1.5, where the Settings row wraps), plus
`build-glyphs.py`, which regenerates the glyph module.
