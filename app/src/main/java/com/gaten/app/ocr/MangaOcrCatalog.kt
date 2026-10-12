/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

/**
 * The manga-ocr models the user can download, as data.
 *
 * An entry is a label, a size for the settings screen and the files that make it up; nothing here
 * knows how to fetch or run anything, so a second model (Baberu OCR, another quantization) is a new
 * entry and no change to [MangaOcrDownloader] or [MangaOcrEngine]. [localName] is the name the file
 * is stored under — [MangaOcrModel.Slot.fileName] — so the engine finds it wherever it came from.
 *
 * `expectedBytes` is the exact size Hugging Face serves; the downloader refuses a body that does not
 * match, which is what catches a truncated download or an error page saved as a model. Leave it null
 * for a source whose size is not known up front: the download still works, the progress bar just
 * cannot show a percentage.
 *
 * Only `manga-ocr-int8` ships: the `fp16` export in the same repository cannot be loaded by ONNX
 * Runtime at all (the decoder's Cast node is typed float16 where its consumer wants float32 — see
 * the README), and the `q4` pair, which does load, was left out until its accuracy has been
 * measured on real manga.
 */
object MangaOcrCatalog {

    /** One file of an entry: where it comes from, and the name it is stored under. */
    data class File(val url: String, val localName: String, val expectedBytes: Long? = null)

    /** One downloadable model. [approxSizeMb] is what the settings screen warns about. */
    data class Entry(
        val id: String,
        val label: String,
        val description: String,
        val approxSizeMb: Int,
        val files: List<File>
    )

    private const val ONNX_BASE =
        "https://huggingface.co/onnx-community/manga-ocr-base-ONNX/resolve/main/onnx"
    private const val VOCAB_URL =
        "https://huggingface.co/kha-white/manga-ocr-base/resolve/main/vocab.txt"

    /** The default entry: the smallest export that ONNX Runtime can actually load. */
    val MANGA_OCR_INT8 = Entry(
        id = "manga-ocr-int8",
        label = "manga-ocr (int8)",
        description = "8-bit weights. Recommended: the smallest export that loads and runs everywhere.",
        approxSizeMb = 117,
        files = listOf(
            File(
                url = "$ONNX_BASE/encoder_model_int8.onnx",
                localName = MangaOcrModel.Slot.ENCODER.fileName,
                expectedBytes = 86_967_767L
            ),
            File(
                url = "$ONNX_BASE/decoder_model_int8.onnx",
                localName = MangaOcrModel.Slot.DECODER.fileName,
                expectedBytes = 29_627_936L
            ),
            File(
                url = VOCAB_URL,
                localName = MangaOcrModel.Slot.VOCAB.fileName,
                expectedBytes = 24_072L
            )
        )
    )

    /** Everything the settings screen offers, in the order it shows them. */
    val entries: List<Entry> = listOf(MANGA_OCR_INT8)

    /** The entry a fresh install should use. */
    val defaultEntry: Entry get() = MANGA_OCR_INT8

    /** The entry with [id], or null when it is not one the app knows. */
    fun byId(id: String?): Entry? = entries.firstOrNull { it.id == id }

    /**
     * The entry whose declared file sizes are exactly [sizes], or null when none matches. This is
     * how a model that was imported (or copied out of a bundled debug APK) is still named in the
     * settings screen: the same bytes, so the same entry.
     */
    fun identify(sizes: Map<MangaOcrModel.Slot, Long>): Entry? = entries.firstOrNull { entry ->
        entry.files.all { file ->
            val slot = MangaOcrModel.Slot.entries.firstOrNull { it.fileName == file.localName }
            val expected = file.expectedBytes
            slot != null && expected != null && sizes[slot] == expected
        }
    }
}
