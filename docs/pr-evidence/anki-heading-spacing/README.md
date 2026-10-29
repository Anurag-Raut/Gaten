# Anki deck picker — heading → caught-up notice spacing

TickTick task `6ae331d2717ab2bce9f1ac08`.

One style value in the all-clear state of the Anki deck picker:
`src/rn/ui/review/AnkiDeckPicker.tsx` — `caughtUp.marginTop: -6` → `spacing.m` (12). The heading,
the notice's own bottom margin, the type scale and the artwork are untouched.

## Provenance of the captures in this directory — read this first

| | |
|---|---|
| Build captured | the **combined build on the old base**: `7d12ac4` + a 6-file working-tree patch (this fix is one line of it), in the coordinator's tree |
| APK | `app-x86_64-debug.apk`, 298,000,245 B, SHA-256 `de73fbf814023f4559ac571de83e2b8900215295eece8c75466dd95222132b9e` |
| Device | `emulator-5554`, AVD `Gaten_Lookup_Review` (pixel_5, API 34, x86_64), 1080x2340 @ 440 dpi, system font scale 0.94, Gaten theme `System` (light) |
| **Not captured** | the **current PR head** (`7e02486` + this one-line fix). These images are not proof of the PR head — a fresh capture on the rebased head is a stated blocker below. |

Copies in this directory, SHA-256:

| File | SHA-256 |
|---|---|
| `anki-all-clear-fixed.png` | `114a49e92e5dc36d7d42be96e27a2fdfda9fd540526e77afdffa0059480e663e` |
| `anki-all-clear-arrival.mp4` | `5972c8fcd82bce22e8c6f33f7738852ffe5d7e7bde329c89bb657724b3a3ba51` |
| `fixture-state-before.txt` | `459046d6442b9974e2012e66be5f548075913c27e35567c280534e42f56e6f1c` |
| `fixture-state-after-restore.txt` | `53fd9397b1ccf412598b392fb4e3c6cd3ffe1589cbefad2b4f84c81ffdfe92da` |

## The fix, measured

Ink-band measurement of the gap from the `Choose a deck` baseline to the top of the notice's first
line, on `anki-all-clear-fixed.png` (1080 px wide at 440 dpi = 2.75 px/dp):

| | Gap |
|---|---|
| Reporter's screenshot of the defect (1272 px wide, 3.5 px/dp) | 24 px = **6.9 dp** |
| The build above | 68 px = **24.7 dp** |

`-6` was also off the spacing ladder (`docs/Gaten_Ink_Design_Language_v1.md` §4). `spacing.m` (12)
lands the gap at ≈ 25 dp, inside the app's own title→content rhythm (the shared `ScreenSubtitle` uses
`marginTop: 11`).

`anki-all-clear-arrival.mp4` is Home → Anki card → deck picker arriving at the fixed state.
`adb screenrecord` encodes a frame only when the screen changes, so the clip has few frames; the still
carries the measurement.

## Fixture and restore proof

The emulator's AnkiDroid collection had nothing in the all-clear state (2 new cards due), so the two
synthetic fixture cards were suspended for the capture and then unsuspended.

| File | Content |
|---|---|
| `fixture-state-before.txt` | cards `1792817301586`, `1792820578043`, deck `Gaten`, type 0, queue 0, due 1 and 2; revlog 0 |
| `fixture-state-after-restore.txt` | after restore: the same rows, revlog 0 |

The two files' card lines (`id, did, ord, type, queue, due, ivl, reps, lapses`) are identical, so the
card scheduling and review state is restored. No full-file hash pair was taken — AnkiDroid rewrites
the collection in place, so the recorded rows and the revlog are the evidence. The app itself
confirmed the restore: Home read `Anki, Connected, 2 reviews due` again.

The fixture deck is named `Gaten` where the reporter's own screenshot showed `Default`; the
composition (heading, notice, `Available decks`, one cleared row) is the same.

## Not exercised

- The current PR head — no capture yet (see the blocker below).
- The picker's other states (decks with cards due, loading skeleton, filtered-empty, failures); only
  the all-clear branch renders the element this change touches.
- Other densities, display sizes and font scales; landscape.
