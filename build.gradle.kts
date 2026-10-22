/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // React Native: configures project-wide New Architecture properties and orders :app
    // evaluation before the autolinked libraries.
    id("com.facebook.react.rootproject")
}

// Older autolinked libraries look for React Native's prebuilt android/ directory.
// RN 0.86 keeps its sources under ReactAndroid, so point those checks at the package root.
extra["reactNativeAndroidRoot"] = file("node_modules/react-native")

// React Native ecosystem libraries read these from the root project ext (the `safeExtGet` pattern
// in their android/build.gradle) instead of hardcoding SDK levels. Kept in sync with the app
// module's minSdk/targetSdk/compileSdk.
buildscript {
    extra["minSdkVersion"] = 29
    extra["compileSdkVersion"] = 36
    extra["targetSdkVersion"] = 36
}
