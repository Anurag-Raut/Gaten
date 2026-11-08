/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.nio.file.Files
import java.security.MessageDigest

/** Geometry, CTC decoding, the dictionary layout and the downloader's verification. */
class PpOcrTest {

    // ---- PpOcrGeometry: the crop is turned when tall; width is ratio-preserving, padded and bounded. ----
    // Fixtures are square-pixel (height 48) where convenient, so natural width == source width.

    @Test
    fun aWideCropKeepsItsAspectAndIsPaddedUpToTheOfficialMinimum() {
        val plan = PpOcrGeometry.plan(sourceWidth = 240, sourceHeight = 48)
        assertFalse(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(240, plan.resizedWidth)
    }

    @Test
    fun aSquareCropIsPaddedRatherThanSquashedUp() {
        val plan = PpOcrGeometry.plan(sourceWidth = 48, sourceHeight = 48)
        assertFalse(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(48, plan.resizedWidth)
    }

    @Test
    fun aTallCropIsTurnedAndUsesTheTurnedAspect() {
        // 20 wide, 100 tall -> turned; the turned crop is 100 wide, 20 tall.
        val plan = PpOcrGeometry.plan(sourceWidth = 20, sourceHeight = 100)
        assertTrue(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(240, plan.resizedWidth)
    }

    @Test
    fun aTurnedCropWiderThanTheMinimumUsesItsOwnTurnedWidth() {
        // 48 wide, 200 tall -> turned; the turned crop is 200 wide, 48 tall.
        val plan = PpOcrGeometry.plan(sourceWidth = 48, sourceHeight = 200)
        assertTrue(plan.rotated)
        assertEquals(320, plan.width)
        assertEquals(200, plan.resizedWidth)
    }

    @Test
    fun aCropBetweenTheBoundsKeepsItsOwnWidth() {
        val plan = PpOcrGeometry.plan(sourceWidth = 480, sourceHeight = 48)
        assertEquals(480, plan.width)
        assertEquals(480, plan.resizedWidth)
    }

    @Test
    fun aLineAtTheUpperBoundIsNotSqueezed() {
        val plan = PpOcrGeometry.plan(sourceWidth = 1280, sourceHeight = 48)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.width)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.resizedWidth)
    }

    @Test
    fun aLinePastTheUpperBoundIsSqueezedIntoIt() {
        val plan = PpOcrGeometry.plan(sourceWidth = 2000, sourceHeight = 48)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.width)
        assertEquals(PpOcrGeometry.MAX_WIDTH, plan.resizedWidth)
    }

    // ---- PpOcrCtc: blanks dropped, runs collapsed, spaces kept. ----

    @Test
    fun blankAndDuplicateClassesAreCollapsed() {
        // a a _ a b ' '  ->  "aab "
        val logits = scores(intArrayOf(1, 1, 0, 1, 2, 3), classes = 4)
        val text = PpOcrCtc.decode(logits, steps = 6, classes = 4, vocab = listOf("blank", "a", "b", " "))
        assertEquals("aab ", text)
    }

    @Test
    fun aClassRepeatedAcrossABlankSurvives() {
        // a _ a  ->  "aa"
        val logits = scores(intArrayOf(1, 0, 1), classes = 3)
        val text = PpOcrCtc.decode(logits, steps = 3, classes = 3, vocab = listOf("blank", "a", "b"))
        assertEquals("aa", text)
    }

    @Test
    fun anAllBlankRunDecodesToNothing() {
        val logits = scores(intArrayOf(0, 0, 0), classes = 2)
        assertEquals("", PpOcrCtc.decode(logits, steps = 3, classes = 2, vocab = listOf("blank", "a")))
    }

    private fun scores(best: IntArray, classes: Int): FloatBuffer {
        val buffer = FloatArray(best.size * classes)
        for (step in best.indices) buffer[step * classes + best[step]] = 1f
        return FloatBuffer.wrap(buffer)
    }

    // ---- PpOcrVocabulary: blank at 0, a space last, symbols from line 1. ----

    @Test
    fun theVocabularyIsBlankThenTheLinesThenASpace() {
        val file = File.createTempFile("dict", ".txt").apply {
            writeText("A\nB\n")
            deleteOnExit()
        }
        assertEquals(listOf("blank", "A", "B", " "), PpOcrVocabulary.load(file))
    }

    // ---- PpOcrFetch: verifies size and hash, cleans up partials, bounds redirects. ----

