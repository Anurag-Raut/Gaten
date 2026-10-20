/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * React Native CLI and Gradle-plugin autolinking configuration. The CLI assumes a template layout
 * with an `android` subfolder; this repo's Gradle root is the repo root, so the Android app module
 * and its package name are pinned here explicitly.
 */
module.exports = {
  project: {
    android: {
      sourceDir: 'app',
      appName: 'app',
      packageName: 'com.gaten.app',
    },
  },
};