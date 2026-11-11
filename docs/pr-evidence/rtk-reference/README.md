# Remembering the Kanji reference - visual evidence

Everything here was captured from the real app on the Android emulator, not a mockup.

| | |
| --- | --- |
| Head commit | `1163685b8e4031e6a669d6ff2f858da7bf860c94` |
| Device | `emulator-5554`, `sdk_gphone64_x86_64`, Android 14 (API 34) |
| Build | `./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK SHA-256 | `f2f2d417ce8e1906fae99c41d09ce386b92cb4e902867134418bfc6cc3cbc15f` |
| Data | bundled `assets/db/rtk.sqlite` from `tools/build_rtk_db.py` (schema 1, 2,991 kanji, no WaniKani token connected anywhere) |

All captures are from the same APK. The device had no WaniKani account, so every kanji surface shown is answered by Remembering the Kanji or by WaniKani's own "no token" path.

## Captures

| File | What it shows |
| --- | --- |
| `01-settings-hub-kanji-reference.png` | Settings hub: the new **Study: Kanji reference** row. It shows Remembering the Kanji here because that is the choice made in this run; a device that never chose shows WaniKani, which `02` captures. |
| `02-kanji-reference-wanikani.png` | The Kanji reference page with **WaniKani** selected - the default a fresh device has, unchanged from before the feature. |
| `03-kanji-reference-rtk.png` | The same page after choosing **Remembering the Kanji**. One radio group; the note under it records that the WaniKani connection is untouched. |
| `04-rtk-kanji-page.png` | RTK page for 鬱, opened by tapping the kanji tile on its word page: 鬱 glyph, **Remembering the Kanji** source line, keyword `gloom`, and the long Heisig story wrapping with `**bold**`/`_italic_` emphasis preserved. |
| `05-rtk-community-mnemonic.png` | Scrolled to **Community mnemonics**: the first Kanji Koohii story with the author (`mcbainpc`) and date, and the compact **View other stories** action. |
| `06-rtk-other-stories.png` | The same page after **View other stories** (now **Hide other stories**): the remaining four stories expanded in place, each with its own author and date, Unicode intact. |
| `07-rtk-primitive.png` | RTK page for 一: keyword `one`, the Heisig story, the **Primitive** comment (its source `* ` list marker dropped), and the community story. |
| `08-rtk-not-available.png` | 躑, a kanji the dataset does not carry, opened from 躑躅: **Not available in Remembering the Kanji** instead of an empty page. |
| `09-wanikani-selected-no-kanji-strip.png` | 猫's word page with **WaniKani** selected and no token: no kanji strip at all - the pre-existing `GatenWaniKani.isConfigured()` gate is unchanged. |
| `10-rtk-strip-same-word.png` | The same 猫 word page after switching the setting to Remembering the Kanji: the strip appears, labelled **Remembering the Kanji**, with the 猫 tile - the choice is what decides it, not a token. |
| `11-settings-after-restart.png` | Settings hub after force-stopping and relaunching the app: **Kanji reference - Remembering the Kanji**, i.e. the choice survived the restart. |
| `12-anki-review-rtk-strip.png` | The Anki review answer side: the note's kanji strip is labelled **Remembering the Kanji** and needs no WaniKani token. |
| `13-anki-review-rtk-panel.png` | Tapping that tile opens the RTK page inline in the review card (猫, keyword `cat`, Heisig story) - the third kanji surface follows the same setting. |
| `14-source-switch-and-rtk-page.mp4` | The recorded interaction (50 s, 540x1170): Settings hub -> Kanji reference -> switch WaniKani then Remembering the Kanji -> Home -> search `utsu` -> open 鬱 -> tap its kanji tile -> RTK page -> expand the remaining stories. This is the navigation and motion evidence. |
| `14-source-switch-replay.txt` | The `agent-device` replay script for that same run; the device was driven through `agent-device` (`agent-device replay docs/pr-evidence/rtk-reference/14-source-switch-replay.txt`). |

`09` and `10` are the same word and the same build, one setting change apart, and are the clearest single piece of evidence that the preference controls the reference.

Back navigation was unchanged throughout: `04`/`07`/`08`/`10` were reached by the existing word -> kanji push and returned with the system Back gesture, and `12`/`13` sit inside the existing Anki review flow.

## Not exercised here

- **The accessibility lookup overlay.** Lookup mode and the floating trigger were both switched on (Gaten's own Screen lookup page reads **Lookup mode: On**), the service was enabled through Android's Accessibility settings and confirmed in `settings get secure enabled_accessibility_services`, but the floating trigger window never appeared over another app, so a lasso lookup could not be started. The overlay renders the same `SearchKanjiScreen` / `ReferenceScreen` component as `09`/`10` with `overlay` set, so the source-aware page itself is the code path shown above; what is unverified is the overlay host, not the kanji page.
- **A WaniKani kanji page with content** (meanings, readings, mnemonics, radicals). That needs a connected WaniKani account, which this emulator does not have. The WaniKani path is untouched: `WaniKaniPanel`, the `lookupKanji`/`lookupRadical` bridge and the entitlement checks were not modified, the strip gate still reads `GatenWaniKani.isConfigured()` (capture `09`), and every WaniKani unit suite still passes.

## Reproducing

```sh
python3 tools/build_rtk_db.py                       # the offline asset (gitignored)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-x86_64-debug.apk
```

Demo lookups used here: `utsu` -> 鬱 (long story, no primitive), `hitotsu` -> 一 (primitive), `tsutsuji` -> 躑躅 (not in RTK), `neko` -> 猫 (strip gate and review strip).

## Local build note

`tools/build_examples_db.py` no longer matches its pinned SHA-256 (upstream updated the Tanaka Corpus on 2026-10-25). The local build input was generated with the hash updated temporarily and that edit reverted; it is unrelated to this feature and is left as a separate issue.
