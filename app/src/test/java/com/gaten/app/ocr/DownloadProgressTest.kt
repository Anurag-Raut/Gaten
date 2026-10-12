/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadProgressTest {

    private fun file(name: String, bytes: Long?) =
        MangaOcrCatalog.File(url = "https://huggingface.co/$name", localName = name, expectedBytes = bytes)

    private val files = listOf(file("encoder.onnx", 100), file("decoder.onnx", 30), file("vocab.txt", 2))

    @Test
    fun bytesBeforeCountsTheFilesAlreadyDone() {
        assertEquals(0L, DownloadProgress.bytesBefore(files, 0))
        assertEquals(100L, DownloadProgress.bytesBefore(files, 1))
        assertEquals(130L, DownloadProgress.bytesBefore(files, 2))
        assertEquals(132L, DownloadProgress.bytesBefore(files, 3))
    }

    @Test
    fun aFileWithoutADeclaredSizeContributesNothing() {
        val unknown = listOf(file("encoder.onnx", 100), file("decoder.onnx", null), file("vocab.txt", 2))

        assertEquals(100L, DownloadProgress.bytesBefore(unknown, 2))
        assertNull(DownloadProgress.totalBytes(unknown))
    }

    @Test
    fun theTotalIsKnownOnlyWhenEveryFileDeclaresItsSize() {
        assertEquals(132L, DownloadProgress.totalBytes(files))
        assertNull(DownloadProgress.totalBytes(emptyList()))
    }

    @Test
    fun theFractionIsClampedAndUnknownWithoutATotal() {
        assertEquals(0.0, DownloadProgress.fraction(0, 132)!!, 0.0)
        assertEquals(0.5, DownloadProgress.fraction(66, 132)!!, 0.0)
        assertEquals(1.0, DownloadProgress.fraction(999, 132)!!, 0.0)
        assertNull(DownloadProgress.fraction(10, null))
        assertNull(DownloadProgress.fraction(10, 0))
    }

    @Test
    fun progressReportsTheEntryFractionAndTheFileBeingFetched() {
        val progress = MangaOcrDownloader.Progress(
            fileIndex = 1,
            fileCount = 3,
            fileName = "decoder.onnx",
            fileBytes = 15,
            entryBytes = 115,
            entryTotal = 132
        )

        assertEquals(115.0 / 132, progress.fraction!!, 1e-9)
        assertNull(progress.copy(entryTotal = null).fraction)
    }
}
