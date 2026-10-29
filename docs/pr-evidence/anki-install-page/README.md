# Review screen evidence — shared header and "Download AnkiDroid" on the not-installed page

Captured for PR #39 (`fix-ankidroid-install-page-header-download-button`):

- the AnkiDroid-missing state of the review flow now draws the app's shared pushed-page bar
  (`ScreenHeader`) — back control, **left-aligned** title, no centred heading — instead of its own
  centred bar, and
- it offers one filled action, **Download AnkiDroid**, which opens
  `https://play.google.com/store/apps/details?id=com.ichi2.anki`, and the flow rechecks AnkiDroid
  when the reader comes back to the app.

| Item | Value |
|---|---|
| Head under test | `5ce39fb` (`fix: unify app UI and add AnkiDroid download action`), working tree clean apart from this evidence |
| App | `com.gaten` 0.1.0 (debug, debuggable), `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `eb00d7578862068afb175b92ae4176ff9434053ffc9e491db693895403469b7c`, built with `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug` |
| JS actually running | the head bundle embedded in that APK (`assets/index.android.bundle`); a Metro dev server on host `:8081` serving the same worktree was up throughout, so both paths are the head revision |
| Device | AVD `Gaten_PR19_Capture` → `emulator-5556`, Android 14, x86_64, 1080x2400 @ 420 dpi, font scale 1.0, cold boot |
| AnkiDroid | absent for `00`–`04` (`pm list packages` has no `com.ichi2.anki`); installed in the background during the `05`/`06` recheck step |
| Before baseline | `00-before-blocked.png` is the same screen on the same AVD at base `7d12ac4` with the base JS, taken from commit `1506017` of the now-closed duplicate PR #38 (see "Provenance") |

## Steps

1. `emulator -avd Gaten_PR19_Capture -no-window -no-audio -no-boot-anim -no-snapshot-save`, then
   `adb -s emulator-5556 install -r -d app/build/outputs/apk/debug/app-x86_64-debug.apk`.
2. Home → the **Anki** card (`Open Anki`) → the blocked state; dismissed the LogBox banner; captured
   `01-after-blocked-install-button.png` and read the state's nodes while the screen was up.
3. With `settings put system show_touches 1` so the tap is visible, recorded `screenrecord` and pressed
   **Download AnkiDroid**: Chrome opened the Play listing (address bar
   `play.google.com/store/apps/details?id=com.ichi2.anki`, page title *AnkiDroid Flashcards*) →
   `02-after-tap-opens-play-store.mp4`/`.gif`, `03-after-play-store-listing.png`.
4. Returned to Gaten without installing anything (`am start` brought the task to the front) — the page
   rechecked and still showed the not-installed state: `04-after-return-recheck.png`.
5. Installed AnkiDroid in the background (base APK pulled from the AnkiDroid already present on
   `emulator-5554`), pressed Home first so the app was genuinely backgrounded, then returned to Gaten:
   the recheck changed the screen to **AnkiDroid access is needed** →
   `05-after-return-recheck-permission.png`, recorded as `06-after-return-recheck.mp4`/`.gif`.
6. `settings put system show_touches 0` afterwards; nothing was answered, graded or installed inside
   Gaten.

## Measurements

Header ink spans measured from the PNGs themselves (dark pixels in the header band,
`y = 170..300`; two clusters = back arrow, then the title):

| File | Back-arrow ink `x` | Title ink `x` | Title ink mid, offset from screen centre `x=540` |
|---|---|---|---|
| `00-before-blocked.png` | 65–105 | **506–575** (width 70) | **0 px — centred** |
| `01-after-blocked-install-button.png` | 43–84 | **127–217** (width 91) | **−368 px — left-aligned, starts at the shared bar's 16 dp content edge** |

The title's own text node in the head revision measures `bounds="[132,195][1036,252]"` on the same
shared bar on the review screens (`docs/pr-evidence/shared-headers-review/NOTES.md`), i.e. the same
132 px left inset for the glyph box.

## Evidence

| File | What it shows |
|---|---|
| `00-before-blocked.png` | baseline `7d12ac4`: centred `Anki` title, `AnkiDroid is not installed, so there is nothing here to review.`, no action |
| `01-after-blocked-install-button.png` | head: left-aligned `Anki` title behind the shared bar, new copy, filled **Download AnkiDroid** button |
| `02-after-tap-opens-play-store.mp4` / `.gif` | 30 s real-app recording (touch indicator on): the tap on the button, Chrome opening the listing |
| `03-after-play-store-listing.png` | the destination page, *AnkiDroid Flashcards* by *AnkiDroid Open Source Team*, with the store URL in the address bar |
| `04-after-return-recheck.png` | back in Gaten after the store, recheck ran, state unchanged because nothing was installed |
| `05-after-return-recheck-permission.png` | after installing AnkiDroid while Gaten was backgrounded and returning: the same page now reports **AnkiDroid access is needed** — the return recheck moved the flow out of the not-installed state |
| `06-after-return-recheck.mp4` / `.gif` | 40 s real-app recording of that background-install-and-return step |

## Provenance of the baseline

`00-before-blocked.png` was produced by the duplicate PR #38 (branch `create-anki-droid-install-page`,
commit `1506017`, file `docs/pr-evidence/ankidroid-install/00-before-blocked.png`) on the same AVD,
the same 1080x2400 @ 420 dpi geometry and the base revision `7d12ac4`, which is this branch's own
base. #38 was closed as a duplicate of #39 and its copy of the baseline is reproduced here so the
before/after pair is self-contained.

## Limitations

- The store link resolves to Chrome on this AVD; the Play Store app's own flow and a real AnkiDroid
  install from the store were not exercised. AnkiDroid was installed from an APK already present on
  another emulator, which is what the recheck reacts to.
- `04`/`05` exercise the recheck on return; the "first-time setup" half of the same code path (opening
  AnkiDroid once to create its collection, then returning) was not driven — the freshly installed
  AnkiDroid had no collection, which is why the flow reports the access state next.
- One device, one locale, default font scale. The permission state, the deck list and the review
  session itself are covered by `docs/pr-evidence/shared-headers-review/`.
- No grading happened in either flow, so neither the Anki collection nor any other app's data was
  changed; the LogBox banner present on these debug captures is React Native's, not the app's UI.
