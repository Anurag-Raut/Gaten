/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("com.facebook.react")
}

val playBundle = providers.gradleProperty("playBundle").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.gaten.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.gaten"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        androidResources {
            localeFilters += "en"
        }
    }

    androidResources {
        // The manga-ocr weights are downloaded on demand now (ocr/MangaOcrModel.kt), never bundled.
        // A stale copy can still sit in `src/main/assets/manga-ocr` (the old fetch task wrote it, and
        // it is gitignored), so exclude the directory from packaging: "<dir>name" matches a directory
        // name in aapt's ignore pattern. The existing pattern (AGP's own defaults, if any) is kept.
        ignoreAssetsPattern = listOfNotNull(ignoreAssetsPattern, "<dir>manga-ocr").joinToString(":")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests {
            // The classes under test log through android.util.Log, whose stub jar method throws by
            // default. The alternative is threading a logger through every one of them just so a
            // JVM test can run the state machine that has nothing to do with Android.
            isReturnDefaultValues = true
        }
    }

    lint {
        // The AnkiDroid API library ships its own lint checks. Its DuplicateCrowdInStrings is an
        // error there and flags two of our intentional duplicates — the app name repeated across
        // app_name/home_title/qs_tile_label/tile_label, and "Select again" as both a button label
        // and a content description — which would otherwise fail :app:lintVitalRelease.
        disable += "DuplicateCrowdInStrings"
    }

    splits {
        abi {
            isEnable = !playBundle
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        // 17 to match what the React Native Gradle plugin pins for Java; Kotlin must agree.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    dependenciesInfo {
        @Suppress("UnstableApiUsage")
        includeInApk = false
        @Suppress("UnstableApiUsage")
        includeInBundle = false
    }
    buildFeatures {
        compose = true
    }
}

// React Native bundling. `root` points at the repo root, one level above this module, because
// package.json, index.js and the Metro/babel configs live there. Debug builds stay unbundled
// (they load JS from Metro) and release variants bundle index.js into index.android.bundle —
// the plugin's defaults, applied by debuggableVariants.
react {
    root = file("..")
    entryFile = file("../index.js")
    // Adds an implementation dependency on every npm module autolinking found (settings-side
    // autolinkLibrariesFromCommand has already included those projects).
    autolinkLibrariesWithApp()
    // Bundle debug too: the host loads assets://index.android.bundle unconditionally, and a
    // Metro dev server is optional, not a launch requirement.
    debuggableVariants.set(emptyList())
}

// ABI-specific version codes for F-Droid and multi-APK support
// arm64-v8a gets a higher code than armeabi-v7a so it's prioritized on compatible devices
androidComponents {
    onVariants { variant ->
        val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)
        variant.outputs.forEach { output ->
            val abi = output.filters.find { 
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI 
            }?.identifier
            if (abi != null) {
                val baseCode = android.defaultConfig.versionCode ?: 0
                output.versionCode.set(baseCode * 10 + (abiCodes[abi] ?: 0))
            }
        }
    }
}

// The JMdict database is generated (and gitignored), so a fresh clone builds an APK with no
// dictionary at all unless someone remembers to run the builder. Two ways to get that wrong are
// caught here, because both would only show up on a device: a missing asset, and an asset from
// before the current schema — the lookup LEFT JOINs tables an older file never wrote. The builder
// stamps `PRAGMA user_version` with the schema version (tools/build_jmdict_db.py, SCHEMA_VERSION)
// and SQLite keeps it at bytes 60-63 of the file header, so reading those 64 bytes is enough.
val dictionarySchemaVersion = 4
val verifyDictionaryAsset by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/assets/db/jmdict.sqlite")
    description = "Fails the build when the generated JMdict asset is missing or of another schema version."
    doLast {
        val file = asset.asFile
        if (!file.isFile) {
            error("Missing app/src/main/assets/db/jmdict.sqlite — generate it with: python3 tools/build_jmdict_db.py")
        }
        val header = ByteArray(64)
        val read = file.inputStream().use { input -> input.read(header) }
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val version = if (read == header.size && header.copyOfRange(0, magic.size).contentEquals(magic)) {
            ((header[60].toLong() and 0xFF) shl 24) or ((header[61].toLong() and 0xFF) shl 16) or
                ((header[62].toLong() and 0xFF) shl 8) or (header[63].toLong() and 0xFF)
        } else {
            -1L
        }
        if (version != dictionarySchemaVersion.toLong()) {
            error(
                "app/src/main/assets/db/jmdict.sqlite is of schema version $version, " +
                    "this build needs $dictionarySchemaVersion — regenerate it with: " +
                    "python3 tools/build_jmdict_db.py"
            )
        }
    }
}

