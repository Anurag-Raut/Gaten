/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The downloader against a real HTTP server on localhost: redirects, size checks, progress
 * reporting, cancellation and cleanup are exercised over a socket, not mocked.
 *
 * The policy is the one thing the test relaxes (localhost is not Hugging Face);
 * [DownloadPolicyTest] covers the rule the app ships, and [refusesAHostOutsideThePolicy] shows that
 * with nothing passed in the downloader is on the strict default.
 */
class MangaOcrDownloaderTest {

    private lateinit var server: FakeHttpServer
    private lateinit var dir: File
    private lateinit var downloader: MangaOcrDownloader

    @Before
    fun setUp() {
        server = FakeHttpServer().apply { start() }
        dir = Files.createTempDirectory("manga-ocr-download").toFile()
        // The one thing the test relaxes: localhost is not Hugging Face.
        downloader = MangaOcrDownloader(dir) { it.host == "127.0.0.1" }
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun file(name: String, body: ByteArray, path: String = "/$name") =
        MangaOcrCatalog.File(url = server.url(path), localName = name, expectedBytes = body.size.toLong())

    private fun entry(vararg files: MangaOcrCatalog.File) = MangaOcrCatalog.Entry(
        id = "test-entry",
        label = "test",
        description = "test",
        approxSizeMb = 1,
        files = files.toList()
    )

    private fun outcome(entry: MangaOcrCatalog.Entry): MangaOcrDownloader.Outcome =
        runBlocking { downloader.download(entry) }

    private fun stagedFiles() = File(dir, ".part").listFiles()?.toList().orEmpty()

    @Test
    fun installsEveryFileAndRecordsTheEntry() {
        val encoder = "encoder-bytes".toByteArray()
        val decoder = "decoder-bytes".toByteArray()
        val vocab = "vocab-bytes".toByteArray()
        server.serve("/encoder.onnx", encoder)
        server.serve("/decoder.onnx", decoder)
        server.serve("/vocab.txt", vocab)

        val outcome = outcome(
            entry(
                file("encoder.onnx", encoder),
                file("decoder.onnx", decoder),
                file("vocab.txt", vocab)
            )
        )

        assertEquals(MangaOcrDownloader.Outcome.Installed, outcome)
        assertEquals("encoder-bytes", File(dir, "encoder.onnx").readText())
        assertEquals("decoder-bytes", File(dir, "decoder.onnx").readText())
        assertEquals("vocab-bytes", File(dir, "vocab.txt").readText())
        assertEquals("test-entry", File(dir, "catalog.txt").readText().trim())
        assertFalse("the staging directory is left behind", File(dir, ".part").exists())
    }

    @Test
    fun followsTheRedirectsHuggingFaceAnswersWith() {
        val body = "redirected-body".toByteArray()
        server.serve("/start", status = 302, location = "/real")
        server.serve("/real", body)

        val outcome = outcome(entry(file("encoder.onnx", body, path = "/start")))

        assertEquals(MangaOcrDownloader.Outcome.Installed, outcome)
        assertEquals("redirected-body", File(dir, "encoder.onnx").readText())
        assertEquals("both hops were requested", 2, server.requests.get())
    }

    @Test
    fun refusesARedirectThatLeavesTheAllowedHosts() {
        server.serve("/start", status = 302, location = "https://example.com/encoder.onnx")

        val outcome = outcome(entry(file("encoder.onnx", "x".toByteArray(), path = "/start")))

        assertTrue(outcome is MangaOcrDownloader.Outcome.Failed)
        assertTrue((outcome as MangaOcrDownloader.Outcome.Failed).reason.contains("refusing"))
        assertEquals("only the first hop was requested", 1, server.requests.get())
    }

    @Test
    fun givesUpOnARedirectLoop() {
        server.serve("/loop", status = 302, location = "/loop")

        val outcome = outcome(entry(file("encoder.onnx", "x".toByteArray(), path = "/loop")))

        assertTrue(outcome is MangaOcrDownloader.Outcome.Failed)
        assertEquals("the first request plus five redirects", 6, server.requests.get())
    }

    @Test
    fun refusesAHostOutsideThePolicy() {
        // No policy passed in: this is the downloader the app uses, and it is the strict one.
        server.serve("/encoder.onnx", "x".toByteArray())

        val outcome = runBlocking {
            MangaOcrDownloader(dir).download(entry(file("encoder.onnx", "x".toByteArray())))
        }

        assertTrue(outcome is MangaOcrDownloader.Outcome.Failed)
        assertEquals("the network was never touched", 0, server.requests.get())
    }

    @Test
    fun aBodyOfTheWrongLengthFailsAndLeavesTheInstalledModelAlone() {
        File(dir, "encoder.onnx").writeText("the model that was already there")
        server.serve("/encoder.onnx", "short".toByteArray())
        val declared = MangaOcrCatalog.File(
            url = server.url("/encoder.onnx"),
            localName = "encoder.onnx",
            expectedBytes = 99L
        )

        val outcome = outcome(entry(declared))

        assertTrue(outcome is MangaOcrDownloader.Outcome.Failed)
        assertEquals("the model that was already there", File(dir, "encoder.onnx").readText())
        assertFalse(File(dir, "catalog.txt").exists())
        assertTrue("no staged files are left", stagedFiles().isEmpty())
    }

    @Test
    fun aTruncatedBodyFailsEvenWhenTheServerLiesAboutItsLength() {
        // Content-Length says 100 but the body ends after 5 bytes: the bytes written are what counts.
        server.serve("/truncated.onnx", ByteArray(5), declaredLength = 100L)
        val declared = MangaOcrCatalog.File(
            url = server.url("/truncated.onnx"),
            localName = "encoder.onnx",
            expectedBytes = 100L
        )

        val outcome = outcome(entry(declared))

        assertTrue(outcome is MangaOcrDownloader.Outcome.Failed)
        assertFalse(File(dir, "encoder.onnx").exists())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun reportsProgressUpToTheWholeEntry() {
        val encoder = ByteArray(100) { 1 }
        val decoder = ByteArray(40) { 2 }
        server.serve("/encoder.onnx", encoder)
        server.serve("/decoder.onnx", decoder)
        val seen = ArrayList<MangaOcrDownloader.Progress>()

        val outcome = runBlocking {
            downloader.download(entry(file("encoder.onnx", encoder), file("decoder.onnx", decoder))) {
                seen.add(it)
            }
        }

        assertEquals(MangaOcrDownloader.Outcome.Installed, outcome)
        assertEquals(listOf("encoder.onnx", "decoder.onnx"), seen.map { it.fileName }.distinct())
        assertEquals(listOf(0, 1), seen.map { it.fileIndex }.distinct())
        assertEquals(2, seen.first().fileCount)
        assertTrue(
            "progress only moves forward",
            seen.zipWithNext().all { it.first.entryBytes <= it.second.entryBytes }
        )
        assertEquals(140L, seen.last().entryBytes)
        assertEquals(1.0, seen.last().fraction!!, 1e-9)
    }

    @Test
    fun cancellingStopsTheDownloadAndLeavesNothingBehind() {
        File(dir, "encoder.onnx").writeText("the model that was already there")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.serveInTwoChunks("/slow.onnx", ByteArray(50), ByteArray(150), started, release)
        val declared = MangaOcrCatalog.File(
            url = server.url("/slow.onnx"),
            localName = "encoder.onnx",
            expectedBytes = 200L
        )

        var outcome: MangaOcrDownloader.Outcome? = null
        runBlocking {
            val job = launch(Dispatchers.IO) { outcome = downloader.download(entry(declared)) }
            assertTrue("the server never started answering", started.await(10, TimeUnit.SECONDS))
            downloader.cancel()
            release.countDown()
            job.join()
        }

        assertEquals(MangaOcrDownloader.Outcome.Cancelled, outcome)
        assertEquals("the model that was already there", File(dir, "encoder.onnx").readText())
        assertFalse(File(dir, "catalog.txt").exists())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun aDownloadAfterACancelStillWorks() {
        val body = "second-attempt".toByteArray()
        server.serve("/encoder.onnx", body)

        runBlocking {
            downloader.cancel()
            assertEquals(MangaOcrDownloader.Outcome.Installed, downloader.download(entry(file("encoder.onnx", body))))
        }
    }

    /** Cancelling the owning coroutine stops chunk callbacks and leaves installed files intact. */
    @Test
    fun coroutineCancellationStopsBodyReadsAndPreservesTheInstalledModel() = runBlocking {
        File(dir, "encoder.onnx").writeText("installed model")
        val body = ByteArray(3 * 64 * 1024)
        server.serve("/encoder.onnx", body)
        var progressUpdates = 0
        val job = launch {
            val downloadJob = coroutineContext[Job]!!
            downloader.download(entry(file("encoder.onnx", body))) {
                progressUpdates++
                downloadJob.cancel()
            }
        }

        job.join()

        assertTrue(job.isCancelled)
        assertEquals("no more body chunks after cancellation", 1, progressUpdates)
        assertEquals("installed model", File(dir, "encoder.onnx").readText())
        assertFalse(File(dir, "catalog.txt").exists())
        assertFalse(File(dir, ".part").exists())
    }
}
