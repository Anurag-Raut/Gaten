# Internal testing deployment

Pushes to `dev` run the existing checks, build a signed Android App Bundle, and upload it to the Google Play internal testing track. The bundle keeps the app's `versionName` from `app/build.gradle.kts` (currently `0.1.0`). Its Play `versionCode` comes from the GitHub Actions run number, so each workflow run has a higher build number. The Play release is named `dev-<run number>` for easy identification. When preparing a public release, update `versionName` deliberately; do not reset `versionCode`.

## GitHub secrets required

Add these under **Repository Settings → Secrets and variables → Actions → New repository secret**:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded upload keystore file.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: upload key alias.
- `ANDROID_KEY_PASSWORD`: upload key password.
- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: complete JSON key contents for a Google service account with access to the app's Play Console.

Encode an existing JKS/keystore file locally with `base64 -w 0 upload-key.jks` on Linux. Keep the keystore and JSON key private; do not commit them.

## Google Play setup

The app must first exist in Play Console with package name `com.gaten`. Create or select a Google Cloud project, enable the Google Play Android Developer API, and create a service account. Invite that service account in Play Console under **Users and permissions**, granting it permission to release apps to testing tracks. The workflow saves each signed bundle as a GitHub artifact before attempting its Play upload. If Play requires the first bundle to be uploaded manually, download that artifact from the Actions run and upload it in Play Console. Use the same upload keystore for subsequent CI builds.

After adding secrets, merge this setup into the GitHub repository's `dev` branch. The first push to `dev` will run the deployment. Add tester Google accounts in Play Console's internal testing settings; testers then opt in using the Play testing link.
