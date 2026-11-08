/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.gradle.api.artifacts.ConfigurablePublishArtifact

/**
 * The `ppocr` fast-follow asset pack: the optional PP-OCRv6 Medium recognition model, delivered by
 * Google Play instead of an in-app download.
 *
 * The pack is this module's `src/main/assets/pp-ocr/medium/…`, which Google Play serves verbatim;
 * `ocr/PpOcrPack.kt` in the app reads it back through `AssetPackLocation.assetsPath()`. Nothing here
 * is committed — the weights are fetched and verified by [stagePpOcrAssets] into the pack, and the
 * staged path is gitignored — and the pack reaches a device only inside an app bundle (built with
 * `-PplayBundle=true`), never an APK.
 *
 * The module is only in the build graph when `-PplayBundle=true` (settings.gradle.kts), so an APK
 * build never evaluates it and never needs these files or a network.
 */
plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("ppocr")
    dynamicDelivery {
        // Delivered right after install, in the background: the model is what the "Recommended"
        // engine needs, but nothing at launch depends on it, so the app must not wait for it.
        deliveryType.set("fast-follow")
    }
}

/** The pack's asset root, and the path the app reads inside it. */
val packAssets = "src/main/assets"
val packAssetPath = "pp-ocr/medium"

/**
 * One file the pack must ship: the pinned upstream source, and what the staged copy must be. These
 * hashes are the ones recorded in docs/ocr-engines.md; the app trusts a pack file by size alone
 * because this task verified its hash here, at build time.
 */
data class PinnedAsset(val name: String, val url: String, val sha256: String, val size: Long)

/** PaddlePaddle's official PP-OCRv6 medium recogniser and the dictionary it decodes through. */
val pinnedAssets = listOf(
    PinnedAsset(
        name = "rec.onnx",
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/" +
            "50c7eacafc52fa7bcf4194e8cd08e46f8558504b/inference.onnx",
        sha256 = "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
        size = 76_554_979L
    ),
    PinnedAsset(
        name = "dict.txt",
        url = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/" +
            "b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt",
        sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
        size = 74_947L
    )
)

/**
 * Puts the pinned files into the pack's assets, fetching only what is missing or wrong.
 *
 * A file already at its pinned size and SHA-256 is left alone, so a staged pack (and a rebuilt
 * bundle) needs the network once. Anything fetched is written to a `.part` beside its destination
 * and renamed only after its size and hash are checked, so an interrupted fetch can never leave a
 * half-written model looking staged. The task never counts as up to date: the size/hash check is the
 * authority, not a timestamp, and it is what also rejects a replaced or truncated local file.
 */
val stagePpOcrAssets by tasks.registering {
    description = "Fetches and verifies the pinned PP-OCRv6 Medium model into the asset pack."
    val directory = layout.projectDirectory.dir("$packAssets/$packAssetPath")
    outputs.dir(directory)
    outputs.upToDateWhen { false }
    doLast {
        val target = directory.asFile
        target.mkdirs()
        for (asset in pinnedAssets) {
            val destination = File(target, asset.name)
            if (destination.isFile && destination.length() == asset.size &&
                sha256Of(destination) == asset.sha256
            ) {
                logger.lifecycle("ppocr: ${asset.name} is already staged and verified")
                continue
            }
            logger.lifecycle("ppocr: fetching ${asset.name} (${asset.size} bytes)")
            fetchPinned(asset, destination)
            logger.lifecycle("ppocr: staged and verified ${asset.name}")
        }
    }
}

/** Streams one file to a `.part` beside its destination, verifies it, then renames it into place. */
fun fetchPinned(asset: PinnedAsset, destination: File) {
    val part = File(destination.parentFile, "${destination.name}.part")
    part.delete()
    try {
        var current = URI(asset.url).toURL()
        val protocol = current.protocol
        var hops = 0
        while (true) {
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.requestMethod = "GET"
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw GradleException("Redirect without a Location from $current")
                    if (++hops > 5) throw GradleException("Too many redirects for ${asset.name}")
                    val next = current.toURI().resolve(location).toURL()
                    if (next.protocol != protocol) {
                        throw GradleException("Redirect from $protocol to ${next.protocol} refused")
                    }
                    current = next
                    continue
                }
                if (status !in 200..299) throw GradleException("HTTP $status from $current")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            total += read
                        }
                    }
                }
                if (total != asset.size) {
                    throw GradleException(
                        "Expected ${asset.size} bytes for ${asset.name}, received $total"
                    )
                }
                if (!digest.digest().toHex().equals(asset.sha256, ignoreCase = true)) {
                    throw GradleException("Checksum mismatch for ${asset.name}")
                }
            } finally {
                connection.disconnect()
            }
            break
        }
        if (!part.renameTo(destination)) {
            throw GradleException("Could not install ${asset.name}")
        }
    } catch (e: Throwable) {
        part.delete()
        throw e
    }
}

/** The file's SHA-256 as lowercase hex. */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

fun ByteArray.toHex(): String {
    val hex = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 0xFF
        hex[index * 2] = "0123456789abcdef"[value ushr 4]
        hex[index * 2 + 1] = "0123456789abcdef"[value and 0x0F]
    }
    return String(hex)
}

/*
 * The asset-pack plugin publishes `src/main/assets` as its consumed `packElements` artifact but
 * registers no producer for it, so Gradle would otherwise be free to hand the bundle an empty pack.
 * Attaching the staging task to that artifact is what makes it a real dependency of the bundle —
 * and of nothing else, since only a bundle build consumes the pack at all.
 */
configurations.named("packElements") {
    artifacts.forEach { artifact ->
        (artifact as? ConfigurablePublishArtifact)?.builtBy(stagePpOcrAssets)
    }
}
