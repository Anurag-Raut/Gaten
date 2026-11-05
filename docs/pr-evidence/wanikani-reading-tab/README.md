# WaniKani review evidence — the answer panel's default detail tab

Captured for branch `fix/wanikani-reading-tab-default` (PR against `dev`): on the WaniKani review
screen the answer panel behind **Show answer** opens on the detail tab that matches the prompt —
**Reading** when the prompt asks for a reading, **Meaning** otherwise — instead of always opening on
Meaning.

This pass produced the **meaning-prompt control** still only. The reading-prompt before/after pair is
**blocked** (see below): the connected account's review queue was exhausted before a reading prompt
could be reached.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `656c792` — `fix(wanikani): open the reading detail tab on a reading prompt`, on `fix/wanikani-reading-tab-default`, cut from `origin/dev` |
| JS actually running | Metro dev server started from this worktree on port 8081 (`adb reverse tcp:8081 tcp:8081`); no APK was rebuilt |
| App | `com.gaten` debug build already installed on the emulator |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080×2340, Android 14, system font scale 0.94, dark theme |
| Fixtures | The project's existing connected WaniKani test account. No review was submitted. |

## Steps

1. App opened on `emulator-5554` with Metro serving this worktree over `adb reverse`; the LogBox
   banner was dismissed.
2. Home → **WaniKani** card → hub → **Reviews** row → the review queue.
3. On the first prompt (Kanji 冷, Level 28, part **Meaning**) the field was filled with a
   deliberately wrong answer (`zzz`). A wrong answer sets the feedback without submitting or
   advancing, which is what exposes the **Show answer** row.
4. **Show answer** tapped; the detail panel opened. Capture `after-02-meaning-prompt-meaning-tab.png`.

## Results

| File | Shows |
|---|---|
| `after-02-meaning-prompt-meaning-tab.png` | A **meaning** prompt (`1 of 123`, Kanji 冷, Level 28): a wrong answer `zzz` in red, `Incorrect` feedback, **Show answer** open, and the detail tab row with **Meaning** selected. The subject carries a reading — the **Reading** tab is present in the row — and the panel still opened on Meaning, which is the required meaning case. |

SHA-256 of the PNG: `d92a05e4798e8045ef932bbb93a62c9f60490da4730a18747a9a8d0fd5d07a47`
(1080×2340, 107,680 bytes).

## Blocked: the reading-prompt before/after

The reading-prompt pair — the same panel on a prompt whose part is **reading**, once on the base code
(panel on Meaning) and once on this change (panel on Reading) — **could not be captured on this
pass**. Reaching a reading prompt requires first answering the meanings that precede it: the prompt
order puts a subject's meaning before its reading and holds them at least `REVIEW_PROMPT_MIN_GAP = 5`
(never more than `REVIEW_PROMPT_MAX_GAP = 10`) prompts apart, so the first reading prompt sits behind
5–10 correct meaning answers. Part-way through that drive the account's queue was exhausted — the hub
went from `68 reviews due` to `0 reviews · All caught up · Next in 1 hour 42 min`. With no reviews
due no reading prompt was reachable, so the pair was not produced. A driver desynchronisation on the
vocabulary layout also cost a pass before that.

What the pair would have shown is covered at the unit level by `tests/wanikani-review-default-tab.test.cjs`
(5/5) and by the rule in `src/rn/ui/review/wanikani-detail-tabs.ts`; the code path itself is not
device-demonstrated here.

## Limitations

- One emulator, one pass. The reading-prompt default is **not** shown on a device; only the
  meaning-prompt control was produced, so the change's headline case carries no rendered evidence.
- The still shows a **wrong**-answer reveal only. The correct-answer reveal, the retry-after-Continue
  path, and the "a wrong answer never opens the panel" rule were not exercised.
- The account's queue was empty at the end of the pass; a later pass with reviews due would be needed
  to reach a reading prompt. Nothing here claims the device would have matched the unit rule.
