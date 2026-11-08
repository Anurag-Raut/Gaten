/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The optional PP-OCRv6 recognition models, fetched on demand.
 *
 * Both are line recognisers only: the boxes come from the detector that is already there (ML Kit /
 * [ScreenLineReader]), so nothing here detects or captures. Two engines are offered —
 *
 *  - [Engine.MEDIUM]: PaddlePaddle's official `PP-OCRv6_medium_rec` ONNX (76.6 MB), for ordinary
 *    screen and novel text. 50 languages, one model.
 *  - [Engine.MANGA]: Kellenok's `manga_rec_v0.2` ONNX (21.2 MB), a PP-OCRv6-small fine-tune for
 *    manga lettering, stylised fonts and outlined thumbnail text.
 *
 * Both take the same `ppocrv6_dict.txt` (18,708 symbols); the two upstream copies are byte-identical
 * and each source is pinned and hash-verified separately below. Both graphs were read from the ONNX
 * files themselves: input `x` float32 `[N,3,48,W]` (W dynamic), output `fetch_name_0` float32
 * `[N,T,18710]` with `T` about `W/8`.
 *
 * Nothing is downloaded unless [download] is called: [PpOcrEngine] only ever reads files that are
 * already installed. A transfer uses whatever connection the device has (the settings screen warns
 * about mobile data before starting one), follows a bounded number of same-protocol redirects over
 * HTTPS, and is checked against the file's exact size and SHA-256 before it is renamed into place, so
 * a partial or tampered download can never be mistaken for an installed model. Files live under
 * [Context.getNoBackupFilesDir], so Android's backup never carries them.
 */
object PpOcrModel {
    private const val TAG = "GatenOcr"

    /**
     * The upstream recognition models this app can install.
     *
     * [revision] is this catalog's identity for the engine: it is written next to the files once an
     * install succeeds, and [isInstalled] requires it to still match, so changing an upstream file
     * forces a re-download. [approximateDownloadBytes] is the sum of the pinned file sizes.
     */
    enum class Engine(
        val id: String,
        val displayName: String,
        val revision: String,
        val approximateDownloadBytes: Long
    ) {
        MEDIUM("medium", "PP-OCRv6 Medium", "ppocrv6-medium-onnx-50c7eaca", 76_629_926L),
        MANGA("manga", "PP-OCRv6 Manga v0.2", "ppocrv6-manga-02af751f", 21_242_487L)
    }

    /** One file to install: where it comes from, and what it must be. */
    internal data class ModelFile(
        val name: String,
        val url: String,
        val sha256: String,
        val size: Long
    )

    private const val MODEL_FILE = "rec.onnx"
    private const val DICT_FILE = "dict.txt"
    private const val REVISION_FILE = "revision"

    // PaddlePaddle/PP-OCRv6_medium_rec_onnx @ 50c7eacafc52fa7bcf4194e8cd08e46f8558504b
    // (ONNX IR 6, opset 11; dict: PaddlePaddle/PaddleOCR @ b03f46425e8ff4442b268ce449e3eef758146cd4).
    private val MEDIUM_FILES = listOf(
        ModelFile(
            MODEL_FILE,
            "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/" +
                "50c7eacafc52fa7bcf4194e8cd08e46f8558504b/inference.onnx",
            "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
            76_554_979L
        ),
        ModelFile(
            DICT_FILE,
            "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/" +
                "b03f46425e8ff4442b268ce449e3eef758146cd4/ppocr/utils/dict/ppocrv6_dict.txt",
            "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
            74_947L
        )
    )

