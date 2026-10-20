/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

module.exports = {
  presets: ['module:@react-native/babel-preset'],
  // Reanimated 4 runs its animations through worklets; the plugin has to be last.
  plugins: ['react-native-worklets/plugin'],
};