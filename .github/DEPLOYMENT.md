# Internal testing releases

| Action | Result |
| --- | --- |
| Push to `dev` | No push workflow or Play upload |
| Open or update a pull request; push to `main` | Existing CI checks and debug APK |
| Save a draft release | No deployment |
| Publish a GitHub pre-release tagged from `dev` | Signed APKs and AAB; AAB uploaded to Play internal testing |
| Publish a regular release | Release job skipped; no deployment |

The release name is your choice: "Internal test", "Beta", and "Nightly" all work.
The **pre-release checkbox** controls deployment, not the name or tag prefix.
The workflow builds the tagged commit, checks that it is in `dev`'s history,
and uploads only to the existing `com.gaten` internal testing track.

## One-time setup

1. Merge the workflow changes into `dev` before creating a release tag. The tagged
   commit must contain `.github/workflows/release.yml`. This release event uses the
   tagged commit's workflow; a separate merge into `main` is not required.
2. Keep these repository Actions secrets configured:
   - `ANDROID_KEYSTORE_BASE64`: base64-encoded upload keystore.
   - `ANDROID_KEYSTORE_PASSWORD`: keystore password.
   - `ANDROID_KEY_ALIAS`: upload key alias.
   - `ANDROID_KEY_PASSWORD`: upload key password.
   - `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: complete service account JSON key.
3. In Play Console, the app must exist as `com.gaten`. Enable the Google Play
   Android Developer API in the service account's Cloud project and grant that
   account permission to release this app to testing tracks.
4. Add tester accounts in Play Console's internal testing settings and share the
   opt-in link. Testers must opt in to receive updates.

The existing secrets can be reused. To encode a keystore on Linux, use
`base64 -w 0 upload-key.jks`; keep its output and the service account key private.

## Publish through GitHub

1. Merge the changes you want to test into `dev`.
2. Open the repository's **Releases → Draft a new release** page.
3. Create a unique tag, for example `internal-2026-10-24.1`, and select **Target:
   dev**. Increment the final number for another release on the same date.
4. Enter a title and release notes describing what testers should check.
5. Check **This is a pre-release**, then click **Publish release**. Saving a draft
   only prepares it; publishing starts the build and internal Play upload.
6. Open **Actions → Release** and inspect the run for that tag. After success,
   download the APKs and AAB from the run's **Artifacts** section and confirm the
   new version in Play Console's internal testing track. These files are Actions
   artifacts, not attachments on the GitHub Release page.

The GitHub tag does not change the Android version shown to users. `versionName`
remains the value in `app/build.gradle.kts`; change it deliberately when needed.
The AAB's `versionCode` is `1000 +` this release workflow's run number, starting
above the old CI counter (87 when this flow was introduced). The existing Gradle
configuration gives standalone APKs ABI-specific version codes. Keep any future
workflow counter or offset changes above the highest version already uploaded
to Play. GitHub's run ID is too large to use as an Android version code.

## Agent CLI procedure

Use this procedure when the user asks to publish an internal testing release.
Use their requested commit if supplied; otherwise use the latest remote `dev`
commit. Publishing distributes the build to internal testers. If the user asks
only for a draft, add `--draft` to the release command and report the draft URL.

1. Fetch `dev`, resolve a fixed commit, and check that it includes the workflow:

   ```sh
   git fetch origin dev
   release_commit=$(git rev-parse origin/dev)
   git cat-file -e "${release_commit}:.github/workflows/release.yml"
   ```

   For an explicitly requested commit, resolve that instead and verify
   `git merge-base --is-ancestor "$release_commit" origin/dev` succeeds. Stop and
   report the reason if it is outside `dev` or does not include the workflow.
   A local uncommitted change is not included in a release.

2. Choose an unused `internal-YYYY-MM-DD.N` tag using the current date. Check tags
   with `git ls-remote --tags origin` and increment `N` until unused. Write the
   release notes to a temporary file, summarizing changes and what to test.

3. Publish with a fixed SHA so a concurrent `dev` push cannot change the build.
   Replace the example tag and notes path below with the chosen values:

   ```sh
   release_tag=internal-2026-10-24.1
   gh release create "$release_tag" \
     --repo Anurag-Raut/Gaten \
     --target "$release_commit" \
     --prerelease \
     --title "Internal test $release_tag" \
     --notes-file /path/to/release-notes.md
   ```

   `--target` is required: omitting it would create the tag on the default branch.
   `--prerelease` is required for this workflow to build and upload.

4. Find the matching release run and follow it to completion:

   ```sh
   gh run list --repo Anurag-Raut/Gaten --workflow release.yml \
     --event release --commit "$release_commit" --limit 5
   gh run watch RUN_ID --repo Anurag-Raut/Gaten --exit-status
   ```

   If several runs share the same commit, select the run for the newly created
   tag. Allow time for the event to arrive. If the workflow has not appeared in
   the Actions menu yet, look in the repository's overall Actions run list.

5. Report the release URL, tag, commit SHA, workflow run URL, and whether **Upload
   to Google Play internal testing** succeeded. Include artifact names. A published
   GitHub Release alone is not proof of a successful Play upload.

## Failed or skipped releases

- A regular release skips the release job. Checking the pre-release box later
  is an edit, not a new `published` event. Publish a new pre-release with a new tag.
- A tag outside `dev` fails before building. Create a new tag from the intended
  `dev` commit rather than moving an existing release tag.
- If a run failed before the Play upload, fix the reported problem and rerun it.
  Reruns retain the same version code. If that code was already uploaded to Play,
  publish a new pre-release so the next run gets a new code.
- If Play requires the first upload to be manual, download the saved AAB from
  the run and upload it through Play Console. Use the same upload keystore later.

GitHub documents the [`release` event](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)
and [pre-release creation](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository).