    // Kellenok/PP-OCRv6_manga @ 02af751fcea728fba69ee421a95838f3e549daa2 (ONNX IR 7, opset 14).
    private val MANGA_FILES = listOf(
        ModelFile(
            MODEL_FILE,
            "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/" +
                "02af751fcea728fba69ee421a95838f3e549daa2/rec/manga_rec_v0.2.onnx",
            "de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888",
            21_167_540L
        ),
        ModelFile(
            DICT_FILE,
            "https://huggingface.co/Kellenok/PP-OCRv6_manga/resolve/" +
                "02af751fcea728fba69ee421a95838f3e549daa2/ppocrv6_dict.txt",
            "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
            74_947L
        )
    )

    internal fun files(engine: Engine): List<ModelFile> = when (engine) {
        Engine.MEDIUM -> MEDIUM_FILES
        Engine.MANGA -> MANGA_FILES
    }

    /** The directory an engine's files live in, never included in Android's backup. */
    internal fun dir(context: Context, engine: Engine): File =
        File(context.noBackupFilesDir, "pp-ocr/${engine.id}")

    /** The ONNX recogniser for [engine]. */
    internal fun modelFile(context: Context, engine: Engine): File = File(dir(context, engine), MODEL_FILE)

    /** The character dictionary for [engine]. */
    internal fun dictFile(context: Context, engine: Engine): File = File(dir(context, engine), DICT_FILE)

    /**
     * True when [engine]'s files are present at their pinned sizes and the revision marker matches
     * the catalog, i.e. [PpOcrEngine] can load them. Cheap: no hashing, so it is safe on every call.
     * A missing or unreadable marker reads as "not installed" rather than throwing.
     */
    fun isInstalled(context: Context, engine: Engine): Boolean {
        val directory = dir(context, engine)
        if (files(engine).any { !it.isPresentIn(directory) }) return false
        return try {
            File(directory, REVISION_FILE).readText().trim() == engine.revision
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the ${engine.id} revision marker", e)
            false
        }
    }

    /**
     * Downloads and installs [engine]'s files, explicitly requested by the user. The transfer uses
     * the device's current connection — the settings screen asks before starting one and warns about
     * mobile data — through the platform's normal routing; a retry opens a fresh connection, which may
     * pick up a different network but can also fail normally.
     *
     * Each file is fetched to a `.part` beside its destination, checked against its pinned size and
     * SHA-256, and only then renamed into place; a failure removes that partial file and throws. A
     * file already at its pinned size is re-hashed before it is trusted, so a leftover from an
     * interrupted run is never blessed unverified. Retryable transport errors are retried with
     * backoff; an integrity failure stops immediately.
     *
     * Safe to call from several coroutines: installs are serialised on one lock.
     *
     * [onProgress] is called with the cumulative bytes written and the engine's total as the
     * download advances, and once more at [total, total] when the bytes are all in; it is never
     * called after [download] returns.
     */
    suspend fun download(
        context: Context,
        engine: Engine,
        onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null
    ) {
        ModelInstall.install(
            displayName = engine.displayName,
            directory = dir(context, engine),
            markerName = REVISION_FILE,
            revision = engine.revision,
            files = files(engine),
            installed = { isInstalled(context, engine) },
            onProgress = onProgress
        )
    }

    /**
     * The revisions of the engines currently installed, `id@revision` joined by `;` in enum order, or
     * an empty string when none is. A caller can show it or cache against it; a change means the
     * installed files differ from what this catalog describes.
     */
    fun revision(context: Context): String = Engine.entries
        .filter { isInstalled(context, it) }
        .joinToString(";") { "${it.id}@${it.revision}" }

    /**
     * A download that cannot be completed. [reason] says which part failed, so a caller can tell a
     * transfer that may succeed on a fresh connection from bytes that will never pass their check.
     */
    class DownloadException(
        message: String,
        cause: Throwable? = null,
        val reason: Reason = Reason.INTEGRITY
    ) : Exception(message, cause) {
        enum class Reason {
            /** Wrong size or hash, or a download that cannot be trusted; a retry would not change it. */
            INTEGRITY,
            /** The connection failed: DNS, a connect or read timeout, or a refused hop. */
            TRANSPORT,
            /** The filesystem refused a write or a rename. */
            STORAGE
        }
    }

