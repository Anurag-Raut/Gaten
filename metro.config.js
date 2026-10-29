/**
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

const { getDefaultConfig, mergeConfig } = require('@react-native/metro-config');
const { withReactNativeGrab } = require('react-native-grab/metro');

/**
 * Metro configuration for React Native 0.86.
 * https://reactnative.dev/docs/metro
 */
const config = {};

module.exports = withReactNativeGrab(mergeConfig(getDefaultConfig(__dirname), config));
