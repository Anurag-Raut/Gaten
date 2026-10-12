/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.coroutineContext

/**
 * Fetches one [MangaOcrCatalog] entry over HTTPS and installs it, for the Download button on the
 * settings screen. This is the only network code in the app, and nothing calls it on its own: the
 * user picks an entry and confirms its size first.
 *
 * How it behaves:
 *  - Only Hugging Face over HTTPS is ever contacted ([DownloadPolicy]), for the URL in the catalog
 *    and for every redirect along the way (the CDN answers with a 302, so up to
 *    [MAX_REDIRECTS] are followed, and a redirect anywhere else is refused rather than followed).
 *  - Each file is streamed into `dir/.part/` first, and moved onto its final name only once every
 *    file of the entry is complete and the expected size. A failure or a cancel leaves whatever was
 *    installed before exactly as it was; the partial files are deleted.
 *  - The body is checked against `expectedBytes` when the entry declares it, both from
 *    Content-Length and from what was actually written, which is what catches a truncated download
 *    or an error page saved under a model's name.
 *  - [download] runs on [Dispatchers.IO] and never throws: the outcome is a value. Cancellation of
 *    the calling coroutine still cancels (as Kotlin requires), and the partial files are cleaned up
 *    on the way out; [cancel] is the cooperative version the UI's Cancel button uses.
 *
 * It takes the model directory rather than a [android.content.Context], so the whole thing runs (and
 * is tested) on a plain JVM.
 *
 * [allowedUrl] is the policy every URL is put through before it is contacted — the catalog's URL and
 * each redirect. The app never passes anything but the default; it is a parameter so the tests can
 * aim the downloader at a server on localhost and exercise the redirects, the size checks and the
 * cleanup for real.
 */
