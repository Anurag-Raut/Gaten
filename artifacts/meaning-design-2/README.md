# Meaning section v2 — PR evidence (capture)

Device screenshots of the new word-detail **Meanings** design
(`src/rn/ui/search/WordDetailScreen.tsx`), taken from the running app on the visible
`Pixel_Safe` emulator (`emulator-5556`).

## The design in these shots

- **Meanings** heading (`typeScale.titleSmall`, `onSurface`) with a trailing `n of N` count
  (`typeScale.bodyMedium`, `onSurfaceVariant`).
- Five senses previewed, each its own row separated by a hairline divider; **View all meanings (N)**
  expands to the full list and becomes **Show fewer meanings**, which returns to five.
- **Part of speech** and **Other spellings** labels use the same `titleSmall` / `onSurface` token as
  the Meanings heading and the WaniKani kanji heading — all four headings match.
- Headword metadata `Common · <pos>` keeps `Common` and the separator in `onSurfaceVariant` while
  the part of speech itself is drawn in the accent colour (`colors.primary`).

## Files

| File | Screen |
| --- | --- |
| `meanings-collapsed-light.png` | 掛ける — collapsed, **5 of 25** + View all meanings (25) |
| `meanings-expanded-light.png` | after View all — **25 of 25**, list from the top |
| `meanings-expanded-light-bottom.png` | after View all — list end, Show fewer meanings, Part of speech, Other spellings |
| `meanings-collapsed-again-light.png` | after Show fewer — back to **5 of 25** |
| `meanings-collapsed-dark.png` | same as collapsed, dark |
| `meanings-expanded-dark.png` | after View all — **25 of 25**, dark |
| `meanings-expanded-dark-bottom.png` | list end, Show fewer, Part of speech, Other spellings, dark |
| `meanings-collapsed-again-dark.png` | after Show fewer — **5 of 25**, dark |
| `entry-cat-6-senses-light.png` | 猫 — six senses: **5 of 6** + View all meanings (6) |
| `entry-five-or-fewer-light.png` | 学校 — **1 of 1**, no expand control (≤ 5 senses) |

All 1080×2340 PNG straight from the device framebuffer.

## Behaviour verified on device

| Step | Observed |
| --- | --- |
| 掛ける opens | `Meanings` + `5 of 25`, 5 divided rows, `View all meanings (25)` |
| Tap View all meanings | `25 of 25`, all 25 senses, `Show fewer meanings` |
| Tap Show fewer meanings | back to `5 of 25`, `View all meanings (25)` (same in dark) |
| 猫 opens | `5 of 6` + `View all meanings (6)` |
| 学校 opens | `1 of 1`, no toggle |

The count is the entry's real sense count: entry 1207610 (掛ける) has exactly 25 `|`-separated
senses in `app/src/main/assets/db/jmdict.sqlite`, matching the header. (An earlier brief said 26;
the shipped data and the UI agree on 25.)

## Freshness

- `./gradlew :app:assembleDebug` after the final heading/colour edit → BUILD SUCCESSFUL,
  `app-x86_64-debug.apk` 297,941,077 B.
- `adb -s emulator-5556 install -r` that APK; `pm path` resolves to a base.apk of the same size and
  `dumpsys package com.gaten` reports `lastUpdateTime=2026-10-25 20:42:54` — the build above.
- The bundle inside the APK changed with each source edit (`assets/index.android.bundle` SHA-256
  differs per build), and debug builds here load `assets://index.android.bundle`, so no Metro server
  is involved.
- Dark mode via `adb shell cmd uimode night yes` (the app's theme choice is unset → `System`).

## Environment

Visible AVD `Pixel_Safe` (1080×2340, 440 dpi, android-34 x86_64) on serial `emulator-5556`,
launched as
`QT_QPA_PLATFORM=xcb /home/Qui/Android/Sdk/emulator/emulator-real -avd Pixel_Safe -port 5556 -no-audio -no-boot-anim -no-snapshot-load`.
(The Qt `wayland` plugin is absent from the emulator's bundled Qt, so `QT_QPA_PLATFORM=xcb` is
required for a visible window on this Wayland desktop.) Driven with `agent-device` plus `adb`
(coordinate taps: this RN build exposes a sparse accessibility tree).

At the end of the capture the emulator is left running with the app open on the 掛ける word detail,
collapsed, in light mode.

## Build note

`tools/build_jmdict_db.py` ran clean. `tools/build_examples_db.py` fails its pinned corpus SHA-256
(`c1283036…` expected, upstream now serves `46396cce…`); the `examples.sqlite` used for these builds
is an earlier run of the same script (schema `user_version=1`, probe lookups pass). The repo's pinned
hash is stale upstream — a separate, reviewable change.

## Typography and disclosure follow-up

Refreshed on the latest local source with three typography roles (36px bold display,
15px semibold headings/actions, 14px regular body), animated Collapsible meanings,
and synchronized DisclosureChevron. TypeScript and debug APK build passed; the APK
was installed on emulator-5556. Expand/collapse was repeated in light and dark themes
(5 of 25 → 25 of 25 → 5 of 25). Emulator left open on the collapsed entry in light mode.

Current evidence: `meanings-collapsed-light.png`, `meanings-collapsed-dark.png`,
`meanings-expanded-light-bottom.png`, `meanings-expanded-dark-bottom.png`,
`meanings-collapsed-again-light.png`, and `meaning-toggle.gif`. Other images above
are from the earlier iteration, before the three-style typography and animated arrow.