    private fun ModelFile.isPresentIn(directory: File): Boolean {
        val file = File(directory, name)
        return file.isFile && file.length() == size
    }
}

/**
 * Installs a catalogue of pinned files, shared by every on-demand OCR model ([PpOcrModel]'s two
 * engines and [MangaOcrModel]'s manga-ocr pair).
 *
 * The transfer uses the device's current connection with the platform's normal routing; a retry opens
 * a fresh connection, which may take a different network if the current one changed. Each file is
 * fetched to a `.part` beside its destination, checked against its pinned size and SHA-256, and only
 * then renamed into place; a failure removes that partial file and throws. A file already at its
 * pinned size is re-hashed before it is trusted, so a leftover from an interrupted run is never
 * blessed unverified. Retryable transport errors are retried with backoff; an integrity failure stops
 * immediately. The revision marker is published only after every file is in place.
 *
 * [installed] is the caller's own "already complete" test — the marker plus sizes for PP-OCRv6, the
 * pinned sizes for manga-ocr (which also accepts files installed before this downloader existed).
 *
 * Installs are serialised on one lock, so two engines cannot download at once.
 */
internal object ModelInstall {
    private const val TAG = "GatenOcr"

    private val lock = Mutex()

    suspend fun install(
        displayName: String,
        directory: File,
        markerName: String,
        revision: String,
        files: List<PpOcrModel.ModelFile>,
        installed: () -> Boolean,
        onProgress: ((bytesDone: Long, bytesTotal: Long) -> Unit)? = null
    ) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                if (installed()) return@withContext
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw PpOcrModel.DownloadException(
                        "Could not create the model directory for $displayName",
                        reason = PpOcrModel.DownloadException.Reason.STORAGE
                    )
                }
                val total = files.sumOf { it.size }
                var done = 0L
                for (file in files) {
                    val destination = File(directory, file.name)
                    val alreadyVerified = destination.isFile &&
                        destination.length() == file.size &&
                        PpOcrFetch.sha256Of(destination) == file.sha256
                    if (!alreadyVerified) {
                        fetchWithRetry(file, destination) { fileBytes ->
                            onProgress?.invoke(done + fileBytes, total)
                        }
                    }
                    done += file.size
                    onProgress?.invoke(done, total)
                }
                publishMarker(directory, markerName, revision, displayName)
                if (!installed()) {
                    throw PpOcrModel.DownloadException("$displayName is incomplete after download")
                }
                Log.i(TAG, "Installed $displayName")
            }
        }
    }

    /** Publishes the revision only after every file is in place, via its own `.part` rename. */
    private fun publishMarker(directory: File, markerName: String, revision: String, displayName: String) {
        val part = File(directory, "$markerName.part")
        val marker = File(directory, markerName)
        try {
            part.writeText(revision)
        } catch (e: IOException) {
            part.delete()
            throw PpOcrModel.DownloadException(
                "Could not write the revision marker for $displayName",
                e,
                PpOcrModel.DownloadException.Reason.STORAGE
            )
        }
        if (marker.exists() && !marker.delete()) {
            part.delete()
            throw PpOcrModel.DownloadException(
                "Could not replace the revision marker for $displayName",
                reason = PpOcrModel.DownloadException.Reason.STORAGE
            )
        }
        if (!part.renameTo(marker)) {
            part.delete()
            throw PpOcrModel.DownloadException(
                "Could not publish the revision marker for $displayName",
                reason = PpOcrModel.DownloadException.Reason.STORAGE
            )
        }
    }

    private suspend fun fetchWithRetry(
        file: PpOcrModel.ModelFile,
        destination: File,
        onBytes: ((Long) -> Unit)? = null
    ) {
        var last: IOException? = null
        if (!file.url.startsWith("https://")) {
            throw PpOcrModel.DownloadException("Refusing a non-HTTPS model URL for ${file.name}")
        }
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                PpOcrFetch.fetch(file.url, destination, file.size, file.sha256, onBytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: PpOcrModel.DownloadException) {
                // An integrity failure or a refused redirect: retrying would not change the answer.
                throw e
            } catch (e: IOException) {
                last = e
                Log.w(TAG, "Download attempt ${attempt + 1}/$MAX_ATTEMPTS failed for ${file.name}", e)
                if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_BACKOFF_MS shl attempt)
            }
        }
        throw PpOcrModel.DownloadException(
            "Could not download ${file.name}",
            last,
            PpOcrModel.DownloadException.Reason.TRANSPORT
        )
    }

    private const val MAX_ATTEMPTS = 3
    private const val RETRY_BACKOFF_MS = 500L
}

