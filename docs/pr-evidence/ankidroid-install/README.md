# Review screen evidence — "Install AnkiDroid" action on the not-installed state

Captured for the change in `5699b61` (`fix: add AnkiDroid install action to review screen`,
`src/rn/ui/ReviewScreen.tsx`): the blocked state for a device without AnkiDroid keeps its
`AnkiDroid is not installed` title, replaces the old sentence with
`AnkiDroid is not installed. Please install it to continue using this functionality.`, and adds one
filled action, **Install AnkiDroid**, which opens
`https://play.google.com/store/apps/details?id=com.ichi2.anki` through `GatenPlatform.openUrl`.

| Item | Value |
|---|---|
| Head under test | `5699b6196ca4bdf4c2c0f230dd90650e632a2a4b`, working tree clean |
| Before baseline | `7d12ac4` (parent of `5699b61`), same APK, JS served from a scratch copy |
| Device | AVD `Gaten_PR19_Capture`, `emulator-5554`, Android 14, x86_64, 1080x2400 @ 420 dpi, font scale 1.0 |
| AnkiDroid | **not installed** on this AVD (`pm list packages` has no `com.ichi2.anki`) — the state this change targets |
| App | `com.gaten` 0.1.0, versionCode 10, debuggable debug build from this worktree, JS from Metro on `:8081` |
| APK | `app-x86_64-debug.apk`, 294 648 666 bytes, sha256 `4b8900d92f24b0e396b3f08d997c016b2c8d92b649e8716a07a9f0dd15f7539e` |
| Result | The button appears on the not-installed state and opens the AnkiDroid Play Store listing |

## Revision and build

1. `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL in 2m 1s`, `:app:packageDebug` wrote
   `app/build/outputs/apk/debug/app-{x86_64,arm64-v8a,armeabi-v7a}-debug.apk`.
2. The three gitignored generated assets this build requires were not present in this worktree; they
   were copied from an existing checkout rather than regenerated: `app/src/main/assets/db/jmdict.sqlite`
   (schema version 4, the version `app/build.gradle.kts` requires) and `examples.sqlite` (schema 1)
   from `/home/Qui/gaten-capture`, and `app/src/main/assets/manga-ocr/` (encoder/decoder/vocab) from
   `/home/Qui/gaten-merge-build`. They are dictionary/model payload, unrelated to this screen, and no
   tracked file was touched by the copy.
3. `adb install -r` of the x86_64 split onto the AVD; `com.gaten` was already installed from an
   earlier build, so the same-signature debug package was replaced in place — no app data was
   uninstalled and AnkiDroid was neither installed nor removed.
4. For these captures, JS was loaded from Metro, so the running JS was checked directly:
   `curl "http://127.0.0.1:8081/index.bundle?platform=android&dev=true&minify=false"` served from this
   worktree contains `play.google.com/store/apps/details?id=com.ichi2.anki` (2 hits) and
   `AnkiDroid is not installed. Please install it to continue using this functionality.` (1 hit),
   while the bundle served from the `7d12ac4` scratch copy contains
   `AnkiDroid is not installed, so there is nothing here to review.` (1 hit) and not the new sentence.
   The only `Install AnkiDroid` text in that older bundle is the unrelated settings row
   `Install AnkiDroid to add cards.`; no review-screen action with that label exists in it.

## Steps

1. Cold boot the AVD headless (`emulator -avd Gaten_PR19_Capture -no-window -no-audio -no-boot-anim`),
   confirm the package list has no `com.ichi2.anki`, `adb reverse tcp:8081 tcp:8081`, start Metro from
   this worktree, `adb shell monkey -p com.gaten -c android.intent.category.LAUNCHER 1`.
2. Home → the **Anki** card (`Open Anki`) → the review flow's blocked state.
3. Read the state's text from the live accessibility tree (`uiautomator dump`) and screenshot it.
4. Tap **Install AnkiDroid** → Chrome opens the listing; record the tap and its result, screenshot the
   loaded page, and read the launched intent back from the platform.