    private fun tempDirectory(): File = Files.createTempDirectory("pp-ocr-test").toFile()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** Runs a download that must fail with the terminal error. */
    private fun downloadFailure(block: suspend () -> Unit) {
        val error = runCatching { runBlocking { block() } }.exceptionOrNull()
        assertTrue("expected DownloadException, got $error", error is PpOcrModel.DownloadException)
    }

    @Test
    fun aVerifiedDownloadIsInstalledUnderItsRealName() {
        val body = ByteArray(2048) { it.toByte() }
        FakeHttpServer().use { server ->
            server.serve("/model", body = body)
            server.start()
            val directory = tempDirectory()
            val destination = File(directory, "rec.onnx")
            runBlocking {
                PpOcrFetch.fetch(server.url("/model"), destination, body.size.toLong(), sha256(body))
            }
            assertTrue(destination.isFile)
            assertEquals(body.size.toLong(), destination.length())
            assertTrue(destination.readBytes().contentEquals(body))
            assertFalse(File(directory, "rec.onnx.part").exists())
        }
    }

    @Test
    fun aSameProtocolRedirectIsFollowed() {
        val body = ByteArray(1024) { (it * 3).toByte() }
        FakeHttpServer().use { server ->
            server.serve("/real", body = body)
            server.serve("/model", status = 302, location = server.url("/real"))
            server.start()
            val destination = File(tempDirectory(), "rec.onnx")
            runBlocking {
                PpOcrFetch.fetch(server.url("/model"), destination, body.size.toLong(), sha256(body))
            }
            assertTrue(destination.readBytes().contentEquals(body))
        }
    }

    @Test
    fun anEndlessRedirectChainIsRefused() {
        val body = ByteArray(64)
        FakeHttpServer().use { server ->
            for (hop in 0 until 10) {
                server.serve("/hop$hop", status = 302, location = server.url("/hop${hop + 1}"))
            }
            server.serve("/hop10", body = body)
            server.start()
            val directory = tempDirectory()
            downloadFailure {
                PpOcrFetch.fetch(server.url("/hop0"), File(directory, "rec.onnx"), body.size.toLong(), sha256(body))
            }
            assertFalse(File(directory, "rec.onnx").exists())
            assertFalse(File(directory, "rec.onnx.part").exists())
        }
    }

    @Test
    fun aServerThatDeclaresTooManyBytesIsRejectedAndLeavesNoPartial() {
        val body = ByteArray(512) { it.toByte() }
        FakeHttpServer().use { server ->
            server.serve("/model", body = body, declaredLength = 4096)
            server.start()
            val directory = tempDirectory()
            downloadFailure {
                PpOcrFetch.fetch(server.url("/model"), File(directory, "rec.onnx"), 4096, sha256(body))
            }
            assertFalse(File(directory, "rec.onnx").exists())
            assertFalse(File(directory, "rec.onnx.part").exists())
        }
    }

    @Test
    fun aHashMismatchIsRejectedAndLeavesNoPartial() {
        val body = ByteArray(777) { (it * 7 + 1).toByte() }
        FakeHttpServer().use { server ->
            server.serve("/model", body = body)
            server.start()
            val directory = tempDirectory()
            downloadFailure {
                PpOcrFetch.fetch(
                    server.url("/model"),
                    File(directory, "rec.onnx"),
                    body.size.toLong(),
                    sha256(ByteArray(777))
                )
            }
            assertFalse(File(directory, "rec.onnx").exists())
            assertFalse(File(directory, "rec.onnx.part").exists())
        }
    }

    @Test
    fun aRetainedFileCanBeRehashedAndAMissingOneReadsAsNull() {
        val content = "the dictionary".toByteArray()
        val file = File.createTempFile("retained", ".bin").apply {
            writeBytes(content)
            deleteOnExit()
        }
        assertEquals(sha256(content), PpOcrFetch.sha256Of(file))
        assertNull(PpOcrFetch.sha256Of(File(file.parentFile, "missing.bin")))
    }

    @Test
    fun theCatalogPinsEveryFileToAnHttpsUrlHashAndSize() {
        val files = PpOcrModel.files
        assertEquals(2, files.size)
        assertTrue(files.all { it.url.startsWith("https://") })
        assertTrue(files.all { it.sha256.length == 64 })
        assertTrue(files.all { it.size > 0 })
        assertEquals(PpOcrModel.Engine.MEDIUM.approximateDownloadBytes, files.sumOf { it.size })
    }
}
