# PR publish notes — NOT part of the pull request body

**PR:** #74 `https://github.com/Anurag-Raut/Gaten/pull/74` — OPEN, base `dev`, head
`feat/wanikani-study-progress`. The revision updates this PR; no second PR.

**Title (keep as is unless it should change):**

```
feat(wanikani): separate today's progress from available study
```

**Status: HELD.** The revision is committed nowhere. Root reviews the new primary screenshot first and
says explicitly to publish; on that word: commit the two production files plus this evidence
directory, push the branch, regenerate the body copy and update PR #74's body. No merge.

**Body:** `PR_DESCRIPTION.md` in this directory, starting at its `## Changes` heading. Post it
verbatim — no preamble, title or preparation notes; the extra states, the real-app stills, the
regression pair and provenance all sit inside one collapsed `<details>` block.

**Before posting:** substitute the SHA into a temporary publication copy, so the tracked doc keeps its
placeholder and no second commit is needed for a self-referential SHA:

```sh
cd docs/pr-evidence/wanikani-study-progress
SHA=$(git rev-parse HEAD)                       # the revision commit, already pushed
sed "s/<COMMITTED_SHA>/$SHA/g" PR_DESCRIPTION.md > /tmp/wk-pr-body-final.md
gh pr edit 74 --body-file /tmp/wk-pr-body-final.md
```

**Gating:** do not commit, push or edit PR #74 until root authorises it.

**Hashes recorded at capture time** (the head hashes change once the revision is committed):

- base `9350181`; branch commit `1e9aace`; captured head = that commit plus the uncommitted revision
- production diff hash (4 files) `85426fbfc250c89ad1c60edb61ffd308e3c204c85b9df185530d90361b3e691f`
- whole-tree diff hash `eff7d0351de4fac89246c3ad6928ead9df77bd5cf29b4978f2dbf83d4f35fd82`
- `WaniKaniHome.tsx` `8655fe23acf7febdbd805bdc26adbce80e1afa6ba5339a3c45f294391dbe84d1`
- `ink.tsx` `ed9ef05ff51f56ba20c17e2f4d9ebab293460a92641574e4b16170ffb5ba7fab`
- after-production `a6c00619650bb556f53668b5d53ba125b07570e5c0a208cac207f044c26c4803`
- after-fixture `c09ee0cef422b2e1faf5bc0c4673a99b4f19264fa402895e672a0edd46e98070`
- swatch-before-production (regression baseline) `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4`
- fixture patch `4dceb9092d82d097befd36fe75e4edc1d50f96afee3ab9da9513564f0a29bc79`
