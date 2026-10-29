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
| Source | branch `fix/wanikani-connect-footer-keyboard`, commit `2d34406068b748d9132fbe56f99965a976122bea` (rebased on `origin/dev` `1163685`) |
| Build | `./gradlew :app:assembleDebug` (`BUILD SUCCESSFUL`) |
| Artifact | `app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK SHA-256 | `7492090079bbf31f343529dccfa98d6909fd2fd30fa7673f73bde20dd36783aa` |
| JS bundle SHA-256 | `46010dde816fd5129534add06f131e0e6cc72ebd31268d6f0c483f59e7c17e65` |
| Install | `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk` |

## What changed

- `KeyboardAvoidingView` now comes from `react-native-keyboard-controller` (as `WaniKaniReview` and `WaniKaniLesson` do), and the `ActionFooter` is its last child: with the keyboard up the token field **and** the Connect WaniKani button both rise and stay visible above it.
- `returnKeyType="go"` on the token field: the IME action key submits the same connect (guard unchanged — only a non-empty, non-pending token).
- `ScrollView` content container now adds `insets.left` / `insets.right` to `contentInsetFor(width)`, so the label, the field and the link all share the same left/right inset (mirrors `WaniKaniSettingsScreen`).
- When the keyboard opens the form is scrolled to its end, so the focused field cannot sit behind the action bar.

## Images

| File | Shows |
|---|---|
| `01-keyboard-down.png` | Keyboard closed. The token field, its label and the «Create a token on wanikani.com» link share the same left inset; the Connect WaniKani action sits at the bottom edge. |
| `02-keyboard-up.png` | Keyboard up after tapping the token field. The form has scrolled so the **token field is fully visible**, the link is under it, and the **Connect WaniKani button is clearly visible above the keyboard**. The IME action key is the forward arrow (`returnKeyType="go"`). |
| `03-ime-action.png` | IME action fired: a fake token was typed and the IME action key pressed; the keyboard dismissed and the screen shows *«WaniKani rejected that token. Check it was copied in full.»* — the IME action ran the same `connect()` as the button (and reached WaniKani). The button is enabled (token non-empty). |
| `04-narrow-width-720x1560.png` | Narrow/short check — 720×1560 px (≈262×567 dp), keyboard down. The horizontal inset is present and shared by the label, field and link. On this short viewport the form is taller than the screen, so the field sits under the action bar until the form is scrolled (a pre-existing scrolling situation, not a keyboard overlap). |

## Notes / limitations

- At 1080×2340 the keyboard-up auto-scroll reliably brings the field fully into view (image 02); the whole flow was exercised end to end.
- The 720×1560 keyboard-up shot could not be captured cleanly: on that run the app's own «Circle or tap Japanese text» overlay kept taking focus, and the emulator's `wm size` override reverted on its own, so the only narrow-screen evidence is the keyboard-down shot (image 04).
- The empty-token guard (IME action does nothing while the token is empty) is unchanged from `dev` and was not re-captured on this build; it is source-verified (`onSubmitEditing` still guards on `token.trim() !== "" && !pending`).
- The floating «G» circle in the screenshots is the React Native debug overlay, present in debug builds only.
- Keyboard state confirmed with `adb shell dumpsys input_method | grep mInputShown`.
