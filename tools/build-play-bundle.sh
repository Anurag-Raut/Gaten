#!/usr/bin/env bash
# Gaten
# Copyright (C) 2026 Anurag Raut
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Build the Play app bundle (.aab), including the `ppocr` fast-follow asset pack that carries the
# Enhanced (PP-OCRv6 Medium) model. Upload the result to Play; APKs can never receive the pack.
#
# Usage: tools/build-play-bundle.sh [versionCode]
#   versionCode  optional; passed as -PversionCode (release.yml uses 1000 + run number).
#
# The first run needs network: stagePpOcrAssets downloads and SHA-256-verifies the model.
# Signing comes from the same keystore environment variables as the release APK build.
set -euo pipefail
cd "$(dirname "$0")/.."

args=(:app:bundleRelease --stacktrace -PplayBundle=true)
if [[ $# -ge 1 ]]; then args+=("-PversionCode=$1"); fi
# Packaging the bundle exhausts the 2 GB default Gradle heap (see release.yml).
args+=(-Dorg.gradle.jvmargs='-Xmx4g -Dfile.encoding=UTF-8')

./gradlew "${args[@]}"

aab=app/build/outputs/bundle/release/app-release.aab
test -f "$aab" || { echo "bundle not found at $aab" >&2; exit 1; }
echo "Built $aab"
