/*
 * Gaten
 * Copyright (C) 2026 Anurag Raut
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.gaten.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.coroutines.coroutineContext

/**
 * The optional reader: manga-ocr, a ViT encoder plus a two-layer BERT decoder, run on ONNX Runtime.
 *
 * It exists because ML Kit reads stylized fonts — outlined thumbnail text, hand-lettered manga
 * lettering — as the kanji their strokes resemble. This model was trained on exactly that kind of
 * text, so it reads the lines ML Kit found ([ScreenLineReader] for the overlay's index,
 * [JapaneseOcr.readLineWithMangaOcr] for the region path), and ML Kit's own reading is what stays
 * when it cannot.
 *
 * Generation is greedy and re-runs the decoder over the whole prefix each step (the exported decoder
 * has no KV cache). That costs a few hundred milliseconds per line on a phone, which is why the
 * callers read a bounded number of lines and fall back on a timeout; short lines never get near the
 * 300 token cap.
 *
 * The sessions are expensive to build (the encoder is ~87 MB), so they are kept across lookups — but
 * not for the life of the process: [OcrModels] releases them once the models have been idle for a
 * while, and when the system asks for memory ([release]), and the next call builds them again. Every
 * call takes [mutex], so two lookups cannot drive the same session at once, and the pair is rebuilt
 * when the model files change — a re-import or a remove is noticed on the next call.
 */
object MangaOcrEngine {
    private const val TAG = "GatenOcr"

    /** Graph names, read off the exported models (encoder_model_int8 / decoder_model_int8). */
    private const val ENCODER_INPUT = "pixel_values"
    private const val ENCODER_OUTPUT = "last_hidden_state"
    private const val DECODER_INPUT_IDS = "input_ids"
    private const val DECODER_ENCODER_STATES = "encoder_hidden_states"
    private const val DECODER_LOGITS = "logits"

    /** `decoder_start_token_id` and `eos_token_id` from the model's config.json. */
    private const val START_TOKEN = 2L
    private const val EOS_TOKEN = 3L

    /** `max_length` from generation_config.json: the model's own cap on a line. */
    private const val MAX_TOKENS = 300

    /** Input side of the encoder, matching [OcrPreprocess]'s model input. */
    private const val MODEL_SIZE = 224L

    private val mutex = Mutex()

    /** The loaded pair, together with the files it was built from. */
    private var loaded: Loaded? = null

    private class Loaded(
        val key: String,
        val environment: OrtEnvironment,
        val encoder: OrtSession,
        val decoder: OrtSession,
        val tokenizer: MangaOcrTokenizer
    )

