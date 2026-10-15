/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * Portions derived from CircleToSearch, Copyright (C) 2025 AKS-Labs
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// Explicit: in a build script `java` is the Java plugin's extension, not a package.
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

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
        applicationId = "com.gaten.app"
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
// dictionary at all unless someone remembers to run the builder. Two ways to get that wrong are
// caught here, because both would only show up on a device: a missing asset, and an asset from
// before the current schema — the lookup LEFT JOINs tables an older file never wrote. The builder
// stamps `PRAGMA user_version` with the schema version (tools/build_jmdict_db.py, SCHEMA_VERSION)
// and SQLite keeps it at bytes 60-63 of the file header, so reading those 64 bytes is enough.
val dictionarySchemaVersion = 3
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

tasks.named("preBuild") {
    dependsOn(verifyDictionaryAsset)
}

// The optional manga-ocr model (~117 MB) is never shipped in a release APK: the user imports it
// from device storage, or a debug build downloads it here. `-PbundleMangaOcr=true` makes Gradle
// fetch the three files into a gitignored, debug-only asset directory (release variants never see
// it) so the debug APK carries the model for testing; without the property nothing is downloaded.
val bundleMangaOcr = providers.gradleProperty("bundleMangaOcr").map { it.toBoolean() }.getOrElse(false)
val mangaOcrAssets = layout.projectDirectory.dir("src/debug/assets/manga-ocr")

/** Streams [url] into [target] through a temp file, following redirects (Hugging Face serves 307s). */
fun downloadFollowingRedirects(url: String, target: File) {
    val temp = File(target.parentFile, "${target.name}.part")
    var current = URI(url).toURL()
    var redirects = 0
    while (true) {
        val connection = (current.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "gaten-gradle")
        }
        connection.connect()
        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location == null) error("HTTP $code from $current without a Location header")
            if (++redirects > 5) error("too many redirects for $url")
            current = current.toURI().resolve(location).toURL()
            continue
        }
        if (code != 200) {
            connection.disconnect()
            error("HTTP $code from $current")
        }
        connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        connection.disconnect()
        break
    }
    if (!temp.renameTo(target)) {
        target.delete()
        if (!temp.renameTo(target)) error("cannot move ${temp.path} to ${target.path}")
    }
}

val fetchMangaOcrModel by tasks.registering {
    val targetDir = mangaOcrAssets.asFile
    val downloads = mapOf(
        "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/encoder_model_int8.onnx" to "encoder.onnx",
        "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx/decoder_model_int8.onnx" to "decoder.onnx",
        "https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt" to "vocab.txt"
    )
    description = "Downloads the optional manga-ocr ONNX model into ${mangaOcrAssets.asFile.relativeTo(projectDir)} (debug builds with -PbundleMangaOcr=true)."
    // Nothing to do while every file is there, so a rebuild after the first fetch is free.
    outputs.upToDateWhen { downloads.values.all { File(targetDir, it).isFile } }
    doLast {
        targetDir.mkdirs()
        for ((url, name) in downloads) {
            val file = File(targetDir, name)
            if (file.isFile && file.length() > 0) {
                logger.lifecycle("manga-ocr: $name is already there, skipping")
                continue
            }
            logger.lifecycle("manga-ocr: downloading $url")
            try {
                downloadFollowingRedirects(url, file)
            } catch (e: Exception) {
                error("Could not download $url: ${e.message} — remove ${targetDir.path} and retry, or use tools/fetch_manga_ocr.sh")
            }
            logger.lifecycle("manga-ocr: wrote $name (${file.length() / 1024 / 1024} MB)")
        }
    }
}

// Only the debug build, and only when asked for: the model must never reach a release APK, and a
// normal debug build must not touch the network.
if (bundleMangaOcr) {
    tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
        dependsOn(fetchMangaOcrModel)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
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

    // AnkiDroid's instant-add API: a ContentProvider, so an export reads and writes on-device and
    // needs no network — nor does anything a card carries: glosses and parts of speech both come from
    // the bundled dictionary in assets/db. The app's INTERNET permission is for the optional OCR
    // model download and the optional WaniKani pages, nothing here.
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")

    // ONNX Runtime Mobile, for the optional manga-ocr second pass (ocr/MangaOcrEngine.kt). The
    // library asks for INTERNET in its manifest, which the app manifest removes; the model itself
    // is never bundled in a release APK (see fetchMangaOcrModel above).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
}
