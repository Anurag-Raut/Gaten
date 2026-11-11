# Radical detail page — rendered evidence

Rendered proof for the radical detail simplification: the radical's WaniKani drawing drawn
**unboxed** with its name once beside it, the duplicate name row and the icon toolbar under the page
header removed, and the page's **Refresh** / **View on WaniKani** actions moved to a flat foot row.
Kanji pages, the loading skeleton and the notice states are unchanged.

## Provenance

| Field | Value |
| --- | --- |
| Branch | `feat/radical-details-design`, targeting `dev` |
| Base / HEAD | `93501814be1ac6e9d630338e25f7101e14e3f2a8` (clean tree, `git show HEAD` matches the published `WaniKaniPanel` radical/kanji branches) |
| Modified source | Working tree over that HEAD; no commit was made. Diff hash `git diff \| sha256sum` = `10dc8bdd827d8341540afa95c4322efe3dc9c30691bf606a49dafecf2cc1c56a` |
| Changed files (hashes) | `src/rn/ui/wanikani/WaniKaniPanel.tsx` `d185f79f…`, `src/rn/ui/search/ReferenceScreen.tsx` `4e74fca0…` (comment only), `docs/design-language.md` `eade871c…` |
| Before APK | Built from a pristine `git archive HEAD` copy of `9350181` at `/home/Qui/gaten-radical-baseline-head`, `:app:assembleDebug -PreactNativeArchitectures=x86_64`; `app-x86_64-debug.apk` sha256 `6842fee510f77bc603b07b04418f33658894553d82274ca7c3b34544934dd824` |
| After APK | Built from the modified working tree with the same command; sha256 `90ed77f3cea602534757b2dd4a1c8b181c6a6cc91b882ec3e004149514209e89`. The on-device `base.apk` was re-hashed to each value before its captures |
| Device | serial `emulator-5554`, AVD `Gaten_Lookup_Review`, Android 14 (API 34), x86_64, 1080×2340 @ 440 dpi, font scale 0.94, `-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect` |
| Themes | dark (`cmd uimode night yes`, default) and light (`night no`) |
| WaniKani account | connected test token present (`no_backup/wanikani/`), no token value read or written. Home read “WaniKani · Connected” throughout |
| Driver | `agent-device` session `radical-evidence` (open/click/screenshot/record); taps by snapshot refs after `snapshot -i` |

### Capture head vs build artifact

The modified APK was built while the implementer's working tree was final; no source edit occurred
between that build and the captures (diff hash above), and the on-device `base.apk` was re-hashed to
the values in the table before each capture. If the change is amended, the after artifacts must be
re-shot against the new APK. The before artifacts come only from the pristine `git archive HEAD`
build above.

Two earlier attempts are **excluded from this evidence**. First, an initial "before" APK was built
from the working tree while the implementer's edit was still in progress, so it was not HEAD; it was
discarded. Second, the debug app was serving a stale cached Metro bundle
(`files/BridgelessReactNativeDevBundle.js`, an earlier session) instead of the packaged bundle; the
cache was renamed to `BridgelessReactNativeDevBundle.js.preevidence` (app data and token untouched;
no `pm clear`, no uninstall) so the APK's own `assets/index.android.bundle` was exercised for every
artifact here. Every PNG/MP4 above was taken after that fix and against a verified APK hash. A Metro
reload regenerates the cache.

The repository README and this file use relative links, which work in the repo. The publication body
`PR_DESCRIPTION.md` uses absolute `raw.githubusercontent.com` URLs (and a GitHub blob URL for the
MP4) with a `<COMMITTED_SHA>` placeholder to resolve after the authorized commit.

## Files

| File | What it shows |
| --- | --- |
| `before-01-say-radical-dark.png` | HEAD: radical `言` / “Say”, name row + refresh/open icons under the app bar, drawing in a tinted rounded box, separate `Meaning` section |
| `after-01-say-radical-dark.png` | Same subject, modified: unboxed drawing with “Say” once beside it, no toolbar row, `Refresh` / `View on WaniKani` at the foot |
| `before-02-say-radical-light.png` / `after-04-say-radical-light.png` | The same before/after pair in light |
| `before-04-leaf-radical-dark.png` / `after-02-leaf-radical-dark.png` | Second radical subject, WaniKani “Leaf” (丆) — its own drawing shape — dark; before is boxed + `Meaning`, after is unboxed with the name and foot actions |
| `before-03-leaf-radical-light.png` / `after-03-leaf-radical-light.png` | The Leaf pair in light |
| `after-05-radical-actions.mp4` | 18.8 s of the modified page: tapping **Refresh** (page settles on the same subject) then **View on WaniKani**, which opens Chrome at `wanikani.com/radicals/say`; back returns to Gaten |

Every screenshot is a full 1080×2340 device capture with the status bar normalised; both themes use
the same subject and page position.

## Commands

```sh
# before (pristine HEAD copy)
git archive HEAD | tar -x -C /home/Qui/gaten-radical-baseline-head
cp -al /home/Qui/Projects/gaten/node_modules /home/Qui/gaten-radical-baseline-head/node_modules
cp -a /home/Qui/Projects/gaten/app/src/main/assets/db/*.sqlite /home/Qui/gaten-radical-baseline-head/app/src/main/assets/db/
flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=2

# after (working tree)
cd /home/Qui/.paseo/worktrees/1t28llpl/radical-details-design
flock /tmp/gaten-build.lock ./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64 --max-workers=2
./node_modules/.bin/tsc --noEmit

# device
adb -s emulator-5554 install -r <apk>        # never uninstall / pm clear
adb -s emulator-5554 shell cmd uimode night yes|no
# agent-device session radical-evidence: open com.gaten --foreground, then
# Home → Recently looked up 言葉 → kanji 言 → Radicals 言; or 夏休み → kanji 夏 → Radicals 丆
# agent-device install, click, screenshot, record start/stop
```

## What the capture did not exercise

- **No character-null radical was reached.** Every WaniKani radical exposed through this account's
  dictionary component lists carried a character as well as WaniKani's SVG (言, 艹, ｲ, 匕, ナ, 又,
  𠂉, 毋, 丆, 目, 夂, 几, 丿, 虫, 飛, 生). Both captured subjects render WaniKani's SVG drawing, so
  the SVG-first branch is device-proven; the `character`-only fallback and the no-art-at-all branch
  are covered by review only.
- **Refresh's network re-read is not observable in a still.** The tapped action left the same
  subject and content on screen with no error; the app logs no HTTP, so the `refresh=true` re-fetch
  is asserted from source, not captured. What is captured is that the foot action is live and the
  page remains stable.
- **No width/font-scale matrix:** not captured at 320/340/430 dp or 130 %/160 % text.
- **The nested radical panel inside review's expanded kanji detail** was not reached in this pass
  (the dictionary path above is the full page).
- **TalkBack** and the REACT accessibility labels were not driven; labels are unchanged in source.
- No review, lesson or answer was submitted and no account state changed; the only device state
  changes were the app install and theme toggles, both restored (dark is the default).
