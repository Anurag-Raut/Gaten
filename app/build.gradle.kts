/*
 * YomiLens
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yomilens.app"
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
        applicationId = "com.yomilens.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        androidResources {
            localeFilters += "en"
        }
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

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
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
// dictionary at all unless someone remembers to run the builder. Fail early instead: the wording
// below is the exact command that fixes it.
val verifyDictionaryAsset by tasks.registering {
    val asset = layout.projectDirectory.file("src/main/assets/db/jmdict.sqlite")
    description = "Fails the build when the generated JMdict asset is missing."
    doLast {
        if (!asset.asFile.isFile) {
            error("Missing app/src/main/assets/db/jmdict.sqlite — generate it with: python3 tools/build_jmdict_db.py")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyDictionaryAsset)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.7.0")

    // On-device Japanese OCR (bundled models, offline, free, no API key)
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
}
