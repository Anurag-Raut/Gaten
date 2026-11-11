# WaniKani connect: the token field lifts above the keyboard, the connect action stays behind it — rendered evidence

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
| Source | branch `fix/wanikani-connect-footer-keyboard`, commit `91ee20f25dcec1d2f54a4959f2cf763792a29e0d` (rebased on `origin/dev` `1163685`) |
| Build | `./gradlew :app:assembleDebug` (`BUILD SUCCESSFUL`) |
| Artifact | `app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK SHA-256 | `98f25cbae1c6da1a4d6cce7df5e7651c66b9ee88488bd427bde442a981d34c0e` |
| JS bundle SHA-256 | `ed24c1aab38548880291cfabefcba24ef34ba655d10db827b82801a4e6dc91ab` |
| Install | `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-x86_64-debug.apk` |

## What changed

- Only the header and the form sit inside the `KeyboardAvoidingView` (now from `react-native-keyboard-controller`, as `WaniKaniReview` and `WaniKaniLesson` use), so with the keyboard up the token field lifts clear of it.
- The `ActionFooter` is a sibling **outside** the KAV, so the Connect WaniKani button stays at the screen's bottom edge and passes **under** the keyboard — it does not rise with it.
- `returnKeyType="go"` on the token field: the IME action key submits the same connect (guard unchanged — only a non-empty, non-pending token).
- `ScrollView` content container adds `insets.left` / `insets.right` to `contentInsetFor(width)`, so the label, the field and the link all share the same left/right inset (mirrors `WaniKaniSettingsScreen`).
- When the keyboard opens the form is scrolled to its end, so the focused field cannot sit behind the action bar.

## Images

| File | Shows |
|---|---|
| `01-keyboard-down.png` | Keyboard closed. The token field, its label and the «Create a token on wanikani.com» link share the same left inset; the Connect WaniKani action sits at the bottom edge. |
| `02-keyboard-up.png` | Keyboard up after tapping the token field. The **token field and its link are visible above the keyboard**, while the **Connect WaniKani button is *not* visible — it sits below the keyboard**, at the screen's bottom edge, exactly as required. The IME action key is the forward arrow (`returnKeyType="go"`). |
| `03-ime-action.png` | IME action fired: a fake token was typed and the IME action key pressed; the keyboard dismissed and the screen shows *«WaniKani rejected that token. Check it was copied in full.»* — the IME action ran the same `connect()` as the button (and reached WaniKani). The button is enabled (token non-empty). |
| `04-narrow-width-720x1560.png` | Narrow/short check — 720×1560 px (≈262×567 dp), keyboard down. The horizontal inset is present and shared by the label, field and link; the Connect WaniKani action is at the bottom edge. |
| `05-narrow-width-keyboard-up.png` | Same 720×1560 screen with the keyboard up: the **token field, the link and the error line are all visible above the keyboard**, and the **Connect WaniKani button is not visible — it is below the keyboard**. Confirms the field is never hidden, even on a short screen. |

## Notes / limitations

- At 1080×2340 the keyboard-up state shows the field above the keyboard and the action behind it (image 02), and the IME action reached WaniKani (image 03).
- The narrow/short screen (720×1560 px) was re-checked on this build, both down (image 04) and up (image 05): the field, link and error line stay visible above the keyboard while the action stays below it.
- The screenshots in images 04/05 were taken with a token already typed (from the IME-action test), so the field shows a masked value and the rejection line; the layout is what the images demonstrate.
- The empty-token guard (IME action does nothing while the token is empty) is unchanged from `dev` and was not re-captured on this build; it is source-verified (`onSubmitEditing` still guards on `token.trim() !== "" && !pending`).
- The floating «G» circle in the screenshots is the React Native debug overlay, present in debug builds only.
- Keyboard state confirmed with `adb shell dumpsys input_method | grep mInputShown`.