5. For the before state: stop Metro, start Metro from a `git archive 7d12ac4` scratch copy
   (`/tmp/gaten-before`, `node_modules` symlinked with the worktree declared as a `watchFolders`
   entry), force-stop and relaunch the app so it loads that bundle, walk the same Home → Anki route,
   and screenshot the same state.

## Evidence

| File | What it shows |
|---|---|
| `00-before-blocked.png` | `7d12ac4`: `AnkiDroid is not installed` + `AnkiDroid is not installed, so there is nothing here to review.` and **no action** below it |
| `01-home-anki-card.png` | Home, the entry point used: the `Anki` card (`Not connected`, `Open Anki`) |
| `02-after-blocked-install-button.png` | `5699b61`: the same state with the new sentence and the filled **Install AnkiDroid** action |
| `03-after-tap-opens-play-store-listing.mp4` | 25 s recording from the real app: the blocked state → tap **Install AnkiDroid** → Chrome loading `play.google.com` → the *AnkiDroid Flashcards* listing. Android `screenrecord` encodes a frame only when the screen changes, so the file is a few frames, not 25 s of motion |
| `03-after-tap-opens-play-store-listing.gif` | The same recording as a 2 fps GIF, for inline review |
| `04-after-play-store-listing.png` | The result page: *AnkiDroid Flashcards*, AnkiDroid Open Source Team, 10M+ downloads, omnibox on `play.google.com/store/apps/de…` |

The tap's intent was read back from the platform after the recording:

```
adb shell dumpsys activity starter | grep play.google
  Intent { act=android.intent.action.VIEW
           dat=https://play.google.com/store/apps/details?id=com.ichi2.anki
           flg=0x14402000 cmp=com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity (has extras) }
```

and `logcat` shows the same launch coming from the app's uid, with
`topResumedActivity=com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity` afterwards.

## Checks

| Check | Result |
|---|---|
| `npx tsc --noEmit` | clean, exit 0 |
| `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL in 2m 1s` |
| Bundle served to the device | head bundle has the new sentence and the Play Store URL; `7d12ac4` bundle has the old sentence and no such action |
| Live screen | before: old sentence, no action; after: new sentence, action present, tap reaches the listing |

No JS unit test was run: the repo has no `npm test`/Jest setup for `src/rn`, and this change is one
screen's copy plus one `openUrl` call. The evidence above is the app itself, not a preview or mockup.

## Limitations

- Debug build with JS from Metro, not a release APK: the native side is unchanged by this commit, but
  this capture does not exercise an R8-minified build.
- The emulator resolves this `https://play.google.com/...` link to Chrome — the Play Store app is
  installed but does not claim the link on this image — so the listing is the web store page opened by
  the browser. The URL the app builds is the Play Store listing; which app renders it is the device's
  resolver's choice.
- Chrome's own first-run screen appeared on the very first tap (fresh AVD profile); it is browser
  state, not app behaviour. For the recorded run it was skipped with a device-side Chrome flag
  (`/data/local/tmp/chrome-command-line` plus `am set-debug-app --persistent com.android.chrome`);
  both were removed after the capture. The intent the app launches is unaffected by that flag —
  it is the browser's launch screen that changes.
- Only the `NOT_INSTALLED` blocked state was driven. The other blocked states (`PERMISSION`,
  `NOT_SET_UP`), the deck picker, the card session and the WaniKani side of the review flow were not
  exercised here.
- The AVD process exited on its own twice during this session (headless emulator, no guest crash in
  `logcat`). `00-before-blocked.png`, `01-home-anki-card.png` and `02-after-blocked-install-button.png`
  come from earlier boots of the same AVD, profile and APK; the recording in `03-…` and the listing in
  `04-…` were re-captured on the last boot, whose live state was also read back through
  `dumpsys`. The JS each capture ran was the bundle Metro served at that moment, checked as above.
- AnkiDroid was neither installed nor uninstalled; its listing was only viewed in the browser.
  The Gaten debug APK was updated in place as described above.
