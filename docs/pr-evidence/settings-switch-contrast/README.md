# Settings switch on-state contrast — evidence

Rendered evidence for the checked switch colours in `SwitchRow`
(`src/rn/ui/settings/settings-components.tsx`), the shared row every settings switch uses.

## Provenance — prior capture, not this branch's head

These four stills come from the parent agent's **combined dirty build on the old base**, taken
before this branch was rebased onto `dev`. They show the switch treatment this pull request
changes; nothing else in them is this pull request's subject.

| Item | Value |
|---|---|
| Base revision | `7d12ac44799fd909f897de65b1f11090c8f7596d` (`find-new-tasks-ticktick`) |
| Working tree | dirty — the combined patch (switch + landscape sheet + deck-picker spacing), 6 files |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 298,000,245 B, built 2026-10-29 17:56:29 |
| APK SHA-256 | `de73fbf814023f4559ac571de83e2b8900215295eece8c75466dd95222132b9e` |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi |
| App theme | Gaten `System`; the emulator was in light mode except for the two dark captures |

**They are not a capture of this branch's head.** Current-head rendered evidence is pending and is
the remaining requirement before this pull request leaves draft. `dev` does not touch the
`SwitchRow` colour lines this branch changes, so the code under test in the stills is the same four
lines this branch carries.

## Files

| File | State |
|---|---|
| `05-lookup-toggle-on-light.png` | Screen lookup, light — **Lookup mode On** |
| `06-lookup-toggle-off-light.png` | Screen lookup, light — **Lookup mode Off** (the Floating button row below shows the same On treatment) |
| `07-lookup-toggle-off-dark.png` | Screen lookup, dark — **Lookup mode Off** |
| `08-lookup-toggle-on-dark.png` | Screen lookup, dark — **Lookup mode On** |

## Measured on the stills (1080 px wide, 2.75 px/dp)

Sampled on the Lookup mode switch's own row (view px y=450):

| Still | Thumb | Track | Page behind |
|---|---|---|---|
| `05` light On | `rgb(66,55,198)` ≈ `#4338CA` `accent` | `rgb(238,242,255)` = `#EEF2FF` `accentSubtle` | `#FDFDFD` |
| `06` light Off | `rgb(128,128,137)` = `#83838C` `iconSecondary` | `rgb(246,246,248)` = `#F6F6F8` `surfaceVariant` | `#FDFDFD` |
| `07` dark Off | `rgb(141,141,150)` = `#909099` `iconSecondary` | `rgb(27,28,33)` = `#1B1C21` `surfaceVariant` | `#101114` |
| `08` dark On | `rgb(162,176,247)` ≈ `#A5B4FC` `accent` | `rgb(49,46,129)` = `#312E81` `accentSubtle` | `#101114` |

The thumb the reader's report found as near-black on a lavender track is the accent on the accent's
quiet container in `08`, and it reads against the page it reaches over.

## Before

The before state is the reader's own report screenshot (Screen lookup, dark, Lookup mode On: a
near-black thumb vanishing into the page, leaving a lavender crescent). It is a user attachment and
is not redistributed here; the after states above are the four stills.

## Not exercised by these stills

- The disabled state (the Quick Settings tile row while a tile request is in flight).
- The other accents (Blue, Violet, Teal, Green, Rose, Black) and the other switch rows
  (Appearance → Sounds, Anki → deck rows).
- Landscape and large-type rendering of the switch rows.
- Anything on this branch's rebased head — see Provenance.
