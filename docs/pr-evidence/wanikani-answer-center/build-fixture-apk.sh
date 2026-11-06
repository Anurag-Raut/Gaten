#!/usr/bin/env bash
#
# Builds the WaniKani review-answer evidence fixture: the production app with one non-production
# entry file, so the review screen renders without a WaniKani account. Nothing in src/, index.js,
# App.js or app/build.gradle.kts changes: ENTRY_FILE is the React Native Gradle plugin's own
# override, and the copied `.evidence-fixture/` directory is the only thing added to the tree.
#
# Used from the repo root:  docs/pr-evidence/wanikani-answer-center/build-fixture-apk.sh
#
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"

cd "$root"
rm -rf .evidence-fixture
cp -r "$here/fixture" .evidence-fixture

# The repo's own tsconfig includes src/rn only, so the fixture is typechecked on its own.
npx tsc --noEmit -p .evidence-fixture/tsconfig.json

flock /tmp/gaten-build.lock \
  env ENTRY_FILE=.evidence-fixture/index.js \
  ./gradlew --no-daemon :app:assembleDebug -PreactNativeArchitectures=x86_64

apk="app/build/outputs/apk/debug/app-x86_64-debug.apk"
sha256sum "$apk"
echo "fixture APK: $root/$apk"
