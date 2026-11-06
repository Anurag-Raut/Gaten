# PR publish notes — NOT part of the pull request body

**Title (use verbatim):**

```
feat(wanikani): separate today's progress from available study
```

**Base:** `dev` · **Head:** `feat/wanikani-study-progress`

**Body:** `PR_DESCRIPTION.md` in this directory, starting at its `## Changes` heading. Post it
verbatim — it opens at the repo template's `## Changes` section and carries no preamble, title or
preparation notes. Sections present: Changes, Visual evidence, Verification, Problems (every
template bullet in Visual evidence is answered; nothing is left as a placeholder note).

**Before posting:** substitute the SHA into a temporary publication copy, so the tracked doc keeps its
placeholder and no second commit is needed just to embed a self-referential SHA:

```sh
cd docs/pr-evidence/wanikani-study-progress
SHA=$(git rev-parse HEAD)                       # the evidence commit, already pushed
sed "s/<COMMITTED_SHA>/$SHA/g" PR_DESCRIPTION.md > /tmp/wk-pr-body-final.md
gh pr create --base dev --head feat/wanikani-study-progress \
  --title "feat(wanikani): separate today's progress from available study" \
  --body-file /tmp/wk-pr-body-final.md
```

No merge, no force push. The tracked `PR_DESCRIPTION.md` (placeholder intact) and `README.md` (base
SHA, head diff hash, per-APK and per-bundle hashes) stay as the provenance record.

The image URLs are
`raw.githubusercontent.com/Anurag-Raut/Gaten/<COMMITTED_SHA>/docs/pr-evidence/wanikani-study-progress/…`
and the MP4 uses the `blob` form of the same path.

**Gating:** do not commit, push or open the PR until root authorises it on top of the user's git
approval.

**Head hashes recorded at capture time** (they change once the commit exists):

- base `9350181`; head working-tree diff hash
  `776076f72f28a85a371737da587a4f179a5a57ccf7184ce538992c7003d410e2`
- `after-production.apk` `06668b2dc2d7b4a91b0d9ef42660afd1f68ad03dea03eb6544862e4cb19787a4`
- `before-production.apk` `56f93104630ecb0e6f4a785f5248dea185f5cf13aab95909aa1876f2870b267e`
- `after-fixture.apk` `ea54df17c6f9c879f96685142e0fa327a29ffea4a60c93aa83e9dd6b5ddd715d`
- `before-fixture.apk` `c06db074d2e916e90a395431c05b98ca7a8b9fc5295721e44192cb8d6d558eec`
- fixture patch `8b462e733155c9068eb2c7990f21e4445e318a0a367559e451180f2e6f9c24a9`
