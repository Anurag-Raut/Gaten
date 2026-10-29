# WaniKani connect: the field and the action both rise above the keyboard — rendered evidence

## Device

| | |
|---|---|
| Emulator | `emulator-5554` (AVD `Gaten_Lookup_Review`, headless, x86_64) |
| Android | 14 (API 34) |
| Model | `sdk_gphone64_x86_64` |
| Fingerprint | `google/sdk_gphone64_x86_64/emu64xa:14/UE1A.230829.050/12077443:userdebug/dev-keys` |
| Display | 1080×2340 @ 440 dpi (plus a 720×1560 override for the narrow check) |
| Navigated | Home → WaniKani card → **Connect** → Connect WaniKani |

## Build under test

| | |
|---|---|
| Source | branch `fix/wanikani-connect-action-rises`, commit `43082b6fb623fa53fa0b0de5483a1a40c6896ee9`, on top of `origin/dev` `de4f894` (the merge of #47) |
| Build | `./gradlew :app:assembleDebug` (`BUILD SUCCESSFUL`) |
| Artifact | `app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK SHA-256 | `207f3075ef4eccce0631f1c08ca1ff4b6b1bb39ae327de8358096466be33d214` |
| JS bundle SHA-256 | `44698b771ebd1e50576d86dc8027417dfda6f4c7635bbe863151ae7f9e2f2d96` |
| Install | `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk` |

## What changed

- `ActionFooter` is now the last child of the `KeyboardAvoidingView` (from `react-native-keyboard-controller`, as `WaniKaniReview` and `WaniKaniLesson` use it): with the keyboard up the token field **and** the Connect WaniKani button both rise and stay visible above it. This supersedes the placement merged in #47, where the footer was a sibling *outside* the KAV and passed under the keyboard.
- `returnKeyType="go"` on the token field: the IME action key submits the same connect (guard unchanged — only a non-empty, non-pending token).
- `ScrollView` content container adds `insets.left` / `insets.right` to `contentInsetFor(width)`, so the label, the field and the link all share the same left/right inset (mirrors `WaniKaniSettingsScreen`).
- When the keyboard opens the form is scrolled to its end, so the focused field cannot sit behind the action bar.

## Images

| File | Shows |
|---|---|
| `01-keyboard-down.png` | Keyboard closed. The token field, its label and the «Create a token on wanikani.com» link share the same left inset; the Connect WaniKani action sits at the bottom edge. |
| `02-keyboard-up.png` | Keyboard up after tapping the token field. The form has scrolled so the **token field is fully visible**, the link is under it, and the **Connect WaniKani button is clearly visible above the keyboard**. The IME action key is the forward arrow (`returnKeyType="go"`). |
| `03-ime-action.png` | IME action fired: a fake token was typed and the IME action key pressed; the keyboard dismissed and the screen shows *«WaniKani rejected that token. Check it was copied in full.»* — the IME action ran the same `connect()` as the button (and reached WaniKani). The button is enabled (token non-empty). |
| `04-narrow-width-720x1560.png` | Narrow/short check — 720×1560 px (≈262×567 dp), keyboard down. The horizontal inset is present and shared by the label, field and link. On this short viewport the form is taller than the screen, so the field sits under the action bar until the form is scrolled (a pre-existing scrolling situation, not a keyboard overlap). |
| `05-narrow-width-keyboard-up.png` | Same 720×1560 screen, keyboard up after tapping the token field: the field and the link are visible and the **Connect WaniKani button sits fully above the keyboard**. Confirms the action rises even on a short screen. |

## Motion

| File | Shows |
|---|---|
| `06-keyboard-rise.mp4` | Narrow 720×1560 screen: the field is focused and the keyboard rises; the form scrolls and **the Connect WaniKani button settles above the keyboard**, never under it. Recorded from the same build with `adb shell screenrecord --size 720x1560`; trimmed to ≈7.5 s. |

## Notes / limitations

- `02-keyboard-up.png` was re-captured on `emulator-5554` from the APK built from this branch (hashes above), after tapping the token field; `01`, `03` and `04` were captured from the same source revision (commit `2d34406`, the identical code change) and were not re-shot.
- At 1080×2340 the keyboard-up auto-scroll reliably brings the field fully into view (image 02); the whole flow was exercised end to end.
- The narrow 720×1560 keyboard-up state, missed in the first capture pass, was captured later from the same APK (`05-narrow-width-keyboard-up.png`, `06-keyboard-rise.mp4`): the field and the action both clear the keyboard. The earlier attempt failed only because the app's «Circle or tap Japanese text» overlay kept taking focus and the `wm size` override reverted; a fresh navigation run with the override in place worked.
- The empty-token guard was exercised on the device: with the field empty the IME action key dismisses the keyboard and does nothing else — no request is made, no message appears, and the Connect WaniKani button stays disabled.
- The floating «G» circle seen in earlier runs is the React Native debug overlay, present in debug builds only; it is absent from the captures kept here.
- Keyboard state confirmed with `adb shell dumpsys input_method | grep mInputShown` (`mInputShown=true` in the keyboard-up capture).
- `05-narrow-width-keyboard-up.png` (`a5d7f04b6c6c2beae176312a839e50846826cba0d8bcc73b19ca0989ec4cf081`) and `06-keyboard-rise.mp4` (`0c8288c1a23974b5b51bc6a04cbcae3c8743a910257bcddec3f5e0165004742e`) were added in a documentation-only follow-up after #48 was merged into `dev` (`2069265`). They come from the same installed APK as the rest of this evidence (`207f3075ef4eccce0631f1c08ca1ff4b6b1bb39ae327de8358096466be33d214`), which the merge did not change.