    /**
     * Reads the line in [crop] with manga-ocr and returns its post-processed text, or null when the
     * model is not installed, cannot be loaded, or inference fails. Never throws, and safe to call
     * from several coroutines: the calls are serialized on [mutex].
     */
    suspend fun recognize(context: Context, crop: Bitmap): String? = withContext(Dispatchers.Default) {
        // The pair is in use from here to the end of the call, so [OcrModels]' idle clock must not
        // run it out mid-inference: the use counts in on the way in and out on the way out.
        OcrModels.begin()
        try {
            mutex.withLock {
                try {
                    infer(context, crop)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "manga-ocr inference failed", e)
                    null
                } catch (e: OutOfMemoryError) {
                    // The encoder's working set is tens of megabytes; on a loaded device that can fail.
                    Log.w(TAG, "manga-ocr ran out of memory", e)
                    null
                }
            }
        } finally {
            OcrModels.end()
        }
    }

    /**
     * One encoder pass plus the greedy decode loop. Returns null when the model is not installed.
     *
     * Suspending: [decode] checks cancellation between decoder steps, so an abandoned read stops
     * before the next native call rather than decoding to the token cap. A call already running on
     * the native side is never interrupted mid-inference.
     */
    private suspend fun infer(context: Context, crop: Bitmap): String? {
        val model = load(context) ?: return null
        val input = OcrPreprocess.toModelInput(crop)

        OnnxTensor.createTensor(
            model.environment,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, MODEL_SIZE, MODEL_SIZE)
        ).use { pixelValues ->
            model.encoder.run(mapOf(ENCODER_INPUT to pixelValues)).use { encoded ->
                val hidden = encoded.get(ENCODER_OUTPUT).orElse(null) as? OnnxTensor ?: return null
                val ids = decode(model, hidden)
                return MangaOcrPostProcess.process(model.tokenizer.decode(ids, 0, ids.size))
            }
        }
    }

    /**
     * Greedy decoding: run the decoder over the tokens so far, take the most likely next one, and
     * stop at [EOS_TOKEN] or [MAX_TOKENS]. The start token is in the array it returns, where the
     * tokenizer drops it as special text.
     */
    private suspend fun decode(model: Loaded, hidden: OnnxTensor): LongArray {
        val ids = LongArray(MAX_TOKENS)
        ids[0] = START_TOKEN
        var length = 1
        while (length < MAX_TOKENS) {
            coroutineContext.ensureActive()
            val next = nextToken(model, hidden, ids, length) ?: break
            if (next == EOS_TOKEN) break
            ids[length] = next
            length++
        }
        return ids.copyOf(length)
    }

    /**
     * The most likely token after the [length] tokens in [ids], read off the decoder's logits for
     * the last position. Null when the graph answered with no logits.
     */
    private fun nextToken(model: Loaded, hidden: OnnxTensor, ids: LongArray, length: Int): Long? {
        val inputs = mapOf(
            DECODER_INPUT_IDS to OnnxTensor.createTensor(
                model.environment,
                LongBuffer.wrap(ids, 0, length),
                longArrayOf(1, length.toLong())
            ),
            DECODER_ENCODER_STATES to hidden
        )
        val inputIds = inputs.getValue(DECODER_INPUT_IDS)
        inputIds.use {
            model.decoder.run(inputs).use { decoded ->
                val logits = decoded.get(DECODER_LOGITS).orElse(null) as? OnnxTensor ?: return null
                val vocabSize = logits.info.shape.last().toInt()
                val buffer = logits.floatBuffer
                val base = (length - 1) * vocabSize
                var best = 0
                var bestScore = buffer.get(base)
                for (token in 1 until vocabSize) {
                    val score = buffer.get(base + token)
                    if (score > bestScore) {
                        bestScore = score
                        best = token
                    }
                }
                return best.toLong()
            }
        }
    }

    /**
     * The loaded sessions, built from `filesDir/manga-ocr/` when they are not already, or when the
     * files behind them changed (a re-imported or removed model). Null when the model is not
     * installed, or when it cannot be loaded at all.
     */
    private fun load(context: Context): Loaded? {
        val encoderFile = MangaOcrModel.file(context, MangaOcrModel.Slot.ENCODER)
        val decoderFile = MangaOcrModel.file(context, MangaOcrModel.Slot.DECODER)
        val vocabFile = MangaOcrModel.file(context, MangaOcrModel.Slot.VOCAB)
        if (!encoderFile.isFile || !decoderFile.isFile || !vocabFile.isFile) {
            close()
            return null
        }

        val key = "${stamp(encoderFile)}:${stamp(decoderFile)}:${stamp(vocabFile)}"
        loaded?.let { if (it.key == key) return it }
        close()

        return try {
            val environment = OrtEnvironment.getEnvironment()
            // Avoid retaining inference allocation caches between lookups. This can also make
            // warm inference slower; session reuse still avoids rebuilding the model each time.
            OrtSession.SessionOptions().use { sessionOptions ->
                sessionOptions.setCPUArenaAllocator(false)
                sessionOptions.setMemoryPatternOptimization(false)
                val tokenizer = MangaOcrTokenizer.load(vocabFile)
                var encoder: OrtSession? = null
                var decoder: OrtSession? = null
                try {
                    encoder = environment.createSession(encoderFile.path, sessionOptions)
                    decoder = environment.createSession(decoderFile.path, sessionOptions)
                    Loaded(key, environment, encoder, decoder, tokenizer).also { loaded = it }
                } catch (e: Throwable) {
                    for (session in listOfNotNull(encoder, decoder)) {
                        try { session.close() } catch (closeError: Exception) { e.addSuppressed(closeError) }
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot load the manga-ocr model", e)
            null
        }
    }

    /** Path, size and modification time: enough to tell one import of a file from the next. */
    private fun stamp(file: File): String = "${file.path}:${file.length()}:${file.lastModified()}"

    /** Drops the sessions. The environment is the process-wide singleton and stays open. */
    private fun close() {
        val dropped = loaded ?: return
        loaded = null
        try {
            dropped.encoder.close()
        } finally {
            dropped.decoder.close()
        }
    }

    /**
     * Drops the sessions so the ~87 MB the encoder holds goes back to the system; the next
     * [recognize] builds the pair again. [OcrModels] calls this once the models have been idle for a
     * while, and when the system is short on memory. It waits on [mutex], so a call already inside
     * [recognize] keeps sessions that are still open until it is done. True when a pair was dropped.
     */
    suspend fun release(): Boolean = mutex.withLock {
        val had = loaded != null
        close()
        had
    }
}

/**
 * The model's tokenizer, as far as decoding needs it: a character-level vocabulary where every token
 * is one character and the first five entries are the special tokens.
 *
 * `vocab.txt` (from kha-white/manga-ocr-base) has no "##" continuations, but the WordPiece prefix is
 * stripped anyway so a vocabulary that has them decodes the same way `BertJapaneseTokenizer.decode`
 * does. Joining without spaces is what manga-ocr's own `post_process` arrives at, since it removes
 * all whitespace from the decoded string.
 */
internal class MangaOcrTokenizer(private val vocab: List<String>) {

    /**
     * The text of `[offset, offset + length)` in [ids]: special tokens dropped, "##" prefixes
     * stripped, everything else concatenated. Ids outside the vocabulary are dropped too.
     */
    fun decode(ids: LongArray, offset: Int, length: Int): String {
        val text = StringBuilder(length)
        for (index in offset until offset + length) {
            val id = ids[index].toInt()
            if (id < 0 || id >= vocab.size) continue
            val token = vocab[id]
            if (token in SPECIAL_TOKENS) continue
            if (token.startsWith(WORDPIECE_PREFIX)) {
                text.append(token, WORDPIECE_PREFIX.length, token.length)
            } else {
                text.append(token)
            }
        }
        return text.toString()
    }

    companion object {
        private const val WORDPIECE_PREFIX = "##"

        /** The five entries `vocab.txt` starts with, dropped the way `skip_special_tokens` drops them. */
        private val SPECIAL_TOKENS = setOf("[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]")

        /** Reads a `vocab.txt`; the id of a token is its line number. */
        fun load(file: File): MangaOcrTokenizer = MangaOcrTokenizer(file.readLines())
    }
}

/**
 * manga-ocr's own `post_process`, which the model's raw output needs before it reads as Japanese:
 * whitespace removed, the ellipsis written as three dots, runs of dots and middle dots normalized
 * to that same count, and half-width ASCII widened to full width (jaconv's `h2z(ascii=True,
 * digit=True)`). Kept here rather than folded into the engine so it can be tested on its own.
 */
internal object MangaOcrPostProcess {

    /** [text] as the reader should see it. */
    fun process(text: String): String = widenAscii(normalizeDots(removeWhitespace(text)))

    /** manga-ocr starts by splitting on whitespace, so the widening below never sees a space. */
    private fun removeWhitespace(text: String): String = text.filterNot { it.isWhitespace() }

    /**
     * The ellipsis spelled as dots, then every run of two or more dots or middle dots collapsed to
     * that many ASCII dots — manga-ocr's `replace("…", "...")` followed by `re.sub("[・.]{2,}")`.
     * A single ・ is a real character and survives.
     */
    private fun normalizeDots(text: String): String {
        val spelled = text.replace("…", "...")
        val builder = StringBuilder(spelled.length)
        var index = 0
        while (index < spelled.length) {
            val char = spelled[index]
            if (char != '・' && char != '.') {
                builder.append(char)
                index++
                continue
            }
            var end = index
            while (end < spelled.length && (spelled[end] == '・' || spelled[end] == '.')) end++
            if (end - index < 2) builder.append(char) else repeat(end - index) { builder.append('.') }
            index = end
        }
        return builder.toString()
    }

    /** Half-width ASCII (`!` to `~`) widened to full width, digits included: jaconv's H2Z_AD map. */
    private fun widenAscii(text: String): String = text.map { char ->
        if (char.code in 0x21..0x7E) (char.code + 0xFEE0).toChar() else char
    }.joinToString("")
}
