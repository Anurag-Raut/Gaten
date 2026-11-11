# Modal / overlay feedback — press, examples and the floating trigger

Rendered evidence for the change set committed as `675e9677db98312ef882fcf0eb4981cce042fd25`
on `orchestrate-deepseek-subagents` — captured from those same file contents while the change set was
still uncommitted: the on-device example-sentence section on the word page *and* in
the lookup sheet, the shared press feedback (`PressableScale` tick plus the Add-to-Anki `pressDrop`),
the haptics bridge resolving the lookup sheet's own window, and the floating trigger's dock and
remove-target pull. Everything here is from this tree's own debug build on a dedicated emulator.

| Item | Value |
|---|---|
| Source | the change set in commit `675e9677db98312ef882fcf0eb4981cce042fd25` on `orchestrate-deepseek-subagents`, whose `git diff 935e05cf60b61179b575466419f2ba534290d607 675e9677…` hashes to `b80b381a2d0e989bce278ef9c52f9392aa4ed3c7e988c98ce59ea9d58cbf76ad` — the working-tree diff this build and these captures were taken from. The captures predate that commit and were made from those same file contents; the APK is that build's, not a rebuild from the commit |
| Build | `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` (282 tasks) |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, 299,868,595 B, SHA-256 `226b3429ff28f9945b764d7ff2acfaa57571eb07cdccd09cb16fecbcbc4b703d` |
| JS inside that APK | `assets/index.android.bundle` SHA-256 `78ee442b1673b991aafd435059b8c4b525af0670167819b0ced9c621892b4efa`; it carries this change set (`No example sentences found for this word`, `Finding examples…` are present in the bundle) |
| Device | `emulator-5556` — AVD `Gaten_Evidence_Overlay`, created for this run (pixel_5, API 34, google_apis x86_64), 1080×2340 @ 440 dpi, light theme. Deliberately **not** the shared `emulator-5554` / `Gaten_Lookup_Review` |
| AnkiDroid | 2.25.3, `variant-abi-AnkiDroid-2.25.3-x86_64.apk`, SHA-256 `154101f06f4ba286248fa4a26863f759d3a1631bd4b1d6790503ec35a85edac7`; `com.ichi2.anki.permission.READ_WRITE_DATABASE` granted to `com.gaten`, so `ankiStatus === "ready"` and the Anki action draws |
| Kanji reference | **Remembering the Kanji** (Settings ▸ Kanji reference) so the word page and the sheet draw their kanji section without a WaniKani account |
| Driver | agent-device 0.21.31 for screenshots and `record`; touches injected with `adb shell input` — see *Tool limit* below |

## What each file shows

| File | What it shows |
|---|---|
| `01-worddetail-top.png` | Word details for 取る, all 18 meanings expanded (`18 of 18`) — the page this section was added to |
| `02-worddetail-end.png` | The end of the same entry: **Remembering the Kanji** section (取 tile), the new **Example sentences** section (お塩とって。/ 新聞を取って。/ キーを取って。 with translations), the Deck row and the `Add to Anki` action last |
| `03-anki-press.mp4` | The press cycle of `Add to Anki`: resting → held → released (busy spinner) → `Added ✓` |
| `04-anki-pressed-frame.png` | A decoded frame of `03` at the held instant |
| `04-anki-after.png` | The button after the press: `Added ✓` |
| `05-floating-dock-and-drop.mp4` | The floating trigger: dragged from the left dock to mid-screen (docks to the nearer, right edge), then dragged to the remove target — the target comes up, the bubble is drawn onto it, release removes the bubble |
| `06-floating-docked-right.png` | A frame of `05`: the bubble resting at the right edge after the second dock |
| `07-floating-pulled-to-target.png` | A frame of `05`: the finger near the remove target, the bubble pulled onto it |
| `08-floating-removed.png` | A frame of `05`: the bubble gone, the target fading out |
| `09-lookup-frozen-frame.png` | The accessibility overlay after `am broadcast -a com.gaten.action.TRIGGER_LOOKUP -p com.gaten`: the frozen frame with the "Reading screen…" chip |
| `10-lookup-sheet.png` | The lookup **sheet** opened by a circular lasso around 取る in that frozen frame: the overlay's own entry, the 取る/撮る tabs, the headword and the meanings |
| `11-lookup-sheet-examples.png` | The same sheet scrolled: the kanji tile, **Example sentences** and the `Add to Anki` action — the overlay is as complete as the full page |
| `12-anki-press-frames.png` | Frame strip of `03`: the `Add to Anki` button band at rest, held, exporting and `Added` |
| `13-floating-frames.png` | Frame strip of `05`: left dock → drag → right dock → drag onto the remove target → removed |

