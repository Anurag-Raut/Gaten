/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

pluginManagement {
    // React Native's Gradle plugin lives in node_modules and is built from source. Explicit path:
    // the settings file sits at the repo root (no android/ subfolder), so a bare relative name
    // would be resolved against Gradle's startup directory, not here.
    includeBuild("node_modules/@react-native/gradle-plugin")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
    id("com.facebook.react.settings")
}
dependencyResolutionManagement {
    // PREFER_SETTINGS, not FAIL_ON_PROJECT_REPOS: the React Native Gradle plugin adds filtered
    // project-level repositories (mavenCentral/google) to every project, and the settings
    // repositories below are the effective ones for :app.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Gaten"
include(":app")

// The PP-OCRv6 Medium fast-follow asset pack is part of a Play *bundle* only: install-time and
// fast-follow packing has no APK equivalent, and :app:assembleDebug/assembleRelease must build with
// no knowledge of the pack (and no network). Including the module — and :app's `assetPacks` entry,
// which is conditional in the same way — only under -PplayBundle=true keeps that true.
val playBundle = providers.gradleProperty("playBundle").map(String::toBoolean).getOrElse(false)
if (playBundle) include(":ppocr")

// React Native autolinking: any npm module that ships an android/ Gradle project is included
// automatically. The command is the bundled CLI, and it must run from the repo root where
// package.json lives — that is this settings directory, not the conventional android/ subfolder
// of a template app, hence the explicit overrides of the plugin's defaults.
extensions.configure<com.facebook.react.ReactSettingsExtension> {
    autolinkLibrariesFromCommand(
        workingDirectory = settings.rootDir,
        lockFiles = settings.layout.rootDirectory.files(
            "package-lock.json",
            "package.json",
            "react-native.config.js",
        ),
    )
}