/**
 * Streams one file to disk over the device's current connection, verifying it before it is allowed
 * to exist under its real name.
 *
 * Redirects are followed explicitly and bounded, and every hop must keep the original URL's protocol
 * (an HTTPS model may not be redirected onto HTTP). Cancellation is checked between reads, so an
 * abandoned download stops before the next chunk. [onBytes] is called with the bytes written so far
 * after every chunk, so a caller can report progress.
 *
 * Any failure — a bad status, a redirected-away protocol, too many redirects, a body larger than
 * expected, a bad size or hash, a cancellation — deletes the `.part` file and rethrows. Integrity and
 * protocol failures throw [PpOcrModel.DownloadException] (terminal); transport faults throw
 * [IOException] (the caller may retry).
 */
internal object PpOcrFetch {
    private const val BUFFER_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5

    suspend fun fetch(
        url: String,
        destination: File,
        expectedSize: Long,
        expectedSha256: String,
        onBytes: ((Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, "${destination.name}.part")
        part.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            var current = URL(url)
            val protocol = current.protocol
            var hops = 0
            while (true) {
                val connection = current.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.requestMethod = "GET"
                    val status = connection.responseCode
                    if (status in 300..399) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Redirect without a Location from $current")
                        if (++hops > MAX_REDIRECTS) {
                            throw PpOcrModel.DownloadException("Too many redirects for ${destination.name}")
                        }
                        val next = URL(current, location)
                        if (next.protocol != protocol) {
                            throw PpOcrModel.DownloadException("Redirect from $protocol to ${next.protocol} refused")
                        }
                        current = next
                        continue
                    }
                    if (status !in 200..299) throw IOException("HTTP $status from $current")
                    val declared = connection.contentLengthLong
                    if (declared > expectedSize) {
                        throw PpOcrModel.DownloadException(
                            "Expected $expectedSize bytes for ${destination.name}, server declares $declared"
                        )
                    }
                    connection.inputStream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                total += read
                                onBytes?.invoke(total)
                                if (total > expectedSize) {
                                    throw PpOcrModel.DownloadException(
                                        "${destination.name} is larger than the pinned $expectedSize bytes"
                                    )
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                break
            }
            if (total != expectedSize) {
                throw PpOcrModel.DownloadException(
                    "Expected $expectedSize bytes for ${destination.name}, received $total"
                )
            }
            if (!digest.digest().toHex().equals(expectedSha256, ignoreCase = true)) {
                throw PpOcrModel.DownloadException("Checksum mismatch for ${destination.name}")
            }
            if (destination.exists() && !destination.delete()) {
                throw IOException("Could not replace ${destination.name}")
            }
            if (!part.renameTo(destination)) {
                throw IOException("Could not install ${destination.name}")
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /** The file's SHA-256 as lowercase hex, or null when it cannot be read. */
    internal fun sha256Of(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().toHex()
    } catch (e: IOException) {
        null
    }

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        for (index in indices) {
            val value = this[index].toInt() and 0xFF
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 0x0F]
        }
        return String(hex)
    }

    private const val HEX = "0123456789abcdef"
}