`03`, `04-anki-pressed-frame`, `05`, `06`–`08` are the interaction and motion evidence, and `12`/`13`
are frame strips of the two recordings so the motion is visible without a player; the remaining files
are stills of the live surfaces.

## Measurements

`Add to Anki` press tone, sampled from a text-free band of the button in decoded frames of `03`
(mean of 230×100 px, `x 90..320`, `y 2085..2185`):

| State | Mean RGB | Frame |
|---|---|---|
| Resting | (66, 54, 198) `#4236C6` | `f001` |
| Held | (138, 129, 218) `#8A81DA` | `f002` |
| After release | (68, 54, 198) | `f003` |

The held tone is what the two shared mechanisms compose: `PressableScale`'s 0.72 press opacity over
the page, plus `pressDrop`'s `heldTone(fill, ink)` — the resting fill with the button's own ink laid
over it at `HELD_LAYER = 0.14`. 0.72·(fill blended 14 % toward `onActionPrimary`) composited on the
page's white gives exactly the sampled 138/129/218.

Floating trigger, from `05` and the app's own preferences:

| Fact | Value |
|---|---|
| Bubble window | 152×152 px (`48 dp` disk × `BUBBLE_PRESS_SCALE 1.15`), drawn as a 132 px disk |
| Second dock | window `(906, 859)` = `widthPixels − window − EDGE_MARGIN_DP(8 dp)`, i.e. the **right** edge, at the height it was released at; stored as `floating_button_x = 906`, `floating_button_y = 859` |
| Remove target | window `(464, 2034)`, 152×152 px — display-centred, `DISMISS_BOTTOM_MARGIN_DP 32 dp` above the usable bottom; its circle centre is `(540, 2110)` |
| Drop | after the release on the target the preference reads `floating_button = false`: the bubble was removed, not merely hidden |

## Method

```sh
./gradlew :app:assembleDebug
agent-device install app/build/outputs/apk/debug/app-x86_64-debug.apk --platform android --device "Gaten_Evidence_Overlay"

# lookup mode = Android's own accessibility setting; on this image the write alone did NOT rebind
# the service, a reboot did
adb -s emulator-5556 shell settings put secure enabled_accessibility_services com.gaten/com.gaten.app.GatenAccessibilityService
adb -s emulator-5556 shell settings put secure accessibility_enabled 1
adb -s emulator-5556 reboot

# the overlay, then a circular lasso around 取る (input motionevent draws the loop a straight
# swipe cannot)
adb -s emulator-5556 shell am broadcast -a com.gaten.action.TRIGGER_LOOKUP -p com.gaten

# the floating trigger and the Anki press are driven with `adb shell input swipe` / `input swipe`
agent-device record start <file>.mp4 --scope device
agent-device screenshot --out <file>.png
```

Fixtures: search `toru` → 取る; AnkiDroid ready with deck `Gaten`; Kanji reference `Remembering the
Kanji`.

## Tool limit that shaped the drive

`agent-device`'s Android input injection (`press`, `swipe`, `gesture`) connects UiAutomation, and on
this image that **unbinds** the Gaten accessibility service — the floating bubble and the overlay
both disappear (verified: `dumpsys accessibility` shows `Bound services:{}` right after an
`agent-device swipe`, and the service stays bound after the same gesture through
`adb shell input swipe`). Screenshots and `record` do not do this. So every touch was injected with
`adb shell input` and agent-device was used only for capture. This is a testing-tool behaviour, not a
product bug — the same limitation is recorded in `docs/pr-evidence/external-trigger/VERIFICATION.md`.

## What this evidence does not establish

- **Physical vibration.** No emulator can prove the tick, the drop tick or the dismissal tick is felt
  on real hardware. `03`/`05` show only the visual halves of those interactions; the haptic call
  itself is verified by code and by the absence of `E_NO_WINDOW` rejections, not by feel.
- **The overlay's haptics through the sheet's own window.** `LookupSheetHost.currentView()` feeds
  `GatenHapticsModule`, but a haptic is not observable in a screen recording; what `09`–`11` show is
  that the overlay entry renders and that the sheet's buttons accept presses.
- Not exercised: dark theme; a text scale other than the emulator's default; landscape for the
  bubble; a WaniKani-connected kanji section (this device has no token, so the kanji section is the
  Remembering the Kanji one); the Quick Settings tile path.
- `05` is an `adb screenrecord` clip, which encodes a frame only when the screen changes; the stills
  and the frame-by-frame positions above carry the claims.
- The bubble hid transiently a few times during the run (the service's `captureToken.busy` /
  `overlay.isShowing` guard, plus the tool limit above). The `05` clip and the two docked stills are
  from the runs where it was up; nothing about the dock, the pull or the drop was reconstructed.