class MangaOcrDownloader(
    private val dir: File,
    private val allowedUrl: (URI) -> Boolean = DownloadPolicy::isAllowed
) {

    /** What a download ended as. */
    sealed interface Outcome {
        /** All files are in place and the entry is recorded as installed. */
        data object Installed : Outcome

        /** The user cancelled; nothing was installed, partial files are gone. */
        data object Cancelled : Outcome

        /** Something went wrong; [reason] is for the settings screen, nothing was installed. */
        data class Failed(val reason: String) : Outcome
    }

    /** How far a download is: bytes of the file being fetched, and of the entry as a whole. */
    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val fileBytes: Long,
        val entryBytes: Long,
        val entryTotal: Long?
    ) {
        /** 0..1 for the entry as a whole, or null when a file's size is not known. */
        val fraction: Double? get() = DownloadProgress.fraction(entryBytes, entryTotal)
    }

    @Volatile
    private var cancelled = false

    /**
     * Asks the download in flight to stop. It notices at the next read, deletes what it staged and
     * answers [Outcome.Cancelled]; a download started afterwards is not affected.
     */
    fun cancel() {
        cancelled = true
    }

    /**
     * Downloads every file of [entry] into [dir] and records it as installed, reporting progress to
     * [onProgress] as the bytes arrive. Never throws; see [Outcome].
     */
    suspend fun download(
        entry: MangaOcrCatalog.Entry,
        onProgress: (Progress) -> Unit = {}
    ): Outcome {
        // Cleared here rather than inside the context below, so a Cancel tapped the instant after
        // Download is not swallowed by the coroutine not having started yet.
        cancelled = false
        return withContext(Dispatchers.IO) {
            val partDir = File(dir, PART_DIR)
            val staged = ArrayList<Pair<File, MangaOcrCatalog.File>>()
            try {
                if (!partDir.isDirectory && !partDir.mkdirs()) {
                    return@withContext Outcome.Failed("cannot create ${partDir.path}")
                }

                for ((index, file) in entry.files.withIndex()) {
                    coroutineContext.ensureActive()
                    if (cancelled) return@withContext Outcome.Cancelled
                    val target = File(partDir, file.localName)
                    target.delete()
                    val failure = fetch(file, index, entry, target, onProgress)
                    if (failure != null) return@withContext failure
                    staged.add(target to file)
                }
                if (cancelled) return@withContext Outcome.Cancelled

                // Every file is on disk and the right size, so only now does the installed model
                // change.
                for ((source, file) in staged) {
                    MangaOcrModel.moveAtomically(source, File(dir, file.localName))
                }
                MangaOcrModel.writeMarker(dir, entry.id)
                Outcome.Installed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                // Whatever happened — success, failure or cancel — nothing stays staged. The whole
                // directory goes, so a file that was still being written when the download stopped
                // is cleaned up too, not just the files that had finished.
                partDir.deleteRecursively()
            }
        }
    }

    /**
     * Fetches one file to [target], following Hugging Face's redirects. Null when it is there and
     * complete, the failure otherwise.
     */
    private fun fetch(
        file: MangaOcrCatalog.File,
        index: Int,
        entry: MangaOcrCatalog.Entry,
        target: File,
        onProgress: (Progress) -> Unit
    ): Outcome? {
        var url = URI(file.url)
        var redirects = 0
        while (true) {
            if (!allowedUrl(url)) {
                return Outcome.Failed("${file.localName}: refusing to download from $url")
            }
            val connection = open(url)
            try {
                val code = connection.responseCode
                if (code in REDIRECT_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?: return Outcome.Failed("${file.localName}: HTTP $code from $url with no Location")
                    if (redirects++ >= MAX_REDIRECTS) {
                        return Outcome.Failed("${file.localName}: more than $MAX_REDIRECTS redirects")
                    }
                    // The CDN's Location is sometimes relative, so it is resolved against the URL
                    // that answered — and then checked by the same policy as the original.
                    url = url.resolve(location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return Outcome.Failed("${file.localName}: HTTP $code from $url")
                }
                return copyBody(connection, file, index, entry, target, onProgress)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Streams the response body of [connection] into [target], checking the size as it goes. */
    private fun copyBody(
        connection: HttpURLConnection,
        file: MangaOcrCatalog.File,
        index: Int,
        entry: MangaOcrCatalog.Entry,
        target: File,
        onProgress: (Progress) -> Unit
    ): Outcome? {
        val expected = file.expectedBytes
        val declared = connection.contentLengthLong
        if (expected != null && declared > 0 && declared != expected) {
            return Outcome.Failed("${file.localName}: the server offers $declared bytes, expected $expected")
        }

        val total = DownloadProgress.totalBytes(entry.files)
        val before = DownloadProgress.bytesBefore(entry.files, index)
        var written = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    if (cancelled) return Outcome.Cancelled
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onProgress(
                        Progress(index, entry.files.size, file.localName, written, before + written, total)
                    )
                }
            }
        }

        if (expected != null && written != expected) {
            return Outcome.Failed("${file.localName}: got $written bytes, expected $expected")
        }
        return null
    }

    private fun open(url: URI): HttpURLConnection =
        (url.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Ask for the bytes as they are: a transparently decompressed body would make
            // Content-Length the compressed size, and that length is the download's integrity check.
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private companion object {
        /** Staging directory inside the model directory; on the same filesystem, so moves are renames. */
        const val PART_DIR = ".part"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val BUFFER_BYTES = 64 * 1024
        const val USER_AGENT = "Gaten (Android manga-ocr model download)"

        val REDIRECT_CODES = 300..399
    }
}

/**
 * Which URLs the downloader may talk to: HTTPS on Hugging Face, and nothing else. Pure, so the rule
 * the catalog is written against is the rule that is tested.
 *
 * The domain list covers the model host and the CDN Hugging Face redirects to (`us.aws.cdn.hf.co`,
 * `cas-bridge.xethub.hf.co`, and the region variants of both), and is deliberately an allow-list: a
 * redirect anywhere else is refused rather than followed, which is what keeps the download pointed at
 * the repository the catalog names.
 */
internal object DownloadPolicy {
    private val ALLOWED_HOSTS = setOf("huggingface.co", "hf.co")
    private val ALLOWED_SUFFIXES = listOf(".huggingface.co", ".hf.co")

    /** True when [url] is an HTTPS URL on Hugging Face (or its CDN). */
    fun isAllowed(url: URI): Boolean {
        if (!url.scheme.equals("https", ignoreCase = true)) return false
        val host = url.host?.lowercase() ?: return false
        return host in ALLOWED_HOSTS || ALLOWED_SUFFIXES.any { host.endsWith(it) }
    }
}

/**
 * The arithmetic behind [MangaOcrDownloader.Progress]: where the entry is as a whole, given the
 * files before the one being fetched. Pure and separate, so the numbers the progress bar shows can
 * be checked without a socket.
 */
internal object DownloadProgress {

    /** Bytes of the files before [index], from their declared sizes (0 for one that declares none). */
    fun bytesBefore(files: List<MangaOcrCatalog.File>, index: Int): Long =
        files.take(index.coerceAtLeast(0)).sumOf { it.expectedBytes ?: 0L }

    /** Bytes of every file in [files], or null when any of them does not declare its size. */
    fun totalBytes(files: List<MangaOcrCatalog.File>): Long? {
        if (files.isEmpty() || files.any { it.expectedBytes == null }) return null
        return files.sumOf { it.expectedBytes ?: 0L }
    }

    /** [done] of [total] as 0..1, or null when the total is unknown. */
    fun fraction(done: Long, total: Long?): Double? {
        if (total == null || total <= 0L) return null
        return (done.toDouble() / total).coerceIn(0.0, 1.0)
    }
}
