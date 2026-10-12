/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import com.gaten.app.ocr.MangaOcrModel.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaOcrModelTest {

    @Test
    fun theNamesTheModelIsDownloadedUnderAreRecognised() {
        // The names onnx-community/manga-ocr-base-ONNX and kha-white/manga-ocr-base use.
        assertEquals(Slot.ENCODER, MangaOcrModel.classifyName("encoder_model_int8.onnx"))
        assertEquals(Slot.DECODER, MangaOcrModel.classifyName("decoder_model_int8.onnx"))
        assertEquals(Slot.VOCAB, MangaOcrModel.classifyName("vocab.txt"))
    }

    @Test
    fun theNamesTheModelIsStoredUnderAreRecognised() {
        assertEquals(Slot.ENCODER, MangaOcrModel.classifyName("encoder.onnx"))
        assertEquals(Slot.DECODER, MangaOcrModel.classifyName("decoder.onnx"))
        assertEquals(Slot.VOCAB, MangaOcrModel.classifyName("vocab.txt"))
    }

    @Test
    fun aPickedFileMayLiveAnywhereAndBeAnyCase() {
        assertEquals(Slot.ENCODER, MangaOcrModel.classifyName("/storage/emulated/0/Download/Encoder.onnx"))
        assertEquals(Slot.DECODER, MangaOcrModel.classifyName("manga-ocr/decoder_model_fp16.onnx"))
        assertEquals(Slot.VOCAB, MangaOcrModel.classifyName("Vocab.TXT"))
    }

    @Test
    fun anythingElseIsNotPartOfTheModel() {
        assertNull(MangaOcrModel.classifyName("jmdict.sqlite"))
        assertNull(MangaOcrModel.classifyName("manga.jpg"))
        assertNull(MangaOcrModel.classifyName(""))
    }
}