// The Tanaka Corpus example asset is generated the same way (tools/build_examples_db.py), so the
// same two mistakes are caught: a missing file, and one from another schema version.
val examplesSchemaVersion = 1
val verifyExamplesAsset by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/assets/db/examples.sqlite")
    description = "Fails the build when the generated example-sentence asset is missing or of another schema version."
    doLast {
        val file = asset.asFile
        if (!file.isFile) {
            error("Missing app/src/main/assets/db/examples.sqlite — generate it with: python3 tools/build_examples_db.py")
        }
        val header = ByteArray(64)
        val read = file.inputStream().use { input -> input.read(header) }
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val version = if (read == header.size && header.copyOfRange(0, magic.size).contentEquals(magic)) {
            ((header[60].toLong() and 0xFF) shl 24) or ((header[61].toLong() and 0xFF) shl 16) or
                ((header[62].toLong() and 0xFF) shl 8) or (header[63].toLong() and 0xFF)
        } else {
            -1L
        }
        if (version != examplesSchemaVersion.toLong()) {
            error(
                "app/src/main/assets/db/examples.sqlite is of schema version $version, " +
                    "this build needs $examplesSchemaVersion — regenerate it with: " +
                    "python3 tools/build_examples_db.py"
            )
        }
    }
}

// The Remembering the Kanji reference asset is generated the same way
// (tools/build_rtk_db.py), so the same two mistakes are caught.
val rtkSchemaVersion = 1
val verifyRtkAsset by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/assets/db/rtk.sqlite")
    description = "Fails the build when the generated RTK asset is missing or of another schema version."
    doLast {
        val file = asset.asFile
        if (!file.isFile) {
            error("Missing app/src/main/assets/db/rtk.sqlite — generate it with: python3 tools/build_rtk_db.py")
        }
        val header = ByteArray(64)
        val read = file.inputStream().use { input -> input.read(header) }
        val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        val version = if (read == header.size && header.copyOfRange(0, magic.size).contentEquals(magic)) {
            ((header[60].toLong() and 0xFF) shl 24) or ((header[61].toLong() and 0xFF) shl 16) or
                ((header[62].toLong() and 0xFF) shl 8) or (header[63].toLong() and 0xFF)
        } else {
            -1L
        }
        if (version != rtkSchemaVersion.toLong()) {
            error(
                "app/src/main/assets/db/rtk.sqlite is of schema version $version, " +
                    "this build needs $rtkSchemaVersion — regenerate it with: " +
                    "python3 tools/build_rtk_db.py"
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyDictionaryAsset)
    dependsOn(verifyExamplesAsset)
    dependsOn(verifyRtkAsset)
}

// The manga-ocr weights are no longer bundled: the app downloads them on demand from the settings
// screen (ocr/MangaOcrModel.kt). Any stale copy left in `src/main/assets/manga-ocr` by the old
// `fetchMangaOcrModel` task is excluded from packaging by the `androidResources.ignoreAssetsPattern`
// entry in the `android {}` block above, so an ignored local copy can never ship.

dependencies {
    // ReactActivity (via ReactActivityDelegate) requires an AppCompat theme; the RN bridge
    // brings androidx.appcompat transitively, but the theme reference here needs the artifact.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Navigation 3: the back stack is app-owned state, NavDisplay renders it.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    // Scopes a ViewModel to a NavEntry instead of the Activity.
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    // @Serializable NavKeys; kotlinx-serialization-core is what Navigation3's rememberNavBackStack
    // saves through, so it is a first-class dependency rather than a transitive accident.
    implementation(libs.kotlinx.serialization.core)
    // Romaji in the search box (tokyo -> ときょ): the dictionary itself is Japanese, so an ASCII
    // query is transliterated before it is looked up. Pure Kotlin, MIT, no transitive deps.
    implementation(libs.wanakana.core)
    testImplementation(libs.junit)
    // android.jar ships org.json as a stub whose every method throws, so a local unit test cannot
    // decode anything with the platform's copy. This gives the JVM tests a real implementation to
    // run the WaniKani response decoding against; the app itself still uses the platform's org.json,
    // and nothing about this reaches the APK.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.compose.material:material-icons-extended")

    // On-device Japanese OCR (bundled models, offline, free, no API key)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // React Native runtime and Hermes. The RN Gradle plugin forces their versions from the
    // react-native entry in package.json, so no versions are pinned here.
    implementation("com.facebook.react:react-android")
    implementation("com.facebook.react:hermes-android")

    // AnkiDroid's instant-add API: a ContentProvider, so an export reads and writes on-device and
    // needs no network — nor does anything a card carries: glosses and parts of speech both come from
    // the bundled dictionary in assets/db. The app's INTERNET permission is for optional WaniKani
    // pages, nothing here.
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")

    // ONNX Runtime Mobile, for the manga-ocr second pass (ocr/MangaOcrEngine.kt) and the optional
    // PP-OCRv6 crop engines (ocr/PpOcrEngine.kt). The library asks for INTERNET in its manifest; no
    // weights are bundled — every model is downloaded on demand over Wi-Fi and verified
    // (ocr/PpOcrModel.kt, ocr/MangaOcrModel.kt).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
}
