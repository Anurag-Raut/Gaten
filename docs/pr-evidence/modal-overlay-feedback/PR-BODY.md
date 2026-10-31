## Changes

Entry detail and touch feedback, in four parts:

- **Example sentences, on the word page and in the lookup sheet.** `ExampleSentencesSection` reads the
  bundled Tanaka Corpus on-device (`GatenDict.lookupExamples`, shortest first) and renders it as the
  entry's own section — used by `WordDetailScreen` *and* by the lookup sheet's `EntryDetail`, so the
  overlay offers exactly the detail the full page does. Three states: finding, none, or the
  sentences; a corpus read failure says so rather than claiming the word has no examples.
- **One tick per control, owned by the control.** `PressableScale` now plays the tick on the click
  itself (not on touch-down, so a press that becomes a scroll never ticks, and a disabled control
  reports no click at all), and the scattered per-screen `GatenHaptics.play("tick")` call sites were
  removed from `AppShell`, `BottomBar`, `ReviewScreen`, `WaniKaniReview`, `KanjiStrip`,
  `LookupSettingsScreen` and `AppearanceSettingsScreen`; `SwitchRow` plays its own. `KanjiStrip`'s
  `haptic` prop is gone with them.
- **`pressDrop`: the one control that travels.** `Button` takes a `PressDrop`; `Add to Anki` uses it,
  sinking 2 dp and easing its fill to `heldTone(fill, ink)` — the resting fill with the button's own
  ink laid over it at Material's 0.14 state layer — on top of the press opacity and the tick.
- **Haptics from the window that is actually on screen.** `GatenHapticsModule` resolves the view per
  call: `LookupSheetHost.currentView()` while the accessibility overlay is up (the overlay, not an
  activity, owns that window, and the main activity may not exist for a lookup started from another
  app), else the current activity's `decorView`. `LookupSheetHost` tracks that view and clears it on
  close. The lookup sheet's two `Pressable` rows (`EntryDetail`'s word tabs,
  `LookupModeDisclosureDialog`'s acknowledgement) became `PressableScale`s, so they tick like
  everything else.
- **Floating trigger.** A release docks the bubble to the nearer vertical edge at the height it was
  let go at, stored at once, gliding there over `Motion.SHORT`; a finger near the remove target
  draws the bubble onto it (recomputed from the finger every move, strongest at the centre, gone at
  96 dp), so a release there is a drop; each touch gives one tick through the shared haptics, and
  removal happens on the single dismissal path.

## Visual evidence

- **UI changed:** Yes.
- **Screenshots:** (inline below; each is a live capture from this build)

Word details — the new Example sentences section, after the Remembering the Kanji section and before
the Anki action:

![Word details, entry end](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/02-worddetail-end.png)

The lookup **overlay** (the sheet over a frozen frame), the same entry with the kanji tile, the
example sentences and the Add to Anki action:

![Lookup sheet, examples and kanji](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/11-lookup-sheet-examples.png)

The overlay's entry head and the frozen frame it was opened from:

![Lookup sheet entry](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/10-lookup-sheet.png)
![Frozen frame](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/09-lookup-frozen-frame.png)

The floating trigger docked, then drawn onto the remove target, then removed:

![Docked to the nearer edge](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/06-floating-docked-right.png)
![Pulled onto the remove target](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/07-floating-pulled-to-target.png)

- **Interaction, navigation or motion:** recorded on the device. GitHub builds no inline player for a video that lives in the repository, so each clip is a direct link below, with a frame strip of the same recording so its motion is visible on this page.

  `Add to Anki`, press down → held → released → added — [03-anki-press.mp4](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/03-anki-press.mp4):

  ![Add to Anki press frames](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/12-anki-press-frames.png)

  Floating trigger, dock on release and the pull onto the remove target — [05-floating-dock-and-drop.mp4](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/05-floating-dock-and-drop.mp4):

  ![Floating trigger frames](https://github.com/Anurag-Raut/Gaten/raw/orchestrate-deepseek-subagents/docs/pr-evidence/modal-overlay-feedback/13-floating-frames.png)

- **Source:** commit `675e9677db98312ef882fcf0eb4981cce042fd25` on `orchestrate-deepseek-subagents`, whose `git diff 935e05cf60b61179b575466419f2ba534290d607 675e9677…` hashes to `b80b381a2d0e989bce278ef9c52f9392aa4ed3c7e988c98ce59ea9d58cbf76ad` — the working-tree diff this build and these captures were taken from. The captures and the APK predate that commit and came from the same file contents; nothing was rebuilt from the commit. Built with `./gradlew :app:assembleDebug` (`BUILD SUCCESSFUL`); `app-x86_64-debug.apk` SHA-256 `226b3429ff28f9945b764d7ff2acfaa57571eb07cdccd09cb16fecbcbc4b703d`, its `assets/index.android.bundle` SHA-256 `78ee442b1673b991aafd435059b8c4b525af0670167819b0ced9c621892b4efa`. Device: `emulator-5556`, AVD `Gaten_Evidence_Overlay` (pixel_5, API 34, x86_64, 1080×2340 @ 440 dpi, light theme) — a separate instance from the shared `emulator-5554`. Not exercised: dark theme, other text scales, landscape for the bubble, a WaniKani-connected kanji section, the Quick Settings tile, and physical vibration (no emulator can prove it).
- **Assets:** `docs/pr-evidence/modal-overlay-feedback/` — the stills and both recordings, with a README naming the source commit and its diff hash, the APK and bundle hashes, the device, the build command, the per-frame measurements, the tool limitation below, and the limitations.
- **Blocked capture:** None outstanding for the surfaces above. Two honest gaps: **physical vibration** cannot be captured on an emulator (the recordings carry only the visual half of the tick), and `agent-device`'s Android input injection *unbinds* the accessibility service on this image (UiAutomation), so the gestures were injected with `adb shell input` while agent-device did the capture — recorded in the evidence README, not a product defect.

## Verification

- **Steps and result:** installed this build on a fresh AVD; searched `toru` → opened 取る; expanded
  all 18 meanings; checked the entry end (kanji → example sentences → deck → Add to Anki);
  long-pressed Add to Anki and watched it sink, darken and then read `Added ✓`; enabled lookup mode
  and the floating button, dragged the bubble (docks to the nearer edge, position persisted),
  dragged it onto the remove target (target up, bubble pulled on, release removed it,
  `floating_button = false`); triggered the overlay with the canonical broadcast and opened the
  sheet with a circular lasso around 取る, then scrolled it to the example sentences.
- **Checks:** `./gradlew :app:assembleDebug` — `BUILD SUCCESSFUL`. `node_modules/.bin/tsc --noEmit -p
  tsconfig.json` — clean. The APK's `assets/index.android.bundle` carries the new section's strings,
  so the installed build is this change set. Not run here: the Kotlin/JS unit suites and lint.

## Problems

- The captures and the APK were produced from the working tree **before** the change set was
  committed. The committed diff hashes identically to the one the build used (`b80b381a…`), so the
  APK is that content, but it was not re-packaged after the commit.
- `agent-device` input injection unbinds the Gaten accessibility service on API 34 emulators; the
  floating trigger and the overlay must be driven with `adb shell input` on this image.
- Physical haptics are unverified by construction.
