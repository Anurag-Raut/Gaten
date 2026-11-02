/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ui.lookup

import com.gaten.app.ocr.RecognitionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The pure seams of engine routing: the exact-crop cache key must change when the selected mode or
 * an installed model revision does, and the failed-read message must match what could actually run.
 */
class RecognitionRoutingTest {

    @Test
    fun theCacheRevisionChangesWithTheMode() {
        assertNotEquals(
            recognitionRevision(RecognitionMode.MANGA_OCR, "manga", "pp"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "pp")
        )
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "pp"),
            recognitionRevision(RecognitionMode.PP_MANGA, "manga", "pp")
        )
    }

    @Test
    fun theCacheRevisionChangesWithTheInstalledPpRevision() {
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@a"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@b")
        )
        assertNotEquals(
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@a"),
            recognitionRevision(RecognitionMode.PP_MEDIUM, "manga", "medium@a;manga@a")
        )
    }

    @Test
    fun theCacheRevisionChangesWithTheInstalledMangaFiles() {
        assertNotEquals(
            recognitionRevision(RecognitionMode.MANGA_OCR, "old", ""),
            recognitionRevision(RecognitionMode.MANGA_OCR, "new", "")
        )
    }

    @Test
    fun theSameInputsGiveTheSameRevision() {
        assertEquals(
            recognitionRevision(RecognitionMode.PP_MANGA, "manga", "manga@x"),
            recognitionRevision(RecognitionMode.PP_MANGA, "manga", "manga@x")
        )
    }

    @Test
    fun theFailedReadMessageNamesMangaOcrWhenItIsSelectedAndInstalled() {
        assertEquals(
            "Manga OCR could not read this text. Please select it again.",
            unreadableMessage(RecognitionMode.MANGA_OCR, selectedInstalled = true, mangaInstalled = true)
        )
    }

    @Test
    fun aPpModeWithAViableEngineNamesTextRecognition() {
        for (mode in listOf(RecognitionMode.PP_MEDIUM, RecognitionMode.PP_MANGA)) {
            assertEquals(
                "Text recognition could not read this text. Please select it again.",
                unreadableMessage(mode, selectedInstalled = true, mangaInstalled = true)
            )
            // The selected PP engine is missing, but Manga OCR is installed and reads instead.
            assertEquals(
                "Text recognition could not read this text. Please select it again.",
                unreadableMessage(mode, selectedInstalled = false, mangaInstalled = true)
            )
        }
    }

    @Test
    fun nothingInstalledPointsAtDownloadingOne() {
        val expected = "No OCR model is installed. Download one in Settings › Text recognition."
        assertEquals(
            expected,
            unreadableMessage(RecognitionMode.MANGA_OCR, false, false, anyEngineInstalled = false)
        )
        assertEquals(
            expected,
            unreadableMessage(RecognitionMode.PP_MEDIUM, false, false, anyEngineInstalled = false)
        )
    }

    @Test
    fun anInstalledOtherEnginePromptsChoosingItInsteadOfDownloading() {
        val expected =
            "Download this engine or choose an installed engine in Settings › Text recognition."
        // Selected PP missing, Manga missing, but the other PP engine is installed.
        assertEquals(
            expected,
            unreadableMessage(RecognitionMode.PP_MEDIUM, false, false, anyEngineInstalled = true)
        )
        // Selected Manga OCR missing, a PP engine installed: Manga OCR is its own fallback.
        assertEquals(
            expected,
            unreadableMessage(RecognitionMode.MANGA_OCR, false, false, anyEngineInstalled = true)
        )
    }
}
