# WaniKani review evidence — the answer panel's default detail tab

Captured for branch `fix/wanikani-reading-tab-default` (PR against `dev`): on the WaniKani review
screen the answer panel behind **Show answer** opens on the detail tab that matches the prompt —
**Reading** when the prompt asks for a reading, **Meaning** otherwise — instead of always opening on
Meaning.

This pass captured the **reading-prompt before/after pair** on a real reading prompt, plus a
meaning-prompt control. It cost **one submitted review** (see *Submission*), which was required to
get past the one-part subject that sat ahead of every reading prompt in this queue.

## Revision and environment

| Item | Value |
|---|---|
| Code under test | `656c792` — `fix(wanikani): open the reading detail tab on a reading prompt`, on `fix/wanikani-reading-tab-default` (base `origin/dev`) |
| After build | that commit as-is |
| Before build | the same tree with **only** `setActiveTab(defaultDetailTab(session.part, item))` reverted to `setActiveTab("meaning")` in `src/rn/ui/review/WaniKaniReview.tsx`, restored afterwards (`git checkout HEAD -- …`; `git status` clean apart from this directory) |
| JS actually running | Metro dev server started from this worktree on port 8081, reached by the app over `adb reverse tcp:8081 tcp:8081`; no APK was rebuilt or reinstalled |
| Bundle assertions | After: `defaultDetailTab` ×3 in the served `index.bundle`. Before: `setActiveTab("meaning")` ×1 and `defaultDetailTab` ×2 (the call elided) |
| Device | AVD `Gaten_Lookup_Review` → `emulator-5554`, 1080×2340, Android 14, system font scale 0.94, dark theme |
| Driver | `agent-device`: `press` by coordinate (uiautomator exposes no text nodes in this app), `type` through the headless test IME, `react-native dismiss-overlay` for the LogBox banner, `screenshot`, `record` |
| Account | the project's connected WaniKani test account — see *Submission* |

## Steps

1. Home → **WaniKani** card → hub → **Reviews**. The session was **7 prompts** (`1 of 7`).
2. Prompts 1–3, all **Vocabulary** (2-part) meanings, answered correctly after confirming the
   SubjectBadge: 先端 `tip`, 極端 `extreme`, 途中 `on the way`. Each advanced the cursor with **no**
   submission (a 2-part subject submits only when its *reading* is done).
3. Prompt 4 was a **Radical, Level 28** — a **one-part** subject, so answering it correctly completes
   it and posts. `yurt` was refused; **Show answer** revealed its meaning (**Giant**); `giant` was
   then answered correctly and **Continue** posted it (see *Submission*).
4. Prompt 5 (`5 of 7`) was the first **reading** prompt — **Vocabulary, Level 27**. Typed a
   deliberately wrong reading (`づづz`), **Check** (sets `feedback`, never submits), then **Show
   answer**: the panel opened on **Reading**. Capture `after-01-reading-prompt-reading-tab.png`;
   the Show-answer tap is `reading-show-answer.mp4`.
5. **Before:** reverted only the `setActiveTab` line, **restarted Metro** (an in-place edit plus
   reload served a stale bundle — no watchman on this host — so the change was proven at the bundle
   level first, not assumed), rebuilt, relaunched, re-answered the three meanings, reached the
   reading prompt again (`4 of 6`) and repeated the wrong-reading → **Show answer** step: the panel
   opened on **Meaning**. Capture `before-01-reading-prompt-meaning-tab.png`.
6. Restored the line; verified `src/rn/ui/review/WaniKaniReview.tsx` is back to the fix and `git
   status` shows only this evidence directory.

## Results

| File | Shows |
| --- | --- |
| `after-01-reading-prompt-reading-tab.png` | **After**, reading prompt (先端, Vocabulary L27, `5 of 7`): wrong reading in red, `Incorrect`, **Show answer** open, tabs with **Reading** selected, body showing `Primary reading せんたん`. |
| `before-01-reading-prompt-meaning-tab.png` | **Before**, the same subject and prompt type (先端, `4 of 6`): same wrong-reading state, but the panel opened on **Meaning** (Reading unselected). |
| `after-02-meaning-prompt-meaning-tab.png` | Control, meaning prompt (Kanji 冷, `1 of 123`): panel on **Meaning** while the Reading tab is offered. |
| `after-04-hub-items-reviewed-1.png` | Hub after the pass: **`Items reviewed 1`**, **`3 reviews due`**. |
| `before-02-hub-items-reviewed-0.png` | Hub before the radical was answered: **`Items reviewed 0`**. |
| `reading-show-answer.mp4` | ~5 s clip on the fix build: `Show answer` tapped on the reading prompt, panel opening on **Reading**. |

Both reading captures are the **same subject and the same prompt type**, differing only in the
`setActiveTab` line, so the tab difference is attributable to the change and not to the item.

## Submission

**Exactly one review was submitted**, and it is disclosed here in full:

- The **Level 28 Radical** (the ヨ-shaped radical WaniKani names **"Giant"**) — a **one-part** subject.
  `wkContinue()` posts when the subject's last part is done, and a radical's only part *is* its
  meaning, so a correct answer is the only way past it.
- Before the correct answer the prompt was answered **wrong once** (`yurt`, refused) to reveal the
  meaning from the panel, so the submitted review carries **`incorrect_meaning_answers: 1`**. The
  radical therefore **drops a stage** rather than rises — a real SRS movement, not a no-op.
- It was **necessary, not incidental**: prompts 1–4 are meanings and 5–7 the readings
  (`orderWithGap`, `REVIEW_PROMPT_MIN_GAP = 5`), so the radical sat **ahead of every reading prompt**,
  and the order is deterministic — a relaunch does not change it. Without this submission no reading
  prompt was reachable at all.
- Hub counts bracket it: `Items reviewed` **0** (`before-02`) → **1** (`after-04`).
- Nothing else was submitted. The three readings were never answered correctly; the session was left
  with a wrong reading only, and the wrong readings/meanings used to expose the reveal row are not
  reported to WaniKani because their subjects were never completed.

## Limitations

- Both reading captures are a **wrong-answer** reveal on one subject (先端). The correct-answer
  reveal, the retry-after-Continue path and the end-of-queue state are not shown.
- The pair is on **one** emulator and **one** prompt type; the guard case (a subject with no
  readings) is only partly visible — the radical's own panel renders **no tab row at all**
  (`tabs.length === 1`), which is why no radical screenshot is included as a tab comparison.
- The before capture required a **Metro restart**: with no watchman, an in-place edit plus reload
  served a stale bundle. The first attempt silently captured the *fix* as if it were the base; the
  build was then asserted from the served bundle before re-running. Anyone repeating this must assert
  the bundle, not assume the reload took.
- No accessibility-tree evidence (this app exposes no text nodes); all interaction was by coordinate.
