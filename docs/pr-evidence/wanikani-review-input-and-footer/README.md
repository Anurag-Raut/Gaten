# WaniKani review: continuous queue, kana reading input, floating action — device evidence

## At a glance

| # | Change | Rendered here |
| --- | --- | --- |
| 1 | A WaniKani review no longer shows a **Review this batch** row or its dots; the queue is continuous. | `before-01`, `after-01` |
| 2 | A reading prompt says its question in kana (`こたえ`, the WaniKani habit) and the reader types romaji: what they type turns into hiragana as they go. | `after-03`, `after-04`, `wk-romaji-typing.mp4` / `.gif` |
| 3 | With the keyboard up the **Check answer** action does not rise with it — it passes under the IME. | `after-02` |
| 4 | The bottom action floats: no hairline above it and no opaque strip behind it, so content scrolls behind the buttons; Anki's button geometry is the reference both lanes now share. | `before-01` / `after-01`, `before-02` / `after-05`, `after-06`, `after-07` / `after-08` |
| 5 | Lesson batches stay at five per batch (unchanged by this change). | Not device-captured — see *What was not captured* |

## Device

| | |
| --- | --- |
| Emulator | `emulator-5556` (AVD `Gaten_Lookup_Review`, x86_64) |
| Android | 14 (API 34), model `sdk_gphone64_x86_64` |
| Display | 1080×2340 @ 440 dpi, `font_scale` 0.94, light mode |
| Keyboard | Gboard (`com.google.android.inputmethod.latin`), software keyboard forced on with `settings put secure show_ime_with_hard_keyboard 1` |
| Anki | AnkiDroid (`com.ichi2.anki`) installed, deck **Gaten** with 4 new cards |

Session note: agent-device swaps the device's IME to its own headless test IME, which has no input
view. To photograph a keyboard, the IME was set back with
`adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`
before tapping a field.

## Builds

| | After (this change) | Before (base) |
| --- | --- | --- |
| Source | branch `fix-manikani-review-batches-typing-input`, commit `1c96ef6` + the working-tree change | the same tree with `src/rn/ui/**` reverted (`git stash push -- src/rn/ui/ReviewScreen.tsx src/rn/ui/review/`) |
| Build | `ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` | same |
| APK | `app/build/outputs/apk/debug/app-x86_64-debug.apk`, sha256 `d38c3f20e8c65642e03556b2ed45d57df33d1f06212d18984e540f06bbe240be` | sha256 `2e8db2900c7962d0dc4df8cb81a7ce54c0106017b8e9dac4323a5c5289717481` |
| JS bundle in that APK | sha256 `8a7e1f2859fcfe6bf3e71d9cdaf6ce1ae409dfb1f162283ea3c38b216779dbc4` | sha256 `5c5979b29f344dc420d22c326e1005473da598c7afa56270c4142583bb9015ad` |
| Install | `adb -s emulator-5556 install -r …` — the on-device `base.apk` re-hashed to the same `d38c3f20…` | same |

Debug variants embed the JS bundle; no Metro server was involved. `after-01`/`after-02` were
re-captured from the `d38c3f20…` build; every other `after-*` file came from an earlier build of the
same source whose embedded bundle hashes identically (`8a7e1f28…`).

## Account state

The evidence uses the project's existing WaniKani test account, connected through the app's own
connect screen (the token is kept in the Android keystore). At capture time the account had
**4 vocabulary reviews due** (`苦しい`, `在留`, `地震`, `震度`) and **no lessons** (`All caught up`).

**No review was submitted.** The meaning of `苦しい` was answered only to reach its reading prompt,
and the app submits a review only once every part of the subject is done, never on a typed or
half-answered part. The session was left before the reading was checked, and the hub still reported
`4 reviews due` afterwards (`after-09`). No lesson was started, so no assignment moved between the
queues.

## Files

| File | Shows |
| --- | --- |
| `before-01-review-batch-row.png` | **Before**: the review card carries a `Review this batch` row with one dot per queued item (4 dots, first filled), and the action sits in an opaque strip under a hairline. |
| `after-01-review-no-batch-row.png` | **After**, same card, same queue (`1 of 4`): the batch row is gone, the input follows the part label directly, and only the `Check answer` button is painted — no hairline, no strip. |
| `after-02-review-keyboard-up.png` | Keyboard up on the review: the field and the card are clear of the keyboard, and **`Check answer` is not visible — it sits behind the IME**, exactly as asked. |
| `after-03-reading-prompt-kotae.png` | The `Reading` part asks for the answer in kana: the field's placeholder is `こたえ`. |
| `after-04-reading-kana-typed.png` | Typing `kurushii` on an English keyboard leaves `くるしい` in the field — the same assist WaniKani gives. |
| `wk-romaji-typing.mp4` / `.gif` | The same typing, recorded: `k` → `ku` → `くる` … → `くるしい` (7.5 s, trimmed from the raw capture). |
| `before-02-anki-review-footer.png` | **Before** on the Anki review card: the action sits inside a background strip with a hairline above it. |
| `after-05-anki-review-footer.png` | **After**: the same card with the button floating over the content, no hairline, no strip. |
| `after-06-anki-review-rating-footer.png` | Revealed answer: the rating buttons float the same way; the deck content is behind them. |
| `after-07-connect-keyboard-down.png` / `after-08-connect-keyboard-up.png` | The connect screen (`WaniKaniConnect.tsx` is part of this change): keyboard down and up — the token field and its link stay visible above the keyboard while the `Connect WaniKani` action passes under it. |
| `after-09-hub-connected.png` | The WaniKani hub after connecting: 4 reviews due, lessons all caught up — the state the review captures were taken from. |

## What was not captured

- **Lesson batching (five per batch).** The account has no lessons, so no lesson screen could be
  rendered; `after-09` shows `All caught up`. The behaviour is unchanged by this change and is
  covered in source (`LESSON_BATCH_SIZE = 5` in `src/rn/ui/review/review-store.ts`, applied by
  `enterWaniKaniLessons`) and by `tests/wanikani-hub.test.cjs`.
- **A scrolled review card.** The review card for `苦しい` is shorter than the viewport, so nothing
  scrolls behind the floating button in `after-01`; the "content scrolls behind the action" claim is
  shown on the Anki card and the connect screen instead, which share the same `ActionFooter`.
- **Typing a *wrong* reading/meaning on the review**, and the review's own submission, were
  deliberately not exercised: a submission would move the account's SRS schedule.
- The `after-08` chip strip is Gboard's own suggestion row; the device clipboard was cleared before
  these captures (reboot) so no other app's text shows there.
